-- Local synthetic fixture: >500 rows, timestamp ties, timezone boundaries, precision.
begin;
set local statement_timeout='30s';
insert into auth.users(id,email) values
 ('a0000000-0000-0000-0000-000000000001','fixture-a@example.invalid'),
 ('b0000000-0000-0000-0000-000000000001','fixture-b@example.invalid');
insert into public.muscle_categories(id,name,display_name) overriding system value values(999991,'fixture-test','Fixture');
insert into public.exercises(id,muscle_category_id,name,description,is_active) values
 ('fixture-alpha',999991,'Fixture alpha','Test',true),('fixture-beta',999991,'Fixture beta','Test',true),
 ('fixture-inactive',999991,'Fixture inactive','Test',false);
insert into public.workout_templates(id,user_id,name,is_deleted) values
 ('20000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000001','Fixture one',false),
 ('20000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000001','Fixture two',false),
 ('20000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000001','Fixture deleted',true),
 ('20000000-0000-0000-0000-000000000004','b0000000-0000-0000-0000-000000000001','Fixture foreign',false);
insert into public.template_exercises(template_id,exercise_id,order_index,sets,reps,rest_seconds) values
 ('20000000-0000-0000-0000-000000000001','fixture-alpha',0,3,10,60),
 ('20000000-0000-0000-0000-000000000001','fixture-beta',1,4,8,90);
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps)
select md5('fixture-workout-'||n)::uuid,'a0000000-0000-0000-0000-000000000001','Fixture history','2026-10-01',
 '2026-10-01T00:00:00Z'::timestamptz+n*interval '1 minute','2026-10-03T00:00:00.123456Z',1.001,1,1
from generate_series(1,600) n;
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,deleted_at) values
 ('30000000-0000-0000-0000-000000006001','a0000000-0000-0000-0000-000000000001','Boundary','2026-10-01',
  '2026-10-01T18:30:00Z','2026-10-02T01:00:00Z',2.005,1,1,null),
 ('30000000-0000-0000-0000-000000006002','a0000000-0000-0000-0000-000000000001','Prefill','2026-10-03',
  '2026-10-03T00:00:00Z','2026-10-03T00:30:00Z',50,1,10,null),
 ('30000000-0000-0000-0000-000000006003','a0000000-0000-0000-0000-000000000001','Deleted','2026-10-03',
  '2026-10-03T00:00:00Z','2026-10-03T00:30:00Z',999,1,10,statement_timestamp()),
 ('30000000-0000-0000-0000-000000006004','b0000000-0000-0000-0000-000000000001','Foreign','2026-10-03',
  '2026-10-03T00:00:00Z','2026-10-03T00:30:00Z',999,1,10,null);
insert into public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,total_volume,total_sets,total_reps,input_defaults)
values('50000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000006002','fixture-alpha',
 'Historical name','Fixture',0,50,1,10,'{"sets":3,"reps":10}');
insert into public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed) values
 ('60000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000001',
  '30000000-0000-0000-0000-000000006002','fixture-alpha',1,5.00,10,true);
-- DST fold/gap boundaries: local day length is not always 24 hours.
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time)
select md5('fixture-dst-'||n)::uuid,'a0000000-0000-0000-0000-000000000001','DST fixture',
  instant::date,instant,instant+interval '1 minute'
from (values
 (1,'2026-11-01T03:30:00Z'::timestamptz),(2,'2026-11-01T05:30:00Z'::timestamptz),
 (3,'2026-11-01T06:30:00Z'::timestamptz),(4,'2026-11-02T04:30:00Z'::timestamptz),
 (5,'2026-03-08T04:30:00Z'::timestamptz),(6,'2026-03-08T06:30:00Z'::timestamptz),
 (7,'2026-03-08T07:30:00Z'::timestamptz),(8,'2026-03-09T03:30:00Z'::timestamptz)
) d(n,instant);
set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);

do $$
declare page jsonb; first_page jsonb; second_page jsonb; cursor_value text; first_cursor text;
 ids uuid[]:='{}'; item jsonb; summary jsonb; detail jsonb; prefill jsonb; message text; count_pages integer:=0;
