"""Replay final migrations and a synthetic four-migration upgrade locally.

A temporary schema-only platform dump comes from the already-guarded local
container. No historical artifact, hosted dump, source data, reset or drop is
used. Uniquely named verification databases remain available for inspection.
"""

import hashlib
import json
import re
import subprocess
import sys
import uuid

from verify_local_backend import FINAL_MIGRATIONS
from verify_local_baseline import CONTAINER, MIGRATIONS, ROOT, command, docker_path, local_sql


PLATFORM_SCHEMAS = frozenset({
    "auth", "storage", "extensions", "realtime", "supabase_functions", "vault",
    "graphql", "graphql_public", "net", "pgsodium", "pgsodium_masks", "cron",
    "_analytics", "_realtime", "_supavisor",
})
APP_SCHEMAS = frozenset({"public", "imfit_private", "supabase_migrations"})
APP_TOC_OBJECTS = frozenset({
    "TRIGGER auth users on_auth_user_created supabase_auth_admin",
    "POLICY storage objects avatars_select_own supabase_storage_admin",
    "POLICY storage objects avatars_insert_own supabase_storage_admin",
    "POLICY storage objects avatars_update_own supabase_storage_admin",
    "POLICY storage objects avatars_delete_own supabase_storage_admin",
})
FINAL_FIXTURES = (
    "online_only_operation_receipts.sql", "online_only_remote_reads.sql",
    "online_only_final_security.sql", "online_only_home_summary.sql",
    "online_only_query_plans.sql", "online_only_legacy_recovery.sql",
)


def filter_platform_toc(toc, roles):
    """Exclude only the five known app objects embedded in platform schemas."""
    retained, excluded = [], set()
    for line in toc.splitlines():
        if not line or line.startswith(";"):
            retained.append(line)
            continue
        match = re.fullmatch(r"\d+; \d+ \d+ (.+)", line)
        if not match:
            raise RuntimeError("Unrecognized platform dump TOC record: " + line)
        item = match.group(1)
        owner = item.rsplit(" ", 1)[-1]
        ownerless_extension = item.startswith(("EXTENSION - ", "COMMENT - EXTENSION "))
        if not ownerless_extension and owner != "-" and owner not in roles:
            raise RuntimeError("Platform dump references unavailable role: " + owner)
        if item in APP_TOC_OBJECTS:
            if item in excluded:
                raise RuntimeError("Duplicate app-dependent platform TOC object: " + item)
            excluded.add(item)
            continue
        if (item.startswith("TRIGGER auth users ") or item.startswith("POLICY storage ")
                or item.startswith("TABLE DATA ")):
            raise RuntimeError("Unknown app-dependent/platform-data TOC object: " + item)
        retained.append(line)
    if excluded != APP_TOC_OBJECTS:
        raise RuntimeError("Platform dump missing exact app-dependent TOC objects: "
                           + ", ".join(sorted(APP_TOC_OBJECTS - excluded)))
    return "\n".join(retained) + "\n"


