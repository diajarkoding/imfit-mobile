-- EXPLICIT COMPATIBILITY CUTOFF. Tested locally only; NOT approved for deployment
-- to the shared project. Release/client compatibility must be reviewed first.
set lock_timeout = '5s';
set statement_timeout = '30s';

revoke all privileges on table public.profiles,public.muscle_categories,public.exercises,
  public.workout_templates,public.template_exercises,public.workout_logs,
  public.exercise_logs,public.workout_sets,public.active_sessions,public.template_write_receipts
  from public,anon,authenticated;

-- A table-level REVOKE must not leave historical column grants as a bypass.
do $$
declare v_table text; v_columns text;
begin
  foreach v_table in array array['profiles','muscle_categories','exercises',
    'workout_templates','template_exercises','workout_logs','exercise_logs',
    'workout_sets','active_sessions','template_write_receipts'] loop
    select string_agg(format('%I',attname),',' order by attnum) into v_columns
    from pg_catalog.pg_attribute where attrelid=format('public.%I',v_table)::regclass
      and attnum>0 and not attisdropped;
    execute format('revoke all privileges (%s) on table public.%I from public,anon,authenticated',
      v_columns,v_table);
  end loop;
end;
$$;

grant select on public.muscle_categories,public.exercises,public.profiles to authenticated;
grant update(name,birth_date,avatar_url) on public.profiles to authenticated;
drop policy profiles_insert_own on public.profiles;
drop policy exercises_select_authenticated on public.exercises;
create policy exercises_select_authenticated on public.exercises for select to authenticated using(is_active);

revoke all on function public.upsert_workout_aggregate(jsonb,jsonb,jsonb) from public,anon,authenticated;
revoke all on function public.upsert_template_aggregate(jsonb,jsonb) from public,anon,authenticated;
revoke all on schema imfit_private from public,anon,authenticated;
revoke all on all tables in schema imfit_private from public,anon,authenticated;
revoke all on all functions in schema imfit_private from public,anon,authenticated;
notify pgrst,'reload schema';
