-- T05: one validator shared by the RPC and actual table writes.
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

CREATE FUNCTION imfit_private.validate_set_targets_v3(p_targets JSONB, p_tracking TEXT)
RETURNS INTEGER LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $function$
DECLARE
  v_row JSONB;
  v_position BIGINT;
  v_count INTEGER;
  v_weight NUMERIC;
  v_reps NUMERIC;
  v_duration NUMERIC;
  v_rest NUMERIC;
BEGIN
  IF jsonb_typeof(p_targets) IS DISTINCT FROM 'array'
     OR p_tracking IS NULL OR p_tracking NOT IN ('WEIGHT_REPS','DURATION') THEN
    RAISE EXCEPTION 'Invalid v3 target array or tracking format' USING ERRCODE='22023';
  END IF;
  v_count := jsonb_array_length(p_targets);
  IF v_count NOT BETWEEN 1 AND 20 THEN
    RAISE EXCEPTION 'Target array must contain 1..20 rows' USING ERRCODE='22023';
  END IF;
  FOR v_row,v_position IN
    SELECT value,ordinality FROM jsonb_array_elements(p_targets) WITH ORDINALITY
  LOOP
    IF jsonb_typeof(v_row) IS DISTINCT FROM 'object'
       OR NOT (v_row ?& ARRAY['row_id','set_number','weight','reps','duration_seconds','rest_seconds'])
       OR (v_row - ARRAY['row_id','set_number','weight','reps','duration_seconds','rest_seconds']) <> '{}'::JSONB
       OR jsonb_typeof(v_row->'row_id') IS DISTINCT FROM 'string'
       OR nullif(btrim(v_row->>'row_id'),'') IS NULL
       OR jsonb_typeof(v_row->'set_number') IS DISTINCT FROM 'number'
       OR jsonb_typeof(v_row->'rest_seconds') IS DISTINCT FROM 'number' THEN
      RAISE EXCEPTION 'Invalid target row shape or identity' USING ERRCODE='22023';
    END IF;
    v_rest := (v_row->>'rest_seconds')::NUMERIC;
    IF (v_row->>'set_number')::NUMERIC <> v_position
       OR v_rest <> trunc(v_rest) OR v_rest NOT BETWEEN 0 AND 600 THEN
      RAISE EXCEPTION 'Target order or rest is invalid' USING ERRCODE='22023';
    END IF;
    IF p_tracking = 'WEIGHT_REPS' THEN
      IF jsonb_typeof(v_row->'weight') IS DISTINCT FROM 'number'
         OR jsonb_typeof(v_row->'reps') IS DISTINCT FROM 'number'
         OR v_row->'duration_seconds' IS DISTINCT FROM 'null'::JSONB THEN
        RAISE EXCEPTION 'Strength requires weight/reps and null duration' USING ERRCODE='22023';
      END IF;
      v_weight := (v_row->>'weight')::NUMERIC;
      v_reps := (v_row->>'reps')::NUMERIC;
      IF v_weight NOT BETWEEN 0 AND 1000 OR trunc(v_weight,2) <> v_weight
         OR v_reps <> trunc(v_reps) OR v_reps NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'Strength target is outside its range or precision' USING ERRCODE='22023';
      END IF;
    ELSE
      IF jsonb_typeof(v_row->'duration_seconds') IS DISTINCT FROM 'number'
         OR v_row->'weight' IS DISTINCT FROM 'null'::JSONB
         OR v_row->'reps' IS DISTINCT FROM 'null'::JSONB THEN
        RAISE EXCEPTION 'Timed targets require duration and null weight/reps' USING ERRCODE='22023';
      END IF;
      v_duration := (v_row->>'duration_seconds')::NUMERIC;
      IF v_duration <> trunc(v_duration) OR v_duration NOT BETWEEN 1 AND 86400 THEN
        RAISE EXCEPTION 'Duration target is outside its range' USING ERRCODE='22023';
      END IF;
    END IF;
  END LOOP;
  IF (SELECT count(DISTINCT value->>'row_id') FROM jsonb_array_elements(p_targets)) <> v_count THEN
    RAISE EXCEPTION 'Duplicate target row identity' USING ERRCODE='22023';
  END IF;
  RETURN v_count;
END;
$function$;
REVOKE ALL ON FUNCTION imfit_private.validate_set_targets_v3(JSONB,TEXT) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION imfit_private.validate_set_targets_v3(JSONB,TEXT) TO service_role;

CREATE FUNCTION imfit_private.validate_template_targets()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = '' AS $function$
DECLARE v_count INTEGER;
BEGIN
  IF NEW.measurement_version = 3 THEN
    v_count := imfit_private.validate_set_targets_v3(NEW.set_targets,NEW.tracking_type::TEXT);
    IF NEW.sets IS NOT NULL AND NEW.sets <> v_count THEN
      RAISE EXCEPTION 'sets does not match target array length' USING ERRCODE='22023';
    END IF;
    NEW.sets := v_count;
  END IF;
  RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION imfit_private.validate_template_targets() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION imfit_private.validate_template_targets() TO service_role;
CREATE TRIGGER validate_template_targets
  BEFORE INSERT OR UPDATE ON public.template_exercises
  FOR EACH ROW EXECUTE FUNCTION imfit_private.validate_template_targets();
