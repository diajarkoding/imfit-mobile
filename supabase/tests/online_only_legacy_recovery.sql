-- Synthetic final-contract behavior and security fixture. No user data survives.
begin;
set local statement_timeout='30s';
set local timezone='UTC';
create function pg_temp.expect_error(q text, expected_state text, expected_message text) returns void
language plpgsql as $$
begin
  execute q;
  raise exception 'Expected failure: %', expected_message;
exception when others then
  if sqlstate <> expected_state or sqlerrm <> expected_message then raise; end if;
end $$;
create function pg_temp.assert_true(ok boolean, message text) returns void language plpgsql as $$
begin if ok is distinct from true then raise exception 'FAIL %',message; end if; end $$;
-- Exact public RPC signatures, not name-prefix or overload-tolerant checks.
do $$
declare signature text; f regprocedure; signatures text[]:=array[
 'save_template_revision(uuid,uuid,bigint,text,jsonb,jsonb,boolean,uuid)',
 'get_workout_history_page(date,date,text,text,integer)',
 'get_exercise_catalog_page(text,integer,text,integer)', 'get_template_page(text,integer)',
 'get_workout_detail(uuid)','get_template_detail(uuid)','get_progress_summary(date,date,text)',
 'get_workout_date_markers(date,date,text)','get_exercise_prefill(text,jsonb)',
 'create_active_session(uuid,uuid,uuid,bigint)','get_active_session(uuid)',
 'update_active_session(uuid,uuid,bigint,jsonb)','cancel_active_session(uuid,uuid,bigint)',
 'finish_active_session(uuid,uuid,bigint)','get_operation_status(uuid)',
 'list_recent_operations(timestamp with time zone,timestamp with time zone,text,integer)',
 'get_home_summary()','get_start_recovery_status(integer)','get_legacy_recovery_page(text,integer)',
 'resolve_legacy_session(uuid,uuid,text,text)','import_legacy_workout(uuid,jsonb,jsonb,jsonb,jsonb)'];
begin
 foreach signature in array signatures loop
  f:=to_regprocedure('public.'||signature);
  perform pg_temp.assert_true(f is not null,'missing signature '||signature);
  perform pg_temp.assert_true(has_function_privilege('authenticated',f,'EXECUTE') and
    not has_function_privilege('anon',f,'EXECUTE'),'RPC role grants '||signature);
  perform pg_temp.assert_true((select p.prosecdef and 'search_path=""'=any(p.proconfig)
    from pg_proc p where p.oid=f),'definer empty search_path '||signature);
  perform pg_temp.assert_true((select count(*)=1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and p.proname=split_part(signature,'(',1)),'unexpected overload '||signature);
 end loop;
 perform pg_temp.assert_true((select count(*)=2 and bool_and(c.relrowsecurity) from pg_class c
   join pg_namespace n on n.oid=c.relnamespace where n.nspname='imfit_private'
   and c.relname in ('legacy_session_reviews','legacy_workout_imports')),'private RLS');
 perform pg_temp.assert_true(not has_schema_privilege('authenticated','imfit_private','USAGE'),'private schema denied');
end $$;
insert into auth.users(id,email) values
 ('a7000000-0000-0000-0000-000000000001','recovery-a@example.invalid'),
 ('b7000000-0000-0000-0000-000000000001','recovery-b@example.invalid');
insert into public.workout_templates(id,user_id,name) values
 ('27000000-0000-0000-0000-000000000001','a7000000-0000-0000-0000-000000000001','Recovery fixture');
insert into public.template_exercises(template_id,exercise_id,order_index,sets,reps,rest_seconds) values
 ('27000000-0000-0000-0000-000000000001','ex_chest_1',0,1,10,60);
