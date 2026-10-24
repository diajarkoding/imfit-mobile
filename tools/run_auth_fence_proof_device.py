"""Run only the isolated proof APK; physical targets require exact serial/model/API opt-in."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


PACKAGE = "com.diajarkoding.imfit.sdkfenceproof.test"
ROOT = Path(__file__).resolve().parents[1]


def require_emulator_serial(serial):
    if not re.fullmatch(r"emulator-\d+", serial or ""):
        raise ValueError("An explicit emulator serial is required; physical devices are forbidden")


def require_avd_identity(actual, qemu, expected):
    if qemu != "1" or actual != expected:
        raise ValueError("Emulator identity mismatch; refusing all install/test/cleanup operations")


def require_target_serial(serial, allowed_physical=None):
    if allowed_physical is None:
        require_emulator_serial(serial)
    elif not serial or serial != allowed_physical or re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Physical device requires an exact explicitly authorized serial")


def require_physical_identity(model, api, qemu, expected_model, expected_api):
    if not expected_model or expected_api is None or model != expected_model or api != str(expected_api) or qemu not in ("", "0"):
        raise ValueError("Physical model/API identity mismatch; refusing all install/test/cleanup operations")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--require-avd-name", default="Pixel_5", help="Exact authorized emulator AVD name")
    parser.add_argument("--allow-physical-serial", help="Explicit authorization for this exact physical serial; also requires model/API")
    parser.add_argument("--require-model")
    parser.add_argument("--require-api", type=int)
    parser.add_argument("--include-store", action="store_true", help="Also run B1.1a store protocol/recovery and Android filesystem tests")
    args = parser.parse_args()
    require_target_serial(args.serial, args.allow_physical_serial)
    physical = args.allow_physical_serial is not None
    if physical and (not args.require_model or args.require_api is None):
        parser.error("Physical authorization also requires --require-model and --require-api")
    out = args.evidence_dir
    out.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]
    commands = []

    def run(command, timeout=180):
        result = subprocess.run([str(c) for c in command], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=timeout)
        commands.append({"command": [str(c) for c in command], "exit_code": result.returncode})
        (out / "sdk-proof-command-ledger.json").write_text(json.dumps(commands, indent=2), encoding="utf-8")
        if result.returncode:
            raise RuntimeError(f"Command failed: {command[0]} ({result.returncode})\n{result.stdout}\n{result.stderr}")
        return result.stdout + result.stderr

    avd = None if physical else run(adb + ["emu", "avd", "name"]).splitlines()[0].strip()
    qemu = run(adb + ["shell", "getprop", "ro.kernel.qemu"]).strip()
    model = run(adb + ["shell", "getprop", "ro.product.model"]).strip()
    api = run(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip()
    if physical:
        require_physical_identity(model, api, qemu, args.require_model, args.require_api)
    else:
        require_avd_identity(avd, qemu, args.require_avd_name)
        if args.require_api is not None and api != str(args.require_api):
            raise ValueError("Emulator API mismatch")
    apk = ROOT / "sdk/auth-fence-proof/build/outputs/apk/androidTest/debug/auth-fence-proof-debug-androidTest.apk"
    badging = run([args.aapt2, "dump", "badging", apk])
    manifest = run([args.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk])
    assert re.search(r"package: name='([^']+)'", badging).group(1) == PACKAGE
    assert re.search(r':targetPackage\([^)]*\)="([^"]+)"', manifest).group(1) == PACKAGE
    baseline = run(adb + ["shell", "pm", "list", "packages", "com.diajarkoding.imfit"])
    assert "package:" + PACKAGE not in baseline, "Refusing to replace or remove a pre-existing package"
    (out / "sdk-proof-manifest.txt").write_text(manifest, encoding="utf-8")
    (out / "sdk-proof-packages-before.txt").write_text(baseline, encoding="utf-8")
    device = {
        "serial": args.serial,
        "avd": avd,
        "qemu": qemu,
        "physical_opt_in": physical,
        "api": api,
        "model": model,
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
    (out / "sdk-proof-device.json").write_text(json.dumps(device, indent=2), encoding="utf-8")
    installed = False
    try:
        assert "Success" in run(adb + ["install", apk])
        installed = True
        class_names = ["AtomicSessionAuthorityTest", "SdkAtomicFenceProofTest", "SdkPersistenceRecoveryGateTest"]
        if args.include_store:
            class_names += ["AtomicSessionStoreProtocolTest", "SdkAtomicStoreRecoveryTest", "StoreBackupBoundaryTest", "SdkWriterFenceTest", "SdkAwaitedWriterTest", "SdkTokenAndOtpFenceTest", "SdkExternalFlowApiTest", "SdkExpiredRecoveryTest", "SdkLifecycleFlowTest", "AndroidLifecycleFlowTest", "AndroidAwaitedDeepLinkTest", "AndroidAtomicStoreDeviceTest", "AndroidStoreProcessTest"]
        expected_tests = 0
        for name in class_names:
            sources = list((ROOT / "sdk/auth-fence-proof/src").glob("*/java/io/github/jan/supabase/auth/" + name + ".kt"))
            assert len(sources) == 1, "Ambiguous/missing test source: " + name
            expected_tests += len(re.findall(r"@Test\s+fun\s+", sources[0].read_text(encoding="utf-8")))
        classes = ",".join("io.github.jan.supabase.auth." + name for name in class_names)
        text = run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                          PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"])
        (out / "sdk-proof-instrumentation.log").write_text(text, encoding="utf-8")
        codes = re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)
        result = {"passed": codes.count("0"), "failed": codes.count("-2"), "errors": codes.count("-1"),
                  "expected_tests": expected_tests, "classes": class_names,
                  "skipped": codes.count("-3") + codes.count("-4"),
                  "infrastructure_failure": "Process crashed" in text or "INSTRUMENTATION_FAILED" in text,
                  "log_sha256": hashlib.sha256((out / "sdk-proof-instrumentation.log").read_bytes()).hexdigest(),
                  "normalized_log_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(), **device}
        (out / "sdk-proof-device-results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result, indent=2))
        success = result["passed"] == expected_tests and not any(result[k] for k in ["failed", "errors", "skipped", "infrastructure_failure"])
    finally:
        if installed:
            assert "Success" in run(adb + ["uninstall", PACKAGE])
        final = run(adb + ["shell", "pm", "list", "packages", "com.diajarkoding.imfit"])
        (out / "sdk-proof-packages-after.txt").write_text(final, encoding="utf-8")
        assert set(final.splitlines()) == set(baseline.splitlines()), "Package preservation mismatch"
    raise SystemExit(0 if success else 1)


if __name__ == "__main__":
    main()
