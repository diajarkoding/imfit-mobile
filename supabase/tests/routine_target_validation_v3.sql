-- T05: behavioral target boundaries, independent of UI or serialized source text.
BEGIN;
DO $targets$
DECLARE
  strength JSONB := '[{"row_id":"s1","set_number":1,"weight":0,"reps":1,"duration_seconds":null,"rest_seconds":0}]';
  timed JSONB := '[{"row_id":"t1","set_number":1,"weight":null,"reps":null,"duration_seconds":1,"rest_seconds":600}]';
  candidate JSONB;
  invalid_value JSONB;
  full_rows JSONB;
BEGIN
  PERFORM imfit_private.validate_set_targets_v3(strength,'WEIGHT_REPS');
  PERFORM imfit_private.validate_set_targets_v3(timed,'DURATION');
  PERFORM imfit_private.validate_set_targets_v3('[{"row_id":"s2","set_number":1,"weight":1000,"reps":100,"duration_seconds":null,"rest_seconds":600}]','WEIGHT_REPS');
  PERFORM imfit_private.validate_set_targets_v3(jsonb_set(timed,'{0,duration_seconds}','86400'),'DURATION');
  SELECT jsonb_agg(jsonb_build_object('row_id','s'||i,'set_number',i,'weight',i+0.25,'reps',i,'duration_seconds',NULL,'rest_seconds',i))
    INTO full_rows FROM generate_series(1,20) i;
  IF imfit_private.validate_set_targets_v3(full_rows,'WEIGHT_REPS') <> 20 THEN
    RAISE EXCEPTION 'Twenty distinct ordered target rows rejected';
  END IF;
  FOR candidate IN SELECT value FROM jsonb_array_elements(jsonb_build_array(
    'null'::jsonb,'{}'::jsonb,'[]'::jsonb,
    full_rows || jsonb_build_array(full_rows->0),
    jsonb_build_array(strength->0,jsonb_set(strength->0,'{set_number}','2')),
    jsonb_set(strength,'{0,row_id}','""'),
    jsonb_set(strength,'{0,set_number}','2'),
    jsonb_set(strength,'{0,rest_seconds}','-1'),
    jsonb_set(strength,'{0,rest_seconds}','601'),
    jsonb_set(strength,'{0,rest_seconds}','0.5'),
    jsonb_set(strength,'{0,weight}','-0.01'),
    jsonb_set(strength,'{0,weight}','1000.01'),
    jsonb_set(strength,'{0,weight}','0.001'),
    jsonb_set(strength,'{0,weight}','"NaN"'),
    jsonb_set(strength,'{0,reps}','0'),
    jsonb_set(strength,'{0,reps}','101'),
    jsonb_set(strength,'{0,reps}','1.5'),
    jsonb_set(strength,'{0,weight}','null'),
    jsonb_set(strength,'{0,duration_seconds}','1'),
    jsonb_set(strength,'{0,extra}','1'),
    jsonb_build_array((strength->0)-'rest_seconds')
  )) LOOP
    BEGIN
      PERFORM imfit_private.validate_set_targets_v3(candidate,'WEIGHT_REPS');
      RAISE EXCEPTION 'Invalid Strength array accepted: %',candidate;
    EXCEPTION WHEN invalid_parameter_value THEN NULL;
    END;
  END LOOP;
  FOR invalid_value IN SELECT value FROM jsonb_array_elements('[0,86401,0.5,null,"60"]') LOOP
    BEGIN
      PERFORM imfit_private.validate_set_targets_v3(jsonb_set(timed,'{0,duration_seconds}',invalid_value),'DURATION');
      RAISE EXCEPTION 'Invalid duration accepted: %',invalid_value;
    EXCEPTION WHEN invalid_parameter_value THEN NULL;
    END;
  END LOOP;
  BEGIN
    PERFORM imfit_private.validate_set_targets_v3(jsonb_set(timed,'{0,reps}','1'),'DURATION');
    RAISE EXCEPTION 'Timed target accepted Strength field';
  EXCEPTION WHEN invalid_parameter_value THEN NULL;
  END;
  RAISE NOTICE 'PASS: target v3 boundaries, row identities, order, precision and field relevance';
END;
$targets$;
ROLLBACK;