-- Historical receipts are irrelevant even when a one-page discovery would overflow.
insert into imfit_private.operation_receipts(user_id,request_id,operation,resource_type,resource_id,payload,result)
select 'a7000000-0000-0000-0000-000000000001',md5('history-request-'||n)::uuid,
 'cancel_active_session','SESSION',md5('history-resource-'||n)::uuid,'{}','{}' from generate_series(1,101) n;
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
select pg_temp.assert_true((public.get_start_recovery_status()->>'can_create')::boolean,'101 receipts do not block Start');
reset role;
insert into imfit_private.operation_receipts(user_id,request_id,operation,resource_type,resource_id,payload,result)
select 'a7000000-0000-0000-0000-000000000001',md5('history-request-'||n)::uuid,
 'cancel_active_session','SESSION',md5('history-resource-'||n)::uuid,'{}','{}' from generate_series(102,1000) n;
set local role authenticated;
select pg_temp.assert_true((public.get_start_recovery_status()->>'can_create')::boolean,'1000 receipts do not block Start');
select pg_temp.expect_error('select public.get_start_recovery_status(2)','PT400','VALIDATION');
select pg_temp.expect_error('select public.get_legacy_recovery_page(null,101)','PT400','VALIDATION');
reset role;
-- More than twenty blockers, the last one cannot disappear behind the preview.
insert into public.active_sessions(id,user_id,template_name,start_time,session_data,last_activity_at,expires_at,status,created_at,updated_at)
select ('47000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,'a7000000-0000-0000-0000-000000000001',
 'Legacy', '2026-09-01T00:00Z','{"legacy":true,"exact":1.005}', '2026-09-01T00:00Z','2026-10-01T00:00Z',
 'EXPIRED','2026-09-01T00:00Z','2026-09-02T00:00Z' from generate_series(1,21) n;
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare status jsonb; page jsonb; cursor text; item jsonb; receipt jsonb; old_receipt jsonb; req uuid; sid uuid; fingerprint text;
begin
 status:=public.get_start_recovery_status();
 perform pg_temp.assert_true(not (status->>'can_create')::boolean and (status->>'has_more_blockers')::boolean
   and jsonb_array_length(status->'blockers')=20,'bounded authoritative blockers');
 page:=public.get_legacy_recovery_page(null,20); cursor:=page#>>'{page_info,next_cursor}';
 perform pg_temp.assert_true((page#>>'{page_info,has_more}')::boolean and cursor is not null,'legacy page cursor');
 perform pg_temp.assert_true(jsonb_array_length(public.get_legacy_recovery_page(cursor,20)->'data')=1,'keyset final item');
 perform pg_temp.expect_error(format('select public.get_legacy_recovery_page(%L,19)',cursor),'PT400','INVALID_CURSOR');
 for item in select value from jsonb_array_elements(public.get_legacy_recovery_page(null,100)->'data') loop
   req:=gen_random_uuid();sid:=(item->>'session_id')::uuid;fingerprint:=item->>'source_fingerprint';
   perform pg_temp.expect_error(format('select public.resolve_legacy_session(%L,%L,%L,%L)',gen_random_uuid(),sid,repeat('0',64),'KEEP_ARCHIVED'),'PT409','LEGACY_SOURCE_CHANGED');
   receipt:=public.resolve_legacy_session(req,sid,fingerprint,'KEEP_ARCHIVED');
   old_receipt:=public.resolve_legacy_session(req,sid,fingerprint,'KEEP_ARCHIVED');
   perform pg_temp.assert_true(receipt=old_receipt,'exact archive replay');
   perform pg_temp.expect_error(format('select public.resolve_legacy_session(%L,%L,%L,%L)',req,sid,fingerprint,'DELETE'),'PT409','IDEMPOTENCY_MISMATCH');
   if sid='47000000-0000-0000-0000-000000000020'::uuid then
     perform pg_temp.assert_true(not (public.get_start_recovery_status()->>'can_create')::boolean
       and jsonb_array_length(public.get_start_recovery_status()->'blockers')=1,'last originally hidden blocker still blocks');
   end if;
 end loop;
 perform pg_temp.assert_true((public.get_start_recovery_status()->>'can_create')::boolean,'archive reviews unblock');
 perform pg_temp.assert_true(not (public.get_active_session()->>'found')::boolean,'current lookup ignores archived null-version rows');
 for item in select value from jsonb_array_elements(public.get_legacy_recovery_page(null,100)->'data') loop
   perform pg_temp.assert_true(item->>'resolution'='KEEP_ARCHIVED' and item#>>'{raw_source,updated_at}'='2026-09-02T00:00:00+00:00'
    and item#>'{raw_source,version}'='null'::jsonb,'original raw timestamp and null version preserved');
 end loop;
 -- Current owner-bound cursor must not become a foreign-owner read token.
 perform set_config('request.jwt.claim.sub','b7000000-0000-0000-0000-000000000001',true);
 perform pg_temp.expect_error(format('select public.get_legacy_recovery_page(%L,20)',cursor),'PT400','INVALID_CURSOR');
 perform pg_temp.expect_error(format('select public.resolve_legacy_session(%L,%L,%L,%L)',gen_random_uuid(),sid,fingerprint,'KEEP_ARCHIVED'),'PT404','RESOURCE_NOT_FOUND');
end $$;
reset role;
select pg_temp.assert_true((select bool_and(s.version is null and s.schema_version is null and s.status='EXPIRED' and not s.review_required
 and r.source_fingerprint=imfit_private.legacy_session_fingerprint(s)) from public.active_sessions s
 join imfit_private.legacy_session_reviews r on r.session_id=s.id where s.user_id='a7000000-0000-0000-0000-000000000001'),'archive hash stable after timestamp trigger');
-- Source changed after review must block again (review fingerprint, not just a status bit).
update public.active_sessions set template_name='Changed after archive' where id='47000000-0000-0000-0000-000000000021';
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
select pg_temp.assert_true(not (public.get_start_recovery_status()->>'can_create')::boolean,'changed archive reviewed again');
reset role;
do $$
declare r imfit_private.operation_receipts%rowtype;
begin
 select * into strict r from imfit_private.operation_receipts where user_id='a7000000-0000-0000-0000-000000000001'
   and operation='resolve_legacy_session' and resource_id='47000000-0000-0000-0000-000000000021';
 perform pg_temp.assert_true(public.resolve_legacy_session(r.request_id,r.resource_id,r.payload->>'source_fingerprint','KEEP_ARCHIVED')=r.result,
   'old receipt replays before changed-source CAS');
 perform pg_temp.assert_true(not (public.get_start_recovery_status()->>'can_create')::boolean,'old replay does not approve changed source');
end $$;
-- Exact decimal + uncompleted set + null provenance aggregate, all identities original.
create temporary table recovery_payload(source jsonb, workout jsonb, exercises jsonb, sets jsonb, request uuid, receipt jsonb);
insert into recovery_payload values(
 '{"schema_version":6,"kind":"WORKOUT","id":"37000000-0000-0000-0000-000000000001","fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","mapping_version":1}',
 '{"id":"37000000-0000-0000-0000-000000000001","user_id":"a7000000-0000-0000-0000-000000000001","template_id":"27000000-0000-0000-0000-000000000001","template_name":"Legacy routine","date":"2026-09-01","start_time":"2026-09-01T00:00:00Z","end_time":"2026-09-01T00:10:00Z","total_volume":"3.015","total_sets":1,"total_reps":3,"deleted_at":null,"created_at":"2026-09-01T00:10:00Z","updated_at":"2026-09-01T00:11:00Z"}',
 '[{"id":"57000000-0000-0000-0000-000000000001","workout_log_id":"37000000-0000-0000-0000-000000000001","exercise_id":"ex_chest_1","exercise_name":"Legacy chest","muscle_category":"CHEST","order_index":0,"total_volume":"3.015","total_sets":1,"total_reps":3,"input_defaults":null,"created_at":"2026-09-01T00:10:00Z","updated_at":"2026-09-01T00:11:00Z"}]',
 '[{"id":"67000000-0000-0000-0000-000000000001","exercise_log_id":"57000000-0000-0000-0000-000000000001","workout_log_id":"37000000-0000-0000-0000-000000000001","exercise_id":"ex_chest_1","set_number":1,"weight":"1.005","reps":3,"is_completed":true,"created_at":"2026-09-01T00:10:00Z","updated_at":"2026-09-01T00:11:00Z"},{"id":"67000000-0000-0000-0000-000000000002","exercise_log_id":"57000000-0000-0000-0000-000000000001","workout_log_id":"37000000-0000-0000-0000-000000000001","exercise_id":"ex_chest_1","set_number":2,"weight":"1.000000000000001","reps":0,"is_completed":false,"created_at":"2026-09-01T00:10:00Z","updated_at":"2026-09-01T00:11:00Z"}]',gen_random_uuid(),null);
grant select,update on recovery_payload to authenticated;
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare p recovery_payload%rowtype; r jsonb; replay jsonb; conflict_request uuid:=gen_random_uuid();
begin
 select * into strict p from recovery_payload;
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',gen_random_uuid(),p.source,p.workout||'{"total_reps":4}',p.exercises,p.sets),'PT409','LEGACY_REVIEW_REQUIRED');
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',gen_random_uuid(),p.source,p.workout||'{"notes":"not allowed"}',p.exercises,p.sets),'PT400','VALIDATION');
 r:=public.import_legacy_workout(p.request,p.source,p.workout,p.exercises,p.sets);
 perform pg_temp.assert_true(r->>'outcome'='IMPORTED' and r->>'workout_id'=p.workout->>'id'
  and (r->>'exercise_count')::integer=1 and (r->>'set_count')::integer=2,'import aggregate receipt');
 replay:=public.import_legacy_workout(p.request,p.source,p.workout,p.exercises,p.sets);
 perform pg_temp.assert_true(replay=r,'original import replay exact');
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',p.request,p.source,p.workout||'{"template_name":"changed"}',p.exercises,p.sets),'PT409','IDEMPOTENCY_MISMATCH');
 replay:=public.import_legacy_workout(gen_random_uuid(),p.source,p.workout,p.exercises,p.sets);
 perform pg_temp.assert_true(replay->>'outcome'='MATCHED' and replay->'id_mapping'=r->'id_mapping'
   and replay->'payload_digest'=r->'payload_digest','new request same source mapping/digest');
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',conflict_request,p.source||jsonb_build_object('fingerprint',repeat('b',64)),p.workout,p.exercises,p.sets),'PT409','LEGACY_IMPORT_CONFLICT');
 update recovery_payload set receipt=r;
 perform set_config('request.jwt.claim.sub','b7000000-0000-0000-0000-000000000001',true);
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',gen_random_uuid(),p.source,p.workout,p.exercises,p.sets),'PT404','RESOURCE_NOT_FOUND');
end $$;
reset role;
select pg_temp.assert_true((select count(*)=1 and bool_and(source_session_id is null and notes is null and rating is null
 and total_volume=3.015 and updated_at='2026-09-01T00:11:00Z') from public.workout_logs where id='37000000-0000-0000-0000-000000000001'),'parent exact/default metadata');
select pg_temp.assert_true((select count(*)=2 and sum(case when is_completed then weight*reps else 0 end)=3.015
 and max(weight)=1.005 and min(weight)=1.000000000000001 from public.workout_sets where workout_log_id='37000000-0000-0000-0000-000000000001'),'exact precision and uncompleted preserved');
select pg_temp.assert_true((select count(*)=1 and bool_and(input_defaults is null) from public.exercise_logs where workout_log_id='37000000-0000-0000-0000-000000000001'),'no inferred provenance');
-- A partial child collision cannot leave its otherwise-new parent/receipt/registry.
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare p recovery_payload%rowtype; wid text:='37000000-0000-0000-0000-000000000099'; req uuid:=gen_random_uuid();
begin
 select * into strict p from recovery_payload;
 perform pg_temp.expect_error(format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',req,
  p.source||jsonb_build_object('id',wid),p.workout||jsonb_build_object('id',wid),
  replace(p.exercises::text,'37000000-0000-0000-0000-000000000001',wid)::jsonb,
  replace(p.sets::text,'37000000-0000-0000-0000-000000000001',wid)::jsonb),'PT409','LEGACY_IMPORT_CONFLICT');
 perform pg_temp.assert_true(public.get_operation_status(req)->>'state'='NOT_FOUND','failed collision no receipt');
end $$;
reset role;
select pg_temp.assert_true(not exists(select 1 from public.workout_logs where id='37000000-0000-0000-0000-000000000099')
 and not exists(select 1 from imfit_private.legacy_workout_imports where source_id='37000000-0000-0000-0000-000000000099'),'collision aggregate rollback');
-- Clone only synthetic fixture payloads; production import never replaces IDs.
create function pg_temp.recovery_case(schema_version integer,tag text) returns jsonb language plpgsql as $$
declare p jsonb; original_id text;
begin
 select jsonb_build_object('source',source,'workout',workout,'exercises',exercises,'sets',sets) into strict p from recovery_payload;
 for original_id in select distinct id from (
   select p#>>'{workout,id}' as id union all select x->>'id' from jsonb_array_elements(p->'exercises') x
   union all select x->>'id' from jsonb_array_elements(p->'sets') x) ids loop
   p:=replace(p::text,original_id,(md5(tag||original_id)::uuid)::text)::jsonb;
 end loop;
 p:=jsonb_set(p,'{source,schema_version}',to_jsonb(schema_version));
 if schema_version in (7,9) then p:=jsonb_set(p,'{exercises,0,input_defaults}','{"sets":3,"reps":10}'); end if;
 return p;
end $$;
create function pg_temp.import_q(request uuid,p jsonb) returns text language sql as $$
 select format('select public.import_legacy_workout(%L,%L,%L,%L,%L)',request,p->'source',p->'workout',p->'exercises',p->'sets');
$$;
create function pg_temp.import_case(request uuid,p jsonb) returns jsonb language sql as $$
 select public.import_legacy_workout(request,p->'source',p->'workout',p->'exercises',p->'sets');
$$;
insert into public.workout_templates(id,user_id,name) values
 ('b7000000-0000-0000-0000-000000000002','b7000000-0000-0000-0000-000000000001','Foreign recovery dependency');
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare p jsonb; changed jsonb; extra jsonb; request uuid; receipt jsonb; matched jsonb; version integer;
begin
 -- Existing aggregate with no registry for this schema is exact MATCHED.
 select jsonb_build_object('source',source||'{"schema_version":7}','workout',workout,'exercises',exercises,'sets',sets)
 into p from recovery_payload;
 receipt:=pg_temp.import_case(gen_random_uuid(),p);
 perform pg_temp.assert_true(receipt->>'outcome'='MATCHED','registry miss matches complete existing aggregate');
 foreach version in array array[7,9] loop
   p:=pg_temp.recovery_case(version,'schema-'||version); request:=gen_random_uuid();
   extra:=(p#>'{exercises,0}')||jsonb_build_object('id',md5('extra-'||version)::uuid,'exercise_id','ex_chest_2',
     'order_index',7,'total_volume','0','total_sets',0,'total_reps',0,'input_defaults',null);
   p:=jsonb_set(p,'{exercises}',(p->'exercises')||jsonb_build_array(extra));
   receipt:=pg_temp.import_case(request,p);
   perform pg_temp.assert_true(receipt->>'outcome'='IMPORTED' and receipt->'id_mapping'->>(p#>>'{workout,id}')=p#>>'{workout,id}'
     and (receipt->>'exercise_count')::integer=2 and (receipt->>'set_count')::integer=2,'supported schema import and original mapping');
   perform pg_temp.assert_true(pg_temp.import_case(request,p)=receipt,'supported schema exact replay');
   changed:=jsonb_set(p,'{workout,id}','"not-a-uuid"');
   perform pg_temp.expect_error(pg_temp.import_q(request,changed),'PT409','IDEMPOTENCY_MISMATCH');
   changed:=p-'workout';
   perform pg_temp.expect_error(pg_temp.import_q(request,changed),'PT409','IDEMPOTENCY_MISMATCH');
   -- Equivalent UUID casing, timestamp zone/precision, numeric scale, child order.
   changed:=jsonb_set(p,'{workout,id}',to_jsonb(upper(p#>>'{workout,id}')));
   changed:=jsonb_set(changed,'{workout,start_time}','"2026-09-01T07:00:00.000000+07:00"');
   changed:=jsonb_set(changed,'{sets,0,weight}','1.005000');
   changed:=jsonb_set(changed,'{sets}',jsonb_build_array(changed#>'{sets,1}',changed#>'{sets,0}'));
   changed:=jsonb_set(changed,'{exercises}',jsonb_build_array(changed#>'{exercises,1}',changed#>'{exercises,0}'));
   matched:=pg_temp.import_case(gen_random_uuid(),changed);
   perform pg_temp.assert_true(matched->>'outcome'='MATCHED' and matched->'id_mapping'=receipt->'id_mapping'
     and matched->'payload_digest'=receipt->'payload_digest','canonical exact match without rounding');
 end loop;
 p:=pg_temp.recovery_case(9,'invalid');
 extra:=(p#>'{exercises,0}')||jsonb_build_object('id',md5('invalid-extra')::uuid,'order_index',7);
 changed:=jsonb_set(p,'{exercises}',(p->'exercises')||jsonb_build_array(extra));
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 extra:=extra||jsonb_build_object('exercise_id','ex_chest_2','order_index',0);
 changed:=jsonb_set(p,'{exercises}',(p->'exercises')||jsonb_build_array(extra));
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{workout,template_id}','"b7000000-0000-0000-0000-000000000002"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT409','LEGACY_REVIEW_REQUIRED');
 changed:=jsonb_set(p,'{workout,template_id}',to_jsonb(gen_random_uuid()));
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT409','LEGACY_REVIEW_REQUIRED');
 changed:=jsonb_set(p,'{source,schema_version}','8');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{source,schema_version}','4');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{source,mapping_version}','2');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{workout,id}','"not-a-uuid"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{workout,start_time}','"now"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{workout,end_time}',p#>'{workout,start_time}');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,reps}','1001');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,weight}','"1000.0001"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,weight}','"NaN"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,is_completed}','"true"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,set_number}','0');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,1,set_number}','1');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,1,id}',p#>'{sets,0,id}');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,exercise_id}','"ex_chest_2"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,workout_log_id}',to_jsonb(gen_random_uuid()));
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{exercises,0,order_index}','-1');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{exercises,0,sync_status}','"PENDING"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{sets,0,is_warmup}','true');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{exercises,0,input_defaults}','{"sets":3,"reps":10,"source":"guessed"}');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{source,schema_version}','6');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT400','VALIDATION');
 changed:=jsonb_set(p,'{exercises,0,total_volume}','"3.02"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),changed),'PT409','LEGACY_REVIEW_REQUIRED');
 -- Foreign UUID collisions are owner hidden even with otherwise owned input.
 perform set_config('request.jwt.claim.sub','b7000000-0000-0000-0000-000000000001',true);
 p:=pg_temp.recovery_case(9,'foreign-set');
 p:=jsonb_set(p,'{workout,user_id}','"b7000000-0000-0000-0000-000000000001"');
 p:=jsonb_set(p,'{workout,template_id}','null');
 p:=jsonb_set(p,'{sets,0,id}','"67000000-0000-0000-0000-000000000001"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),p),'PT404','RESOURCE_NOT_FOUND');
 p:=pg_temp.recovery_case(9,'foreign-exercise');
 p:=jsonb_set(p,'{workout,user_id}','"b7000000-0000-0000-0000-000000000001"');
 p:=jsonb_set(p,'{workout,template_id}','null');
 p:=jsonb_set(p,'{exercises,0,id}','"57000000-0000-0000-0000-000000000001"');
 p:=jsonb_set(p,'{sets,0,exercise_log_id}','"57000000-0000-0000-0000-000000000001"');
 p:=jsonb_set(p,'{sets,1,exercise_log_id}','"57000000-0000-0000-0000-000000000001"');
 perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),p),'PT404','RESOURCE_NOT_FOUND');
