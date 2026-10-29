-- Additive online-only legacy recovery. No historical writer is reopened.
set lock_timeout = '5s';
set statement_timeout = '30s';

alter table imfit_private.operation_receipts
  drop constraint operation_receipts_resource_type_check;
alter table imfit_private.operation_receipts add constraint operation_receipts_resource_type_check
  check (resource_type in ('TEMPLATE','SESSION','WORKOUT'));

create table imfit_private.legacy_session_reviews (
  user_id uuid not null references auth.users(id) on delete cascade,
  session_id uuid not null references public.active_sessions(id),
  source_fingerprint text not null check (source_fingerprint ~ '^[0-9a-f]{64}$'),
  raw_source jsonb not null,
  request_id uuid not null,
  reviewed_at timestamptz not null default statement_timestamp(),
  primary key (user_id,session_id)
);
create table imfit_private.legacy_workout_imports (
  user_id uuid not null references auth.users(id) on delete cascade,
  source_schema_version integer not null check (source_schema_version in (6,7,9)),
  source_id uuid not null,
  mapping_version integer not null check (mapping_version=1),
  source_fingerprint text not null check (source_fingerprint ~ '^[0-9a-f]{64}$'),
  canonical_payload jsonb not null,
  payload_digest text not null,
  workout_id uuid not null references public.workout_logs(id),
  id_mapping jsonb not null,
  result jsonb not null,
  primary key (user_id,source_schema_version,source_id,mapping_version)
);
alter table imfit_private.legacy_session_reviews enable row level security;
alter table imfit_private.legacy_workout_imports enable row level security;
revoke all on imfit_private.legacy_session_reviews,imfit_private.legacy_workout_imports
  from public,anon,authenticated;

-- Exactly the original source fields. updated_at is archived but NOT hashed:
-- the existing timestamp trigger must remain enabled when status is archived.
create function imfit_private.legacy_session_fingerprint(p_row public.active_sessions)
returns text language sql stable set search_path = '' set timezone = 'UTC' as $$
  select encode(sha256(convert_to(jsonb_build_object(
    'id',p_row.id,'user_id',p_row.user_id,'template_id',p_row.template_id,
    'template_name',p_row.template_name,'start_time',p_row.start_time,
    'current_exercise_index',p_row.current_exercise_index,'session_data',p_row.session_data,
    'last_activity_at',p_row.last_activity_at,'expires_at',p_row.expires_at,
    'created_at',p_row.created_at)::text,'UTF8')),'hex');
$$;
create function imfit_private.legacy_session_reviewed(p_row public.active_sessions)
returns boolean language sql stable set search_path = '' as $$
  select exists(select 1 from imfit_private.legacy_session_reviews r
    where r.user_id=p_row.user_id and r.session_id=p_row.id
      and r.source_fingerprint=imfit_private.legacy_session_fingerprint(p_row));
$$;

create function public.get_start_recovery_status(p_contract_version integer default 1)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_result jsonb;
begin
  if p_contract_version is distinct from 1 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  -- One statement/snapshot; readiness is EXISTS over every blocker, not preview size.
  with blockers as materialized (
    select s.id,s.version,
      case when s.version is null or s.schema_version is null then 'LEGACY_SESSION'
        when s.status='EXPIRED' or s.expires_at<=statement_timestamp() then 'EXPIRED_SESSION'
        else 'ACTIVE_SESSION' end as kind,
      case when s.version is null or s.schema_version is null
        then imfit_private.legacy_session_fingerprint(s) end as fingerprint,
      case when s.version is null or s.schema_version is null then 'LEGACY_REVIEW_REQUIRED'
        else 'SESSION_REVIEW_REQUIRED' end as reason
    from public.active_sessions s where s.user_id=v_owner and
      (case when s.version is null or s.schema_version is null then not imfit_private.legacy_session_reviewed(s)
        else s.status in ('ACTIVE','PAUSED') or (s.status='EXPIRED' and s.review_required) end)
  ), preview as (select * from blockers order by id limit 20)
  select jsonb_build_object('contract_version',1,'checked_at',statement_timestamp(),
    'can_create',not exists(select 1 from blockers),
    'blockers',coalesce((select jsonb_agg(jsonb_build_object('kind',kind,'resource_id',id,
      'version',version,'source_fingerprint',fingerprint,'reason',reason) order by id) from preview),'[]'::jsonb),
    'has_more_blockers',exists(select 1 from blockers offset 20)) into v_result;
  return v_result;
