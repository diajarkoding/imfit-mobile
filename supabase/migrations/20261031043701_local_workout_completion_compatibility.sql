-- Room remains authoritative for in-progress Android sessions. This endpoint only
-- commits immutable, completed local snapshots; it cannot mutate server sessions.
set lock_timeout = '5s';
set statement_timeout = '30s';

create schema if not exists imfit_private;
alter table public.exercise_logs add column if not exists input_defaults jsonb;

create table imfit_private.local_workout_receipts (
  workout_id uuid primary key references public.workout_logs(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  payload jsonb not null,
  result jsonb not null,
  committed_at timestamptz not null default statement_timestamp()
);
create index local_workout_receipts_owner on imfit_private.local_workout_receipts(user_id);
alter table imfit_private.local_workout_receipts enable row level security;
revoke all on imfit_private.local_workout_receipts from public, anon, authenticated;

create or replace function public.persist_local_workout_aggregate(
  p_workout jsonb, p_exercises jsonb, p_sets jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_id uuid;
  v_payload jsonb;
  v_receipt imfit_private.local_workout_receipts%rowtype;
  v_e jsonb;
  v_s jsonb;
  v_count integer;
  v_reps integer;
  v_volume numeric;
  v_total_reps integer := 0;
  v_total_volume numeric := 0;
  v_now timestamptz := statement_timestamp();
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if jsonb_typeof(p_workout) is distinct from 'object'
    or jsonb_typeof(p_exercises) is distinct from 'array'
    or jsonb_typeof(p_sets) is distinct from 'array'
    or octet_length(jsonb_build_array(p_workout,p_exercises,p_sets)::text) > 262144 then
    raise exception 'Invalid aggregate envelope' using errcode = '22023';
  end if;
  v_id := (p_workout->>'id')::uuid;
  if v_id is null or (p_workout->>'user_id')::uuid is distinct from v_owner then
    raise exception 'Workout owner mismatch' using errcode = '42501';
  end if;
  -- Canonical child order makes retries independent of SQLite query order.
  v_payload := jsonb_build_object('workout',p_workout,
    'exercises',(select coalesce(jsonb_agg(e order by e->>'id'),'[]') from jsonb_array_elements(p_exercises) e),
    'sets',(select coalesce(jsonb_agg(s order by s->>'id'),'[]') from jsonb_array_elements(p_sets) s));
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_id::text, 73));
  select * into v_receipt from imfit_private.local_workout_receipts where workout_id=v_id;
  if found then
    if v_receipt.user_id<>v_owner then raise exception 'Workout owner mismatch' using errcode='42501'; end if;
    if v_receipt.payload is distinct from v_payload then
      raise exception 'IDEMPOTENCY_MISMATCH' using errcode='PT409';
    end if;
    return v_receipt.result;
  end if;
  -- Never overwrite existing history (including server-session completions).
  if exists(select 1 from public.workout_logs where id=v_id) then
    raise exception 'WORKOUT_ALREADY_EXISTS' using errcode='PT409';
  end if;
  if not exists(select 1 from public.workout_templates
      where id=(p_workout->>'template_id')::uuid and user_id=v_owner)
    or nullif(btrim(p_workout->>'template_name'),'') is null
    or (p_workout->>'start_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz <= (p_workout->>'start_time')::timestamptz
    or (p_workout->>'date')::date is distinct from ((p_workout->>'start_time')::timestamptz at time zone 'UTC')::date
    or p_workout->>'deleted_at' is not null
    or jsonb_array_length(p_exercises) not between 1 and 100
    or jsonb_array_length(p_sets) not between 1 and 2000 then
    raise exception 'Invalid completed workout' using errcode='22023';
  end if;
  if (select count(distinct e->>'id') from jsonb_array_elements(p_exercises) e)<>jsonb_array_length(p_exercises)
    or (select count(distinct e->>'order_index') from jsonb_array_elements(p_exercises) e)<>jsonb_array_length(p_exercises)
    or (select count(distinct s->>'id') from jsonb_array_elements(p_sets) s)<>jsonb_array_length(p_sets) then
    raise exception 'Duplicate snapshot identifiers' using errcode='22023';
  end if;
  for v_s in select value from jsonb_array_elements(p_sets) loop
    if jsonb_typeof(v_s)<>'object' or (v_s->>'id')::uuid is null
      or (v_s->>'workout_log_id')::uuid is distinct from v_id
      or (v_s->>'is_completed')::boolean is distinct from true
      or (v_s->>'set_number')::integer is null or (v_s->>'set_number')::integer<1
      or (v_s->>'weight')::numeric is null or not ((v_s->>'weight')::numeric between 0 and 1000)
      or (v_s->>'reps')::integer is null or not ((v_s->>'reps')::integer between 0 and 1000)
      or not exists(select 1 from jsonb_array_elements(p_exercises) e
        where (e->>'id')::uuid=(v_s->>'exercise_log_id')::uuid
          and e->>'exercise_id'=v_s->>'exercise_id') then
      raise exception 'Invalid completed set snapshot' using errcode='22023';
    end if;
  end loop;
  for v_e in select value from jsonb_array_elements(p_exercises) loop
    if jsonb_typeof(v_e)<>'object' or (v_e->>'id')::uuid is null
      or (v_e->>'workout_log_id')::uuid is distinct from v_id
      or nullif(btrim(v_e->>'exercise_name'),'') is null
      or nullif(btrim(v_e->>'muscle_category'),'') is null
      or (v_e->>'order_index')::integer is null or (v_e->>'order_index')::integer<0
      or not exists(select 1 from public.exercises where id=v_e->>'exercise_id') then
      raise exception 'Invalid exercise snapshot' using errcode='22023';
    end if;
    select count(*)::integer,coalesce(sum((s->>'reps')::integer),0)::integer,
      coalesce(sum((s->>'weight')::numeric*(s->>'reps')::integer),0)
      into v_count,v_reps,v_volume from jsonb_array_elements(p_sets) s
      where s->>'exercise_log_id'=v_e->>'id';
    if v_count=0 or v_count is distinct from (v_e->>'total_sets')::integer
      or v_reps is distinct from (v_e->>'total_reps')::integer
      or (v_e->>'total_volume')::numeric is null
      or abs(v_volume-(v_e->>'total_volume')::numeric)>greatest(0.01,abs(v_volume)*0.00001)
      or (select count(distinct s->>'set_number') from jsonb_array_elements(p_sets) s
        where s->>'exercise_log_id'=v_e->>'id')<>v_count then
      raise exception 'Exercise totals do not match snapshot' using errcode='22023';
    end if;
    v_total_reps:=v_total_reps+v_reps;
    v_total_volume:=v_total_volume+v_volume;
  end loop;
  if jsonb_array_length(p_sets) is distinct from (p_workout->>'total_sets')::integer
    or v_total_reps is distinct from (p_workout->>'total_reps')::integer
    or (p_workout->>'total_volume')::numeric is null
    or abs(v_total_volume-(p_workout->>'total_volume')::numeric)>greatest(0.01,abs(v_total_volume)*0.00001) then
    raise exception 'Workout totals do not match snapshot' using errcode='22023';
  end if;

  insert into public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,
    total_volume,total_sets,total_reps,created_at,updated_at)
  values(v_id,v_owner,(p_workout->>'template_id')::uuid,p_workout->>'template_name',
    (p_workout->>'date')::date,(p_workout->>'start_time')::timestamptz,(p_workout->>'end_time')::timestamptz,
    v_total_volume,jsonb_array_length(p_sets),v_total_reps,v_now,v_now);
  for v_e in select value from jsonb_array_elements(p_exercises) loop
    insert into public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,
      order_index,total_volume,total_sets,total_reps,input_defaults,created_at,updated_at)
    values((v_e->>'id')::uuid,v_id,v_e->>'exercise_id',v_e->>'exercise_name',v_e->>'muscle_category',
      (v_e->>'order_index')::integer,(v_e->>'total_volume')::numeric,
      (v_e->>'total_sets')::integer,(v_e->>'total_reps')::integer,v_e->'input_defaults',v_now,v_now);
  end loop;
  for v_s in select value from jsonb_array_elements(p_sets) loop
    insert into public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,
      weight,reps,is_completed,created_at,updated_at)
    values((v_s->>'id')::uuid,(v_s->>'exercise_log_id')::uuid,v_id,v_s->>'exercise_id',
      (v_s->>'set_number')::integer,(v_s->>'weight')::numeric,(v_s->>'reps')::integer,true,v_now,v_now);
  end loop;
  v_result:=jsonb_build_object('workout_id',v_id,'exercise_count',jsonb_array_length(p_exercises),
    'set_count',jsonb_array_length(p_sets),'server_updated_at',v_now);
  insert into imfit_private.local_workout_receipts(workout_id,user_id,payload,result)
    values(v_id,v_owner,v_payload,v_result);
  return v_result;
exception when unique_violation or foreign_key_violation or check_violation then
  raise exception 'Invalid snapshot or conflicting identifier' using errcode='PT409';
end;
$$;
revoke all on function public.persist_local_workout_aggregate(jsonb,jsonb,jsonb) from public,anon;
grant execute on function public.persist_local_workout_aggregate(jsonb,jsonb,jsonb) to authenticated;
revoke all on function public.upsert_workout_aggregate(jsonb,jsonb,jsonb) from public,anon,authenticated;
comment on function public.persist_local_workout_aggregate(jsonb,jsonb,jsonb) is
  'Owner-checked immutable completion bridge for Room sessions. Exact retries return the original receipt; no direct table write grants.';
notify pgrst, 'reload schema';