end $$;
reset role;
select pg_temp.assert_true((select count(*)=3 from public.workout_logs where user_id='a7000000-0000-0000-0000-000000000001'),
 'invalid requests leave no parents');
select pg_temp.assert_true((select count(*)=4 from imfit_private.legacy_workout_imports where user_id='a7000000-0000-0000-0000-000000000001'),
 'invalid requests leave no registry entries');
-- Actual late database failure, after parent/exercise INSERT, rolls all writes back.
alter table public.workout_sets add constraint recovery_late_failure check(weight<>777) not valid;
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare p jsonb; request uuid:=gen_random_uuid();
begin
 p:=pg_temp.recovery_case(9,'late-failure');
 p:=jsonb_set(p,'{sets,1,weight}','777'); -- uncompleted; declared totals remain valid
 perform pg_temp.expect_error(pg_temp.import_q(request,p),'PT409','LEGACY_IMPORT_CONFLICT');
 perform pg_temp.assert_true(public.get_operation_status(request)->>'state'='NOT_FOUND','late failure no receipt');
end $$;
reset role;
select pg_temp.assert_true(not exists(select 1 from public.workout_logs where id=md5('late-failure37000000-0000-0000-0000-000000000001')::uuid)
 and not exists(select 1 from public.exercise_logs where workout_log_id=md5('late-failure37000000-0000-0000-0000-000000000001')::uuid)
 and not exists(select 1 from public.workout_sets where workout_log_id=md5('late-failure37000000-0000-0000-0000-000000000001')::uuid)
 and not exists(select 1 from imfit_private.legacy_workout_imports where source_id=md5('late-failure37000000-0000-0000-0000-000000000001')::uuid),
 'late constraint rolls back aggregate and registry');
