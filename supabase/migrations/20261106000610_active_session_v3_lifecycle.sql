-- T08 lifecycle cutover; run after 00600 validators/persistence. Existing ACLs retained.
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='120s';

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
  if (v_template.measurement_version = 3 and exists(select 1 from public.template_exercises
      where template_id=p_template_id and measurement_version is distinct from 3))
     or (v_template.measurement_version is distinct from 3 and exists(select 1 from public.template_exercises
      where template_id=p_template_id and measurement_version=3)) then
    perform imfit_private.fail('PT400','VALIDATION');
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
    if v_template.measurement_version = 3 then
      v_exercise := imfit_private.session_exercise_v3(v_id,v_child.exercise_id,jsonb_array_length(v_exercises),v_child.set_targets);
    else
    v_exercise := imfit_private.new_session_exercise(
      v_owner, v_id, v_child.exercise_id, jsonb_array_length(v_exercises),
      v_defaults, 60
    );
    end if;
    v_exercises := v_exercises || jsonb_build_array(v_exercise);
  end loop;

  v_data := jsonb_build_object(
    'exercises', v_exercises,
    'current_exercise_id', v_exercises#>'{0,id}',
    'used_exercise_ids', (select jsonb_agg(e->'id') from jsonb_array_elements(v_exercises) e),
    'used_set_ids', (select jsonb_agg(s->'id') from jsonb_array_elements(v_exercises) e, lateral jsonb_array_elements(e->'sets') s)
  );

  if v_template.measurement_version = 3 then v_data:=v_data||jsonb_build_object('schema_version',3); end if;

  if octet_length(v_data::text) > 262144 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  insert into public.active_sessions(
    id, user_id, template_id, template_name, start_time, session_data, last_activity_at,
    expires_at, version, schema_version, status, review_required, template_revision
  ) values (
    p_session_id, v_owner, p_template_id, v_template.name, v_now, v_data, v_now,
    v_now + interval '30 days', 1, case when v_template.measurement_version=3 then 3 else 2 end, 'ACTIVE', false, v_template.revision
  ) returning * into v_row;

  v_result := imfit_private.session_receipt(v_row, p_request_id, 'create_active_session');
  perform imfit_private.record_operation(v_owner, p_request_id, 'create_active_session', 'SESSION', p_session_id, v_payload, v_result);
  return v_result;
end;
$function$;

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
    when 'edit_set' then array['type','exercise_log_id','set_id','weight','reps','duration_seconds','distance_meters','additional_weight','assistance_weight','calories_kcal','speed_kph','lap_reps','incline_percent']
    when 'add_exercise' then v_required||array['set_targets']
    when 'add_set' then v_required||array['target_row_id']
    else v_required end;

  if v_required is null or not (v_command ?& v_required) or (v_command - v_allowed) <> '{}'::jsonb then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  select * into v_row from public.active_sessions where id = p_session_id and user_id = v_owner for update;
  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED'); end if;
  if v_row.schema_version=3 and v_row.session_data->'schema_version' is distinct from '3'::jsonb then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if v_row.schema_version<>3 and v_command ?| array['calories_kcal','speed_kph','lap_reps','incline_percent','set_targets','target_row_id'] then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  if v_row.schema_version=3 and v_row.status='PAUSED' and v_type not in ('resume','acknowledge_expiry') then
    perform imfit_private.fail('PT409','STATE_CONFLICT');
  end if;
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
    elsif v_row.schema_version=3 then
      v_data:=imfit_private.apply_session_command_v3(v_data,v_command);
      v_exercises:=v_data->'exercises';
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

    -- A distinct complete/undo request on an already matching state is a no-op.
    if v_row.schema_version<>3 or v_data is distinct from v_row.session_data or v_type in ('pause','resume') then
    update public.active_sessions
    set session_data = v_data, status = v_row.status, paused_at = v_row.paused_at,
        total_paused_ms = v_row.total_paused_ms, version = version + 1,
        last_activity_at = v_now, expires_at = v_now + interval '30 days'
    where id = p_session_id returning * into v_row;
    end if;
  end if;

  v_result := imfit_private.session_receipt(v_row, p_request_id, 'update_active_session');
  perform imfit_private.record_operation(v_owner, p_request_id, 'update_active_session', 'SESSION', p_session_id, v_payload, v_result);
  return v_result;
