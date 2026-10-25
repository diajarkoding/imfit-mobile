-- IMFIT backend hardening migration
-- Target: Supabase Postgres 17

set lock_timeout = '5s';
set statement_timeout = '30s';

-- Make future Data API exposure explicit.
alter default privileges for role postgres in schema public
  revoke select, insert, update, delete on tables from anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  revoke execute on functions from anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  revoke usage, select on sequences from anon, authenticated, service_role;
alter default privileges for role postgres in schema public
  revoke execute on functions from public;

-- Existing Data API grants: anonymous clients get no database rows.
revoke all privileges on table
  public.profiles,
  public.muscle_categories,
  public.exercises,
  public.workout_templates,
  public.template_exercises,
  public.workout_logs,
  public.exercise_logs,
  public.workout_sets,
  public.active_sessions
from anon;

-- Signed-in users get only the operations used by the mobile app.
revoke all privileges on table
  public.profiles,
  public.muscle_categories,
  public.exercises,
  public.workout_templates,
  public.template_exercises,
  public.workout_logs,
  public.exercise_logs,
  public.workout_sets,
  public.active_sessions
from authenticated;

grant select on table public.muscle_categories, public.exercises to authenticated;
grant select, insert, update on table public.profiles to authenticated;
grant select, insert, update, delete on table
  public.workout_templates,
  public.template_exercises,
  public.workout_logs,
  public.exercise_logs,
  public.workout_sets,
  public.active_sessions
to authenticated;

-- Ensure every exposed application table remains protected by RLS.
alter table public.profiles enable row level security;
alter table public.muscle_categories enable row level security;
alter table public.exercises enable row level security;
alter table public.workout_templates enable row level security;
alter table public.template_exercises enable row level security;
alter table public.workout_logs enable row level security;
alter table public.exercise_logs enable row level security;
alter table public.workout_sets enable row level security;
alter table public.active_sessions enable row level security;

-- Replace broad/public policies with operation-specific authenticated policies.
drop policy if exists "Users can view own profile" on public.profiles;
drop policy if exists "Users can insert own profile" on public.profiles;
drop policy if exists "Users can update own profile" on public.profiles;

create policy profiles_select_own
on public.profiles for select to authenticated
using ((select auth.uid()) = id);

create policy profiles_insert_own
on public.profiles for insert to authenticated
with check ((select auth.uid()) = id);

create policy profiles_update_own
on public.profiles for update to authenticated
using ((select auth.uid()) = id)
with check ((select auth.uid()) = id);

drop policy if exists "Users can view own templates" on public.workout_templates;
drop policy if exists "Users can create own templates" on public.workout_templates;
drop policy if exists "Users can update own templates" on public.workout_templates;
drop policy if exists "Users can delete own templates" on public.workout_templates;

create policy workout_templates_select_own
on public.workout_templates for select to authenticated
using ((select auth.uid()) = user_id);

create policy workout_templates_insert_own
on public.workout_templates for insert to authenticated
with check ((select auth.uid()) = user_id);

create policy workout_templates_update_own
on public.workout_templates for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy workout_templates_delete_own
on public.workout_templates for delete to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "Users can manage template exercises" on public.template_exercises;

create policy template_exercises_select_own
on public.template_exercises for select to authenticated
using (exists (
  select 1
  from public.workout_templates wt
  where wt.id = template_exercises.template_id
    and wt.user_id = (select auth.uid())
));

create policy template_exercises_insert_own
on public.template_exercises for insert to authenticated
with check (exists (
  select 1
  from public.workout_templates wt
  where wt.id = template_exercises.template_id
    and wt.user_id = (select auth.uid())
));

create policy template_exercises_update_own
on public.template_exercises for update to authenticated
using (exists (
  select 1
  from public.workout_templates wt
  where wt.id = template_exercises.template_id
    and wt.user_id = (select auth.uid())
))
with check (exists (
  select 1
  from public.workout_templates wt
  where wt.id = template_exercises.template_id
    and wt.user_id = (select auth.uid())
));

create policy template_exercises_delete_own
on public.template_exercises for delete to authenticated
using (exists (
  select 1
  from public.workout_templates wt
  where wt.id = template_exercises.template_id
    and wt.user_id = (select auth.uid())
));

