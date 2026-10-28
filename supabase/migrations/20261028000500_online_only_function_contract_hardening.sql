-- Follow-up to locally observed lint findings; do not rewrite applied migrations.
set lock_timeout='5s';
set statement_timeout='30s';
alter function imfit_private.fail(text,text,jsonb) immutable;
alter function imfit_private.cursor_encode(jsonb,jsonb) stable;
alter function imfit_private.cursor_decode(text,jsonb) stable;
alter function imfit_private.weight_text(jsonb) stable;
alter function imfit_private.page_result(jsonb,jsonb,integer) stable;

create or replace function imfit_private.json_uuid(p_value jsonb)
returns uuid language plpgsql immutable set search_path = '' as $$
begin
  if jsonb_typeof(p_value) is distinct from 'string' then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  begin return (p_value#>>'{}')::uuid;
  exception when invalid_text_representation then
    perform imfit_private.fail('PT400','VALIDATION');
    return null; -- Unreachable after fail; explicit for static control-flow analysis.
  end;
end;
$$;

create or replace function imfit_private.typed_cursor(p_cursor text,p_query jsonb,p_timestamp boolean)
returns jsonb language plpgsql stable set search_path = '' as $$
declare v_key jsonb; v_time timestamptz; v_id uuid;
begin
  v_key:=imfit_private.cursor_decode(p_cursor,p_query);
  if v_key is null then return null; end if;
  if jsonb_array_length(v_key)<>2 or jsonb_typeof(v_key->0) is distinct from 'string'
    or jsonb_typeof(v_key->1) is distinct from 'string' then perform imfit_private.fail('PT400','INVALID_CURSOR'); end if;
  if p_timestamp then
    begin v_time:=(v_key->>0)::timestamptz; v_id:=(v_key->>1)::uuid;
    exception when invalid_text_representation or invalid_datetime_format or datetime_field_overflow then
      perform imfit_private.fail('PT400','INVALID_CURSOR');
    end;
    if v_time is null or not isfinite(v_time) or v_id is null then
      perform imfit_private.fail('PT400','INVALID_CURSOR');
    end if;
  end if;
  return v_key;
end;
$$;

create or replace function public.finish_active_session(p_request_id uuid,p_session_id uuid,p_expected_version bigint)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  return imfit_private.terminal_session(p_request_id,p_session_id,p_expected_version,true);
exception when unique_violation or foreign_key_violation or check_violation then
  perform imfit_private.fail('PT409','STATE_CONFLICT');
  return null; -- fail always raises; no successful null receipt.
end;
$$;

create or replace function public.create_active_session(
  p_request_id uuid, p_session_id uuid, p_template_id uuid, p_expected_template_revision bigint
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := imfit_private.require_owner(); v_payload jsonb; v_result jsonb;
  v_template public.workout_templates%rowtype; v_row public.active_sessions%rowtype;
  v_child public.template_exercises%rowtype; v_exercises jsonb := '[]'::jsonb; v_exercise jsonb;
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

revoke all on all functions in schema imfit_private from public,anon,authenticated;
notify pgrst,'reload schema';
