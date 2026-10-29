#!/usr/bin/env python3
"""Capture real-local-backend timer evidence on disposable, isolated emulator users.

Offline scenarios disconnect only AFTER the fixture confirms the remote session and
arms its timer. Cold proofs SIGKILL the exact fixture PID, never force-stop the app.
"""
import argparse
from contextlib import nullcontext
import json
import re
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

FIELDS = re.compile(r"([A-Za-z][A-Za-z0-9_]*)=([^\s]+)")
SCENARIOS = ("warm", "warm-offline", "cold-offline", "cold-online")


def trace_events(text):
    return [dict(FIELDS.findall(line)) for line in text.splitlines() if "IMFITRest" in line]


def summarize(text):
    groups = {}
    for fields in trace_events(text):
        identity = fields.get("restInstanceId")
        if identity and identity != "null":
            groups.setdefault((identity, fields.get("generation")), []).append(fields)
    results = []
    for (identity, generation), events in groups.items():
        def first(name):
            return next((e for e in events if e.get("event") == name), None)
        schedule = first("alarm_scheduled")
        receiver = first("receiver_received")
        commit = first("expired_committed")
        notify = next((e for e in events if e.get("event") == "notify_result" and e.get("success") == "true"), None)
        request = next((e for e in reversed(events) if e.get("event") == "expiry_requested" and
                        commit and e.get("source") == commit.get("source") and
                        int(e.get("now", "0")) <= int(commit["now"])), None)
        deadline = next((int(e["deadlineElapsedMs"]) for e in events
                         if e.get("deadlineElapsedMs", "null") != "null"), None)
        def lateness(event):
            return int(event["now"]) - deadline if event and deadline is not None else None
        results.append(dict(restInstanceId=identity, generation=generation,
                            appliedMethod=schedule.get("appliedMethod") if schedule else None,
                            deadlineElapsedMs=deadline, expirySource=commit.get("source") if commit else None,
                            alarmLatenessMs=lateness(receiver), commitLatenessMs=lateness(commit),
                            notificationLatenessMs=lateness(notify),
                            processingDelayMs=int(commit["now"]) - int(request["now"]) if commit and request else None,
                            commitCount=sum(e.get("event") == "expired_committed" for e in events)))
    return results


def analyze_cold(text, armed, scenario):
    """Fail closed on missing proof, cross-generation evidence, or deadline reset."""
    if scenario not in ("cold-offline", "cold-online"):
        raise ValueError("A cold scenario is required")
    identity = (armed["restInstanceId"], armed["generation"])
    deadline = int(armed["deadlineElapsedMs"])
    events = [e for e in trace_events(text)
              if (e.get("restInstanceId"), e.get("generation")) == identity]
    snapshots = [e for e in events if e.get("event") == "test_snapshot"]
    phase = "offline_verified" if scenario == "cold-offline" else "online_verified"
    verified = next((e for e in snapshots if e.get("phase") == phase and e.get("scenario") == scenario), None)
    if not verified:
        raise AssertionError("Missing cold verification snapshot")
    if verified.get("pid") == armed["pid"] or not verified.get("pid", "").isdigit():
        raise AssertionError("Cold verification did not run in a new PID")
    if int(verified.get("deadlineElapsedMs", "-1")) != deadline:
        raise AssertionError("Cold recovery reset the original deadline")
    # Detect a restarted timer even if its final snapshot is made to look correct.
    for event in events:
        if event.get("event") in ("alarm_scheduled", "rest_started", "test_snapshot"):
            value = event.get("deadlineElapsedMs", "null")
            if value != "null" and int(value) != deadline:
                raise AssertionError("Cold recovery changed the original anchor")
    commits = [e for e in events if e.get("event") == "expired_committed"]
    if scenario == "cold-offline":
        if int(verified.get("now", "0")) < deadline + 3000:
            raise AssertionError("Cold offline observation did not pass the deadline")
        if verified.get("anchorRecoverable") != "true" or verified.get("status") != "RUNNING":
            raise AssertionError("Cold offline anchor is not recoverable")
        forbidden = [e for e in events if e.get("event") in ("expired_committed", "audio_started") or
                     (e.get("event") == "notify_result" and e.get("success") == "true")]
        if forbidden:
            raise AssertionError("Cold offline expiry, completion notification, or audio was emitted")
    else:
        verification = next((e for e in snapshots if e.get("phase") == "session_verified" and
                             e.get("scenario") == scenario), None)
        if not verification or verification.get("pid") != verified["pid"]:
            raise AssertionError("Missing authoritative session verification in the new process")
        if int(verification.get("deadlineElapsedMs", "-1")) != deadline:
            raise AssertionError("Online verification changed the original deadline")
        if len(commits) != 1:
            raise AssertionError("Cold online recovery must commit expiry exactly once")
        if events.index(commits[0]) <= events.index(verification):
            raise AssertionError("Cold expiry committed before authoritative verification")
        if int(commits[0]["now"]) < deadline:
            raise AssertionError("Cold online expiry preceded the original deadline")
    return dict(scenario=scenario, originalDeadlineElapsedMs=deadline,
                oldPid=armed["pid"], newPid=verified["pid"], commitCount=len(commits), verified=verified)


