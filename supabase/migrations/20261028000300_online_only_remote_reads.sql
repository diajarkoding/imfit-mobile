-- F1b local-first bounded remote reads. No shared-project deployment authorized.
set lock_timeout = '5s';
set statement_timeout = '30s';
create index workout_history_keyset on public.workout_logs(user_id,end_time desc,id desc) where deleted_at is null;
create index catalog_name_keyset on public.exercises((lower(name) collate "C"),(id collate "C")) where is_active;

create function imfit_private.validate_window(p_start date,p_end date,p_timezone text)
returns void language plpgsql stable set search_path = '' as $$
begin
  if p_start is null or p_end is null or p_end<=p_start or p_end-p_start>366
     or p_timezone is null or not exists(select 1 from pg_catalog.pg_timezone_names
       where name=p_timezone and (name='UTC' or position('/' in name)>0)) then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
end;
$$;
create function imfit_private.validate_limit(p_limit integer)
returns void language plpgsql immutable set search_path = '' as $$
begin
  if p_limit is null or p_limit not between 1 and 100 then perform imfit_private.fail('PT400','VALIDATION'); end if;
end;
$$;
create function imfit_private.page_result(p_rows jsonb,p_query jsonb,p_limit integer)
returns jsonb language plpgsql immutable set search_path = '' as $$
declare v_more boolean:=jsonb_array_length(p_rows)>p_limit; v_visible jsonb; v_data jsonb;
begin
  v_visible:=case when v_more then p_rows-p_limit else p_rows end;
  select coalesce(jsonb_agg(item->'data' order by ord),'[]') into v_data
    from jsonb_array_elements(v_visible) with ordinality a(item,ord);
  return jsonb_build_object('data',v_data,'page_info',jsonb_build_object('limit',p_limit,
    'has_more',v_more,'next_cursor',case when v_more then
      imfit_private.cursor_encode(p_query,v_visible->-1->'key') end));
end;
$$;
create function imfit_private.typed_cursor(p_cursor text,p_query jsonb,p_timestamp boolean)
returns jsonb language plpgsql immutable set search_path = '' as $$
declare v_key jsonb; v_time timestamptz; v_id uuid;
begin
  v_key:=imfit_private.cursor_decode(p_cursor,p_query);
  if v_key is null then return null; end if;
  if jsonb_array_length(v_key)<>2 or jsonb_typeof(v_key->0) is distinct from 'string'
    or jsonb_typeof(v_key->1) is distinct from 'string' then perform imfit_private.fail('PT400','INVALID_CURSOR'); end if;
  if p_timestamp then
    begin v_time:=(v_key->>0)::timestamptz; v_id:=(v_key->>1)::uuid;
    exception when invalid_text_representation or invalid_datetime_format or datetime_field_overflow then
      perform imfit_private.fail('PT400','INVALID_CURSOR');
    end;
  end if;
  return v_key;
end;
$$;
create function imfit_private.workout_summary(p_row public.workout_logs)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('id',p_row.id,'template_id',p_row.template_id,'template_name',p_row.template_name,
    'start_time',p_row.start_time,'end_time',p_row.end_time,
    'duration_seconds',floor(extract(epoch from(p_row.end_time-p_row.start_time)))::bigint,
    'total_volume',p_row.total_volume::text,'total_sets',p_row.total_sets,'total_reps',p_row.total_reps);
$$;