drop policy if exists "Users can manage own workout logs" on public.workout_logs;

create policy workout_logs_select_own
on public.workout_logs for select to authenticated
using ((select auth.uid()) = user_id);

create policy workout_logs_insert_own
on public.workout_logs for insert to authenticated
with check ((select auth.uid()) = user_id);

create policy workout_logs_update_own
on public.workout_logs for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy workout_logs_delete_own
on public.workout_logs for delete to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "Users can manage exercise logs" on public.exercise_logs;

create policy exercise_logs_select_own
on public.exercise_logs for select to authenticated
using (exists (
  select 1
  from public.workout_logs wl
  where wl.id = exercise_logs.workout_log_id
    and wl.user_id = (select auth.uid())
));

create policy exercise_logs_insert_own
on public.exercise_logs for insert to authenticated
with check (exists (
  select 1
  from public.workout_logs wl
  where wl.id = exercise_logs.workout_log_id
    and wl.user_id = (select auth.uid())
));

create policy exercise_logs_update_own
on public.exercise_logs for update to authenticated
using (exists (
  select 1
  from public.workout_logs wl
  where wl.id = exercise_logs.workout_log_id
    and wl.user_id = (select auth.uid())
))
with check (exists (
  select 1
  from public.workout_logs wl
  where wl.id = exercise_logs.workout_log_id
    and wl.user_id = (select auth.uid())
));

create policy exercise_logs_delete_own
on public.exercise_logs for delete to authenticated
using (exists (
  select 1
  from public.workout_logs wl
  where wl.id = exercise_logs.workout_log_id
    and wl.user_id = (select auth.uid())
));

drop policy if exists "Users can manage workout sets" on public.workout_sets;

create policy workout_sets_select_own
on public.workout_sets for select to authenticated
using (exists (
  select 1
  from public.exercise_logs el
  join public.workout_logs wl on wl.id = el.workout_log_id
  where el.id = workout_sets.exercise_log_id
    and wl.user_id = (select auth.uid())
));

create policy workout_sets_insert_own
on public.workout_sets for insert to authenticated
with check (exists (
  select 1
  from public.exercise_logs el
  join public.workout_logs wl on wl.id = el.workout_log_id
  where el.id = workout_sets.exercise_log_id
    and wl.user_id = (select auth.uid())
));

create policy workout_sets_update_own
on public.workout_sets for update to authenticated
using (exists (
  select 1
  from public.exercise_logs el
  join public.workout_logs wl on wl.id = el.workout_log_id
  where el.id = workout_sets.exercise_log_id
    and wl.user_id = (select auth.uid())
))
with check (exists (
  select 1
  from public.exercise_logs el
  join public.workout_logs wl on wl.id = el.workout_log_id
  where el.id = workout_sets.exercise_log_id
    and wl.user_id = (select auth.uid())
));

create policy workout_sets_delete_own
on public.workout_sets for delete to authenticated
using (exists (
  select 1
  from public.exercise_logs el
  join public.workout_logs wl on wl.id = el.workout_log_id
  where el.id = workout_sets.exercise_log_id
    and wl.user_id = (select auth.uid())
));

drop policy if exists "Users can manage own sessions" on public.active_sessions;

create policy active_sessions_select_own
on public.active_sessions for select to authenticated
using ((select auth.uid()) = user_id);

create policy active_sessions_insert_own
on public.active_sessions for insert to authenticated
with check ((select auth.uid()) = user_id);

create policy active_sessions_update_own
on public.active_sessions for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy active_sessions_delete_own
on public.active_sessions for delete to authenticated
using ((select auth.uid()) = user_id);

-- Keep catalog policies signed-in and read-only.
drop policy if exists "Authenticated can view categories" on public.muscle_categories;
create policy muscle_categories_select_authenticated
on public.muscle_categories for select to authenticated
using (true);

drop policy if exists "Authenticated can view exercises" on public.exercises;
create policy exercises_select_authenticated
on public.exercises for select to authenticated
using (true);

-- Server-controlled modification timestamps.
create or replace function public.update_updated_at_column()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  new.updated_at = statement_timestamp();
  return new;
end;
$$;

revoke all on function public.update_updated_at_column() from public, anon, authenticated;
grant execute on function public.update_updated_at_column() to postgres;

