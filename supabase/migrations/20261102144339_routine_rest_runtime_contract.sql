-- Routine rest is historical metadata, not runtime timer configuration.
-- Preserve existing ACLs, ownership guards, receipts and legacy payload support.
BEGIN;

CREATE OR REPLACE FUNCTION imfit_private.save_template_revision(
  p_request_id uuid,
  p_template_id uuid,
  p_expected_revision bigint,
  p_name text,
  p_exercises jsonb,
  p_removed_exercise_ids jsonb,
  p_is_deleted boolean,
  p_owner_id uuid
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
  v_track public.tracking_type_enum;
  v_dur integer;
  v_dist integer;
  v_reps integer;
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
        (e->>'order_index') is null or (e->>'sets') is null) or
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

    v_track := case when v_child ? 'tracking_type' and v_child->>'tracking_type' is not null
      then (v_child->>'tracking_type')::public.tracking_type_enum
      else (select default_tracking_type from public.exercises where id = v_child->>'exercise_id') end;
    v_dur := case when v_child ? 'target_duration_seconds' and v_child->>'target_duration_seconds' is not null
      then (v_child->>'target_duration_seconds')::integer else null end;
    v_dist := case when v_child ? 'target_distance_meters' and v_child->>'target_distance_meters' is not null
      then (v_child->>'target_distance_meters')::integer else null end;
    v_reps := case when v_child ? 'reps' and v_child->>'reps' is not null
      then (v_child->>'reps')::integer else null end;

    update public.template_exercises
    set order_index = v_count,
        sets = (v_child->>'sets')::integer,
        reps = v_reps,
        rest_seconds = coalesce((v_child->>'rest_seconds')::integer, rest_seconds),
        tracking_type = v_track,
        target_duration_seconds = v_dur,
        target_distance_meters = v_dist
    where template_id = p_template_id and exercise_id = v_child->>'exercise_id';
    if not found then
      insert into public.template_exercises(
        template_id, exercise_id, order_index, sets, reps, rest_seconds,
        tracking_type, target_duration_seconds, target_distance_meters
      ) values(
        p_template_id, v_child->>'exercise_id', v_count, (v_child->>'sets')::integer,
        v_reps, coalesce((v_child->>'rest_seconds')::integer, 60),
        v_track, v_dur, v_dist
      );
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
$function$;

