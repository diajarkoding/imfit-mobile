"""Strictly local, ephemeral credentials and disposable-user helpers for device proof."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.diajarkoding.imfit"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError("Fixture HTTP redirect refused")


class LocalFixtureBackend:
    def __init__(self, accounts=1):
        if accounts not in (1, 2):
            raise ValueError("One or two synthetic accounts required")
        self.count = accounts
        self.accounts = []
        self.secrets = []
        self.url = "http://127.0.0.1:54321"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def __enter__(self):
        sys.path.insert(0, str(ROOT / "supabase/tests"))
        from verify_local_baseline import local_sql, docker_path
        self.sql = local_sql()  # verifies local Docker before reading credentials
        env = dict(os.environ)
        env["PATH"] = str(Path(docker_path()).parent) + os.pathsep + env.get("PATH", "")
        status = subprocess.run([shutil.which("node"), str(ROOT / "node_modules/supabase/dist/supabase.js"),
                                 "status", "--output", "json"], cwd=ROOT, env=env,
                                capture_output=True, text=True, timeout=30)
        if status.returncode:
            raise RuntimeError("Local Supabase status failed (credential output withheld)")
        config = json.loads(status.stdout)
        parsed = urllib.parse.urlsplit(config["API_URL"])
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.port != 54321 or parsed.username or parsed.password or parsed.path not in ("", "/"):
            raise RuntimeError("Only the local Supabase API on port54321 is allowed")
        self.anon = config["ANON_KEY"]
        self.secrets.append(self.anon)
        try:
            for _ in range(self.count):
                email = "imfit-ready-" + uuid.uuid4().hex + "@example.invalid"
                password = uuid.uuid4().hex + "Aa!2"
                self.secrets.extend([email, password])
                data = self.request("/auth/v1/signup", {"email": email, "password": password, "data": {"name": "Local ready fixture"}})
                owner = str(uuid.UUID(data["user"]["id"]))
                self.accounts.append({"owner": owner, "email": email, "password": password, "access_token": data.get("access_token")})
                if not data.get("access_token"):
                    raise RuntimeError("Local synthetic signup did not issue a real session")
                self.secrets.extend([data["access_token"], data.get("refresh_token", "")])
            return self
        except BaseException:
            self.__exit__(*sys.exc_info())
            raise

    def request(self, path, body=None, token=None, endpoint=None):
        base = endpoint or self.url
        parsed = urllib.parse.urlsplit(base)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.username or parsed.password or parsed.path:
            raise ValueError("Fixture requests require an explicit loopback origin")
        headers = {"apikey": self.anon, "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method="POST" if body is not None else "GET")
        try:
            with self.opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError("Local fixture API returned HTTP " + str(error.code)) from None

    def rpc(self, name, body, account=0, endpoint=None):
        if not re.fullmatch(r"[a-z][a-z0-9_]*", name):
            raise ValueError("Invalid fixture RPC")
        return self.request("/rest/v1/rpc/" + name, body, self.accounts[account]["access_token"], endpoint)

    def instrumentation_args(self):
        first = self.accounts[0]
        args = {"readyEmail": first["email"], "readyPassword": first["password"],
                "readyOwner": first["owner"], "readyBackend": self.url, "readyAnonKey": self.anon}
        if len(self.accounts) == 2:
            other = self.accounts[1]
            args.update(readyOtherEmail=other["email"], readyOtherPassword=other["password"], readyOtherOwner=other["owner"])
        return args

    def redact(self, text):
        for value in sorted(filter(None, self.secrets), key=len, reverse=True):
            text = text.replace(value, "[REDACTED]")
        return text

    def __exit__(self, exc_type, exc, tb):
        failures = []
        for account in reversed(self.accounts):
            try:
                # Values are generated locally; both exact identity AND synthetic email must match.
                self.sql("delete from auth.users where id='" + str(uuid.UUID(account["owner"])) + "'::uuid and email='" + account["email"] + "';")
            except Exception:
                failures.append(account["owner"])
        self.accounts.clear()
        if failures:
            raise RuntimeError("Exact synthetic account cleanup failed: " + ",".join(failures))


class Device:
    def __init__(self, serial, user, output, redact=lambda text: text):
        if not re.fullmatch(r"emulator-\d+", serial) or not str(user).isdigit() or int(user) <= 0:
            raise ValueError("A disposable emulator and non-owner user are required")
        self.serial, self.user, self.output, self.redact = serial, str(int(user)), Path(output), redact
        self.prefix = ["adb", "-s", serial]
        self.commands = []

    def run(self, args, *, timeout=90, secret=False, binary=False):
        command = self.prefix + list(map(str, args))
        self.commands.append(self.redact(command) if isinstance(command, str) else [self.redact(x) for x in command])
        (self.output / "commands.json").write_text(json.dumps(self.commands, indent=2), encoding="utf-8")
        result = subprocess.run(command, capture_output=True, text=not binary, timeout=timeout)
        if result.returncode:
            detail = "" if binary else self.redact(result.stdout + result.stderr)
            raise RuntimeError("ADB command failed: " + str(args[:3]) + " " + detail)
        return result.stdout if binary else self.redact(result.stdout) if secret else result.stdout

    def validate(self):
        if self.run(["get-state"]).strip() != "device" or self.run(["shell", "getprop", "ro.kernel.qemu"]).strip() != "1":
            raise RuntimeError("Device is not a running emulator")
        users = self.run(["shell", "pm", "list", "users"])
        match = re.search(r"UserInfo\{" + self.user + r":([^:}]+):", users)
        if not match or not match.group(1).startswith("imfit-online-ready"):
            raise RuntimeError("User must be a separately-created imfit-online-ready fixture user")
        for user_id in re.findall(r"UserInfo\{(\d+):", users):
            packages = self.run(["shell", "pm", "list", "packages", "--user", user_id]).splitlines()
            if "package:" + PACKAGE in packages or "package:" + PACKAGE + ".test" in packages:
                raise RuntimeError("Refusing an app installed for any existing user; global APK replacement could affect nonfixture data")
        self.run(["shell", "am", "start-user", "-w", self.user])
        self.original_user = self.run(["shell", "am", "get-current-user"]).strip()
        if not self.original_user.isdigit():
            raise RuntimeError("Cannot determine foreground Android user")
        self.run(["shell", "am", "switch-user", self.user])
        import time
        until = time.monotonic() + 30
        while self.run(["shell", "am", "get-current-user"]).strip() != self.user:
            if time.monotonic() >= until:
                raise RuntimeError("Fixture user did not become foreground")
            time.sleep(.2)

    def exact_kill(self, pid):
        if not str(pid).isdigit():
            raise RuntimeError("Invalid exact PID")
        rows = [row.split() for row in self.run(["shell", "ps", "-A", "-o", "USER,PID,NAME"]).splitlines()]
        if not any(len(row) == 3 and row[0].startswith("u" + self.user + "_") and row[1:] == [str(pid), PACKAGE] for row in rows):
            raise RuntimeError("Exact process identity/user mismatch")
        self.run(["shell", "su", "0", "kill", "-9", str(pid)])

    def screenshot(self, name):
        (self.output / (name + ".png")).write_bytes(self.run(["exec-out", "screencap", "-p"], binary=True))


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()




class ResponseLossProxy:
    """Forward the fixed local API, dropping exactly one *committed* finish response."""
    port = 54322
    url = "http://127.0.0.1:54322"

    def __init__(self, allow_response_loss=True, port=54322):
        if not isinstance(port, int) or port < 0 or port > 65535 or port == 54321:
            raise ValueError("Invalid loopback proxy port")
        self.allow_response_loss = allow_response_loss
        self.port = port

    @staticmethod
    def _clock():
        import time
        from datetime import datetime, timezone
        return {"hostMonotonicNs": time.monotonic_ns(), "hostUtc": datetime.now(timezone.utc).isoformat()}

    def snapshot_trace(self):
        with self.lock:
            return [dict(event) for event in self.trace]

    def mark_phase(self, phase):
        if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", phase):
            raise ValueError("Invalid evidence phase")
        event = dict(self._clock(), kind="phase", phase=phase)
        with self.lock:
            self.trace.append(event)
        return dict(event)

    def arm_operation(self, operation):
        if not self.allow_response_loss:
            raise RuntimeError("Response loss disabled")
        if operation not in ("finish_active_session", "import_legacy_workout", "save_template_revision"):
            raise ValueError("Unsupported fixture mutation")
        with self.lock:
            if self.armed:
                raise RuntimeError("A response drop is already armed")
            self.state = {"committed": False, "dropped": False, "receipt": None}
            self.drop_operation = operation
            self.armed = True

    def set_phase(self, armed):
        with self.lock:
            self.phase_armed = bool(armed)

    def hold_before_upstream(self, operation):
        import threading
        if operation != "import_legacy_workout":
            raise ValueError("Only the selected import may be held")
        hold = {"arrived": threading.Event(), "release": threading.Event(), "discard": False}
        with self.lock:
            if operation in self.holds:
                raise RuntimeError("An import hold already exists")
            self.holds[operation] = hold
        return hold

    def release_hold(self, hold, discard=False):
        with self.lock:
            hold["discard"] = discard
        hold["release"].set()

    def __enter__(self):
        import http.client
        import http.server
        import socket
        import threading
        self.secret = uuid.uuid4().hex
        self.control_path = "/__fixture/" + self.secret
        self.state = {"committed": False, "dropped": False, "receipt": None}
        self.trace = []
        self.armed = False
        self.lock = threading.Lock()
        self.phase_armed = False
        self.holds = {}
        proxy = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            def log_message(self, *args):
                pass  # Never log Authorization, credentials, request bodies, or signed URLs.
            def handle_request(self):
                parsed = urllib.parse.urlsplit(self.path)
                if parsed.path.startswith("/__fixture/"):
                    if parsed.path == proxy.control_path + "/arm" and self.command == "POST":
                        if not proxy.allow_response_loss:
                            self.send_error(403); return
                        query = urllib.parse.parse_qs(parsed.query)
                        if set(query) != {"operation"} or len(query["operation"]) != 1:
                            self.send_error(400); return
                        try:
                            proxy.arm_operation(query["operation"][0])
                        except ValueError:
                            self.send_error(400); return
                        except RuntimeError:
                            self.send_error(409); return
                        data = b'{"armed":true}'
                    elif parsed.path == proxy.control_path + "/status" and self.command == "GET":
                        with proxy.lock:
                            data = json.dumps(proxy.state).encode()
                    elif parsed.path == proxy.control_path + "/phase" and self.command == "GET":
                        with proxy.lock:
                            data = json.dumps({"armed": proxy.phase_armed}).encode()
                    else:
                        self.send_error(404); return
                    self.send_response(200); self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data))); self.end_headers()
                    self.wfile.write(data); return
                if parsed.scheme or parsed.netloc or not parsed.path.startswith(("/auth/v1/", "/rest/v1/", "/storage/v1/")):
                    self.send_error(400); return
                connection = http.client.HTTPConnection("127.0.0.1", 54321, timeout=90)
                # Keep arrival evidence even if forwarding fails or its response never arrives.
                # Only public resource identifiers are retained; dynamic paths and all queries
                # are discarded before anything enters the trace.
                resource = parsed.path.removeprefix("/rest/v1/")
                if resource.startswith("rpc/"):
                    candidate = resource.removeprefix("rpc/")
                    operation = candidate if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", candidate) else "[redacted]"
                    safe_path = "/rest/v1/rpc/" + operation
                    category = "rpc"
                elif parsed.path.startswith("/rest/v1/"):
                    operation = resource if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", resource) else "[redacted]"
                    safe_path = "/rest/v1/" + operation
                    category = "table"
                else:
                    operation = "non-rpc"
                    safe_path = "/auth/v1/[redacted]" if parsed.path.startswith("/auth/v1/") else "/storage/v1/[redacted]"
                    category = "auth" if parsed.path.startswith("/auth/v1/") else "storage"
                event = dict(proxy._clock(), kind="http", category=category, method=self.command,
                             path=safe_path, operation=operation, status=None)
                with proxy.lock:
                    proxy.trace.append(event)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length > 4 * 1024 * 1024:
                        self.send_error(413); return
                    body = self.rfile.read(length) if length else None
                    with proxy.lock:
                        hold = proxy.holds.pop(operation, None)
                    if hold is not None:
                        with proxy.lock:
                            event["heldBeforeUpstream"] = True
                        hold["arrived"].set()
                        if not hold["release"].wait(180):
                            raise TimeoutError("Selected import hold timed out")
                        if hold["discard"]:
                            with proxy.lock:
                                event["discardedBeforeUpstream"] = True
                            self.close_connection = True
                            self.connection.shutdown(socket.SHUT_RDWR)
                            self.connection.close()
                            return
                    headers = {key: value for key, value in self.headers.items()
                               if key.lower() not in {"host", "connection", "transfer-encoding"}}
                    headers["Connection"] = "close"
                    connection.request(self.command, self.path, body, headers)
                    upstream = connection.getresponse()
                    data = upstream.read()
                    drop = False
                    with proxy.lock:
                        event["status"] = upstream.status
                        event["completedHostMonotonicNs"] = proxy._clock()["hostMonotonicNs"]
                        if proxy.armed and operation == proxy.drop_operation and 200 <= upstream.status < 300:
                            receipt = json.loads(data)
                            resource = "template_id" if operation == "save_template_revision" else "workout_id"
                            if not isinstance(receipt, dict) or not receipt.get("request_id") or not receipt.get(resource):
                                raise RuntimeError("Upstream mutation did not return a real receipt")
                            proxy.state = {"committed": True, "dropped": True,
                                           "receipt": {key: receipt[key] for key in ("request_id", resource)}}
                            event["responseDropped"] = True
                            proxy.armed = False
                            drop = True
                    if drop:
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    self.send_response(upstream.status)
                    for key, value in upstream.getheaders():
                        if key.lower() not in {"connection", "transfer-encoding", "content-length"}:
                            self.send_header(key, value)
                    self.send_header("Content-Length", str(len(data))); self.end_headers()
                    self.wfile.write(data)
                finally:
                    connection.close()
            do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = do_OPTIONS = handle_request
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.port = self.server.server_address[1]
        self.url = "http://127.0.0.1:" + str(self.port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def snapshot_sources():
    """Return deterministic source fingerprint and per-file evidence (no local.properties)."""
    paths = []
    extensions = {".kt", ".java", ".xml", ".kts", ".toml", ".pro", ".json", ".sql", ".py", ".properties"}
    for root in ("app/src", "app/schemas", "gradle", "supabase/migrations", "supabase/tests", "tools"):
        for path in (ROOT / root).rglob("*"):
            if path.is_file() and path.suffix in extensions and "__pycache__" not in path.parts:
                paths.append(path)
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "app/build.gradle.kts",
                 "app/proguard-rules.pro", "app/rest-timer-instrumentation-app.pro", "app/rest-timer-instrumentation-test.pro"):
        path = ROOT / name
        if path.is_file():
            paths.append(path)
    manifest = {path.relative_to(ROOT).as_posix(): sha256(path) for path in sorted(set(paths))}
    digest = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"sourceFingerprint": digest, "files": manifest}
