-- F1b local-first foundation. Deployment to the shared project is NOT authorized.
-- Preserve the original template implementation; add the global receipt contract.
set lock_timeout = '5s';
set statement_timeout = '30s';

create schema imfit_private;
revoke all on schema imfit_private from public, anon, authenticated;
alter default privileges in schema imfit_private revoke execute on functions from public;

create table imfit_private.operation_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  request_id uuid not null,
  operation text not null,
  resource_type text not null check (resource_type in ('TEMPLATE', 'SESSION')),
  resource_id uuid not null,
  payload jsonb not null,
  result jsonb not null,
  committed_at timestamptz not null default clock_timestamp(),
  committed_at_source text not null default 'COMMIT'
    check (committed_at_source in ('COMMIT', 'OBSERVED_BACKFILL')),
  retain_until timestamptz not null default (clock_timestamp() + interval '90 days'),
  primary key (user_id, request_id),
  check (retain_until >= committed_at + interval '90 days')
);
alter table imfit_private.operation_receipts enable row level security;
revoke all on imfit_private.operation_receipts from public, anon, authenticated;
create index operation_receipts_owner_time on imfit_private.operation_receipts
  (user_id, committed_at desc, request_id desc);

-- Old receipts have no original commit timestamp. Do not fabricate one or alter
-- their payload/result; label the observation explicitly and retain from now.
insert into imfit_private.operation_receipts
  (user_id, request_id, operation, resource_type, resource_id, payload, result,
   committed_at, committed_at_source, retain_until)
select user_id, request_id, 'save_template_revision', 'TEMPLATE',
  (payload->>'id')::uuid, payload, receipt, statement_timestamp(),
  'OBSERVED_BACKFILL', statement_timestamp() + interval '90 days'
from public.template_write_receipts;

create function imfit_private.require_owner() returns uuid
language plpgsql stable set search_path = '' as $$
declare v_owner uuid := auth.uid();
begin
  if v_owner is null then
    raise exception 'Authenticated identity required' using errcode = '42501';
  end if;
  return v_owner;
end;
$$;

create function imfit_private.fail(p_code text, p_message text, p_details jsonb default null)
returns void language plpgsql set search_path = '' as $$
begin
  raise exception using errcode = p_code, message = p_message,
    detail = coalesce(p_details::text, '{}');
end;
$$;

-- The caller holds this lock until the RPC transaction commits/rolls back.
create function imfit_private.request_replay(
  p_owner uuid, p_request uuid, p_operation text, p_resource uuid, p_payload jsonb
) returns jsonb language plpgsql set search_path = '' as $$
declare v_old imfit_private.operation_receipts%rowtype;
begin
  if p_request is null or p_resource is null then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_owner::text || ':' || p_request::text, 0));
  select * into v_old from imfit_private.operation_receipts
    where user_id = p_owner and request_id = p_request;
  if found then
    if v_old.operation <> p_operation or v_old.resource_id <> p_resource
       or v_old.payload is distinct from p_payload then
      perform imfit_private.fail('PT409', 'IDEMPOTENCY_MISMATCH');
    end if;
    return v_old.result;
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_owner::text, 2));
  return null;
end;
$$;

create function imfit_private.record_operation(
  p_owner uuid, p_request uuid, p_operation text, p_type text,
  p_resource uuid, p_payload jsonb, p_result jsonb
) returns void language sql set search_path = '' as $$
  insert into imfit_private.operation_receipts
    (user_id, request_id, operation, resource_type, resource_id, payload, result,
     committed_at, retain_until)
  values (p_owner, p_request, p_operation, p_type, p_resource, p_payload, p_result,
    statement_timestamp(), statement_timestamp() + interval '90 days');
$$;

create function imfit_private.operation_json(p_row imfit_private.operation_receipts)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('request_id', p_row.request_id, 'operation', p_row.operation,
    'resource_type', p_row.resource_type, 'resource_id', p_row.resource_id,
    'committed_at', p_row.committed_at, 'committed_at_source', p_row.committed_at_source,
    'result', p_row.result);
$$;

