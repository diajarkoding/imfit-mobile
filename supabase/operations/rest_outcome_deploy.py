#!/usr/bin/env python3
"""T37: apply the single additive migration 20261108000100_rest_outcome_contract.

Same safety model as architectural_deploy.py (explicit PG* connection, NEW evidence directory outside the
repository, live requires a verified backup whose child restore passed, one migration committed atomically WITH
its ledger row, no resume/repair). It deliberately handles ONE migration and only on top of the exact ledger
left by T11 (19 baseline + 12 pending = 31 identities); any other ledger state is refused.

  python supabase/operations/rest_outcome_deploy.py --target local --evidence-directory <new-dir-outside-repo>
  python supabase/operations/rest_outcome_deploy.py --target live  --backup-directory <backup-dir> --evidence-directory <new-dir-outside-repo>

Before the migration: a read-only preflight proves the ledger and that the new columns do not exist yet.
After: a read-only verification proves the columns/constraints, that no historical row received a value, and that
the replaced functions know the new keys. Failure preserves state for inspection; fix forward only (the added
columns are nullable and harmless to every existing client).
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import architectural_deploy as base  # noqa: E402
from full_database_backup_restore import file_hash, restrict, save_json  # noqa: E402

VERSION, NAME = '20261108000100', 'rest_outcome_contract'
EXPECTED = list(base.BASELINE) + list(base.PENDING)

EXPECTED_VALUES = ',\n'.join('(' + base.literal(v) + ',' + base.literal(n) + ')' for v, n in EXPECTED)

LEDGER_GUARD = """DO $t37_ledger_guard$
BEGIN
  IF EXISTS (
    WITH expected(version, name) AS (VALUES """ + EXPECTED_VALUES + """)
    SELECT 1 FROM expected e FULL JOIN supabase_migrations.schema_migrations a USING(version)
    WHERE e.version IS NULL OR a.version IS NULL OR e.name IS DISTINCT FROM a.name
  ) THEN
    RAISE EXCEPTION 'BLOCKED: ledger is not exactly the T11 end state (31 migrations); no resume or repair';
  END IF;
END;
$t37_ledger_guard$;
"""

PREFLIGHT = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '60s';
""" + LEDGER_GUARD + """
DO $t37_preflight$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'
      AND ((table_name = 'workout_sets' AND column_name IN ('rest_elapsed_ms', 'rest_end_reason'))
        OR (table_name = 'workout_logs' AND column_name IN ('paused_elapsed_ms', 'active_elapsed_ms')))) THEN
    RAISE EXCEPTION 'BLOCKED: T37 columns already exist; investigate, do not reapply';
  END IF;
  IF to_regprocedure('imfit_private.validate_result_v3(jsonb,jsonb,boolean)') IS NULL
     OR to_regprocedure('imfit_private.insert_workout_v3(uuid,jsonb,jsonb,jsonb)') IS NULL
     OR to_regprocedure('public.persist_local_workout_aggregate(jsonb,jsonb,jsonb)') IS NULL
     OR to_regprocedure('public.get_workout_detail(uuid)') IS NULL THEN
    RAISE EXCEPTION 'BLOCKED: expected v3 functions are missing';
  END IF;
END;
$t37_preflight$;
ROLLBACK;
"""

VERIFY = """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '60s';
DO $t37_verify$
DECLARE v_ledger integer;
BEGIN
  SELECT count(*) INTO v_ledger FROM supabase_migrations.schema_migrations;
  IF v_ledger <> 32 OR NOT EXISTS (SELECT 1 FROM supabase_migrations.schema_migrations
      WHERE version = '20261108000100' AND name = 'rest_outcome_contract') THEN
    RAISE EXCEPTION 'VERIFY: ledger must be 32 rows ending with T37';
  END IF;
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND is_nullable = 'YES'
      AND ((table_name = 'workout_sets' AND column_name IN ('rest_elapsed_ms', 'rest_end_reason'))
        OR (table_name = 'workout_logs' AND column_name IN ('paused_elapsed_ms', 'active_elapsed_ms')))) <> 4 THEN
    RAISE EXCEPTION 'VERIFY: expected four nullable columns';
  END IF;
  IF (SELECT count(*) FROM pg_constraint WHERE conname IN ('workout_sets_rest_outcome_check', 'workout_logs_session_durations_check')) <> 2 THEN
    RAISE EXCEPTION 'VERIFY: constraints missing';
  END IF;
  IF EXISTS (SELECT 1 FROM public.workout_sets WHERE rest_elapsed_ms IS NOT NULL OR rest_end_reason IS NOT NULL)
     OR EXISTS (SELECT 1 FROM public.workout_logs WHERE paused_elapsed_ms IS NOT NULL OR active_elapsed_ms IS NOT NULL) THEN
    RAISE EXCEPTION 'VERIFY: a historical row received a new value';
  END IF;
  IF position('rest_end_reason' IN pg_get_functiondef('imfit_private.validate_result_v3(jsonb,jsonb,boolean)'::regprocedure)) = 0
     OR position('active_elapsed_ms' IN pg_get_functiondef('imfit_private.insert_workout_v3(uuid,jsonb,jsonb,jsonb)'::regprocedure)) = 0
     OR position('rest_elapsed_ms' IN pg_get_functiondef('public.persist_local_workout_aggregate(jsonb,jsonb,jsonb)'::regprocedure)) = 0
     OR position('rest_elapsed_ms' IN pg_get_functiondef('public.get_workout_detail(uuid)'::regprocedure)) = 0 THEN
    RAISE EXCEPTION 'VERIFY: a replaced function does not know the new keys';
  END IF;
END;
$t37_verify$;
SELECT jsonb_build_object('workout_logs', (SELECT count(*) FROM public.workout_logs),
  'workout_sets', (SELECT count(*) FROM public.workout_sets),
  'receipts', (SELECT count(*) FROM imfit_private.local_workout_receipts))::text;
ROLLBACK;
"""

