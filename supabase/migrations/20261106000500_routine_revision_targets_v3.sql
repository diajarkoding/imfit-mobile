-- T07: canonical routine target arrays with the existing ownership/CAS/receipt protocol.
-- One function signature, not overloaded compatibility wrappers. Old eight-argument
-- calls use NULL via the final default; v3 explicitly supplies p_measurement_version=3.
-- This also identifies versioned requests for empty routines and delete operations.
-- Requires the reader/session compatibility guard below; full v3 sessions remain T08.
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

DROP FUNCTION public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid);
DROP FUNCTION imfit_private.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid);

CREATE OR REPLACE FUNCTION imfit_private.save_template_revision(
  p_request_id uuid,
  p_template_id uuid,
  p_expected_revision bigint,
  p_name text,
  p_exercises jsonb,
  p_removed_exercise_ids jsonb,
  p_is_deleted boolean,
  p_owner_id uuid,
  p_measurement_version integer DEFAULT NULL
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
  v_target_count integer;
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
  if p_measurement_version is not null then
    v_payload := v_payload || jsonb_build_object('measurement_version',p_measurement_version);
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_owner::text || ':' || p_request_id::text, 0));
  select * into v_previous from public.template_write_receipts
    where user_id = v_owner and request_id = p_request_id;
  if found then
    if v_previous.payload <> v_payload then
      raise exception 'Request identity reused with different payload' using errcode = '22023';
    end if;
    return v_previous.receipt;
  end if;
  if p_measurement_version is not null and p_measurement_version <> 3 then
    raise exception 'Unsupported routine contract' using errcode='22023';
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
    if v_parent.measurement_version = 3 and p_measurement_version is distinct from 3 then
      raise exception 'Routine requires v3 writer' using errcode='0A000';
    end if;
  else
    if p_expected_revision is not null or p_is_deleted then
      raise exception 'Template unavailable' using errcode = '40001';
    end if;
    insert into public.workout_templates(id, user_id, name) values(p_template_id, v_owner, p_name);
  end if;

  if exists (select 1 from jsonb_array_elements(p_exercises) e
      where jsonb_typeof(e) <> 'object' or nullif(e->>'exercise_id','') is null or
        (e->>'order_index') is null or (p_measurement_version is distinct from 3 and (e->>'sets') is null)) or
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

    v_track := case when p_measurement_version = 3
      then (select default_tracking_type from public.exercises where id = v_child->>'exercise_id')
      when v_child ? 'tracking_type' and v_child->>'tracking_type' is not null
      then (v_child->>'tracking_type')::public.tracking_type_enum
      else (select default_tracking_type from public.exercises where id = v_child->>'exercise_id') end;
    v_dur := case when v_child ? 'target_duration_seconds' and v_child->>'target_duration_seconds' is not null
      then (v_child->>'target_duration_seconds')::integer else null end;
    v_dist := case when v_child ? 'target_distance_meters' and v_child->>'target_distance_meters' is not null
      then (v_child->>'target_distance_meters')::integer else null end;
    v_reps := case when v_child ? 'reps' and v_child->>'reps' is not null
      then (v_child->>'reps')::integer else null end;

    if p_measurement_version = 3 then
      v_target_count := imfit_private.validate_set_targets_v3(v_child->'set_targets',v_track::text);
      if v_child ? 'sets' and (v_child->>'sets')::numeric is distinct from v_target_count::numeric then
        raise exception 'sets does not match target array length' using errcode='22023';
      end if;
      update public.template_exercises
      set order_index=v_count,sets=v_target_count,tracking_type=v_track,
          set_targets=v_child->'set_targets',measurement_version=3
      where template_id=p_template_id and exercise_id=v_child->>'exercise_id';
      if not found then
        insert into public.template_exercises(template_id,exercise_id,order_index,sets,reps,
          tracking_type,set_targets,measurement_version)
        values(p_template_id,v_child->>'exercise_id',v_count,v_target_count,NULL,
          v_track,v_child->'set_targets',3);
      end if;
    else
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
    end if;
    v_count := v_count + 1;
  end loop;

  update public.workout_templates set name = p_name, is_deleted = p_is_deleted,
    measurement_version = case when p_measurement_version = 3 then 3 else measurement_version end
    where id = p_template_id and user_id = v_owner returning * into v_parent;
  v_receipt := jsonb_build_object('request_id', p_request_id, 'template_id', p_template_id,
    'revision', v_parent.revision, 'exercise_count', v_count, 'server_updated_at', v_parent.updated_at);
  if p_measurement_version = 3 then
    v_receipt := v_receipt || jsonb_build_object('measurement_version',3);
  end if;
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
  p_owner_id uuid,
  p_measurement_version integer DEFAULT NULL
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
  v_target_count integer;
  v_previous public.template_write_receipts%rowtype;