end;
$function$;

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
  v_v3_exercises jsonb;
  v_v3_sets jsonb;
  v_v3_workout jsonb;
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
      if v_row.schema_version=3 then
        if v_row.session_data->'schema_version' is distinct from '3'::jsonb then perform imfit_private.fail('PT400','VALIDATION'); end if;
        for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
          perform imfit_private.validate_snapshot_v3(v_e->'input_defaults');
          if v_e->'measurement_version' is distinct from '3'::jsonb
             or v_e->'tracking_type' is distinct from v_e#>'{input_defaults,tracking_type}'
             or v_e->'modality' is distinct from v_e#>'{input_defaults,modality}'
             or v_e->'tracking_profile' is distinct from v_e#>'{input_defaults,tracking_profile}'
             or v_e->'supported_metrics' is distinct from v_e#>'{input_defaults,supported_metrics}'
             or jsonb_array_length(v_e->'sets') not between 1 and 20
             or (select count(distinct s->>'target_row_id') from jsonb_array_elements(v_e->'sets') s)<>jsonb_array_length(v_e->'sets') then
            perform imfit_private.fail('PT400','VALIDATION');
          end if;
          for v_s in select value from jsonb_array_elements(v_e->'sets') loop
            perform imfit_private.validate_result_v3(v_e->'input_defaults',v_s,(v_s->>'is_completed')::boolean);
          end loop;
        end loop;
        v_workout:=p_session;
        select coalesce(jsonb_agg(jsonb_build_object('id',e->'id','workout_log_id',v_workout,
          'exercise_id',e->'exercise_id','exercise_name',e->'name','muscle_category',e->'muscle_category',
          'order_index',e->'order_index','measurement_version',3,'tracking_type',e->'tracking_type',
          'modality',e->'modality','input_defaults',e->'input_defaults') order by (e->>'order_index')::integer),'[]'::jsonb)
        into v_v3_exercises from jsonb_array_elements(v_row.session_data->'exercises') e
        where exists(select 1 from jsonb_array_elements(e->'sets') s where s->'is_completed'='true'::jsonb);
        select coalesce(jsonb_agg(s||jsonb_build_object('workout_log_id',v_workout,'exercise_log_id',e->'id','exercise_id',e->'exercise_id')
          order by (e->>'order_index')::integer,(s->>'set_number')::integer),'[]'::jsonb)
        into v_v3_sets from jsonb_array_elements(v_row.session_data->'exercises') e
        cross join lateral jsonb_array_elements(e->'sets') s where s->'is_completed'='true'::jsonb;
        v_v3_workout:=jsonb_build_object('id',v_workout,'user_id',v_owner,'template_id',v_row.template_id,
          'template_name',v_row.template_name,'date',(v_row.start_time at time zone 'UTC')::date,
          'start_time',v_row.start_time,'end_time',greatest(v_now,v_row.start_time+interval '1 microsecond'),
          'measurement_version',3,'source_session_id',p_session);
        perform imfit_private.insert_workout_v3(v_owner,v_v3_workout,v_v3_exercises,v_v3_sets);
      else
      -- Verify there is at least one completed set
      select count(*)::integer into v_sets
      from jsonb_array_elements(v_row.session_data->'exercises') e,
           lateral jsonb_array_elements(e->'sets') s
      where (s->>'is_completed')::boolean;

      if v_sets = 0 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

      -- Validate each completed set against its tracking type
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        -- Validate explicit metadata before the shared validator's legacy fallback.
        -- Neither allowed catalog modes nor today's modality describe this session.
        if v_e ? 'tracking_type' and (
          jsonb_typeof(v_e->'tracking_type') is distinct from 'string'
          or v_e->>'tracking_type' not in (
            'WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS',
            'REPS_ONLY', 'DURATION', 'DISTANCE_DURATION'
          )
        ) then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        if v_e->>'modality' is not null and (
          jsonb_typeof(v_e->'modality') is distinct from 'string'
          or v_e->>'modality' not in (
            'STRENGTH', 'CALISTHENICS', 'ISOMETRIC', 'CARDIO', 'CONDITIONING'
          )
        ) then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          perform imfit_private.validate_set_measurement(v_track, v_s, true);
        end loop;
      end loop;

      v_workout := md5(p_session::text || ':workout')::uuid;
      v_quality := case when v_row.schema_version >= 2 and not exists (
        select 1 from jsonb_array_elements(v_row.session_data->'exercises') e
        where not (e ? 'tracking_type')
      ) then 'VERIFIED' else 'LEGACY_UNVERIFIED' end;

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
        v_modality := v_e->>'modality';

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
          case when v_row.schema_version >= 2 and v_e ? 'tracking_type'
            then 'VERIFIED' else 'LEGACY_UNVERIFIED' end, v_e->'input_defaults'
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

NOTIFY pgrst,'reload schema';
