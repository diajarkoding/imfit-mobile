-- Local verification follow-up; shared-project deployment remains unapproved.
set lock_timeout='5s';
set statement_timeout='30s';

create function imfit_private.close_terminal_pause()
returns trigger language plpgsql set search_path = '' as $$
declare v_stop timestamptz;
begin
  if old.schema_version=1 and old.status='PAUSED' and old.paused_at is not null
    and new.status in ('COMPLETED','CANCELLED','EXPIRED') then
    v_stop:=case when new.status='EXPIRED' then least(clock_timestamp(),old.expires_at)
      else clock_timestamp() end;
    new.total_paused_ms:=old.total_paused_ms+
      greatest(0,floor(extract(epoch from(v_stop-old.paused_at))*1000)::bigint);
    new.paused_at:=null;
  end if;
  return new;
end;
$$;
create trigger close_terminal_pause before update of status on public.active_sessions
  for each row execute function imfit_private.close_terminal_pause();

create or replace function imfit_private.session_receipt(
  p_row public.active_sessions, p_request uuid, p_operation text
) returns jsonb language plpgsql stable set search_path = '' as $$
declare v_snapshot jsonb:=imfit_private.session_snapshot(p_row);
begin
  if octet_length(v_snapshot::text)>262144 then perform imfit_private.fail('PT400','VALIDATION'); end if;
  return jsonb_build_object('request_id',p_request,'operation',p_operation,'session_id',p_row.id,
    'version',p_row.version,'status',p_row.status,'committed_at',statement_timestamp(),
    'snapshot',v_snapshot)||case when p_row.status='COMPLETED' then
      jsonb_build_object('workout_id',p_row.completed_workout_id) else '{}'::jsonb end;
end;
$$;
revoke all on all functions in schema imfit_private from public,anon,authenticated;
