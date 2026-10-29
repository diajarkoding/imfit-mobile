"""Real fsync-window process death for the exact synthetic app PID; no app hooks."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
from online_ready_support import PACKAGE


def checked_signal(device, pid, signal):
    if signal not in ("STOP", "CONT", "KILL") or not str(pid).isdigit():
        raise ValueError("Invalid exact process signal")
    rows = [line.split() for line in device.run(["shell", "ps", "-A", "-o", "USER,PID,NAME"]).splitlines()]
    if not any(len(row) == 3 and row[0].startswith("u" + device.user + "_") and row[1:] == [str(pid), PACKAGE] for row in rows):
        raise RuntimeError("Fixture process identity changed")
    device.run(["shell", "su", "0", "kill", "-" + signal, str(pid)])


def root_json(device, path):
    text = device.run(["shell", "su", "0", "sh", "-c", "if [ -f '" + path + "' ]; then cat '" + path + "'; else echo null; fi"])
    return json.loads(text)


def write_app_file(device, path, content, marker_path, evidence):
    """Write only a caller-selected synthetic sidecar, restoring exact app ownership/context."""
    if not path.startswith(f"/data/user/{device.user}/{PACKAGE}/no_backup/") or ".." in Path(path).parts:
        raise ValueError("Only exact fixture no-backup sidecars may be written")
    owner = device.run(["shell", "su", "0", "stat", "-c", "%u:%g", marker_path]).strip()
    if not re.fullmatch(r"\d+:\d+", owner) or int(owner.split(":")[0]) // 100000 != int(device.user):
        raise RuntimeError("Fixture sidecar ownership invalid")
    local = evidence / ("transfer-" + uuid.uuid4().hex)
    remote = "/data/local/tmp/" + local.name
    local.write_bytes(content)
    try:
        device.run(["push", str(local), remote])
        parent = path.rsplit("/", 1)[0]
        device.run(["shell", "su", "0", "mkdir", "-p", parent])
        device.run(["shell", "su", "0", "sh", "-c", "cat '" + remote + "' > '" + path + "'"])
        device.run(["shell", "su", "0", "chown", owner, parent, path])
        device.run(["shell", "su", "0", "restorecon", "-F", parent, path])
        device.run(["shell", "su", "0", "sync"])
    finally:
        device.run(["shell", "rm", "-f", remote])
        local.unlink(missing_ok=True)


def checkpoint_path(device, owner):
    return f"/data/user/{device.user}/{PACKAGE}/no_backup/legacy_recovery/" + hashlib.sha256(owner.encode()).hexdigest() + ".json"


def checkpoint_snapshot(device, owner):
    base = checkpoint_path(device, owner)
    files = {suffix: root_json(device, base + suffix) for suffix in ("", ".new", ".bak")}
    authority = files[".bak"] if files[".bak"] is not None else files[""]
    candidate = files[".new"] if files[".new"] is not None else files[""] if files[".bak"] is not None else None
    for document in (authority, candidate):
        if document is not None and (document.get("ownerId") != owner or document.get("formatVersion") != 1):
            raise RuntimeError("Checkpoint does not belong to the exact fixture owner")
    return {"files": files, "authority": authority, "candidate": candidate}


def action(document, workout):
    if document is None:
        raise RuntimeError("Missing authoritative checkpoint")
    matches = [entry for entry in document["actions"] if entry["operation"] == "import_legacy_workout" and entry["sourceId"] == workout]
    if len(matches) != 1:
        raise RuntimeError("Crash fixture must contain exactly one selected import action")
    return matches[0]


def wait_event(event, process, timeout, message):
    until = time.monotonic() + timeout
    while not event.wait(.1):
        if process.poll() is not None or time.monotonic() >= until:
            raise RuntimeError(message)


def run_crash_window(device, backend, proxy, class_name, arguments, evidence, hashes):
    point = arguments["fixtureCrashPoint"]
    if point not in ("prepared", "submitted", "response-before-checkpoint"):
        raise ValueError("Unknown crash window")
    owner = backend.accounts[0]["owner"]
    root = f"/data/user/{device.user}/{PACKAGE}"
    marker_path = root + "/files/online-ready-fixture.json"
    marker = root_json(device, marker_path)
    if marker.get("fixture") != arguments["fixtureId"] or marker.get("owner") != owner:
        raise RuntimeError("Crash controller refuses nonfixture data")
    checkpoint = checkpoint_path(device, owner)
    nonce = uuid.uuid4().hex
    remote_trace = "/data/local/tmp/imfit-ready-" + nonce + ".strace"
    remote_watcher = "/data/local/tmp/imfit-ready-" + nonce + ".sh"
    local_watcher = evidence / "watcher.sh"
    proxy.set_phase(False)
    hold = proxy.hold_before_upstream("import_legacy_workout") if point == "submitted" else None
    command = ["shell", "am", "instrument", "--user", device.user, "-w", "-r", "-e", "class", class_name + "#prepareLegacyCrashWindow"]
    for key, value in dict(backend.instrumentation_args(), androidUser=device.user, allowSyntheticSession="true", **arguments).items():
        command += ["-e", key, str(value)]
    command += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
    device.commands.append([backend.redact(value) for value in device.prefix + command])
    (device.output / "commands.json").write_text(json.dumps(device.commands, indent=2))
    ready = threading.Event()
    attached = threading.Event()
    identity = {}
    log = subprocess.Popen(device.prefix + ["logcat", "-T", "1", "-v", "brief", "IMFITReady:I", "*:S"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    def read_ready():
        with (evidence / "preparation-log.txt").open("w") as output:
            for line in log.stdout:
                output.write(backend.redact(line)); output.flush()
                found = re.search(r"event=legacy_crash_ready.*?pid=(\d+)", line)
                if found:
                    identity["pid"] = found.group(1); ready.set()
    log_thread = threading.Thread(target=read_ready, daemon=True); log_thread.start()
    test = subprocess.Popen(device.prefix + command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    tracer = None
    trace_thread = None
    killed = False
    failure = None
    try:
        wait_event(ready, test, 180, "Real UI recovery preparation did not arm")
        pid = identity["pid"]
        checked_signal(device, pid, "CONT")  # exact user/package guard, not a process restart
        # Only fsync is traced. No syscall payload, HTTP header, body, or application data is logged.
        trace_command = "strace -f -p " + pid + " -e trace=fsync -e inject=fsync:delay_enter=1000000 -o " + remote_trace + " & tracer=$!; echo IMFIT_TRACER_PID=$tracer; wait $tracer"
        tracer = subprocess.Popen(device.prefix + ["shell", "su", "0", "sh", "-c", trace_command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        def read_trace():
            with (evidence / "strace-attach.txt").open("w") as output:
                for line in tracer.stdout:
                    output.write(line); output.flush()
                    found = re.search(r"IMFIT_TRACER_PID=(\d+)", line)
                    if found:
                        identity["tracer"] = found.group(1)
                    if "attached" in line and re.search(r"\b" + pid + r"\b", line):
                        attached.set()
        trace_thread = threading.Thread(target=read_trace, daemon=True); trace_thread.start()
        wait_event(attached, tracer, 30, "Root ptrace/fsync delay unavailable; refusing a skipped crash proof")
        if point != "submitted":
            old_state, new_state = ("PREPARED", "SUBMITTED") if point == "prepared" else ("SUBMITTED", "CONFIRMED")
            # Supports both Android AtomicFile layouts, never treats an uncommitted base as authority.
            predicate = "{ [ -f '" + checkpoint + ".bak' ] && grep -q '\"state\":\"" + old_state + "\"' '" + checkpoint + ".bak' && grep -q '\"state\":\"" + new_state + "\"' '" + checkpoint + "'; } || { [ ! -f '" + checkpoint + ".bak' ] && grep -q '\"state\":\"" + old_state + "\"' '" + checkpoint + "' && grep -q '\"state\":\"" + new_state + "\"' '" + checkpoint + ".new'; }"
            script = "#!/system/bin/sh\nend=$(($(date +%s)+150))\nwhile kill -0 " + pid + " 2>/dev/null; do\n if " + predicate + "; then\n  [ \"$(stat -c %u /proc/" + pid + ")\" = \"$(stat -c %u '" + marker_path + "')\" ] || exit 4\n  kill -STOP " + pid + " || exit 5\n  echo STOPPED\n  exit 0\n fi\n [ $(date +%s) -lt $end ] || exit 6\n sleep 0.005\ndone\nexit 7\n"
            local_watcher.write_text(script, encoding="utf-8")
            device.run(["push", str(local_watcher), remote_watcher])
            watcher = subprocess.Popen(device.prefix + ["shell", "su", "0", "sh", remote_watcher], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            proxy.set_phase(True)
            stdout, _ = watcher.communicate(timeout=170)
            (evidence / "watcher-output.txt").write_text(stdout)
            if watcher.returncode or "STOPPED" not in stdout:
                raise RuntimeError("Exact AtomicFile fsync crash window was not observed")
        else:
            proxy.set_phase(True)
            if not hold["arrived"].wait(150):
                raise RuntimeError("Selected import never reached the pre-upstream hold")
            checked_signal(device, pid, "STOP")
        snapshot = checkpoint_snapshot(device, owner)
        original = action(snapshot["authority"], marker["workoutId"])
        candidate = action(snapshot["candidate"], marker["workoutId"]) if snapshot["candidate"] else None
        expected = "PREPARED" if point == "prepared" else "SUBMITTED"
        if original["state"] != expected or original.get("validatedReceipt") is not None:
            raise RuntimeError("Durable authority does not match requested crash boundary")
        if point == "prepared" and (candidate is None or candidate["state"] != "SUBMITTED"):
            raise RuntimeError("PREPARED authority was not observed before the SUBMITTED commit")
        if point == "submitted" and candidate is not None:
            raise RuntimeError("SUBMITTED checkpoint has an unfinished AtomicFile replacement")
        if point == "response-before-checkpoint" and (candidate is None or candidate["state"] != "CONFIRMED" or not candidate.get("validatedReceipt")):
            raise RuntimeError("Received/validated receipt was not observed before checkpoint commit")
        if candidate and any(original[key] != candidate[key] for key in ("actionId", "requestId", "requestJson", "bodyDigest", "idMapping")):
            raise RuntimeError("Immutable original changed during checkpoint transition")
        status = backend.rpc("get_operation_status", {"p_request_id": original["requestId"]})
        if status["state"] != ("COMMITTED" if point == "response-before-checkpoint" else "NOT_FOUND"):
            raise RuntimeError("Server commit status does not match the requested crash window")
        (evidence / "frozen-checkpoint.json").write_text(json.dumps(snapshot, indent=2))
        (evidence / "server-status.json").write_text(json.dumps(status, indent=2))
        observation = {"pid": int(pid), "action": original,
            "receipt": candidate.get("validatedReceipt") if point == "response-before-checkpoint" else None,
            "hashes": {key: None if value == "ABSENT" else value for key, value in hashes.items()}}
        write_app_file(device, root + "/no_backup/legacy_ready_crash_observation.json", json.dumps(observation).encode(), marker_path, evidence)
        device.exact_kill(pid); killed = True
        if hold:
            proxy.release_hold(hold, discard=True)
        output, _ = test.communicate(timeout=30)
        (evidence / "preparation-instrumentation.txt").write_text(backend.redact(output))
        (evidence / "crash-window.json").write_text(json.dumps({"point": point, "pid": int(pid), "signal": 9, "requestId": original["requestId"], "actionId": original["actionId"], "serverState": status["state"], "instrumentationIntentionallyKilled": True}, indent=2))
    except BaseException as error:
        failure = error
    finally:
        proxy.set_phase(False)
        if hold:
            proxy.release_hold(hold, discard=True)
        if identity.get("tracer"):
            try:
                rows = device.run(["shell", "ps", "-A", "-o", "PID,ARGS"])
                row = next((line for line in rows.splitlines() if line.split() and line.split()[0] == identity["tracer"]), "")
                if row and "strace" in row and re.search(r"-p\s+" + identity["pid"] + r"\b", row):
                    device.run(["shell", "su", "0", "kill", "-INT", identity["tracer"]])
                elif row:
                    raise RuntimeError("Tracer identity changed; refusing unrelated-process signal")
            except Exception as error:
                failure = failure or error
        if not killed and identity.get("pid"):
            try: checked_signal(device, identity["pid"], "CONT")
            except Exception as error: failure = failure or error
        if tracer:
            try: tracer.wait(timeout=15)
            except subprocess.TimeoutExpired:
                tracer.terminate(); tracer.wait(timeout=10)
                failure = failure or RuntimeError("Tracer did not detach cleanly")
        if trace_thread: trace_thread.join(timeout=5)
        if test.poll() is None:
            test.terminate(); test.communicate(timeout=10)
        log.terminate(); log.wait(timeout=10); log_thread.join(timeout=5)
        try:
            trace = device.run(["shell", "su", "0", "sh", "-c", "if [ -f '" + remote_trace + "' ]; then cat '" + remote_trace + "'; fi"])
            (evidence / "fsync-only.strace").write_text(trace)
            if not failure and ("fsync(" not in trace or "DELAYED" not in trace):
                failure = RuntimeError("No actual fsync delay evidence was recorded")
            device.run(["shell", "su", "0", "rm", "-f", remote_trace, remote_watcher])
        except Exception as error:
            failure = failure or error
        local_watcher.unlink(missing_ok=True)
    if failure:
        raise failure
