-- IMFIT Phase 2: Comprehensive Test Matrix (B2-01 through B2-20)
-- Run via: psql -X -v ON_ERROR_STOP=1 -f supabase/tests/phase2_test_matrix.sql

-- Execute only against a disposable database as a fixture-capable administrator.
-- RPCs run with the real authenticated role; all fixtures are rolled back.
BEGIN;
SET LOCAL TIME ZONE 'UTC';
SELECT set_config('imfit.test.user1', gen_random_uuid()::text, true),
       set_config('imfit.test.user2', gen_random_uuid()::text, true);
INSERT INTO auth.users (id, email, raw_user_meta_data)
SELECT current_setting('imfit.test.user1')::uuid, 'phase2-owner@example.invalid', '{"name":"Phase2 Owner"}'::jsonb
UNION ALL
SELECT current_setting('imfit.test.user2')::uuid, 'phase2-other@example.invalid', '{"name":"Phase2 Other"}'::jsonb;
CREATE TEMP TABLE phase2_original_workout_quality ON COMMIT DROP AS
SELECT id, data_quality, to_jsonb(w) AS snapshot FROM public.workout_logs w;
CREATE TEMP TABLE phase2_original_exercise_quality ON COMMIT DROP AS
SELECT id, metric_quality, to_jsonb(e) AS snapshot FROM public.exercise_logs e;
CREATE TEMP TABLE phase2_original_sets ON COMMIT DROP AS
SELECT id, to_jsonb(s) AS snapshot FROM public.workout_sets s;
CREATE TEMP TABLE phase2_original_receipts ON COMMIT DROP AS
SELECT workout_id, to_jsonb(r) AS snapshot FROM imfit_private.local_workout_receipts r;
SET LOCAL ROLE authenticated;

DO $test_matrix$
DECLARE
  v_user1 uuid := current_setting('imfit.test.user1')::uuid;
  v_user2 uuid := current_setting('imfit.test.user2')::uuid;
  v_req uuid;
  v_tmpl uuid;
  v_sess uuid;
  v_wkt uuid;
  v_res jsonb;
  v_detail jsonb;
  v_prog jsonb;
  v_prefill jsonb;
  v_pass boolean;
  v_now timestamptz := date_trunc('day', clock_timestamp()) + interval '12 hours';
  v_eid uuid;
  v_sid uuid;
