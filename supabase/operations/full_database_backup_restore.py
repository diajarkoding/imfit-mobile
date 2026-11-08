#!/usr/bin/env python3
"""G03 genuine logical backup and isolated restore; never uses the Management API.

Backup (prompts for an EXISTING authorized database password; never resets it):
  python supabase/operations/full_database_backup_restore.py backup --host aws-1-ap-south-1.pooler.supabase.com --user postgres.ltcxltahkrjnsjaxndsa
Restore (use an image containing every source extension at its recorded version):
  python supabase/operations/full_database_backup_restore.py restore --backup-directory <completed-backup-directory> --image <compatible-postgres-17-image>

No schemas, tables, rows, ownership or ACLs are deliberately excluded. Database role
passwords are deliberately NOT exported. Storage object BYTES are not PostgreSQL
content and require a separate Storage backup. A missing extension, permission, row,
sequence state, owner or grant fails the restore gate rather than being ignored.
All logs/artifacts, including failed attempts, stay in the restricted outside-Git root.
Only local Docker containers are created; source connections are read-only.
"""
import argparse
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import secrets
import re
import subprocess
import sys
import threading

ROOT = Path.home() / 'IMFIT-SAFE' / 'full-database-20261106'
CONTAINER = 'imfit-g03-20261106'
EXCLUDED = "n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'"
SETTINGS = "SET timezone='UTC'; SET datestyle='ISO, YMD'; SET extra_float_digits=3; SET bytea_output='hex'; SET row_security=off; SET search_path=pg_catalog;"


