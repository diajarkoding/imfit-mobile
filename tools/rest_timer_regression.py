#!/usr/bin/env python3
"""Run offline synthetic instrumentation and retain correlatable timing evidence.

Production fixtures require a disposable emulator and an isolated Android user.
Never clears logcat or owner-user data. Use --analyze for an existing capture.
"""
import argparse
import json
import re
import subprocess
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

FIELDS = re.compile(r"([A-Za-z][A-Za-z0-9_]*)=([^\s]+)")


def summarize(text):
    groups = {}
    for line in text.splitlines():
        if "IMFITRest" not in line:
            continue
        fields = dict(FIELDS.findall(line))
        identity = fields.get("restInstanceId")
        if not identity or identity == "null":
            continue
        key = (identity, fields.get("generation"))
        groups.setdefault(key, []).append(fields)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--class-name", default="com.diajarkoding.imfit.core.timer.RestTimerBackgroundTest")
    parser.add_argument("--analyze", type=Path)
    parser.add_argument("--package", choices=["com.diajarkoding.imfit.demo", "com.diajarkoding.imfit"], default="com.diajarkoding.imfit.demo")
    parser.add_argument("--exact", choices=["allow", "deny"])
    parser.add_argument("--offline", action="store_true", help="Disable emulator networking temporarily for synthetic production fixtures")
    parser.add_argument("--user", default="0", help="Use an isolated Android user for production fixtures")
    parser.add_argument("--cold-process", action="store_true", help="Hold preparation instrumentation, SIGKILL its exact PID using emulator su, observe the alarm, verify persistence")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.analyze:
        (args.output / "timings.json").write_text(json.dumps(summarize(args.analyze.read_text()), indent=2))
        return
    if not args.serial.startswith("emulator-"):
        parser.error("This runner is restricted to disposable emulators, not personal devices.")
    adb = ["adb", "-s", args.serial]

    def capture(name, command):
        result = subprocess.run(adb + command, capture_output=True, text=True, timeout=30)
        (args.output / name).write_text(result.stdout + result.stderr)
        return result

    metadata = {"startedUtc": datetime.now(timezone.utc).isoformat(), "serial": args.serial,
                "avd": capture("avd.txt", ["emu", "avd", "name"]).stdout.strip(),
                "api": capture("api.txt", ["shell", "getprop", "ro.build.version.sdk"]).stdout.strip(),
                "fingerprint": capture("fingerprint.txt", ["shell", "getprop", "ro.build.fingerprint"]).stdout.strip(),
                "testClass": args.class_name, "package": args.package, "user": args.user}
    if args.package == "com.diajarkoding.imfit" and not args.offline:
        parser.error("Production fixtures require --offline and refuse real accounts.")
    if args.package == "com.diajarkoding.imfit" and args.user == "0":
        parser.error("Production fixtures require an isolated non-owner Android user.")
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2))
    capture("alarms-before.txt", ["shell", "dumpsys", "alarm"])
    capture("services-before.txt", ["shell", "dumpsys", "activity", "services", args.package])
    capture("deviceidle-before.txt", ["shell", "dumpsys", "deviceidle"])
    capture("battery-before.txt", ["shell", "dumpsys", "battery"])
    logfile = args.output / "logcat.txt"
    log = subprocess.Popen(adb + ["logcat", "-T", "1", "-b", "main", "-b", "system", "-b", "crash",
                                  "-v", "threadtime", "IMFITRest:V", "IMFITRestTest:V",
                                   "WM-WorkerWrapper:V", "AlarmManager:V", "NotificationService:V", "AndroidRuntime:E", "*:S"],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    snapshots = set()
    cold = {}
    armed = threading.Event()
    notified = threading.Event()
    target_crashed = threading.Event()

    def read_log():
        with logfile.open("w") as output:
            for line in log.stdout:
                output.write(line)
                output.flush()
                if "AndroidRuntime:" in line and "Process: " + args.package + "," in line:
                    target_crashed.set()
                fields = dict(FIELDS.findall(line))
                if fields.get("event") == "test_snapshot" and fields.get("scenario") == "cold" and fields.get("phase") == "armed":
                    cold["armed"] = fields
                    armed.set()
                if fields.get("restInstanceId") == cold.get("armed", {}).get("restInstanceId"):
                    if fields.get("event") == "expired_committed":
                        cold["commit"] = fields
                    if fields.get("event") == "notify_result" and fields.get("success") == "true":
                        cold["notify"] = fields
                        notified.set()
                if "event=test_snapshot" in line:
                    label = re.sub(r"[^A-Za-z0-9_-]", "_", fields.get("scenario", "test") + "-" + fields.get("phase", "snapshot"))
                    if label not in snapshots:
                        snapshots.add(label)
                        for kind, cmd in [("alarm", ["dumpsys", "alarm"]),
                                          ("service", ["dumpsys", "activity", "services", args.package]),
                                          ("idle", ["dumpsys", "deviceidle"])]:
                            capture(label + "-" + kind + ".txt", ["shell"] + cmd)

    reader = threading.Thread(target=read_log, daemon=True)
    reader.start()
    original_exact = capture("exact-before.txt", ["shell", "appops", "get", "--user", args.user, args.package, "SCHEDULE_EXACT_ALARM"]).stdout
    original_wifi = capture("wifi-before.txt", ["shell", "settings", "get", "global", "wifi_on"]).stdout.strip()
    original_data = capture("data-before.txt", ["shell", "settings", "get", "global", "mobile_data"]).stdout.strip()
    def instrument(class_name):
        target_crashed.clear()
        command = adb + ["shell", "am", "instrument", "--user", args.user, "-w", "-r", "-e", "class", class_name,
                         "-e", "allowSyntheticSession", "true", "-e", "androidUser", args.user,
                         args.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        until = time.monotonic() + 1200
        while True:
            try:
                stdout, stderr = process.communicate(timeout=1)
                return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
            except subprocess.TimeoutExpired:
                if args.cold_process and class_name.endswith("#prepareColdProcessFixture") and armed.is_set() and not cold.get("killSent"):
                    pid = cold["armed"]["pid"]
                    assert pid.isdigit(), "Invalid preparation PID"
                    rows = capture("cold-processes-before-kill.txt", ["shell", "ps", "-A", "-o", "USER,PID,NAME"]).stdout.splitlines()
                    assert any((len(row.split()) == 3 and row.split()[0].startswith("u" + args.user + "_") and
                                row.split()[1:] == [pid, args.package]) for row in rows), "Preparation PID identity mismatch"
                    capture("cold-exits-before-kill.txt", ["shell", "dumpsys", "activity", "exit-info", args.package])
                    killed = capture("cold-kill.txt", ["shell", "su", "0", "kill", "-9", pid])
                    assert killed.returncode == 0, "Emulator SIGKILL failed (su required)"
                    cold["killSent"] = True
                if target_crashed.is_set():
                    process.terminate()
                    stdout, stderr = process.communicate(timeout=10)
                    return subprocess.CompletedProcess(command, 1, stdout, stderr + "\nHost detected target crash; see logcat.txt\n")
                if time.monotonic() >= until:
                    process.kill()
                    process.communicate(timeout=10)
                    raise subprocess.TimeoutExpired(command, 1200)

    def succeeded(result):
        return result.returncode == 0 and bool(re.search(r"OK \(\d+ tests?\)", result.stdout + result.stderr))

    cold_cleaned = False
    try:
        if args.offline:
            capture("wifi-disable.txt", ["shell", "svc", "wifi", "disable"])
            capture("data-disable.txt", ["shell", "svc", "data", "disable"])
        if args.exact:
            capture("exact-setup.txt", ["shell", "appops", "set", "--user", args.user, args.package, "SCHEDULE_EXACT_ALARM", args.exact])
        result = instrument(args.class_name)
        output = result.stdout + result.stderr
        (args.output / "instrumentation.txt").write_text(output)
        # Killing a still-running fixture intentionally terminates its instrumentation.
        passed = bool(cold.get("killSent")) if args.cold_process else succeeded(result)
        if args.cold_process and passed:
            assert armed.wait(10), "Missing cold preparation identity"
            # SIGKILL preserves pending alarms; do not finish instrumentation first.
            until = time.monotonic() + 10
            while True:
                ps = capture("cold-processes.txt", ["shell", "ps", "-A", "-o", "USER,PID,NAME"]).stdout
                rows = [line.split() for line in ps.splitlines()]
                old_alive = any(len(row) == 3 and row[0].startswith("u" + args.user + "_") and
                                row[1] == cold["armed"]["pid"] and row[2] == args.package for row in rows)
                if not old_alive:
                    break
                assert time.monotonic() < until, "Old app process survived SIGKILL; cold test is not valid"
                threading.Event().wait(0.1)  # Predicate polling, not a delay assumed to imply process death.
            budget = (int(cold["armed"]["deadlineElapsedMs"]) - int(cold["armed"]["now"])) / 1000 + 20
            assert notified.wait(max(10, budget)), "Cold receiver did not post before the observation deadline"
            assert cold["commit"]["pid"] != cold["armed"]["pid"], "Expiry ran in the preparation process"
            assert cold["commit"].get("source") == "alarm", "Cold expiry must originate from the real alarm"
            exits = capture("cold-exits-after-delivery.txt", ["shell", "dumpsys", "activity", "exit-info", args.package]).stdout
            killed_exit = next((block for block in exits.split("ApplicationExitInfo #")
                                if re.search(r"\bpid=" + cold["armed"]["pid"] + r"\b", block)), "")
            assert "reason=2 (SIGNALED)" in killed_exit and "status=9" in killed_exit, "Missing SIGKILL exit evidence"
            # notify() returning is not a barrier for NotificationManagerService's queue.
            # Observe the actual card with bounded predicate polling, without app reconcile.
            observation_started = time.monotonic()
            attempt = 0
            while True:
                active = capture(f"cold-active-notifications-{attempt}.txt", ["shell", "cmd", "notification", "list"]).stdout
                if any(line.split("|")[:3] == [args.user, args.package, "1002"] for line in active.splitlines()):
                    cold["activeNotificationObservationMs"] = round((time.monotonic() - observation_started) * 1000)
                    break
                assert time.monotonic() - observation_started < 5, "Cold completion notification is not active within 5s"
                attempt += 1
                threading.Event().wait(0.05)
            lateness = int(cold["commit"]["now"]) - int(cold["armed"]["deadlineElapsedMs"])
            assert 0 <= lateness <= 5000, "Cold expiry lateness exceeded 5s: " + str(lateness)
            capture("cold-delivered-alarms.txt", ["shell", "dumpsys", "alarm"])
            verification = instrument(args.class_name.split("#")[0] + "#verifyAndCleanColdProcessFixture")
            (args.output / "cold-verification.txt").write_text(verification.stdout + verification.stderr)
            cold_cleaned = True
            passed = succeeded(verification)
            (args.output / "cold-evidence.json").write_text(json.dumps(cold, indent=2))
        metadata["status"] = "PASS" if passed else "FAIL"
        if args.cold_process and cold.get("killSent"):
            print("Preparation instrumentation was intentionally terminated by SIGKILL; use metadata.json and cold-verification.txt for the scenario result.")
        print(output)
    except subprocess.TimeoutExpired as exc:
        metadata["status"] = "BLOCKED"
        metadata["reason"] = "instrumentation timeout; inspect device state before retrying"
        (args.output / "instrumentation.txt").write_text(str(exc))
    except Exception as exc:
        metadata["status"] = "FAIL"
        metadata["reason"] = str(exc)
        print(type(exc).__name__ + ": " + str(exc))
    finally:
        if args.cold_process and cold.get("armed") and not cold_cleaned:
            try:
                cleanup = instrument(args.class_name.split("#")[0] + "#verifyAndCleanColdProcessFixture")
                (args.output / "cold-cleanup.txt").write_text(cleanup.stdout + cleanup.stderr)
            except Exception as exc:
                metadata["cleanupError"] = str(exc)
        # Restore global device state even if the target crashed before its Kotlin finally block.
        if "#forcedDoze" in args.class_name:
            if "mForceIdle=false" in (args.output / "deviceidle-before.txt").read_text():
                capture("idle-restored.txt", ["shell", "dumpsys", "deviceidle", "unforce"])
            if "UPDATES STOPPED" not in (args.output / "battery-before.txt").read_text():
                capture("battery-restored.txt", ["shell", "dumpsys", "battery", "reset"])
        if args.exact:
            found = re.search(r"SCHEDULE_EXACT_ALARM: (allow|deny|ignore|default)", original_exact)
            capture("exact-restored.txt", ["shell", "appops", "set", "--user", args.user, args.package, "SCHEDULE_EXACT_ALARM", found.group(1) if found else "default"])
        if args.offline:
            capture("wifi-restored.txt", ["shell", "svc", "wifi", "enable" if original_wifi == "1" else "disable"])
            capture("data-restored.txt", ["shell", "svc", "data", "enable" if original_data == "1" else "disable"])
        log.terminate()
        log.wait(timeout=10)
        reader.join(timeout=30)
        capture("alarms-after.txt", ["shell", "dumpsys", "alarm"])
        capture("services-after.txt", ["shell", "dumpsys", "activity", "services", args.package])
        capture("deviceidle-after.txt", ["shell", "dumpsys", "deviceidle"])
        metadata["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2))
        (args.output / "timings.json").write_text(json.dumps(summarize(logfile.read_text()), indent=2))
    if metadata.get("status") != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
