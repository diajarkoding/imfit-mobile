"""Verify the isolated Auth proof against the pinned upstream source archive; never rewrite sources."""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import zipfile


EXPECTED_SHA256 = "58c785e03908506120653bd6a2a636db6ebb33e4194169a20d08bf7f64fea1ce"
ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "sdk/auth-fence-proof/upstream"
EXPECTED_DELTAS = {
    "commonMain/io/github/jan/supabase/auth/Auth.kt",
    "commonMain/io/github/jan/supabase/auth/AuthImpl.kt",
    "commonMain/io/github/jan/supabase/auth/AuthenticatedSupabaseApi.kt",
    "commonMain/io/github/jan/supabase/auth/mfa/MfaApi.kt",
    "commonMain/io/github/jan/supabase/auth/providers/builtin/OTP.kt",
    "commonMain/io/github/jan/supabase/auth/providers/builtin/DefaultAuthProvider.kt",
    "commonMain/io/github/jan/supabase/auth/Utils.kt",
    "androidMain/io/github/jan/supabase/auth/Android.kt",
    "androidMain/io/github/jan/supabase/auth/setupPlatform.kt",
    "androidMain/io/github/jan/supabase/auth/Utils.android.kt",
}
EXPECTED_ADDITIONS = {
    "commonMain/io/github/jan/supabase/auth/AtomicSessionAuthority.kt",
    "commonMain/io/github/jan/supabase/auth/AtomicSessionStore.kt",
    "androidMain/io/github/jan/supabase/auth/AtomicStoreFiles.kt",
    "androidMain/io/github/jan/supabase/auth/FileAtomicSessionStore.kt",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-sources", type=Path)
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    source = args.upstream_sources
    if source is None:
        gradle = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        archives = list((gradle / "caches/modules-2/files-2.1/io.github.jan-tennert.supabase/auth-kt-android/3.0.3")
                        .glob("**/*sources.jar"))
        if len(archives) != 1:
            parser.error("Pass --upstream-sources with the pinned Auth3.0.3 Android sources.jar")
        source = archives[0]
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit("Upstream archive checksum mismatch; refusing to bless different source")
    manifest, delta, patch = {}, [], []
    with zipfile.ZipFile(source) as archive:
        originals = {n for n in archive.namelist() if n.endswith(".kt")}
        if len(originals) != 58:
            raise SystemExit("Unexpected source archive inventory")
        for name in sorted(originals):
            original = archive.read(name)
            local = (VENDOR / name).read_bytes()
            manifest[name] = {
                "upstream_sha256": hashlib.sha256(original).hexdigest(),
                "proof_sha256": hashlib.sha256(local).hexdigest(),
                "byte_identical": original == local,
            }
            if original != local:
                delta.append(name)
                patch.extend(difflib.unified_diff(original.decode().splitlines(keepends=True),
                                                 local.decode().splitlines(keepends=True),
                                                 fromfile="auth3.0.3/" + name, tofile="proof/" + name))
    if set(delta) != EXPECTED_DELTAS:
        raise SystemExit("Unexpected upstream patch scope: " + repr(delta))
    additions = sorted(str(p.relative_to(VENDOR)).replace("\\", "/") for p in VENDOR.rglob("*.kt")
                       if str(p.relative_to(VENDOR)).replace("\\", "/") not in originals)
    if set(additions) != EXPECTED_ADDITIONS:
        raise SystemExit("Unexpected added SDK source files: " + repr(additions))
    result = {
        "upstream_archive_sha256": digest,
        "upstream_source_count": len(originals),
        "byte_identical_source_count": len(originals) - len(delta),
        "patched_upstream_files": delta,
        "new_sdk_files": additions,
        "verification_scope": "source provenance only; resolve application dependencies separately",
        "files": manifest,
    }
    if args.report_dir:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "auth-proof-provenance.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        (args.report_dir / "auth3.0.3-proof.patch").write_text("".join(patch), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, indent=2))


if __name__ == "__main__":
    main()