end;
$$;

create function public.get_legacy_recovery_page(p_cursor text default null,p_limit integer default 20)
returns jsonb language plpgsql stable security definer set search_path = '' set timezone = 'UTC' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_query jsonb; v_key jsonb; v_id uuid; v_rows jsonb;
begin
  perform imfit_private.validate_limit(p_limit);
  v_query:=jsonb_build_object('rpc','get_legacy_recovery_page','owner',v_owner,'limit',p_limit);
  v_key:=imfit_private.cursor_decode(p_cursor,v_query);
  if v_key is not null then
    if jsonb_array_length(v_key)<>1 or jsonb_typeof(v_key->0) is distinct from 'string' then
      perform imfit_private.fail('PT400','INVALID_CURSOR'); end if;
    begin v_id:=(v_key->>0)::uuid;
    exception when invalid_text_representation then perform imfit_private.fail('PT400','INVALID_CURSOR'); end;
  end if;
  select coalesce(jsonb_agg(jsonb_build_object('key',jsonb_build_array(s.id),
    'data',jsonb_build_object('session_id',s.id,'source_fingerprint',imfit_private.legacy_session_fingerprint(s),
      'resolution',case when r.source_fingerprint=imfit_private.legacy_session_fingerprint(s) then 'KEEP_ARCHIVED' end,
      'raw_source',case when r.source_fingerprint=imfit_private.legacy_session_fingerprint(s)
        then r.raw_source else to_jsonb(s) end)) order by s.id),'[]'::jsonb)
  into v_rows from (select * from public.active_sessions where user_id=v_owner
    and (version is null or schema_version is null) and (v_key is null or id>v_id)
    order by id limit p_limit+1) s
  left join imfit_private.legacy_session_reviews r on r.user_id=s.user_id and r.session_id=s.id;
  return imfit_private.page_result(v_rows,v_query,p_limit);
end;
$$;

create function public.resolve_legacy_session(p_request_id uuid,p_session_id uuid,
  p_expected_source_fingerprint text,p_resolution text)
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_row public.active_sessions%rowtype;
  v_payload jsonb; v_result jsonb; v_hash text;
begin
  v_payload:=jsonb_build_object('session_id',p_session_id,'source_fingerprint',p_expected_source_fingerprint,'resolution',p_resolution);
  v_result:=imfit_private.request_replay(v_owner,p_request_id,'resolve_legacy_session',p_session_id,v_payload);
  if v_result is not null then return v_result; end if;
  if p_resolution is distinct from 'KEEP_ARCHIVED' or p_expected_source_fingerprint is null
    or p_expected_source_fingerprint !~ '^[0-9a-f]{64}$' then perform imfit_private.fail('PT400','VALIDATION'); end if;
  select * into v_row from public.active_sessions where id=p_session_id and user_id=v_owner for update;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if v_row.version is not null and v_row.schema_version is not null then
    perform imfit_private.fail('PT409','SESSION_REVIEW_REQUIRED'); end if;
  v_hash:=imfit_private.legacy_session_fingerprint(v_row);
  if v_hash<>p_expected_source_fingerprint then perform imfit_private.fail('PT409','LEGACY_SOURCE_CHANGED'); end if;
  insert into imfit_private.legacy_session_reviews(user_id,session_id,source_fingerprint,raw_source,request_id)
  values(v_owner,p_session_id,v_hash,to_jsonb(v_row),p_request_id)
  on conflict(user_id,session_id) do update set source_fingerprint=excluded.source_fingerprint,
    raw_source=case when legacy_session_reviews.source_fingerprint=excluded.source_fingerprint
      then legacy_session_reviews.raw_source else excluded.raw_source end,
    request_id=excluded.request_id,reviewed_at=statement_timestamp();
  update public.active_sessions set status='EXPIRED',review_required=false where id=p_session_id;
  v_result:=jsonb_build_object('request_id',p_request_id,'operation','resolve_legacy_session',
    'session_id',p_session_id,'source_fingerprint',v_hash,'resolution','KEEP_ARCHIVED','committed_at',statement_timestamp());
  perform imfit_private.record_operation(v_owner,p_request_id,'resolve_legacy_session','SESSION',p_session_id,v_payload,v_result);
  return v_result;
