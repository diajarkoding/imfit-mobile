# Auth 3.0.3 - isolated IMFIT B1.1 proof

**Not a production dependency. Not a complete SDK safety implementation.**

This optional Android library reconstructs only the Auth module from the pinned upstream Android
source archive. It is included using `-PsdkFenceProof=true`. The normal `:app` SDK coordinates,
Supabase BOM and production DI are unchanged; there is no dependency substitution or publication.

## Provenance

- Upstream: `io.github.jan-tennert.supabase:auth-kt-android:3.0.3`, 58 Kotlin files including shared
  `settingsMain` sources. 51 original files remain byte-identical. The seven exact patches are
  `Auth.kt`, `AuthImpl.kt`, `AuthenticatedSupabaseApi.kt`, `mfa/MfaApi.kt`, `Utils.kt`,
  Android `Android.kt` and Android `setupPlatform.kt`.
- Four proof additions: `AtomicSessionAuthority.kt`, `AtomicSessionStore.kt`,
  Android `AtomicStoreFiles.kt` and Android `FileAtomicSessionStore.kt`.
- Source archive SHA256: `58c785e03908506120653bd6a2a636db6ebb33e4194169a20d08bf7f64fea1ce`.
- License: MIT, copyright 2024 Jan Tennert; see `LICENSE` (upstream 3.0.3 tag).
- Module dependencies follow the Android 3.0.3 POM. `secure-random-jvm:0.3.2` is also needed on the
  compile classpath when rebuilding PKCE source (published POM lists it as runtime). No version bump.
- Compiler internal opt-in and common/platform source flags reconstruct upstream's compilation
  environment; using an opt-in is not reflection or private-member replacement.

## Verification

```powershell
python tools/verify_auth_fence_provenance.py --report-dir <evidence-directory>
.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest :auth-fence-proof:assembleDebugAndroidTest --console=plain
python tools/run_auth_fence_proof_device.py --aapt2 <aapt2.exe> --evidence-dir <evidence-directory> --serial <authorized-serial> [explicit target opt-in]
```

The verifier creates an exact source hash inventory and upstream patch diff without rewriting files.
The build runs locally without backend credentials. SDK tests use synthetic sessions and Ktor
MockEngine with an invalid host, never an actual Supabase project. Failure logs must be reported,
not ignored or normalized to PASS.

## Proof boundary

The prototype exposes one awaited authority for SDK writers, local commit leases, refresh jobs,
MFA verification/enrollment, PKCE preparation and explicit external-flow import. Proof-only
entrypoints such as stop-refresh, OAuth URL preparation and deep-link handling are suspending so
they can await that authority; the normal application still uses upstream Auth 3.0.3.

This is **not** proof of every startup/restore interleaving, real provider state echo, browser/process
re-creation, expired durable recovery, all external-flow initiation paths or complete SDK-to-Room
binding. A green mechanism test must not be called G-SDK01..06, SDK-BIND or B1 PASS. No application
binding may be enabled until the complete writer matrix and mandatory tests pass.

`SdkPersistenceRecoveryGateTest` is a desired **blocking** assertion: a store that writes then throws
has no durable receipt/commit record in the original SessionManager contract. The original assertion
is kept unchanged and now passes against the proof `AtomicSessionStore`; it must not be ignored,
relaxed or reclassified. Exact commands, counts, artifact hashes and remaining limitations are kept
in `docs/plan/IMFIT_IMPLEMENTATION_REPORT.md`.
