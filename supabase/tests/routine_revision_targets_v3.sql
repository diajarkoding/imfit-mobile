-- T04/T07: real authenticated save/read/replay flows. All fixtures roll back.
BEGIN;
DO $fixtures$
DECLARE owner_id UUID := gen_random_uuid(); other_id UUID := gen_random_uuid();
BEGIN
  PERFORM set_config('imfit.v3.owner',owner_id::TEXT,true);
  PERFORM set_config('imfit.v3.other',other_id::TEXT,true);
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES
    (owner_id,'v3-owner@example.invalid','{"name":"V3 Owner"}'),
    (other_id,'v3-other@example.invalid','{"name":"V3 Other"}');
END;
$fixtures$;
SET LOCAL ROLE authenticated;
DO $routine$
DECLARE
  owner_id UUID := current_setting('imfit.v3.owner')::UUID;
  other_id UUID := current_setting('imfit.v3.other')::UUID;
  routine_id UUID := gen_random_uuid();
  legacy_id UUID := gen_random_uuid();
  request_id UUID := gen_random_uuid();
  legacy_request UUID := gen_random_uuid();
  before_receipt JSONB;
  receipt JSONB;
  detail JSONB;
  revised JSONB;
  payload JSONB;
  invalid_payload JSONB;
  legacy_payload JSONB := '[{"exercise_id":"ex_chest_4","order_index":0,"sets":2,"reps":12,"rest_seconds":0,"tracking_type":"BODYWEIGHT_REPS"}]';
  rows JSONB := '[{"row_id":"s1","set_number":1,"weight":0,"reps":12,"duration_seconds":null,"rest_seconds":0},{"row_id":"s2","set_number":2,"weight":25.25,"reps":8,"duration_seconds":null,"rest_seconds":120}]';
  timed JSONB := '[{"row_id":"t1","set_number":1,"weight":null,"reps":null,"duration_seconds":60,"rest_seconds":0},{"row_id":"t2","set_number":2,"weight":null,"reps":null,"duration_seconds":90,"rest_seconds":600}]';
  rev BIGINT;
