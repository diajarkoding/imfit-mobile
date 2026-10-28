-- Local final-cutoff security/constraint checks; all synthetic data rolls back.
begin;
set local statement_timeout='30s';
insert into auth.users(id,email) values
 ('a0000000-0000-0000-0000-000000000001','fixture-a@example.invalid'),
 ('b0000000-0000-0000-0000-000000000001','fixture-b@example.invalid');
insert into public.muscle_categories(id,name,display_name) overriding system value values(999991,'fixture-test','Fixture');
insert into public.exercises(id,muscle_category_id,name,description,is_active) values
 ('fixture-alpha',999991,'Fixture alpha','Test',true),('fixture-inactive',999991,'Fixture inactive','Test',false);
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time) values
 ('30000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000001','Fixture','2026-10-01','2026-10-01T00:00Z','2026-10-01T01:00Z'),
 ('30000000-0000-0000-0000-000000000002','b0000000-0000-0000-0000-000000000001','Foreign','2026-10-01','2026-10-01T00:00Z','2026-10-01T01:00Z');
insert into public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category) values
 ('50000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','fixture-alpha','Fixture alpha','Fixture');

do $$
declare function_count integer;
begin
 begin
  insert into public.workout_sets(exercise_log_id,workout_log_id,exercise_id,set_number) values
   ('50000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000002','fixture-alpha',1);
  raise exception 'FAIL mismatched workout parent';
 exception when foreign_key_violation then null; end;
 begin
  insert into public.workout_sets(exercise_log_id,workout_log_id,exercise_id,set_number) values
   ('50000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001','fixture-inactive',1);
  raise exception 'FAIL mismatched exercise parent';
 exception when foreign_key_violation then null; end;
 begin
  insert into public.workout_sets(exercise_log_id,workout_log_id,exercise_id,set_number) values
   ('50000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',null,1);
  raise exception 'FAIL partial parent nullability';
 exception when check_violation then null; end;
 select count(*) into function_count from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' and p.proname=any(array[
  'save_template_revision','get_workout_history_page','get_exercise_catalog_page','get_template_page',
  'get_workout_detail','get_template_detail','get_progress_summary','get_workout_date_markers',
  'get_exercise_prefill','create_active_session','get_active_session','update_active_session',
  'cancel_active_session','finish_active_session','get_operation_status','list_recent_operations'])
  and has_function_privilege('authenticated',p.oid,'EXECUTE') and not has_function_privilege('anon',p.oid,'EXECUTE');
 if function_count<>16 then raise exception 'FAIL RPC grant inventory'; end if;
 if has_schema_privilege('authenticated','imfit_private','USAGE')
  or has_function_privilege('authenticated','public.upsert_workout_aggregate(jsonb,jsonb,jsonb)','EXECUTE')
  or has_function_privilege('authenticated','public.upsert_template_aggregate(jsonb,jsonb)','EXECUTE') then
  raise exception 'FAIL private/legacy execute permission'; end if;
end;
$$;
set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);
do $$
declare rows_affected integer; receipt jsonb; session_id uuid:=gen_random_uuid(); eid uuid; sid uuid;
 paused_at timestamptz; detail jsonb;
