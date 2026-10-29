"""Local Auth/PostgREST, real two-connection races, and committed-response-loss tests.

Never accepts a remote URL or service-role key. Creates two synthetic local Auth
accounts and deletes only those exact fixture accounts/resources in finally.
"""

import argparse
import concurrent.futures
import copy
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
from datetime import datetime, timedelta, timezone
import urllib.error
import urllib.parse
import urllib.request
import uuid

from verify_local_baseline import MIGRATIONS, ROOT, docker_path, local_sql
from verify_local_backend import FINAL_MIGRATIONS


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Local test refuses HTTP redirect")


def run(phase):
    sql = local_sql()  # Refuse remote Docker contexts BEFORE obtaining local keys.
    versions = json.loads(sql("select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
    expected = MIGRATIONS + ["20261028000100", "20261028000200", "20261028000300"]
    if phase == "final":
        expected = FINAL_MIGRATIONS
    if versions != expected:
        raise RuntimeError("Unexpected migration inventory for transport phase")
    env = dict(os.environ)
    env["PATH"] = str(os.path.dirname(docker_path())) + os.pathsep + env.get("PATH", "")
    result = subprocess.run([
        shutil.which("node"), str(ROOT / "node_modules/supabase/dist/supabase.js"),
        "status", "--output", "json",
    ], cwd=ROOT, env=env, capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Local Supabase status unavailable; credential output omitted")
    status = json.loads(result.stdout)
    parsed = urllib.parse.urlsplit(status["API_URL"])
    if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1") or parsed.username or parsed.password:
        raise RuntimeError("Refusing non-loopback or unexpected API endpoint")
    port = parsed.port
    if not isinstance(port, int):
        raise RuntimeError("Missing local API port")
    base = f"http://127.0.0.1:{port}"
    anon = status["ANON_KEY"]
    del status  # Other local keys are never used, printed, or persisted.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    checks = []
    owners = []
    fixture = uuid.uuid4().hex
    started_day = datetime.now(timezone.utc).date()

    def check(condition, name):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    def request(path, body=None, token=None, method="POST", endpoint=base):
        headers = {"apikey": anon, "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(endpoint + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method=method)
        try:
            with opener.open(req, timeout=20) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if raw else None

    def rpc(name, body, token):
        return request("/rest/v1/rpc/" + name, body, token)

    def ok(name, body, token):
        code, value = rpc(name, body, token)
        check(code == 200, name + " HTTP 200")
        return value

    def race(*functions):
        barrier = threading.Barrier(len(functions))
        def call(function):
            barrier.wait(timeout=10)
            return function()
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(functions)) as pool:
            return list(pool.map(call, functions))

    def mutation(session_id, version, command):
        return {"p_request_id": str(uuid.uuid4()), "p_session_id": session_id,
                "p_expected_version": version, "p_command": command}

    def finish_body(session_id, version):
        return {"p_request_id": str(uuid.uuid4()), "p_session_id": session_id, "p_expected_version": version}

    def rejected(name, body, actor, status, message, label):
        code, error = rpc(name, body, actor)
        check(code == status and error["message"] == message, label)

    def lose_response(name, body, actor):
        proxy_result = {}

        class DropResponse(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
                try:
                    connection.request("POST", "/rest/v1/rpc/" + name, raw, {
                        "apikey": anon, "Authorization": "Bearer " + actor, "Content-Type": "application/json",
                    })
                    response = connection.getresponse()
                    proxy_result["status"] = response.status
                    proxy_result["body"] = json.loads(response.read())
                finally:
                    connection.close()
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()

        proxy = HTTPServer(("127.0.0.1", 0), DropResponse)
        proxy.timeout = 25
        thread = threading.Thread(target=proxy.handle_request, daemon=True)
        thread.start()
        lost_response = False
        try:
            request("/rest/v1/rpc/" + name, body, actor,
                    endpoint=f"http://127.0.0.1:{proxy.server_port}")
        except (http.client.RemoteDisconnected, urllib.error.URLError, ConnectionResetError):
            lost_response = True
        finally:
            thread.join(timeout=30)
            proxy.server_close()
        check(lost_response and not thread.is_alive() and proxy_result.get("status") == 200,
              name + " committed HTTP response deliberately lost")
        discovered = ok("get_operation_status", {"p_request_id": body["p_request_id"]}, actor)
        check(discovered["state"] == "COMMITTED" and discovered["receipt"]["result"] == proxy_result["body"],
              name + " targeted discovery after lost response")
        check(ok(name, body, actor) == proxy_result["body"], name + " exact replay after response loss")
        return proxy_result["body"]

    try:
        for number in range(2):
            email = f"imfit-fixture-{fixture}-{number}@example.invalid"
            code, user = request("/auth/v1/signup", {
                "email": email, "password": uuid.uuid4().hex + "Aa!2", "data": {"name": "Local fixture"},
            })
            check(code == 200 and isinstance(user.get("access_token"), str), "local Auth signup")
            user_id = str(uuid.UUID(user["user"]["id"]))
            owners.append((user_id, email))
            if number == 0:
                owner, token = user_id, user["access_token"]
            else:
                other_token = user["access_token"]

        code, denied = rpc("get_active_session", {}, anon)
        check(code in (401, 403) and denied["code"] == "42501", "anonymous RPC denied")
        catalog = ok("get_exercise_catalog_page", {"p_limit": 1}, token)
        exercise = catalog["data"][0]["id"]
        template_id = str(uuid.uuid4())
        template_request = {"p_request_id": str(uuid.uuid4()), "p_template_id": template_id,
            "p_expected_revision": None, "p_name": "Fixture " + fixture,
            "p_exercises": [{"exercise_id": exercise, "order_index": 0, "sets": 3, "reps": 10, "rest_seconds": 60}],
            "p_removed_exercise_ids": [], "p_is_deleted": False, "p_owner_id": owner}
        template = ok("save_template_revision", template_request, token)
        revision = template["revision"]
        check(ok("get_template_detail", {"p_template_id": template_id}, token)["revision"] == revision,
              "template detail revision")
        check(len(ok("get_template_page", {}, token)["data"]) == 1, "bounded template summary")
        invalid = dict(template_request, p_request_id=str(uuid.uuid4()), p_expected_revision=revision,
            p_exercises=[{"exercise_id": exercise, "order_index": 0, "sets": "3", "reps": 10, "rest_seconds": 60}])
        code, error = rpc("save_template_revision", invalid, token)
        check(code == 400 and error["code"] == "PT400" and error["message"] == "VALIDATION", "HTTP validation mapping")

        def create(session_id=None):
            session_id = session_id or str(uuid.uuid4())
            value = ok("create_active_session", {"p_request_id": str(uuid.uuid4()), "p_session_id": session_id,
                "p_template_id": template_id, "p_expected_template_revision": revision}, token)
            return session_id, value

        sid, created = create()
        e_id = created["snapshot"]["exercises"][0]["id"]
        s_id = created["snapshot"]["exercises"][0]["sets"][0]["id"]
        edit = mutation(sid, 1, {"type": "edit_set", "exercise_log_id": e_id, "set_id": s_id, "weight": "20.5", "reps": 10})
        edited = ok("update_active_session", edit, token)
        replay_edit = dict(edit, p_command=dict(edit["p_command"], weight="20.50"))
        check(ok("update_active_session", replay_edit, token) == edited, "HTTP decimal replay")
        code, error = rpc("update_active_session", mutation(sid, 1, {"type": "pause"}), token)
        check(code == 409 and error["code"] == "PT409" and error["message"] == "VERSION_CONFLICT"
              and json.loads(error["details"])["current_version"] == 2, "HTTP conflict mapping")
        check(not ok("get_active_session", {"p_session_id": sid}, other_token)["found"], "cross-owner session lookup")
        code, error = rpc("get_template_detail", {"p_template_id": template_id}, other_token)
        check(code == 404 and error["message"] == "RESOURCE_NOT_FOUND", "cross-owner detail mapping")

        outcomes = race(*[lambda body=mutation(sid, 2, {"type": "pause"}): rpc("update_active_session", body, token) for _ in range(2)])
        check(sorted(code for code, _ in outcomes) == [200, 409], "two-connection update CAS")
        paused_at = next(body for code, body in outcomes if code == 200)["snapshot"]["paused_at"]
        ok("update_active_session", mutation(sid, 3, {"type": "complete_set", "exercise_log_id": e_id, "set_id": s_id}), token)
        outcomes = race(*[lambda body=finish_body(sid, 4): rpc("finish_active_session", body, token) for _ in range(2)])
        check([code for code, _ in outcomes] == [200, 200] and outcomes[0][1]["workout_id"] == outcomes[1][1]["workout_id"],
              "two-connection finish canonical workout")
        if phase == "final":
            snapshot = outcomes[0][1]["snapshot"]
            check(snapshot["paused_at"] is None and snapshot["total_paused_ms"] > 0,
                  "terminal paused anchor closed and accumulated")
        detail = ok("get_workout_detail", {"p_workout_id": outcomes[0][1]["workout_id"]}, token)
        check(detail["total_volume"] == "205.00" and detail["total_sets"] == 1, "server decimal totals")
        if phase == "final":
            elapsed = datetime.fromisoformat(detail["end_time"]) - datetime.fromisoformat(paused_at)
            expected_pause_ms = (elapsed.days * 86400000000 + elapsed.seconds * 1000000 + elapsed.microseconds) // 1000
            check(outcomes[0][1]["snapshot"]["total_paused_ms"] == expected_pause_ms,
                  "terminal pause uses canonical workout end time")

        def prepared():
            session_id, value = create()
            eid = value["snapshot"]["exercises"][0]["id"]
            setid = value["snapshot"]["exercises"][0]["sets"][0]["id"]
            ok("update_active_session", mutation(session_id, 1, {"type": "edit_set", "exercise_log_id": eid,
                "set_id": setid, "weight": "10.00", "reps": 10}), token)
            ok("update_active_session", mutation(session_id, 2, {"type": "complete_set", "exercise_log_id": eid, "set_id": setid}), token)
            return session_id

        lost_sid = prepared()
        lost = finish_body(lost_sid, 3)
        lose_response("finish_active_session", lost, token)

        terminal_sid = prepared()
        finish = finish_body(terminal_sid, 3)
        cancel = finish_body(terminal_sid, 3)
        outcomes = race(lambda: rpc("finish_active_session", finish, token), lambda: rpc("cancel_active_session", cancel, token))
        check(sorted(code for code, _ in outcomes) == [200, 409], "two-connection finish/cancel single winner")
        check(next(body for code, body in outcomes if code == 409)["message"] == "SESSION_TERMINAL", "terminal loser resolves, not overwrites")

        start_bodies = [{"p_request_id": str(uuid.uuid4()), "p_session_id": str(uuid.uuid4()),
            "p_template_id": template_id, "p_expected_template_revision": revision} for _ in range(2)]
        outcomes = race(*[lambda body=body: rpc("create_active_session", body, token) for body in start_bodies])
        check(sorted(code for code, _ in outcomes) == [200, 409], "two-connection create single running owner")
        active = next(body for code, body in outcomes if code == 200)
        ok("cancel_active_session", finish_body(active["session_id"], 1), token)

        updates = [dict(template_request, p_request_id=str(uuid.uuid4()), p_expected_revision=revision,
                        p_name="Fixture race " + fixture + str(i)) for i in range(2)]
        outcomes = race(*[lambda body=body: rpc("save_template_revision", body, token) for body in updates])
        check(sorted(code for code, _ in outcomes) == [200, 409], "two-connection template CAS")
        duplicate = dict(template_request, p_request_id=str(uuid.uuid4()), p_template_id=str(uuid.uuid4()), p_name="Fixture duplicate " + fixture)
        outcomes = race(lambda: rpc("save_template_revision", duplicate, token), lambda: rpc("save_template_revision", duplicate, token))
        check([code for code, _ in outcomes] == [200, 200] and outcomes[0][1] == outcomes[1][1], "two-connection same request replay")
        code, error = rpc("create_active_session", dict(start_bodies[0], p_request_id=template_request["p_request_id"]), token)
        check(code == 409 and error["message"] == "IDEMPOTENCY_MISMATCH", "request identity bound across RPCs")

        today = datetime.now(timezone.utc).date()
        window = {"p_start_date": started_day.isoformat(), "p_end_date": (today + timedelta(days=1)).isoformat(), "p_timezone": "UTC"}
        summary = ok("get_progress_summary", window, token)
        check(summary["workout_count"] in (2, 3), "period summary includes committed workouts only")
        check(len(ok("get_workout_history_page", dict(window, p_limit=20), token)["data"]) == summary["workout_count"], "history wire envelope")
        if phase == "final":
            check(ok("get_home_summary", {}, token)["last_workout"]["total_volume"] is not None, "G3 all-time Home summary")
        markers = ok("get_workout_date_markers", window, token)["dates"]
        check(sum(item["workout_count"] for item in markers) == summary["workout_count"], "date markers wire envelope")
        check(ok("get_exercise_prefill", {"p_exercise_id": exercise, "p_input_defaults": {"sets": 3, "reps": 10}}, token)["source"] == "HISTORY", "prefill wire envelope")
        check(len(ok("list_recent_operations", {"p_from": (datetime.now(timezone.utc)-timedelta(hours=1)).isoformat(),
            "p_until": (datetime.now(timezone.utc)+timedelta(hours=1)).isoformat(), "p_limit": 100}, token)["data"]) > 10, "discovery wire envelope")

        code, _ = request("/rest/v1/active_sessions?id=eq." + active["session_id"], {"current_exercise_index": 0}, token, "PATCH")
        if phase == "compatibility":
            check(code == 204, "compatibility direct writer residual risk demonstrated")
        else:
            check(code == 403, "final direct session write denied")
            code, _ = request("/rest/v1/profiles?id=eq." + owner, {"name": "Fixture renamed"}, token, "PATCH")
            check(code == 204, "profile allowed column update")
            code, _ = request("/rest/v1/profiles?id=eq." + owner, {"email": "forged@example.invalid"}, token, "PATCH")
            check(code == 403, "profile auth-managed column denied")
            now = datetime.now(timezone.utc)
            code, _ = rpc("upsert_workout_aggregate", {"p_workout": {"id": str(uuid.uuid4()), "template_name": "Fixture bypass",
                "date": today.isoformat(), "start_time": (now-timedelta(minutes=1)).isoformat(), "end_time": now.isoformat()},
                "p_exercises": [], "p_sets": []}, token)
            check(code == 403, "legacy aggregate writer denied after cutoff")

            for receipt_count in (101, 899):
                sql("begin; set local role authenticated; select set_config('request.jwt.claim.sub','"
                    + owner + "',true); do $$ begin for i in 1.." + str(receipt_count)
                    + " loop perform public.finish_active_session(gen_random_uuid(),'"
                    + sid + "'::uuid,4); end loop; end $$; commit;")
                ready = ok("get_start_recovery_status", {}, token)
                check(ready["contract_version"] == 1 and ready["can_create"]
                      and ready["blockers"] == [] and not ready["has_more_blockers"],
                      "readiness independent of " + ("101" if receipt_count == 101 else "1000") + " terminal receipts")
            rejected("get_start_recovery_status", {"p_contract_version": 2}, token, 400,
                     "VALIDATION", "readiness contract fail closed")

            # Privileged setup is limited to this invocation's synthetic owner.
            legacy_ids = sorted(str(uuid.uuid4()) for _ in range(21))
            values = ",".join("('" + legacy_id + "'::uuid,'" + owner
                + "'::uuid,'" + template_id + "'::uuid,'Legacy fixture','EXPIRED',true,"
                + "'{\"legacy\":\"raw\",\"precision\":1.005}'::jsonb,clock_timestamp()-interval '1 day')"
                for legacy_id in legacy_ids)
            sql("begin; insert into public.active_sessions(id,user_id,template_id,template_name,"
                "status,review_required,session_data,expires_at) values " + values + "; commit;")
            ready = ok("get_start_recovery_status", {}, token)
            check(not ready["can_create"] and ready["has_more_blockers"] and len(ready["blockers"]) == 20
                  and [item["resource_id"] for item in ready["blockers"]] == legacy_ids[:20],
                  "readiness bounded preview with hidden twenty-first blocker")
            check(all(item["kind"] == "LEGACY_SESSION" and item["reason"] == "LEGACY_REVIEW_REQUIRED"
                      for item in ready["blockers"]), "legacy readiness typed blockers")
            check(ok("get_start_recovery_status", {}, other_token)["can_create"],
                  "readiness excludes foreign owner blockers")
            first_page = ok("get_legacy_recovery_page", {"p_limit": 1}, token)
            check(len(first_page["data"]) == 1 and first_page["page_info"]["has_more"],
                  "legacy recovery explicit bounded page")
            cursor = first_page["page_info"]["next_cursor"]
            rejected("get_legacy_recovery_page", {"p_limit": 1, "p_cursor": cursor},
                     other_token, 400, "INVALID_CURSOR", "legacy cursor owner binding")
            for invalid_limit in (0, 101):
                rejected("get_legacy_recovery_page", {"p_limit": invalid_limit}, token, 400,
                         "VALIDATION", "legacy page limit rejects " + str(invalid_limit))
            selected = first_page["data"][0]
            original_raw = selected["raw_source"]
            resolve = {"p_request_id": str(uuid.uuid4()), "p_session_id": selected["session_id"],
                "p_expected_source_fingerprint": selected["source_fingerprint"], "p_resolution": "KEEP_ARCHIVED"}
            rejected("resolve_legacy_session", dict(resolve, p_request_id=str(uuid.uuid4())),
                     other_token, 404, "RESOURCE_NOT_FOUND", "foreign legacy resolution hidden")
            rejected("resolve_legacy_session", dict(resolve, p_request_id=str(uuid.uuid4()),
                     p_expected_source_fingerprint="0" * 64), token, 409, "LEGACY_SOURCE_CHANGED",
                     "legacy fingerprint CAS rejects changed source")
            rejected("resolve_legacy_session", dict(resolve, p_request_id=str(uuid.uuid4()),
                     p_resolution="DELETE"), token, 400, "VALIDATION", "legacy archive only resolution")
            resolved = lose_response("resolve_legacy_session", resolve, token)
            check(resolved["session_id"] == selected["session_id"]
                  and resolved["source_fingerprint"] == selected["source_fingerprint"]
                  and resolved["resolution"] == "KEEP_ARCHIVED", "legacy archive receipt identity")
            archived = ok("get_legacy_recovery_page", {"p_limit": 100}, token)["data"]
            first_archived = next(item for item in archived if item["session_id"] == selected["session_id"])
            check(first_archived["resolution"] == "KEEP_ARCHIVED"
                  and first_archived["source_fingerprint"] == selected["source_fingerprint"]
                  and first_archived["raw_source"] == original_raw,
                  "legacy raw source and fingerprint preserved after archive timestamp trigger")
            rejected("resolve_legacy_session", dict(resolve, p_expected_source_fingerprint="f" * 64), token, 409,
                     "IDEMPOTENCY_MISMATCH", "legacy request body immutable")
            for item in archived:
                if item["resolution"] is None:
                    if item["session_id"] == legacy_ids[-1]:
                        hidden = ok("get_start_recovery_status", {}, token)
                        check(not hidden["can_create"] and not hidden["has_more_blockers"]
                              and [entry["resource_id"] for entry in hidden["blockers"]] == [legacy_ids[-1]],
                              "originally hidden blocker still prevents Start after preview resolved")
                    ok("resolve_legacy_session", {"p_request_id": str(uuid.uuid4()), "p_session_id": item["session_id"],
                        "p_expected_source_fingerprint": item["source_fingerprint"], "p_resolution": "KEEP_ARCHIVED"}, token)
            check(ok("get_start_recovery_status", {}, token)["can_create"],
                  "all archived legacy sessions unblock Start")
            changed_id = selected["session_id"]
            sql("begin; update public.active_sessions set session_data='{\"changed\":true}'::jsonb"
                " where id='" + changed_id + "'::uuid and user_id='" + owner + "'::uuid; commit;")
            check(not ok("get_start_recovery_status", {}, token)["can_create"],
                  "changed archived source invalidates fingerprint-bound acknowledgement")
            check(ok("resolve_legacy_session", resolve, token) == resolved,
                  "legacy replay precedes current source CAS")

            def import_body(schema=9):
                workout_id, exercise_log_id, completed_id, unfinished_id = [str(uuid.uuid4()) for _ in range(4)]
                timestamp = "2001-02-03T04:05:06.123000Z"
                source = {"schema_version": schema, "kind": "WORKOUT", "id": workout_id,
                    "fingerprint": hashlib.sha256(workout_id.encode()).hexdigest(), "mapping_version": 1}
                workout = {"id": workout_id, "user_id": owner, "template_id": None,
                    "template_name": "Legacy exact", "date": "2001-02-03",
                    "start_time": timestamp, "end_time": "2001-02-03T05:05:06.123000Z",
                    "total_volume": "3.015", "total_sets": 1, "total_reps": 3,
                    "deleted_at": None, "created_at": timestamp, "updated_at": timestamp}
                exercises = [{"id": exercise_log_id, "workout_log_id": workout_id, "exercise_id": exercise,
                    "exercise_name": catalog["data"][0]["name"], "muscle_category": "Historical",
                    "order_index": 0, "total_volume": "3.015", "total_sets": 1, "total_reps": 3,
                    "input_defaults": None, "created_at": timestamp, "updated_at": timestamp}]
                sets = [{"id": set_id, "exercise_log_id": exercise_log_id, "workout_log_id": workout_id,
                    "exercise_id": exercise, "set_number": number, "weight": weight, "reps": reps,
                    "is_completed": completed, "created_at": timestamp, "updated_at": timestamp}
                    for set_id, number, weight, reps, completed in [
                        (completed_id, 1, "1.005", 3, True), (unfinished_id, 2, "2.00001", 0, False)]]
                return {"p_request_id": str(uuid.uuid4()), "p_source": source, "p_workout": workout,
                    "p_exercises": exercises, "p_sets": sets}

            same_request = import_body()
            outcomes = race(lambda: rpc("import_legacy_workout", same_request, token),
                            lambda: rpc("import_legacy_workout", same_request, token))
            check([code for code, _ in outcomes] == [200, 200] and outcomes[0][1] == outcomes[1][1],
                  "two-connection legacy same request exact receipt")
            same_source = import_body()
            outcomes = race(lambda: rpc("import_legacy_workout", same_source, token),
                            lambda: rpc("import_legacy_workout", dict(same_source, p_request_id=str(uuid.uuid4())), token))
            check([code for code, _ in outcomes] == [200, 200]
                  and {result["outcome"] for _, result in outcomes} == {"IMPORTED", "MATCHED"}
                  and outcomes[0][1]["workout_id"] == outcomes[1][1]["workout_id"]
                  and outcomes[0][1]["id_mapping"] == outcomes[1][1]["id_mapping"],
                  "two-connection legacy source dedup single aggregate")

            imported_body = import_body()
            imported = lose_response("import_legacy_workout", imported_body, token)
            expected_mapping = {item["id"]: item["id"] for item in [
                imported_body["p_workout"], *imported_body["p_exercises"], *imported_body["p_sets"]]}
            check(imported["outcome"] == "IMPORTED" and imported["id_mapping"] == expected_mapping
                  and imported["exercise_count"] == 1 and imported["set_count"] == 2
                  and imported["source_fingerprint"] == imported_body["p_source"]["fingerprint"],
                  "legacy import preserves every UUID and incomplete set")
            check(ok("get_operation_status", {"p_request_id": imported_body["p_request_id"]}, other_token)["state"] == "NOT_FOUND",
                  "legacy import receipt discovery owner isolation")
            repeated = ok("import_legacy_workout", dict(imported_body, p_request_id=str(uuid.uuid4())), token)
            check(repeated["workout_id"] == imported["workout_id"]
                  and repeated["id_mapping"] == imported["id_mapping"]
                  and repeated["payload_digest"] == imported["payload_digest"],
                  "legacy new request same source retains workout mapping and server digest")
            persisted = json.loads(sql("begin read only; select jsonb_build_object("
                "'volume',(select total_volume::text from public.workout_logs where id='"
                + imported["workout_id"] + "'::uuid),'defaults',(select input_defaults from public.exercise_logs where id='"
                + imported_body["p_exercises"][0]["id"] + "'::uuid),'sets',(select jsonb_agg(jsonb_build_object("
                "'weight',weight::text,'completed',is_completed) order by set_number) from public.workout_sets where workout_log_id='"
                + imported["workout_id"] + "'::uuid)); rollback;"))
            check(persisted == {"volume": "3.015", "defaults": None, "sets": [
                {"weight": "1.005", "completed": True}, {"weight": "2.00001", "completed": False}]},
                "legacy exact decimals and null provenance persisted without rounding")
            for schema in (6, 7):
                check(ok("import_legacy_workout", import_body(schema), token)["outcome"] == "IMPORTED",
                      "legacy schema " + str(schema) + " import")
            altered = copy.deepcopy(imported_body)
            altered["p_workout"]["template_name"] = "Changed source body"
            rejected("import_legacy_workout", altered, token, 409, "IDEMPOTENCY_MISMATCH",
                     "legacy import immutable request digest")
            altered["p_request_id"] = str(uuid.uuid4())
            rejected("import_legacy_workout", altered, token, 409, "LEGACY_IMPORT_CONFLICT",
                     "legacy source key changed payload conflicts without overwrite")
            changed_fingerprint = copy.deepcopy(imported_body)
            changed_fingerprint["p_request_id"] = str(uuid.uuid4())
            changed_fingerprint["p_source"]["fingerprint"] = "f" * 64
            rejected("import_legacy_workout", changed_fingerprint, token, 409, "LEGACY_IMPORT_CONFLICT",
                     "legacy source key changed fingerprint conflicts")
            malformed = import_body()
            malformed["p_source"]["schema_version"] = 8
            rejected("import_legacy_workout", malformed, token, 400, "VALIDATION", "unsupported legacy schema blocked")
            malformed = import_body()
            malformed["p_workout"]["notes"] = "Not allowlisted"
            rejected("import_legacy_workout", malformed, token, 400, "VALIDATION", "legacy business field allowlist")
            malformed = import_body()
            malformed["p_workout"]["total_volume"] = "3.02"
            rejected("import_legacy_workout", malformed, token, 409, "LEGACY_REVIEW_REQUIRED",
                     "legacy totals mismatch requires review not rounding")
            foreign = import_body()
            foreign["p_workout"]["template_id"] = template_id
            foreign["p_workout"]["user_id"] = owners[1][0]
            rejected("import_legacy_workout", foreign, other_token, 409, "LEGACY_REVIEW_REQUIRED",
                     "legacy foreign template dependency requires review")
            rejected("import_legacy_workout", imported_body, other_token, 404, "RESOURCE_NOT_FOUND",
                     "legacy foreign owner UUID collision hidden")
            foreign_child = import_body()
            foreign_child["p_workout"]["user_id"] = owners[1][0]
            foreign_child["p_sets"][0]["id"] = imported_body["p_sets"][0]["id"]
            rejected("import_legacy_workout", foreign_child, other_token, 404, "RESOURCE_NOT_FOUND",
                     "legacy foreign child UUID collision hidden")
            collision = import_body()
            collision["p_sets"][1]["id"] = imported_body["p_sets"][0]["id"]
            rejected("import_legacy_workout", collision, token, 409, "LEGACY_IMPORT_CONFLICT",
                     "legacy partial child collision fails atomically")
            check(ok("get_operation_status", {"p_request_id": collision["p_request_id"]}, token)["state"] == "NOT_FOUND",
                  "legacy failed collision writes no receipt")
            absent = json.loads(sql("begin read only; select jsonb_build_object("
                "'parent',exists(select 1 from public.workout_logs where id='" + collision["p_workout"]["id"] + "'::uuid),"
                "'child',exists(select 1 from public.exercise_logs where id='" + collision["p_exercises"][0]["id"] + "'::uuid),"
                "'registry',exists(select 1 from imfit_private.legacy_workout_imports where user_id='" + owner
                + "'::uuid and source_id='" + collision["p_source"]["id"] + "'::uuid)); rollback;"))
            check(not any(absent.values()), "legacy child collision rolls back parent children and registry")
            for name, body in [
                ("get_start_recovery_status", {}), ("get_legacy_recovery_page", {}),
                ("resolve_legacy_session", resolve), ("import_legacy_workout", imported_body)]:
                code, error = rpc(name, body, anon)
                check(code in (401, 403) and error["code"] == "42501", name + " anonymous denied")
            code, _ = request("/rest/v1/workout_logs", imported_body["p_workout"], token)
            check(code == 403, "legacy import does not reopen direct workout writer")
    finally:
        # Scope is exactly the synthetic accounts this invocation created.
        for user_id, email in owners:
            sql("begin; delete from auth.users where id='" + user_id + "'::uuid and email='" + email + "'; commit;")
    print(json.dumps({"status": "PASS", "phase": phase, "checks_passed": len(checks), "checks": checks,
        "cleanup": "exact synthetic Auth accounts and dependent fixtures removed",
        "scope": "local Auth/PostgREST/concurrency/response-loss; not Android/device or shared-project deployment"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=["compatibility", "final"], required=True)
    args = parser.parse_args()
    try:
        run(args.phase)
    except (AssertionError, RuntimeError, subprocess.TimeoutExpired, urllib.error.URLError,
            json.JSONDecodeError, KeyError, IndexError, ValueError) as error:
        print(f"FAIL/BLOCKED local transport: {error}", file=sys.stderr)
        sys.exit(1)