end;
$$;

-- Canonicalize only the import projection. Never use floating point or round().
create function imfit_private.legacy_import_row(p_kind text,p_row jsonb)
returns jsonb language plpgsql stable set search_path = '' set timezone = 'UTC' as $$
declare v_keys text[]; v_nullable text[]; v_key text; v_value jsonb; v_text text;
  v_result jsonb:='{}'; v_number numeric; v_time timestamptz;
begin
  v_keys:=case p_kind
    when 'WORKOUT' then array['id','user_id','template_id','template_name','date','start_time','end_time',
      'total_volume','total_sets','total_reps','deleted_at','created_at','updated_at']
    when 'EXERCISE' then array['id','workout_log_id','exercise_id','exercise_name','muscle_category','order_index',
      'total_volume','total_sets','total_reps','input_defaults','created_at','updated_at']
    when 'SET' then array['id','exercise_log_id','workout_log_id','exercise_id','set_number','weight','reps',
      'is_completed','created_at','updated_at'] end;
  if v_keys is null or jsonb_typeof(p_row) is distinct from 'object' or (p_row-v_keys)<>'{}'::jsonb then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  v_nullable:=array['template_id','deleted_at','input_defaults'];
  foreach v_key in array v_keys loop
    v_value:=p_row->v_key; v_text:=p_row->>v_key;
    if v_value is null or v_value='null'::jsonb then
      if not (v_key=any(v_nullable)) then perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_value:='null'::jsonb;
    elsif v_key=any(array['id','user_id','template_id','workout_log_id','exercise_log_id']) then
      v_value:=to_jsonb(imfit_private.json_uuid(v_value));
    elsif v_key=any(array['total_volume','weight']) then
      if jsonb_typeof(v_value) not in ('number','string') or v_text !~ '^-?[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?$'
        or length(v_text)>1000 then perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_number:=v_text::numeric;
      if v_number<0 or (v_key='weight' and v_number>1000) then perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_value:=to_jsonb(trim_scale(v_number));
    elsif v_key=any(array['total_sets','total_reps','order_index','set_number','reps']) then
      if jsonb_typeof(v_value) is distinct from 'number' or v_text !~ '^(0|[1-9][0-9]*)$'
        or v_text::numeric>2147483647 or (v_key='set_number' and v_text::numeric<1)
        or (v_key='reps' and v_text::numeric>1000) then perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_value:=to_jsonb(v_text::integer);
    elsif v_key=any(array['start_time','end_time','created_at','updated_at','deleted_at']) then
      if jsonb_typeof(v_value) is distinct from 'string'
        or v_text !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$' then
        perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_time:=v_text::timestamptz;
      if not isfinite(v_time) then perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_value:=to_jsonb(to_char(v_time at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'));
    elsif v_key='date' then
      if jsonb_typeof(v_value) is distinct from 'string' or v_text !~ '^\d{4}-\d{2}-\d{2}$' then
        perform imfit_private.fail('PT400','VALIDATION'); end if;
      v_value:=to_jsonb(v_text::date);
    elsif v_key='is_completed' then
      if jsonb_typeof(v_value) is distinct from 'boolean' then perform imfit_private.fail('PT400','VALIDATION'); end if;
    elsif v_key='input_defaults' then
      if jsonb_typeof(v_value) is distinct from 'object' or (v_value-array['sets','reps'])<>'{}'::jsonb
        or not (v_value ?& array['sets','reps']) then perform imfit_private.fail('PT400','VALIDATION'); end if;
      perform imfit_private.json_int(v_value->'sets',1,20);
      perform imfit_private.json_int(v_value->'reps',1,100);
    elsif jsonb_typeof(v_value) is distinct from 'string' then perform imfit_private.fail('PT400','VALIDATION');
    end if;
    v_result:=v_result||jsonb_build_object(v_key,v_value);
  end loop;
  return v_result;
exception when invalid_text_representation or numeric_value_out_of_range or invalid_datetime_format or datetime_field_overflow then
  perform imfit_private.fail('PT400','VALIDATION'); return null;
end;
$$;

create function public.import_legacy_workout(p_request_id uuid,p_source jsonb,p_workout jsonb,p_exercises jsonb,p_sets jsonb)
returns jsonb language plpgsql security definer set search_path = '' set timezone = 'UTC' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_id uuid; v_source_id uuid; v_schema integer;
  v_payload jsonb; v_result jsonb; v_parent jsonb; v_exercises jsonb; v_sets jsonb;
  v_canonical jsonb; v_digest text; v_mapping jsonb; v_old imfit_private.legacy_workout_imports%rowtype;
  v_target public.workout_logs%rowtype; v_actual jsonb; v_outcome text;
  v_volume numeric; v_count bigint; v_reps bigint; v_exercise_count integer; v_set_count integer;
begin
  -- Bind the original body before parsing any field. A changed malformed body
  -- on an existing request is still an idempotency mismatch, not a new import.
  v_payload:=jsonb_build_object('source',p_source,'workout',p_workout,'exercises',p_exercises,'sets',p_sets);
  if p_request_id is null then perform imfit_private.fail('PT400','VALIDATION'); end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_owner::text||':'||p_request_id::text,0));
  select resource_id into v_id from imfit_private.operation_receipts
    where user_id=v_owner and request_id=p_request_id;
  if found then
    return imfit_private.request_replay(v_owner,p_request_id,'import_legacy_workout',v_id,v_payload);
  end if;
  if jsonb_typeof(p_workout) is distinct from 'object' then perform imfit_private.fail('PT400','VALIDATION'); end if;
  v_id:=imfit_private.json_uuid(p_workout->'id');
  v_result:=imfit_private.request_replay(v_owner,p_request_id,'import_legacy_workout',v_id,v_payload);
  if v_result is not null then return v_result; end if;
  if jsonb_typeof(p_source) is distinct from 'object' or
    (p_source-array['schema_version','kind','id','fingerprint','mapping_version'])<>'{}'::jsonb
    or not (p_source ?& array['schema_version','kind','id','fingerprint','mapping_version'])
    or p_source->'schema_version' not in ('6'::jsonb,'7'::jsonb,'9'::jsonb)
    or p_source->'mapping_version' is distinct from '1'::jsonb
    or p_source->>'kind' is distinct from 'WORKOUT'
    or jsonb_typeof(p_source->'fingerprint') is distinct from 'string'
    or (p_source->>'fingerprint') !~ '^[0-9a-f]{64}$'
    or jsonb_typeof(p_exercises) is distinct from 'array' or jsonb_typeof(p_sets) is distinct from 'array'
    or octet_length(v_payload::text)>4194304 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  v_source_id:=imfit_private.json_uuid(p_source->'id'); v_schema:=(p_source->>'schema_version')::integer;
  if v_source_id<>v_id or jsonb_array_length(p_exercises)+jsonb_array_length(p_sets)+1>10000 then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  v_parent:=imfit_private.legacy_import_row('WORKOUT',p_workout);
  if (v_parent->>'user_id')::uuid<>v_owner then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if (v_parent->>'end_time')::timestamptz<=(v_parent->>'start_time')::timestamptz then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  if v_parent->>'template_id' is not null then
    perform 1 from public.workout_templates where id=(v_parent->>'template_id')::uuid and user_id=v_owner for key share;
    if not found then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  end if;
  select coalesce(jsonb_agg(item order by (item->>'order_index')::integer,item->>'id'),'[]'::jsonb)
    into v_exercises from (select imfit_private.legacy_import_row('EXERCISE',value) as item from jsonb_array_elements(p_exercises)) e;
  select coalesce(jsonb_agg(item order by item->>'exercise_log_id',(item->>'set_number')::integer,item->>'id'),'[]'::jsonb)
    into v_sets from (select imfit_private.legacy_import_row('SET',value) as item from jsonb_array_elements(p_sets)) s;
  if v_schema=6 and exists(select 1 from jsonb_array_elements(v_exercises) e where e->'input_defaults'<>'null'::jsonb) then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  v_exercise_count:=jsonb_array_length(v_exercises); v_set_count:=jsonb_array_length(v_sets);
  if exists(select 1 from jsonb_array_elements(v_exercises) e where (e->>'workout_log_id')::uuid<>v_id)
    or exists(select 1 from jsonb_array_elements(v_sets) s where (s->>'workout_log_id')::uuid<>v_id
      or not exists(select 1 from jsonb_array_elements(v_exercises) e where e->'id'=s->'exercise_log_id' and e->'exercise_id'=s->'exercise_id'))
    or exists(select 1 from jsonb_array_elements(v_exercises) e group by e->>'order_index' having count(*)>1)
    or exists(select 1 from jsonb_array_elements(v_exercises) e group by e->>'exercise_id' having count(*)>1)
    or exists(select 1 from jsonb_array_elements(v_sets) s group by s->>'exercise_log_id',s->>'set_number' having count(*)>1)
    or exists(select 1 from (select v_parent->>'id' as id union all select e->>'id' from jsonb_array_elements(v_exercises) e
      union all select s->>'id' from jsonb_array_elements(v_sets) s) ids group by id having count(*)>1) then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  perform id from public.exercises where id in
    (select e->>'exercise_id' from jsonb_array_elements(v_exercises) e) for key share;
  get diagnostics v_count=row_count;
  if v_count<>v_exercise_count then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  if exists(
    select 1 from jsonb_array_elements(v_exercises) e
    left join (
      select s->>'exercise_log_id' as exercise_log_id,
        sum((s->>'weight')::numeric*(s->>'reps')::integer) as volume,
        count(*) as sets,sum((s->>'reps')::integer) as reps
      from jsonb_array_elements(v_sets) s where (s->>'is_completed')::boolean
      group by s->>'exercise_log_id'
    ) totals on totals.exercise_log_id=e->>'id'
    where coalesce(totals.volume,0)<>(e->>'total_volume')::numeric
      or coalesce(totals.sets,0)<>(e->>'total_sets')::integer
      or coalesce(totals.reps,0)<>(e->>'total_reps')::integer
  ) then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  select coalesce(sum((s->>'weight')::numeric*(s->>'reps')::integer),0),count(*),coalesce(sum((s->>'reps')::integer),0)
    into v_volume,v_count,v_reps from jsonb_array_elements(v_sets) s where (s->>'is_completed')::boolean;
  if v_volume<>(v_parent->>'total_volume')::numeric or v_count<>(v_parent->>'total_sets')::integer
    or v_reps<>(v_parent->>'total_reps')::integer then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  v_canonical:=jsonb_build_object('workout',v_parent,'exercises',v_exercises,'sets',v_sets);
  v_digest:=encode(sha256(convert_to(v_canonical::text,'UTF8')),'hex');
  select jsonb_object_agg(id,id) into v_mapping from (select v_parent->>'id' as id
    union all select e->>'id' from jsonb_array_elements(v_exercises) e
    union all select s->>'id' from jsonb_array_elements(v_sets) s) ids;
  select * into v_old from imfit_private.legacy_workout_imports where user_id=v_owner
    and source_schema_version=v_schema and source_id=v_source_id and mapping_version=1 for update;
  if found and (v_old.source_fingerprint<>p_source->>'fingerprint' or v_old.canonical_payload<>v_canonical) then
    perform imfit_private.fail('PT409','LEGACY_IMPORT_CONFLICT'); end if;
    -- Detect collisions across parents before any write; distinguish foreign owners.
    if exists(select 1 from public.exercise_logs e join public.workout_logs w on w.id=e.workout_log_id
      where e.id in(select (x->>'id')::uuid from jsonb_array_elements(v_exercises) x) and w.user_id<>v_owner)
      or exists(select 1 from public.workout_sets s join public.exercise_logs e on e.id=s.exercise_log_id
        join public.workout_logs w on w.id=e.workout_log_id
        where s.id in(select (x->>'id')::uuid from jsonb_array_elements(v_sets) x) and w.user_id<>v_owner) then
      perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  select * into v_target from public.workout_logs where id=v_id for update;
  if found then
    if v_target.user_id<>v_owner then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
    begin
    select jsonb_build_object('workout',imfit_private.legacy_import_row('WORKOUT',to_jsonb(v_target)-array['notes','rating','source_session_id']),
      'exercises',coalesce((select jsonb_agg(imfit_private.legacy_import_row('EXERCISE',to_jsonb(e)) order by e.order_index,e.id)
        from public.exercise_logs e where e.workout_log_id=v_id),'[]'::jsonb),
      'sets',coalesce((select jsonb_agg(imfit_private.legacy_import_row('SET',to_jsonb(s)-array['is_warmup','notes']) order by s.exercise_log_id,s.set_number,s.id)
        from public.workout_sets s where s.workout_log_id=v_id or s.exercise_log_id in
          (select id from public.exercise_logs where workout_log_id=v_id)),'[]'::jsonb)) into v_actual;
    exception when sqlstate 'PT400' then
      perform imfit_private.fail('PT409','LEGACY_IMPORT_CONFLICT');
    end;
    if v_actual<>v_canonical or v_target.notes is not null or v_target.rating is not null or v_target.source_session_id is not null
      or exists(select 1 from public.workout_sets where workout_log_id=v_id and (is_warmup or notes is not null)) then
      perform imfit_private.fail('PT409','LEGACY_IMPORT_CONFLICT'); end if;
    v_outcome:='MATCHED';
  else
    if exists(select 1 from public.exercise_logs where id in(select (x->>'id')::uuid from jsonb_array_elements(v_exercises) x))
      or exists(select 1 from public.workout_sets where id in(select (x->>'id')::uuid from jsonb_array_elements(v_sets) x)) then
      perform imfit_private.fail('PT409','LEGACY_IMPORT_CONFLICT'); end if;
    insert into public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,deleted_at,created_at,updated_at)
      select id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,deleted_at,created_at,updated_at
      from jsonb_populate_record(null::public.workout_logs,v_parent);
    insert into public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,total_volume,total_sets,total_reps,input_defaults,created_at,updated_at)
      select id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,total_volume,total_sets,total_reps,input_defaults,created_at,updated_at
      from jsonb_populate_recordset(null::public.exercise_logs,v_exercises);
    insert into public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,created_at,updated_at)
      select id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,created_at,updated_at
      from jsonb_populate_recordset(null::public.workout_sets,v_sets);
    v_outcome:='IMPORTED';
  end if;
  v_result:=jsonb_build_object('request_id',p_request_id,'operation','import_legacy_workout','workout_id',v_id,
    'source_fingerprint',p_source->>'fingerprint','payload_digest',v_digest,'outcome',v_outcome,
    'id_mapping',v_mapping,'exercise_count',v_exercise_count,'set_count',v_set_count,'committed_at',statement_timestamp());
  insert into imfit_private.legacy_workout_imports(user_id,source_schema_version,source_id,mapping_version,
    source_fingerprint,canonical_payload,payload_digest,workout_id,id_mapping,result)
  values(v_owner,v_schema,v_source_id,1,p_source->>'fingerprint',v_canonical,v_digest,v_id,v_mapping,v_result)
  on conflict(user_id,source_schema_version,source_id,mapping_version) do nothing;
  perform imfit_private.record_operation(v_owner,p_request_id,'import_legacy_workout','WORKOUT',v_id,v_payload,v_result);
  return v_result;
