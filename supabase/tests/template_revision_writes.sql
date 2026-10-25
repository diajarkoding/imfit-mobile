-- Run ONLY on an isolated Supabase test database after migrations.
-- This file is NOT executed by JVM tests. No real project was accessed by the integration agent.
begin;

insert into auth.users(id, email) values
  ('a0000000-0000-0000-0000-000000000001', 'fixture-a@example.invalid'),
  ('b0000000-0000-0000-0000-000000000001', 'fixture-b@example.invalid');
insert into public.muscle_categories(id,name,display_name) overriding system value
  values (999991, 'fixture-test', 'Fixture');
insert into public.exercises(id,muscle_category_id,name,description) values
  ('fixture-squat',999991,'Fixture squat','Test'),
  ('fixture-press',999991,'Fixture press','Test');

set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);

do $$
declare
  created jsonb;
  replayed jsonb;
  updated jsonb;
  children jsonb := '[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60},
                     {"exercise_id":"fixture-press","order_index":1,"sets":3,"reps":10,"rest_seconds":60}]';
  revision_before bigint;
  squat_id uuid;
begin
  created := public.save_template_revision('10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',null,'Fixture routine',children,'[]',false,'a0000000-0000-0000-0000-000000000001');
  replayed := public.save_template_revision('10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',null,'Fixture routine',children,'[]',false,'a0000000-0000-0000-0000-000000000001');
  if created <> replayed then raise exception 'FAIL: idempotent replay'; end if;
  begin
    perform public.save_template_revision('10000000-0000-0000-0000-000000000001',
      '20000000-0000-0000-0000-000000000001',null,'Changed payload',children,'[]',false,'a0000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: reused request accepted';
  exception when invalid_parameter_value then null;
  end;
  select id into squat_id from public.template_exercises
    where template_id='20000000-0000-0000-0000-000000000001' and exercise_id='fixture-squat';
  children := '[{"exercise_id":"fixture-press","order_index":0,"sets":4,"reps":8,"rest_seconds":90},
                {"exercise_id":"fixture-squat","order_index":1,"sets":3,"reps":10,"rest_seconds":60}]';
  updated := public.save_template_revision('10000000-0000-0000-0000-000000000002',
    '20000000-0000-0000-0000-000000000001',(created->>'revision')::bigint,'Fixture routine',children,'[]',false,'a0000000-0000-0000-0000-000000000001');
  if (updated->>'revision')::bigint <= (created->>'revision')::bigint then raise exception 'FAIL: revision not monotonic'; end if;
  if not exists (select 1 from public.template_exercises where id=squat_id and order_index=1) then
    raise exception 'FAIL: reorder lost child identity';
  end if;
  begin
    perform public.save_template_revision('10000000-0000-0000-0000-000000000003',
      '20000000-0000-0000-0000-000000000001',(created->>'revision')::bigint,'Stale name',children,'[]',false,'a0000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: stale revision accepted';
  exception when serialization_failure then null;
  end;

  revision_before := (updated->>'revision')::bigint;
  begin
    perform public.save_template_revision('10000000-0000-0000-0000-000000000004',
      '20000000-0000-0000-0000-000000000001',revision_before,'Invalid child',
      '[{"exercise_id":"missing-fixture-exercise","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]',
      '["fixture-squat","fixture-press"]',false,'a0000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: invalid child accepted';
  exception when foreign_key_violation then null;
  end;
  if (select revision from public.workout_templates where id='20000000-0000-0000-0000-000000000001') <> revision_before or
     (select count(*) from public.template_exercises where template_id='20000000-0000-0000-0000-000000000001') <> 2 then
    raise exception 'FAIL: partial write escaped rollback';
  end if;
  begin
    perform public.save_template_revision('10000000-0000-0000-0000-000000000005',
      '20000000-0000-0000-0000-000000000001',revision_before,'Implicit removal',
      '[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]','[]',false,'a0000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: implicit removal accepted';
  exception when serialization_failure then null;
  end;
  updated := public.save_template_revision('10000000-0000-0000-0000-000000000006',
    '20000000-0000-0000-0000-000000000001',revision_before,'Explicit removal',
    '[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]','["fixture-press"]',false,'a0000000-0000-0000-0000-000000000001');
  if (select count(*) from public.template_exercises where template_id='20000000-0000-0000-0000-000000000001') <> 1 then
    raise exception 'FAIL: explicit removal not applied';
  end if;

  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000001',true);
  begin
    perform public.save_template_revision('10000000-0000-0000-0000-000000000007',
      '20000000-0000-0000-0000-000000000001',(updated->>'revision')::bigint,'Foreign account','[]','[]',false,'a0000000-0000-0000-0000-000000000001');
    raise exception 'FAIL: foreign owner accepted';
  exception when insufficient_privilege then null;
  end;
  perform set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);
  -- Receipts are server-only, including when a malicious caller knows its own owner id.
  begin
    insert into public.template_write_receipts values('a0000000-0000-0000-0000-000000000001',
      '10000000-0000-0000-0000-000000000099','{}','{}');
    raise exception 'FAIL: client forged a receipt';
  exception when insufficient_privilege then null;
  end;
end;
$$;
rollback;
