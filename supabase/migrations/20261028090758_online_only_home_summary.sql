-- G3 bounded all-time Home summary. Local implementation only; no shared deployment approval.
set lock_timeout='5s';
set statement_timeout='30s';
create function public.get_home_summary()
returns jsonb language plpgsql stable security definer set search_path='' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_last jsonb;
begin
 select imfit_private.workout_summary(w) into v_last from public.workout_logs w
 where w.user_id=v_owner and w.deleted_at is null
 order by w.end_time desc,w.id desc limit 1;
 return jsonb_build_object('last_workout',v_last);
end;
$$;
revoke all on function public.get_home_summary() from public,anon;
grant execute on function public.get_home_summary() to authenticated;
notify pgrst,'reload schema';