-- Bounded, query-bound cursors. A cursor is not an authorization token.
create function imfit_private.cursor_encode(p_query jsonb, p_key jsonb)
returns text language sql immutable set search_path = '' as $$
  select translate(replace(encode(convert_to(jsonb_build_object(
    'v', 1, 'query', p_query, 'key', p_key)::text, 'UTF8'), 'base64'), E'\n', ''), '+/', '-_');
$$;

create function imfit_private.cursor_decode(p_cursor text, p_query jsonb)
returns jsonb language plpgsql immutable set search_path = '' as $$
declare v_value jsonb;
begin
  if p_cursor is null then return null; end if;
  if length(p_cursor) > 8192 then
    perform imfit_private.fail('PT400', 'INVALID_CURSOR');
  end if;
  begin
    v_value := convert_from(decode(translate(p_cursor, '-_', '+/'), 'base64'), 'UTF8')::jsonb;
  exception when others then
    perform imfit_private.fail('PT400', 'INVALID_CURSOR');
  end;
  if v_value->'v' is distinct from '1'::jsonb
     or v_value->'query' is distinct from p_query
     or jsonb_typeof(v_value->'key') is distinct from 'array' then
    perform imfit_private.fail('PT400', 'INVALID_CURSOR');
  end if;
  return v_value->'key';
end;
$$;

-- Move, do not copy or edit the historical migration's implementation.
alter function public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid)
  set schema imfit_private;
revoke all on function imfit_private.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid)
  from public, anon, authenticated;

