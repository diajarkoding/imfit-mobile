-- Source-only integration migration. Apply/test separately; the agent does not deploy it.
-- Revision covers legacy parent AND child writers, not only this new RPC.
alter table public.workout_templates add column if not exists revision bigint not null default 0;

create or replace function public.bump_template_revision()
returns trigger language plpgsql set search_path = '' as $$
begin
  new.revision := old.revision + 1;
  return new;
end;
$$;
drop trigger if exists bump_template_revision on public.workout_templates;
create trigger bump_template_revision before update on public.workout_templates
for each row execute function public.bump_template_revision();

create or replace function public.bump_template_from_child()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'DELETE' then
    update public.workout_templates set updated_at = statement_timestamp() where id = old.template_id;
    return old;
  end if;
  update public.workout_templates set updated_at = statement_timestamp() where id = new.template_id;
  if tg_op = 'UPDATE' and old.template_id <> new.template_id then
    update public.workout_templates set updated_at = statement_timestamp() where id = old.template_id;
  end if;
  return new;
end;
$$;
drop trigger if exists bump_template_from_child on public.template_exercises;
create trigger bump_template_from_child after insert or update or delete on public.template_exercises
for each row execute function public.bump_template_from_child();

-- Only the SECURITY DEFINER RPC may create receipts. REST clients cannot forge acknowledgement.
create table if not exists public.template_write_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  request_id uuid not null,
  payload jsonb not null,
  receipt jsonb not null,
  primary key(user_id, request_id)
);
alter table public.template_write_receipts enable row level security;
revoke all on public.template_write_receipts from public, anon, authenticated;