BEGIN
  PERFORM set_config('request.jwt.claim.sub',owner_id::TEXT,true);
  before_receipt := public.save_template_revision(legacy_request,legacy_id,NULL,'Legacy bodyweight',legacy_payload,'[]',false,owner_id);
  -- Conversion does not invent or flatten old scalar values.
  payload := jsonb_build_array(jsonb_build_object('exercise_id','ex_chest_4','order_index',0,'set_targets',rows));
  receipt := public.save_template_revision(request_id,legacy_id,(before_receipt->>'revision')::BIGINT,'Converted bodyweight',payload,'[]',false,owner_id,3);
  detail := public.get_template_detail(legacy_id);
  IF detail#>'{exercises,0,set_targets}' IS DISTINCT FROM rows
     OR detail#>>'{exercises,0,sets}' <> '2'
     OR detail#>>'{exercises,0,tracking_type}' <> 'WEIGHT_REPS'
     OR detail#>>'{exercises,0,reps}' <> '12'
     OR detail#>>'{exercises,0,rest_seconds}' <> '0'
     OR detail->>'measurement_version' <> '3' THEN
    RAISE EXCEPTION 'Converted targets or original scalar prescription changed: %',detail;
  END IF;
  IF public.save_template_revision(request_id,legacy_id,(before_receipt->>'revision')::BIGINT,'Converted bodyweight',payload,'[]',false,owner_id,3) IS DISTINCT FROM receipt
     OR public.get_template_detail(legacy_id) IS DISTINCT FROM detail THEN
    RAISE EXCEPTION 'Identical replay mutated canonical state';
  END IF;
  -- A pre-conversion scalar receipt remains replayable after the cutover.
  IF public.save_template_revision(legacy_request,legacy_id,NULL,'Legacy bodyweight',legacy_payload,'[]',false,owner_id) IS DISTINCT FROM before_receipt
     OR public.get_template_detail(legacy_id) IS DISTINCT FROM detail THEN
    RAISE EXCEPTION 'Historical scalar replay stopped being immutable';
  END IF;
  BEGIN
    PERFORM public.save_template_revision(request_id,legacy_id,(before_receipt->>'revision')::BIGINT,'Changed identity',payload,'[]',false,owner_id,3);
    RAISE EXCEPTION 'Changed request payload accepted';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN
    IF SQLERRM <> 'IDEMPOTENCY_MISMATCH' THEN RAISE; END IF;
  END;
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),legacy_id,(receipt->>'revision')::BIGINT,'Scalar overwrite',legacy_payload,'[]',false,owner_id);
    RAISE EXCEPTION 'Scalar writer overwrote a v3 routine';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN
    IF SQLERRM <> 'CONTRACT_NOT_SUPPORTED' THEN RAISE; END IF;
  END;

  -- Heterogeneous duration rows survive as-is, independently of scalar placeholders.
  payload := payload || jsonb_build_array(jsonb_build_object('exercise_id','ex_core_1','order_index',1,'sets',2,'set_targets',timed));
  receipt := public.save_template_revision(gen_random_uuid(),routine_id,NULL,'Mixed target rows',payload,'[]',false,owner_id,3);
  detail := public.get_template_detail(routine_id);
  IF detail#>'{exercises,0,set_targets}' IS DISTINCT FROM rows
     OR detail#>'{exercises,1,set_targets}' IS DISTINCT FROM timed THEN
    RAISE EXCEPTION 'Independent targets failed round trip';
  END IF;
  rev := (receipt->>'revision')::BIGINT;
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,rev-1,'Stale',payload,'[]',false,owner_id,3);
    RAISE EXCEPTION 'Stale revision accepted';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN
    IF SQLERRM <> 'REVISION_CONFLICT' THEN RAISE; END IF;
  END;
  PERFORM set_config('request.jwt.claim.sub',other_id::TEXT,true);
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,rev,'Other owner',payload,'[]',false,other_id,3);
    RAISE EXCEPTION 'Cross-owner revision accepted';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  PERFORM set_config('request.jwt.claim.sub',owner_id::TEXT,true);

  -- Malformed later exercises must not partially commit earlier edits or receipts.
  invalid_payload := jsonb_set(payload,'{0,set_targets,0,weight}','99.5');
  invalid_payload := jsonb_set(invalid_payload,'{1,sets}','1');
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,rev,'Partial invalid',invalid_payload,'[]',false,owner_id,3);
    RAISE EXCEPTION 'Count mismatch accepted';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  IF public.get_template_detail(routine_id) IS DISTINCT FROM detail THEN
    RAISE EXCEPTION 'Rejected save partially committed';
  END IF;
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,rev,'Wrong format',jsonb_set(payload,'{0,tracking_type}','"BODYWEIGHT_REPS"'),'[]',false,owner_id,3);
    RAISE EXCEPTION 'Manual legacy mode accepted by v3 writer';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,rev,'Competing scalar',jsonb_set(payload,'{0,reps}','10'),'[]',false,owner_id,3);
    RAISE EXCEPTION 'Competing scalar prescription accepted';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;

  -- Remove the first row and append a fresh identity; surviving s2 keeps its values.
  revised := jsonb_build_array(jsonb_set(rows->1,'{set_number}','1'),
    jsonb_build_object('row_id','s3','set_number',2,'weight',50,'reps',6,'duration_seconds',NULL,'rest_seconds',75));
  payload := jsonb_set(payload,'{0,set_targets}',revised);
  receipt := public.save_template_revision(gen_random_uuid(),routine_id,rev,'Edited rows',payload,'[]',false,owner_id,3);
  detail := public.get_template_detail(routine_id);
  IF detail#>'{exercises,0,set_targets}' IS DISTINCT FROM revised
     OR detail#>'{exercises,1,set_targets}' IS DISTINCT FROM timed THEN
    RAISE EXCEPTION 'Stable target identities or other exercise values changed';
  END IF;
  -- Empty v3 routines retain their marker: old writers cannot resurrect scalar data.
  receipt := public.save_template_revision(gen_random_uuid(),routine_id,(receipt->>'revision')::BIGINT,'Empty v3', '[]','["ex_chest_4","ex_core_1"]',false,owner_id,3);
  detail := public.get_template_detail(routine_id);
  IF detail->'exercises' IS DISTINCT FROM '[]'::JSONB OR detail->>'measurement_version' <> '3' THEN
    RAISE EXCEPTION 'Empty routine lost its contract version';
  END IF;
  BEGIN
    PERFORM public.save_template_revision(gen_random_uuid(),routine_id,(receipt->>'revision')::BIGINT,'Old empty writer','[]','[]',false,owner_id);
    RAISE EXCEPTION 'Empty scalar request bypassed sticky version';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN
    IF SQLERRM <> 'CONTRACT_NOT_SUPPORTED' THEN RAISE; END IF;
  END;
  RAISE NOTICE 'PASS: array round-trip, conversion, stable IDs, atomic rejection, ownership, CAS, replay and scalar-writer guards';
