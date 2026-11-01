-- IMFIT Phase 1 / production preflight / READ ONLY.
-- Must pass BEFORE trying phase1 migration; intentionally fails once phase1 is applied.
DO $phase1_ready$
DECLARE v_catalog_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_catalog_count FROM public.exercises;
  IF v_catalog_count<>161 THEN
    RAISE EXCEPTION 'BLOCKED: expected 161 exercises but found %',v_catalog_count;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
             WHERE n.nspname='public' AND t.typname='tracking_type_enum')
    OR to_regclass('public.exercise_tracking_modes') IS NOT NULL
  THEN RAISE EXCEPTION 'BLOCKED: phase1 objects already present (partial/reapplied schema?)';
  END IF;
  IF EXISTS (SELECT 1 FROM supabase_migrations.schema_migrations
             WHERE version='20261101000100')
  THEN RAISE EXCEPTION 'BLOCKED: phase1 migration already recorded';
  END IF;
  IF NOT EXISTS (
       SELECT 1 FROM information_schema.columns
       WHERE table_schema='public' AND table_name='template_exercises'
         AND column_name='reps' AND is_nullable='NO' AND column_default='10'
  ) THEN RAISE EXCEPTION 'BLOCKED: template_exercises.reps v1 contract changed';
  END IF;
  IF NOT EXISTS (
       SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
       WHERE t.oid='public.active_sessions'::regclass
         AND c.conname='active_sessions_schema_version_check'
         AND pg_get_constraintdef(c.oid) LIKE '%schema_version = 1%'
  ) THEN RAISE EXCEPTION 'BLOCKED: active_sessions constraint drifted';
  END IF;
  RAISE NOTICE 'PASS: phase1 preflight (catalog %; v1 constraints verified)',v_catalog_count;
END;
$phase1_ready$;