update public.workout_logs set updated_at = coalesce(updated_at, created_at, statement_timestamp()) where updated_at is null;
update public.exercise_logs set updated_at = coalesce(updated_at, created_at, statement_timestamp()) where updated_at is null;

alter table public.workout_logs alter column updated_at set default now();
alter table public.workout_logs alter column updated_at set not null;
alter table public.exercise_logs alter column updated_at set default now();
alter table public.exercise_logs alter column updated_at set not null;

drop trigger if exists update_profiles_updated_at on public.profiles;
create trigger update_profiles_updated_at before update on public.profiles
for each row execute function public.update_updated_at_column();

drop trigger if exists update_exercises_updated_at on public.exercises;
create trigger update_exercises_updated_at before update on public.exercises
for each row execute function public.update_updated_at_column();

drop trigger if exists update_workout_templates_updated_at on public.workout_templates;
create trigger update_workout_templates_updated_at before update on public.workout_templates
for each row execute function public.update_updated_at_column();

drop trigger if exists update_template_exercises_updated_at on public.template_exercises;
create trigger update_template_exercises_updated_at before update on public.template_exercises
for each row execute function public.update_updated_at_column();

drop trigger if exists update_workout_logs_updated_at on public.workout_logs;
create trigger update_workout_logs_updated_at before update on public.workout_logs
for each row execute function public.update_updated_at_column();

drop trigger if exists update_exercise_logs_updated_at on public.exercise_logs;
create trigger update_exercise_logs_updated_at before update on public.exercise_logs
for each row execute function public.update_updated_at_column();

drop trigger if exists update_workout_sets_updated_at on public.workout_sets;
create trigger update_workout_sets_updated_at before update on public.workout_sets
for each row execute function public.update_updated_at_column();

drop trigger if exists update_active_sessions_updated_at on public.active_sessions;
create trigger update_active_sessions_updated_at before update on public.active_sessions
for each row execute function public.update_updated_at_column();

-- Harden the Auth profile trigger: fixed search_path, robust optional birth date,
-- idempotent profile creation, and no public RPC execution.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_birth_date date;
begin
  begin
    v_birth_date := nullif(new.raw_user_meta_data ->> 'birth_date', '')::date;
  exception
    when invalid_datetime_format or datetime_field_overflow then
      v_birth_date := null;
  end;

  insert into public.profiles (id, name, email, birth_date)
  values (
    new.id,
    coalesce(nullif(btrim(new.raw_user_meta_data ->> 'name'), ''), split_part(new.email, '@', 1)),
    new.email,
    v_birth_date
  )
  on conflict (id) do nothing;

  return new;
end;
$$;

revoke all on function public.handle_new_user() from public, anon, authenticated;
grant execute on function public.handle_new_user() to postgres, supabase_auth_admin;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

-- Prevent a user-owned row from referencing another user's template.
create or replace function public.validate_owned_template_reference()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.template_id is not null
     and not exists (
       select 1
       from public.workout_templates wt
       where wt.id = new.template_id
         and wt.user_id = new.user_id
     ) then
    raise exception 'template_id does not belong to user_id' using errcode = '23503';
  end if;
  return new;
end;
$$;

revoke all on function public.validate_owned_template_reference()
from public, anon, authenticated;
grant execute on function public.validate_owned_template_reference() to postgres;

drop trigger if exists validate_workout_log_template_owner on public.workout_logs;
create trigger validate_workout_log_template_owner
before insert or update of user_id, template_id on public.workout_logs
for each row execute function public.validate_owned_template_reference();

drop trigger if exists validate_active_session_template_owner on public.active_sessions;
create trigger validate_active_session_template_owner
before insert or update of user_id, template_id on public.active_sessions
for each row execute function public.validate_owned_template_reference();

-- Remove duplicate indexes and add missing FK/delta-sync indexes.
drop index if exists public.idx_active_sessions_user;
drop index if exists public.idx_workout_templates_user;

create index if not exists idx_active_sessions_template_id
  on public.active_sessions (template_id);
create index if not exists idx_exercise_logs_exercise_id
  on public.exercise_logs (exercise_id);
create index if not exists idx_template_exercises_exercise_id
  on public.template_exercises (exercise_id);
create index if not exists idx_workout_logs_template_id
  on public.workout_logs (template_id);

