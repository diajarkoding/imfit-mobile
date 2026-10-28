-- Run ONLY against the isolated local test database after the receipt migration.
-- Everything created here is synthetic and rolls back, including auth fixtures.
begin;
set local statement_timeout = '30s';
insert into auth.users(id,email) values
  ('a0000000-0000-0000-0000-000000000001','fixture-a@example.invalid'),
  ('b0000000-0000-0000-0000-000000000001','fixture-b@example.invalid');
insert into public.muscle_categories(id,name,display_name) overriding system value
  values (999991,'fixture-test','Fixture');
insert into public.exercises(id,muscle_category_id,name,description) values
  ('fixture-squat',999991,'Fixture squat','Test'),
  ('fixture-press',999991,'Fixture press','Test');
set local role authenticated;
select set_config('request.jwt.claim.sub','a0000000-0000-0000-0000-000000000001',true);

do $$
declare
  owner_id uuid := 'a0000000-0000-0000-0000-000000000001';
  template_id uuid := '20000000-0000-0000-0000-000000000001';
  request_one uuid := '10000000-0000-0000-0000-000000000001';
  request_two uuid := '10000000-0000-0000-0000-000000000002';
  request_three uuid := '10000000-0000-0000-0000-000000000003';
  children jsonb := '[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]';
  original jsonb; updated jsonb; replayed jsonb; discovered jsonb;
  page_one jsonb; page_two jsonb; cursor_one text; message text;
  window_start timestamptz := statement_timestamp() - interval '1 hour';
  window_end timestamptz := statement_timestamp() + interval '1 hour';
begin
  original := public.save_template_revision(request_one,template_id,null,
    'Fixture routine',children,'[]',false,owner_id);
  if original->>'exercise_count' <> '1' then raise exception 'FAIL create'; end if;
  discovered := public.get_operation_status(request_one);
  if discovered->>'state' <> 'COMMITTED'
     or discovered#>>'{receipt,operation}' <> 'save_template_revision'
     or discovered#>>'{receipt,committed_at_source}' <> 'COMMIT'
     or discovered#>'{receipt,result}' <> original
     or discovered->'receipt' ? 'payload' then raise exception 'FAIL discovery'; end if;

  begin
    perform public.save_template_revision(request_one,template_id,null,
      'Changed payload',children,'[]',false,owner_id);
    raise exception 'FAIL mismatched replay accepted';
  exception when sqlstate 'PT409' then
    get stacked diagnostics message = message_text;
    if message <> 'IDEMPOTENCY_MISMATCH' then raise exception 'FAIL mismatch discriminator'; end if;
  end;

  updated := public.save_template_revision(request_two,template_id,(original->>'revision')::bigint,
    'Updated routine',children,'[]',false,owner_id);
  if (updated->>'revision')::bigint <= (original->>'revision')::bigint then
    raise exception 'FAIL revision not monotonic';
  end if;
  replayed := public.save_template_revision(request_one,template_id,null,
    'Fixture routine',children,'[]',false,owner_id);
  if replayed <> original then raise exception 'FAIL receipt changed after later mutation'; end if;

  begin
    perform public.save_template_revision(request_three,template_id,(original->>'revision')::bigint,
      'Stale routine',children,'[]',false,owner_id);
    raise exception 'FAIL stale revision accepted';
  exception when sqlstate 'PT409' then
    get stacked diagnostics message = message_text;
    if message <> 'REVISION_CONFLICT' then raise exception 'FAIL revision discriminator'; end if;
  end;
  if public.get_operation_status(request_three)->>'state' <> 'NOT_FOUND' then
    raise exception 'FAIL failed write produced receipt';
  end if;

  begin
    perform public.save_template_revision(request_three,template_id,(updated->>'revision')::bigint,
      'Invalid child',jsonb_set(children,'{0,sets}','"3"'),'[]',false,owner_id);
    raise exception 'FAIL string integer accepted';
  exception when sqlstate 'PT400' then
    get stacked diagnostics message = message_text;
    if message <> 'VALIDATION' then raise exception 'FAIL validation discriminator'; end if;
  end;
  begin
    perform public.save_template_revision(request_three,template_id,(updated->>'revision')::bigint,
      'Invalid child',jsonb_set(children,'{0,unexpected}','true'),'[]',false,owner_id);
    raise exception 'FAIL unknown child field accepted';
  exception when sqlstate 'PT400' then null;
  end;
  if public.get_operation_status(request_three)->>'state' <> 'NOT_FOUND' then
    raise exception 'FAIL invalid write produced receipt';
  end if;

  page_one := public.list_recent_operations(window_start,window_end,null,1);
  cursor_one := page_one#>>'{page_info,next_cursor}';
  if jsonb_array_length(page_one->'data') <> 1
     or page_one#>>'{page_info,has_more}' <> 'true' or cursor_one is null then
    raise exception 'FAIL first page';
  end if;
  page_two := public.list_recent_operations(window_start,window_end,cursor_one,1);
  if jsonb_array_length(page_two->'data') <> 1
     or page_two#>>'{page_info,has_more}' <> 'false'
     or page_two#>>'{page_info,next_cursor}' is not null
     or page_one#>>'{data,0,request_id}' = page_two#>>'{data,0,request_id}' then
    raise exception 'FAIL keyset page';
  end if;
  begin
    perform public.list_recent_operations(window_start,window_end,'not-base64',1);
    raise exception 'FAIL malformed cursor accepted';
  exception when sqlstate 'PT400' then
    get stacked diagnostics message = message_text;
    if message <> 'INVALID_CURSOR' then raise exception 'FAIL cursor discriminator'; end if;
  end;
  begin
    perform public.list_recent_operations(window_start - interval '1 minute',window_end,cursor_one,1);
    raise exception 'FAIL cross-query cursor accepted';
  exception when sqlstate 'PT400' then null;
  end;
  begin
    perform public.list_recent_operations(window_start,window_end,null,0);
    raise exception 'FAIL invalid limit accepted';
  exception when sqlstate 'PT400' then null;
  end;

  perform set_config('request.jwt.claim.sub','b0000000-0000-0000-0000-000000000001',true);
  if public.get_operation_status(request_one)->>'state' <> 'NOT_FOUND'
     or public.list_recent_operations(window_start,window_end)->'data' <> '[]'::jsonb then
    raise exception 'FAIL cross-owner discovery';
  end if;
  begin
    perform public.list_recent_operations(window_start,window_end,cursor_one,1);
    raise exception 'FAIL cross-owner cursor accepted';
  exception when sqlstate 'PT400' then null;
  end;
  begin
    perform public.save_template_revision(request_three,template_id,null,
      'Forged owner',children,'[]',false,owner_id);
    raise exception 'FAIL forged owner accepted';
  exception when insufficient_privilege then null;
  end;
  begin
    perform count(*) from imfit_private.operation_receipts;
    raise exception 'FAIL private receipts exposed';
  exception when insufficient_privilege then null;
  end;
end;
$$;

set local role anon;
do $$
begin
  begin
    perform public.get_operation_status('10000000-0000-0000-0000-000000000001');
    raise exception 'FAIL anon executed discovery';
  exception when insufficient_privilege then null;
  end;
end;
$$;
rollback;