begin
 if (select count(*) from public.profiles)<>1 then raise exception 'FAIL profile isolation'; end if;
 if exists(select 1 from public.exercises where id='fixture-inactive') then raise exception 'FAIL inactive catalog visible'; end if;
 update public.profiles set name='Fixture updated' where id='a0000000-0000-0000-0000-000000000001';
 get diagnostics rows_affected=row_count;
 if rows_affected<>1 then raise exception 'FAIL allowed profile update'; end if;
 update public.profiles set name='Forged' where id='b0000000-0000-0000-0000-000000000001';
 get diagnostics rows_affected=row_count;
 if rows_affected<>0 then raise exception 'FAIL cross-owner profile update'; end if;
 begin
  update public.profiles set email='forged@example.invalid'; raise exception 'FAIL auth-managed profile write';
 exception when insufficient_privilege then null; end;
 begin
  insert into public.profiles(id,name,email) values(gen_random_uuid(),'Forged','forged@example.invalid');
  raise exception 'FAIL profile insert';
 exception when insufficient_privilege then null; end;
 begin
  insert into public.workout_templates(user_id,name) values('a0000000-0000-0000-0000-000000000001','Forged');
  raise exception 'FAIL direct template write';
 exception when insufficient_privilege then null; end;
 begin
  update public.active_sessions set version=999; raise exception 'FAIL direct session write';
 exception when insufficient_privilege then null; end;
 begin
  delete from public.workout_sets; raise exception 'FAIL direct child delete';
 exception when insufficient_privilege then null; end;
 begin
  perform count(*) from public.template_write_receipts; raise exception 'FAIL raw template receipt read';
 exception when insufficient_privilege then null; end;
 begin
  perform count(*) from imfit_private.operation_receipts; raise exception 'FAIL raw operation receipt read';
 exception when insufficient_privilege then null; end;
 begin
  perform imfit_private.require_owner(); raise exception 'FAIL private helper execution';
 exception when insufficient_privilege then null; end;
 begin
  perform public.upsert_workout_aggregate('{}','[]','[]'); raise exception 'FAIL legacy workout writer';
 exception when insufficient_privilege then null; end;
 begin
  perform public.upsert_template_aggregate('{}','[]'); raise exception 'FAIL legacy template writer';
 exception when insufficient_privilege then null; end;
 receipt:=public.save_template_revision(gen_random_uuid(),'20000000-0000-0000-0000-000000000001',null,'Fixture RPC-only',
  '[{"exercise_id":"fixture-alpha","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]','[]',false,
  'a0000000-0000-0000-0000-000000000001');
 receipt:=public.create_active_session(gen_random_uuid(),session_id,'20000000-0000-0000-0000-000000000001',(receipt->>'revision')::bigint);
 eid:=(receipt#>>'{snapshot,exercises,0,id}')::uuid; sid:=(receipt#>>'{snapshot,exercises,0,sets,0,id}')::uuid;
 receipt:=public.update_active_session(gen_random_uuid(),session_id,1,jsonb_build_object(
  'type','edit_set','exercise_log_id',eid,'set_id',sid,'weight','1.00','reps',2));
 receipt:=public.update_active_session(gen_random_uuid(),session_id,2,jsonb_build_object(
  'type','complete_set','exercise_log_id',eid,'set_id',sid));
 receipt:=public.update_active_session(gen_random_uuid(),session_id,3,'{"type":"pause"}');
 paused_at:=(receipt#>>'{snapshot,paused_at}')::timestamptz;
 perform pg_sleep(0.01);
 receipt:=public.finish_active_session(gen_random_uuid(),session_id,4);
 if receipt#>'{snapshot,paused_at}'<>'null'::jsonb
   or (receipt#>>'{snapshot,total_paused_ms}')::bigint<1 then
  raise exception 'FAIL terminal pause accounting'; end if;
 detail:=public.get_workout_detail((receipt->>'workout_id')::uuid);
 if (receipt#>>'{snapshot,total_paused_ms}')::bigint<>
   floor(extract(epoch from((detail->>'end_time')::timestamptz-paused_at))*1000)::bigint then
  raise exception 'FAIL terminal pause/workout end anchor mismatch'; end if;
 if detail->>'total_volume'<>'2.00' then
  raise exception 'FAIL definer RPC lost required access'; end if;
end;
$$;
reset role;
-- Pure envelope boundary test on an in-memory row, without modifying real data.
-- The existing unique name index correctly prevents storing huge parent names.
do $$
declare stored_row public.active_sessions%rowtype;
begin
 select * into strict stored_row from public.active_sessions
  where user_id='a0000000-0000-0000-0000-000000000001' and status='COMPLETED';
 stored_row.template_name:=repeat('x',262145);
 begin
  perform imfit_private.session_receipt(stored_row,gen_random_uuid(),'fixture_snapshot_size');
  raise exception 'FAIL oversized snapshot returned';
 exception when sqlstate 'PT400' then null; end;
end;
$$;
set local role anon;
do $$
begin
 begin perform count(*) from public.exercises; raise exception 'FAIL anon catalog';
 exception when insufficient_privilege then null; end;
 begin perform public.get_active_session(); raise exception 'FAIL anon session RPC';
 exception when insufficient_privilege then null; end;
end;
$$;
reset role;
set constraints all immediate;
rollback;
