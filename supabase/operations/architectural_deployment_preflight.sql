-- G01: read-only, fail-fast gate for the untouched live baseline observed 2026-11-06.
-- Run before the FIRST pending migration, not between migrations or after deployment:
-- psql -X -v ON_ERROR_STOP=1 -f supabase/operations/architectural_deployment_preflight.sql
-- Execute as one standalone script, not inside a deployment transaction.
-- No customer rows, credentials, DDL, DML, role changes, or ledger repairs.
-- This is a baseline gate, NOT approval to deploy: require full backup/restore,
-- snapshot compatibility, reader/RPC compatibility, and the other release gates.
-- It intentionally rejects partial deployment, unknown ledger entries, changed RPCs,
-- extra overloads, or unexpected relevant schema. Investigate; do not suppress errors.
--
-- Ordered real application (never mark the two missing migrations as already applied):
-- 20261101000300 -> 20261102144339 -> 20261105000100 -> 20261106000100
-- -> 20261106000200 -> 20261106000300 -> 20261106000400 -> 20261106000500
-- -> 20261106000600 -> 20261106000610 -> 20261106000700 -> 20261106000800.
-- Do not reapply recorded 20261101000200; all 15 function bodies matched live.
-- 20261102144339 owns BEGIN/COMMIT. Do NOT concatenate it into a larger outer
-- transaction: its COMMIT would commit earlier SQL and detach later ledger writes.
-- For a transaction-owning runner, execute statement-aware transaction-control
-- normalization only after isolated rehearsal; do not rewrite applied migration files.
-- Files without their own BEGIN need a runner-owned transaction (SET LOCAL included).
-- Record each ledger row atomically with its migration using the deployment runner.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

DO $baseline$
DECLARE
  v_mismatch text;