create function public.save_template_revision(
  p_request_id uuid, p_template_id uuid, p_expected_revision bigint,
  p_name text, p_exercises jsonb, p_removed_exercise_ids jsonb,
  p_is_deleted boolean, p_owner_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := imfit_private.require_owner();
  v_payload jsonb;
  v_result jsonb;
  v_child jsonb;
  v_message text;
begin
  if p_owner_id is distinct from v_owner then
    raise exception 'Authenticated identity required' using errcode = '42501';
  end if;
  v_payload := jsonb_build_object('id', p_template_id, 'owner', p_owner_id,
    'revision', p_expected_revision, 'name', p_name, 'exercises', p_exercises,
    'removed', p_removed_exercise_ids, 'deleted', p_is_deleted);
  v_result := imfit_private.request_replay(v_owner, p_request_id,
    'save_template_revision', p_template_id, v_payload);
  if v_result is not null then return v_result; end if;
  if p_expected_revision < 0 or p_name is null
     or char_length(btrim(p_name)) not between 2 and 100
     or p_is_deleted is null
     or jsonb_typeof(p_exercises) is distinct from 'array'
     or jsonb_typeof(p_removed_exercise_ids) is distinct from 'array'
     or octet_length(v_payload::text) > 262144 then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  if jsonb_array_length(p_exercises) > 100 then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  for v_child in select value from jsonb_array_elements(p_exercises) loop
    if jsonb_typeof(v_child) is distinct from 'object' then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if (v_child - array['exercise_id','order_index','sets','reps','rest_seconds']) <> '{}'::jsonb
       or jsonb_typeof(v_child->'exercise_id') is distinct from 'string'
       or not (v_child ?& array['exercise_id','order_index','sets','reps','rest_seconds']) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if jsonb_typeof(v_child->'order_index') is distinct from 'number'
       or jsonb_typeof(v_child->'sets') is distinct from 'number'
       or jsonb_typeof(v_child->'reps') is distinct from 'number'
       or jsonb_typeof(v_child->'rest_seconds') is distinct from 'number'
       or (v_child->>'order_index') !~ '^(0|[1-9][0-9]*)$'
       or (v_child->>'sets') !~ '^[1-9][0-9]*$'
       or (v_child->>'reps') !~ '^[1-9][0-9]*$'
       or (v_child->>'rest_seconds') !~ '^(0|[1-9][0-9]*)$' then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if (v_child->>'order_index')::numeric > 99
       or (v_child->>'sets')::numeric not between 1 and 20
       or (v_child->>'reps')::numeric not between 1 and 100
       or (v_child->>'rest_seconds')::numeric not between 0 and 600
       or not exists (select 1 from public.exercises
         where id = v_child->>'exercise_id' and is_active) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
  end loop;
  begin
    v_result := imfit_private.save_template_revision(p_request_id, p_template_id,
      p_expected_revision, p_name, p_exercises, p_removed_exercise_ids,
      p_is_deleted, p_owner_id);
  exception
    when serialization_failure then
      get stacked diagnostics v_message = message_text;
      if v_message like 'Legacy %' then
        perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED');
      end if;
      perform imfit_private.fail('PT409', 'REVISION_CONFLICT');
    when insufficient_privilege then
      perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND');
    when invalid_parameter_value or invalid_text_representation
      or numeric_value_out_of_range or check_violation or unique_violation then
      perform imfit_private.fail('PT400', 'VALIDATION');
  end;
  perform imfit_private.record_operation(v_owner, p_request_id,
    'save_template_revision', 'TEMPLATE', p_template_id, v_payload, v_result);
  return v_result;
end;
$$;

create function public.get_operation_status(p_request_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid := imfit_private.require_owner(); v_receipt jsonb;
begin
  if p_request_id is null then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  select imfit_private.operation_json(r) into v_receipt
  from imfit_private.operation_receipts r
  where user_id = v_owner and request_id = p_request_id;
  return jsonb_build_object('state', case when found then 'COMMITTED' else 'NOT_FOUND' end,
    'receipt', v_receipt);
end;
$$;

create function public.list_recent_operations(
  p_from timestamptz, p_until timestamptz, p_cursor text default null, p_limit integer default 20
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare
  v_owner uuid := imfit_private.require_owner(); v_query jsonb; v_key jsonb;
  v_time timestamptz; v_id uuid; v_rows jsonb; v_last jsonb; v_more boolean;
begin
  if p_from is null or p_until is null or p_until <= p_from
     or p_until - p_from > interval '90 days' or p_limit is null or p_limit not between 1 and 100 then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  v_query := jsonb_build_object('rpc','list_recent_operations','owner',v_owner,'from',p_from,'until',p_until);
  v_key := imfit_private.cursor_decode(p_cursor, v_query);
  if v_key is not null then
    begin
      if jsonb_array_length(v_key) <> 2 or v_key->>0 is null or v_key->>1 is null then
        perform imfit_private.fail('PT400', 'INVALID_CURSOR');
      end if;
      v_time := (v_key->>0)::timestamptz; v_id := (v_key->>1)::uuid;
    exception when invalid_text_representation or invalid_datetime_format or datetime_field_overflow then
      perform imfit_private.fail('PT400', 'INVALID_CURSOR');
    end;
  end if;
  select coalesce(jsonb_agg(imfit_private.operation_json(r) order by committed_at desc, request_id desc), '[]')
  into v_rows from (
    select * from imfit_private.operation_receipts
    where user_id = v_owner and committed_at >= p_from and committed_at < p_until
      and (v_key is null or (committed_at,request_id) < (v_time,v_id))
    order by committed_at desc, request_id desc limit p_limit + 1
  ) r;
  v_more := jsonb_array_length(v_rows) > p_limit;
  if v_more then v_rows := v_rows - p_limit; end if;
  v_last := v_rows->(jsonb_array_length(v_rows)-1);
  return jsonb_build_object('data', v_rows, 'page_info', jsonb_build_object(
    'limit', p_limit, 'has_more', v_more, 'next_cursor', case when v_more then
      imfit_private.cursor_encode(v_query,jsonb_build_array(v_last->'committed_at',v_last->'request_id')) end));
end;
$$;

revoke all on function public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid) from public, anon;
revoke all on function public.get_operation_status(uuid) from public, anon;
revoke all on function public.list_recent_operations(timestamptz,timestamptz,text,integer) from public, anon;
grant execute on function public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid) to authenticated;
grant execute on function public.get_operation_status(uuid) to authenticated;
grant execute on function public.list_recent_operations(timestamptz,timestamptz,text,integer) to authenticated;
revoke all on all functions in schema imfit_private from public, anon, authenticated;
notify pgrst, 'reload schema';
