#!/usr/bin/env python3
"""Run productionRelease regression methods serially on an isolated offline emulator.

Install both release APKs first. Each invocation uses rest_timer_regression.py to retain
evidence and restore networking/exact access. Cold process testing requires emulator su.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


SCENARIOS = [
    ("render-fault", "sessionRenderingReadFailureMustNotBlockExpiry", "allow", False),
    ("background", "backgroundExactProductionRelease", "allow", False),
    ("receiver-only", "receiverWithoutServiceProductionRelease", "allow", False),
    ("cancel", "cancelledRestNeverAlertsProductionRelease", "allow", False),
    ("inexact", "exactDeniedUsesInexactProductionRelease", "deny", False),
    ("screen-off", "screenOffExactProductionRelease", "allow", False),
    ("doze", "forcedDozeExactProductionRelease", "allow", False),
    ("cold", "prepareColdProcessFixture", "allow", True),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.user.isdigit() or int(args.user) == 0:
        parser.error("Use a disposable emulator and a non-owner Android user")
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for name, method, exact, cold in SCENARIOS:
        # Explicit wake/unlock requests, not a static startup delay. The test waits for
        # actual app/Settings foreground state before arming and before backgrounding.
        for command in [["input", "keyevent", "KEYCODE_WAKEUP"], ["wm", "dismiss-keyguard"]]:
            subprocess.run(["adb", "-s", args.serial, "shell"] + command, check=True, timeout=30)
        command = [sys.executable, str(Path(__file__).with_name("rest_timer_regression.py")),
                   "--serial", args.serial, "--package", "com.diajarkoding.imfit", "--user", args.user,
                   "--offline", "--exact", exact, "--output", str(args.output / name), "--class-name",
                   "com.diajarkoding.imfit.core.timer.RestTimerProductionReleaseTest#" + method]
        if cold:
            command.append("--cold-process")
        with (args.output / (name + ".host.log")).open("w") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
        metadata_path = args.output / name / "metadata.json"
        metadata = json.loads(metadata_path.read_text()) if metadata_path.exists() else {}
        status = metadata.get("status", "BLOCKED")
        if result.returncode and status == "PASS":
            status = "FAIL"
        results.append(dict(scenario=name, method=method, status=status, exitCode=result.returncode,
                            evidence=name, reason=metadata.get("reason")))
        (args.output / "suite.json").write_text(json.dumps(results, indent=2))
        print(name + ": " + status, flush=True)
    return 0 if all(row["status"] == "PASS" for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
