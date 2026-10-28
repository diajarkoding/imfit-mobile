-- Read-only metadata audit for the IMFIT Full Online-Only preparation gate.
-- This is NOT a migration, integration test, or proof that RLS resists attacks.
-- Does not read business rows or invoke application RPCs.
-- Run against the explicitly selected project with a read-only connection.
with public_tables as (
  select c.oid, c.relname, c.relrowsecurity
  from pg_catalog.pg_class c
  join pg_catalog.pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind in ('r', 'p')
), public_routines as (
  select p.oid, p.proname
  from pg_catalog.pg_proc p
  join pg_catalog.pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public' and p.prokind = 'f'
    and not exists (
      select 1 from pg_catalog.pg_depend d
      where d.classid = 'pg_catalog.pg_proc'::regclass
        and d.objid = p.oid and d.deptype = 'e'
    )
), required_routines(name) as (
  values
    ('save_template_revision'),
    ('get_workout_history_page'),
    ('get_exercise_catalog_page'),
    ('get_template_page'),
    ('get_workout_detail'),
    ('get_template_detail'),
    ('get_progress_summary'),
    ('get_workout_date_markers'),
    ('get_exercise_prefill'),
    ('create_active_session'),
    ('get_active_session'),
    ('update_active_session'),
    ('cancel_active_session'),
    ('finish_active_session'),
    ('get_operation_status'),
    ('list_recent_operations')
)
select jsonb_build_object(
  'observed_at_utc', timezone('UTC', statement_timestamp()),
  'audit_role', current_user,
  'transaction_read_only', current_setting('transaction_read_only'),
  'public_table_count', (select count(*) from public_tables),
  'public_tables_without_rls', (
    select coalesce(jsonb_agg(relname order by relname), '[]'::jsonb)
    from public_tables where not relrowsecurity
  ),
  'public_routine_count', (select count(*) from public_routines),
  'public_policy_count', (
    select count(*) from pg_catalog.pg_policies where schemaname = 'public'
  ),
  'missing_required_routine_names', (
    select coalesce(jsonb_agg(r.name order by r.name), '[]'::jsonb)
    from required_routines r
    where not exists (select 1 from public_routines p where p.proname = r.name)
  ),
  'template_revision_column', exists (
    select 1 from pg_catalog.pg_attribute a
    join public_tables t on t.oid = a.attrelid
    where t.relname = 'workout_templates' and a.attname = 'revision'
      and not a.attisdropped
  ),
  'template_receipt_table', to_regclass('public.template_write_receipts')::text,
  'active_session_version_column', exists (
    select 1 from pg_catalog.pg_attribute a
    join public_tables t on t.oid = a.attrelid
    where t.relname = 'active_sessions' and a.attname = 'version'
      and not a.attisdropped
  ),
  'active_session_status_column', exists (
    select 1 from pg_catalog.pg_attribute a
    join public_tables t on t.oid = a.attrelid
    where t.relname = 'active_sessions' and a.attname = 'status'
      and not a.attisdropped
  ),
  'authenticated_direct_write_tables', (
    select coalesce(jsonb_agg(t.relname order by t.relname), '[]'::jsonb)
    from public_tables t
    where has_table_privilege('authenticated', t.oid, 'INSERT')
       or has_table_privilege('authenticated', t.oid, 'UPDATE')
       or has_table_privilege('authenticated', t.oid, 'DELETE')
  ),
  'authenticated_column_writes', (
    select coalesce(jsonb_agg(jsonb_build_object('table', table_name, 'column', column_name,
      'privilege', privilege_type) order by table_name, column_name, privilege_type), '[]'::jsonb)
    from information_schema.column_privileges
    where table_schema = 'public' and grantee = 'authenticated'
      and privilege_type in ('INSERT', 'UPDATE')
  ),
  'private_operation_receipt_rls', exists (
    select 1 from pg_catalog.pg_class c
    join pg_catalog.pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'imfit_private' and c.relname = 'operation_receipts' and c.relrowsecurity
  ),
  'limitations', 'Metadata only; target routine names do not verify signatures, HTTP exposure, transactions, isolation, or runtime security. Table and column grants must both be reviewed.'
) as audit;
