#!/usr/bin/env python3
"""Run local production HTTP/UI and installed legacy upgrades on one disposable user.

No hosted API, owner-user clearing, old application database, or fabricated token is used.
Build normal and instrumented release APKs first, retaining their source/hash manifests.
The only build performed here is the temporary platform-SQLite baseline fixture APK.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
from contextlib import ExitStack
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from online_ready_support import Device, LocalFixtureBackend, PACKAGE, ResponseLossProxy, ROOT, sha256, snapshot_sources
from online_ready_fixture import build_fixture

READY = "com.diajarkoding.imfit.OnlineOnlyReadyTest"
UPGRADE = "com.diajarkoding.imfit.LegacyUpgradeReadyTest"
SCENARIOS = [("fresh", 9), ("schema6", 6), ("schema7", 7), ("schema9", 9),
             ("wal", 9), ("remote-expired", 9), ("server-legacy", 9), ("unknown4", 4),
             ("unknown8", 8), ("corrupt", 9), ("restore", 9), ("accounts", 9), ("import-conflict", 9), ("crash-import", 9)]


def legacy_id(fixture, key):
    """Exact java.util.UUID.nameUUIDFromBytes(UTF-8(fixture + key))."""
    return str(uuid.UUID(bytes=hashlib.md5((fixture + key).encode()).digest(), version=3))


def prepare_dependencies(backend, fixture, scenario, evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    exercise = backend.rpc("get_exercise_catalog_page", {"p_limit": 1})["data"][0]["id"]
    if not isinstance(exercise, str) or not exercise or len(exercise) > 255:
        raise RuntimeError("Invalid real catalog exercise identity")
    payloads = []
    for account, label in enumerate(("A", "B")):
        payload = {"p_request_id": legacy_id(fixture, label + "template-create"),
            "p_template_id": legacy_id(fixture, label + "template"), "p_expected_revision": None,
            "p_name": "Ready legacy " + label,
            "p_exercises": [{"exercise_id": exercise, "order_index": 0, "sets": 2, "reps": 10, "rest_seconds": 60}],
            "p_removed_exercise_ids": [], "p_is_deleted": False, "p_owner_id": backend.accounts[account]["owner"]}
        receipt = backend.rpc("save_template_revision", payload, account)
        if receipt["template_id"] != payload["p_template_id"] or receipt["revision"] != 1:
            raise RuntimeError("Exact legacy template dependency not created")
        payloads.append(payload)
    extras = {"exerciseIdBase64": base64.b64encode(exercise.encode()).decode()}
    if scenario == "schema9":
        original = dict(payloads[0], p_request_id=legacy_id(fixture, "intent-request"),
                        p_expected_revision=1, p_name="Ready original intent")
        # Real SQL commit followed by a dropped HTTP response: never acknowledge it locally.
        with ResponseLossProxy(port=0) as loss:
            loss.arm_operation("save_template_revision")
            try:
                backend.rpc("save_template_revision", original, endpoint=loss.url)
            except Exception:
                if not loss.state["committed"] or not loss.state["dropped"]:
                    raise
            else:
                raise RuntimeError("Template intent response loss did not occur")
            if loss.state["receipt"]["request_id"] != original["p_request_id"]:
                raise RuntimeError("Template intent proxy receipt mismatch")
            (evidence / "template-response-loss.json").write_text(json.dumps({"trace": loss.snapshot_trace(), "state": loss.state}, indent=2))
        extras["intentBase64"] = base64.b64encode(json.dumps(original, separators=(",", ":")).encode()).decode()
    if scenario in ("wal", "remote-expired", "server-legacy"):
        session = legacy_id(fixture, "remote-session")
        owner = backend.accounts[0]["owner"]
        if scenario == "server-legacy":
            backend.sql("insert into public.active_sessions(id,user_id,template_id,template_name,start_time,session_data,expires_at) values ('" + session + "'::uuid,'" + owner + "'::uuid,'" + payloads[0]["p_template_id"] + "'::uuid,'Ready server legacy',now(),'{}'::jsonb,now()+interval '30 days');")
            state = "LEGACY"
        else:
            created = backend.rpc("create_active_session", {"p_request_id": legacy_id(fixture, "remote-create"), "p_session_id": session,
                "p_template_id": payloads[0]["p_template_id"], "p_expected_template_revision": 1})
            if created["session_id"] != session:
                raise RuntimeError("Remote session dependency mismatch")
            if scenario == "wal":
                backend.rpc("update_active_session", {"p_request_id": legacy_id(fixture, "remote-pause"), "p_session_id": session,
                    "p_expected_version": created["snapshot"]["version"], "p_command": {"type": "pause"}})
                state = "PAUSED"
            else:
                backend.sql("update public.active_sessions set expires_at=now()-interval '1 second' where id='" + session + "'::uuid and user_id='" + owner + "'::uuid;")
                state = "EXPIRED"
        extras.update(remoteSessionId=session, remoteState=state)
    if scenario == "import-conflict":
        backend.sql("insert into public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps) values ('" + legacy_id(fixture, "Aworkout") + "'::uuid,'" + backend.accounts[0]["owner"] + "'::uuid,'" + payloads[0]["p_template_id"] + "'::uuid,'Existing conflicting fixture',date '2025-10-29',timestamptz '2025-10-29 00:00:00Z',timestamptz '2025-10-29 00:01:00Z',0,0,0);")
    return extras


def remote_fixture_aggregate(backend, fixture, label):
    workout = legacy_id(fixture, label + "workout")
    owner = backend.accounts[0 if label == "A" else 1]["owner"]
    return json.loads(backend.sql("select jsonb_build_object('parent',(select to_jsonb(w) from public.workout_logs w where w.id='" + workout + "'::uuid and w.user_id='" + owner + "'::uuid),'exercises',(select coalesce(jsonb_agg(to_jsonb(e) order by e.id),'[]'::jsonb) from public.exercise_logs e where e.workout_log_id='" + workout + "'::uuid),'sets',(select coalesce(jsonb_agg(to_jsonb(s) order by s.id),'[]'::jsonb) from public.workout_sets s where s.workout_log_id='" + workout + "'::uuid));"))


def artifact(path, manifest_path, sources, delta):
    if not path.is_file() or not manifest_path.is_file():
        raise RuntimeError("Required prebuilt APK/provenance missing: " + str(path))
    manifest = json.loads(manifest_path.read_text())
    expected = {"sha256": sha256(path), "sourceFingerprint": sources["sourceFingerprint"],
                "variant": "productionRelease", "minified": True, "testKeepDelta": delta}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Stale/wrong-variant APK manifest: " + str(manifest_path))
    return expected


def sdk_path():
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            candidate = Path(os.environ[key])
            if candidate.is_dir():
                return candidate
    properties = ROOT / "local.properties"
    if properties.is_file():
        match = re.search(r"^sdk.dir=(.+)$", properties.read_text(), re.MULTILINE)
        if match:
            candidate = Path(match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
            if candidate.is_dir():
                return candidate
    raise RuntimeError("Android SDK unavailable")


def certificate(apk, sdk):
    candidates = sorted((sdk / "build-tools").glob("*/apksigner.bat" if os.name == "nt" else "*/apksigner"))
    if not candidates:
        raise RuntimeError("SDK apksigner missing")
    result = subprocess.run([str(candidates[-1]), "verify", "--print-certs", str(apk)], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError("APK signature verification failed: " + str(apk))
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout)
    if len(certs) != 1:
        raise RuntimeError("Expected exactly one fixture debug signing certificate")
    return certs[0].lower()


def installed_hashes(device):
    base = f"/data/user/{device.user}/{PACKAGE}/databases/imfit_database"
    result = {}
    for suffix in ("", "-wal"):
        path = base + suffix
        probe = device.run(["shell", "su", "0", "sh", "-c", f"if [ -f '{path}' ]; then sha256sum '{path}'; else echo ABSENT; fi"])
        result["imfit_database" + suffix] = probe.split()[0]
    return result


def marker(device):
    return json.loads(device.run(["shell", "su", "0", "cat", f"/data/user/{device.user}/{PACKAGE}/files/online-ready-fixture.json"]))


def clear_owned(device, fixture):
    if marker(device).get("fixture") != fixture:
        raise RuntimeError("Refusing to clear data without exact synthetic fixture ownership")
    output = device.run(["shell", "pm", "clear", "--user", device.user, PACKAGE])
    if output.strip() != "Success":
        raise RuntimeError("Exact fixture-user clear failed")


def instrument(device, backend, name, args, evidence, restart=False, restart_event="restart_armed"):
    command = ["shell", "am", "instrument", "--user", device.user, "-w", "-r", "-e", "class", name]
    for key, value in dict(backend.instrumentation_args(), androidUser=device.user,
                           allowSyntheticSession="true", **args).items():
        command += ["-e", key, str(value)]
    command += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
    if not restart:
        output = device.run(command, timeout=900, secret=True)
        (evidence / "instrumentation.txt").write_text(output)
        if not re.search(r"OK \([1-9]\d* tests?\)", output) or re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:3|4)\b", output) or "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output or "INSTRUMENTATION_CODE: -1" not in output:
            raise RuntimeError("Instrumentation did not pass: " + name)
        (evidence / "result.json").write_text(json.dumps({"status": "PASS", "tests": int(re.search(r"OK \((\d+) tests?\)", output).group(1)), "failures": 0, "skips": 0}, indent=2))
        return
    # Observe only this run's new log entries, then kill its exact non-owner PID.
    log = subprocess.Popen(device.prefix + ["logcat", "-T", "1", "-v", "brief", "IMFITReady:I", "*:S"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    armed = threading.Event()
    observed = {}
    def consume():
        with (evidence / "restart-log.txt").open("w") as out:
            for line in log.stdout:
                out.write(backend.redact(line)); out.flush()
                match = re.search(r"event=" + re.escape(restart_event) + r".*?pid=(\d+)", line)
                if match:
                    observed["pid"] = match.group(1); armed.set()
    reader = threading.Thread(target=consume, daemon=True); reader.start()
    device.commands.append([backend.redact(x) for x in device.prefix + command])
    (device.output / "commands.json").write_text(json.dumps(device.commands, indent=2))
    process = subprocess.Popen(device.prefix + command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        if not armed.wait(180):
            raise RuntimeError("Confirmed restart preparation never armed")
        device.exact_kill(observed["pid"])
        output, _ = process.communicate(timeout=30)
        (evidence / "instrumentation.txt").write_text(backend.redact(output))
        until = time.monotonic() + 15
        while re.search(r"\b" + observed["pid"] + r"\b", device.run(["shell", "ps", "-A", "-o", "PID,NAME"])):
            if time.monotonic() > until:
                raise RuntimeError("SIGKILL process survived")
            time.sleep(.1)
        (evidence / "restart.json").write_text(json.dumps({"exactPid": observed["pid"], "signal": 9, "instrumentationIntentionallyKilled": True}, indent=2))
    finally:
        if process.poll() is None:
            process.kill(); process.communicate(timeout=10)
        log.terminate(); log.wait(timeout=10); reader.join(timeout=5)


class Surface:
    """Host-driven visible UI for the normal minified APK, not instrumentation proof."""
    def __init__(self, device, backend):
        self.device, self.backend = device, backend
    def nodes(self):
        path = f"/data/local/tmp/imfit-ready-{self.device.user}.xml"
        self.device.run(["shell", "uiautomator", "dump", path])
        text = self.device.run(["shell", "cat", path])
        self.device.run(["shell", "rm", path])
        return list(ET.fromstring(text).iter("node"))
    def wait(self, predicate, timeout=60):
        until = time.monotonic() + timeout
        while True:
            matches = [node for node in self.nodes() if predicate(node)]
            if matches:
                return matches
            if time.monotonic() >= until:
                raise RuntimeError("Normal APK visible UI did not reach required state")
            time.sleep(.25)
    def tap(self, node):
        x1,y1,x2,y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        self.device.run(["shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2)])
    def text(self, *values):
        return self.wait(lambda n: n.get("text") in values or n.get("content-desc") in values)
    def click(self, *values):
        self.tap(self.text(*values)[-1])
    def enter(self, node, value):
        self.tap(node)
        self.device.run(["shell", "input", "keyevent", "KEYCODE_MOVE_END"])
        self.device.run(["shell", "input", "keyevent"] + ["KEYCODE_DEL"] * 80)
        self.device.run(["shell", "input", "text", value], secret=True)
    def journey(self):
        fields = self.wait(lambda n: n.get("class") == "android.widget.EditText")
        if len(fields) != 2:
            raise RuntimeError("Expected visible login email/password fields")
        account = self.backend.accounts[0]
        self.enter(fields[0], account["email"]); self.enter(fields[1], account["password"])
        self.click("Sign In", "Masuk")
        self.click("Ready normal proof")
        self.click("Start Workout", "Mulai Latihan")
        fields = self.wait(lambda n: n.get("class") == "android.widget.EditText")
        if len(fields) < 2:
            raise RuntimeError("Confirmed workout inputs not visible")
        self.enter(fields[0], "20.5"); self.enter(fields[1], "10")
        self.device.run(["shell", "input", "keyevent", "KEYCODE_BACK"])
        hold = int(os.environ.get("IMFIT_READY_SURFACE_HOLD_SECONDS", "0"))
        if hold:
            if not 1 <= hold <= 1800:
                raise RuntimeError("Surface hold must be 1..1800 seconds")
            self.text("Saved on server", "Tersimpan di server")
            gate = self.device.output / "surface-continue"
            if gate.exists():
                raise RuntimeError("Surface continue marker must not pre-exist")
            (self.device.output / "surface-ready.json").write_text(json.dumps({
                "phase": "normal-minified-confirmed-active", "instrumentationTested": False,
                "user": self.device.user, "continueMarker": str(gate), "timeoutSeconds": hold}, indent=2))
            until = time.monotonic() + hold
            while not gate.is_file():
                if time.monotonic() >= until:
                    raise RuntimeError("Surface hold expired without explicit host continuation")
                time.sleep(.25)
        self.tap(self.wait(lambda n: n.get("checkable") == "true" and n.get("checked") == "false" and n.get("enabled") == "true")[-1])
        self.device.screenshot("normal-complete")
        self.click("Finish Workout", "Selesaikan Latihan")
        # Explicit confirmation dialogs differ only in the visible localized label.
        candidates = [n for n in self.nodes() if n.get("text") in ("Finish", "Finish Workout", "Selesaikan", "Selesaikan Latihan")]
        if candidates:
            self.tap(candidates[-1])
        self.text("Workout Complete!", "Latihan Selesai!")
        self.device.screenshot("normal-summary")
        pids = [row.split() for row in self.device.run(["shell", "ps", "-A", "-o", "USER,PID,NAME"]).splitlines()]
        matches = [row[1] for row in pids if len(row)==3 and row[0].startswith("u"+self.device.user+"_") and row[2]==PACKAGE]
        if len(matches)!=1:
            raise RuntimeError("Normal APK restart PID ambiguous")
        self.device.exact_kill(matches[0])
        self.device.run(["shell", "am", "start", "--user", self.device.user, "-n", PACKAGE+"/.MainActivity"])
        self.text("My Routines", "Rutinitas Saya", "Home", "Beranda")
        self.device.screenshot("normal-restarted")


def seed_normal_template(backend):
    account = backend.accounts[0]
    def rpc(name, body):
        request = urllib.request.Request(backend.url + "/rest/v1/rpc/" + name, data=json.dumps(body).encode(),
            headers={"apikey": backend.anon, "Authorization": "Bearer " + account["access_token"], "Content-Type": "application/json"})
        with backend.opener.open(request, timeout=30) as response:
            return json.load(response)
    exercise = rpc("get_exercise_catalog_page", {"p_limit":1})["data"][0]["id"]
    rpc("save_template_revision", {"p_request_id": str(uuid.uuid4()), "p_template_id": str(uuid.uuid4()), "p_expected_revision": None,
        "p_name": "Ready normal proof", "p_exercises":[{"exercise_id":exercise,"order_index":0,"sets":1,"reps":10,"rest_seconds":0}],
        "p_removed_exercise_ids":[],"p_is_deleted":False,"p_owner_id":account["owner"]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not args.user.isdigit() or int(args.user)<=0:
        parser.error("Use a disposable emulator and a non-owner fixture user")
    args.output.mkdir(parents=True, exist_ok=False)
    results = {"startedUtc":datetime.now(timezone.utc).isoformat(),"status":"FAIL","scenarios":[], "scope":"local internal debug-signed minified; not publish-ready or hosted deployment"}
    results["unexercisedAcceptance"] = [
        {"gate": "crash-after-PREPARED-before-SUBMITTED", "status": "NOT_TESTED"},
        {"gate": "crash-after-SUBMITTED-before-dispatch", "status": "NOT_TESTED"},
        {"gate": "crash-after-response-before-checkpoint", "status": "NOT_TESTED"},
        {"gate": "restored-source-changed-fingerprint-review", "status": "NOT_TESTED"},
    ]
    device = Device(args.serial,args.user,args.output)
    original_reverse = None
    active_fixture = None
    try:
        sources = snapshot_sources()
        (args.output / "source-manifest.json").write_text(json.dumps(sources, indent=2))
        normal = ROOT / "app/build/outputs/online-ready/normal-production-release.apk"
        target = ROOT / "app/build/outputs/apk/production/release/app-production-release.apk"
        test_apk = ROOT / "app/build/outputs/apk/androidTest/production/release/app-production-release-androidTest.apk"
        provenance = {"normal":artifact(normal,normal.with_suffix(".json"),sources,False),
                      "instrumented":artifact(target,target.parent/"online-ready-build.json",sources,True)}
        if not test_apk.is_file():
            raise RuntimeError("Production release instrumentation APK missing")
        # Separate instrumentation APK must be pinned by its own hash, not a possibly stale build output.
        build_manifest = json.loads((target.parent / "online-ready-build.json").read_text())
        if build_manifest.get("testApkSha256") != sha256(test_apk):
            raise RuntimeError("Test APK hash missing/mismatched in instrumented build manifest")
        provenance["testApkSha256"] = sha256(test_apk)
        if provenance["normal"]["sha256"] == provenance["instrumented"]["sha256"]:
            raise RuntimeError("Normal release and instrumentation keep-delta APK must be distinct")
        device.validate()
        results.update(serial=args.serial,user=int(args.user),api=device.run(["shell","getprop","ro.build.version.sdk"]).strip(),fingerprint=device.run(["shell","getprop","ro.build.fingerprint"]).strip(),apks=provenance)
        original_reverse = device.run(["reverse","--list"])
        (args.output / "reverse-before.txt").write_text(original_reverse)
        migration_files = sorted((ROOT / "supabase/migrations").glob("*.sql"))
        migration_evidence=[]
        for path in migration_files:
            sql=path.read_text()
            migration_evidence.append({"name":path.name,"sha256":sha256(path),"signatures":re.findall(r"create\s+(?:or\s+replace\s+)?function\s+public\.[\w]+\s*\([^;]*?\)\s*returns\s+[^\n]+",sql,re.I)})
        (args.output / "migration-contracts.json").write_text(json.dumps(migration_evidence,indent=2))
        sdk=sdk_path()
        if certificate(normal,sdk)!=certificate(target,sdk) or certificate(target,sdk)!=certificate(test_apk,sdk):
            raise RuntimeError("Normal/target/test signatures differ")
        with tempfile.TemporaryDirectory(prefix="imfit-ready-fixture-") as temporary:
            fixture_apk, build_log=build_fixture(temporary,sdk)
            (args.output / "fixture-build.txt").write_text(build_log)
            if certificate(fixture_apk,sdk)!=certificate(target,sdk):
                raise RuntimeError("Temporary baseline APK does not use exact target debug signature")
            results["fixtureApkSha256"]=sha256(fixture_apk)
            with LocalFixtureBackend(accounts=2) as backend, ResponseLossProxy() as proxy:
                backend.secrets.append(proxy.secret)
                device.redact=backend.redact
                catalog = backend.sql("select coalesce(jsonb_agg(jsonb_build_object('signature',p.oid::regprocedure::text,'arguments',pg_get_function_arguments(p.oid),'returns',pg_get_function_result(p.oid),'security_definer',p.prosecdef,'configuration',p.proconfig,'acl',p.proacl) order by p.oid::regprocedure::text),'[]'::jsonb) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public';")
                (args.output / "installed-rpc-catalog.json").write_text(catalog)
                device.run(["reverse","tcp:54321","tcp:54322"])
                fixture_base=f"/data/user/{device.user}/{PACKAGE}"
                def baseline(scenario,version,fixture_id=None,dependencies=None):
                    nonlocal active_fixture
                    active_fixture=fixture_id or str(uuid.uuid4())
                    launch_id=str(uuid.uuid4())
                    dependencies=dependencies or prepare_dependencies(backend,active_fixture,scenario,args.output/("dependencies-"+active_fixture))
                    device.run(["install","--user",device.user,"-r",str(fixture_apk)],timeout=180)
                    command=["shell","am","start","--user",device.user,"-n",PACKAGE+"/.FixtureActivity","--es","owner",backend.accounts[0]["owner"],"--es","other",backend.accounts[1]["owner"],"--es","fixture",active_fixture,"--es","launchId",launch_id,"--es","scenario",scenario,"--ei","schema",str(version)]
                    for key,value in dependencies.items():
                        command+=["--es",key,value]
                    device.run(command)
                    deadline=time.monotonic()+60
                    while True:
                        text=device.run(["logcat","-d","-t","300","-v","brief","IMFITReadyFixture:I","*:S"])
                        match=re.search(r"event=fixture_ready writerClosed=true pid=(\d+) fixture="+active_fixture+" launch="+launch_id,text)
                        if match:
                            device.exact_kill(match.group(1)); break
                        if time.monotonic()>deadline:
                            raise RuntimeError("Baseline writer never closed successfully")
                        time.sleep(.2)
                    if marker(device)["fixture"]!=active_fixture:
                        raise RuntimeError("Installed baseline ownership mismatch")
                    return dependencies
                for scenario,version in SCENARIOS:
                    evidence=args.output/scenario; evidence.mkdir()
                    dependencies=baseline(scenario,version)
                    before=installed_hashes(device)
                    remote_before={label:remote_fixture_aggregate(backend,active_fixture,label) for label in ("A","B")}
                    (evidence/"remote-before.json").write_text(json.dumps(remote_before,indent=2))
                    if scenario=="wal" and before["imfit_database-wal"]=="ABSENT":
                        raise RuntimeError("Committed WAL fixture absent")
                    restored={}
                    if scenario=="restore":
                        for name,digest in before.items():
                            if digest!="ABSENT":
                                restored[name]=device.run(["exec-out","su","0","cat",fixture_base+"/databases/"+name],binary=True)
                    device.run(["install","--user",device.user,"-r",str(target)],timeout=180)
                    device.run(["install","--user",device.user,"-r",str(test_apk)],timeout=180)
                    test_args={"fixtureId":active_fixture,"fixtureScenario":scenario,"fixtureSchema":version,
                        "fixtureRestorePhase":"initial","fixtureExpectedImportOutcome":"CONFLICT" if scenario=="import-conflict" else "IMPORTED",
                        "responseLossControlUrl":"http://127.0.0.1:54321"+proxy.control_path}
                    if scenario=="crash-import":
                        prepared=evidence/"submitted";prepared.mkdir()
                        instrument(device,backend,UPGRADE+"#prepareLegacyImportResponseLoss",test_args,prepared,
                            restart=True,restart_event="legacy_restart_armed")
                        resolved=evidence/"resolved";resolved.mkdir()
                        instrument(device,backend,UPGRADE+"#verifyLegacyImportResponseLoss",test_args,resolved)
                    else:
                        instrument(device,backend,UPGRADE+"#installedSourceRemainsOwnerScopedAndReadableThroughRecovery",test_args,evidence)
                    if scenario=="restore":
                        if installed_hashes(device)!=before:
                            raise RuntimeError("Initial import changed source before database-only restore")
                        original_fixture=active_fixture
                        clear_owned(device,active_fixture)
                        baseline("restore",version,fixture_id=original_fixture,dependencies=dependencies)
                        for name,data in restored.items():
                            temp=evidence/name;temp.write_bytes(data)
                            remote=f"/data/local/tmp/imfit-ready-{active_fixture}-{name}"
                            device.run(["push",str(temp),remote])
                            try:
                                device.run(["shell","su","0","sh","-c",f"cat '{remote}' > '{fixture_base}/databases/{name}'"])
                            finally:
                                device.run(["shell","rm",remote]);temp.unlink()
                        if installed_hashes(device)!=before:
                            raise RuntimeError("Database-only restore did not preserve exact source")
                        device.run(["install","--user",device.user,"-r",str(target)],timeout=180)
                        restored_evidence=evidence/"restored";restored_evidence.mkdir()
                        instrument(device,backend,UPGRADE+"#installedSourceRemainsOwnerScopedAndReadableThroughRecovery",
                            dict(test_args,fixtureRestorePhase="restored",fixtureExpectedImportOutcome="MATCHED"),restored_evidence)
                    after=installed_hashes(device)
                    (evidence/"source-preservation.json").write_text(json.dumps({"before":before,"after":after,"excluded":"operational -shm"},indent=2))
                    if before!=after:
                        raise RuntimeError("Installed source database/WAL changed: "+scenario)
                    remote_after={label:remote_fixture_aggregate(backend,active_fixture,label) for label in ("A","B")}
                    (evidence/"remote-after.json").write_text(json.dumps(remote_after,indent=2))
                    if remote_after["B"]!=remote_before["B"]:
                        raise RuntimeError("Unselected owner B aggregate was changed")
                    if scenario=="import-conflict" and remote_after!=remote_before:
                        raise RuntimeError("Conflict overwrote the existing remote target")
                    device.screenshot(scenario)
                    results["scenarios"].append({"name":scenario,"status":"PASS","schema":version})
                    clear_owned(device,active_fixture);active_fixture=None
                # Run actual production UI/account-switch/response-loss against real Auth/PostgREST.
                baseline("fresh",9)
                device.run(["install","--user",device.user,"-r",str(target)],timeout=180)
                common={"responseLossControlUrl":"http://127.0.0.1:54321"+proxy.control_path}
                for method in ("onlineUiJourney","prepareProcessRestart","verifyProcessRestart","lostResponseAndAccountSwitch"):
                    evidence=args.output/method;evidence.mkdir()
                    instrument(device,backend,READY+"#"+method,common,evidence,restart=method=="prepareProcessRestart")
                    device.screenshot(method)
                    results["scenarios"].append({"name":method,"status":"PASS"})
                if not proxy.state["committed"] or not proxy.state["dropped"]:
                    raise RuntimeError("Actual upstream committed-response-loss was not exercised")
                (args.output/"request-trace.json").write_text(json.dumps(proxy.trace,indent=2))
                (args.output/"lost-response-receipt.json").write_text(json.dumps(proxy.state,indent=2))
                fixture_owners = ",".join("'" + account["owner"] + "'::uuid" for account in backend.accounts)
                for table in ("operation_receipts", "legacy_workout_imports", "legacy_session_reviews"):
                    rows = backend.sql("select coalesce(jsonb_agg(to_jsonb(f)),'[]'::jsonb) from imfit_private." + table + " f where user_id in (" + fixture_owners + ");")
                    (args.output / (table + ".json")).write_text(backend.redact(rows))
                clear_owned(device,active_fixture);active_fixture=None
                # A different synthetic owner avoids old server sessions influencing normal-APK proof.
                with LocalFixtureBackend(accounts=2) as normal_backend:
                    device.redact=normal_backend.redact
                    baseline_owner_backend=backend
                    backend=normal_backend
                    try:
                        baseline("fresh",9)
                        device.run(["install","--user",device.user,"-r",str(normal)],timeout=180)
                        seed_normal_template(normal_backend)
                        device.run(["shell","am","start","--user",device.user,"-n",PACKAGE+"/.MainActivity"])
                        Surface(device,normal_backend).journey()
                        results["scenarios"].append({"name":"normal-minified-visible-ui","status":"PASS","instrumentationTested":False})
                        clear_owned(device,active_fixture);active_fixture=None
                    finally:
                        backend=baseline_owner_backend;device.redact=backend.redact
            # Timer suite provisions its own exact synthetic identities and restores network/appops/doze.
            device.run(["reverse","tcp:54321","tcp:54321"])
            device.run(["install","--user",device.user,"-r",str(target)],timeout=180)
            device.run(["install","--user",device.user,"-r",str(test_apk)],timeout=180)
            timer=subprocess.run([os.sys.executable,"-B",str(ROOT/"tools/run_rest_timer_suite.py"),"--serial",args.serial,"--user",args.user,"--output",str(args.output/"timer")],capture_output=True,text=True,timeout=7200)
            (args.output/"timer-suite.txt").write_text(timer.stdout+timer.stderr)
            if timer.returncode:
                raise RuntimeError("Production timer suite failed")
            results["scenarios"].append({"name":"production-timer-suite","status":"PASS"})
        results["status"]="PASS"
    except BaseException as error:
        results["error"]=device.redact(str(error))
    finally:
        cleanup=[]
        if active_fixture:
            try: clear_owned(device,active_fixture)
            except Exception as error: cleanup.append("Exact fixture cleanup: "+str(error))
        if original_reverse is not None:
            try:
                mapping=next((line.split()[-1] for line in original_reverse.splitlines() if len(line.split())>=3 and line.split()[-2]=="tcp:54321"),None)
                if mapping: device.run(["reverse","tcp:54321",mapping])
                else: device.run(["reverse","--remove","tcp:54321"])
            except Exception as error: cleanup.append("Reverse restoration: "+str(error))
        if getattr(device, "original_user", None):
            try: device.run(["shell", "am", "switch-user", device.original_user])
            except Exception as error: cleanup.append("Foreground user restoration: " + str(error))
        if cleanup:
            results["status"]="FAIL";results["cleanupErrors"]=cleanup
        results["finishedUtc"]=datetime.now(timezone.utc).isoformat()
        (args.output/"results.json").write_text(json.dumps(results,indent=2))
    return 0 if results["status"]=="PASS" else 1


if __name__=="__main__":
    raise SystemExit(main())
