-- T06 storage: nullable fields, decimal meters, explicit zero, legacy defaults.
BEGIN;
DO $results$
DECLARE
  owner_id UUID := gen_random_uuid(); workout_id UUID := gen_random_uuid(); log_id UUID := gen_random_uuid();
  result_id UUID := gen_random_uuid(); legacy_id UUID := gen_random_uuid();
  actual public.workout_sets%ROWTYPE;
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'v3-results@example.invalid','{"name":"V3 Results"}');
  INSERT INTO public.workout_logs(id,user_id,template_name,date,start_time,end_time)
    VALUES(workout_id,owner_id,'Result storage fixture',current_date,now()-interval '2 minutes',now());
  INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category)
    VALUES(log_id,workout_id,'ex_cardio_1','Treadmill','Cardio');
  INSERT INTO public.workout_sets(id,workout_log_id,exercise_log_id,exercise_id,set_number)
    VALUES(legacy_id,workout_id,log_id,'ex_cardio_1',1);
  SELECT * INTO actual FROM public.workout_sets WHERE id=legacy_id;
  IF actual.weight IS DISTINCT FROM 0::NUMERIC OR actual.reps IS DISTINCT FROM 0
     OR actual.measurement_version IS NOT NULL OR actual.target_row_id IS NOT NULL THEN
    RAISE EXCEPTION 'Legacy defaults or unknown version changed';
  END IF;
  INSERT INTO public.workout_sets(id,workout_log_id,exercise_log_id,exercise_id,set_number,
    measurement_version,target_row_id,weight,reps,duration_seconds,distance_meters,
    calories_kcal,speed_kph,lap_reps,incline_percent)
  VALUES(result_id,workout_id,log_id,'ex_cardio_1',2,3,'cardio-row-2',NULL,NULL,90,1234.56,
    0,12.34,0,-2.50);
  SELECT * INTO actual FROM public.workout_sets WHERE id=result_id;
  IF actual.weight IS NOT NULL OR actual.reps IS NOT NULL
     OR actual.target_row_id IS DISTINCT FROM 'cardio-row-2'
     OR actual.distance_meters IS DISTINCT FROM 1234.56::NUMERIC
     OR actual.calories_kcal IS DISTINCT FROM 0::NUMERIC
     OR actual.speed_kph IS DISTINCT FROM 12.34::NUMERIC
     OR actual.lap_reps IS DISTINCT FROM 0 OR actual.incline_percent IS DISTINCT FROM -2.50::NUMERIC THEN
    RAISE EXCEPTION 'Null, explicit zero, decimal meters or decline lost precision';
  END IF;
  UPDATE public.workout_sets SET distance_meters=NULL,calories_kcal=NULL,speed_kph=NULL,
    lap_reps=NULL,incline_percent=NULL WHERE id=result_id;
  SELECT * INTO actual FROM public.workout_sets WHERE id=result_id;
  IF actual.distance_meters IS NOT NULL OR actual.calories_kcal IS NOT NULL
     OR actual.speed_kph IS NOT NULL OR actual.lap_reps IS NOT NULL OR actual.incline_percent IS NOT NULL THEN
    RAISE EXCEPTION 'Cleared metrics were converted to zero';
  END IF;
  BEGIN
    UPDATE public.workout_sets SET calories_kcal=-0.01 WHERE id=result_id;
    RAISE EXCEPTION 'Negative calories accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.workout_sets SET speed_kph='NaN'::NUMERIC WHERE id=result_id;
    RAISE EXCEPTION 'Non-finite speed accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.workout_sets SET distance_meters='NaN'::NUMERIC WHERE id=result_id;
    RAISE EXCEPTION 'Non-finite distance accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.workout_sets SET lap_reps=-1 WHERE id=result_id;
    RAISE EXCEPTION 'Negative laps accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.workout_sets SET target_row_id=NULL WHERE id=result_id;
    RAISE EXCEPTION 'V3 result without target identity accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  RAISE NOTICE 'PASS: v3 nullable/decimal result storage, clear/zero/decline and legacy defaults';
END;
$results$;
ROLLBACK;