CREATE OR REPLACE FUNCTION public.save_template_revision(
  p_request_id uuid,
  p_template_id uuid,
  p_expected_revision bigint,
  p_name text,
  p_exercises jsonb,
  p_removed_exercise_ids jsonb,
  p_is_deleted boolean,
  p_owner_id uuid
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_payload jsonb;
  v_result jsonb;
  v_child jsonb;
  v_message text;
  v_allowed_keys text[];
  v_track text;
  v_dur integer;
  v_dist integer;
  v_reps integer;
  v_sets integer;
  v_rest integer;
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

  v_allowed_keys := array['exercise_id','order_index','sets','reps','rest_seconds',
                          'tracking_type','target_duration_seconds','target_distance_meters'];

  for v_child in select value from jsonb_array_elements(p_exercises) loop
    if jsonb_typeof(v_child) is distinct from 'object' then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if (v_child - v_allowed_keys) <> '{}'::jsonb
       or jsonb_typeof(v_child->'exercise_id') is distinct from 'string'
       or not (v_child ?& array['exercise_id','order_index','sets']) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if jsonb_typeof(v_child->'order_index') is distinct from 'number'
       or jsonb_typeof(v_child->'sets') is distinct from 'number'
       or (v_child->>'order_index') !~ '^(0|[1-9][0-9]*)$'
       or (v_child->>'sets') !~ '^[1-9][0-9]*$'
       or (v_child->>'rest_seconds' is not null and (
         jsonb_typeof(v_child->'rest_seconds') is distinct from 'number'
         or (v_child->>'rest_seconds') !~ '^(0|[1-9][0-9]*)$'
         or (v_child->>'rest_seconds')::numeric not between 0 and 600)) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;

    v_sets := (v_child->>'sets')::integer;
    v_rest := coalesce((v_child->>'rest_seconds')::integer, 60);
    if (v_child->>'order_index')::numeric > 99
       or v_sets not between 1 and 20
       or v_rest not between 0 and 600
       or not exists (select 1 from public.exercises where id = v_child->>'exercise_id' and is_active) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;

    v_track := v_child->>'tracking_type';
    if v_track is not null then
      if not exists (select 1 from pg_enum where enumtypid = 'public.tracking_type_enum'::regtype and enumlabel = v_track) then
        perform imfit_private.fail('PT400', 'VALIDATION');
      end if;
      -- Verify tracking mode is allowed for this exercise
      if not exists (
        select 1 from public.exercise_tracking_modes
        where exercise_id = v_child->>'exercise_id'
          and tracking_type = v_track::public.tracking_type_enum
      ) then
        perform imfit_private.fail('PT400', 'VALIDATION');
      end if;
    else
      select default_tracking_type::text into v_track
      from public.exercises where id = v_child->>'exercise_id';
    end if;

    v_reps := case when v_child ? 'reps' and v_child->>'reps' is not null then (v_child->>'reps')::integer else null end;
    v_dur := case when v_child ? 'target_duration_seconds' and v_child->>'target_duration_seconds' is not null
      then (v_child->>'target_duration_seconds')::integer else null end;
    v_dist := case when v_child ? 'target_distance_meters' and v_child->>'target_distance_meters' is not null
      then (v_child->>'target_distance_meters')::integer else null end;

    case v_track
      when 'WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY' then
        if v_reps is null or v_reps not between 1 and 100 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'DURATION' then
        if v_dur is null or v_dur not between 1 and 86400 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'DISTANCE_DURATION' then
        if v_dur is null or v_dur not between 1 and 86400 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        if v_dist is not null and v_dist not between 1 and 1000000 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      else
        if v_reps is null or v_reps not between 1 and 100 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
    end case;
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
$function$;

CREATE OR REPLACE FUNCTION public.create_active_session(
  p_request_id uuid,
  p_session_id uuid,
  p_template_id uuid,
  p_expected_template_revision bigint
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_payload jsonb;
  v_result jsonb;
  v_template public.workout_templates%rowtype;
  v_row public.active_sessions%rowtype;
  v_child record;
  v_exercises jsonb := '[]';
  v_exercise jsonb;
  v_id uuid;
  v_now timestamptz := clock_timestamp();
  v_data jsonb;
  v_defaults jsonb;
begin
  v_payload := jsonb_build_object('template_id', p_template_id, 'expected_revision', p_expected_template_revision);
  v_result := imfit_private.request_replay(v_owner, p_request_id, 'create_active_session', p_session_id, v_payload);
  if v_result is not null then return v_result; end if;
  if p_template_id is null or p_expected_template_revision is null or p_expected_template_revision < 0 then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  if exists (select 1 from public.active_sessions s where s.user_id = v_owner
    and (s.version is null or s.schema_version is null) and not imfit_private.legacy_session_reviewed(s)) then
    perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED');
  end if;
  if exists (select 1 from public.active_sessions where user_id = v_owner and version is not null and schema_version is not null
    and (status in ('ACTIVE', 'PAUSED') or (status = 'EXPIRED' and review_required))) then
    perform imfit_private.fail('PT409', 'SESSION_REVIEW_REQUIRED');
  end if;
  if exists (select 1 from public.active_sessions where id = p_session_id) then
    perform imfit_private.fail('PT409', 'STATE_CONFLICT');
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_template_id::text, 1));
  select * into v_template from public.workout_templates where id = p_template_id and user_id = v_owner and not is_deleted for update;
  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  if v_template.revision <> p_expected_template_revision then perform imfit_private.fail('PT409', 'REVISION_CONFLICT'); end if;
  if (select count(*) from public.template_exercises where template_id = p_template_id) not between 1 and 100
    or exists (select 1 from public.template_exercises where template_id = p_template_id group by exercise_id having count(*) > 1) then
    perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED');
  end if;

  for v_child in (
    select te.*, ex.default_tracking_type, ex.modality
    from public.template_exercises te
    join public.exercises ex on ex.id = te.exercise_id
    where te.template_id = p_template_id
    order by te.order_index
  ) loop
    v_id := md5(p_session_id::text || ':' || v_child.id::text)::uuid;
    v_defaults := jsonb_build_object(
      'sets', v_child.sets,
      'reps', coalesce(v_child.reps, 10),
      'tracking_type', coalesce(v_child.tracking_type::text, v_child.default_tracking_type::text),
      'target_duration_seconds', v_child.target_duration_seconds,
      'target_distance_meters', v_child.target_distance_meters
    );
    v_exercise := imfit_private.new_session_exercise(
      v_owner, v_id, v_child.exercise_id, jsonb_array_length(v_exercises),
      v_defaults, 60
    );
    v_exercises := v_exercises || jsonb_build_array(v_exercise);
  end loop;

  v_data := jsonb_build_object(
    'exercises', v_exercises,
    'current_exercise_id', v_exercises#>'{0,id}',
    'used_exercise_ids', (select jsonb_agg(e->'id') from jsonb_array_elements(v_exercises) e),
    'used_set_ids', (select jsonb_agg(s->'id') from jsonb_array_elements(v_exercises) e, lateral jsonb_array_elements(e->'sets') s)
  );

  if octet_length(v_data::text) > 262144 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  insert into public.active_sessions(
    id, user_id, template_id, template_name, start_time, session_data, last_activity_at,
    expires_at, version, schema_version, status, review_required, template_revision
  ) values (
    p_session_id, v_owner, p_template_id, v_template.name, v_now, v_data, v_now,
    v_now + interval '30 days', 1, 2, 'ACTIVE', false, v_template.revision
  ) returning * into v_row;

  v_result := imfit_private.session_receipt(v_row, p_request_id, 'create_active_session');
  perform imfit_private.record_operation(v_owner, p_request_id, 'create_active_session', 'SESSION', p_session_id, v_payload, v_result);
  return v_result;
end;
$function$;

COMMIT;
