-- Local synthetic query-plan check, not a load/latency benchmark.
begin;
set local statement_timeout='30s';
insert into auth.users(id,email) values('a0000000-0000-0000-0000-000000000001','fixture-plan@example.invalid');
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume)
select md5('fixture-plan-'||n)::uuid,'a0000000-0000-0000-0000-000000000001','Fixture plan','2026-10-01',
 '2026-10-01T00:00:00Z'::timestamptz+n*interval '1 second',
 '2026-10-01T00:01:00Z'::timestamptz+n*interval '1 second',1
from generate_series(1,10000) n;
analyze public.workout_logs;
do $$
declare query_plan jsonb;
begin
 execute $plan$
  explain (analyze,buffers,format json)
  select id,end_time from public.workout_logs
  where user_id='a0000000-0000-0000-0000-000000000001' and deleted_at is null
   and start_time>='2026-10-01T00:00:00Z' and start_time<'2026-10-02T00:00:00Z'
   and (end_time,id)<('2026-10-01T01:00:00Z','ffffffff-ffff-ffff-ffff-ffffffffffff')
  order by end_time desc,id desc limit 21
 $plan$ into query_plan;
 if strpos(query_plan::text,'workout_history_keyset')=0 then
  raise exception 'FAIL history keyset index not selected on synthetic 10k fixture';
 end if;
end;
$$;
rollback;