def analyze_warm_transport(trace, hot_peeks, after_deadline):
    """Require an actual minute of running observation, not just a hot-loop count."""
    for key in ("restInstanceId", "generation", "runId"):
        if hot_peeks.get(key) != after_deadline.get(key):
            raise AssertionError("Transport observation changed timer identity")
    device_duration = int(after_deadline["now"]) - int(hot_peeks["now"])
    if int(hot_peeks.get("peekCount", "0")) != 10000 or device_duration < 60000:
        raise AssertionError("Missing 10,000 peeks plus 60 seconds of actual running observation")
    boundaries = {}
    for phase in ("hot_peeks", "after_deadline"):
        matches = [event for event in trace if event.get("kind") == "phase" and event.get("phase") == phase]
        if len(matches) != 1:
            raise AssertionError("Missing or ambiguous transport phase: " + phase)
        boundaries[phase] = int(matches[0]["hostMonotonicNs"])
    start, end = boundaries["hot_peeks"], boundaries["after_deadline"]
    if end - start < 60_000_000_000:
        raise AssertionError("Host did not observe 60 seconds of the running timer")
    requests = [event for event in trace if event.get("kind") == "http"]
    def business(event):
        return event.get("category") in ("rpc", "table", "storage")
    setup_count = sum(business(event) and int(event["hostMonotonicNs"]) < start for event in requests)
    if not setup_count:
        raise AssertionError("No setup business traffic traversed the observation proxy")
    observed = [event for event in requests if business(event) and
                start <= int(event["hostMonotonicNs"]) <= end]
    if observed:
        raise AssertionError("Business HTTP occurred while the confirmed timer was running")
    return dict(peekCount=10000, deviceObservationDurationMs=device_duration,
                hostObservationDurationMs=(end - start) // 1_000_000,
                businessRequestCount=0, setupBusinessRequestCount=setup_count,
                excludedAuthRequestCount=sum(event.get("category") == "auth" and
                    start <= int(event["hostMonotonicNs"]) <= end for event in requests),
                startHostMonotonicNs=start, endHostMonotonicNs=end)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--class-name", default="com.diajarkoding.imfit.core.timer.RestTimerBackgroundTest")
    parser.add_argument("--analyze", type=Path)
    parser.add_argument("--package", choices=["com.diajarkoding.imfit.demo", "com.diajarkoding.imfit"], default="com.diajarkoding.imfit.demo")
    parser.add_argument("--exact", choices=["allow", "deny"])
    parser.add_argument("--scenario", choices=SCENARIOS, default="warm")
    parser.add_argument("--user", default="0")
    args = parser.parse_args()
    if not args.analyze and (not args.serial.startswith("emulator-") or not args.user.isdigit()):
        parser.error("This runner requires a disposable emulator and a numeric Android user")
    production = args.package == "com.diajarkoding.imfit"
    if production and not args.analyze and int(args.user) == 0:
        parser.error("Production fixtures require an isolated non-owner Android user")
    args.output.mkdir(parents=True, exist_ok=False)
    if args.analyze:
        (args.output / "timings.json").write_text(json.dumps(summarize(args.analyze.read_text()), indent=2), encoding="utf-8")
        return 0
    metadata = dict(startedUtc=datetime.now(timezone.utc).isoformat(), serial=args.serial,
                    testClass=args.class_name, package=args.package, user=args.user, scenario=args.scenario,
                    status="FAIL")
    adb = ["adb", "-s", args.serial]
    backend = None
    proxy = None
    log = None
    reader = None
    reader_errors = []
    state = {}
    state_lock = threading.Lock()
    restore = []
    cold = args.scenario.startswith("cold-")
    offline = args.scenario.endswith("-offline")
    logfile = args.output / "logcat.txt"
    run_id = uuid.uuid4().hex
    metadata["runId"] = run_id

    def sanitize(text):
        return backend.redact(text) if backend else text

    def capture(name, command, checked=True):
        result = subprocess.run(adb + command, capture_output=True, text=True, timeout=30)
        (args.output / name).write_text(sanitize(result.stdout + result.stderr), encoding="utf-8")
        if checked and result.returncode:
            raise RuntimeError("Device command failed; see " + name)
        return result.stdout

    def processes(name):
        rows = capture(name, ["shell", "ps", "-A", "-o", "USER,PID,NAME"]).splitlines()
        return [row.split()[1] for row in rows if len(row.split()) == 3 and
                row.split()[0].startswith("u" + args.user + "_") and row.split()[2] == args.package]

    def read_log():
        try:
            with logfile.open("w", encoding="utf-8") as output:
                for raw in log.stdout:
                    line = sanitize(raw)
                    output.write(line)
                    output.flush()
                    fields = dict(FIELDS.findall(line))
                    with state_lock:
                        if fields.get("event") == "test_snapshot" and fields.get("runId") == run_id:
                            phase = fields.get("phase")
                            if proxy and phase in ("hot_peeks", "after_deadline") and phase not in state:
                                proxy.mark_phase(phase)
                            state[phase] = fields
                        if "AndroidRuntime:" in line and "Process: " + args.package + "," in line:
                            state["crashed"] = True
        except Exception as exc:
            reader_errors.append(type(exc).__name__)

    def wait_snapshot(phase):
        until = time.monotonic() + 10
        while True:
            with state_lock:
                snapshot = state.get(phase)
            if snapshot:
                return snapshot
            if time.monotonic() >= until or reader_errors or log.poll() is not None:
                raise AssertionError("Missing current-run trace phase: " + phase)
            threading.Event().wait(0.05)

    def disconnect():
        # Dropping the loopback reverse also guarantees HTTP cannot continue over an
        # emulator route while Android is still reporting a stale validated network.
        capture("reverse-disconnected.txt", ["reverse", "--remove", "tcp:54321"])
        capture("wifi-disconnected.txt", ["shell", "svc", "wifi", "disable"])
        capture("data-disconnected.txt", ["shell", "svc", "data", "disable"])
        state["disconnected"] = True

    def reconnect():
        port = str(proxy.port) if proxy else "54321"
        capture("reverse-reconnected.txt", ["reverse", "tcp:54321", "tcp:" + port])
        capture("wifi-reconnected.txt", ["shell", "svc", "wifi", "enable"])
        capture("data-reconnected.txt", ["shell", "svc", "data", "enable"])

    def restore_environment(name, command):
        capture(name + "-restored.txt", command)
        until = time.monotonic() + 10
        while True:
            if name in ("wifi", "data"):
                setting = "wifi_on" if name == "wifi" else "mobile_data"
                observed = capture(name + "-restored-state.txt", ["shell", "settings", "get", "global", setting]).strip()
                correct = observed == ("1" if command[-1] == "enable" else "0")
            elif name == "reverse":
                rows = capture("reverse-restored-state.txt", ["reverse", "--list"]).splitlines()
                mappings = [row.split()[-2:] for row in rows if "tcp:54321" in row.split()]
                correct = mappings == [["tcp:54321", "tcp:54321"]] if "--remove" not in command else not mappings
            elif name in ("exact", "fsi"):
                operation = command[-2]
                observed = capture(name + "-restored-state.txt", ["shell", "appops", "get", "--user", args.user, args.package, operation])
                mode = re.search(re.escape(operation) + r": (allow|deny|ignore|default)", observed)
                correct = (mode.group(1) if mode else "default") == command[-1]
            elif name == "idle":
                correct = "mForceIdle=false" in capture("idle-restored-state.txt", ["shell", "dumpsys", "deviceidle"])
            else:
                correct = "UPDATES STOPPED" not in capture("battery-restored-state.txt", ["shell", "dumpsys", "battery"])
            if correct:
                return
            if time.monotonic() >= until:
                raise RuntimeError("Device state restoration was not observed: " + name)
            threading.Event().wait(0.1)

    def instrument(method, prepare=False):
        with state_lock:
            state.pop("crashed", None)
        command = adb + ["shell", "am", "instrument", "--user", args.user, "-w", "-r", "-e", "class", method,
                         "-e", "allowSyntheticSession", "true", "-e", "androidUser", args.user,
                         "-e", "timerScenario", args.scenario, "-e", "timerRunId", run_id]
        if backend:
            for key, value in backend.instrumentation_args().items():
                command.extend(["-e", key, value])
        command.append(args.package + ".test/androidx.test.runner.AndroidJUnitRunner")
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        state["instrumentStarted"] = True
        until = time.monotonic() + 1200
        try:
            while True:
                try:
                    stdout, stderr = process.communicate(timeout=0.25)
                    return subprocess.CompletedProcess([], process.returncode, sanitize(stdout), sanitize(stderr))
                except subprocess.TimeoutExpired:
                    with state_lock:
                        confirmed = state.get("confirmed")
                        armed = state.get("armed")
                        crashed = state.get("crashed")
                    if offline and confirmed and not state.get("disconnected"):
                        if any(confirmed.get(key) != value for key, value in
                               (("fixture", "true"), ("confirmed", "true"), ("backend", "loopback"))):
                            raise AssertionError("Invalid confirmed real-backend handshake")
                        disconnect()
                    if prepare and armed and not state.get("killSent"):
                        if not confirmed or any(confirmed.get(key) != value for key, value in
                                                (("fixture", "true"), ("confirmed", "true"), ("backend", "loopback"))):
                            raise AssertionError("Missing confirmed real-backend handshake before arming")
                        if any(armed.get(key) != confirmed.get(key) for key in ("pid", "restInstanceId", "generation", "deadlineElapsedMs")):
                            raise AssertionError("Armed timer differs from the confirmed fixture")
                        if offline and not state.get("disconnected"):
                            raise AssertionError("Cold offline fixture armed before host disconnect")
                        pid = armed.get("pid", "")
                        if not pid.isdigit() or pid not in processes("cold-processes-before-kill.txt"):
                            raise AssertionError("Preparation PID is not the exact fixture-user app process")
                        if int(armed["deadlineElapsedMs"]) <= int(armed["now"]):
                            raise AssertionError("Fixture was already due before SIGKILL")
                        capture("cold-exits-before-kill.txt", ["shell", "dumpsys", "activity", "exit-info", args.package])
                        capture("cold-kill.txt", ["shell", "su", "0", "kill", "-9", pid])
                        state["killSent"] = True
                    if crashed and not state.get("killSent"):
                        raise RuntimeError("Target app crashed; inspect logcat.txt")
                    if reader_errors or log.poll() is not None:
                        raise RuntimeError("Log capture failed")
                    if time.monotonic() >= until:
                        raise RuntimeError("Instrumentation timeout")
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=10)

    def require_success(result, name):
        (args.output / name).write_text(result.stdout + result.stderr, encoding="utf-8")
        if result.returncode != 0 or not re.search(r"OK \([1-9]\d* tests?\)", result.stdout + result.stderr):
            raise AssertionError("Instrumentation failed; see " + name)
        if re.search(r"(?:INSTRUMENTATION_FAILED|FAILURES!!!|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[34])", result.stdout + result.stderr):
            raise AssertionError("Instrumentation reported failure; see " + name)

    try:
        if production:
            from online_ready_support import LocalFixtureBackend, ResponseLossProxy
            fixture_context = LocalFixtureBackend()
            proxy_context = ResponseLossProxy(allow_response_loss=False, port=0)
        else:
            fixture_context = nullcontext()
            proxy_context = nullcontext()
        with fixture_context as fixture, proxy_context as proxy:
            backend = fixture
            if production and backend.url != "http://127.0.0.1:54321":
                raise RuntimeError("Production proof requires the exact local loopback backend")
            try:
                metadata["api"] = int(capture("api.txt", ["shell", "getprop", "ro.build.version.sdk"]).strip())
                capture("avd.txt", ["emu", "avd", "name"])
                capture("fingerprint.txt", ["shell", "getprop", "ro.build.fingerprint"])
                if production:
                    users = capture("users.txt", ["shell", "pm", "list", "users"])
                    if not re.search(r"UserInfo\{" + args.user + r":", users):
                        raise RuntimeError("Fixture Android user does not exist")
                    if capture("current-user.txt", ["shell", "am", "get-current-user"]).strip() != args.user:
                        raise RuntimeError("Switch to the fixture Android user before running UI instrumentation")
                for package in (args.package, args.package + ".test"):
                    if "package:" not in capture(package + "-path.txt", ["shell", "pm", "path", "--user", args.user, package]):
                        raise RuntimeError("Required installed APK is missing")
                for label, command in (("alarm", ["dumpsys", "alarm"]),
                                       ("services", ["dumpsys", "activity", "services", args.package]),
                                       ("deviceidle", ["dumpsys", "deviceidle"]), ("battery", ["dumpsys", "battery"])):
                    before = capture(label + "-before.txt", ["shell"] + command)
                    if label == "deviceidle" and "mForceIdle=false" in before:
                        restore.append(("idle", ["shell", "dumpsys", "deviceidle", "unforce"]))
                    elif label == "deviceidle":
                        raise RuntimeError("Refusing unknown or already forced-doze state")
                    if label == "battery" and "UPDATES STOPPED" not in before:
                        restore.append(("battery", ["shell", "dumpsys", "battery", "reset"]))
                    elif label == "battery" and "#forcedDoze" in args.class_name:
                        raise RuntimeError("Refusing a pre-existing battery override for a doze scenario")
                for kind, setting in (("wifi", "wifi_on"), ("data", "mobile_data")):
                    original = capture(kind + "-before.txt", ["shell", "settings", "get", "global", setting]).strip()
                    if original not in ("0", "1"):
                        raise RuntimeError("Cannot determine initial network state")
                    restore.append((kind, ["shell", "svc", kind, "enable" if original == "1" else "disable"]))
                reverse = capture("reverse-before.txt", ["reverse", "--list"])
                mappings = [row.split() for row in reverse.splitlines() if "tcp:54321" in row.split()]
                if any(row[-2:] != ["tcp:54321", "tcp:54321"] for row in mappings):
                    raise RuntimeError("Conflicting reverse mapping; refusing to replace it")
                restore.append(("reverse", ["reverse", "tcp:54321", "tcp:54321"] if mappings else
                                ["reverse", "--remove", "tcp:54321"]))
                if metadata["api"] >= 31 and args.exact:
                    original = capture("exact-before.txt", ["shell", "appops", "get", "--user", args.user, args.package, "SCHEDULE_EXACT_ALARM"])
                    match = re.search(r"SCHEDULE_EXACT_ALARM: (allow|deny|ignore|default)", original)
                    restore.append(("exact", ["shell", "appops", "set", "--user", args.user, args.package,
                                              "SCHEDULE_EXACT_ALARM", match.group(1) if match else "default"]))
                    capture("exact-setup.txt", ["shell", "appops", "set", "--user", args.user, args.package, "SCHEDULE_EXACT_ALARM", args.exact])
                if metadata["api"] >= 34:
                    original = capture("fsi-before.txt", ["shell", "appops", "get", "--user", args.user, args.package, "USE_FULL_SCREEN_INTENT"])
                    match = re.search(r"USE_FULL_SCREEN_INTENT: (allow|deny|ignore|default)", original)
                    restore.append(("fsi", ["shell", "appops", "set", "--user", args.user, args.package,
                                            "USE_FULL_SCREEN_INTENT", match.group(1) if match else "default"]))
                reconnect()
                log = subprocess.Popen(adb + ["logcat", "-T", "1", "-b", "main", "-b", "system", "-b", "crash",
                                       "-v", "threadtime", "IMFITRest:V", "IMFITRestTest:V", "AndroidRuntime:E", "*:S"],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                reader = threading.Thread(target=read_log, daemon=True)
                reader.start()
                result = instrument(args.class_name, prepare=cold)
                if not cold:
                    require_success(result, "instrumentation.txt")
                    if production:
                        confirmed = wait_snapshot("confirmed")
                        if any(confirmed.get(key) != value for key, value in
                               (("fixture", "true"), ("confirmed", "true"), ("backend", "loopback"))):
                            raise AssertionError("Missing real-backend confirmation")
                        hot_peeks = wait_snapshot("hot_peeks")
                        after_deadline = wait_snapshot("after_deadline")
                        observation = analyze_warm_transport(proxy.snapshot_trace(), hot_peeks, after_deadline)
                        (args.output / "zero-business-transport.json").write_text(json.dumps(observation, indent=2), encoding="utf-8")
                        metadata["zeroBusinessTransport"] = observation
                    if offline and not state.get("disconnected"):
                        raise AssertionError("Offline instrumentation never completed the host disconnect handshake")
                else:
                    (args.output / "preparation.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
                    if not state.get("killSent"):
                        raise AssertionError("Cold preparation exited before the exact PID SIGKILL")
                    armed = state["armed"]
                    until = time.monotonic() + 10
                    while armed["pid"] in processes("cold-processes-after-kill.txt"):
                        if time.monotonic() >= until:
                            raise AssertionError("Old fixture PID survived SIGKILL")
                        threading.Event().wait(0.1)
                    metadata["oldPidGone"] = True
                    test_class = args.class_name.split("#")[0]
                    if offline:
                        remaining = max(0, (int(armed["deadlineElapsedMs"]) - int(armed["now"])) / 1000) + 5
                        until = time.monotonic() + remaining
                        while time.monotonic() < until:
                            if reader_errors or log.poll() is not None:
                                raise RuntimeError("Log capture failed during cold offline observation")
                            threading.Event().wait(min(0.25, max(0, until - time.monotonic())))
                        require_success(instrument(test_class + "#verifyColdOfflineProcessFixture"), "cold-offline-verification.txt")
                        notifications = capture("cold-offline-notifications.txt", ["shell", "cmd", "notification", "list"])
                        if any(line.strip().split("|")[:3] == [args.user, args.package, "1002"] for line in notifications.splitlines()):
                            raise AssertionError("Cold offline completion notification is active")
                    else:
                        require_success(instrument(test_class + "#verifyColdOnlineProcessFixture"), "cold-online-verification.txt")
                    wait_snapshot("offline_verified" if offline else "online_verified")
                    evidence = analyze_cold(logfile.read_text(encoding="utf-8"), armed, args.scenario)
                    processes("cold-processes-verified.txt")
                    (args.output / "cold-evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
                    exits = capture("cold-exits-after.txt", ["shell", "dumpsys", "activity", "exit-info", args.package])
                    if metadata["api"] >= 30:
                        block = next((b for b in exits.split("ApplicationExitInfo #") if
                                      re.search(r"\bpid=" + armed["pid"] + r"\b", b)), "")
                        if "reason=2 (SIGNALED)" not in block or "status=9" not in block:
                            raise AssertionError("Missing exact SIGKILL exit evidence")
                    state["verified"] = True
                metadata["status"] = "PASS"
            finally:
                cleanup_errors = []
                # A crashed doze fixture may never execute its Android finally block.
                # Release idle before attempting any network-bound fixture cleanup.
                for name, command in restore:
                    if name in ("idle", "battery"):
                        try:
                            capture(name + "-cleanup-precondition.txt", command)
                        except Exception as exc:
                            cleanup_errors.append(name + " cleanup precondition: " + type(exc).__name__)
                if cold and state.get("armed"):
                    try:
                        # Offline proof has already been frozen above. Online verification
                        # now also cancels the fixture session and clears only fixture state.
                        reconnect()
                        if offline or not state.get("verified"):
                            require_success(instrument(args.class_name.split("#")[0] + "#verifyColdOnlineProcessFixture"), "cold-cleanup.txt")
                    except Exception as exc:
                        cleanup_errors.append("fixture cleanup: " + type(exc).__name__)
                if production and state.get("instrumentStarted"):
                    try:
                        reconnect()
                        require_success(instrument(args.class_name.split("#")[0] + "#cleanupProductionFixture"), "fixture-cleanup.txt")
                    except Exception as exc:
                        cleanup_errors.append("remote fixture cleanup: " + type(exc).__name__)
                for name, command in reversed(restore):
                    try:
                        restore_environment(name, command)
                    except Exception as exc:
                        cleanup_errors.append(name + ": " + type(exc).__name__)
                if log:
                    try:
                        log.terminate()
                        log.wait(timeout=10)
                        reader.join(timeout=10)
                        if reader.is_alive() or reader_errors:
                            raise RuntimeError("Log capture did not finish cleanly")
                    except Exception as exc:
                        cleanup_errors.append("log capture: " + type(exc).__name__)
                if proxy:
                    try:
                        (args.output / "request-trace.json").write_text(json.dumps(proxy.snapshot_trace(), indent=2), encoding="utf-8")
                    except (OSError, ValueError):
                        cleanup_errors.append("request trace could not be retained")
                if cleanup_errors:
                    metadata["restorationErrors"] = cleanup_errors
                    metadata["status"] = "FAIL"
    except Exception as exc:
        metadata["status"] = "FAIL"
        # Exception messages from subprocess/HTTP helpers may contain credentials.
        metadata["reason"] = sanitize(str(exc)) if isinstance(exc, (AssertionError, RuntimeError)) else type(exc).__name__
    finally:
        metadata["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        try:
            if logfile.exists():
                timings = summarize(logfile.read_text(encoding="utf-8"))
                (args.output / "timings.json").write_text(json.dumps(timings, indent=2), encoding="utf-8")
        except (OSError, ValueError, KeyError):
            metadata["status"] = "FAIL"
            metadata["evidenceError"] = "Timing capture is unreadable or malformed"
        (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print("Timer scenario " + args.scenario + ": " + metadata["status"])
    return 0 if metadata["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
