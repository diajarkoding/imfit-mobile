-- RAISE helpers must not be constant-folded while planning an unexecuted branch.
-- STABLE is sufficient for read-only validation and avoids eager IMMUTABLE calls.
alter function imfit_private.fail(text,text,jsonb) stable;
alter function imfit_private.json_int(jsonb,integer,integer) stable;
alter function imfit_private.json_uuid(jsonb) stable;
alter function imfit_private.validate_limit(integer) stable;