-- Each subtransaction restores the target, including timestamp-trigger effects.
-- Existing flags/provenance/timestamps/extra children are not silently rewritten.
do $$
declare mutation text; p jsonb;
begin
 select jsonb_build_object('source',source,'workout',workout,'exercises',exercises,'sets',sets) into p from recovery_payload;
 foreach mutation in array array[
   'update public.workout_logs set notes=''preserved'' where id=''37000000-0000-0000-0000-000000000001''',
   'update public.workout_logs set rating=5 where id=''37000000-0000-0000-0000-000000000001''',
   'update public.workout_logs set created_at=created_at+interval ''1 millisecond'' where id=''37000000-0000-0000-0000-000000000001''',
   'update public.exercise_logs set input_defaults=''{"sets":1,"reps":1}'' where id=''57000000-0000-0000-0000-000000000001''',
   'update public.workout_sets set is_warmup=true where id=''67000000-0000-0000-0000-000000000001''',
   'update public.workout_sets set notes=''preserved'' where id=''67000000-0000-0000-0000-000000000001''',
   'update public.workout_sets set is_completed=true where id=''67000000-0000-0000-0000-000000000002''',
   'insert into public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number) values (''67000000-0000-0000-0000-000000000003'',''57000000-0000-0000-0000-000000000001'',''37000000-0000-0000-0000-000000000001'',''ex_chest_1'',3)'
 ] loop
   begin
     execute mutation;
     perform pg_temp.expect_error(pg_temp.import_q(gen_random_uuid(),p),'PT409','LEGACY_IMPORT_CONFLICT');
     raise exception using errcode='ZZ001',message='restore fixture subtransaction';
   exception when sqlstate 'ZZ001' then null;
   end;
 end loop;