create function public.get_workout_history_page(
 p_start_date date,p_end_date date,p_timezone text,p_cursor text default null,p_limit integer default 20
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_query jsonb; v_key jsonb; v_rows jsonb;
begin
  perform imfit_private.validate_window(p_start_date,p_end_date,p_timezone);
  perform imfit_private.validate_limit(p_limit);
  v_query:=jsonb_build_object('rpc','get_workout_history_page','owner',v_owner,
    'start',p_start_date,'end',p_end_date,'timezone',p_timezone);
  v_key:=imfit_private.typed_cursor(p_cursor,v_query,true);
  select coalesce(jsonb_agg(jsonb_build_object('data',imfit_private.workout_summary(w),
    'key',jsonb_build_array(w.end_time,w.id)) order by w.end_time desc,w.id desc),'[]') into v_rows
  from(select * from public.workout_logs where user_id=v_owner and deleted_at is null
    and start_time >= (p_start_date::timestamp at time zone p_timezone)
    and start_time < (p_end_date::timestamp at time zone p_timezone)
    and (v_key is null or (end_time,id)<((v_key->>0)::timestamptz,(v_key->>1)::uuid))
    order by end_time desc,id desc limit p_limit+1) w;
  return imfit_private.page_result(v_rows,v_query,p_limit);
end;
$$;

create function public.get_exercise_catalog_page(
 p_search text default '',p_category_id integer default null,p_cursor text default null,p_limit integer default 25
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_query jsonb; v_key jsonb; v_rows jsonb; v_search text;
begin
  perform imfit_private.validate_limit(p_limit);
  if p_search is null or length(btrim(p_search))>100 or (p_category_id is not null
    and not exists(select 1 from public.muscle_categories where id=p_category_id)) then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;
  v_search:=lower(btrim(p_search));
  v_query:=jsonb_build_object('rpc','get_exercise_catalog_page','owner',v_owner,'search',v_search,'category',p_category_id);
  v_key:=imfit_private.typed_cursor(p_cursor,v_query,false);
  select coalesce(jsonb_agg(jsonb_build_object('data',jsonb_build_object('id',e.id,'muscle_category_id',e.muscle_category_id,
    'name',e.name,'image_url',e.image_url,'difficulty',e.difficulty,'equipment',e.equipment),
    'key',jsonb_build_array(lower(e.name),e.id)) order by lower(e.name) collate "C",e.id collate "C"),'[]') into v_rows
  from(select * from public.exercises where is_active and (p_category_id is null or muscle_category_id=p_category_id)
    and strpos(lower(name),v_search)>0 and (v_key is null or
      (lower(name) collate "C",id collate "C")>((v_key->>0) collate "C",(v_key->>1) collate "C"))
    order by lower(name) collate "C",id collate "C" limit p_limit+1) e;
  return imfit_private.page_result(v_rows,v_query,p_limit);
end;
$$;

create function public.get_template_page(p_cursor text default null,p_limit integer default 15)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_query jsonb; v_key jsonb; v_rows jsonb;
begin
  perform imfit_private.validate_limit(p_limit);
  v_query:=jsonb_build_object('rpc','get_template_page','owner',v_owner);
  v_key:=imfit_private.typed_cursor(p_cursor,v_query,true);
  select coalesce(jsonb_agg(jsonb_build_object('data',jsonb_build_object('id',t.id,'name',t.name,'revision',t.revision,
    'updated_at',t.updated_at,'exercise_count',(select count(*) from public.template_exercises where template_id=t.id)),
    'key',jsonb_build_array(t.updated_at,t.id)) order by t.updated_at desc,t.id desc),'[]') into v_rows
  from(select * from public.workout_templates where user_id=v_owner and not is_deleted
    and (v_key is null or (updated_at,id)<((v_key->>0)::timestamptz,(v_key->>1)::uuid))
    order by updated_at desc,id desc limit p_limit+1) t;
  return imfit_private.page_result(v_rows,v_query,p_limit);
end;
$$;

create function public.get_template_detail(p_template_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_result jsonb;
begin
  select jsonb_build_object('id',t.id,'name',t.name,'revision',t.revision,'updated_at',t.updated_at,
    'exercises',(select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'exercise_id',e.exercise_id,
      'order_index',e.order_index,'sets',e.sets,'reps',e.reps,'rest_seconds',e.rest_seconds)
      order by e.order_index,e.id),'[]') from public.template_exercises e where e.template_id=t.id))
  into v_result from public.workout_templates t where t.id=p_template_id and t.user_id=v_owner and not t.is_deleted;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  return v_result;
end;
$$;

