"""Local Auth/PostgREST, real two-connection races, and committed-response-loss tests.

Never accepts a remote URL or service-role key. Creates two synthetic local Auth
accounts and deletes only those exact fixture accounts/resources in finally.
"""

import argparse
import concurrent.futures
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


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Local test refuses HTTP redirect")


def run(phase):
    sql = local_sql()  # Refuse remote Docker contexts BEFORE obtaining local keys.
    versions = json.loads(sql("select jsonb_agg(version order by version) from supabase_migrations.schema_migrations;"))
    expected = MIGRATIONS + ["20261028000100", "20261028000200", "20261028000300"]
    if phase == "final":
        expected.extend(["20261028000400", "20261028000500", "20261028000600", "20261028042753", "20261028043417"])
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
        proxy_result = {}

        class DropResponse(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                connection = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
                try:
                    connection.request("POST", "/rest/v1/rpc/finish_active_session", body, {
                        "apikey": anon, "Authorization": "Bearer " + token, "Content-Type": "application/json",
                    })
                    response = connection.getresponse()
                    proxy_result["status"] = response.status
                    proxy_result["body"] = json.loads(response.read())
                finally:
                    connection.close()
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()  # Server has committed; client sees no status/body.

        proxy = HTTPServer(("127.0.0.1", 0), DropResponse)
        proxy.timeout = 25
        thread = threading.Thread(target=proxy.handle_request, daemon=True)
        thread.start()
        lost_response = False
        try:
            request("/rest/v1/rpc/finish_active_session", lost, token,
                    endpoint=f"http://127.0.0.1:{proxy.server_port}")
        except (http.client.RemoteDisconnected, urllib.error.URLError, ConnectionResetError):
            lost_response = True
        finally:
            thread.join(timeout=30)
            proxy.server_close()
        check(lost_response and not thread.is_alive() and proxy_result.get("status") == 200, "committed HTTP response deliberately lost")
        discovered = ok("get_operation_status", {"p_request_id": lost["p_request_id"]}, token)
        check(discovered["state"] == "COMMITTED" and discovered["receipt"]["result"]["workout_id"] == proxy_result["body"]["workout_id"],
              "discovery after lost response")
        check(ok("finish_active_session", lost, token) == proxy_result["body"], "retry after lost response exact receipt")

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