def run():
    primary_sql = local_sql()
    files = sorted((ROOT / "supabase/migrations").glob("*.sql"))
    versions = [path.name.split("_", 1)[0] for path in files]
    actual = json.loads(primary_sql("select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
    if actual != FINAL_MIGRATIONS or versions != FINAL_MIGRATIONS:
        raise RuntimeError("Expected exact fourteen-migration final source and local inventory")
    metadata = json.loads(primary_sql("""
      begin read only;
      select jsonb_build_object(
        'version',current_setting('server_version_num')::integer,
        'schemas',(select jsonb_agg(nspname order by nspname) from pg_namespace
          where nspname not like 'pg_%' and nspname<>'information_schema'),
        'roles',(select jsonb_agg(rolname order by rolname) from pg_roles),
        'restore_admin',(select rolsuper or rolcreatedb from pg_roles where rolname='supabase_admin'));
      rollback;
    """))
    if not 170000 <= metadata["version"] < 180000:
        raise RuntimeError("Platform bootstrap requires the configured local PostgreSQL 17")
    unknown = set(metadata["schemas"]) - PLATFORM_SCHEMAS - APP_SCHEMAS
    if unknown:
        raise RuntimeError("Unrecognized local platform schemas; review before replay: " + ", ".join(sorted(unknown)))
    required_roles = {"postgres", "supabase_admin", "supabase_auth_admin", "supabase_storage_admin", "anon", "authenticated", "service_role"}
    missing_roles = required_roles - set(metadata["roles"])
    if missing_roles or not metadata["restore_admin"]:
        raise RuntimeError("Platform bootstrap roles unavailable/restore admin cannot create DB: " + ", ".join(sorted(missing_roles)))
    docker = docker_path()
    marker = uuid.uuid4().hex
    fresh = "imfit_ready_fresh_" + marker
    upgrade = "imfit_ready_upgrade_" + marker
    dump_path = "/tmp/imfit-platform-" + marker + ".dump"
    toc_path = "/tmp/imfit-platform-" + marker + ".list"

    def execute(database, text):
        if database not in (fresh, upgrade):
            raise RuntimeError("Refusing unexpected verification database")
        return command([docker, "exec", "-i", CONTAINER, "psql", "-X", "-q", "-t", "-A",
            "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database], text)

    def bootstrap(database):
        command([docker, "exec", CONTAINER, "createdb", "-U", "supabase_admin", "-O", "postgres",
                 "--template=template0", database])
        command([docker, "exec", CONTAINER, "pg_restore", "-U", "supabase_admin", "-d", database,
                 "--exit-on-error", "--use-list=" + toc_path, dump_path])
        absent = json.loads(execute(database, "select jsonb_build_object('templates',to_regclass('public.workout_templates'),"
            "'registry',to_regclass('imfit_private.operation_receipts'),'migration_history',to_regclass('supabase_migrations.schema_migrations'));"))
        if any(absent.values()):
            raise RuntimeError("Platform restore included application schema/data")
        execute(database, "create schema if not exists supabase_migrations; create table supabase_migrations.schema_migrations(version text primary key);")

    def apply(database, migration):
        version = migration.name.split("_", 1)[0]
        execute(database, "begin;\n" + migration.read_text(encoding="utf-8")
                + "\ninsert into supabase_migrations.schema_migrations(version) values('" + version + "');\ncommit;")

    def fixture(database, name):
        execute(database, (ROOT / "supabase/tests" / name).read_text(encoding="utf-8"))

    def staged_apply(database, selected):
        stages = {
            MIGRATIONS[-1]: "template_revision_writes.sql",
            "20261028000100": "online_only_operation_receipts.sql",
            "20261028000200": "online_only_session_lifecycle.sql",
            "20261028000300": "online_only_remote_reads.sql",
        }
        for migration in selected:
            apply(database, migration)
            stage = stages.get(migration.name.split("_", 1)[0])
            if stage:
                fixture(database, stage)

    try:
        # Schema-only intentionally contains no auth rows, storage rows or secrets.
        command([docker, "exec", CONTAINER, "pg_dump", "-U", "supabase_admin", "-d", "postgres",
                 "--schema-only", "--format=custom", "--no-publications", "--no-subscriptions",
                 "--exclude-schema=public", "--exclude-schema=imfit_private",
                 "--exclude-schema=supabase_migrations", "--file=" + dump_path])
        toc = command([docker, "exec", CONTAINER, "pg_restore", "--list", dump_path])
        retained = filter_platform_toc(toc, set(metadata["roles"]))
        command([docker, "exec", "-i", CONTAINER, "sh", "-c", "umask 077; cat > " + toc_path], retained)
        bootstrap(fresh)
        staged_apply(fresh, files)
        for name in FINAL_FIXTURES:
            fixture(fresh, name)

        bootstrap(upgrade)
        staged_apply(upgrade, files[:4])
        old_versions = json.loads(execute(upgrade, "select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
        if old_versions != MIGRATIONS:
            raise RuntimeError("Synthetic upgrade did not start at exact four-migration baseline")
        # Only synthetic rows are ever inserted into this new verification DB.
        execute(upgrade, """
          begin;
          insert into auth.users(id,email) values
            ('c0000000-0000-0000-0000-000000000001','fixture-upgrade@example.invalid');
          set local role authenticated;
          select set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000001',true);
          select public.save_template_revision('10000000-0000-0000-0000-000000000099',
            '20000000-0000-0000-0000-000000000099',null,'Legacy fixture',
            '[{"exercise_id":"ex_chest_1","order_index":0,"sets":"3","reps":"10","rest_seconds":"60"}]',
            '[]',false,'c0000000-0000-0000-0000-000000000001');
          reset role;
          insert into public.active_sessions(id,user_id,template_id,template_name,session_data,expires_at)
          values('40000000-0000-0000-0000-000000000099','c0000000-0000-0000-0000-000000000001',
            '20000000-0000-0000-0000-000000000099','Legacy fixture',
            '{"legacy":"preserve","sets":[{"reps":12,"weight":1.005}]}',clock_timestamp()+interval '30 days');
          commit;
        """)
        before = json.loads(execute(upgrade, """
          select jsonb_build_object('receipt',(select receipt from public.template_write_receipts
            where request_id='10000000-0000-0000-0000-000000000099'),
            'draft',(select session_data from public.active_sessions where id='40000000-0000-0000-0000-000000000099'));
        """))
        staged_apply(upgrade, files[4:])
        after = json.loads(execute(upgrade, """
          begin;
          set local role authenticated;
          select set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000001',true);
          select public.save_template_revision('10000000-0000-0000-0000-000000000099',
            '20000000-0000-0000-0000-000000000099',null,'Legacy fixture',
            '[{"exercise_id":"ex_chest_1","order_index":0,"sets":"3","reps":"10","rest_seconds":"60"}]',
            '[]',false,'c0000000-0000-0000-0000-000000000001');
          rollback;
        """).splitlines()[-1])
        if after != before["receipt"]:
            raise RuntimeError("Legacy receipt replay changed after upgrade")
        preserved = json.loads(execute(upgrade, """
          select jsonb_build_object('draft',s.session_data,'version',s.version,'schema_version',s.schema_version,
            'review_required',s.review_required,'receipt',r.result,'timestamp_source',r.committed_at_source,
            'retained_90_days',r.retain_until>=r.committed_at+interval '90 days')
          from public.active_sessions s join imfit_private.operation_receipts r
            on r.user_id=s.user_id and r.request_id='10000000-0000-0000-0000-000000000099'
          where s.id='40000000-0000-0000-0000-000000000099';
        """))
        if (preserved["draft"] != before["draft"] or preserved["receipt"] != before["receipt"]
            or preserved["version"] is not None or preserved["schema_version"] is not None
            or not preserved["review_required"] or not preserved["retained_90_days"]
            or preserved["timestamp_source"] != "OBSERVED_BACKFILL"):
            raise RuntimeError("Legacy preservation/backfill invariant failed")
        execute(upgrade, """
          begin;
          set local role authenticated;
          select set_config('request.jwt.claim.sub','c0000000-0000-0000-0000-000000000001',true);
          do $$ begin
            begin perform public.get_active_session(); raise exception 'FAIL legacy review bypass';
            exception when sqlstate 'PT409' then
              if sqlerrm<>'LEGACY_REVIEW_REQUIRED' then raise; end if;
            end;
          end $$;
          rollback;
        """)
        for name in FINAL_FIXTURES:
            fixture(upgrade, name)
        for database in (fresh, upgrade):
            final_versions = json.loads(execute(database, "select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
            if final_versions != FINAL_MIGRATIONS:
                raise RuntimeError("Verification database final migration inventory mismatch")
    finally:
        # Remove only the two unique temporary schema files, never a database.
        command([docker, "exec", CONTAINER, "rm", "-f", "--", dump_path, toc_path])
    print(json.dumps({
        "status": "PASS", "fresh_database": fresh, "upgrade_database": upgrade,
        "migration_versions": versions,
        "source_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files},
        "checks": ["current_local_schema_only_platform_bootstrap", "exact_app_TOC_filter_and_roles",
            "all_source_migrations_fresh_replay", "staged_lifecycle_before_lockdown",
            "final_RPC_lifecycle_security_reads_and_legacy_recovery", "synthetic_four_migration_upgrade",
            "legacy_draft_preserved_and_review_blocked", "old_receipt_exact_replay",
            "observed_backfill_timestamp_and_90_day_retention"],
        "scope": "local PostgreSQL 17 schema replay/upgrade; not fresh cluster, device, or hosted deployment",
        "cleanup": "temporary schema dump/list removed; verification databases retained; no reset/drop/purge",
    }, indent=2))


if __name__ == "__main__":
    try:
        run()
    except (RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError, KeyError, ValueError) as error:
        print(f"FAIL/BLOCKED local migration paths: {error}", file=sys.stderr)
        sys.exit(1)