END;
$routine$;
RESET ROLE;
-- Preserve replay even if only the original template receipt remains.
DO $pruned_receipt$
DECLARE row_receipt public.template_write_receipts%ROWTYPE; actual JSONB; before_parent JSONB;
BEGIN
  SELECT * INTO row_receipt FROM public.template_write_receipts WHERE payload->>'name'='Legacy bodyweight';
  DELETE FROM imfit_private.operation_receipts WHERE user_id=row_receipt.user_id AND request_id=row_receipt.request_id;
  SELECT to_jsonb(t) INTO before_parent FROM public.workout_templates t WHERE id=(row_receipt.payload->>'id')::UUID;
  PERFORM set_config('request.jwt.claim.sub',row_receipt.user_id::TEXT,true);
  actual := public.save_template_revision(row_receipt.request_id,(row_receipt.payload->>'id')::UUID,NULL,
    row_receipt.payload->>'name',row_receipt.payload->'exercises',row_receipt.payload->'removed',false,row_receipt.user_id);
  IF actual IS DISTINCT FROM row_receipt.receipt
     OR (SELECT to_jsonb(t) FROM public.workout_templates t WHERE id=(row_receipt.payload->>'id')::UUID) IS DISTINCT FROM before_parent
     OR EXISTS (SELECT 1 FROM imfit_private.operation_receipts WHERE user_id=row_receipt.user_id AND request_id=row_receipt.request_id) THEN
    RAISE EXCEPTION 'Template receipt replay re-executed or mutated pruned operation';
  END IF;
END;
$pruned_receipt$;
DO $table_invariant$
DECLARE child_id UUID;
BEGIN
  SELECT e.id INTO STRICT child_id FROM public.template_exercises e
    JOIN public.workout_templates t ON t.id=e.template_id
    WHERE t.name='Converted bodyweight';
  BEGIN
    UPDATE public.template_exercises SET sets=1 WHERE id=child_id;
    RAISE EXCEPTION 'Direct write bypassed target count invariant';
  EXCEPTION WHEN invalid_parameter_value THEN NULL;
  END;
  BEGIN
    UPDATE public.template_exercises SET set_targets=jsonb_set(set_targets,'{0,reps}','0') WHERE id=child_id;
    RAISE EXCEPTION 'Direct write bypassed target validation';
  EXCEPTION WHEN invalid_parameter_value THEN NULL;
  END;
  UPDATE public.template_exercises SET sets=NULL WHERE id=child_id;
  IF (SELECT sets FROM public.template_exercises WHERE id=child_id) IS DISTINCT FROM 2 THEN
    RAISE EXCEPTION 'Table trigger did not derive sets from canonical rows';
  END IF;
END;
$table_invariant$;
ROLLBACK;