BEGIN
  WITH expected(version, name) AS (
    VALUES
      ('20260828130114', 'imfit_schema_baseline'),
      ('20260828130132', 'imfit_catalog_seed'),
      ('20260828130154', 'imfit_backend_hardening'),
      ('20261025000100', 'template_revision_writes'),
      ('20261028000100', 'online_only_operation_receipts'),
      ('20261028000200', 'online_only_session_lifecycle'),
      ('20261028000300', 'online_only_remote_reads'),
      ('20261028000400', 'online_only_final_write_lockdown'),
      ('20261028000500', 'online_only_function_contract_hardening'),
      ('20261028000600', 'online_only_validation_planning'),
      ('20261028042753', 'online_only_terminal_snapshot_invariants'),
      ('20261028043417', 'online_only_terminal_time_anchor'),
      ('20261028090758', 'online_only_home_summary'),
      ('20261029000100', 'online_only_legacy_recovery'),
      ('20261031044254', 'local_workout_completion_compatibility'),
      ('20261031044715', 'local_workout_history_snapshots'),
      ('20261031154000', 'routine_card_primary_muscle_category'),
      ('20261101000100', 'exercise_multimodality_tracking'),
      ('20261101000200', 'backend_rpc_multimodality_modernization')
  ), drift AS (
    SELECT coalesce(e.version, a.version) AS version
    FROM expected e
    FULL JOIN supabase_migrations.schema_migrations a USING (version)
    WHERE e.version IS NULL OR a.version IS NULL OR e.name IS DISTINCT FROM a.name
  )
  SELECT string_agg(version, ', ' ORDER BY version) INTO v_mismatch FROM drift;
  IF v_mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'BLOCKED: ledger differs from pristine 20261101000200 baseline: %', v_mismatch;
  END IF;

  -- prosrc hashes compare dollar-quoted bodies directly, not pg_get_functiondef
  -- formatting. All expected bodies were also compared with repository 00200.
  WITH expected(signature, body_md5, security_definer, volatility, language) AS (
    VALUES
      ('imfit_private.new_session_exercise(uuid,uuid,text,integer,jsonb,integer)', '4d28bb0c1ee97d6f366a67a97c37f0a9', false, 's', 'plpgsql'),
      ('imfit_private.prefill(uuid,text,jsonb)', 'a1217904925c0eecd96b78ac513d3034', false, 's', 'plpgsql'),
      ('imfit_private.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid)', 'a30881b32a825acceceeba7ea24daa56', true, 'v', 'plpgsql'),
      ('imfit_private.terminal_session(uuid,uuid,bigint,boolean)', '23fd095172d00cbeab8ac1fa8e423f32', false, 'v', 'plpgsql'),
      ('imfit_private.validate_set_measurement(text,jsonb,boolean)', '4e53b76f46e59036ce2eba7a7596064f', false, 's', 'plpgsql'),
      ('imfit_private.weight_text(jsonb)', '5e0b9af6abbac9baf4a1152a81578a3b', false, 's', 'plpgsql'),
      ('imfit_private.workout_summary(workout_logs)', '77f61851d8a0270ed7af7e660bc90e3d', false, 's', 'sql'),
      ('public.create_active_session(uuid,uuid,uuid,bigint)', 'aeca5b6c55669e219d23e13d53179d09', true, 'v', 'plpgsql'),
      ('public.get_exercise_catalog_page(text,integer,text,integer)', '3d60d91054133f66f51fdd25e941a455', true, 's', 'plpgsql'),
      ('public.get_progress_summary(date,date,text)', '7f48697843985b3ea314f1a7e0318bb5', true, 's', 'plpgsql'),
      ('public.get_template_detail(uuid)', '55fab4c139e9b85ecd9df6f30db985d2', true, 's', 'plpgsql'),
      ('public.get_workout_detail(uuid)', 'd79c5fb8582c9df745fb20f908c9cd51', true, 's', 'plpgsql'),
      ('public.persist_local_workout_aggregate(jsonb,jsonb,jsonb)', '88ab47547dfb180bbcb3435cd3f6795a', true, 'v', 'plpgsql'),
      ('public.save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid)', 'dafa39236f3ab0bbd93c67b87cb2da41', true, 'v', 'plpgsql'),
      ('public.update_active_session(uuid,uuid,bigint,jsonb)', 'fe0d885247679411d1d147482ef3fb0e', true, 'v', 'plpgsql')
  )
  SELECT string_agg(e.signature, ', ' ORDER BY e.signature) INTO v_mismatch
  FROM expected e
  LEFT JOIN pg_catalog.pg_proc p ON p.oid = pg_catalog.to_regprocedure(e.signature)
  LEFT JOIN pg_catalog.pg_roles r ON r.oid = p.proowner
  LEFT JOIN pg_catalog.pg_language l ON l.oid = p.prolang
  WHERE p.oid IS NULL
     OR md5(p.prosrc) IS DISTINCT FROM e.body_md5
     OR p.prosecdef IS DISTINCT FROM e.security_definer
     OR p.provolatile::text IS DISTINCT FROM e.volatility
     OR l.lanname::text IS DISTINCT FROM e.language
     OR r.rolname::text IS DISTINCT FROM 'postgres'
     OR p.proconfig IS DISTINCT FROM ARRAY['search_path=""']::text[];
  IF v_mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'BLOCKED: RPC identity/body/security baseline drift: %', v_mismatch;
  END IF;

  IF (SELECT count(*) FROM pg_catalog.pg_proc p
      JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname IN ('public', 'imfit_private')
        AND p.proname IN ('weight_text', 'validate_set_measurement',
          'save_template_revision', 'get_template_detail', 'new_session_exercise',
          'create_active_session', 'update_active_session', 'terminal_session',
          'persist_local_workout_aggregate', 'prefill', 'workout_summary',
          'get_progress_summary', 'get_workout_detail', 'get_exercise_catalog_page')) <> 15 THEN
    RAISE EXCEPTION 'BLOCKED: unexpected overload or namespace for baseline RPCs';
  END IF;

  WITH expected(table_name, column_name, udt_schema, udt_name, is_nullable, column_default) AS (
    VALUES
      ('exercises', 'default_tracking_type', 'public', 'tracking_type_enum', 'NO', NULL::text),
      ('exercises', 'modality', 'public', 'exercise_modality_enum', 'NO', NULL),
      ('template_exercises', 'reps', 'pg_catalog', 'int4', 'YES', NULL),
      ('template_exercises', 'rest_seconds', 'pg_catalog', 'int4', 'NO', '60'),
      ('template_exercises', 'tracking_type', 'public', 'tracking_type_enum', 'YES', NULL),
      ('template_exercises', 'target_duration_seconds', 'pg_catalog', 'int4', 'YES', NULL),
      ('template_exercises', 'target_distance_meters', 'pg_catalog', 'int4', 'YES', NULL),
      ('workout_sets', 'weight', 'pg_catalog', 'numeric', 'NO', '0'),
      ('workout_sets', 'reps', 'pg_catalog', 'int4', 'NO', '0'),
      ('workout_sets', 'duration_seconds', 'pg_catalog', 'int4', 'YES', NULL),
      ('workout_sets', 'distance_meters', 'pg_catalog', 'int4', 'YES', NULL)
  )
  SELECT string_agg(e.table_name || '.' || e.column_name, ', '
                   ORDER BY e.table_name, e.column_name) INTO v_mismatch
  FROM expected e
  LEFT JOIN information_schema.columns a
    ON a.table_schema = 'public' AND a.table_name = e.table_name AND a.column_name = e.column_name
  WHERE a.column_name IS NULL
     OR a.udt_schema IS DISTINCT FROM e.udt_schema
     OR a.udt_name IS DISTINCT FROM e.udt_name
     OR a.is_nullable IS DISTINCT FROM e.is_nullable
     OR a.column_default IS DISTINCT FROM e.column_default;
  IF v_mismatch IS NOT NULL THEN
    RAISE EXCEPTION 'BLOCKED: relevant schema prerequisite drift: %', v_mismatch;
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'exercises'
               AND column_name IN ('tracking_profile', 'supported_metrics'))
     OR pg_catalog.to_regprocedure('imfit_private.exercise_supported_metrics_valid(text[])') IS NOT NULL THEN
    RAISE EXCEPTION 'BLOCKED: catalog cutover already exists without expected baseline';
  END IF;
END;
$baseline$;
SELECT 'PASS: pristine baseline only; deployment release gates still required' AS preflight;
ROLLBACK;
