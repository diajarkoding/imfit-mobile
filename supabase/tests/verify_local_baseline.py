"""Verify the four-migration baseline on the local IMFIT Docker database only.

Uses the existing rollback-only template fixture and a read-only metadata audit.
No remote URL, project linking, reset, credentials, or service-role key is accepted.
Run from the repository: python supabase/tests/verify_local_baseline.py
"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tomllib


ROOT = Path(__file__).resolve().parents[2]
PROJECT = "IMFIT-MOBILE"
CONTAINER = f"supabase_db_{PROJECT}"
MIGRATIONS = ["20260828130114", "20260828130132", "20260828130154", "20261025000100"]


def docker_path():
    found = shutil.which("docker")
    if found:
        return found
    local = Path(os.environ.get("LOCALAPPDATA", ""))
    candidates = [
        local / "Programs/DockerDesktop/resources/bin/docker.exe",
        Path("C:/Program Files/Docker/Docker/resources/bin/docker.exe"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("Docker CLI is unavailable")


def command(args, sql=None):
    result = subprocess.run(
        args, input=sql, text=True, encoding="utf-8", capture_output=True,
        cwd=ROOT, timeout=45,
    )
    if result.returncode:
        # SQL here contains only schema metadata and explicitly synthetic fixtures.
        raise RuntimeError(result.stderr.strip() or "Local Docker command failed")
    return result.stdout.strip()


def local_sql():
    """Return a psql executor only after verifying the local socket and project."""
    config = tomllib.loads((ROOT / "supabase/config.toml").read_text(encoding="utf-8"))
    if config["project_id"] != PROJECT:
        raise RuntimeError("Unexpected local project ID; refusing execution")
    # Context inspection does not account for an environment endpoint override.
    if os.environ.get("DOCKER_HOST"):
        raise RuntimeError("DOCKER_HOST override is set; refusing implicit target")
    docker = docker_path()
    context = json.loads(command([docker, "context", "inspect"]))[0]
    endpoint = context["Endpoints"]["docker"]["Host"]
    if not endpoint.startswith(("npipe://", "unix://")):
        raise RuntimeError("Docker context is not a local socket; refusing execution")
    info = json.loads(command([docker, "inspect", CONTAINER]))[0]
    labels = info["Config"].get("Labels") or {}
    if labels.get("com.supabase.cli.project") != PROJECT:
        raise RuntimeError("Container project label mismatch; refusing execution")
    if not info["State"]["Running"]:
        raise RuntimeError("Local database container is not running")

    def sql(text):
        return command([
            docker, "exec", "-i", CONTAINER, "psql", "-X", "-q", "-t", "-A",
            "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "postgres",
        ], text)

    return sql


def verify():
    sql = local_sql()
    metadata = json.loads(sql("""
        begin read only;
        select jsonb_build_object(
          'server_version_num', current_setting('server_version_num'),
          'database', current_database(),
          'migration_history', to_regclass('supabase_migrations.schema_migrations'),
          'storage_buckets', to_regclass('storage.buckets'),
          'app_templates', to_regclass('public.workout_templates'));
        rollback;
    """))
    if not all(metadata.get(key) for key in ("migration_history", "storage_buckets", "app_templates")):
        raise RuntimeError("Local initialization/migration history is incomplete; refusing fixture")
    if not 170000 <= int(metadata["server_version_num"]) < 180000:
        raise RuntimeError("Expected PostgreSQL 17 baseline")
    migrations = json.loads(sql("""
        begin read only;
        select coalesce(jsonb_agg(version order by version), '[]'::jsonb)
        from supabase_migrations.schema_migrations;
        rollback;
    """))
    if metadata["database"] != "postgres" or migrations != MIGRATIONS:
        raise RuntimeError("Not the expected four-migration baseline; refusing fixture")

    sql((ROOT / "supabase/tests/template_revision_writes.sql").read_text(encoding="utf-8"))
    audit_sql = (ROOT / "supabase/tests/full_online_only_readiness_audit.sql").read_text(encoding="utf-8")
    audit = json.loads(sql("begin read only;\n" + audit_sql + "\nrollback;"))
    if audit["transaction_read_only"] != "on":
        raise RuntimeError("Metadata audit was not read-only")
    if not audit["template_revision_column"] or not audit["template_receipt_table"]:
        raise RuntimeError("Template revision/receipt baseline is missing")
    if audit["public_tables_without_rls"]:
        raise RuntimeError("Baseline has public tables without RLS")
    if "save_template_revision" in audit["missing_required_routine_names"]:
        raise RuntimeError("Baseline template RPC is missing")
    if len(audit["missing_required_routine_names"]) != 15:
        raise RuntimeError("Unexpected RPC inventory for baseline; inspect before proceeding")
    print(json.dumps({
        "status": "PASS",
        "target": CONTAINER,
        "server_version_num": metadata["server_version_num"],
        "migration_versions": migrations,
        "checks": ["local_socket_and_project_guard", "template_revision_rollback_fixture", "read_only_metadata_audit"],
        "missing_target_rpcs": audit["missing_required_routine_names"],
        "scope": "baseline only; not new session/read RPCs, HTTP, concurrency, or final lockdown",
    }, indent=2))


if __name__ == "__main__":
    try:
        verify()
    except (RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError, KeyError) as error:
        print(f"FAIL/BLOCKED local baseline: {error}", file=sys.stderr)
        sys.exit(1)
