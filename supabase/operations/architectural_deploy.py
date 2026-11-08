#!/usr/bin/env python3
"""Apply the twelve T11 migrations, never resume or repair a partial deployment.

Use explicit PGHOST, PGPORT, PGUSER and PGDATABASE; libpq obtains authentication
from the environment/passfile, never command-line arguments. Evidence must be a
NEW directory outside the repository. Live additionally requires the completed
full_database_backup_restore.py backup and its passing child restore evidence.

  python supabase/operations/architectural_deploy.py --target local --evidence-directory <outside-repo-new-directory>
  python supabase/operations/architectural_deploy.py --target live --backup-directory <backup-directory> --evidence-directory <outside-repo-new-directory>

Each migration commits independently WITH its exact repository version/name and
normalized statement array. Failure preserves earlier commits, rolls back the
current transaction on disconnect, and blocks a fresh run at pristine preflight.
An interrupted COMMIT may have an unknown outcome: inspect evidence and ledger;
this program deliberately provides no resume, rollback or ledger-repair command.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from full_database_backup_restore import canonical_schema, file_hash, restrict, save_json

REPOSITORY = Path(__file__).resolve().parents[2]
OPERATIONS = Path(__file__).resolve().parent
MIGRATIONS = REPOSITORY / 'supabase' / 'migrations'
BASELINE = (
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
    ('20261101000200', 'backend_rpc_multimodality_modernization'),
)
PENDING = (
    ('20261101000300', 'local_workout_legacy_provenance'),
    ('20261102144339', 'routine_rest_runtime_contract'),
    ('20261105000100', 'legacy_snapshot_metadata'),
    ('20261106000100', 'exercise_catalog_capabilities'),
    ('20261106000200', 'routine_set_targets'),
    ('20261106000300', 'routine_target_validation'),
    ('20261106000400', 'workout_results_v3'),
    ('20261106000500', 'routine_revision_targets_v3'),
    ('20261106000600', 'active_session_v3'),
    ('20261106000610', 'active_session_v3_lifecycle'),
    ('20261106000700', 'local_workout_aggregate_v3'),
    ('20261106000800', 'workout_read_contract_v3'),
)
WORD = re.compile(r'[^\W\d][\w$]*', re.UNICODE)
DOLLAR = re.compile(r'\$(?:[^\W\d][\w]*\$|\$)', re.UNICODE)


def split_statements(sql):
    """Lex SQL, not lines: nested comments, quoted identifiers, E/U& strings,
    doubled quotes and tagged dollar bodies cannot introduce statement breaks.
    Standard strings require standard_conforming_strings=on (forced by psql).
    Reject psql commands rather than letting client-side commands escape the tx.
    """
    if '\x00' in sql:
        raise RuntimeError('NUL byte in migration SQL.')
    result = []
    tokens = []
    start = i = 0
    last_string_end = None
    last_string_escaped = False
    while i < len(sql):
        char = sql[i]
        if char.isspace():
            i += 1
            continue
        if sql.startswith('--', i):
            end = sql.find('\n', i + 2)
            i = len(sql) if end < 0 else end + 1
            continue
        if sql.startswith('/*', i):
            depth = 1
            i += 2
            while i < len(sql) and depth:
                if sql.startswith('/*', i):
                    depth += 1
                    i += 2
                elif sql.startswith('*/', i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            if depth:
                raise RuntimeError('Unterminated block comment in migration.')
            continue
        if char in "'\"":
            quote = char
            escaped = quote == "'" and (
                (i > 0 and sql[i - 1] in 'eE' and
                 (i < 2 or not (sql[i - 2].isalnum() or sql[i - 2] in '_$'))) or
                (last_string_end is not None and last_string_escaped and
                 '\n' in sql[last_string_end:i]))
            i += 1
            while i < len(sql):
                if escaped and sql[i] == '\\':
                    i += 2
                elif sql[i] == quote:
                    if i + 1 < len(sql) and sql[i + 1] == quote:
                        i += 2
                    else:
                        i += 1
                        break
                else:
                    i += 1
            else:
                raise RuntimeError('Unterminated quoted string/identifier in migration.')
            tokens.append('<literal>' if quote == "'" else '<identifier>')
            last_string_end = i if quote == "'" else None
            last_string_escaped = escaped
            continue
        dollar = DOLLAR.match(sql, i) if char == '$' else None
        if dollar:
            end = sql.find(dollar.group(), dollar.end())
            if end < 0:
                raise RuntimeError('Unterminated dollar-quoted body in migration.')
            i = end + len(dollar.group())
            tokens.append('<literal>')
            last_string_end = None
            continue
        if char == '\\':
            raise RuntimeError('psql meta-commands are forbidden in migrations.')
        if char == ';':
            if tokens:
                result.append((sql[start:i + 1].strip(), tokens))
            start = i + 1
            tokens = []
            last_string_end = None
            i += 1
            continue
        word = WORD.match(sql, i)
        if word:
            tokens.append(word.group().upper())
            i = word.end()
        else:
            tokens.append(char)
            i += 1
        last_string_end = None
    if tokens:
        result.append((sql[start:].strip() + '\n;', tokens))
    return result


def normalized_statements(version, text):
    parsed = split_statements(text)
    if not parsed:
        raise RuntimeError('Empty migration: ' + version)
    if version == '20261102144339':
        if parsed[0][1] != ['BEGIN'] or parsed[-1][1] != ['COMMIT']:
            raise RuntimeError('Expected exactly the known outer BEGIN/COMMIT pair: ' + version)
        parsed = parsed[1:-1]
    for _, tokens in parsed:
        if (tokens[0] in {'BEGIN', 'START', 'COMMIT', 'END', 'ROLLBACK', 'ABORT',
                          'SAVEPOINT', 'RELEASE', 'PREPARE', 'COPY'} or
                (tokens[0] == 'SET' and tokens[1:2] != ['LOCAL'])):
            raise RuntimeError('Unexpected transaction/session control or COPY in ' + version)
    if not parsed:
        raise RuntimeError('Migration has no executable body: ' + version)
    return [text for text, _ in parsed]


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def outside_repository(path):
    resolved = path.resolve()
    if resolved.is_relative_to(REPOSITORY):
        raise RuntimeError('Evidence and backups must remain outside the repository.')
    return resolved


def connection_environment(target):
    environment = os.environ.copy()
    for key in ('PGHOST', 'PGPORT', 'PGUSER', 'PGDATABASE'):
        if not environment.get(key):
            raise RuntimeError('Explicit ' + key + ' is required.')
    if (environment.get('PGHOSTADDR') or environment.get('PGSERVICE') or
            ',' in environment['PGHOST'] or ',' in environment['PGPORT'] or
            '=' in environment['PGDATABASE'] or '://' in environment['PGDATABASE']):
        raise RuntimeError('Service, host-address overrides, multi-host and connection-string databases are forbidden.')
    if any('\n' in environment[key] or '\r' in environment[key]
           for key in ('PGHOST', 'PGPORT', 'PGUSER', 'PGDATABASE')):
        raise RuntimeError('Connection fields must not contain line breaks.')
    port = int(environment['PGPORT'])
    if not 1 <= port <= 65535 or port == 6543:
        raise RuntimeError('Use a direct/session connection, not the transaction pooler.')
    if target == 'local':
        host = environment['PGHOST']
        if host not in ('localhost', '127.0.0.1', '::1') and not host.startswith('/'):
            raise RuntimeError('Local rehearsal requires loopback or a local Unix socket.')
    elif environment.get('PGSSLMODE') not in ('require', 'verify-ca', 'verify-full'):
        raise RuntimeError('Live requires PGSSLMODE=require, verify-ca or verify-full.')
    # No password is inspected, copied to an argument, or included in evidence.
    environment['PGOPTIONS'] = '-c standard_conforming_strings=on -c client_encoding=UTF8'
    environment.setdefault('PGCONNECT_TIMEOUT', '20')
    environment['PGAPPNAME'] = 'imfit-t11-architectural-deploy'
    return environment


def backup_gate(directory, environment):
    directory = outside_repository(directory)
    evidence_path = directory / 'backup-evidence.json'
    evidence = json.loads(evidence_path.read_text(encoding='utf-8'))
    required = {'database.dump', 'globals.sql', 'source-manifest.json', 'source-schema.sql'}
    if (evidence.get('status') != 'BACKUP_COMPLETE_RESTORE_UNPROVEN' or
            not required.issubset(evidence.get('files', {}))):
        raise RuntimeError('A complete genuine backup with all four recorded checksums is required.')
    if (evidence.get('source_host') != environment['PGHOST'] or
            str(evidence.get('source_port')) != environment['PGPORT'] or
            evidence.get('source_user') != environment['PGUSER'] or
            evidence.get('database') != environment['PGDATABASE']):
        raise RuntimeError('Backup source does not match the explicit live connection.')
    for name, digest in evidence['files'].items():
        path = directory / name
        if (name not in required or path.is_symlink() or not path.is_file() or
                not re.fullmatch(r'[0-9a-f]{64}', digest) or file_hash(path) != digest):
            raise RuntimeError('Backup checksum/path mismatch; deployment refused.')
    source_manifest = json.loads((directory / 'source-manifest.json').read_text(encoding='utf-8'))
    source_schema = canonical_schema((directory / 'source-schema.sql').read_text(encoding='utf-8'))
    for child in sorted(directory.glob('restore-*')):
        if not child.is_dir() or child.is_symlink():
            continue
        restore_path = child / 'restore-evidence.json'
        if not restore_path.is_file():
            continue
        restore = json.loads(restore_path.read_text(encoding='utf-8'))
        if (restore.get('status') != 'RESTORE_FIDELITY_PASSED' or
                restore.get('schema_matches') is not True or
                Path(restore.get('backup_directory', '')).resolve() != directory):
            continue
        restored_manifest = child / 'restored-manifest.json'
        restored_schema = child / 'restored-schema.sql'
        if (json.loads(restored_manifest.read_text(encoding='utf-8')) != source_manifest or
                canonical_schema(restored_schema.read_text(encoding='utf-8')) != source_schema):
            raise RuntimeError('Passing restore evidence no longer matches its backup.')
        return {'backup_directory': str(directory),
                'backup_evidence_sha256': file_hash(evidence_path),
                'backup_files': evidence['files'],
                'restore_directory': str(child),
                'restore_evidence_sha256': file_hash(restore_path),
                'restored_manifest_sha256': file_hash(restored_manifest),
                'restored_schema_sha256': file_hash(restored_schema)}
    raise RuntimeError('No child RESTORE_FIDELITY_PASSED evidence refers to this verified backup.')


def psql(sql, directory, label, environment):
    (directory / (label + '.sql')).write_text(sql, encoding='utf-8')
    result = subprocess.run(
        ['psql', '-X', '-qAt', '--no-password', '-v', 'ON_ERROR_STOP=1', '--file=-'],
        input=sql, text=True, encoding='utf-8', errors='replace', env=environment,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (directory / (label + '.stdout.log')).write_text(result.stdout, encoding='utf-8')
    (directory / (label + '.stderr.log')).write_text(result.stderr, encoding='utf-8')
    if result.returncode:
        raise RuntimeError('psql failed; inspect restricted ' + label + ' logs. Do not retry or repair ledger.')
    return result.stdout


BASELINE_VERSIONS_SQL = ','.join(literal(version) for version, _ in BASELINE)
BASELINE_HASH_SQL = """SELECT encode(sha256(convert_to(
    coalesce(jsonb_agg(to_jsonb(m) ORDER BY m.version), '[]'::jsonb)::text,
    'UTF8')), 'hex') FROM supabase_migrations.schema_migrations m
    WHERE m.version IN (""" + BASELINE_VERSIONS_SQL + ')'


def predecessor_guard(applied, baseline_hash):
    expected = list(BASELINE) + [(m['version'], m['name']) for m in applied]
    values = ',\n'.join('(' + literal(v) + ',' + literal(n) + ')' for v, n in expected)
    previous = json.dumps([{'version': m['version'], 'statements': m['statements']} for m in applied])
    return """DO $t11_ledger_guard$
BEGIN
  IF EXISTS (
    WITH expected(version, name) AS (VALUES """ + values + """)
    SELECT 1 FROM expected e FULL JOIN supabase_migrations.schema_migrations a USING(version)
    WHERE e.version IS NULL OR a.version IS NULL OR e.name IS DISTINCT FROM a.name
  ) THEN
    RAISE EXCEPTION 'BLOCKED: migration predecessor ledger drift; no resume or repair';
  END IF;
  IF (""" + BASELINE_HASH_SQL + ') IS DISTINCT FROM ' + literal(baseline_hash) + """ THEN
    RAISE EXCEPTION 'BLOCKED: recorded baseline ledger contents changed';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_to_recordset(""" + literal(previous) + """::jsonb) e(version text, statements jsonb)
    JOIN supabase_migrations.schema_migrations a USING(version)
    WHERE to_jsonb(a.statements) IS DISTINCT FROM e.statements
  ) THEN
    RAISE EXCEPTION 'BLOCKED: applied migration statements changed';
  END IF;
END;
$t11_ledger_guard$;
"""


def deployment_sql(migration, applied, baseline_hash):
    statements = 'ARRAY[' + ',\n'.join(literal(s) for s in migration['statements']) + ']::text[]'
    return """BEGIN;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';
SELECT pg_catalog.pg_advisory_xact_lock(20261106, 1100);
LOCK TABLE supabase_migrations.schema_migrations IN SHARE ROW EXCLUSIVE MODE;
""" + predecessor_guard(applied, baseline_hash) + '\n'.join(migration['statements']) + """
INSERT INTO supabase_migrations.schema_migrations(version, name, statements)
VALUES (""" + literal(migration['version']) + ',' + literal(migration['name']) + ',' + statements + """);
COMMIT;
"""


def deploy(args):
    os.umask(0o077)
    directory = outside_repository(Path(args.evidence_directory))
    if directory.exists():
        raise RuntimeError('Evidence directory must be new; never overwrite a prior deployment attempt.')
    directory.mkdir(parents=True, mode=0o700)
    restrict(directory)
    evidence = {'status': 'INCOMPLETE', 'target': args.target,
                'started_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                'committed_versions': [], 'migrations': []}
    save_json(directory / 'deployment-evidence.json', evidence)
    try:
        environment = connection_environment(args.target)
        if args.target == 'live':
            if not args.backup_directory:
                raise RuntimeError('Live deployment requires --backup-directory.')
            evidence['backup_gate'] = backup_gate(Path(args.backup_directory), environment)
        elif args.backup_directory:
            raise RuntimeError('--backup-directory is a live-only gate, not a local connection setting.')
        loaded = []
        for version, name in PENDING:
            source = MIGRATIONS / (version + '_' + name + '.sql')
            # Read once: captured bytes are the bytes actually deployed even if
            # a source file changes later. Never alter the repository migration.
            raw = source.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            text = raw.decode('utf-8')
            statements = normalized_statements(version, text)
            (directory / source.name).write_bytes(raw)
            loaded.append({'version': version, 'name': name, 'statements': statements})
            evidence['migrations'].append({'version': version, 'name': name,
                                           'file': source.name, 'sha256': digest,
                                           'statement_count': len(statements),
                                           'outer_transaction_removed': version == '20261102144339'})
        preflight_path = OPERATIONS / 'architectural_deployment_preflight.sql'
        preflight_raw = preflight_path.read_bytes()
        preflight = preflight_raw.decode('utf-8')
        evidence['preflight_sha256'] = hashlib.sha256(preflight_raw).hexdigest()
        evidence['runner_sha256'] = file_hash(Path(__file__))
        save_json(directory / 'deployment-evidence.json', evidence)
        psql(preflight, directory, '00-pristine-preflight', environment)
        snapshot_sql = """BEGIN READ ONLY;
SELECT jsonb_build_object('identities',
    (SELECT jsonb_agg(jsonb_build_array(version,name) ORDER BY version)
     FROM supabase_migrations.schema_migrations), 'baseline_sha256',
    (""" + BASELINE_HASH_SQL + "))::text;\nROLLBACK;\n"
        snapshot = json.loads(psql(snapshot_sql, directory, '01-baseline-ledger', environment))
        if snapshot['identities'] != [list(pair) for pair in BASELINE]:
            raise RuntimeError('Ledger changed after preflight; pristine deployment refused.')
        evidence['baseline_ledger_sha256'] = snapshot['baseline_sha256']
        evidence['preflight_passed'] = True
        save_json(directory / 'deployment-evidence.json', evidence)
        applied = []
        for index, migration in enumerate(loaded, 1):
            evidence['in_flight_version'] = migration['version']
            save_json(directory / 'deployment-evidence.json', evidence)
            sql = deployment_sql(migration, applied, snapshot['baseline_sha256'])
            psql(sql, directory, f'{index + 1:02d}-apply-' + migration['version'], environment)
            applied.append(migration)
            evidence['committed_versions'].append(migration['version'])
            evidence.pop('in_flight_version')
            save_json(directory / 'deployment-evidence.json', evidence)
        # Verify the final ledger against exactly the inserted statements while
        # retaining the untouched baseline hash; this transaction makes no writes.
        final_sql = """BEGIN;
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';
SELECT pg_catalog.pg_advisory_xact_lock(20261106, 1100);
LOCK TABLE supabase_migrations.schema_migrations IN SHARE ROW EXCLUSIVE MODE;
""" + predecessor_guard(applied, snapshot['baseline_sha256']) + "ROLLBACK;\n"
        psql(final_sql, directory, '14-final-ledger', environment)
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
    print('Ordered deployment committed; restricted evidence: ' + str(directory))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--target', choices=('local', 'live'), required=True)
    parser.add_argument('--backup-directory')
    parser.add_argument('--evidence-directory', required=True)
    args = parser.parse_args()
    try:
        deploy(args)
    except (Exception, KeyboardInterrupt) as error:
        # Unexpected library errors may contain connection details; keep console
        # failures generic rather than echoing raw libpq/environment exceptions.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('BLOCKED: ' + message, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
