-- Isolated LOCAL database only. Synthetic fixtures and all mutations roll back.
begin;
set local statement_timeout = '30s';
insert into auth.users(id,email) values
 ('a0000000-0000-0000-0000-000000000001','fixture-a@example.invalid'),
 ('b0000000-0000-0000-0000-000000000001','fixture-b@example.invalid');
insert into public.muscle_categories(id,name,display_name) overriding system value values(999991,'fixture-test','Fixture');
insert into public.exercises(id,muscle_category_id,name,description) values
 ('fixture-squat',999991,'Fixture squat','Test'),('fixture-press',999991,'Fixture press','Test');
set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);

do $$
declare
 owner_id uuid:='a0000000-0000-0000-0000-000000000001';
 template_id uuid:='20000000-0000-0000-0000-000000000001';
 session_id uuid:='40000000-0000-0000-0000-000000000001';
 create_id uuid:='10000000-0000-0000-0000-000000000010';
 edit_id uuid:='10000000-0000-0000-0000-000000000012';
 receipt jsonb; initial jsonb; edited jsonb; finished jsonb; replayed jsonb;
 exercise_log_id uuid; set_id uuid; revision bigint; workout_id uuid; message text;
begin
 receipt:=public.save_template_revision(gen_random_uuid(),template_id,null,'Fixture routine',
  '[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]','[]',false,owner_id);
 revision:=(receipt->>'revision')::bigint;
 initial:=public.create_active_session(create_id,session_id,template_id,revision);
 if initial->>'version'<>'1' or initial->>'status'<>'ACTIVE'
    or jsonb_array_length(initial#>'{snapshot,exercises,0,sets}')<>3 then raise exception 'FAIL create snapshot'; end if;
 exercise_log_id:=(initial#>>'{snapshot,exercises,0,id}')::uuid;
 set_id:=(initial#>>'{snapshot,exercises,0,sets,0,id}')::uuid;
 if public.create_active_session(create_id,session_id,template_id,revision)<>initial then raise exception 'FAIL create replay'; end if;
 begin
  perform public.create_active_session(gen_random_uuid(),gen_random_uuid(),template_id,revision);
  raise exception 'FAIL duplicate active owner';
 exception when sqlstate 'PT409' then
  get stacked diagnostics message=message_text;
  if message<>'SESSION_REVIEW_REQUIRED' then raise exception 'FAIL create blocker'; end if;
 end;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,1,jsonb_build_object(
   'type','edit_set','exercise_log_id',exercise_log_id,'set_id',set_id,'weight',20,'reps',10));
  raise exception 'FAIL numeric weight accepted';
 exception when sqlstate 'PT400' then null; end;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,1,jsonb_build_object(
   'type','edit_set','exercise_log_id',exercise_log_id,'set_id',set_id,'weight','20.001','reps',10));
  raise exception 'FAIL excess precision accepted';
 exception when sqlstate 'PT400' then null; end;
 edited:=public.update_active_session(edit_id,session_id,1,jsonb_build_object(
  'type','edit_set','exercise_log_id',exercise_log_id,'set_id',set_id,'weight','20.5','reps',10));
 if edited->>'version'<>'2' or edited#>>'{snapshot,exercises,0,sets,0,weight}'<>'20.50' then
  raise exception 'FAIL saved weight/version'; end if;
 replayed:=public.update_active_session(edit_id,session_id,1,jsonb_build_object(
  'type','edit_set','exercise_log_id',exercise_log_id,'set_id',set_id,'weight','20.50','reps',10));
 if replayed<>edited then raise exception 'FAIL canonical decimal replay'; end if;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,1,'{"type":"pause"}');
  raise exception 'FAIL stale version accepted';
 exception when sqlstate 'PT409' then
  get stacked diagnostics message=message_text;
  if message<>'VERSION_CONFLICT' then raise exception 'FAIL CAS discriminator'; end if;
 end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,2,jsonb_build_object(
  'type','complete_set','exercise_log_id',exercise_log_id,'set_id',set_id));
 if receipt->>'version'<>'3' then raise exception 'FAIL complete version'; end if;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,3,jsonb_build_object(
   'type','remove_exercise','exercise_log_id',exercise_log_id));
  raise exception 'FAIL removed completed exercise';
 exception when sqlstate 'PT409' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,3,'{"type":"pause"}');
 if receipt->>'status'<>'PAUSED' or receipt->>'version'<>'4' then raise exception 'FAIL pause'; end if;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,4,'{"type":"pause"}');
  raise exception 'FAIL repeated pause';
 exception when sqlstate 'PT409' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,4,'{"type":"resume"}');
 if receipt->>'status'<>'ACTIVE' or receipt->>'version'<>'5' then raise exception 'FAIL resume'; end if;
 finished:=public.finish_active_session(gen_random_uuid(),session_id,5);
 workout_id:=(finished->>'workout_id')::uuid;
 if finished->>'status'<>'COMPLETED' or finished->>'version'<>'6' or workout_id is null then raise exception 'FAIL finish'; end if;
 if not exists(select 1 from public.workout_logs w where w.id=workout_id and w.source_session_id=session_id
   and w.total_sets=1 and w.total_reps=10 and w.total_volume=205) then raise exception 'FAIL server totals'; end if;
 if (select count(*) from public.workout_sets where workout_log_id=workout_id)<>1 then raise exception 'FAIL uncompleted sets logged'; end if;
 receipt:=public.finish_active_session(gen_random_uuid(),session_id,5);
 if receipt->>'workout_id'<>workout_id::text or receipt->>'version'<>'6' then raise exception 'FAIL canonical finish'; end if;
 if (select count(*) from public.workout_logs where source_session_id=session_id)<>1 then raise exception 'FAIL duplicate workout'; end if;
 begin
  perform public.cancel_active_session(gen_random_uuid(),session_id,5);
  raise exception 'FAIL cancel overwrote completion';
 exception when sqlstate 'PT409' then
  get stacked diagnostics message=message_text;
  if message<>'SESSION_TERMINAL' then raise exception 'FAIL terminal discriminator'; end if;
 end;
 if public.create_active_session(create_id,session_id,template_id,revision)<>initial then raise exception 'FAIL historical receipt changed'; end if;
 if public.get_active_session()->>'found'<>'false' then raise exception 'FAIL terminal is running'; end if;
 if public.get_active_session(session_id)#>>'{session,version}'<>'6' then raise exception 'FAIL terminal lookup'; end if;
 perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000001',true);
 if public.get_active_session(session_id)->>'found'<>'false'
   or public.get_operation_status(create_id)->>'state'<>'NOT_FOUND' then raise exception 'FAIL cross-owner read'; end if;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,6,'{"type":"pause"}');
  raise exception 'FAIL cross-owner mutation';
 exception when sqlstate 'PT404' then null; end;
 perform set_config('request.jwt.claim.sub',owner_id::text,true);
 receipt:=public.create_active_session(gen_random_uuid(),'40000000-0000-0000-0000-000000000002',template_id,revision);
