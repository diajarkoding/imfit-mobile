-- Local-first F1b lifecycle. Shared-project deployment requires separate approval.
set lock_timeout = '5s';
set statement_timeout = '30s';

alter table public.active_sessions
  add column version bigint check (version > 0),
  add column schema_version integer check (schema_version = 1),
  add column status text not null default 'ACTIVE'
    check (status in ('ACTIVE','PAUSED','COMPLETED','CANCELLED','EXPIRED')),
  add column review_required boolean not null default true,
  add column template_revision bigint,
  add column paused_at timestamptz,
  add column total_paused_ms bigint not null default 0 check (total_paused_ms >= 0),
  add column completed_workout_id uuid;
-- Old JSON is deliberately NOT promoted to a validated v1 snapshot.
alter table public.active_sessions drop constraint active_sessions_user_id_key;
create unique index active_sessions_one_running on public.active_sessions(user_id)
  where status in ('ACTIVE','PAUSED');
alter table public.active_sessions alter column template_id drop not null;
alter table public.active_sessions drop constraint active_sessions_template_id_fkey;
alter table public.active_sessions add constraint active_sessions_template_id_fkey
  foreign key(template_id) references public.workout_templates(id) on delete set null;
alter table public.workout_logs add column source_session_id uuid unique
  references public.active_sessions(id) deferrable initially deferred;
alter table public.active_sessions add constraint active_sessions_workout_fkey
  foreign key(completed_workout_id) references public.workout_logs(id) deferrable initially deferred;
alter table public.exercise_logs add column input_defaults jsonb;
alter table public.exercise_logs add constraint exercise_logs_parent_identity
  unique(id,workout_log_id,exercise_id);
alter table public.workout_sets add constraint workout_sets_parent_identity
  foreign key(exercise_log_id,workout_log_id,exercise_id)
  references public.exercise_logs(id,workout_log_id,exercise_id) not valid;
-- Invalid old rows cause a review failure, never a data-deleting repair.
alter table public.workout_sets validate constraint workout_sets_parent_identity;
alter table public.workout_sets add constraint workout_sets_complete_parent_pair
  check ((workout_log_id is null) = (exercise_id is null)) not valid;
alter table public.workout_sets validate constraint workout_sets_complete_parent_pair;

create function imfit_private.json_int(p_value jsonb, p_min integer, p_max integer)
returns integer language plpgsql immutable set search_path = '' as $$
begin
  if jsonb_typeof(p_value) is distinct from 'number'
     or p_value::text !~ '^(0|[1-9][0-9]*)$' then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if p_value::text::numeric not between p_min and p_max then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  return p_value::text::integer;
end;
$$;