begin
  if p_owner_id is distinct from v_owner then
    raise exception 'Authenticated identity required' using errcode = '42501';
  end if;
  v_payload := jsonb_build_object('id', p_template_id, 'owner', p_owner_id,
    'revision', p_expected_revision, 'name', p_name, 'exercises', p_exercises,
    'removed', p_removed_exercise_ids, 'deleted', p_is_deleted);
  -- Do not add a null version key to immutable legacy receipt payloads.
  if p_measurement_version is not null then
    v_payload := v_payload || jsonb_build_object('measurement_version',p_measurement_version);
  end if;
  v_result := imfit_private.request_replay(v_owner, p_request_id,
    'save_template_revision', p_template_id, v_payload);
  if v_result is not null then return v_result; end if;
  -- The template receipt is also authoritative when an operation receipt was
  -- pruned. Identical legacy replay must not revalidate against today's catalog.
  select * into v_previous from public.template_write_receipts
    where user_id=v_owner and request_id=p_request_id;
  if found then
    if v_previous.payload is distinct from v_payload then
      perform imfit_private.fail('PT409','IDEMPOTENCY_MISMATCH');
    end if;
    return v_previous.receipt;
  end if;
  if (p_measurement_version is not null and p_measurement_version <> 3)
     or p_expected_revision < 0 or p_name is null
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
    if p_measurement_version = 3 then
      if (v_child - array['exercise_id','order_index','sets','tracking_type','measurement_version','set_targets']) <> '{}'::jsonb
         or not (v_child ?& array['exercise_id','order_index','set_targets'])
         or jsonb_typeof(v_child->'exercise_id') is distinct from 'string'
         or jsonb_typeof(v_child->'order_index') is distinct from 'number'
         or (v_child->>'order_index') !~ '^(0|[1-9][0-9]*)$'
         or (v_child->>'order_index')::numeric > 99
         or (v_child ? 'measurement_version' and v_child->'measurement_version' is distinct from '3'::jsonb) then
        perform imfit_private.fail('PT400','VALIDATION');
      end if;
      select default_tracking_type::text into v_track from public.exercises
      where id=v_child->>'exercise_id' and is_active;
      if v_track is null or v_track not in ('WEIGHT_REPS','DURATION')
         or (v_child ? 'tracking_type' and v_child->'tracking_type' is distinct from to_jsonb(v_track)) then
        perform imfit_private.fail('PT400','VALIDATION');
      end if;
      begin
        v_target_count := imfit_private.validate_set_targets_v3(v_child->'set_targets',v_track);
      exception when invalid_parameter_value or numeric_value_out_of_range then
        perform imfit_private.fail('PT400','VALIDATION');
      end;
      if v_child ? 'sets' then
        if jsonb_typeof(v_child->'sets') is distinct from 'number' then
          perform imfit_private.fail('PT400','VALIDATION');
        end if;
        if (v_child->>'sets')::numeric is distinct from v_target_count::numeric then
          perform imfit_private.fail('PT400','VALIDATION');
        end if;
      end if;
      continue;
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
      p_is_deleted, p_owner_id, p_measurement_version);
  exception
    when feature_not_supported then
      perform imfit_private.fail('PT409','CONTRACT_NOT_SUPPORTED');
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

REVOKE ALL ON FUNCTION public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid,integer) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid,integer) TO authenticated;
REVOKE ALL ON FUNCTION imfit_private.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid,integer) FROM PUBLIC,anon,authenticated,service_role;

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
    'measurement_version', t.measurement_version,
    'updated_at', t.updated_at,
    'exercises', (
      select coalesce(jsonb_agg(jsonb_build_object(
        'id', e.id,
        'exercise_id', e.exercise_id,
        'order_index', e.order_index,
        'sets', e.sets,
        'measurement_version', e.measurement_version,
        'set_targets', e.set_targets,
        'reps', coalesce(e.reps, 0),
        'rest_seconds', e.rest_seconds,
        'tracking_type', coalesce(e.tracking_type::text, 'WEIGHT_REPS'),
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
  -- T08 owns v3 session creation. Never flatten heterogeneous rows into v2.
  if v_template.measurement_version = 3 or exists (select 1 from public.template_exercises
      where template_id=p_template_id and measurement_version=3) then
    perform imfit_private.fail('PT409','CONTRACT_NOT_SUPPORTED');
  end if;
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
      'tracking_type', coalesce(v_child.tracking_type::text, 'WEIGHT_REPS'),
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

NOTIFY pgrst, 'reload schema';
