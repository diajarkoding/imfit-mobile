#!/usr/bin/env python3
"""Run real-local-backend productionRelease timer proofs on an isolated emulator user.

Install the minified production app and instrumentation APK first. Each scenario
creates its own synthetic local Auth fixture; no device account or database is seeded.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


SCENARIOS = [
    ("warm", "warmProductionRelease", "allow", "warm"),
    ("warm-offline", "warmOfflineProductionRelease", "allow", "warm-offline"),
    ("background", "backgroundExactProductionRelease", "allow", "warm"),
    ("receiver-only", "receiverWithoutServiceProductionRelease", "allow", "warm"),
    ("cancel", "cancelledRestNeverAlertsProductionRelease", "allow", "warm"),
    ("inexact", "exactDeniedUsesInexactProductionRelease", "deny", "warm"),
    ("screen-off", "screenOffExactProductionRelease", "allow", "warm"),
    ("doze", "forcedDozeExactProductionRelease", "allow", "warm"),
    ("cold-offline", "prepareColdProcessFixture", "allow", "cold-offline"),
    ("cold-online", "prepareColdProcessFixture", "allow", "cold-online"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not args.user.isdigit() or int(args.user) == 0:
        parser.error("Use a disposable emulator and a non-owner Android user")
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for name, method, exact, scenario in SCENARIOS:
        command = [sys.executable, str(Path(__file__).with_name("rest_timer_regression.py")),
                   "--serial", args.serial, "--package", "com.diajarkoding.imfit", "--user", args.user,
                   "--scenario", scenario, "--exact", exact, "--output", str(args.output / name),
                   "--class-name", "com.diajarkoding.imfit.core.timer.RestTimerProductionReleaseTest#" + method]
        try:
            for request in [["input", "keyevent", "KEYCODE_WAKEUP"], ["wm", "dismiss-keyguard"]]:
                subprocess.run(["adb", "-s", args.serial, "shell"] + request,
                               check=True, capture_output=True, timeout=30)
            with (args.output / (name + ".host.log")).open("w", encoding="utf-8") as output:
                # The child owns bounded waits and must finish its restoration finally.
                result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
            metadata_path = args.output / name / "metadata.json"
            metadata = json.loads(metadata_path.read_text(encoding="utf-8")) if metadata_path.exists() else {}
            status = "PASS" if result.returncode == 0 and metadata.get("status") == "PASS" else "FAIL"
            row = dict(scenario=name, method=method, status=status, exitCode=result.returncode,
                       evidence=name, reason=metadata.get("reason"))
        except (subprocess.SubprocessError, OSError, ValueError) as exc:
            # Never serialize a subprocess command: instrumentation arguments contain credentials.
            row = dict(scenario=name, method=method, status="FAIL", evidence=name,
                       reason="Host orchestration failed: " + type(exc).__name__)
        results.append(row)
        (args.output / "suite.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(name + ": " + row["status"], flush=True)
        if row["status"] != "PASS":
            # A restoration failure makes further runs unsafe; retain the original failing evidence.
            break
    return 0 if len(results) == len(SCENARIOS) and all(row["status"] == "PASS" for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
