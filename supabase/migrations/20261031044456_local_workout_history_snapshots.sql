-- Full Room history hydration needs tombstones and complete children, not direct
-- table SELECT grants or date-window-limited UI history summaries.
set lock_timeout = '5s';
set statement_timeout = '30s';

create or replace function public.get_workout_sync_page(
  p_after_id uuid default null, p_limit integer default 25
) returns jsonb
language plpgsql stable security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_rows jsonb;
  v_more boolean;
  v_cursor uuid;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_limit is null or p_limit not between 1 and 50 then
    raise exception 'Invalid page limit' using errcode='22023';
  end if;
  -- One SQL statement snapshots each parent and ALL children atomically. The
  -- extra row determines continuation without an offset/row-limit truncation.
  with selected as materialized (
    select w.* from public.workout_logs w
    where w.user_id=v_owner and (p_after_id is null or w.id>p_after_id)
    order by w.id limit p_limit+1
  ), page as (
    select * from selected order by id limit p_limit
  )
  select coalesce(jsonb_agg(jsonb_build_object('workout',to_jsonb(w),
    'exercises',(select coalesce(jsonb_agg(to_jsonb(e) order by e.order_index,e.id),'[]')
      from public.exercise_logs e where e.workout_log_id=w.id),
    'sets',(select coalesce(jsonb_agg(to_jsonb(s)||jsonb_build_object(
        'workout_log_id',w.id,'exercise_id',e.exercise_id) order by s.exercise_log_id,s.set_number,s.id),'[]')
      from public.workout_sets s join public.exercise_logs e on e.id=s.exercise_log_id
      where e.workout_log_id=w.id)) order by w.id),'[]'),
    (select count(*)>p_limit from selected),
    (select id from page order by id desc limit 1)
    into v_rows,v_more,v_cursor from page w;
  return jsonb_build_object('owner_id',v_owner,'items',v_rows,
    'next_cursor',case when v_more then v_cursor else null end);
end;
$$;
revoke all on function public.get_workout_sync_page(uuid,integer) from public,anon;
grant execute on function public.get_workout_sync_page(uuid,integer) to authenticated;
comment on function public.get_workout_sync_page(uuid,integer) is
  'Owner-only complete history aggregates (including tombstones), atomic per page, UUID keyset pagination. No direct table grants.';
notify pgrst, 'reload schema';
