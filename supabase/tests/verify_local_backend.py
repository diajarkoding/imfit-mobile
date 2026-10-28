"""Run phase-specific rollback fixtures against the verified local project only."""

import argparse
import json
import subprocess
import sys

from verify_local_baseline import CONTAINER, MIGRATIONS, ROOT, local_sql


def verify(phase):
    sql = local_sql()
    versions = json.loads(sql("""
        begin read only;
        select coalesce(jsonb_agg(version order by version), '[]'::jsonb)
        from supabase_migrations.schema_migrations;
        rollback;
    """))
    expected = MIGRATIONS + ["20261028000100"]
    if phase in ("sessions", "reads", "final"):
        expected.append("20261028000200")
    if phase in ("reads", "final"):
        expected.append("20261028000300")
    if phase == "final":
        expected.extend(["20261028000400", "20261028000500", "20261028000600", "20261028042753", "20261028043417"])
    if versions != expected:
        raise RuntimeError("Unexpected migration inventory for selected phase; refusing fixture")
    sql((ROOT / "supabase/tests/online_only_operation_receipts.sql").read_text(encoding="utf-8"))
    if phase in ("sessions", "reads"):
        sql((ROOT / "supabase/tests/online_only_session_lifecycle.sql").read_text(encoding="utf-8"))
    if phase in ("reads", "final"):
        sql((ROOT / "supabase/tests/online_only_remote_reads.sql").read_text(encoding="utf-8"))
    if phase == "final":
        sql((ROOT / "supabase/tests/online_only_final_security.sql").read_text(encoding="utf-8"))
        sql((ROOT / "supabase/tests/online_only_query_plans.sql").read_text(encoding="utf-8"))
        audit = json.loads(sql("begin read only;\n" +
            (ROOT / "supabase/tests/full_online_only_readiness_audit.sql").read_text(encoding="utf-8") + "\nrollback;"))
        expected_columns = [
            {"table": "profiles", "column": column, "privilege": "UPDATE"}
            for column in ("avatar_url", "birth_date", "name")
        ]
        if (audit["missing_required_routine_names"] or audit["public_tables_without_rls"]
            or audit["authenticated_direct_write_tables"] or not audit["private_operation_receipt_rls"]
            or audit["authenticated_column_writes"] != expected_columns):
            raise RuntimeError("Final metadata/RLS/table-and-column-grant readiness audit failed")
    print(json.dumps({
        "status": "PASS", "phase": phase, "target": CONTAINER,
        "migration_versions": versions,
        "checks": ["template_wrapper", "receipt_replay", "typed_validation", "discovery", "keyset_cursor", "owner_isolation", "private_receipt_and_anon_denial"]
            + (["session_commands", "atomic_finish_and_rollback", "expiry_review", "legacy_fail_closed"] if phase in ("sessions", "reads") else [])
            + (["602_workout_pagination", "period_aggregates", "timezone", "numeric_precision", "prefill_provenance"] if phase in ("reads", "final") else [])
            + (["final_write_lockdown", "profile_column_grants", "child_parent_constraints", "RPC_only_finish", "10k_history_keyset_query_plan", "read_only_readiness_audit"] if phase == "final" else []),
        "scope": "Selected SQL rollback fixtures only; HTTP/concurrency are a separate transport runner",
    }, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["operations", "sessions", "reads", "final"], required=True)
    args = parser.parse_args()
    try:
        verify(args.phase)
    except (RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError, KeyError) as error:
        print(f"FAIL/BLOCKED local backend: {error}", file=sys.stderr)
        sys.exit(1)