COUNTS = """BEGIN READ ONLY;
SELECT jsonb_build_object('workout_logs', (SELECT count(*) FROM public.workout_logs),
  'workout_sets', (SELECT count(*) FROM public.workout_sets),
  'receipts', (SELECT count(*) FROM imfit_private.local_workout_receipts),
  'receipt_sha256', (SELECT encode(sha256(convert_to(coalesce(string_agg(payload::text || result::text, '' ORDER BY workout_id), ''), 'UTF8')), 'hex')
                     FROM imfit_private.local_workout_receipts))::text;
ROLLBACK;
"""


def apply_sql(statements):
    array = 'ARRAY[' + ',\n'.join(base.literal(s) for s in statements) + ']::text[]'
    return """BEGIN;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';
SELECT pg_catalog.pg_advisory_xact_lock(20261108, 1);
LOCK TABLE supabase_migrations.schema_migrations IN SHARE ROW EXCLUSIVE MODE;
""" + LEDGER_GUARD + '\n'.join(statements) + """
INSERT INTO supabase_migrations.schema_migrations(version, name, statements)
VALUES (""" + base.literal(VERSION) + ',' + base.literal(NAME) + ',' + array + """);
COMMIT;
"""


def deploy(args):
    os.umask(0o077)
    directory = base.outside_repository(Path(args.evidence_directory))
    if directory.exists():
        raise RuntimeError('Evidence directory must be new; never overwrite a prior deployment attempt.')
    directory.mkdir(parents=True, mode=0o700)
    restrict(directory)
    evidence = {'status': 'INCOMPLETE', 'target': args.target, 'migration': VERSION + '_' + NAME,
                'started_at': dt.datetime.now(dt.timezone.utc).isoformat()}
    save_json(directory / 'deployment-evidence.json', evidence)
    try:
        environment = base.connection_environment(args.target)
        if args.target == 'live':
            if not args.backup_directory:
                raise RuntimeError('Live deployment requires --backup-directory.')
            evidence['backup_gate'] = base.backup_gate(Path(args.backup_directory), environment)
        elif args.backup_directory:
            raise RuntimeError('--backup-directory is a live-only gate, not a local connection setting.')
        source = base.MIGRATIONS / (VERSION + '_' + NAME + '.sql')
        raw = source.read_bytes()  # the captured bytes are the bytes deployed
        statements = base.normalized_statements(VERSION, raw.decode('utf-8'))
        (directory / source.name).write_bytes(raw)
        evidence.update(sha256=hashlib.sha256(raw).hexdigest(), statement_count=len(statements),
                        runner_sha256=file_hash(Path(__file__)))
        save_json(directory / 'deployment-evidence.json', evidence)
        base.psql(PREFLIGHT, directory, '00-preflight', environment)
        before = json.loads(base.psql(COUNTS, directory, '01-counts-before', environment))
        evidence['counts_before'] = before
        evidence['in_flight'] = True
        save_json(directory / 'deployment-evidence.json', evidence)
        base.psql(apply_sql(statements), directory, '02-apply', environment)
        evidence.pop('in_flight')
        evidence['committed'] = True
        save_json(directory / 'deployment-evidence.json', evidence)
        base.psql(VERIFY, directory, '03-verify', environment)
        after = json.loads(base.psql(COUNTS, directory, '04-counts-after', environment))
        evidence['counts_after'] = after
        if after != before:
            raise RuntimeError('Row counts or receipt hash changed across an additive migration.')
        evidence['status'] = 'DEPLOYMENT_PASSED'
    except (Exception, KeyboardInterrupt) as error:
        evidence['status'] = 'DEPLOYMENT_FAILED'
        evidence['failure'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        raise
    finally:
        evidence['finished_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        evidence['artifact_sha256'] = {
            path.name: file_hash(path) for path in sorted(directory.iterdir())
            if path.is_file() and path.name != 'deployment-evidence.json'}
        save_json(directory / 'deployment-evidence.json', evidence)
    print('T37 migration committed; restricted evidence: ' + str(directory))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--target', choices=('local', 'live'), required=True)
    parser.add_argument('--backup-directory')
    parser.add_argument('--evidence-directory', required=True)
    args = parser.parse_args()
    try:
        deploy(args)
    except (Exception, KeyboardInterrupt) as error:
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('BLOCKED: ' + message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
