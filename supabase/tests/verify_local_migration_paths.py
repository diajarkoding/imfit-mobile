"""Replay app migrations on a platform-only local database and upgrade a v4 dump.

Requires the local pre-additive backup produced during F1b. It creates fresh,
uniquely named verification databases in the already-guarded local container.
No database/schema is dropped, reset, purged, or exposed through a remote URL.
Verification databases are retained for inspection.
"""

import hashlib
import json
import subprocess
import sys
import uuid

from verify_local_baseline import CONTAINER, MIGRATIONS, ROOT, command, docker_path, local_sql


DUMP = "/tmp/imfit-f1b-baseline-c0be6b8.dump"


def run():
    primary_sql = local_sql()
    files = sorted((ROOT / "supabase/migrations").glob("*.sql"))
    versions = [path.name.split("_", 1)[0] for path in files]
    actual = json.loads(primary_sql("select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
    if actual != versions or versions[:4] != MIGRATIONS:
        raise RuntimeError("Local source/migration inventory mismatch")
    docker = docker_path()
    marker = uuid.uuid4().hex[:8]
    fresh = "imfit_f1b_fresh_" + marker
    upgrade = "imfit_f1b_upgrade_" + marker

    def execute(database, text):
        if database not in (fresh, upgrade):
            raise RuntimeError("Refusing unexpected verification database")
        return command([docker, "exec", "-i", CONTAINER, "psql", "-X", "-q", "-t", "-A",
            "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database], text)

    def create(database):
        command([docker, "exec", CONTAINER, "createdb", "-U", "supabase_admin", "-O", "postgres",
                 "--template=template0", database])

    def apply(database, migration):
        version = migration.name.split("_", 1)[0]
        text = migration.read_text(encoding="utf-8")
        execute(database, "begin;\n" + text + "\ninsert into supabase_migrations.schema_migrations(version) values('"
                + version + "');\ncommit;")

    def fixture(database, name):
        execute(database, (ROOT / "supabase/tests" / name).read_text(encoding="utf-8"))

    toc = command([docker, "exec", CONTAINER, "pg_restore", "--list", DUMP])
    retained = []
    excluded = []
    for line in toc.splitlines():
        # These objects originated in app migrations, not the Supabase platform.
        app_object = ("TRIGGER auth users on_auth_user_created" in line
            or "POLICY storage objects avatars_" in line
            or "TABLE DATA storage buckets " in line)
        (excluded if app_object else retained).append(line)
    if len(excluded) != 6:
        raise RuntimeError("Unexpected platform backup TOC; review before replay")
    toc_path = "/tmp/imfit-platform-" + marker + ".list"
    command([docker, "exec", "-i", CONTAINER, "sh", "-c", "cat > " + toc_path], "\n".join(retained))
    create(fresh)
    command([docker, "exec", CONTAINER, "pg_restore", "-U", "supabase_admin", "-d", fresh,
        "--exit-on-error", "--exclude-schema=public", "--exclude-schema=supabase_migrations",
        "--use-list=" + toc_path, DUMP])
    absent = json.loads(execute(fresh, "select jsonb_build_object('templates',to_regclass('public.workout_templates'),"
        "'registry',to_regclass('imfit_private.operation_receipts'),'migration_history',to_regclass('supabase_migrations.schema_migrations'));"))
    if any(absent.values()):
        raise RuntimeError("Fresh replay did not start with an empty app schema")
    execute(fresh, "create schema if not exists supabase_migrations; create table supabase_migrations.schema_migrations(version text primary key);")
    for position, migration in enumerate(files):
        apply(fresh, migration)
        if position == 3:
            fixture(fresh, "template_revision_writes.sql")
        elif position == 4:
            fixture(fresh, "online_only_operation_receipts.sql")
        elif position == 5:
            fixture(fresh, "online_only_session_lifecycle.sql")
        elif position == 6:
            fixture(fresh, "online_only_remote_reads.sql")
    for name in ("online_only_operation_receipts.sql", "online_only_remote_reads.sql", "online_only_final_security.sql", "online_only_home_summary.sql"):
        fixture(fresh, name)

    create(upgrade)
    command([docker, "exec", CONTAINER, "pg_restore", "-U", "supabase_admin", "-d", upgrade,
             "--exit-on-error", DUMP])
    old_versions = json.loads(execute(upgrade, "select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
    if old_versions != MIGRATIONS:
        raise RuntimeError("Upgrade dump is not the expected four-migration baseline")
    # Legacy receipt deliberately used numeric strings accepted by the old RPC.
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
    for migration in files[4:]:
        apply(upgrade, migration)
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
    print(json.dumps({
        "status": "PASS", "fresh_database": fresh, "upgrade_database": upgrade,
        "migration_versions": versions,
        "source_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in files},
        "checks": ["platform_only_restore_empty_app_schema", "all_source_migrations_fresh_replay",
            "staged_SQL_fixtures", "final_SQL_security_and_reads", "four_migration_dump_upgrade",
            "legacy_draft_preserved_and_review_blocked", "old_receipt_exact_replay",
            "observed_backfill_timestamp_and_90_day_retention"],
        "scope": "local application-schema replay/upgrade on PostgreSQL 17; not a fresh Docker cluster, CI, device, or remote deployment",
        "cleanup": "verification databases retained; no reset/drop/purge",
    }, indent=2))


if __name__ == "__main__":
    try:
        run()
    except (RuntimeError, subprocess.TimeoutExpired, json.JSONDecodeError, KeyError, ValueError) as error:
        print(f"FAIL/BLOCKED local migration paths: {error}", file=sys.stderr)
        sys.exit(1)