create function public.get_workout_detail(p_workout_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_result jsonb;
begin
  select imfit_private.workout_summary(w)||jsonb_build_object('exercise_logs',(
    select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'exercise_id',e.exercise_id,'exercise_name',e.exercise_name,
      'muscle_category',e.muscle_category,'order_index',e.order_index,'input_defaults',e.input_defaults,
      'total_volume',e.total_volume::text,'total_sets',e.total_sets,'total_reps',e.total_reps,'sets',(
        select coalesce(jsonb_agg(jsonb_build_object('id',s.id,'set_number',s.set_number,'weight',s.weight::text,
          'reps',s.reps,'is_completed',s.is_completed) order by s.set_number,s.id),'[]')
        from public.workout_sets s where s.exercise_log_id=e.id and s.is_completed))
      order by e.order_index,e.id),'[]') from public.exercise_logs e where e.workout_log_id=w.id))
  into v_result from public.workout_logs w where w.id=p_workout_id and w.user_id=v_owner and w.deleted_at is null;
  if not found then perform imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); end if;
  return v_result;
end;
$$;

create function public.get_progress_summary(p_start_date date,p_end_date date,p_timezone text)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_result jsonb;
begin
  perform imfit_private.validate_window(p_start_date,p_end_date,p_timezone);
  select jsonb_build_object('start_date',p_start_date,'end_date',p_end_date,'timezone',p_timezone,
    'workout_count',count(*),'total_volume',coalesce(sum(total_volume),0)::text,
    'total_sets',coalesce(sum(total_sets),0),'total_reps',coalesce(sum(total_reps),0),
    'duration_seconds',coalesce(sum(floor(extract(epoch from(end_time-start_time)))),0)::bigint)
  into v_result from public.workout_logs where user_id=v_owner and deleted_at is null
    and start_time >= (p_start_date::timestamp at time zone p_timezone)
    and start_time < (p_end_date::timestamp at time zone p_timezone);
  return v_result;
end;
$$;

create function public.get_workout_date_markers(p_start_date date,p_end_date date,p_timezone text)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_owner uuid:=imfit_private.require_owner(); v_dates jsonb;
begin
  perform imfit_private.validate_window(p_start_date,p_end_date,p_timezone);
  select coalesce(jsonb_agg(jsonb_build_object('date',d.workout_date,'workout_count',d.workout_count) order by d.workout_date),'[]')
  into v_dates from(select (start_time at time zone p_timezone)::date as workout_date,count(*) as workout_count
    from public.workout_logs where user_id=v_owner and deleted_at is null
      and start_time >= (p_start_date::timestamp at time zone p_timezone)
      and start_time < (p_end_date::timestamp at time zone p_timezone)
    group by (start_time at time zone p_timezone)::date) d;
  return jsonb_build_object('start_date',p_start_date,'end_date',p_end_date,'timezone',p_timezone,'dates',v_dates);
end;
$$;

create function public.get_exercise_prefill(p_exercise_id text,p_input_defaults jsonb)
returns jsonb language sql stable security definer set search_path = '' as $$
  select imfit_private.prefill(imfit_private.require_owner(),p_exercise_id,p_input_defaults);
$$;

revoke all on function public.get_workout_history_page(date,date,text,text,integer) from public,anon;
revoke all on function public.get_exercise_catalog_page(text,integer,text,integer) from public,anon;
revoke all on function public.get_template_page(text,integer) from public,anon;
revoke all on function public.get_template_detail(uuid) from public,anon;
revoke all on function public.get_workout_detail(uuid) from public,anon;
revoke all on function public.get_progress_summary(date,date,text) from public,anon;
revoke all on function public.get_workout_date_markers(date,date,text) from public,anon;
revoke all on function public.get_exercise_prefill(text,jsonb) from public,anon;
grant execute on function public.get_workout_history_page(date,date,text,text,integer) to authenticated;
grant execute on function public.get_exercise_catalog_page(text,integer,text,integer) to authenticated;
grant execute on function public.get_template_page(text,integer) to authenticated;
grant execute on function public.get_template_detail(uuid) to authenticated;
grant execute on function public.get_workout_detail(uuid) to authenticated;
grant execute on function public.get_progress_summary(date,date,text) to authenticated;
grant execute on function public.get_workout_date_markers(date,date,text) to authenticated;
grant execute on function public.get_exercise_prefill(text,jsonb) to authenticated;
revoke all on all functions in schema imfit_private from public,anon,authenticated;
notify pgrst,'reload schema';