create or replace function public.save_template_revision(
  p_request_id uuid,
  p_template_id uuid,
  p_expected_revision bigint,
  p_name text,
  p_exercises jsonb,
  p_removed_exercise_ids jsonb,
  p_is_deleted boolean,
  p_owner_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_parent public.workout_templates%rowtype;
  v_payload jsonb;
  v_receipt jsonb;
  v_previous public.template_write_receipts%rowtype;
  v_child jsonb;
  v_removed text;
  v_offset bigint;
  v_count integer;
begin
  if v_owner is null or p_owner_id is distinct from v_owner or p_request_id is null or p_template_id is null then
    raise exception 'Authenticated request identity required' using errcode = '42501';
  end if;
  if p_name is null or char_length(btrim(p_name)) not between 2 and 100 or
     p_is_deleted is null or p_exercises is null or jsonb_typeof(p_exercises) <> 'array' or
     p_removed_exercise_ids is null or jsonb_typeof(p_removed_exercise_ids) <> 'array' then
    raise exception 'Invalid template payload' using errcode = '22023';
  end if;
  v_payload := jsonb_build_object('id', p_template_id, 'owner', p_owner_id, 'revision', p_expected_revision,
    'name', p_name, 'exercises', p_exercises, 'removed', p_removed_exercise_ids, 'deleted', p_is_deleted);
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_owner::text || ':' || p_request_id::text, 0));
  select * into v_previous from public.template_write_receipts
    where user_id = v_owner and request_id = p_request_id;
  if found then
    if v_previous.payload <> v_payload then
      raise exception 'Request identity reused with different payload' using errcode = '22023';
    end if;
    return v_previous.receipt;
  end if;

  -- Serialize concurrent creates of this exact id as well as updates.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_template_id::text, 1));
  select * into v_parent from public.workout_templates where id = p_template_id for update;
  if found then
    if v_parent.user_id <> v_owner then
      raise exception 'Template unavailable' using errcode = '42501';
    end if;
    if p_expected_revision is null or p_expected_revision <> v_parent.revision then
      raise exception 'Template revision conflict' using errcode = '40001';
    end if;
  else
    if p_expected_revision is not null or p_is_deleted then
      raise exception 'Template unavailable' using errcode = '40001';
    end if;
    insert into public.workout_templates(id, user_id, name) values(p_template_id, v_owner, p_name);
  end if;

  if exists (select 1 from jsonb_array_elements(p_exercises) e
      where jsonb_typeof(e) <> 'object' or nullif(e->>'exercise_id','') is null or
        (e->>'order_index') is null or (e->>'sets') is null or (e->>'reps') is null or (e->>'rest_seconds') is null) or
     exists (select 1 from jsonb_array_elements(p_removed_exercise_ids) e where jsonb_typeof(e) <> 'string') then
    raise exception 'Invalid exercise identity' using errcode = '22023';
  end if;
  if (select count(*) from jsonb_array_elements(p_exercises)) <>
     (select count(distinct e->>'exercise_id') from jsonb_array_elements(p_exercises) e) or
     (select count(*) from jsonb_array_elements(p_removed_exercise_ids)) <>
     (select count(distinct e) from jsonb_array_elements_text(p_removed_exercise_ids) e) then
    raise exception 'Duplicate exercise identity' using errcode = '22023';
  end if;
  if exists (select 1 from public.template_exercises where template_id = p_template_id
      group by exercise_id having count(*) > 1) then
    raise exception 'Legacy duplicate exercises require review' using errcode = '40001';
  end if;
  -- The removed list must be EXACTLY the existing identities omitted by the submitted list.
  if exists (select 1 from public.template_exercises old where old.template_id = p_template_id
      and not exists (select 1 from jsonb_array_elements(p_exercises) e where e->>'exercise_id' = old.exercise_id)
      and not exists (select 1 from jsonb_array_elements_text(p_removed_exercise_ids) r where r = old.exercise_id)) or
     exists (select 1 from jsonb_array_elements_text(p_removed_exercise_ids) r
      where not exists (select 1 from public.template_exercises old where old.template_id = p_template_id and old.exercise_id = r)
         or exists (select 1 from jsonb_array_elements(p_exercises) e where e->>'exercise_id' = r)) then
    raise exception 'Explicit removed identities do not match current template' using errcode = '40001';
  end if;
  for v_removed in select value from jsonb_array_elements_text(p_removed_exercise_ids) loop
    delete from public.template_exercises where template_id = p_template_id and exercise_id = v_removed;
  end loop;

  -- Stage kept rows outside the old/new order ranges so swaps cannot violate the unique index.
  select coalesce(max(order_index), 0)::bigint + jsonb_array_length(p_exercises) + 1
    into v_offset from public.template_exercises where template_id = p_template_id;
  if v_offset * 2 > 2147483647 then
    raise exception 'Legacy exercise order requires review' using errcode = '22023';
  end if;
  update public.template_exercises set order_index = (order_index::bigint + v_offset)::integer
    where template_id = p_template_id;
  v_count := 0;
  for v_child in select value from jsonb_array_elements(p_exercises) loop
    if (v_child->>'order_index')::integer <> v_count then
      raise exception 'Exercise order must be contiguous' using errcode = '22023';
    end if;
    update public.template_exercises set order_index = v_count, sets = (v_child->>'sets')::integer,
      reps = (v_child->>'reps')::integer, rest_seconds = (v_child->>'rest_seconds')::integer
      where template_id = p_template_id and exercise_id = v_child->>'exercise_id';
    if not found then
      insert into public.template_exercises(template_id, exercise_id, order_index, sets, reps, rest_seconds)
        values(p_template_id, v_child->>'exercise_id', v_count, (v_child->>'sets')::integer,
          (v_child->>'reps')::integer, (v_child->>'rest_seconds')::integer);
    end if;
    v_count := v_count + 1;
  end loop;
  update public.workout_templates set name = p_name, is_deleted = p_is_deleted
    where id = p_template_id and user_id = v_owner returning * into v_parent;
  v_receipt := jsonb_build_object('request_id', p_request_id, 'template_id', p_template_id,
    'revision', v_parent.revision, 'exercise_count', v_count, 'server_updated_at', v_parent.updated_at);
  insert into public.template_write_receipts(user_id, request_id, payload, receipt)
    values(v_owner, p_request_id, v_payload, v_receipt);
  return v_receipt;
end;
$$;

revoke all on function public.bump_template_revision() from public, anon, authenticated;
revoke all on function public.bump_template_from_child() from public, anon, authenticated;
revoke all on function public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid) from public, anon;
grant execute on function public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid) to authenticated;
