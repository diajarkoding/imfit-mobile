-- T01 read-only SQL backup generator. Run before catalog DDL in one consistent snapshot.
-- Save backup_sql verbatim outside Git. This is a scoped DATA backup, not a full
-- database/auth/storage backup. Restore only into EMPTY matching baseline tables
-- in an isolated database; do not execute it as an in-place production rollback.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH targets(table_name, ordinal) AS (
  VALUES ('muscle_categories',1), ('exercises',2), ('exercise_tracking_modes',3)
), columns AS (
  SELECT t.table_name, t.ordinal,
         string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) AS names
  FROM targets t
  JOIN pg_attribute a ON a.attrelid = ('public.' || t.table_name)::regclass
  WHERE a.attnum > 0 AND NOT a.attisdropped AND a.attgenerated = ''
  GROUP BY t.table_name, t.ordinal
), records AS (
  SELECT 'muscle_categories' AS table_name,
         coalesce(jsonb_agg(to_jsonb(c) ORDER BY c.id), '[]'::jsonb) AS data
  FROM public.muscle_categories c
  UNION ALL
  SELECT 'exercises', coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id), '[]'::jsonb)
  FROM public.exercises e
  UNION ALL
  SELECT 'exercise_tracking_modes',
         coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.exercise_id,m.tracking_type), '[]'::jsonb)
  FROM public.exercise_tracking_modes m
)
SELECT '-- Scoped catalog data backup: ' || transaction_timestamp()::text || E'\n'
       || '-- Restore only into empty, matching baseline tables in an isolated database.' || E'\nBEGIN;\n'
       || string_agg(format(
            'INSERT INTO public.%1$I (%2$s) SELECT %2$s FROM jsonb_populate_recordset(NULL::public.%1$I, %3$L::jsonb);',
            c.table_name, c.names, r.data::text), E'\n' ORDER BY c.ordinal)
       || E'\nSELECT setval(pg_get_serial_sequence(''public.muscle_categories'',''id''), greatest((SELECT max(id) FROM public.muscle_categories),1), true);\nCOMMIT;\n'
       AS backup_sql
FROM columns c JOIN records r USING (table_name);
COMMIT;