create function imfit_private.json_uuid(p_value jsonb)
returns uuid language plpgsql immutable set search_path = '' as $$
begin
  if jsonb_typeof(p_value) is distinct from 'string' then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  begin return (p_value#>>'{}')::uuid;
  exception when invalid_text_representation then perform imfit_private.fail('PT400','VALIDATION'); end;
end;
$$;

create function imfit_private.weight_text(p_value jsonb)
returns text language plpgsql immutable set search_path = '' as $$
declare v_text text := p_value#>>'{}';
begin
  if jsonb_typeof(p_value) is distinct from 'string'
     or length(v_text) > 7 or v_text !~ '^(0|[1-9][0-9]{0,3})(\.[0-9]{1,2})?$' then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if v_text::numeric > 1000 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  return to_char(v_text::numeric,'FM9990.00');
end;
$$;

create function imfit_private.prefill(p_owner uuid, p_exercise text, p_defaults jsonb)
returns jsonb language plpgsql stable set search_path = '' as $$
declare
  v_sets integer; v_reps integer; v_log public.exercise_logs%rowtype;
  v_rows jsonb; v_source text := 'DEFAULTS'; v_workout uuid;
begin
  if p_exercise is null or not exists(select 1 from public.exercises where id=p_exercise and is_active)
     or jsonb_typeof(p_defaults) is distinct from 'object'
     or (p_defaults - array['sets','reps']) <> '{}'::jsonb then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  v_sets := imfit_private.json_int(p_defaults->'sets',1,20);
  v_reps := imfit_private.json_int(p_defaults->'reps',1,100);
  select w.id into v_workout from public.workout_logs w
  where w.user_id=p_owner and w.deleted_at is null and exists (
    select 1 from public.exercise_logs e join public.workout_sets s on s.exercise_log_id=e.id
    where e.workout_log_id=w.id and e.exercise_id=p_exercise and s.is_completed)
  order by w.end_time desc,w.id desc limit 1;
  if v_workout is not null then
    if (select count(*) from public.exercise_logs where workout_log_id=v_workout and exercise_id=p_exercise) <> 1 then
      perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED');
    end if;
    select * into v_log from public.exercise_logs where workout_log_id=v_workout and exercise_id=p_exercise;
    if v_log.input_defaults = p_defaults then
      if exists(select 1 from public.workout_sets where exercise_log_id=v_log.id
        and is_completed and set_number <= v_sets and scale(weight)>2) then
        perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED');
      end if;
      v_source := 'HISTORY';
    end if;
  end if;
  select jsonb_agg(jsonb_build_object('set_number', n,
    'weight',case when s.id is null then '0.00' else to_char(s.weight,'FM9990.00') end,
    'reps',coalesce(s.reps,v_reps)) order by n) into v_rows
  from generate_series(1,v_sets) n
  left join public.workout_sets s on v_source='HISTORY' and s.exercise_log_id=v_log.id
    and s.set_number=n and s.is_completed;
  return jsonb_build_object('source',v_source,'source_workout_id',
    case when v_source='HISTORY' then v_workout end,'input_defaults',p_defaults,'sets',v_rows);
end;
$$;

create function imfit_private.new_session_exercise(
  p_owner uuid, p_id uuid, p_exercise text, p_order integer, p_defaults jsonb, p_rest integer
) returns jsonb language plpgsql stable set search_path = '' as $$
declare v_prefill jsonb; v_sets jsonb; v_name text; v_category text;
begin
  v_prefill := imfit_private.prefill(p_owner,p_exercise,p_defaults);
  select e.name,c.display_name into v_name,v_category from public.exercises e
  join public.muscle_categories c on c.id=e.muscle_category_id where e.id=p_exercise and e.is_active;
  if not found then perform imfit_private.fail('PT400','VALIDATION'); end if;
  select jsonb_agg(s || jsonb_build_object('id',md5(p_id::text || ':' || (s->>'set_number'))::uuid,
    'is_completed',false) order by (s->>'set_number')::integer) into v_sets
  from jsonb_array_elements(v_prefill->'sets') s;
  return jsonb_build_object('id',p_id,'exercise_id',p_exercise,'name',v_name,'muscle_category',v_category,
    'order_index',p_order,'rest_seconds',p_rest,'input_defaults',p_defaults,'sets',v_sets,
    'max_set_number',jsonb_array_length(v_sets));
end;
$$;

create function imfit_private.session_snapshot(p_row public.active_sessions)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('id',p_row.id,'version',p_row.version,'status',p_row.status,
    'effective_status',case when p_row.status in ('ACTIVE','PAUSED') and p_row.expires_at<=statement_timestamp()
      then 'EXPIRED' else p_row.status end,
    'review_required',p_row.review_required or
      (p_row.status in ('ACTIVE','PAUSED') and p_row.expires_at<=statement_timestamp()),
    'template_id',p_row.template_id,'template_name',p_row.template_name,'template_revision',p_row.template_revision,
    'started_at',p_row.start_time,'paused_at',p_row.paused_at,'total_paused_ms',p_row.total_paused_ms,
    'last_activity_at',p_row.last_activity_at,'expires_at',p_row.expires_at,'schema_version',p_row.schema_version,
    'current_exercise_id',p_row.session_data->'current_exercise_id','exercises',p_row.session_data->'exercises',
    'completed_workout_id',p_row.completed_workout_id);
$$;

create function imfit_private.session_receipt(
  p_row public.active_sessions, p_request uuid, p_operation text
) returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('request_id',p_request,'operation',p_operation,'session_id',p_row.id,
    'version',p_row.version,'status',p_row.status,'committed_at',statement_timestamp(),
    'snapshot',imfit_private.session_snapshot(p_row)) ||
    case when p_row.status='COMPLETED' then jsonb_build_object('workout_id',p_row.completed_workout_id) else '{}'::jsonb end;
$$;

create function public.create_active_session(
  p_request_id uuid, p_session_id uuid, p_template_id uuid, p_expected_template_revision bigint
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := imfit_private.require_owner(); v_payload jsonb; v_result jsonb;
  v_template public.workout_templates%rowtype; v_row public.active_sessions%rowtype;
  v_child public.template_exercises%rowtype; v_exercises jsonb := '[]'; v_exercise jsonb;
  v_id uuid; v_now timestamptz := clock_timestamp(); v_data jsonb;
begin
  v_payload := jsonb_build_object('template_id',p_template_id,'expected_revision',p_expected_template_revision);
  v_result := imfit_private.request_replay(v_owner,p_request_id,'create_active_session',p_session_id,v_payload);
  if v_result is not null then return v_result; end if;
  if p_template_id is null or p_expected_template_revision is null or p_expected_template_revision<0 then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if exists(select 1 from public.active_sessions where user_id=v_owner
    and (version is null or schema_version is null)) then
    perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED');
  end if;
  if exists(select 1 from public.active_sessions where user_id=v_owner
    and (status in ('ACTIVE','PAUSED') or (status='EXPIRED' and review_required))) then
    perform imfit_private.fail('PT409','SESSION_REVIEW_REQUIRED');
  end if;
  if exists(select 1 from public.active_sessions where id=p_session_id) then
    perform imfit_private.fail('PT409','STATE_CONFLICT');
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_template_id::text,1));
  select * into v_template from public.workout_templates
    where id=p_template_id and user_id=v_owner and not is_deleted for update;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if v_template.revision <> p_expected_template_revision then
    perform imfit_private.fail('PT409','REVISION_CONFLICT');
  end if;
  if (select count(*) from public.template_exercises where template_id=p_template_id) not between 1 and 100
     or exists(select 1 from public.template_exercises where template_id=p_template_id
       group by exercise_id having count(*)>1) then
    perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED');
  end if;
  for v_child in select * from public.template_exercises where template_id=p_template_id order by order_index loop
    v_id := md5(p_session_id::text || ':' || v_child.id::text)::uuid;
    v_exercise := imfit_private.new_session_exercise(v_owner,v_id,v_child.exercise_id,
      jsonb_array_length(v_exercises),jsonb_build_object('sets',v_child.sets,'reps',v_child.reps),v_child.rest_seconds);
    v_exercises := v_exercises || jsonb_build_array(v_exercise);
  end loop;
  v_data := jsonb_build_object('exercises',v_exercises,'current_exercise_id',v_exercises#>'{0,id}',
    'used_exercise_ids',(select jsonb_agg(e->'id') from jsonb_array_elements(v_exercises) e),
    'used_set_ids',(select jsonb_agg(s->'id') from jsonb_array_elements(v_exercises) e,
      lateral jsonb_array_elements(e->'sets') s));
  if octet_length(v_data::text)>262144 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  insert into public.active_sessions(id,user_id,template_id,template_name,start_time,session_data,
    last_activity_at,expires_at,version,schema_version,status,review_required,template_revision)
  values(p_session_id,v_owner,p_template_id,v_template.name,v_now,v_data,v_now,v_now+interval '30 days',
    1,1,'ACTIVE',false,v_template.revision) returning * into v_row;
  v_result := imfit_private.session_receipt(v_row,p_request_id,'create_active_session');
  perform imfit_private.record_operation(v_owner,p_request_id,'create_active_session','SESSION',p_session_id,v_payload,v_result);
  return v_result;