exception when unique_violation or foreign_key_violation or check_violation or not_null_violation then
  -- A foreign writer can win a UUID race after the pre-insert checks. The
  -- exception block has rolled back our aggregate before inspecting its owner.
  if exists(select 1 from public.workout_logs where id=v_id and user_id<>v_owner)
    or exists(select 1 from public.exercise_logs e join public.workout_logs w on w.id=e.workout_log_id
      where w.user_id<>v_owner and e.id in
        (select (x->>'id')::uuid from jsonb_array_elements(coalesce(v_exercises,'[]'::jsonb)) x))
    or exists(select 1 from public.workout_sets s join public.exercise_logs e on e.id=s.exercise_log_id
      join public.workout_logs w on w.id=e.workout_log_id where w.user_id<>v_owner and s.id in
        (select (x->>'id')::uuid from jsonb_array_elements(coalesce(v_sets,'[]'::jsonb)) x)) then
    perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND');
  end if;
  perform imfit_private.fail('PT409','LEGACY_IMPORT_CONFLICT'); return null;
end;
$$;

create or replace function public.create_active_session(
  p_request_id uuid,p_session_id uuid,p_template_id uuid,p_expected_template_revision bigint
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_payload jsonb; v_result jsonb;
  v_template public.workout_templates%rowtype; v_row public.active_sessions%rowtype;
  v_child public.template_exercises%rowtype; v_exercises jsonb:='[]'; v_exercise jsonb;
  v_id uuid; v_now timestamptz:=clock_timestamp(); v_data jsonb;
begin
  v_payload:=jsonb_build_object('template_id',p_template_id,'expected_revision',p_expected_template_revision);
  v_result:=imfit_private.request_replay(v_owner,p_request_id,'create_active_session',p_session_id,v_payload);
  if v_result is not null then return v_result; end if;
  if p_template_id is null or p_expected_template_revision is null or p_expected_template_revision<0 then
    perform imfit_private.fail('PT400','VALIDATION'); end if;
  if exists(select 1 from public.active_sessions s where s.user_id=v_owner
    and (s.version is null or s.schema_version is null) and not imfit_private.legacy_session_reviewed(s)) then
    perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  if exists(select 1 from public.active_sessions where user_id=v_owner and version is not null and schema_version is not null
    and (status in ('ACTIVE','PAUSED') or (status='EXPIRED' and review_required))) then
    perform imfit_private.fail('PT409','SESSION_REVIEW_REQUIRED'); end if;
  if exists(select 1 from public.active_sessions where id=p_session_id) then perform imfit_private.fail('PT409','STATE_CONFLICT'); end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_template_id::text,1));
  select * into v_template from public.workout_templates where id=p_template_id and user_id=v_owner and not is_deleted for update;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  if v_template.revision<>p_expected_template_revision then perform imfit_private.fail('PT409','REVISION_CONFLICT'); end if;
  if (select count(*) from public.template_exercises where template_id=p_template_id) not between 1 and 100
    or exists(select 1 from public.template_exercises where template_id=p_template_id group by exercise_id having count(*)>1) then
    perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  for v_child in select * from public.template_exercises where template_id=p_template_id order by order_index loop
    v_id:=md5(p_session_id::text||':'||v_child.id::text)::uuid;
    v_exercise:=imfit_private.new_session_exercise(v_owner,v_id,v_child.exercise_id,jsonb_array_length(v_exercises),
      jsonb_build_object('sets',v_child.sets,'reps',v_child.reps),v_child.rest_seconds);
    v_exercises:=v_exercises||jsonb_build_array(v_exercise);
  end loop;
  v_data:=jsonb_build_object('exercises',v_exercises,'current_exercise_id',v_exercises#>'{0,id}',
    'used_exercise_ids',(select jsonb_agg(e->'id') from jsonb_array_elements(v_exercises) e),
    'used_set_ids',(select jsonb_agg(s->'id') from jsonb_array_elements(v_exercises) e,lateral jsonb_array_elements(e->'sets') s));
  if octet_length(v_data::text)>262144 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  insert into public.active_sessions(id,user_id,template_id,template_name,start_time,session_data,last_activity_at,
    expires_at,version,schema_version,status,review_required,template_revision)
  values(p_session_id,v_owner,p_template_id,v_template.name,v_now,v_data,v_now,v_now+interval '30 days',1,1,'ACTIVE',false,v_template.revision)
    returning * into v_row;
  v_result:=imfit_private.session_receipt(v_row,p_request_id,'create_active_session');
  perform imfit_private.record_operation(v_owner,p_request_id,'create_active_session','SESSION',p_session_id,v_payload,v_result);
  return v_result;