def run(args, log=None, input_text=None):
    result = subprocess.run(args, input=input_text, text=True, encoding='utf-8', errors='replace', stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if log:
        Path(log).write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        raise RuntimeError('Command failed; inspect restricted log: ' + str(log or 'unavailable'))
    return result.stdout


def restrict(directory):
    directory.mkdir(parents=True, exist_ok=True)
    if os.name == 'nt':
        sid = json.loads(run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', '[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value | ConvertTo-Json']).strip())
        run(['icacls.exe', str(directory), '/inheritance:r', '/grant:r', '*' + sid + ':(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'])
    else:
        directory.chmod(0o700)


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_array_casts(text):
    # Re-parsing a dump expands a varchar-literal array -> text[] coercion
    # into per-element text casts. These are the same typed literal expression.
    literal = r"'(?:[^']|'')*'::character varying"
    pattern = r"ARRAY\[((?:" + literal + r")(?:, " + literal + r")*)\]::text\[\]"
    text = re.sub(pattern, lambda match: 'ARRAY[' + re.sub(literal, lambda item: item.group()+'::text',match.group(1)) + ']', text)
    quoted = r"\(ARRAY\[((?:" + literal + r")(?:, " + literal + r")*)\]\)::\"text\"\[\]"
    return re.sub(quoted,lambda match: 'ARRAY['+re.sub(literal,lambda item: '('+item.group()+')::"text"',match.group(1))+']',text)


def canonical_schema(text):
    # Owners/object definitions/comments must match. Compare ACLs separately in
    # the complete manifest: pg_dump omits extension-init grants and may reorder
    # equivalent GRANT statements, so its ACL text is not a fidelity oracle.
    text = '\n'.join(line for line in text.splitlines()
                     if not line.startswith(('-- Dumped from database version ', '-- Dumped by pg_dump version ', '\\restrict ', '\\unrestrict ')))
    sections = re.split(r'(?m)(?=^--\n-- Name: )',text)
    return '\n\n'.join(canonical_array_casts(section.strip()) for section in sections
                      if not re.match(r'--\n-- Name: [^\n]*; Type: (?:ACL|DEFAULT ACL);',section))+'\n'


def docker_source(directory, image, host, port, user, database, command):
    database_option = '--database' if command[0] == 'pg_dumpall' else '--dbname'
    # Windows bind mounts may look world-readable to libpq. Copy inside the
    # ephemeral container and set Unix permissions; never put passwords in argv.
    wrapper = 'umask 077; cp /backup/source.pgpass /tmp/source.pgpass; chmod 600 /tmp/source.pgpass; exec "$@"'
    return ['docker', 'run', '--rm', '-i', '--mount', f'type=bind,source={directory.resolve()},target=/backup',
            '-e', 'PGPASSFILE=/tmp/source.pgpass', '-e', 'PGSSLMODE=require', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
            '-e', 'PGCONNECT_TIMEOUT=20', image, 'sh', '-c', wrapper, 'sh', *command,
            '--host', host, '--port', str(port), '--username', user, database_option, database, '--no-password']


def sql_source(directory, image, host, port, user, database, sql):
    args = docker_source(directory, image, host, port, user, database, ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'])
    return run(args, directory / 'source-query.log', sql)


def manifest_sql(snapshot=None):
    # Only fingerprints/counts leave PostgreSQL: never print customer rows.
    start = 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n'
    if snapshot:
        start += "SET TRANSACTION SNAPSHOT '" + snapshot + "';\n"
    return start + SETTINGS + "\n" + r"""
SELECT jsonb_build_object('kind','extension','name',e.extname,'version',e.extversion,'schema',n.nspname,'owner',pg_get_userbyid(e.extowner))::text
FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace ORDER BY e.extname;
SELECT jsonb_build_object('kind','tablespace','name',spcname,'owner',pg_get_userbyid(spcowner),'acl',spcacl,'options',spcoptions,'location',pg_tablespace_location(oid))::text FROM pg_tablespace ORDER BY spcname;
SELECT jsonb_build_object('kind','schema','name',n.nspname,'owner',pg_get_userbyid(n.nspowner),'acl',n.nspacl)::text
FROM pg_namespace n WHERE """ + EXCLUDED + r""" ORDER BY n.nspname;
SELECT jsonb_build_object('kind','relation','schema',n.nspname,'name',c.relname,'type',c.relkind,'owner',pg_get_userbyid(c.relowner),'acl',c.relacl,'rls',c.relrowsecurity,'force_rls',c.relforcerowsecurity,'replica_identity',c.relreplident,'options',c.reloptions)::text
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE """ + EXCLUDED + r""" ORDER BY n.nspname,c.relname;
SELECT jsonb_build_object('kind','column','schema',n.nspname,'relation',c.relname,'name',a.attname,'type',format_type(a.atttypid,a.atttypmod),'not_null',a.attnotnull,'identity',a.attidentity,'generated',a.attgenerated,'default',pg_get_expr(d.adbin,d.adrelid),'acl',a.attacl)::text
FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
WHERE """ + EXCLUDED + r""" AND a.attnum>0 AND NOT a.attisdropped ORDER BY n.nspname,c.relname,a.attnum;
SELECT jsonb_build_object('kind','routine','schema',n.nspname,'name',p.proname,'args',pg_get_function_identity_arguments(p.oid),'owner',pg_get_userbyid(p.proowner),'acl',p.proacl,'definition',CASE WHEN p.prokind IN ('f','p') THEN pg_get_functiondef(p.oid) ELSE NULL END)::text
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE """ + EXCLUDED + r""" ORDER BY n.nspname,p.proname,pg_get_function_identity_arguments(p.oid);
SELECT jsonb_build_object('kind','policy','schema',schemaname,'table',tablename,'name',policyname,'permissive',permissive,'roles',roles,'command',cmd,'using',qual,'check',with_check)::text FROM pg_policies ORDER BY schemaname,tablename,policyname;
SELECT jsonb_build_object('kind','default_acl','role',pg_get_userbyid(d.defaclrole),'schema',n.nspname,'type',d.defaclobjtype,'acl',d.defaclacl)::text FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid=d.defaclnamespace ORDER BY 1;
SELECT jsonb_build_object('kind','role','name',rolname,'superuser',rolsuper,'inherit',rolinherit,'create_role',rolcreaterole,'create_db',rolcreatedb,'login',rolcanlogin,'replication',rolreplication,'bypass_rls',rolbypassrls,'connection_limit',rolconnlimit,'valid_until',rolvaliduntil,'config',rolconfig)::text FROM pg_roles WHERE rolname !~ '^pg_' ORDER BY rolname;
SELECT jsonb_build_object('kind','membership','role',r.rolname,'member',m.rolname,'grantor',g.rolname,'admin',a.admin_option,'inherit',a.inherit_option,'set',a.set_option)::text FROM pg_auth_members a JOIN pg_roles r ON r.oid=a.roleid JOIN pg_roles m ON m.oid=a.member JOIN pg_roles g ON g.oid=a.grantor ORDER BY 1;
SELECT jsonb_build_object('kind','database','owner',pg_get_userbyid(datdba),'encoding',pg_encoding_to_char(encoding),'collate',datcollate,'ctype',datctype,'acl',datacl,'connection_limit',datconnlimit,'allow_connections',datallowconn)::text FROM pg_database WHERE datname=current_database();
SELECT jsonb_build_object('kind','constraint','schema',n.nspname,'table',c.relname,'name',x.conname,'definition',pg_get_constraintdef(x.oid,true))::text FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE """ + EXCLUDED + r""" ORDER BY 1;
SELECT jsonb_build_object('kind','trigger','schema',n.nspname,'table',c.relname,'name',t.tgname,'enabled',t.tgenabled,'definition',pg_get_triggerdef(t.oid,true))::text FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal AND """ + EXCLUDED + r""" ORDER BY 1;
SELECT format('SELECT jsonb_build_object(''kind'',''rows'',''schema'',%L,''table'',%L,''count'',count(*),''hash_a'',coalesce(sum((''x''||substr(h,1,16))::bit(64)::bigint::numeric),0)::text,''hash_b'',coalesce(sum((''x''||substr(h,17,16))::bit(64)::bigint::numeric),0)::text)::text FROM (SELECT md5(to_jsonb(t)::text) AS h FROM ONLY %I.%I t) hashes;', n.nspname,c.relname,n.nspname,c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind IN ('r','m') AND """ + EXCLUDED + r""" ORDER BY n.nspname,c.relname
\gexec
SELECT format('SELECT jsonb_build_object(''kind'',''sequence'',''schema'',%L,''name'',%L,''last_value'',last_value,''is_called'',is_called)::text FROM %I.%I;',n.nspname,c.relname,n.nspname,c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='S' AND """ + EXCLUDED + r""" ORDER BY n.nspname,c.relname
\gexec
SELECT jsonb_build_object('kind','large_objects','count',count(*),'hash',md5(coalesce(string_agg(oid::text||':'||md5(lo_get(oid)),',' ORDER BY oid),'')))::text FROM pg_largeobject_metadata;
COMMIT;
"""


def parse_manifest(text):
    records = [json.loads(line) for line in text.splitlines() if line.strip()]
    # ACL array ordering is insertion order, not grant semantics. Preserve every
    # grantor/grantee/privilege/grant-option entry while comparing stable order.
    for record in records:
        if isinstance(record.get('acl'), list):
            record['acl'].sort()
        if record['kind']=='constraint':
            record['definition'] = canonical_array_casts(record['definition'])
    return sorted(records, key=lambda x: json.dumps(x, sort_keys=True))


def backup(args):
    restrict(ROOT)
    directory = ROOT / ('backup-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    restrict(directory)
    if args.port == 6543:
        raise RuntimeError('Use direct PostgreSQL or session pooler (5432), NOT the transaction pooler.')
    password = getpass.getpass('Existing authorized database password (not the Supabase API token): ')
    if not password:
        raise RuntimeError('An existing authorized database password is required.')
    if any('\n' in str(value) or '\r' in str(value) for value in (args.host,args.user,args.database,password)):
        raise RuntimeError('Connection fields must not contain line breaks.')
    def escape(value):
        return str(value).replace('\\', '\\\\').replace(':', '\\:')
    pgpass = directory / 'source.pgpass'
    pgpass.write_text(':'.join(map(escape, [args.host, args.port, args.database, args.user, password])) + '\n', encoding='utf-8')
    password = None
    holder = None
    errors = []
    evidence = {'status':'INCOMPLETE','source_host':args.host,'source_port':args.port,'database':args.database,'source_user':args.user,'image':args.image,'scope':'Unfiltered PostgreSQL logical dump, all accessible schemas/data/large objects/roles/owners/grants; no role passwords or Storage object bytes.'}
    save_json(directory / 'backup-evidence.json', evidence)
    try:
        holder = subprocess.Popen(docker_source(directory,args.image,args.host,args.port,args.user,args.database,['psql','-X','-qAt','-v','ON_ERROR_STOP=1']), stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
        def collect_errors():
            errors.append(holder.stderr.read())
        thread = threading.Thread(target=collect_errors,daemon=True)
        thread.start()
        holder.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT pg_export_snapshot();\n")
        holder.stdin.flush()
        snapshot = holder.stdout.readline().strip()
        if not snapshot or not all(c in '0123456789ABCDEFabcdef-' for c in snapshot):
            raise RuntimeError('Could not export a source snapshot; inspect snapshot-holder.log.')
        command = ['pg_dump','--format=custom','--create','--file=/backup/database.dump','--snapshot='+snapshot]
        run(docker_source(directory,args.image,args.host,args.port,args.user,args.database,command),directory/'pg-dump.log')
        run(docker_source(directory,args.image,args.host,args.port,args.user,args.database,['pg_dump','--schema-only','--create','--quote-all-identifiers','--file=/backup/source-schema.sql','--snapshot='+snapshot]),directory/'schema-dump.log')
        run(docker_source(directory,args.image,args.host,args.port,args.user,args.database,['pg_dumpall','--globals-only','--quote-all-identifiers','--no-role-passwords','--file=/backup/globals.sql']),directory/'pg-dumpall.log')
        manifest = parse_manifest(sql_source(directory,args.image,args.host,args.port,args.user,args.database,manifest_sql(snapshot)))
        save_json(directory/'source-manifest.json',manifest)
        run(['docker','run','--rm','--mount',f'type=bind,source={directory.resolve()},target=/backup,readonly',args.image,'pg_restore','--list','/backup/database.dump'],directory/'archive-toc.txt')
        evidence.update(status='BACKUP_COMPLETE_RESTORE_UNPROVEN',snapshot=snapshot,files={name:file_hash(directory/name) for name in ['database.dump','globals.sql','source-manifest.json','source-schema.sql']},manifest_records=len(manifest),role_passwords_exported=False)
        save_json(directory/'backup-evidence.json',evidence)
        print('Real logical backup saved outside Git: '+str(directory))
    finally:
        if holder:
            if holder.poll() is None:
                try:
                    holder.stdin.write('ROLLBACK;\n\\q\n'); holder.stdin.flush()
                    holder.wait(timeout=30)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    holder.kill(); holder.wait()
            thread.join(timeout=5)
            (directory/'snapshot-holder.log').write_text(''.join(errors),encoding='utf-8')
        pgpass.unlink(missing_ok=True)


def restore(args):
    directory = Path(args.backup_directory).resolve()
    if not directory.is_relative_to(ROOT.resolve()):
        raise RuntimeError('Restore input must be inside the designated restricted outside-Git root.')
    evidence = json.loads((directory/'backup-evidence.json').read_text(encoding='utf-8'))
    if evidence['status'] != 'BACKUP_COMPLETE_RESTORE_UNPROVEN':
        raise RuntimeError('A completed genuine logical backup is required.')
    for name,digest in evidence['files'].items():
        if file_hash(directory/name)!=digest:
            raise RuntimeError('Backup integrity mismatch: '+name)
    source = json.loads((directory/'source-manifest.json').read_text(encoding='utf-8'))
    # Match initdb's source bootstrap identity, preserving built-in tablespace,
    # extension ownership and predefined-role grantors rather than ignoring them.
    admin = next(x['owner'] for x in source if x['kind']=='tablespace' and x['name']=='pg_default')
    if not any(x['kind']=='role' and x['name']==admin and x['superuser'] for x in source):
        raise RuntimeError('The source bootstrap owner must be a recorded superuser for faithful isolated initialization.')
    output = directory / ('restore-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    restrict(output)
    create_admin = 'CREATE ROLE "' + admin.replace('"','""') + '";'
    globals_lines = (directory/'globals.sql').read_text(encoding='utf-8').splitlines()
    if globals_lines.count(create_admin) != 1:
        raise RuntimeError('Expected exactly one quoted bootstrap CREATE ROLE in genuine globals dump.')
    # initdb already created this one role. Replay every ALTER ROLE and grant,
    # removing only its duplicate creation from this derived restore input.
    (output/'globals-restore.sql').write_text('\n'.join(line for line in globals_lines if line != create_admin)+'\n',encoding='utf-8')
    # No host port published. Refuse to reuse or delete ANY existing container.
    existing = run(['docker','container','ls','--all','--format','{{.Names}}']).splitlines()
    if CONTAINER in existing:
        raise RuntimeError('The isolated G03 container already exists; preserve or remove it explicitly before rerunning.')
    secret_file = output/'local-admin-password'
    secret_file.write_text(secrets.token_urlsafe(48),encoding='utf-8')
    result = {'status':'RESTORE_FAILED','container':CONTAINER,'image':args.image,'backup_directory':str(directory),'published_ports':False}
    save_json(output/'restore-evidence.json',result)
    try:
        run(['docker','run','--detach','--name',CONTAINER,'--mount',f'type=bind,source={directory},target=/backup,readonly','--mount',f'type=bind,source={output},target=/restore,readonly','-e','POSTGRES_USER='+admin,'-e','POSTGRES_PASSWORD_FILE=/restore/local-admin-password',args.image],output/'container-start.log')
        import time
        for _ in range(60):
            # pg_isready also accepts the entrypoint's temporary init server.
            # Wait until PID 1 is the final postgres process, not its shell.
            process = subprocess.run(['docker','exec',CONTAINER,'cat','/proc/1/cmdline'],capture_output=True)
            executable = process.stdout.split(b'\0',1)[0].rsplit(b'/',1)[-1]
            ready = subprocess.run(['docker','exec',CONTAINER,'pg_isready','-U',admin,'-d','postgres'],capture_output=True)
            if executable in (b'postgres',b'.postgres-wrapped') and ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Isolated PostgreSQL did not become ready.')
        def local_sql(sql, database='postgres'):
            return run(['docker','exec','-i',CONTAINER,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U',admin,'-d',database],output/'local-query.log',sql)
        available = {tuple(x) for x in json.loads(local_sql("SELECT coalesce(json_agg(json_build_array(name,version)),'[]'::json) FROM pg_available_extension_versions;"))}
        missing = [{'name':x['name'],'version':x['version']} for x in source if x['kind']=='extension' and (x['name'],x['version']) not in available]
        if missing:
            save_json(output/'missing-extensions.json',missing)
            raise RuntimeError('Use an extension-compatible PostgreSQL image; see missing-extensions.json. Nothing was silently omitted.')
        run(['docker','exec',CONTAINER,'psql','-X','-v','ON_ERROR_STOP=1','-U',admin,'-d','postgres','--file=/restore/globals-restore.sql'],output/'globals-restore.log')
        # --create needs its source DB name to be absent, including the usual postgres.
        source_database = evidence['database']
        if source_database in ('template0','template1'):
            raise RuntimeError('Template databases are not valid application restore targets.')
        if source_database == 'postgres':
            local_sql('DROP DATABASE postgres;',database='template1')
        toc = run(['docker','exec',CONTAINER,'pg_restore','--list','/backup/database.dump']).splitlines()
        deferred = [line for line in toc if ' ACL graphql_public FUNCTION graphql(' in line]
        bootstrap = [line for line in toc if re.match(r'^\d+; \d+ \d+ (?:DATABASE -|SCHEMA -) ',line)]
        (output/'restore-bootstrap.list').write_text('\n'.join(bootstrap)+'\n',encoding='utf-8')
        (output/'restore-main.list').write_text('\n'.join(line for line in toc if line not in deferred and line not in bootstrap)+'\n',encoding='utf-8')
        (output/'restore-wrapper-acl.list').write_text('\n'.join(deferred)+'\n',encoding='utf-8')
        run(['docker','exec',CONTAINER,'pg_restore','--exit-on-error','--create','--use-list=/restore/restore-bootstrap.list','--username='+admin,'--dbname=template1','/backup/database.dump'],output/'database-bootstrap.log')
        # pg_dump does not emit ALTER EXTENSION OWNER. Install extensions under
        # their recorded source owners before replaying CREATE IF NOT EXISTS.
        # Any elevation exists only inside this isolated transaction; source role
        # flags are restored before COMMIT and checked again in the full manifest.
        def identifier(value):
            return '"' + value.replace('"','""') + '"'
        extension_sql = ['BEGIN;']
        roles = {item['name']:item for item in source if item['kind']=='role'}
        for item in source:
            if item['kind']!='extension' or item['name']=='plpgsql':
                continue
            owner = identifier(item['owner'])
            extension_sql.extend(['ALTER ROLE '+owner+' SUPERUSER;',
                'SET LOCAL ROLE '+owner+';',
                'CREATE EXTENSION IF NOT EXISTS '+identifier(item['name'])+' WITH SCHEMA '+identifier(item['schema'])+" VERSION '"+item['version'].replace("'","''")+"';",
                'RESET ROLE;'])
            if not roles[item['owner']]['superuser']:
                extension_sql.append('ALTER ROLE '+owner+' NOSUPERUSER;')
        extension_sql.append('COMMIT;')
        local_sql('\n'.join(extension_sql),database=source_database)
        run(['docker','exec',CONTAINER,'pg_restore','--exit-on-error','--section=pre-data','--use-list=/restore/restore-main.list','--username='+admin,'--dbname='+source_database,'/backup/database.dump'],output/'database-pre-data.log')
        # Supabase attaches this generated wrapper to pg_graphql. pg_dump then
        # omits its definition as an extension member, but vanilla CREATE EXTENSION
        # cannot recreate it without Supabase's later-installed event trigger.
        # Restore the exact backed-up definition/membership BEFORE replaying ACLs.
        wrapper = next((x for x in source if x['kind']=='routine' and
                        x['schema']=='graphql_public' and x['name']=='graphql'), None)
        if wrapper:
            signature = 'graphql_public.graphql(text,text,jsonb,jsonb)'
            if local_sql("SELECT to_regprocedure('" + signature + "') IS NULL;",database=source_database).strip() == 't':
                sql = wrapper['definition'] + ';\nALTER FUNCTION ' + signature + ' OWNER TO "' + wrapper['owner'].replace('"','""') + '";\n'
                sql += 'ALTER EXTENSION pg_graphql ADD FUNCTION ' + signature + ';\n'
                (output/'managed-extension-wrapper.sql').write_text(sql,encoding='utf-8')
                local_sql(sql,database=source_database)
        if deferred:
            run(['docker','exec',CONTAINER,'pg_restore','--exit-on-error','--use-list=/restore/restore-wrapper-acl.list','--username='+admin,'--dbname='+source_database,'/backup/database.dump'],output/'database-wrapper-acl.log')
        run(['docker','exec',CONTAINER,'pg_restore','--exit-on-error','--section=data','--use-list=/restore/restore-main.list','--username='+admin,'--dbname='+source_database,'/backup/database.dump'],output/'database-data.log')
        run(['docker','exec',CONTAINER,'pg_restore','--exit-on-error','--section=post-data','--use-list=/restore/restore-main.list','--username='+admin,'--dbname='+source_database,'/backup/database.dump'],output/'database-post-data.log')
        # Restore explicit ACLs pg_dump can omit as extension init-privileges or
        # default-owner ACLs. Never drop an unexpected grant: the manifest gate
        # below still rejects any surplus privilege or changed grantor.
        acl_targets = []
        for item in source:
            if not item.get('acl'):
                continue
            kind = item['kind']
            if kind=='database':
                target = 'DATABASE '+identifier(source_database)
            elif kind=='schema':
                target = 'SCHEMA '+identifier(item['name'])
            elif kind=='routine':
                target = 'FUNCTION '+identifier(item['schema'])+'.'+identifier(item['name'])+'('+item['args']+')'
            elif kind=='relation' and item['type'] in ('r','p','v','m','f','S'):
                target = ('SEQUENCE ' if item['type']=='S' else 'TABLE ')+identifier(item['schema'])+'.'+identifier(item['name'])
            else:
                continue
            acl_targets.append({'target':target,'acl':item['acl']})
        payload = json.dumps(acl_targets).replace("'","''")
        acl_sql = local_sql("""
SELECT format('SET LOCAL ROLE %I; GRANT %s ON %s TO %s%s; RESET ROLE;',
  pg_get_userbyid(a.grantor),a.privilege_type,t.target,
  CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(a.grantee)) END,
  CASE WHEN a.is_grantable THEN ' WITH GRANT OPTION' ELSE '' END)
FROM jsonb_to_recordset('"""+payload+"""'::jsonb) t(target text,acl text[])
CROSS JOIN LATERAL aclexplode(t.acl::aclitem[]) a;
""",database=source_database)
        (output/'acl-restore.sql').write_text('BEGIN;\n'+acl_sql+'COMMIT;\n',encoding='utf-8')
        local_sql('BEGIN;\n'+acl_sql+'COMMIT;\n',database=source_database)
        # pg_restore only emits database comments with --create, which would
        # recreate the already bootstrapped database. Replay the exact dump SQL.
        database_comments = re.findall(r"""(?m)^COMMENT ON DATABASE "(?:[^"]|"")*" IS (?:'(?:[^']|'')*'|NULL);""",
                                       (directory/'source-schema.sql').read_text(encoding='utf-8'))
        local_sql('\n'.join(database_comments),database=source_database)
        # Extension-owned sequence state is omitted by pg_dump too; preserve the
        # recorded source state after restored DDL event triggers have run.
        sequence_sql = []
        for item in source:
            if item['kind']=='sequence':
                name = '"' + item['schema'].replace('"','""') + '"."' + item['name'].replace('"','""') + '"'
                sequence_sql.append("SELECT pg_catalog.setval('" + name.replace("'","''") + "'," + str(item['last_value']) + ',' + str(item['is_called']).lower() + ');')
        local_sql('\n'.join(sequence_sql),database=source_database)
        restored = parse_manifest(local_sql(manifest_sql(),database=source_database))
        save_json(output/'restored-manifest.json',restored)
        restored_schema = run(['docker','exec',CONTAINER,'pg_dump','--schema-only','--create','--quote-all-identifiers','--username='+admin,'--dbname='+source_database],output/'restored-schema.log')
        (output/'restored-schema.sql').write_text(restored_schema,encoding='utf-8')
        schema_matches = canonical_schema((directory/'source-schema.sql').read_text(encoding='utf-8')) == canonical_schema(restored_schema)
        result['schema_matches'] = schema_matches
        if restored != source:
            source_set={json.dumps(x,sort_keys=True) for x in source}
            restored_set={json.dumps(x,sort_keys=True) for x in restored}
            save_json(output/'manifest-differences.json',{'source_only':[json.loads(x) for x in sorted(source_set-restored_set)],'restored_only':[json.loads(x) for x in sorted(restored_set-source_set)]})
            raise RuntimeError('Restore fidelity mismatch; see manifest-differences.json (includes receipt/data hashes, owners, ACLs and sequences).')
        if not schema_matches:
            raise RuntimeError('Schema fidelity mismatch; compare source-schema.sql with restored-schema.sql. No owner/grant/schema differences are suppressed.')
        result.update(status='RESTORE_FIDELITY_PASSED',manifest_records=len(restored),row_tables=sum(x['kind']=='rows' for x in restored),sequence_count=sum(x['kind']=='sequence' for x in restored),role_passwords_restored=False)
        print('Isolated restore fidelity passed; evidence outside Git: '+str(output))
    except Exception as error:
        result['failure']=str(error)
        raise
    finally:
        save_json(output/'restore-evidence.json',result)
        secret_file.unlink(missing_ok=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
    commands=parser.add_subparsers(dest='command',required=True)
    b=commands.add_parser('backup')
    b.add_argument('--host',required=True); b.add_argument('--port',type=int,default=5432)
    b.add_argument('--user',required=True); b.add_argument('--database',default='postgres'); b.add_argument('--image',default='postgres:17')
    r=commands.add_parser('restore')
    r.add_argument('--backup-directory',required=True); r.add_argument('--image',default='postgres:17')
    args=parser.parse_args()
    try:
        (backup if args.command=='backup' else restore)(args)
    except Exception as error:
        print('G03 NOT PASSED: '+str(error),file=sys.stderr)
        return 1
    return 0


if __name__=='__main__':
    sys.exit(main())