end;
$$;

create function public.get_active_session(p_session_id uuid default null)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid := imfit_private.require_owner(); v_row public.active_sessions%rowtype;
begin
  select * into v_row from public.active_sessions where user_id=v_owner
    and ((p_session_id is not null and id=p_session_id) or (p_session_id is null
      and (status in ('ACTIVE','PAUSED') or (status='EXPIRED' and review_required)
        or version is null or schema_version is null)))
    order by last_activity_at desc,id desc limit 1;
  if not found then return jsonb_build_object('found',false,'session',null); end if;
  if v_row.version is null or v_row.schema_version is null then
    perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED');
  end if;
  return jsonb_build_object('found',true,'session',imfit_private.session_snapshot(v_row));
end;
$$;

create function public.update_active_session(
  p_request_id uuid, p_session_id uuid, p_expected_version bigint, p_command jsonb
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := imfit_private.require_owner(); v_payload jsonb; v_result jsonb;
  v_command jsonb := p_command; v_type text; v_allowed text[];
  v_row public.active_sessions%rowtype; v_data jsonb; v_exercises jsonb;
  v_exercise jsonb; v_sets jsonb; v_set jsonb; v_eid uuid; v_sid uuid;
  v_e integer; v_s integer; v_int integer; v_now timestamptz := clock_timestamp();
begin
  -- Normalize valid numeric text before identity comparison, never round input.
  if jsonb_typeof(v_command)='object' and v_command ? 'weight' then
    begin v_command := jsonb_set(v_command,'{weight}',to_jsonb(imfit_private.weight_text(v_command->'weight')));
    exception when sqlstate 'PT400' then null; end;
  end if;
  v_payload := jsonb_build_object('expected_version',p_expected_version,'command',v_command);
  v_result := imfit_private.request_replay(v_owner,p_request_id,'update_active_session',p_session_id,v_payload);
  if v_result is not null then return v_result; end if;
  if p_expected_version is null or p_expected_version<1 or jsonb_typeof(v_command) is distinct from 'object'
     or jsonb_typeof(v_command->'type') is distinct from 'string' or octet_length(v_payload::text)>262144 then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  v_type := v_command->>'type';
  v_allowed := case v_type
    when 'edit_set' then array['type','exercise_log_id','set_id','weight','reps']
    when 'complete_set' then array['type','exercise_log_id','set_id']
    when 'uncomplete_set' then array['type','exercise_log_id','set_id']
    when 'add_set' then array['type','exercise_log_id','set_id']
    when 'remove_set' then array['type','exercise_log_id','set_id']
    when 'add_exercise' then array['type','exercise_log_id','exercise_id']
    when 'remove_exercise' then array['type','exercise_log_id']
    when 'set_rest_seconds' then array['type','exercise_log_id','rest_seconds']
    when 'select_exercise' then array['type','exercise_log_id']
    when 'pause' then array['type'] when 'resume' then array['type']
    when 'acknowledge_expiry' then array['type'] else null end;
  if v_allowed is null or not (v_command ?& v_allowed) or (v_command-v_allowed)<>'{}'::jsonb then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  select * into v_row from public.active_sessions where id=p_session_id and user_id=v_owner for update;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  if v_row.version<>p_expected_version then
    perform imfit_private.fail('PT409','VERSION_CONFLICT',jsonb_build_object('current_version',v_row.version));
  end if;
  if v_row.status='EXPIRED' or (v_row.status in ('ACTIVE','PAUSED') and v_row.expires_at<=v_now) then
    if v_type<>'acknowledge_expiry' or (v_row.status='EXPIRED' and not v_row.review_required) then
      perform imfit_private.fail('PT409','SESSION_REVIEW_REQUIRED');
    end if;
    update public.active_sessions set status='EXPIRED',review_required=false,version=version+1
      where id=p_session_id returning * into v_row;
  else
    if v_row.status not in ('ACTIVE','PAUSED') then perform imfit_private.fail('PT409','SESSION_TERMINAL'); end if;
    v_data := v_row.session_data; v_exercises := v_data->'exercises';
    if v_type='pause' then
      if v_row.status<>'ACTIVE' then perform imfit_private.fail('PT409','STATE_CONFLICT'); end if;
      v_row.status := 'PAUSED'; v_row.paused_at := v_now;
    elsif v_type='resume' then
      if v_row.status<>'PAUSED' then perform imfit_private.fail('PT409','STATE_CONFLICT'); end if;
      v_row.status := 'ACTIVE';
      v_row.total_paused_ms := v_row.total_paused_ms+greatest(0,floor(extract(epoch from (v_now-v_row.paused_at))*1000)::bigint);
      v_row.paused_at := null;
    elsif v_type='acknowledge_expiry' then
      perform imfit_private.fail('PT409','STATE_CONFLICT');
    else
      v_eid := imfit_private.json_uuid(v_command->'exercise_log_id');
      select (ord-1)::integer,e into v_e,v_exercise
      from jsonb_array_elements(v_exercises) with ordinality a(e,ord) where e->>'id'=v_eid::text;
      if v_type='add_exercise' then
        if v_e is not null or v_data->'used_exercise_ids' ? v_eid::text
           or jsonb_array_length(v_exercises)>=100
           or jsonb_typeof(v_command->'exercise_id') is distinct from 'string'
           or exists(select 1 from jsonb_array_elements(v_exercises) e where e->>'exercise_id'=v_command->>'exercise_id') then
          perform imfit_private.fail('PT400','VALIDATION');
        end if;
        v_exercise := imfit_private.new_session_exercise(v_owner,v_eid,v_command->>'exercise_id',
          jsonb_array_length(v_exercises),'{"sets":3,"reps":10}',60);
        v_exercises := v_exercises || jsonb_build_array(v_exercise);
        v_data := jsonb_set(v_data,'{used_exercise_ids}',(v_data->'used_exercise_ids')||to_jsonb(v_eid::text));
        v_data := jsonb_set(v_data,'{used_set_ids}',(v_data->'used_set_ids')||
          (select jsonb_agg(s->'id') from jsonb_array_elements(v_exercise->'sets') s));
        if jsonb_array_length(v_exercises)=1 then v_data:=jsonb_set(v_data,'{current_exercise_id}',to_jsonb(v_eid::text)); end if;
      else
        if v_e is null then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
        v_sets := v_exercise->'sets';
        if v_type='remove_exercise' then
          if exists(select 1 from jsonb_array_elements(v_sets) s where (s->>'is_completed')::boolean) then
            perform imfit_private.fail('PT409','STATE_CONFLICT');
          end if;
          v_exercises := v_exercises-v_e;
          select coalesce(jsonb_agg(jsonb_set(e,'{order_index}',to_jsonb(ord-1)) order by ord),'[]') into v_exercises
          from jsonb_array_elements(v_exercises) with ordinality a(e,ord);
          if v_data->>'current_exercise_id'=v_eid::text then
            v_data:=jsonb_set(v_data,'{current_exercise_id}',coalesce(v_exercises#>'{0,id}','null'));
          end if;
        elsif v_type='set_rest_seconds' then
          v_int:=imfit_private.json_int(v_command->'rest_seconds',0,600);
          v_exercises:=jsonb_set(v_exercises,array[v_e::text,'rest_seconds'],to_jsonb(v_int));
        elsif v_type='select_exercise' then
          v_data:=jsonb_set(v_data,'{current_exercise_id}',to_jsonb(v_eid::text));
        else
          v_sid:=imfit_private.json_uuid(v_command->'set_id');
          select (ord-1)::integer,s into v_s,v_set from jsonb_array_elements(v_sets) with ordinality a(s,ord)
            where s->>'id'=v_sid::text;
          if v_type='add_set' then
            if v_s is not null or v_data->'used_set_ids' ? v_sid::text or jsonb_array_length(v_sets)>=20 then
              perform imfit_private.fail('PT400','VALIDATION');
            end if;
            v_int:=(v_exercise->>'max_set_number')::integer;
            if v_int=2147483647 then perform imfit_private.fail('PT400','VALIDATION'); end if;
            v_int:=v_int+1;
            v_sets:=v_sets||jsonb_build_array(jsonb_build_object('id',v_sid,'set_number',v_int,
              'weight','0.00','reps',(v_exercise#>>'{input_defaults,reps}')::integer,'is_completed',false));
            v_exercise:=jsonb_set(v_exercise,'{max_set_number}',to_jsonb(v_int));
            v_data:=jsonb_set(v_data,'{used_set_ids}',(v_data->'used_set_ids')||to_jsonb(v_sid::text));
          else
            if v_s is null then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
            if v_type in ('edit_set','remove_set') and (v_set->>'is_completed')::boolean then
              perform imfit_private.fail('PT409','STATE_CONFLICT');
            end if;
            if v_type='edit_set' then
              v_int:=imfit_private.json_int(v_command->'reps',0,1000);
              v_set:=v_set||jsonb_build_object('weight',imfit_private.weight_text(v_command->'weight'),'reps',v_int);
            elsif v_type='complete_set' then
              if (v_set->>'weight')::numeric<=0 or (v_set->>'reps')::integer<=0 then
                perform imfit_private.fail('PT400','VALIDATION');
              end if;
              v_set:=jsonb_set(v_set,'{is_completed}','true');
            elsif v_type='uncomplete_set' then v_set:=jsonb_set(v_set,'{is_completed}','false');
            end if;
            if v_type='remove_set' then v_sets:=v_sets-v_s;
            else v_sets:=jsonb_set(v_sets,array[v_s::text],v_set); end if;
          end if;
          v_exercise:=jsonb_set(v_exercise,'{sets}',v_sets);
          v_exercises:=jsonb_set(v_exercises,array[v_e::text],v_exercise);
        end if;
      end if;
    end if;
    v_data:=jsonb_set(v_data,'{exercises}',v_exercises);
    if octet_length(v_data::text)>262144 then perform imfit_private.fail('PT400','VALIDATION'); end if;
    update public.active_sessions set session_data=v_data,status=v_row.status,paused_at=v_row.paused_at,
      total_paused_ms=v_row.total_paused_ms,version=version+1,last_activity_at=v_now,expires_at=v_now+interval '30 days'
      where id=p_session_id returning * into v_row;
  end if;
  v_result:=imfit_private.session_receipt(v_row,p_request_id,'update_active_session');
  perform imfit_private.record_operation(v_owner,p_request_id,'update_active_session','SESSION',p_session_id,v_payload,v_result);
  return v_result;
end;
$$;

create function imfit_private.terminal_session(
  p_request uuid,p_session uuid,p_version bigint,p_finish boolean
) returns jsonb language plpgsql set search_path = '' as $$
declare
  v_owner uuid:=imfit_private.require_owner(); v_payload jsonb; v_result jsonb;
  v_operation text:=case when p_finish then 'finish_active_session' else 'cancel_active_session' end;
  v_status text:=case when p_finish then 'COMPLETED' else 'CANCELLED' end;
  v_row public.active_sessions%rowtype; v_now timestamptz:=clock_timestamp();
  v_workout uuid; v_e jsonb; v_s jsonb; v_volume numeric; v_sets integer; v_reps integer;
begin
  v_payload:=jsonb_build_object('expected_version',p_version);
  v_result:=imfit_private.request_replay(v_owner,p_request,v_operation,p_session,v_payload);
  if v_result is not null then return v_result; end if;
  if p_version is null or p_version<1 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  select * into v_row from public.active_sessions where id=p_session and user_id=v_owner for update;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  if v_row.status<>v_status then
    if v_row.status in ('COMPLETED','CANCELLED') then
      perform imfit_private.fail('PT409','SESSION_TERMINAL',jsonb_build_object(
        'terminal_status',v_row.status,'workout_id',v_row.completed_workout_id));
    end if;
    if v_row.status='EXPIRED' or v_row.expires_at<=v_now then perform imfit_private.fail('PT409','SESSION_REVIEW_REQUIRED'); end if;
    if v_row.version<>p_version then perform imfit_private.fail('PT409','VERSION_CONFLICT',jsonb_build_object('current_version',v_row.version)); end if;
    if p_finish then
      select count(*)::integer,coalesce(sum((s->>'weight')::numeric*(s->>'reps')::integer),0),
        coalesce(sum((s->>'reps')::integer),0)::integer into v_sets,v_volume,v_reps
      from jsonb_array_elements(v_row.session_data->'exercises') e,
        lateral jsonb_array_elements(e->'sets') s where (s->>'is_completed')::boolean;
      if v_sets=0 or exists(select 1 from jsonb_array_elements(v_row.session_data->'exercises') e,
        lateral jsonb_array_elements(e->'sets') s where (s->>'is_completed')::boolean
        and ((s->>'weight')::numeric<=0 or (s->>'reps')::integer<=0)) then
        perform imfit_private.fail('PT400','VALIDATION');
      end if;
      v_workout:=md5(p_session::text||':workout')::uuid;
      insert into public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,
        total_volume,total_sets,total_reps,source_session_id)
      values(v_workout,v_owner,v_row.template_id,v_row.template_name,(v_row.start_time at time zone 'UTC')::date,
        v_row.start_time,greatest(v_now,v_row.start_time+interval '1 microsecond'),v_volume,v_sets,v_reps,p_session);
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        if not exists(select 1 from jsonb_array_elements(v_e->'sets') s where (s->>'is_completed')::boolean) then continue; end if;
        select count(*)::integer,sum((s->>'weight')::numeric*(s->>'reps')::integer),sum((s->>'reps')::integer)::integer
          into v_sets,v_volume,v_reps from jsonb_array_elements(v_e->'sets') s where (s->>'is_completed')::boolean;
        insert into public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,
          order_index,total_volume,total_sets,total_reps,input_defaults)
        values((v_e->>'id')::uuid,v_workout,v_e->>'exercise_id',v_e->>'name',v_e->>'muscle_category',
          (v_e->>'order_index')::integer,v_volume,v_sets,v_reps,v_e->'input_defaults');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          insert into public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed)
          values((v_s->>'id')::uuid,(v_e->>'id')::uuid,v_workout,v_e->>'exercise_id',
            (v_s->>'set_number')::integer,(v_s->>'weight')::numeric,(v_s->>'reps')::integer,true);
        end loop;
      end loop;
    end if;
    update public.active_sessions set status=v_status,version=version+1,review_required=false,
      completed_workout_id=v_workout where id=p_session returning * into v_row;
  end if;
  v_result:=imfit_private.session_receipt(v_row,p_request,v_operation);
  perform imfit_private.record_operation(v_owner,p_request,v_operation,'SESSION',p_session,v_payload,v_result);
  return v_result;
end;
$$;

create function public.finish_active_session(p_request_id uuid,p_session_id uuid,p_expected_version bigint)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  return imfit_private.terminal_session(p_request_id,p_session_id,p_expected_version,true);
exception when unique_violation or foreign_key_violation or check_violation then
  -- The exception subtransaction rolls back the entire aggregate, not just a set.
  perform imfit_private.fail('PT409','STATE_CONFLICT');
end;
$$;
create function public.cancel_active_session(p_request_id uuid,p_session_id uuid,p_expected_version bigint)
returns jsonb language sql security definer set search_path = '' as $$
  select imfit_private.terminal_session(p_request_id,p_session_id,p_expected_version,false);
$$;

revoke all on function public.create_active_session(uuid,uuid,uuid,bigint) from public,anon;
revoke all on function public.get_active_session(uuid) from public,anon;
revoke all on function public.update_active_session(uuid,uuid,bigint,jsonb) from public,anon;
revoke all on function public.finish_active_session(uuid,uuid,bigint) from public,anon;
revoke all on function public.cancel_active_session(uuid,uuid,bigint) from public,anon;
grant execute on function public.create_active_session(uuid,uuid,uuid,bigint) to authenticated;
grant execute on function public.get_active_session(uuid) to authenticated;
grant execute on function public.update_active_session(uuid,uuid,bigint,jsonb) to authenticated;
grant execute on function public.finish_active_session(uuid,uuid,bigint) to authenticated;
grant execute on function public.cancel_active_session(uuid,uuid,bigint) to authenticated;
revoke all on all functions in schema imfit_private from public,anon,authenticated;
notify pgrst,'reload schema';