end;
$$;

create or replace function public.get_active_session(p_session_id uuid default null)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_row public.active_sessions%rowtype;
begin
  select * into v_row from public.active_sessions s where s.user_id=v_owner
    and ((p_session_id is not null and s.id=p_session_id) or (p_session_id is null and
      (case when s.version is null or s.schema_version is null then not imfit_private.legacy_session_reviewed(s)
        else s.status in ('ACTIVE','PAUSED') or (s.status='EXPIRED' and s.review_required) end)))
    order by s.last_activity_at desc,s.id desc limit 1;
  if not found then return jsonb_build_object('found',false,'session',null); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409','LEGACY_REVIEW_REQUIRED'); end if;
  return jsonb_build_object('found',true,'session',imfit_private.session_snapshot(v_row));
end;
$$;

revoke all on all functions in schema imfit_private from public,anon,authenticated;
revoke all on function public.get_start_recovery_status(integer),public.get_legacy_recovery_page(text,integer),
  public.resolve_legacy_session(uuid,uuid,text,text),public.import_legacy_workout(uuid,jsonb,jsonb,jsonb,jsonb) from public,anon;
grant execute on function public.get_start_recovery_status(integer),public.get_legacy_recovery_page(text,integer),
  public.resolve_legacy_session(uuid,uuid,text,text),public.import_legacy_workout(uuid,jsonb,jsonb,jsonb,jsonb) to authenticated;
notify pgrst,'reload schema';
