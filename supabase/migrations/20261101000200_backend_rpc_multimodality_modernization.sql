-- IMFIT Phase 2 / Backend Services & RPC Modernization
-- Adds multi-modality tracking support across all RPCs while maintaining strict backward compatibility.

SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

-- 0. Relax template_exercises.reps to support duration-only targets
ALTER TABLE public.template_exercises ALTER COLUMN reps DROP NOT NULL;
ALTER TABLE public.template_exercises ALTER COLUMN reps DROP DEFAULT;
ALTER TABLE public.template_exercises DROP CONSTRAINT IF EXISTS template_exercises_reps_check;
ALTER TABLE public.template_exercises ADD CONSTRAINT template_exercises_reps_check
  CHECK (reps IS NULL OR (reps >= 1 AND reps <= 100));
-- 0.1 Update weight_text to accept both string and number JSON values
CREATE OR REPLACE FUNCTION imfit_private.weight_text(p_value jsonb)
 RETURNS text
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare v_text text := p_value#>>'{}';
begin
  if (jsonb_typeof(p_value) not in ('string', 'number'))
     or length(v_text) > 7 or v_text !~ '^(0|[1-9][0-9]{0,3})(\.[0-9]{1,2})?$' then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if v_text::numeric > 1000 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  return to_char(v_text::numeric,'FM9990.00');
end;
$function$;


-- 1. Shared Set Measurement Validation Function
CREATE OR REPLACE FUNCTION imfit_private.validate_set_measurement(
  p_tracking text,
  p_set jsonb,
  p_is_completed boolean
)
 RETURNS void
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare
  v_weight numeric;
  v_reps integer;
  v_dur integer;
  v_dist integer;
  v_add numeric;
  v_assist numeric;
