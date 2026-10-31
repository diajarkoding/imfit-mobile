-- Migration: Routine card primary muscle category based on working sets weighting
-- RPC get_template_page: includes primary_category_id computed via sets weighting

CREATE OR REPLACE FUNCTION public.get_template_page(p_cursor text DEFAULT NULL::text, p_limit integer DEFAULT 15)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_query jsonb;
  v_key jsonb;
  v_rows jsonb;
begin
  perform imfit_private.validate_limit(p_limit);
  v_query := jsonb_build_object('rpc', 'get_template_page', 'owner', v_owner);
  v_key := imfit_private.typed_cursor(p_cursor, v_query, true);

  select coalesce(jsonb_agg(jsonb_build_object(
    'data', jsonb_build_object(
      'id', t.id,
      'name', t.name,
      'revision', t.revision,
      'updated_at', t.updated_at,
      'exercise_count', coalesce(stats.exercise_count, 0),
      'primary_category_id', stats.primary_category_id
    ),
    'key', jsonb_build_array(t.updated_at, t.id)
  ) order by t.updated_at desc, t.id desc), '[]') into v_rows
  from (
    select * from public.workout_templates
    where user_id = v_owner and not is_deleted
      and (v_key is null or (updated_at, id) < ((v_key->>0)::timestamptz, (v_key->>1)::uuid))
    order by updated_at desc, id desc
    limit p_limit + 1
  ) t
  left join lateral (
    select
      count(*) as exercise_count,
      case
        -- 1. Routine kosong -> neutral artwork
        when count(*) = 0 then null
        -- 2. Cardio saja
        when count(*) filter (where e.muscle_category_id <> 8) = 0 then 8
        -- 3. Kombinasi Upper (1..5) dan Lower (6) signifikan -> Full Body (neutral)
        when coalesce(sum(te.sets) filter (where e.muscle_category_id in (1, 2, 3, 4, 5)), 0) >= 0.30 * nullif(sum(te.sets) filter (where e.muscle_category_id <> 8), 0)
         and coalesce(sum(te.sets) filter (where e.muscle_category_id = 6), 0) >= 0.25 * nullif(sum(te.sets) filter (where e.muscle_category_id <> 8), 0)
        then null
        -- 4. Kategori resistansi dominan berdasarkan working sets (tie-breaker: order_index terkecil)
        else (
          select e2.muscle_category_id
          from public.template_exercises te2
          join public.exercises e2 on e2.id = te2.exercise_id
          where te2.template_id = t.id and e2.muscle_category_id <> 8
          group by e2.muscle_category_id
          order by sum(te2.sets) desc, min(te2.order_index) asc
          limit 1
        )
      end as primary_category_id
    from public.template_exercises te
    join public.exercises e on e.id = te.exercise_id
    where te.template_id = t.id
  ) stats on true;

  return imfit_private.page_result(v_rows, v_query, p_limit);
end;
$function$;