create index if not exists idx_workout_templates_user_updated
  on public.workout_templates (user_id, updated_at, id);
create index if not exists idx_workout_logs_user_updated
  on public.workout_logs (user_id, updated_at, id);
create index if not exists idx_template_exercises_template_updated
  on public.template_exercises (template_id, updated_at, id);
create index if not exists idx_exercise_logs_workout_updated
  on public.exercise_logs (workout_log_id, updated_at, id);
create index if not exists idx_workout_sets_exercise_updated
  on public.workout_sets (exercise_log_id, updated_at, id);

-- Private avatar bucket: enforce safe media types and a limit compatible with
-- existing objects while the client-side compression pipeline is adopted.
update storage.buckets
set file_size_limit = 5242880,
    allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp']::text[]
where id = 'avatars';

drop policy if exists "Users can download own avatars" on storage.objects;
drop policy if exists "Users can upload avatars to own folder" on storage.objects;
drop policy if exists "Users can update own avatars" on storage.objects;
drop policy if exists "Users can delete own avatars" on storage.objects;

create policy avatars_select_own
on storage.objects for select to authenticated
using (
  bucket_id = 'avatars'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy avatars_insert_own
on storage.objects for insert to authenticated
with check (
  bucket_id = 'avatars'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy avatars_update_own
on storage.objects for update to authenticated
using (
  bucket_id = 'avatars'
  and (storage.foldername(name))[1] = (select auth.uid())::text
)
with check (
  bucket_id = 'avatars'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

create policy avatars_delete_own
on storage.objects for delete to authenticated
using (
  bucket_id = 'avatars'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

-- Atomic workout aggregate upsert. One RPC invocation is one DB transaction.
create or replace function public.upsert_workout_aggregate(
  p_workout jsonb,
  p_exercises jsonb default '[]'::jsonb,
  p_sets jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_workout_id uuid;
  v_exercise jsonb;
  v_set jsonb;
  v_exercise_log_id uuid;
  v_parent_exercise_id text;
  v_exercise_count integer := 0;
  v_set_count integer := 0;
  v_server_time timestamptz := statement_timestamp();
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if p_workout is null or jsonb_typeof(p_workout) <> 'object' then
    raise exception 'p_workout must be a JSON object' using errcode = '22023';
  end if;
  if jsonb_typeof(p_exercises) <> 'array' or jsonb_typeof(p_sets) <> 'array' then
    raise exception 'p_exercises and p_sets must be JSON arrays' using errcode = '22023';
  end if;

  v_workout_id := nullif(p_workout ->> 'id', '')::uuid;
  if v_workout_id is null then
    raise exception 'workout.id is required' using errcode = '22023';
  end if;
  if nullif(p_workout ->> 'user_id', '') is not null
     and (p_workout ->> 'user_id')::uuid <> v_uid then
    raise exception 'workout.user_id must match the authenticated user' using errcode = '42501';
  end if;

  insert into public.workout_logs (
    id, user_id, template_id, template_name, date, start_time, end_time,
    total_volume, total_sets, total_reps, notes, rating,
    created_at, deleted_at, updated_at
  )
  values (
    v_workout_id,
    v_uid,
    nullif(p_workout ->> 'template_id', '')::uuid,
    p_workout ->> 'template_name',
    (p_workout ->> 'date')::date,
    (p_workout ->> 'start_time')::timestamptz,
    (p_workout ->> 'end_time')::timestamptz,
    coalesce(nullif(p_workout ->> 'total_volume', '')::numeric, 0),
    coalesce(nullif(p_workout ->> 'total_sets', '')::integer, 0),
    coalesce(nullif(p_workout ->> 'total_reps', '')::integer, 0),
    p_workout ->> 'notes',
    nullif(p_workout ->> 'rating', '')::integer,
    coalesce(nullif(p_workout ->> 'created_at', '')::timestamptz, v_server_time),
    nullif(p_workout ->> 'deleted_at', '')::timestamptz,
    v_server_time
  )
  on conflict (id) do update set
    user_id = excluded.user_id,
    template_id = excluded.template_id,
    template_name = excluded.template_name,
    date = excluded.date,
    start_time = excluded.start_time,
    end_time = excluded.end_time,
    total_volume = excluded.total_volume,
    total_sets = excluded.total_sets,
    total_reps = excluded.total_reps,
    notes = excluded.notes,
    rating = excluded.rating,
    deleted_at = excluded.deleted_at,
    updated_at = v_server_time
  where public.workout_logs.user_id = v_uid;

  if not found then
    raise exception 'Workout id belongs to another user' using errcode = '42501';
  end if;

  for v_exercise in select value from jsonb_array_elements(p_exercises)
  loop
    if jsonb_typeof(v_exercise) <> 'object' then
      raise exception 'Each exercise must be a JSON object' using errcode = '22023';
    end if;
    v_exercise_log_id := nullif(v_exercise ->> 'id', '')::uuid;
    if v_exercise_log_id is null then
      raise exception 'exercise.id is required' using errcode = '22023';
    end if;

    insert into public.exercise_logs (
      id, workout_log_id, exercise_id, exercise_name, muscle_category,
      order_index, total_volume, total_sets, total_reps, created_at, updated_at
    )
    values (
      v_exercise_log_id,
      v_workout_id,
      v_exercise ->> 'exercise_id',
      v_exercise ->> 'exercise_name',
      v_exercise ->> 'muscle_category',
      coalesce(nullif(v_exercise ->> 'order_index', '')::integer, 0),
      coalesce(nullif(v_exercise ->> 'total_volume', '')::numeric, 0),
      coalesce(nullif(v_exercise ->> 'total_sets', '')::integer, 0),
      coalesce(nullif(v_exercise ->> 'total_reps', '')::integer, 0),
      coalesce(nullif(v_exercise ->> 'created_at', '')::timestamptz, v_server_time),
      v_server_time
    )
    on conflict (id) do update set
      workout_log_id = excluded.workout_log_id,
      exercise_id = excluded.exercise_id,
      exercise_name = excluded.exercise_name,
      muscle_category = excluded.muscle_category,
      order_index = excluded.order_index,
      total_volume = excluded.total_volume,
      total_sets = excluded.total_sets,
      total_reps = excluded.total_reps,
      updated_at = v_server_time;

    v_exercise_count := v_exercise_count + 1;
  end loop;

  for v_set in select value from jsonb_array_elements(p_sets)
  loop
    if jsonb_typeof(v_set) <> 'object' then
      raise exception 'Each set must be a JSON object' using errcode = '22023';
    end if;
    v_exercise_log_id := nullif(v_set ->> 'exercise_log_id', '')::uuid;

    select el.exercise_id
      into v_parent_exercise_id
    from public.exercise_logs el
    where el.id = v_exercise_log_id
      and el.workout_log_id = v_workout_id;

    if not found then
      raise exception 'set.exercise_log_id must belong to this workout' using errcode = '23503';
    end if;

    insert into public.workout_sets (
      id, exercise_log_id, set_number, weight, reps, is_completed,
      is_warmup, notes, created_at, updated_at, workout_log_id, exercise_id
    )
    values (
      nullif(v_set ->> 'id', '')::uuid,
      v_exercise_log_id,
      (v_set ->> 'set_number')::integer,
      coalesce(nullif(v_set ->> 'weight', '')::numeric, 0),
      coalesce(nullif(v_set ->> 'reps', '')::integer, 0),
      coalesce(nullif(v_set ->> 'is_completed', '')::boolean, false),
      coalesce(nullif(v_set ->> 'is_warmup', '')::boolean, false),
      v_set ->> 'notes',
      coalesce(nullif(v_set ->> 'created_at', '')::timestamptz, v_server_time),
      v_server_time,
      v_workout_id,
      v_parent_exercise_id
    )
    on conflict (id) do update set
      exercise_log_id = excluded.exercise_log_id,
      set_number = excluded.set_number,
      weight = excluded.weight,
      reps = excluded.reps,
      is_completed = excluded.is_completed,
      is_warmup = excluded.is_warmup,
      notes = excluded.notes,
      updated_at = v_server_time,
      workout_log_id = excluded.workout_log_id,
      exercise_id = excluded.exercise_id;

    v_set_count := v_set_count + 1;
  end loop;

  return jsonb_build_object(
    'workout_id', v_workout_id,
    'exercise_count', v_exercise_count,
    'set_count', v_set_count,
    'server_updated_at', v_server_time
  );
end;
$$;

revoke all on function public.upsert_workout_aggregate(jsonb, jsonb, jsonb)
from public, anon;
grant execute on function public.upsert_workout_aggregate(jsonb, jsonb, jsonb)
to authenticated, service_role;

-- Atomic template aggregate upsert.
create or replace function public.upsert_template_aggregate(
  p_template jsonb,
  p_exercises jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_template_id uuid;
  v_exercise jsonb;
  v_exercise_count integer := 0;
  v_server_time timestamptz := statement_timestamp();
begin
  if v_uid is null then
    raise exception 'Authentication required' using errcode = '42501';
  end if;
  if p_template is null or jsonb_typeof(p_template) <> 'object' then
    raise exception 'p_template must be a JSON object' using errcode = '22023';
  end if;
  if jsonb_typeof(p_exercises) <> 'array' then
    raise exception 'p_exercises must be a JSON array' using errcode = '22023';
  end if;

  v_template_id := nullif(p_template ->> 'id', '')::uuid;
  if v_template_id is null then
    raise exception 'template.id is required' using errcode = '22023';
  end if;
  if nullif(p_template ->> 'user_id', '') is not null
     and (p_template ->> 'user_id')::uuid <> v_uid then
    raise exception 'template.user_id must match the authenticated user' using errcode = '42501';
  end if;

  insert into public.workout_templates (
    id, user_id, name, description, estimated_duration, is_deleted,
    created_at, updated_at
  )
  values (
    v_template_id,
    v_uid,
    p_template ->> 'name',
    p_template ->> 'description',
    nullif(p_template ->> 'estimated_duration', '')::integer,
    coalesce(nullif(p_template ->> 'is_deleted', '')::boolean, false),
    coalesce(nullif(p_template ->> 'created_at', '')::timestamptz, v_server_time),
    v_server_time
  )
  on conflict (id) do update set
    user_id = excluded.user_id,
    name = excluded.name,
    description = excluded.description,
    estimated_duration = excluded.estimated_duration,
    is_deleted = excluded.is_deleted,
    updated_at = v_server_time
  where public.workout_templates.user_id = v_uid;

  if not found then
    raise exception 'Template id belongs to another user' using errcode = '42501';
  end if;

  for v_exercise in select value from jsonb_array_elements(p_exercises)
  loop
    if jsonb_typeof(v_exercise) <> 'object' then
      raise exception 'Each template exercise must be a JSON object' using errcode = '22023';
    end if;

    insert into public.template_exercises (
      id, template_id, exercise_id, order_index, sets, reps,
      rest_seconds, notes, created_at, updated_at
    )
    values (
      nullif(v_exercise ->> 'id', '')::uuid,
      v_template_id,
      v_exercise ->> 'exercise_id',
      coalesce(nullif(v_exercise ->> 'order_index', '')::integer, 0),
      coalesce(nullif(v_exercise ->> 'sets', '')::integer, 3),
      coalesce(nullif(v_exercise ->> 'reps', '')::integer, 10),
      coalesce(nullif(v_exercise ->> 'rest_seconds', '')::integer, 60),
      v_exercise ->> 'notes',
      coalesce(nullif(v_exercise ->> 'created_at', '')::timestamptz, v_server_time),
      v_server_time
    )
    on conflict (id) do update set
      template_id = excluded.template_id,
      exercise_id = excluded.exercise_id,
      order_index = excluded.order_index,
      sets = excluded.sets,
      reps = excluded.reps,
      rest_seconds = excluded.rest_seconds,
      notes = excluded.notes,
      updated_at = v_server_time;

    v_exercise_count := v_exercise_count + 1;
  end loop;

  return jsonb_build_object(
    'template_id', v_template_id,
    'exercise_count', v_exercise_count,
    'server_updated_at', v_server_time
  );
end;
$$;

revoke all on function public.upsert_template_aggregate(jsonb, jsonb)
from public, anon;
grant execute on function public.upsert_template_aggregate(jsonb, jsonb)
to authenticated, service_role;

comment on function public.upsert_workout_aggregate(jsonb, jsonb, jsonb)
is 'Atomically upserts one authenticated user workout with its exercise logs and sets.';
comment on function public.upsert_template_aggregate(jsonb, jsonb)
is 'Atomically upserts one authenticated user template with its exercise rows.';
