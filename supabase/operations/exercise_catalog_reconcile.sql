-- T01: read-only evidence. Compare results to repository migrations and Room v9.
-- No user/session payloads are returned; function bodies are schema metadata.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT version,name FROM supabase_migrations.schema_migrations ORDER BY version;
SELECT table_name,column_name,data_type,udt_name,is_nullable,column_default
FROM information_schema.columns
WHERE table_schema='public' AND table_name IN
  ('exercises','template_exercises','active_sessions','exercise_logs','workout_sets')
ORDER BY table_name,ordinal_position;
SELECT n.nspname AS schema,p.proname,pg_get_function_identity_arguments(p.oid) AS args,
       md5(pg_get_functiondef(p.oid)) AS definition_md5,pg_get_functiondef(p.oid) AS definition
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname IN ('public','imfit_private') AND p.prokind='f'
ORDER BY n.nspname,p.proname,p.oid;
SELECT id,name,modality,default_tracking_type FROM public.exercises ORDER BY id;
SELECT exercise_id,tracking_type FROM public.exercise_tracking_modes ORDER BY exercise_id,tracking_type;
SELECT conname,convalidated,pg_get_constraintdef(oid) AS definition
FROM pg_constraint WHERE conrelid='public.exercises'::regclass ORDER BY conname;
SELECT tgname,pg_get_triggerdef(oid) AS definition
FROM pg_trigger WHERE tgrelid='public.exercises'::regclass AND NOT tgisinternal;
SELECT relname,relacl::text,relrowsecurity FROM pg_class
WHERE oid IN ('public.exercises'::regclass,'public.exercise_tracking_modes'::regclass);
COMMIT;
