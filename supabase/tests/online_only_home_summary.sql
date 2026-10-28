-- G3 all-time summary, owner/visibility/grant coverage. Synthetic rollback only.
begin;
insert into auth.users(id,email) values
 ('a0000000-0000-0000-0000-000000000001','fixture-home-a@example.invalid'),
 ('b0000000-0000-0000-0000-000000000001','fixture-home-b@example.invalid');
insert into public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,deleted_at) values
 ('30000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000001','Old visible','2020-01-01','2020-01-01T00:00Z','2020-01-01T01:00Z',1.005,null),
 ('30000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000001','Deleted latest','2026-10-01','2026-10-01T00:00Z','2026-10-01T01:00Z',999,now()),
 ('30000000-0000-0000-0000-000000000003','b0000000-0000-0000-0000-000000000001','Other owner','2026-10-01','2026-10-01T00:00Z','2026-10-01T01:00Z',999,null);
set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);
do $$ declare result jsonb; begin
 result:=public.get_home_summary();
 if result#>>'{last_workout,id}'<>'30000000-0000-0000-0000-000000000001'
  or result#>>'{last_workout,total_volume}'<>'1.005' then raise exception 'FAIL Home all-time/owner/precision/visibility'; end if;
end $$;
reset role;
update public.workout_logs set deleted_at=now() where user_id='a0000000-0000-0000-0000-000000000001';
set local role authenticated;
do $$ begin
 if public.get_home_summary()<>'{"last_workout":null}'::jsonb then raise exception 'FAIL authoritative empty Home'; end if;
end $$;
set local role anon;
do $$ begin
 begin perform public.get_home_summary(); raise exception 'FAIL anon Home';
 exception when insufficient_privilege then null; end;
end $$;
rollback;
