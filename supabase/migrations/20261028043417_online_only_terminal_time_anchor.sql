-- Keep completed pause accounting aligned with the canonical workout end time,
-- not the slightly later trigger execution time. Local-only deployment approval.
create or replace function imfit_private.close_terminal_pause()
returns trigger language plpgsql set search_path = '' as $$
declare v_stop timestamptz;
begin
  if old.schema_version=1 and old.status='PAUSED' and old.paused_at is not null
    and new.status in ('COMPLETED','CANCELLED','EXPIRED') then
    if new.status='COMPLETED' then
      select end_time into v_stop from public.workout_logs
        where id=new.completed_workout_id and user_id=new.user_id;
      if v_stop is null then perform imfit_private.fail('PT409','STATE_CONFLICT'); end if;
    elsif new.status='EXPIRED' then v_stop:=least(clock_timestamp(),old.expires_at);
    else v_stop:=clock_timestamp(); end if;
    new.total_paused_ms:=old.total_paused_ms+
      greatest(0,floor(extract(epoch from(v_stop-old.paused_at))*1000)::bigint);
    new.paused_at:=null;
  end if;
  return new;
end;
$$;
revoke all on function imfit_private.close_terminal_pause() from public,anon,authenticated;