end $$;
-- Typed lifecycle against final lockdown; no authenticated direct table reads.
set local role authenticated;
select set_config('request.jwt.claim.sub','a7000000-0000-0000-0000-000000000001',true);
do $$
declare item jsonb; receipt jsonb; request uuid; revision bigint; session_id uuid:=md5('recovery-lifecycle')::uuid; eid uuid; sid uuid;
begin
 -- Explicitly review the source changed above; Start must never hide it.
 select value into item from jsonb_array_elements(public.get_legacy_recovery_page(null,100)->'data') x(value)
   where value->'resolution'='null'::jsonb;
 perform public.resolve_legacy_session(gen_random_uuid(),(item->>'session_id')::uuid,item->>'source_fingerprint','KEEP_ARCHIVED');
 receipt:=public.save_template_revision(gen_random_uuid(),md5('recovery-lifecycle-template')::uuid,null,'Recovery typed template',
   '[{"exercise_id":"ex_chest_1","order_index":0,"sets":1,"reps":3,"rest_seconds":60}]','[]',false,'a7000000-0000-0000-0000-000000000001');
 revision:=(receipt->>'revision')::bigint;
 receipt:=public.create_active_session(gen_random_uuid(),session_id,md5('recovery-lifecycle-template')::uuid,revision);
 perform pg_temp.assert_true(public.get_start_recovery_status()#>>'{blockers,0,kind}'='ACTIVE_SESSION','typed active blocker');
 perform pg_temp.assert_true(public.get_active_session(session_id)#>>'{session,version}'='1','targeted loaded snapshot');
 eid:=(receipt#>>'{snapshot,exercises,0,id}')::uuid;sid:=(receipt#>>'{snapshot,exercises,0,sets,0,id}')::uuid;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,1,jsonb_build_object('type','edit_set','exercise_log_id',eid,'set_id',sid,'weight','12.34','reps',3));
 perform pg_temp.expect_error(format('select public.update_active_session(%L,%L,1,''{"type":"pause"}'')',gen_random_uuid(),session_id),'PT409','VERSION_CONFLICT');
 receipt:=public.update_active_session(gen_random_uuid(),session_id,2,jsonb_build_object('type','complete_set','exercise_log_id',eid,'set_id',sid));
 receipt:=public.update_active_session(gen_random_uuid(),session_id,3,'{"type":"pause"}');
 receipt:=public.update_active_session(gen_random_uuid(),session_id,4,'{"type":"resume"}');
 request:=gen_random_uuid();receipt:=public.finish_active_session(request,session_id,5);
 perform pg_temp.assert_true(public.finish_active_session(request,session_id,5)=receipt,'finish exact replay');
 perform pg_temp.assert_true(public.get_workout_detail((receipt->>'workout_id')::uuid)->>'total_volume'='37.02','finish confirmed aggregate');
 receipt:=public.create_active_session(gen_random_uuid(),md5('recovery-cancel')::uuid,md5('recovery-lifecycle-template')::uuid,revision);
 receipt:=public.cancel_active_session(gen_random_uuid(),md5('recovery-cancel')::uuid,1);
 perform pg_temp.assert_true(receipt->>'status'='CANCELLED','cancel terminal receipt');
 receipt:=public.create_active_session(gen_random_uuid(),md5('recovery-expiry')::uuid,md5('recovery-lifecycle-template')::uuid,revision);
end $$;
reset role;
update public.active_sessions set expires_at=statement_timestamp()-interval '1 second' where id=md5('recovery-expiry')::uuid;
set local role authenticated;
select pg_temp.assert_true(public.get_start_recovery_status()#>>'{blockers,0,kind}'='EXPIRED_SESSION','effective expiry blocks');
select pg_temp.expect_error(format('select public.resolve_legacy_session(%L,%L,%L,''KEEP_ARCHIVED'')',gen_random_uuid(),md5('recovery-expiry')::uuid,repeat('a',64)),
 'PT409','SESSION_REVIEW_REQUIRED');
select public.update_active_session(gen_random_uuid(),md5('recovery-expiry')::uuid,1,'{"type":"acknowledge_expiry"}');
select pg_temp.assert_true((public.get_start_recovery_status()->>'can_create')::boolean,'versioned expiry receipt unblocks');
select pg_temp.expect_error('select count(*) from imfit_private.legacy_session_reviews','42501','permission denied for schema imfit_private');
select pg_temp.expect_error('select count(*) from imfit_private.legacy_workout_imports','42501','permission denied for schema imfit_private');
reset role;
set local role anon;
select pg_temp.expect_error('select public.get_start_recovery_status()','42501','permission denied for function get_start_recovery_status');
select pg_temp.expect_error('select public.get_legacy_recovery_page()','42501','permission denied for function get_legacy_recovery_page');
select pg_temp.expect_error(format('select public.resolve_legacy_session(%L,%L,%L,''KEEP_ARCHIVED'')',gen_random_uuid(),gen_random_uuid(),repeat('a',64)),
 '42501','permission denied for function resolve_legacy_session');
select pg_temp.expect_error('select public.import_legacy_workout(null,null,null,null,null)','42501','permission denied for function import_legacy_workout');
reset role;
set constraints all immediate;
rollback;