BEGIN
  IF current_user <> 'authenticated' THEN
    RAISE EXCEPTION 'Matrix must exercise the authenticated database role';
  END IF;
  -- Setup session user context to user1
  PERFORM set_config('request.jwt.claim.sub', v_user1::text, true);
  PERFORM set_config('request.jwt.claims', json_build_object('sub', v_user1::text, 'role', 'authenticated')::text, true);

  ------------------------------------------------------------------------------
  -- B2-01: Save routine v1 (Legacy sets/reps format)
  ------------------------------------------------------------------------------
  v_req := gen_random_uuid();
  v_tmpl := gen_random_uuid();
  v_res := public.save_template_revision(
    v_req, v_tmpl, NULL, 'Test Routine v1',
    jsonb_build_array(
      jsonb_build_object('exercise_id', 'ex_chest_1', 'order_index', 0, 'sets', 3, 'reps', 10, 'rest_seconds', 60)
    ),
    '[]'::jsonb, false, v_user1
  );
  IF (v_res->>'template_id')::uuid <> v_tmpl OR (v_res->>'revision')::bigint < 1 THEN
    RAISE EXCEPTION 'B2-01 FAILED: save routine v1 receipt mismatch: %', v_res;
  END IF;
  RAISE NOTICE 'B2-01 PASS: Save routine v1 succeeded';

  ------------------------------------------------------------------------------
  -- B2-02: Save routine Plank 3 x 45s (target duration stored)
  ------------------------------------------------------------------------------
  v_req := gen_random_uuid();
  v_tmpl := gen_random_uuid();
  v_res := public.save_template_revision(
    v_req, v_tmpl, NULL, 'Plank Isometric Routine',
    jsonb_build_array(
      jsonb_build_object('exercise_id', 'ex_core_1', 'order_index', 0, 'sets', 3, 'tracking_type', 'DURATION',
                         'target_duration_seconds', 45, 'rest_seconds', 60)
    ),
    '[]'::jsonb, false, v_user1
  );
  v_detail := public.get_template_detail(v_tmpl);
  IF (v_detail#>>'{exercises,0,target_duration_seconds}')::integer <> 45
     OR v_detail#>>'{exercises,0,tracking_type}' <> 'DURATION' THEN
    RAISE EXCEPTION 'B2-02 FAILED: target duration not stored properly: %', v_detail;
  END IF;
  RAISE NOTICE 'B2-02 PASS: Save routine Plank 3 x 45s succeeded';

  ------------------------------------------------------------------------------
  -- B2-03: Save routine Treadmill 5000m (target distance & duration stored)
  ------------------------------------------------------------------------------
  v_req := gen_random_uuid();
  v_tmpl := gen_random_uuid();
  v_res := public.save_template_revision(
    v_req, v_tmpl, NULL, 'Treadmill Cardio Routine',
    jsonb_build_array(
      jsonb_build_object('exercise_id', 'ex_cardio_1', 'order_index', 0, 'sets', 1, 'tracking_type', 'DISTANCE_DURATION',
                         'target_duration_seconds', 1800, 'target_distance_meters', 5000, 'rest_seconds', 60)
    ),
    '[]'::jsonb, false, v_user1
  );
  v_detail := public.get_template_detail(v_tmpl);
  IF (v_detail#>>'{exercises,0,target_distance_meters}')::integer <> 5000
     OR (v_detail#>>'{exercises,0,target_duration_seconds}')::integer <> 1800
     OR v_detail#>>'{exercises,0,tracking_type}' <> 'DISTANCE_DURATION' THEN
    RAISE EXCEPTION 'B2-03 FAILED: cardio target distance/duration mismatch: %', v_detail;
  END IF;
  RAISE NOTICE 'B2-03 PASS: Save routine Treadmill 5000m succeeded';

  ------------------------------------------------------------------------------
  -- B2-04: Tracking mode not allowed (e.g. DISTANCE_DURATION for Bench Press)
  ------------------------------------------------------------------------------
  v_req := gen_random_uuid();
  v_tmpl := gen_random_uuid();
  BEGIN
    v_res := public.save_template_revision(
      v_req, v_tmpl, NULL, 'Invalid Mode Routine',
      jsonb_build_array(
        jsonb_build_object('exercise_id', 'ex_chest_1', 'order_index', 0, 'sets', 3, 'tracking_type', 'DISTANCE_DURATION',
                           'target_duration_seconds', 60, 'target_distance_meters', 100, 'rest_seconds', 60)
      ),
      '[]'::jsonb, false, v_user1
    );
    RAISE EXCEPTION 'B2-04 FAILED: invalid tracking mode was unexpectedly accepted';
  EXCEPTION WHEN sqlstate 'PT400' THEN
    RAISE NOTICE 'B2-04 PASS: Disallowed tracking mode correctly rejected with PT400';
  END;

  ------------------------------------------------------------------------------
  -- B2-05 & B2-06: Create active session v2 with typed targets
  ------------------------------------------------------------------------------
  v_req := gen_random_uuid();
  v_tmpl := gen_random_uuid();
  v_res := public.save_template_revision(
    v_req, v_tmpl, NULL, 'Multi-Modality Program',
    jsonb_build_array(
      jsonb_build_object('exercise_id', 'ex_chest_1', 'order_index', 0, 'sets', 2, 'reps', 10, 'tracking_type', 'WEIGHT_REPS', 'rest_seconds', 60),
      jsonb_build_object('exercise_id', 'ex_chest_4', 'order_index', 1, 'sets', 2, 'reps', 15, 'tracking_type', 'BODYWEIGHT_REPS', 'rest_seconds', 60),
      jsonb_build_object('exercise_id', 'ex_core_1', 'order_index', 2, 'sets', 2, 'tracking_type', 'DURATION', 'target_duration_seconds', 45, 'rest_seconds', 60),
      jsonb_build_object('exercise_id', 'ex_cardio_1', 'order_index', 3, 'sets', 1, 'tracking_type', 'DISTANCE_DURATION', 'target_duration_seconds', 1800, 'target_distance_meters', 5000, 'rest_seconds', 60)
    ),
    '[]'::jsonb, false, v_user1
  );

  v_sess := gen_random_uuid();
  v_res := public.create_active_session(gen_random_uuid(), v_sess, v_tmpl, (v_res->>'revision')::bigint);
  IF (v_res#>>'{snapshot,schema_version}')::integer <> 2 THEN
    RAISE EXCEPTION 'B2-06 FAILED: session schema_version expected 2, got: %', v_res;
  END IF;
  RAISE NOTICE 'B2-05 & B2-06 PASS: Create session v2 succeeded with schema_version = 2';

  ------------------------------------------------------------------------------
  -- B2-07: Push-Up 15 reps, 0 kg completed successfully
  ------------------------------------------------------------------------------
  -- Exercise 1 in session is Push-Up (ex_chest_4)
  v_eid := (v_res#>>'{snapshot,exercises,1,id}')::uuid;
  v_sid := (v_res#>>'{snapshot,exercises,1,sets,0,id}')::uuid;
  -- Edit Push-Up set 1: 15 reps, 0 kg
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 1,
    jsonb_build_object('type', 'edit_set', 'exercise_log_id', v_eid, 'set_id', v_sid, 'reps', 15, 'additional_weight', 0)
  );
  -- Complete set
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 2,
    jsonb_build_object('type', 'complete_set', 'exercise_log_id', v_eid, 'set_id', v_sid)
  );
  IF (v_res#>>'{snapshot,exercises,1,sets,0,is_completed}')::boolean <> true THEN
    RAISE EXCEPTION 'B2-07 FAILED: push-up 0kg was not completed: %', v_res;
  END IF;
  RAISE NOTICE 'B2-07 PASS: Push-Up 0kg completed successfully';

  ------------------------------------------------------------------------------
  -- B2-08: Bench Press 0 kg x 10 reps REJECTED
  ------------------------------------------------------------------------------
  v_eid := (v_res#>>'{snapshot,exercises,0,id}')::uuid;
  v_sid := (v_res#>>'{snapshot,exercises,0,sets,0,id}')::uuid;
  -- Edit Bench Press set: 0 kg, 10 reps
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 3,
    jsonb_build_object('type', 'edit_set', 'exercise_log_id', v_eid, 'set_id', v_sid, 'weight', 0, 'reps', 10)
  );
  -- Try to complete: must FAIL with PT400
  BEGIN
    v_res := public.update_active_session(
      gen_random_uuid(), v_sess, 4,
      jsonb_build_object('type', 'complete_set', 'exercise_log_id', v_eid, 'set_id', v_sid)
    );
    RAISE EXCEPTION 'B2-08 FAILED: Bench press 0kg complete was accepted';
  EXCEPTION WHEN sqlstate 'PT400' THEN
    RAISE NOTICE 'B2-08 PASS: Bench Press 0kg complete correctly rejected with PT400';
  END;

  -- Now edit Bench Press to valid weight: 40 kg, 10 reps and complete it
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 4,
    jsonb_build_object('type', 'edit_set', 'exercise_log_id', v_eid, 'set_id', v_sid, 'weight', 40, 'reps', 10)
  );
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 5,
    jsonb_build_object('type', 'complete_set', 'exercise_log_id', v_eid, 'set_id', v_sid)
  );

  ------------------------------------------------------------------------------
  -- B2-09: Plank 45s completed successfully
  ------------------------------------------------------------------------------
  v_eid := (v_res#>>'{snapshot,exercises,2,id}')::uuid;
  v_sid := (v_res#>>'{snapshot,exercises,2,sets,0,id}')::uuid;
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 6,
    jsonb_build_object('type', 'edit_set', 'exercise_log_id', v_eid, 'set_id', v_sid, 'duration_seconds', 45)
  );
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 7,
    jsonb_build_object('type', 'complete_set', 'exercise_log_id', v_eid, 'set_id', v_sid)
  );
  IF (v_res#>>'{snapshot,exercises,2,sets,0,is_completed}')::boolean <> true THEN
    RAISE EXCEPTION 'B2-09 FAILED: Plank set not completed: %', v_res;
  END IF;
  RAISE NOTICE 'B2-09 PASS: Plank 45s completed successfully';

  ------------------------------------------------------------------------------
  -- B2-10: Treadmill 5000m / 1800s completed successfully
  ------------------------------------------------------------------------------
  v_eid := (v_res#>>'{snapshot,exercises,3,id}')::uuid;
  v_sid := (v_res#>>'{snapshot,exercises,3,sets,0,id}')::uuid;
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 8,
    jsonb_build_object('type', 'edit_set', 'exercise_log_id', v_eid, 'set_id', v_sid, 'duration_seconds', 1800, 'distance_meters', 5000)
  );
  v_res := public.update_active_session(
    gen_random_uuid(), v_sess, 9,
    jsonb_build_object('type', 'complete_set', 'exercise_log_id', v_eid, 'set_id', v_sid)
  );
  IF (v_res#>>'{snapshot,exercises,3,sets,0,is_completed}')::boolean <> true THEN
    RAISE EXCEPTION 'B2-10 FAILED: Treadmill set not completed: %', v_res;
  END IF;
  RAISE NOTICE 'B2-10 PASS: Treadmill 5000m / 1800s completed successfully';

  -- Finish active session to verify terminal_session
  v_res := public.finish_active_session(gen_random_uuid(), v_sess, 10);
  v_wkt := (v_res->>'workout_id')::uuid;
  IF v_wkt IS NULL THEN
    RAISE EXCEPTION 'Finish session failed to produce workout: %', v_res;
  END IF;

  v_detail := public.get_workout_detail(v_wkt);
  IF (v_detail->>'total_volume')::numeric <> 400
     OR (v_detail->>'total_reps')::integer <> 25 -- 10 bench + 15 pushup
     OR (v_detail->>'total_duration_seconds')::integer <> 1845 -- 45 plank + 1800 treadmill
     OR (v_detail->>'total_distance_meters')::integer <> 5000
     OR v_detail->>'data_quality' <> 'VERIFIED' THEN
    RAISE EXCEPTION 'Terminal session metrics mismatch: %', v_detail;
  END IF;
  RAISE NOTICE 'Terminal session produced verified separated metrics: external vol 400kg, reps 25, duration 1845s, dist 5000m';

  ------------------------------------------------------------------------------
  -- B2-11: Assisted Pull-Up assistance 20 kg
  ------------------------------------------------------------------------------
  -- Verify via persist_local_workout_aggregate
  v_wkt := gen_random_uuid();
  v_eid := gen_random_uuid();
  v_sid := gen_random_uuid();
  v_res := public.persist_local_workout_aggregate(
    jsonb_build_object(
      'id', v_wkt, 'user_id', v_user1, 'template_id', v_tmpl, 'template_name', 'Assisted Routine',
      'date', (v_now at time zone 'UTC')::date, 'start_time', v_now - interval '30 minutes',
      'end_time', v_now, 'total_volume', 0, 'total_sets', 1, 'total_reps', 10
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_eid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_back_17',
                         'exercise_name', 'Assisted Pull-Up Machine', 'muscle_category', 'Back',
                         'order_index', 0, 'total_volume', 0, 'total_sets', 1, 'total_reps', 10,
                         'tracking_type', 'ASSISTED_REPS')
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_sid, 'exercise_log_id', v_eid, 'workout_log_id', v_wkt,
                         'exercise_id', 'ex_back_17', 'set_number', 1, 'is_completed', true,
                         'reps', 10, 'assistance_weight', 20)
    )
  );
  v_detail := public.get_workout_detail(v_wkt);
  IF (v_detail->>'total_volume')::numeric <> 0 OR (v_detail->>'total_reps')::integer <> 10 THEN
    RAISE EXCEPTION 'B2-11 FAILED: Assisted Pull-Up volume should be 0, got %', v_detail;
  END IF;
  RAISE NOTICE 'B2-11 PASS: Assisted Pull-Up 20kg assistance does not add to external volume';

  ------------------------------------------------------------------------------
  -- B2-12: Dumbbell Thrusters 20 kg x 10 (modality CONDITIONING, external volume 200kg)
  ------------------------------------------------------------------------------
  v_wkt := gen_random_uuid();
  v_eid := gen_random_uuid();
  v_sid := gen_random_uuid();
  v_res := public.persist_local_workout_aggregate(
    jsonb_build_object(
      'id', v_wkt, 'user_id', v_user1, 'template_id', v_tmpl, 'template_name', 'Conditioning Routine',
      'date', (v_now at time zone 'UTC')::date, 'start_time', v_now - interval '20 minutes',
      'end_time', v_now, 'total_volume', 200, 'total_sets', 1, 'total_reps', 10
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_eid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_cardio_15',
                         'exercise_name', 'Dumbbell Thrusters', 'muscle_category', 'Cardio',
                         'order_index', 0, 'total_volume', 200, 'total_sets', 1, 'total_reps', 10,
                         'tracking_type', 'WEIGHT_REPS')
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_sid, 'exercise_log_id', v_eid, 'workout_log_id', v_wkt,
                         'exercise_id', 'ex_cardio_15', 'set_number', 1, 'is_completed', true,
                         'weight', 20, 'reps', 10)
    )
  );
  v_detail := public.get_workout_detail(v_wkt);
  IF (v_detail->>'total_volume')::numeric <> 200 THEN
    RAISE EXCEPTION 'B2-12 FAILED: Thrusters external volume expected 200, got %', v_detail;
  END IF;
  RAISE NOTICE 'B2-12 PASS: Dumbbell Thrusters produced 200kg external volume';

  ------------------------------------------------------------------------------
  -- B2-13: Workout offline with multiple tracking types committed via sync
  ------------------------------------------------------------------------------
  v_wkt := gen_random_uuid();
  v_eid := gen_random_uuid();
  v_sid := gen_random_uuid();
  v_res := public.persist_local_workout_aggregate(
    jsonb_build_object(
      'id', v_wkt, 'user_id', v_user1, 'template_id', v_tmpl, 'template_name', 'Mixed Offline Workout',
      'date', (v_now at time zone 'UTC')::date, 'start_time', v_now - interval '45 minutes',
      'end_time', v_now, 'total_volume', 500, 'total_sets', 2, 'total_reps', 10
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_eid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_legs_1',
                         'exercise_name', 'Barbell Squat', 'muscle_category', 'Legs',
                         'order_index', 0, 'total_volume', 500, 'total_sets', 1, 'total_reps', 10,
                         'tracking_type', 'WEIGHT_REPS'),
      jsonb_build_object('id', md5(v_wkt::text || ':e2')::uuid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_core_1',
                         'exercise_name', 'Plank', 'muscle_category', 'Core',
                         'order_index', 1, 'total_volume', 0, 'total_sets', 1, 'total_reps', 0,
                         'tracking_type', 'DURATION')
    ),
    jsonb_build_array(
      jsonb_build_object('id', v_sid, 'exercise_log_id', v_eid, 'workout_log_id', v_wkt,
                         'exercise_id', 'ex_legs_1', 'set_number', 1, 'is_completed', true,
                         'weight', 50, 'reps', 10),
      jsonb_build_object('id', md5(v_wkt::text || ':s2')::uuid, 'exercise_log_id', md5(v_wkt::text || ':e2')::uuid,
                         'workout_log_id', v_wkt, 'exercise_id', 'ex_core_1', 'set_number', 1, 'is_completed', true,
                         'duration_seconds', 60)
    )
  );
  IF (v_res->>'exercise_count')::integer <> 2 OR (v_res->>'set_count')::integer <> 2 THEN
    RAISE EXCEPTION 'B2-13 FAILED: mixed offline workout persist error: %', v_res;
  END IF;
  RAISE NOTICE 'B2-13 PASS: Workout offline with multiple tracking types committed successfully';

  ------------------------------------------------------------------------------
  -- B2-14: Retry payload with identical payload returns receipt without duplicates
  ------------------------------------------------------------------------------
  DECLARE
    v_res2 jsonb;
    v_count1 integer;
    v_count2 integer;
  BEGIN
    SELECT count(*) INTO v_count1
    FROM jsonb_array_elements(public.get_workout_sync_page(NULL, 50)->'items') item
    WHERE (item#>>'{workout,id}')::uuid = v_wkt;
    v_res2 := public.persist_local_workout_aggregate(
      jsonb_build_object(
        'id', v_wkt, 'user_id', v_user1, 'template_id', v_tmpl, 'template_name', 'Mixed Offline Workout',
        'date', (v_now at time zone 'UTC')::date, 'start_time', v_now - interval '45 minutes',
        'end_time', v_now, 'total_volume', 500, 'total_sets', 2, 'total_reps', 10
      ),
      jsonb_build_array(
        jsonb_build_object('id', v_eid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_legs_1',
                           'exercise_name', 'Barbell Squat', 'muscle_category', 'Legs',
                           'order_index', 0, 'total_volume', 500, 'total_sets', 1, 'total_reps', 10,
                           'tracking_type', 'WEIGHT_REPS'),
        jsonb_build_object('id', md5(v_wkt::text || ':e2')::uuid, 'workout_log_id', v_wkt, 'exercise_id', 'ex_core_1',
                           'exercise_name', 'Plank', 'muscle_category', 'Core',
                           'order_index', 1, 'total_volume', 0, 'total_sets', 1, 'total_reps', 0,
                           'tracking_type', 'DURATION')
      ),
      jsonb_build_array(
        jsonb_build_object('id', v_sid, 'exercise_log_id', v_eid, 'workout_log_id', v_wkt,
                           'exercise_id', 'ex_legs_1', 'set_number', 1, 'is_completed', true,
                           'weight', 50, 'reps', 10),
        jsonb_build_object('id', md5(v_wkt::text || ':s2')::uuid, 'exercise_log_id', md5(v_wkt::text || ':e2')::uuid,
                           'workout_log_id', v_wkt, 'exercise_id', 'ex_core_1', 'set_number', 1, 'is_completed', true,
                           'duration_seconds', 60)
      )
    );
    SELECT count(*) INTO v_count2
    FROM jsonb_array_elements(public.get_workout_sync_page(NULL, 50)->'items') item
    WHERE (item#>>'{workout,id}')::uuid = v_wkt;
    IF v_count1 <> v_count2 OR v_count2 <> 1 THEN
      RAISE EXCEPTION 'B2-14 FAILED: duplicate workout created on retry';
    END IF;
    IF v_res IS DISTINCT FROM v_res2 THEN
      RAISE EXCEPTION 'B2-14 FAILED: receipt mismatch on retry';
    END IF;
    PERFORM set_config('imfit.test.retry_workout', v_wkt::text, true);
    RAISE NOTICE 'B2-14 PASS: Retry with identical payload returned same receipt without duplication';
  END;

  ------------------------------------------------------------------------------
  -- B2-15: Retry with conflicting payload for same workout ID raises PT409
  ------------------------------------------------------------------------------
  BEGIN
    v_res := public.persist_local_workout_aggregate(
      jsonb_build_object(
        'id', v_wkt, 'user_id', v_user1, 'template_id', v_tmpl, 'template_name', 'CONFLICTING NAME',
        'date', (v_now at time zone 'UTC')::date, 'start_time', v_now - interval '45 minutes',
        'end_time', v_now, 'total_volume', 500, 'total_sets', 2, 'total_reps', 10
      ),
      jsonb_build_array(),
      jsonb_build_array()
    );
    RAISE EXCEPTION 'B2-15 FAILED: conflicting payload was accepted';
  EXCEPTION WHEN sqlstate 'PT409' THEN
    RAISE NOTICE 'B2-15 PASS: Conflicting payload correctly rejected with PT409';
  END;

  ------------------------------------------------------------------------------
  -- B2-16: User A accessing User B workout rejected
  ------------------------------------------------------------------------------
  -- Switch context to user2
  PERFORM set_config('request.jwt.claim.sub', v_user2::text, true);
  PERFORM set_config('request.jwt.claims', json_build_object('sub', v_user2::text, 'role', 'authenticated')::text, true);
  BEGIN
    v_detail := public.get_workout_detail(v_wkt);
    RAISE EXCEPTION 'B2-16 FAILED: user2 was able to read user1 workout detail';
  EXCEPTION WHEN sqlstate 'PT404' THEN
    RAISE NOTICE 'B2-16 PASS: Cross-account workout access rejected with PT404';
  END;

  -- Switch context back to user1
  PERFORM set_config('request.jwt.claim.sub', v_user1::text, true);
  PERFORM set_config('request.jwt.claims', json_build_object('sub', v_user1::text, 'role', 'authenticated')::text, true);

  ------------------------------------------------------------------------------
  -- B2-17: Prefill exercise with different tracking type does not mix
  ------------------------------------------------------------------------------
  -- Query prefill for Plank with DURATION defaults vs REPS defaults
  v_prefill := public.get_exercise_prefill('ex_core_1', jsonb_build_object('sets', 2, 'tracking_type', 'DURATION'));
  IF v_prefill#>>'{sets,0,duration_seconds}' IS NULL THEN
    RAISE EXCEPTION 'B2-17 FAILED: DURATION prefill missing duration_seconds: %', v_prefill;
  END IF;
  RAISE NOTICE 'B2-17 PASS: Prefill respects tracking type without mixing';

  ------------------------------------------------------------------------------
  -- B2-18: Get progress summary and workout detail match raw records
  ------------------------------------------------------------------------------
  v_prog := public.get_progress_summary(
    (v_now - interval '2 days')::date,
    (v_now + interval '2 days')::date,
    'UTC'
  );
  IF (v_prog->>'workout_count')::integer < 2 THEN
    RAISE EXCEPTION 'B2-18 FAILED: workout count in progress summary mismatch: %', v_prog;
  END IF;
  IF (v_prog->>'hold_duration_seconds')::integer < 45 THEN
    RAISE EXCEPTION 'B2-18 FAILED: hold duration seconds mismatch: %', v_prog;
  END IF;
  RAISE NOTICE 'B2-18 PASS: Progress summary reflects separated modality metrics';

  -- B2-19 runs after RESET ROLE below: authenticated has no raw-history SELECT.

  ------------------------------------------------------------------------------
  -- B2-20: Legacy client backward compatibility (get_workout_sync_page, catalog)
  ------------------------------------------------------------------------------
  v_res := public.get_workout_sync_page(NULL, 10);
  IF v_res->'items' IS NULL OR jsonb_array_length(v_res->'items') = 0 THEN
    RAISE EXCEPTION 'B2-20 FAILED: get_workout_sync_page failed: %', v_res;
  END IF;

  v_res := public.get_exercise_catalog_page('', NULL, NULL, 5);
  IF v_res#>>'{data,0,allowed_modes}' IS NULL THEN
    RAISE EXCEPTION 'B2-20 FAILED: catalog page missing allowed_modes: %', v_res;
  END IF;
  RAISE NOTICE 'B2-20 PASS: Catalog and sync pages return backward-compatible extended contracts';

  RAISE NOTICE '========================================================';
  RAISE NOTICE 'Authenticated RPC scenarios complete; checking historical quality as fixture administrator';
  RAISE NOTICE '========================================================';
END;
$test_matrix$;

RESET ROLE;
DO $historical_quality$
BEGIN
  IF (SELECT count(*) FROM public.workout_logs
      WHERE id = current_setting('imfit.test.retry_workout')::uuid) <> 1
    OR (SELECT count(*) FROM public.exercise_logs
        WHERE workout_log_id = current_setting('imfit.test.retry_workout')::uuid) <> 2
    OR (SELECT count(*) FROM public.workout_sets
        WHERE workout_log_id = current_setting('imfit.test.retry_workout')::uuid) <> 2 THEN
    RAISE EXCEPTION 'B2-14 FAILED: privileged raw-row retry parity mismatch';
  END IF;
  RAISE NOTICE 'B2-14 PASS: Privileged inspection confirms 1 workout, 2 exercises, 2 sets after retry';
  IF EXISTS (
    SELECT 1 FROM phase2_original_workout_quality old
    LEFT JOIN public.workout_logs current USING (id)
    WHERE current.id IS NULL OR current.data_quality IS DISTINCT FROM old.data_quality
  ) OR EXISTS (
    SELECT 1 FROM phase2_original_exercise_quality old
    LEFT JOIN public.exercise_logs current USING (id)
    WHERE current.id IS NULL OR current.metric_quality IS DISTINCT FROM old.metric_quality
  ) THEN
    RAISE EXCEPTION 'B2-19 FAILED: pre-existing historical quality changed';
  END IF;
  RAISE NOTICE 'B2-19 PASS: All pre-existing workout/exercise quality markers unchanged';
  IF EXISTS (
    SELECT 1 FROM phase2_original_workout_quality old
    LEFT JOIN public.workout_logs current USING (id)
    WHERE to_jsonb(current) IS DISTINCT FROM old.snapshot
  ) OR EXISTS (
    SELECT 1 FROM phase2_original_exercise_quality old
    LEFT JOIN public.exercise_logs current USING (id)
    WHERE to_jsonb(current) IS DISTINCT FROM old.snapshot
  ) OR EXISTS (
    SELECT 1 FROM phase2_original_sets old
    LEFT JOIN public.workout_sets current USING (id)
    WHERE to_jsonb(current) IS DISTINCT FROM old.snapshot
  ) OR EXISTS (
    SELECT 1 FROM phase2_original_receipts old
    LEFT JOIN imfit_private.local_workout_receipts current USING (workout_id)
    WHERE to_jsonb(current) IS DISTINCT FROM old.snapshot
  ) THEN
    RAISE EXCEPTION 'B2-19 FAILED: pre-existing history or receipts changed';
  END IF;
  RAISE NOTICE 'B2-19 PASS: All pre-existing history rows and receipts unchanged';
  RAISE NOTICE 'ALL 20 PHASE 2 TEST SCENARIOS (B2-01 TO B2-20) PASSED';
END;
$historical_quality$;
ROLLBACK;
