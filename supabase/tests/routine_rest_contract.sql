-- Run against a disposable migrated database; fixtures never survive this transaction.
\set ON_ERROR_STOP on
BEGIN;
DO $fixtures$
DECLARE owner_id uuid := gen_random_uuid();
BEGIN
  PERFORM set_config('imfit.rest_test.owner', owner_id::text, true);
  INSERT INTO auth.users(id, email, raw_user_meta_data)
  VALUES (owner_id, 'routine-rest@example.invalid', '{"name":"Rest Contract"}');
END;
$fixtures$;
SET LOCAL ROLE authenticated;
DO $contract$
DECLARE
  owner_id uuid := current_setting('imfit.rest_test.owner')::uuid;
  template_id uuid := gen_random_uuid();
  legacy_id uuid := gen_random_uuid();
  request_id uuid := gen_random_uuid();
  session_id uuid := gen_random_uuid();
  payload jsonb := '[{"exercise_id":"ex_chest_1","order_index":0,"sets":2,"reps":10,"tracking_type":"WEIGHT_REPS"}]';
  receipt jsonb;
  legacy_receipt jsonb;
  result jsonb;
  invalid_rest jsonb;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', owner_id::text, true);
  PERFORM set_config('request.jwt.claims', jsonb_build_object('sub', owner_id, 'role', 'authenticated')::text, true);
  receipt := public.save_template_revision(request_id, template_id, NULL, 'No routine rest', payload, '[]', false, owner_id);
  IF public.save_template_revision(request_id, template_id, NULL, 'No routine rest', payload, '[]', false, owner_id) IS DISTINCT FROM receipt THEN
    RAISE EXCEPTION 'Omitted-rest replay changed its receipt';
  END IF;
  result := public.get_template_detail(template_id);
  IF (result#>>'{exercises,0,rest_seconds}')::integer IS DISTINCT FROM 60 THEN
    RAISE EXCEPTION 'Omitted routine rest did not persist default 60: %', result;
  END IF;
  legacy_receipt := public.save_template_revision(gen_random_uuid(), legacy_id, NULL, 'Legacy routine rest',
    jsonb_set(payload, '{0,rest_seconds}', '120'), '[]', false, owner_id);
  result := public.get_template_detail(legacy_id);
  IF (result#>>'{exercises,0,rest_seconds}')::integer IS DISTINCT FROM 120 THEN
    RAISE EXCEPTION 'Explicit legacy rest was not preserved';
  END IF;
  legacy_receipt := public.save_template_revision(gen_random_uuid(), legacy_id, (legacy_receipt->>'revision')::bigint,
    'Legacy edited without rest', payload, '[]', false, owner_id);
  result := public.get_template_detail(legacy_id);
  IF (result#>>'{exercises,0,rest_seconds}')::integer IS DISTINCT FROM 120 THEN
    RAISE EXCEPTION 'Editing without rest rewrote historical rest';
  END IF;
  result := public.create_active_session(gen_random_uuid(), session_id, legacy_id, (legacy_receipt->>'revision')::bigint);
  IF (result#>>'{snapshot,exercises,0,rest_seconds}')::integer IS DISTINCT FROM 60 THEN
    RAISE EXCEPTION 'Runtime rest inherited routine configuration: %', result;
  END IF;
  FOREACH invalid_rest IN ARRAY ARRAY['-1'::jsonb, '601'::jsonb, '1.5'::jsonb, '"60"'::jsonb] LOOP
    BEGIN
      PERFORM public.save_template_revision(gen_random_uuid(), gen_random_uuid(), NULL, 'Invalid rest value',
        jsonb_set(payload, '{0,rest_seconds}', invalid_rest), '[]', false, owner_id);
      RAISE EXCEPTION 'Accepted invalid rest: %', invalid_rest;
    EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
    END;
  END LOOP;
  RAISE NOTICE 'PASS: omitted rest defaults to 60, legacy values survive, runtime is independent, replay is stable, invalid rest is rejected';
END;
$contract$;
ROLLBACK;