begin
  if p_set is null or jsonb_typeof(p_set) <> 'object' then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_weight := case when p_set ? 'weight' and p_set->>'weight' is not null and p_set->>'weight' <> ''
    then (p_set->>'weight')::numeric else null end;
  v_reps := case when p_set ? 'reps' and p_set->>'reps' is not null and p_set->>'reps' <> ''
    then (p_set->>'reps')::integer else null end;
  v_dur := case when p_set ? 'duration_seconds' and p_set->>'duration_seconds' is not null and p_set->>'duration_seconds' <> ''
    then (p_set->>'duration_seconds')::integer else null end;
  v_dist := case when p_set ? 'distance_meters' and p_set->>'distance_meters' is not null and p_set->>'distance_meters' <> ''
    then (p_set->>'distance_meters')::integer else null end;
  v_add := case when p_set ? 'additional_weight' and p_set->>'additional_weight' is not null and p_set->>'additional_weight' <> ''
    then (p_set->>'additional_weight')::numeric else null end;
  v_assist := case when p_set ? 'assistance_weight' and p_set->>'assistance_weight' is not null and p_set->>'assistance_weight' <> ''
    then (p_set->>'assistance_weight')::numeric else null end;

  -- Validate range bounds when values are present
  if v_weight is not null and (v_weight < 0 or v_weight > 1000) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  if v_reps is not null and (v_reps < 0 or v_reps > 1000) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  if v_dur is not null and (v_dur < 0 or v_dur > 86400) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  if v_dist is not null and (v_dist < 0 or v_dist > 1000000) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  if v_add is not null and (v_add < 0 or v_add > 1000) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
  if v_assist is not null and (v_assist < 0 or v_assist > 1000) then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  if p_is_completed then
    case p_tracking
      when 'WEIGHT_REPS' then
        if coalesce(v_weight, 0) <= 0 or coalesce(v_reps, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'BODYWEIGHT_REPS' then
        if coalesce(v_reps, 0) <= 0 or (v_add is not null and v_add < 0) then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'ASSISTED_REPS' then
        if coalesce(v_reps, 0) <= 0 or coalesce(v_assist, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'REPS_ONLY' then
        if coalesce(v_reps, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'DURATION' then
        if coalesce(v_dur, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      when 'DISTANCE_DURATION' then
        if coalesce(v_dur, 0) <= 0 or coalesce(v_dist, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
      else
        -- Default to WEIGHT_REPS validation
        if coalesce(v_weight, 0) <= 0 or coalesce(v_reps, 0) <= 0 then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
    end case;
  end if;
end;
$function$;

-- 2. imfit_private.save_template_revision
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
        (e->>'order_index') is null or (e->>'sets') is null or (e->>'rest_seconds') is null) or
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
        rest_seconds = (v_child->>'rest_seconds')::integer,
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
        v_reps, (v_child->>'rest_seconds')::integer,
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

-- 3. public.save_template_revision
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
       or not (v_child ?& array['exercise_id','order_index','sets','rest_seconds']) then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;
    if jsonb_typeof(v_child->'order_index') is distinct from 'number'
       or jsonb_typeof(v_child->'sets') is distinct from 'number'
       or jsonb_typeof(v_child->'rest_seconds') is distinct from 'number'
       or (v_child->>'order_index') !~ '^(0|[1-9][0-9]*)$'
       or (v_child->>'sets') !~ '^[1-9][0-9]*$'
       or (v_child->>'rest_seconds') !~ '^(0|[1-9][0-9]*)$' then
      perform imfit_private.fail('PT400', 'VALIDATION');
    end if;

    v_sets := (v_child->>'sets')::integer;
    v_rest := (v_child->>'rest_seconds')::integer;
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

-- 4. public.get_template_detail
CREATE OR REPLACE FUNCTION public.get_template_detail(p_template_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare v_owner uuid := imfit_private.require_owner(); v_result jsonb;
begin
  select jsonb_build_object(
    'id', t.id,
    'name', t.name,
    'revision', t.revision,
    'updated_at', t.updated_at,
    'exercises', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'id', e.id,
        'exercise_id', e.exercise_id,
        'order_index', e.order_index,
        'sets', e.sets,
        'reps', coalesce(e.reps, 0),
        'rest_seconds', e.rest_seconds,
        'tracking_type', coalesce(e.tracking_type::text, ex.default_tracking_type::text),
        'modality', ex.modality::text,
        'target_duration_seconds', e.target_duration_seconds,
        'target_distance_meters', e.target_distance_meters
      ) order by e.order_index, e.id), '[]'::jsonb)
      from public.template_exercises e
      join public.exercises ex on ex.id = e.exercise_id
      where e.template_id = t.id
    )
  )
  into v_result
  from public.workout_templates t
  where t.id = p_template_id and t.user_id = v_owner and not t.is_deleted;

  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  return v_result;
end;
$function$;

-- 5. imfit_private.new_session_exercise
CREATE OR REPLACE FUNCTION imfit_private.new_session_exercise(
  p_owner uuid,
  p_id uuid,
  p_exercise text,
  p_order integer,
  p_defaults jsonb,
  p_rest integer
)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare
  v_prefill jsonb;
  v_sets jsonb;
  v_name text;
  v_category text;
  v_tracking public.tracking_type_enum;
  v_modality public.exercise_modality_enum;
begin
  select e.name, c.display_name, e.default_tracking_type, e.modality
  into v_name, v_category, v_tracking, v_modality
  from public.exercises e
  join public.muscle_categories c on c.id = e.muscle_category_id
  where e.id = p_exercise and e.is_active;

  if not found then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  if p_defaults ? 'tracking_type' and p_defaults->>'tracking_type' is not null then
    v_tracking := (p_defaults->>'tracking_type')::public.tracking_type_enum;
  end if;

  v_prefill := imfit_private.prefill(p_owner, p_exercise, p_defaults);

  select jsonb_agg(s || jsonb_build_object(
    'id', md5(p_id::text || ':' || (s->>'set_number'))::uuid,
    'is_completed', false
  ) order by (s->>'set_number')::integer) into v_sets
  from jsonb_array_elements(v_prefill->'sets') s;

  return jsonb_build_object(
    'id', p_id,
    'exercise_id', p_exercise,
    'name', v_name,
    'muscle_category', v_category,
    'tracking_type', v_tracking::text,
    'modality', v_modality::text,
    'order_index', p_order,
    'rest_seconds', p_rest,
    'input_defaults', p_defaults,
    'sets', v_sets,
    'max_set_number', jsonb_array_length(v_sets)
  );
end;
$function$;

-- 6. public.create_active_session
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
      v_defaults, v_child.rest_seconds
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

-- 7. public.update_active_session
CREATE OR REPLACE FUNCTION public.update_active_session(
  p_request_id uuid,
  p_session_id uuid,
  p_expected_version bigint,
  p_command jsonb
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
  v_command jsonb := p_command;
  v_type text;
  v_allowed text[];
  v_row public.active_sessions%rowtype;
  v_data jsonb;
  v_exercises jsonb;
  v_exercise jsonb;
  v_sets jsonb;
  v_set jsonb;
  v_eid uuid;
  v_sid uuid;
  v_e integer;
  v_s integer;
  v_int integer;
  v_track text;
  v_required text[];
  v_now timestamptz := clock_timestamp();
begin
  if jsonb_typeof(v_command) = 'object' then
    if v_command ? 'weight' then
      begin
        v_command := jsonb_set(v_command, '{weight}', to_jsonb(imfit_private.weight_text(v_command->'weight')));
      exception when sqlstate 'PT400' then null; end;
    end if;
    if v_command ? 'additional_weight' then
      begin
        v_command := jsonb_set(v_command, '{additional_weight}', to_jsonb(imfit_private.weight_text(v_command->'additional_weight')));
      exception when sqlstate 'PT400' then null; end;
    end if;
    if v_command ? 'assistance_weight' then
      begin
        v_command := jsonb_set(v_command, '{assistance_weight}', to_jsonb(imfit_private.weight_text(v_command->'assistance_weight')));
      exception when sqlstate 'PT400' then null; end;
    end if;
  end if;

  v_payload := jsonb_build_object('expected_version', p_expected_version, 'command', v_command);
  v_result := imfit_private.request_replay(v_owner, p_request_id, 'update_active_session', p_session_id, v_payload);
  if v_result is not null then return v_result; end if;

  if p_expected_version is null or p_expected_version < 1 or jsonb_typeof(v_command) is distinct from 'object'
     or jsonb_typeof(v_command->'type') is distinct from 'string' or octet_length(v_payload::text) > 262144 then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_type := v_command->>'type';
  v_required := case v_type
    when 'edit_set' then array['type','exercise_log_id','set_id']
    when 'complete_set' then array['type','exercise_log_id','set_id']
    when 'uncomplete_set' then array['type','exercise_log_id','set_id']
    when 'add_set' then array['type','exercise_log_id','set_id']
    when 'remove_set' then array['type','exercise_log_id','set_id']
    when 'add_exercise' then array['type','exercise_log_id','exercise_id']
    when 'remove_exercise' then array['type','exercise_log_id']
    when 'set_rest_seconds' then array['type','exercise_log_id','rest_seconds']
    when 'select_exercise' then array['type','exercise_log_id']
    when 'pause' then array['type']
    when 'resume' then array['type']
    when 'acknowledge_expiry' then array['type']
    else null end;

  v_allowed := case v_type
    when 'edit_set' then array['type','exercise_log_id','set_id','weight','reps','duration_seconds','distance_meters','additional_weight','assistance_weight']
    else v_required end;

  if v_required is null or not (v_command ?& v_required) or (v_command - v_allowed) <> '{}'::jsonb then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  select * into v_row from public.active_sessions where id = p_session_id and user_id = v_owner for update;
  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED'); end if;
  if v_row.version <> p_expected_version then
    perform imfit_private.fail('PT409', 'VERSION_CONFLICT', jsonb_build_object('current_version', v_row.version));
  end if;

  if v_row.status = 'EXPIRED' or (v_row.status in ('ACTIVE', 'PAUSED') and v_row.expires_at <= v_now) then
    if v_type <> 'acknowledge_expiry' or (v_row.status = 'EXPIRED' and not v_row.review_required) then
      perform imfit_private.fail('PT409', 'SESSION_REVIEW_REQUIRED');
    end if;
    update public.active_sessions set status = 'EXPIRED', review_required = false, version = version + 1
      where id = p_session_id returning * into v_row;
  else
    if v_row.status not in ('ACTIVE', 'PAUSED') then perform imfit_private.fail('PT409', 'SESSION_TERMINAL'); end if;
    v_data := v_row.session_data;
    v_exercises := v_data->'exercises';

    if v_type = 'pause' then
      if v_row.status <> 'ACTIVE' then perform imfit_private.fail('PT409', 'STATE_CONFLICT'); end if;
      v_row.status := 'PAUSED'; v_row.paused_at := v_now;
    elsif v_type = 'resume' then
      if v_row.status <> 'PAUSED' then perform imfit_private.fail('PT409', 'STATE_CONFLICT'); end if;
      v_row.status := 'ACTIVE';
      v_row.total_paused_ms := v_row.total_paused_ms + greatest(0, floor(extract(epoch from (v_now - v_row.paused_at)) * 1000)::bigint);
      v_row.paused_at := null;
    elsif v_type = 'acknowledge_expiry' then
      perform imfit_private.fail('PT409', 'STATE_CONFLICT');
    else
      v_eid := imfit_private.json_uuid(v_command->'exercise_log_id');
      select (ord - 1)::integer, e into v_e, v_exercise
      from jsonb_array_elements(v_exercises) with ordinality a(e, ord) where e->>'id' = v_eid::text;

      if v_type = 'add_exercise' then
        if v_e is not null or v_data->'used_exercise_ids' ? v_eid::text
           or jsonb_array_length(v_exercises) >= 100
           or jsonb_typeof(v_command->'exercise_id') is distinct from 'string'
           or exists (select 1 from jsonb_array_elements(v_exercises) e where e->>'exercise_id' = v_command->>'exercise_id') then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        v_exercise := imfit_private.new_session_exercise(
          v_owner, v_eid, v_command->>'exercise_id', jsonb_array_length(v_exercises),
          '{"sets":3,"reps":10}'::jsonb, 60
        );
        v_exercises := v_exercises || jsonb_build_array(v_exercise);
        v_data := jsonb_set(v_data, '{used_exercise_ids}', (v_data->'used_exercise_ids') || to_jsonb(v_eid::text));
        v_data := jsonb_set(v_data, '{used_set_ids}', (v_data->'used_set_ids') ||
          (select jsonb_agg(s->'id') from jsonb_array_elements(v_exercise->'sets') s));
        if jsonb_array_length(v_exercises) = 1 then
          v_data := jsonb_set(v_data, '{current_exercise_id}', to_jsonb(v_eid::text));
        end if;
      else
        if v_e is null then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
        v_sets := v_exercise->'sets';
        v_track := coalesce(v_exercise->>'tracking_type', 'WEIGHT_REPS');

        if v_type = 'remove_exercise' then
          if exists (select 1 from jsonb_array_elements(v_sets) s where (s->>'is_completed')::boolean) then
            perform imfit_private.fail('PT409', 'STATE_CONFLICT');
          end if;
          v_exercises := v_exercises - v_e;
          select coalesce(jsonb_agg(jsonb_set(e, '{order_index}', to_jsonb(ord - 1)) order by ord), '[]'::jsonb)
          into v_exercises from jsonb_array_elements(v_exercises) with ordinality a(e, ord);
          if v_data->>'current_exercise_id' = v_eid::text then
            v_data := jsonb_set(v_data, '{current_exercise_id}', coalesce(v_exercises#>'{0,id}', 'null'::jsonb));
          end if;
        elsif v_type = 'set_rest_seconds' then
          v_int := imfit_private.json_int(v_command->'rest_seconds', 0, 600);
          v_exercises := jsonb_set(v_exercises, array[v_e::text, 'rest_seconds'], to_jsonb(v_int));
        elsif v_type = 'select_exercise' then
          v_data := jsonb_set(v_data, '{current_exercise_id}', to_jsonb(v_eid::text));
        else
          v_sid := imfit_private.json_uuid(v_command->'set_id');
          select (ord - 1)::integer, s into v_s, v_set
          from jsonb_array_elements(v_sets) with ordinality a(s, ord) where s->>'id' = v_sid::text;

          if v_type = 'add_set' then
            if v_s is not null or v_data->'used_set_ids' ? v_sid::text or jsonb_array_length(v_sets) >= 20 then
              perform imfit_private.fail('PT400', 'VALIDATION');
            end if;
            v_int := (v_exercise->>'max_set_number')::integer;
            if v_int = 2147483647 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;
            v_int := v_int + 1;

            v_set := jsonb_build_object(
              'id', v_sid,
              'set_number', v_int,
              'is_completed', false,
              'weight', case when v_track = 'WEIGHT_REPS' then '0.00' end,
              'reps', case when v_track in ('WEIGHT_REPS','BODYWEIGHT_REPS','ASSISTED_REPS','REPS_ONLY')
                then coalesce((v_exercise#>>'{input_defaults,reps}')::integer, 10) end,
              'duration_seconds', case when v_track in ('DURATION','DISTANCE_DURATION')
                then (v_exercise#>>'{input_defaults,target_duration_seconds}')::integer end,
              'distance_meters', case when v_track = 'DISTANCE_DURATION'
                then (v_exercise#>>'{input_defaults,target_distance_meters}')::integer end,
              'additional_weight', case when v_track = 'BODYWEIGHT_REPS' then '0.00' end,
              'assistance_weight', case when v_track = 'ASSISTED_REPS' then '0.00' end
            );

            v_sets := v_sets || jsonb_build_array(v_set);
            v_exercise := jsonb_set(v_exercise, '{max_set_number}', to_jsonb(v_int));
            v_data := jsonb_set(v_data, '{used_set_ids}', (v_data->'used_set_ids') || to_jsonb(v_sid::text));
          else
            if v_s is null then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
            if v_type in ('edit_set', 'remove_set') and (v_set->>'is_completed')::boolean then
              perform imfit_private.fail('PT409', 'STATE_CONFLICT');
            end if;

            if v_type = 'edit_set' then
              if v_command ? 'weight' then
                v_set := jsonb_set(v_set, '{weight}', to_jsonb(imfit_private.weight_text(v_command->'weight')));
              end if;
              if v_command ? 'reps' then
                v_set := jsonb_set(v_set, '{reps}', to_jsonb(imfit_private.json_int(v_command->'reps', 0, 1000)));
              end if;
              if v_command ? 'duration_seconds' then
                v_set := jsonb_set(v_set, '{duration_seconds}', to_jsonb(imfit_private.json_int(v_command->'duration_seconds', 0, 86400)));
              end if;
              if v_command ? 'distance_meters' then
                v_set := jsonb_set(v_set, '{distance_meters}', to_jsonb(imfit_private.json_int(v_command->'distance_meters', 0, 1000000)));
              end if;
              if v_command ? 'additional_weight' then
                v_set := jsonb_set(v_set, '{additional_weight}', to_jsonb(imfit_private.weight_text(v_command->'additional_weight')));
              end if;
              if v_command ? 'assistance_weight' then
                v_set := jsonb_set(v_set, '{assistance_weight}', to_jsonb(imfit_private.weight_text(v_command->'assistance_weight')));
              end if;
              -- Validate bounds of updated set
              perform imfit_private.validate_set_measurement(v_track, v_set, false);

            elsif v_type = 'complete_set' then
              -- Validate completion eligibility according to tracking_type
              perform imfit_private.validate_set_measurement(v_track, v_set, true);
              v_set := jsonb_set(v_set, '{is_completed}', 'true');
            elsif v_type = 'uncomplete_set' then
              v_set := jsonb_set(v_set, '{is_completed}', 'false');
            end if;

            if v_type = 'remove_set' then
              v_sets := v_sets - v_s;
            else
              v_sets := jsonb_set(v_sets, array[v_s::text], v_set);
            end if;
          end if;
          v_exercise := jsonb_set(v_exercise, '{sets}', v_sets);
          v_exercises := jsonb_set(v_exercises, array[v_e::text], v_exercise);
        end if;
      end if;
    end if;

    v_data := jsonb_set(v_data, '{exercises}', v_exercises);
    if octet_length(v_data::text) > 262144 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

    update public.active_sessions
    set session_data = v_data, status = v_row.status, paused_at = v_row.paused_at,
        total_paused_ms = v_row.total_paused_ms, version = version + 1,
        last_activity_at = v_now, expires_at = v_now + interval '30 days'
    where id = p_session_id returning * into v_row;
  end if;

  v_result := imfit_private.session_receipt(v_row, p_request_id, 'update_active_session');
  perform imfit_private.record_operation(v_owner, p_request_id, 'update_active_session', 'SESSION', p_session_id, v_payload, v_result);
  return v_result;
end;
$function$;

-- 8. imfit_private.terminal_session
CREATE OR REPLACE FUNCTION imfit_private.terminal_session(
  p_request uuid,
  p_session uuid,
  p_version bigint,
  p_finish boolean
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_payload jsonb;
  v_result jsonb;
  v_operation text := case when p_finish then 'finish_active_session' else 'cancel_active_session' end;
  v_status text := case when p_finish then 'COMPLETED' else 'CANCELLED' end;
  v_row public.active_sessions%rowtype;
  v_now timestamptz := clock_timestamp();
  v_workout uuid;
  v_e jsonb;
  v_s jsonb;
  v_volume numeric := 0;
  v_add_vol numeric := 0;
  v_sets integer := 0;
  v_reps integer := 0;
  v_duration integer := 0;
  v_distance integer := 0;
  v_e_volume numeric;
  v_e_add_vol numeric;
  v_e_sets integer;
  v_e_reps integer;
  v_e_duration integer;
  v_e_distance integer;
  v_track text;
  v_modality text;
  v_quality text;
begin
  v_payload := jsonb_build_object('expected_version', p_version);
  v_result := imfit_private.request_replay(v_owner, p_request, v_operation, p_session, v_payload);
  if v_result is not null then return v_result; end if;
  if p_version is null or p_version < 1 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  select * into v_row from public.active_sessions where id = p_session and user_id = v_owner for update;
  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED'); end if;

  if v_row.status <> v_status then
    if v_row.status in ('COMPLETED', 'CANCELLED') then
      perform imfit_private.fail('PT409', 'SESSION_TERMINAL', jsonb_build_object(
        'terminal_status', v_row.status, 'workout_id', v_row.completed_workout_id));
    end if;
    if v_row.status = 'EXPIRED' or v_row.expires_at <= v_now then perform imfit_private.fail('PT409', 'SESSION_REVIEW_REQUIRED'); end if;
    if v_row.version <> p_version then perform imfit_private.fail('PT409', 'VERSION_CONFLICT', jsonb_build_object('current_version', v_row.version)); end if;

    if p_finish then
      -- Verify there is at least one completed set
      select count(*)::integer into v_sets
      from jsonb_array_elements(v_row.session_data->'exercises') e,
           lateral jsonb_array_elements(e->'sets') s
      where (s->>'is_completed')::boolean;

      if v_sets = 0 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

      -- Validate each completed set against its tracking type
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          perform imfit_private.validate_set_measurement(v_track, v_s, true);
        end loop;
      end loop;

      v_workout := md5(p_session::text || ':workout')::uuid;
      v_quality := case when v_row.schema_version >= 2 then 'VERIFIED' else 'LEGACY_UNVERIFIED' end;

      -- Pre-calculate workout-level aggregates across all completed sets
      v_volume := 0; v_add_vol := 0; v_reps := 0; v_duration := 0; v_distance := 0;
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          if v_track = 'WEIGHT_REPS' then
            v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
          elsif v_track = 'BODYWEIGHT_REPS' then
            v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
          end if;
          if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
            v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
          end if;
          if v_track in ('DURATION', 'DISTANCE_DURATION') then
            v_duration := v_duration + coalesce((v_s->>'duration_seconds')::integer, 0);
          end if;
          if v_track = 'DISTANCE_DURATION' then
            v_distance := v_distance + coalesce((v_s->>'distance_meters')::integer, 0);
          end if;
        end loop;
      end loop;

      insert into public.workout_logs(
        id, user_id, template_id, template_name, date, start_time, end_time,
        total_volume, total_sets, total_reps, total_duration_seconds, total_distance_meters,
        additional_volume, data_quality, source_session_id
      ) values (
        v_workout, v_owner, v_row.template_id, v_row.template_name, (v_row.start_time at time zone 'UTC')::date,
        v_row.start_time, greatest(v_now, v_row.start_time + interval '1 microsecond'),
        v_volume, v_sets, v_reps, v_duration, v_distance, v_add_vol, v_quality, p_session
      );

      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        if not exists (select 1 from jsonb_array_elements(v_e->'sets') s where (s->>'is_completed')::boolean) then
          continue;
        end if;

        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        select modality::text into v_modality from public.exercises where id = v_e->>'exercise_id';
        v_modality := coalesce(v_e->>'modality', v_modality, 'STRENGTH');

        v_e_volume := 0; v_e_add_vol := 0; v_e_sets := 0; v_e_reps := 0; v_e_duration := 0; v_e_distance := 0;
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          v_e_sets := v_e_sets + 1;
          if v_track = 'WEIGHT_REPS' then
            v_e_volume := v_e_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
          elsif v_track = 'BODYWEIGHT_REPS' then
            v_e_add_vol := v_e_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
          end if;
          if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
            v_e_reps := v_e_reps + coalesce((v_s->>'reps')::integer, 0);
          end if;
          if v_track in ('DURATION', 'DISTANCE_DURATION') then
            v_e_duration := v_e_duration + coalesce((v_s->>'duration_seconds')::integer, 0);
          end if;
          if v_track = 'DISTANCE_DURATION' then
            v_e_distance := v_e_distance + coalesce((v_s->>'distance_meters')::integer, 0);
          end if;
        end loop;

        insert into public.exercise_logs(
          id, workout_log_id, exercise_id, exercise_name, muscle_category,
          order_index, total_volume, total_sets, total_reps, total_duration_seconds,
          total_distance_meters, additional_volume, tracking_type, modality,
          metric_quality, input_defaults
        ) values (
          (v_e->>'id')::uuid, v_workout, v_e->>'exercise_id', v_e->>'name', v_e->>'muscle_category',
          (v_e->>'order_index')::integer, v_e_volume, v_e_sets, v_e_reps, v_e_duration,
          v_e_distance, v_e_add_vol, v_track::public.tracking_type_enum, v_modality::public.exercise_modality_enum,
          v_quality, v_e->'input_defaults'
        );

        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          insert into public.workout_sets(
            id, exercise_log_id, workout_log_id, exercise_id, set_number,
            weight, reps, duration_seconds, distance_meters, additional_weight,
            assistance_weight, is_completed
          ) values (
            (v_s->>'id')::uuid, (v_e->>'id')::uuid, v_workout, v_e->>'exercise_id',
            (v_s->>'set_number')::integer,
            coalesce((v_s->>'weight')::numeric, 0),
            coalesce((v_s->>'reps')::integer, 0),
            (v_s->>'duration_seconds')::integer,
            (v_s->>'distance_meters')::integer,
            (v_s->>'additional_weight')::numeric,
            (v_s->>'assistance_weight')::numeric,
            true
          );
        end loop;
      end loop;
    end if;

    update public.active_sessions
    set status = v_status, version = version + 1, review_required = false,
        completed_workout_id = v_workout
    where id = p_session returning * into v_row;
  end if;

  v_result := imfit_private.session_receipt(v_row, p_request, v_operation);
  perform imfit_private.record_operation(v_owner, p_request, v_operation, 'SESSION', p_session, v_payload, v_result);
  return v_result;
end;
$function$;

-- 9. public.persist_local_workout_aggregate (MAIN HYBRID SYNC PATH)
CREATE OR REPLACE FUNCTION public.persist_local_workout_aggregate(
  p_workout jsonb,
  p_exercises jsonb,
  p_sets jsonb
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
  v_add_vol numeric;
  v_dur integer;
  v_dist integer;
  v_total_reps integer := 0;
  v_total_volume numeric := 0;
  v_total_add_vol numeric := 0;
  v_total_duration integer := 0;
  v_total_distance integer := 0;
  v_now timestamptz := statement_timestamp();
  v_result jsonb;
  v_track text;
  v_default_track public.tracking_type_enum;
  v_modality public.exercise_modality_enum;
  v_quality text;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if jsonb_typeof(p_workout) is distinct from 'object'
    or jsonb_typeof(p_exercises) is distinct from 'array'
    or jsonb_typeof(p_sets) is distinct from 'array'
    or octet_length(jsonb_build_array(p_workout, p_exercises, p_sets)::text) > 262144 then
    raise exception 'Invalid aggregate envelope' using errcode = '22023';
  end if;

  v_id := (p_workout->>'id')::uuid;
  if v_id is null or (p_workout->>'user_id')::uuid is distinct from v_owner then
    raise exception 'Workout owner mismatch' using errcode = '42501';
  end if;

  v_payload := jsonb_build_object(
    'workout', p_workout,
    'exercises', (select coalesce(jsonb_agg(e order by e->>'id'), '[]'::jsonb) from jsonb_array_elements(p_exercises) e),
    'sets', (select coalesce(jsonb_agg(s order by s->>'id'), '[]'::jsonb) from jsonb_array_elements(p_sets) s)
  );

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_id::text, 73));
  select * into v_receipt from imfit_private.local_workout_receipts where workout_id = v_id;
  if found then
    if v_receipt.user_id <> v_owner then raise exception 'Workout owner mismatch' using errcode = '42501'; end if;
    if v_receipt.payload is distinct from v_payload then
      raise exception 'IDEMPOTENCY_MISMATCH' using errcode = 'PT409';
    end if;
    return v_receipt.result;
  end if;

  if exists (select 1 from public.workout_logs where id = v_id) then
    raise exception 'WORKOUT_ALREADY_EXISTS' using errcode = 'PT409';
  end if;

  if not exists (select 1 from public.workout_templates where id = (p_workout->>'template_id')::uuid and user_id = v_owner)
    or nullif(btrim(p_workout->>'template_name'), '') is null
    or (p_workout->>'start_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz <= (p_workout->>'start_time')::timestamptz
    or (p_workout->>'date')::date is distinct from ((p_workout->>'start_time')::timestamptz at time zone 'UTC')::date
    or p_workout->>'deleted_at' is not null
    or jsonb_array_length(p_exercises) not between 1 and 100
    or jsonb_array_length(p_sets) not between 1 and 2000 then
    raise exception 'Invalid completed workout' using errcode = '22023';
  end if;

  if (select count(distinct e->>'id') from jsonb_array_elements(p_exercises) e) <> jsonb_array_length(p_exercises)
    or (select count(distinct e->>'order_index') from jsonb_array_elements(p_exercises) e) <> jsonb_array_length(p_exercises)
    or (select count(distinct s->>'id') from jsonb_array_elements(p_sets) s) <> jsonb_array_length(p_sets) then
    raise exception 'Duplicate snapshot identifiers' using errcode = '22023';
  end if;

  -- Validate sets
  for v_s in select value from jsonb_array_elements(p_sets) loop
    if jsonb_typeof(v_s) <> 'object' or (v_s->>'id')::uuid is null
      or (v_s->>'workout_log_id')::uuid is distinct from v_id
      or (v_s->>'is_completed')::boolean is distinct from true
      or (v_s->>'set_number')::integer is null or (v_s->>'set_number')::integer < 1
      or not exists (
        select 1 from jsonb_array_elements(p_exercises) e
        where (e->>'id')::uuid = (v_s->>'exercise_log_id')::uuid
          and e->>'exercise_id' = v_s->>'exercise_id'
      ) then
      raise exception 'Invalid completed set snapshot' using errcode = '22023';
    end if;

    -- Resolve tracking type for this set
    select e->>'tracking_type' into v_track
    from jsonb_array_elements(p_exercises) e
    where (e->>'id')::uuid = (v_s->>'exercise_log_id')::uuid;

    if v_track is null then
      select default_tracking_type::text into v_track
      from public.exercises where id = v_s->>'exercise_id';
    end if;
    v_track := coalesce(v_track, 'WEIGHT_REPS');

    -- Validate set measurement via shared validator
    begin
      perform imfit_private.validate_set_measurement(v_track, v_s, true);
    exception when sqlstate 'PT400' then
      raise exception 'Set measurement invalid for tracking type' using errcode = '22023';
    end;
  end loop;

  -- Validate exercises and calculate totals
  for v_e in select value from jsonb_array_elements(p_exercises) loop
    if jsonb_typeof(v_e) <> 'object' or (v_e->>'id')::uuid is null
      or (v_e->>'workout_log_id')::uuid is distinct from v_id
      or nullif(btrim(v_e->>'exercise_name'), '') is null
      or nullif(btrim(v_e->>'muscle_category'), '') is null
      or (v_e->>'order_index')::integer is null or (v_e->>'order_index')::integer < 0
      or not exists (select 1 from public.exercises where id = v_e->>'exercise_id') then
      raise exception 'Invalid exercise snapshot' using errcode = '22023';
    end if;

    select default_tracking_type, modality into v_default_track, v_modality
    from public.exercises where id = v_e->>'exercise_id';
    v_track := coalesce(v_e->>'tracking_type', v_default_track::text, 'WEIGHT_REPS');

    v_count := 0; v_reps := 0; v_volume := 0; v_add_vol := 0; v_dur := 0; v_dist := 0;
    for v_s in select value from jsonb_array_elements(p_sets) where (value->>'exercise_log_id')::uuid = (v_e->>'id')::uuid loop
      v_count := v_count + 1;
      if v_track = 'WEIGHT_REPS' then
        v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
      elsif v_track = 'BODYWEIGHT_REPS' then
        v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
      end if;
      if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
        v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
      end if;
      if v_track in ('DURATION', 'DISTANCE_DURATION') then
        v_dur := v_dur + coalesce((v_s->>'duration_seconds')::integer, 0);
      end if;
      if v_track = 'DISTANCE_DURATION' then
        v_dist := v_dist + coalesce((v_s->>'distance_meters')::integer, 0);
      end if;
    end loop;

    if v_count = 0 or (select count(distinct s->>'set_number') from jsonb_array_elements(p_sets) s
        where s->>'exercise_log_id' = v_e->>'id') <> v_count then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;

    -- Verify client-reported exercise totals if present
    if v_e ? 'total_sets' and v_count is distinct from (v_e->>'total_sets')::integer then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;
    if v_e ? 'total_reps' and v_reps is distinct from (v_e->>'total_reps')::integer then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;
    if v_e ? 'total_volume' and (v_e->>'total_volume')::numeric is not null
       and abs(v_volume - (v_e->>'total_volume')::numeric) > greatest(0.01, abs(v_volume) * 0.00001) then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;

    v_total_reps := v_total_reps + v_reps;
    v_total_volume := v_total_volume + v_volume;
    v_total_add_vol := v_total_add_vol + v_add_vol;
    v_total_duration := v_total_duration + v_dur;
    v_total_distance := v_total_distance + v_dist;
  end loop;

  -- Verify client-reported workout totals
  if jsonb_array_length(p_sets) is distinct from (p_workout->>'total_sets')::integer
    or v_total_reps is distinct from (p_workout->>'total_reps')::integer
    or (p_workout->>'total_volume')::numeric is null
    or abs(v_total_volume - (p_workout->>'total_volume')::numeric) > greatest(0.01, abs(v_total_volume) * 0.00001) then
    raise exception 'Workout totals do not match snapshot' using errcode = '22023';
  end if;

  v_quality := case when exists (select 1 from jsonb_array_elements(p_exercises) e where e ? 'tracking_type')
    then 'VERIFIED' else 'LEGACY_UNVERIFIED' end;

  insert into public.workout_logs(
    id, user_id, template_id, template_name, date, start_time, end_time,
    total_volume, total_sets, total_reps, total_duration_seconds, total_distance_meters,
    additional_volume, data_quality, created_at, updated_at
  ) values (
    v_id, v_owner, (p_workout->>'template_id')::uuid, p_workout->>'template_name',
    (p_workout->>'date')::date, (p_workout->>'start_time')::timestamptz, (p_workout->>'end_time')::timestamptz,
    v_total_volume, jsonb_array_length(p_sets), v_total_reps, v_total_duration, v_total_distance,
    v_total_add_vol, v_quality, v_now, v_now
  );

  for v_e in select value from jsonb_array_elements(p_exercises) loop
    select default_tracking_type, modality into v_default_track, v_modality
    from public.exercises where id = v_e->>'exercise_id';
    v_track := coalesce(v_e->>'tracking_type', v_default_track::text, 'WEIGHT_REPS');

    v_count := 0; v_reps := 0; v_volume := 0; v_add_vol := 0; v_dur := 0; v_dist := 0;
    for v_s in select value from jsonb_array_elements(p_sets) where (value->>'exercise_log_id')::uuid = (v_e->>'id')::uuid loop
      v_count := v_count + 1;
      if v_track = 'WEIGHT_REPS' then
        v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
      elsif v_track = 'BODYWEIGHT_REPS' then
        v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
      end if;
      if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
        v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
      end if;
      if v_track in ('DURATION', 'DISTANCE_DURATION') then
        v_dur := v_dur + coalesce((v_s->>'duration_seconds')::integer, 0);
      end if;
      if v_track = 'DISTANCE_DURATION' then
        v_dist := v_dist + coalesce((v_s->>'distance_meters')::integer, 0);
      end if;
    end loop;

    insert into public.exercise_logs(
      id, workout_log_id, exercise_id, exercise_name, muscle_category,
      order_index, total_volume, total_sets, total_reps, total_duration_seconds,
      total_distance_meters, additional_volume, tracking_type, modality,
      metric_quality, input_defaults, created_at, updated_at
    ) values (
      (v_e->>'id')::uuid, v_id, v_e->>'exercise_id', v_e->>'exercise_name', v_e->>'muscle_category',
      (v_e->>'order_index')::integer, v_volume, v_count, v_reps, v_dur, v_dist, v_add_vol,
      v_track::public.tracking_type_enum, v_modality::public.exercise_modality_enum, v_quality,
      v_e->'input_defaults', v_now, v_now
    );
  end loop;

  for v_s in select value from jsonb_array_elements(p_sets) loop
    insert into public.workout_sets(
      id, exercise_log_id, workout_log_id, exercise_id, set_number,
      weight, reps, duration_seconds, distance_meters, additional_weight,
      assistance_weight, is_completed, created_at, updated_at
    ) values (
      (v_s->>'id')::uuid, (v_s->>'exercise_log_id')::uuid, v_id, v_s->>'exercise_id',
      (v_s->>'set_number')::integer,
      coalesce((v_s->>'weight')::numeric, 0),
      coalesce((v_s->>'reps')::integer, 0),
      (v_s->>'duration_seconds')::integer,
      (v_s->>'distance_meters')::integer,
      (v_s->>'additional_weight')::numeric,
      (v_s->>'assistance_weight')::numeric,
      true, v_now, v_now
    );
  end loop;

  v_result := jsonb_build_object(
    'workout_id', v_id,
    'exercise_count', jsonb_array_length(p_exercises),
    'set_count', jsonb_array_length(p_sets),
    'server_updated_at', v_now
  );
  insert into imfit_private.local_workout_receipts(workout_id, user_id, payload, result)
    values(v_id, v_owner, v_payload, v_result);
  return v_result;

exception when unique_violation or foreign_key_violation or check_violation then
  raise exception 'Invalid snapshot or conflicting identifier' using errcode = 'PT409';
end;
$function$;

-- 10. imfit_private.prefill
CREATE OR REPLACE FUNCTION imfit_private.prefill(p_owner uuid, p_exercise text, p_defaults jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare
  v_sets integer;
  v_reps integer;
  v_log public.exercise_logs%rowtype;
  v_rows jsonb;
  v_source text := 'DEFAULTS';
  v_workout uuid;
  v_track public.tracking_type_enum;
  v_allowed_keys text[];
begin
  if p_exercise is null or not exists(select 1 from public.exercises where id = p_exercise and is_active)
     or jsonb_typeof(p_defaults) is distinct from 'object' then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_allowed_keys := array['sets', 'reps', 'tracking_type', 'target_duration_seconds', 'target_distance_meters'];
  if (p_defaults - v_allowed_keys) <> '{}'::jsonb then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_sets := imfit_private.json_int(coalesce(p_defaults->'sets', '3'::jsonb), 1, 20);
  v_reps := imfit_private.json_int(coalesce(p_defaults->'reps', '10'::jsonb), 1, 100);

  v_track := case when p_defaults ? 'tracking_type' and p_defaults->>'tracking_type' is not null
    then (p_defaults->>'tracking_type')::public.tracking_type_enum
    else (select default_tracking_type from public.exercises where id = p_exercise) end;

  select w.id into v_workout from public.workout_logs w
  where w.user_id = p_owner and w.deleted_at is null and exists (
    select 1 from public.exercise_logs e join public.workout_sets s on s.exercise_log_id = e.id
    where e.workout_log_id = w.id and e.exercise_id = p_exercise and s.is_completed
      and (e.tracking_type is null or e.tracking_type = v_track)
  )
  order by w.end_time desc, w.id desc limit 1;

  if v_workout is not null then
    if (select count(*) from public.exercise_logs where workout_log_id = v_workout and exercise_id = p_exercise) = 1 then
      select * into v_log from public.exercise_logs where workout_log_id = v_workout and exercise_id = p_exercise;
      if v_log.input_defaults = p_defaults or (v_log.tracking_type is not null and v_log.tracking_type = v_track) then
        v_source := 'HISTORY';
      end if;
    end if;
  end if;

  select jsonb_agg(jsonb_build_object(
    'set_number', n,
    'weight', case when s.id is null then '0.00' else to_char(coalesce(s.weight, 0), 'FM9990.00') end,
    'reps', coalesce(s.reps, v_reps),
    'duration_seconds', s.duration_seconds,
    'distance_meters', s.distance_meters,
    'additional_weight', case when s.additional_weight is not null then to_char(s.additional_weight, 'FM9990.00') end,
    'assistance_weight', case when s.assistance_weight is not null then to_char(s.assistance_weight, 'FM9990.00') end
  ) order by n) into v_rows
  from generate_series(1, v_sets) n
  left join public.workout_sets s on v_source = 'HISTORY' and s.exercise_log_id = v_log.id
    and s.set_number = n and s.is_completed;

  return jsonb_build_object(
    'source', v_source,
    'source_workout_id', case when v_source = 'HISTORY' then v_workout end,
    'input_defaults', p_defaults,
    'sets', v_rows
  );
end;
$function$;

-- 11. imfit_private.workout_summary
CREATE OR REPLACE FUNCTION imfit_private.workout_summary(p_row public.workout_logs)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO ''
AS $function$
  select jsonb_build_object(
    'id', p_row.id,
    'template_id', p_row.template_id,
    'template_name', p_row.template_name,
    'start_time', p_row.start_time,
    'end_time', p_row.end_time,
    'duration_seconds', floor(extract(epoch from (p_row.end_time - p_row.start_time)))::bigint,
    'total_volume', p_row.total_volume::text,
    'total_sets', p_row.total_sets,
    'total_reps', p_row.total_reps,
    'total_duration_seconds', coalesce(p_row.total_duration_seconds, 0),
    'total_distance_meters', coalesce(p_row.total_distance_meters, 0),
    'additional_volume', coalesce(p_row.additional_volume, 0)::text,
    'data_quality', coalesce(p_row.data_quality, 'LEGACY_UNVERIFIED')
  );
$function$;

-- 12. public.get_progress_summary
CREATE OR REPLACE FUNCTION public.get_progress_summary(
  p_start_date date,
  p_end_date date,
  p_timezone text
)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_result jsonb;
begin
  perform imfit_private.validate_window(p_start_date, p_end_date, p_timezone);

  select jsonb_build_object(
    'start_date', p_start_date,
    'end_date', p_end_date,
    'timezone', p_timezone,
    'workout_count', count(*),
    -- legacy fields for v1 clients
    'total_volume', coalesce(sum(total_volume), 0)::text,
    'total_sets', coalesce(sum(total_sets), 0),
    'total_reps', coalesce(sum(total_reps), 0),
    'duration_seconds', coalesce(sum(floor(extract(epoch from (end_time - start_time)))), 0)::bigint,
    -- typed fields for v2 clients
    'external_volume_kg', coalesce(sum(total_volume), 0)::text,
    'additional_volume_kg', coalesce(sum(additional_volume), 0)::text,
    'hold_duration_seconds', coalesce((
      select sum(el.total_duration_seconds)
      from public.exercise_logs el
      join public.workout_logs wl on wl.id = el.workout_log_id
      where wl.user_id = v_owner and wl.deleted_at is null
        and el.modality = 'ISOMETRIC'
        and wl.start_time >= (p_start_date::timestamp at time zone p_timezone)
        and wl.start_time < (p_end_date::timestamp at time zone p_timezone)
    ), 0)::bigint,
    'cardio_duration_seconds', coalesce((
      select sum(el.total_duration_seconds)
      from public.exercise_logs el
      join public.workout_logs wl on wl.id = el.workout_log_id
      where wl.user_id = v_owner and wl.deleted_at is null
        and el.modality in ('CARDIO', 'CONDITIONING')
        and wl.start_time >= (p_start_date::timestamp at time zone p_timezone)
        and wl.start_time < (p_end_date::timestamp at time zone p_timezone)
    ), 0)::bigint,
    'cardio_distance_meters', coalesce(sum(total_distance_meters), 0)::bigint,
    'workout_duration_seconds', coalesce(sum(floor(extract(epoch from (end_time - start_time)))), 0)::bigint
  )
  into v_result
  from public.workout_logs
  where user_id = v_owner and deleted_at is null
    and start_time >= (p_start_date::timestamp at time zone p_timezone)
    and start_time < (p_end_date::timestamp at time zone p_timezone);

  return v_result;
end;
$function$;

-- 13. public.get_workout_detail
CREATE OR REPLACE FUNCTION public.get_workout_detail(p_workout_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_result jsonb;
begin
  select imfit_private.workout_summary(w) || jsonb_build_object('exercise_logs', (
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', e.id,
      'exercise_id', e.exercise_id,
      'exercise_name', e.exercise_name,
      'muscle_category', e.muscle_category,
      'order_index', e.order_index,
      'input_defaults', e.input_defaults,
      'total_volume', e.total_volume::text,
      'total_sets', e.total_sets,
      'total_reps', e.total_reps,
      'tracking_type', coalesce(e.tracking_type::text, ex.default_tracking_type::text),
      'modality', coalesce(e.modality::text, ex.modality::text),
      'total_duration_seconds', coalesce(e.total_duration_seconds, 0),
      'total_distance_meters', coalesce(e.total_distance_meters, 0),
      'additional_volume', coalesce(e.additional_volume, 0)::text,
      'metric_quality', coalesce(e.metric_quality, 'LEGACY_UNVERIFIED'),
      'sets', (
        select coalesce(jsonb_agg(jsonb_build_object(
          'id', s.id,
          'set_number', s.set_number,
          'weight', s.weight::text,
          'reps', s.reps,
          'duration_seconds', s.duration_seconds,
          'distance_meters', s.distance_meters,
          'additional_weight', case when s.additional_weight is not null then s.additional_weight::text end,
          'assistance_weight', case when s.assistance_weight is not null then s.assistance_weight::text end,
          'is_completed', s.is_completed
        ) order by s.set_number, s.id), '[]'::jsonb)
        from public.workout_sets s
        where s.exercise_log_id = e.id and s.is_completed
      )
    ) order by e.order_index, e.id), '[]'::jsonb)
    from public.exercise_logs e
    join public.exercises ex on ex.id = e.exercise_id
    where e.workout_log_id = w.id
  ))
  into v_result
  from public.workout_logs w
  where w.id = p_workout_id and w.user_id = v_owner and w.deleted_at is null;

  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  return v_result;
end;
$function$;

-- 14. public.get_exercise_catalog_page
CREATE OR REPLACE FUNCTION public.get_exercise_catalog_page(
  p_search text DEFAULT ''::text,
  p_category_id integer DEFAULT NULL::integer,
  p_cursor text DEFAULT NULL::text,
  p_limit integer DEFAULT 25
)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_query jsonb;
  v_key jsonb;
  v_rows jsonb;
  v_search text;
begin
  perform imfit_private.validate_limit(p_limit);
  if p_search is null or length(btrim(p_search)) > 100 or (p_category_id is not null
    and not exists (select 1 from public.muscle_categories where id = p_category_id)) then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;
  v_search := lower(btrim(p_search));
  v_query := jsonb_build_object('rpc', 'get_exercise_catalog_page', 'owner', v_owner, 'search', v_search, 'category', p_category_id);
  v_key := imfit_private.typed_cursor(p_cursor, v_query, false);

  select coalesce(jsonb_agg(jsonb_build_object(
    'data', jsonb_build_object(
      'id', e.id,
      'muscle_category_id', e.muscle_category_id,
      'name', e.name,
      'image_url', e.image_url,
      'difficulty', e.difficulty,
      'equipment', e.equipment,
      'default_tracking_type', e.default_tracking_type::text,
      'modality', e.modality::text,
      'allowed_modes', (
        select coalesce(jsonb_agg(m.tracking_type::text order by m.tracking_type::text), '[]'::jsonb)
        from public.exercise_tracking_modes m where m.exercise_id = e.id
      )
    ),
    'key', jsonb_build_array(lower(e.name), e.id)
  ) order by lower(e.name) collate "C", e.id collate "C"), '[]'::jsonb) into v_rows
  from (
    select * from public.exercises
    where is_active and (p_category_id is null or muscle_category_id = p_category_id)
      and strpos(lower(name), v_search) > 0 and (v_key is null or
        (lower(name) collate "C", id collate "C") > ((v_key->>0) collate "C", (v_key->>1) collate "C"))
    order by lower(name) collate "C", id collate "C"
    limit p_limit + 1
  ) e;

  return imfit_private.page_result(v_rows, v_query, p_limit);
end;
$function$;