begin
 first_page:=public.get_workout_history_page('2026-10-01','2026-10-04','UTC',null,37);
 first_cursor:=first_page#>>'{page_info,next_cursor}';
 page:=first_page;
 loop
  count_pages:=count_pages+1;
  if count_pages>20 then raise exception 'FAIL non-advancing cursor'; end if;
  for item in select value from jsonb_array_elements(page->'data') loop
   if (item->>'id')::uuid=any(ids) then raise exception 'FAIL duplicate across pages'; end if;
   ids:=array_append(ids,(item->>'id')::uuid);
   if jsonb_typeof(item->'total_volume')<>'string' then raise exception 'FAIL numeric wire'; end if;
  end loop;
  cursor_value:=page#>>'{page_info,next_cursor}';
  exit when page#>>'{page_info,has_more}'='false';
  if cursor_value is null then raise exception 'FAIL missing cursor'; end if;
  page:=public.get_workout_history_page('2026-10-01','2026-10-04','UTC',cursor_value,37);
 end loop;
 if cardinality(ids)<>602 or cursor_value is not null then raise exception 'FAIL complete history pagination'; end if;
 summary:=public.get_progress_summary('2026-10-01','2026-10-04','UTC');
 if summary->>'workout_count'<>'602' or (summary->>'total_volume')::numeric<>652.605
   or summary->>'total_sets'<>'602' or summary->>'total_reps'<>'611' then raise exception 'FAIL full aggregate'; end if;
 if public.get_progress_summary('2026-10-01','2026-10-02','UTC')->>'workout_count'<>'601'
   or public.get_progress_summary('2026-10-01','2026-10-02','Asia/Jakarta')->>'workout_count'<>'600' then
   raise exception 'FAIL timezone start grouping'; end if;
 if public.get_progress_summary('2026-11-01','2026-11-02','America/New_York')->>'workout_count'<>'3'
  or public.get_progress_summary('2026-11-02','2026-11-03','America/New_York')->>'workout_count'<>'0'
  or public.get_progress_summary('2026-11-02','2026-11-03','UTC')->>'workout_count'<>'1'
  or public.get_progress_summary('2026-03-08','2026-03-09','America/New_York')->>'workout_count'<>'3'
  or public.get_progress_summary('2026-03-09','2026-03-10','America/New_York')->>'workout_count'<>'0'
  or public.get_progress_summary('2026-03-09','2026-03-10','UTC')->>'workout_count'<>'1' then
  raise exception 'FAIL DST fold/gap day bounds'; end if;
 detail:=public.get_workout_date_markers('2026-10-01','2026-10-04','Asia/Jakarta');
 if detail#>>'{dates,0,workout_count}'<>'600' or detail#>>'{dates,1,workout_count}'<>'1'
   or detail#>>'{dates,2,workout_count}'<>'1' then raise exception 'FAIL date markers'; end if;
 begin
  perform public.get_workout_history_page('2026-10-01','2026-10-04','Asia/Jakarta',first_cursor,37);
  raise exception 'FAIL timezone cursor mismatch accepted';
 exception when sqlstate 'PT400' then null; end;
 begin
  perform public.get_progress_summary('2026-01-01','2028-01-01','UTC');
  raise exception 'FAIL oversized window';
 exception when sqlstate 'PT400' then null; end;
 begin
  perform public.get_workout_date_markers('2026-10-01','2026-10-04','EST');
  raise exception 'FAIL timezone abbreviation';
 exception when sqlstate 'PT400' then null; end;
 detail:=public.get_workout_detail('30000000-0000-0000-0000-000000006002');
 if detail->>'duration_seconds'<>'1800' or detail#>>'{exercise_logs,0,exercise_name}'<>'Historical name'
   or detail#>>'{exercise_logs,0,sets,0,weight}'<>'5.00' then raise exception 'FAIL consistent detail'; end if;
 begin
  perform public.get_workout_detail('30000000-0000-0000-0000-000000006003');
  raise exception 'FAIL deleted detail visible';
 exception when sqlstate 'PT404' then null; end;
 first_page:=public.get_template_page(null,1);
 second_page:=public.get_template_page(first_page#>>'{page_info,next_cursor}',1);
 if first_page#>>'{page_info,has_more}'<>'true' or second_page#>>'{page_info,has_more}'<>'false'
   or first_page#>>'{data,0,id}'=second_page#>>'{data,0,id}' then raise exception 'FAIL template pagination'; end if;
 detail:=public.get_template_detail('20000000-0000-0000-0000-000000000001');
 if jsonb_array_length(detail->'exercises')<>2 or detail#>>'{exercises,1,order_index}'<>'1' then
  raise exception 'FAIL template aggregate'; end if;
 first_page:=public.get_exercise_catalog_page('  FIXTURE  ',999991,null,1);
 second_page:=public.get_exercise_catalog_page('fixture',999991,first_page#>>'{page_info,next_cursor}',1);
 if first_page#>>'{data,0,id}'<>'fixture-alpha' or second_page#>>'{data,0,id}'<>'fixture-beta'
   or second_page#>>'{page_info,has_more}'<>'false' then raise exception 'FAIL catalog normalization/page/visibility'; end if;
 if public.get_exercise_catalog_page('%',999991)->'data'<>'[]'::jsonb then raise exception 'FAIL wildcard search expansion'; end if;
 begin
  perform public.get_exercise_catalog_page('fixture',999991,null,101); raise exception 'FAIL invalid limit';
 exception when sqlstate 'PT400' then null; end;
 prefill:=public.get_exercise_prefill('fixture-alpha','{"sets":3,"reps":10}');
 if prefill->>'source'<>'HISTORY' or prefill#>>'{sets,0,weight}'<>'5.00'
   or prefill#>>'{sets,1,weight}'<>'0.00' or prefill#>>'{sets,1,reps}'<>'10' then raise exception 'FAIL prefill matching/gaps'; end if;
 if public.get_exercise_prefill('fixture-alpha','{"sets":4,"reps":10}')->>'source'<>'DEFAULTS' then
  raise exception 'FAIL mismatched provenance reused'; end if;
 perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000001',true);
 if public.get_progress_summary('2026-10-01','2026-10-04','UTC')->>'workout_count'<>'1'
   or jsonb_array_length(public.get_template_page()->'data')<>1 then raise exception 'FAIL owner aggregate'; end if;
 begin
  perform public.get_workout_history_page('2026-10-01','2026-10-04','UTC',first_cursor,37);
  raise exception 'FAIL owner cursor mismatch';
 exception when sqlstate 'PT400' then null; end;
 begin
  perform public.get_template_detail('20000000-0000-0000-0000-000000000001'); raise exception 'FAIL foreign template detail';
 exception when sqlstate 'PT404' then null; end;
 begin
  perform public.get_workout_detail('30000000-0000-0000-0000-000000006002'); raise exception 'FAIL foreign workout detail';
 exception when sqlstate 'PT404' then null; end;
 perform set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);
end;
$$;
reset role;
update public.workout_sets set weight=5.001 where id='60000000-0000-0000-0000-000000000001';
set local role authenticated;
do $$
begin
 begin
  perform public.get_exercise_prefill('fixture-alpha','{"sets":3,"reps":10}'); raise exception 'FAIL legacy precision rounded';
 exception when sqlstate 'PT409' then null; end;
 if public.get_workout_detail('30000000-0000-0000-0000-000000006002')#>>'{exercise_logs,0,sets,0,weight}'<>'5.001' then
  raise exception 'FAIL legacy read precision lost'; end if;
end;
$$;
set local role anon;
do $$
begin
 begin
  perform public.get_workout_history_page('2026-10-01','2026-10-04','UTC'); raise exception 'FAIL anon history';
 exception when insufficient_privilege then null; end;
end;
$$;
reset role;
set constraints all immediate;
rollback;