end;
$$;

reset role;
update public.active_sessions set expires_at=clock_timestamp()-interval '1 day'
 where id='40000000-0000-0000-0000-000000000002';
set local role authenticated;
do $$
declare receipt jsonb; snapshot jsonb; revision bigint;
begin
 snapshot:=public.get_active_session('40000000-0000-0000-0000-000000000002')->'session';
 if snapshot->>'status'<>'ACTIVE' or snapshot->>'effective_status'<>'EXPIRED'
   or snapshot->>'review_required'<>'true' then raise exception 'FAIL effective expiry'; end if;
 begin
  perform public.cancel_active_session(gen_random_uuid(),'40000000-0000-0000-0000-000000000002',1);
  raise exception 'FAIL expired cancel skipped review';
 exception when sqlstate 'PT409' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),'40000000-0000-0000-0000-000000000002',1,'{"type":"acknowledge_expiry"}');
 if receipt->>'status'<>'EXPIRED' or receipt->>'version'<>'2'
   or receipt#>>'{snapshot,review_required}'<>'false'
   or jsonb_array_length(receipt#>'{snapshot,exercises}')<>1 then raise exception 'FAIL expiry review preservation'; end if;
 select wt.revision into revision from public.workout_templates wt where id='20000000-0000-0000-0000-000000000001';
 receipt:=public.create_active_session(gen_random_uuid(),'40000000-0000-0000-0000-000000000003',
  '20000000-0000-0000-0000-000000000001',revision);
 receipt:=public.cancel_active_session(gen_random_uuid(),'40000000-0000-0000-0000-000000000003',1);
 if receipt->>'status'<>'CANCELLED' then raise exception 'FAIL cancel'; end if;
end;
$$;

reset role;
set local role authenticated;
do $$
declare
 receipt jsonb; current_version bigint:=1; revision bigint; e_id uuid; s_id uuid;
 added_id uuid:=gen_random_uuid(); next_id uuid:=gen_random_uuid(); press_id uuid:=gen_random_uuid();
 session_id uuid:='40000000-0000-0000-0000-000000000005';
 collision_id uuid; failed_request uuid:=gen_random_uuid(); message text;
begin
 select wt.revision into revision from public.workout_templates wt where id='20000000-0000-0000-0000-000000000001';
 receipt:=public.create_active_session(gen_random_uuid(),session_id,'20000000-0000-0000-0000-000000000001',revision);
 e_id:=(receipt#>>'{snapshot,exercises,0,id}')::uuid;
 s_id:=(receipt#>>'{snapshot,exercises,0,sets,0,id}')::uuid;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','set_rest_seconds','exercise_log_id',e_id,'rest_seconds',600));
 current_version:=(receipt->>'version')::bigint;
 if receipt#>>'{snapshot,exercises,0,rest_seconds}'<>'600' then raise exception 'FAIL rest override'; end if;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',added_id));
 current_version:=(receipt->>'version')::bigint;
 if receipt#>>'{snapshot,exercises,0,sets,3,set_number}'<>'4' then raise exception 'FAIL add set number'; end if;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','remove_set','exercise_log_id',e_id,'set_id',added_id));
 current_version:=(receipt->>'version')::bigint;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,current_version,
   jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',added_id));
  raise exception 'FAIL reused removed identity';
 exception when sqlstate 'PT400' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',next_id));
 current_version:=(receipt->>'version')::bigint;
 if receipt#>>'{snapshot,exercises,0,sets,3,set_number}'<>'5' then raise exception 'FAIL reused set number'; end if;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','add_exercise','exercise_log_id',press_id,'exercise_id','fixture-press'));
 current_version:=(receipt->>'version')::bigint;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','select_exercise','exercise_log_id',press_id));
 current_version:=(receipt->>'version')::bigint;
 if receipt#>>'{snapshot,current_exercise_id}'<>press_id::text then raise exception 'FAIL selection'; end if;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','remove_exercise','exercise_log_id',press_id));
 current_version:=(receipt->>'version')::bigint;
 if receipt#>>'{snapshot,current_exercise_id}'<>e_id::text then raise exception 'FAIL current exercise fallback'; end if;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','complete_set','exercise_log_id',e_id,'set_id',s_id));
 current_version:=(receipt->>'version')::bigint;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,current_version,
   jsonb_build_object('type','edit_set','exercise_log_id',e_id,'set_id',s_id,'weight','22.30','reps',8));
  raise exception 'FAIL edited completed set';
 exception when sqlstate 'PT409' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','uncomplete_set','exercise_log_id',e_id,'set_id',s_id));
 current_version:=(receipt->>'version')::bigint;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','edit_set','exercise_log_id',e_id,'set_id',s_id,'weight','22.30','reps',8));
 current_version:=(receipt->>'version')::bigint;
 for i in 1..16 loop
  receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
   jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',gen_random_uuid()));
  current_version:=(receipt->>'version')::bigint;
 end loop;
 begin
  perform public.update_active_session(gen_random_uuid(),session_id,current_version,
   jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',gen_random_uuid()));
  raise exception 'FAIL set cap bypass';
 exception when sqlstate 'PT400' then null; end;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','remove_set','exercise_log_id',e_id,'set_id',next_id));
 current_version:=(receipt->>'version')::bigint;
 select ws.id into collision_id from public.workout_sets ws limit 1;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','add_set','exercise_log_id',e_id,'set_id',collision_id));
 current_version:=(receipt->>'version')::bigint;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','edit_set','exercise_log_id',e_id,'set_id',collision_id,'weight','1.00','reps',1));
 current_version:=(receipt->>'version')::bigint;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,current_version,
  jsonb_build_object('type','complete_set','exercise_log_id',e_id,'set_id',collision_id));
 current_version:=(receipt->>'version')::bigint;
 begin
  perform public.finish_active_session(failed_request,session_id,current_version);
  raise exception 'FAIL induced set collision did not fail';
 exception when sqlstate 'PT409' then
  get stacked diagnostics message=message_text;
  if message<>'STATE_CONFLICT' then raise exception 'FAIL collision discriminator'; end if;
 end;
 if exists(select 1 from public.workout_logs where source_session_id=session_id)
  or public.get_operation_status(failed_request)->>'state'<>'NOT_FOUND'
  or public.get_active_session(session_id)#>>'{session,status}'<>'ACTIVE'
  or public.get_active_session(session_id)#>>'{session,version}'<>current_version::text then
  raise exception 'FAIL atomic finish rollback'; end if;
 perform public.cancel_active_session(gen_random_uuid(),session_id,current_version);
end;
$$;
reset role;
insert into public.active_sessions(id,user_id,template_id,template_name,expires_at,session_data)
values('40000000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-000000000001',
 '20000000-0000-0000-0000-000000000001','Legacy fixture',clock_timestamp()+interval '1 day','{"legacy":"preserve"}');
set local role authenticated;
do $$
declare message text; revision bigint;
begin
 begin
  perform public.get_active_session(); raise exception 'FAIL legacy became valid';
 exception when sqlstate 'PT409' then
  get stacked diagnostics message=message_text;
  if message<>'LEGACY_REVIEW_REQUIRED' then raise exception 'FAIL legacy discriminator'; end if;
 end;
 select wt.revision into revision from public.workout_templates wt where id='20000000-0000-0000-0000-000000000001';
 begin
  perform public.create_active_session(gen_random_uuid(),gen_random_uuid(),'20000000-0000-0000-0000-000000000001',revision);
  raise exception 'FAIL legacy create bypass';
 exception when sqlstate 'PT409' then null; end;
 if not exists(select 1 from public.active_sessions where id='40000000-0000-0000-0000-000000000004'
   and version is null and session_data='{"legacy":"preserve"}'::jsonb) then raise exception 'FAIL legacy modified'; end if;
end;
$$;
reset role;
set constraints all immediate;
rollback;
