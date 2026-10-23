# IMFIT — Implementation Plan (Persiapan → Selesai)

**ID:** IMFIT-RPS-PLAN-001 · **Versi:** 1.0 · **Dibuat:** 23 Oktober 2026  
**Titik awal historis:** B1.1a storage proof; **status dan next action aktual hanya di Implementation Report**, bukan header plan ini.  
**Dokumen inti tetap:** `IMFIT_FEATURE_SPEC.md` + file ini + `IMFIT_IMPLEMENTATION_REPORT.md`.

## 0. Protokol kerja wajib untuk agent (ulang sampai selesai)

**Siklus tunggal:** `READ 3 DOCS → VERIFY REPO → CHECK APPROVAL/BLOCKER → IMPLEMENT SMALLEST SAFE SLICE → TEST → UPDATE REPORT YANG SAMA → REPEAT`.

1. **Awal setiap sesi:** baca ketiga file inti saja, berurutan **Feature Spec → Implementation Plan → Implementation Report**. Pahami *next action*, persetujuan saat ini, hasil test yang benar-benar pernah dieksekusi, dan protected baseline. Jangan mengandalkan pesan/summary lama sebagai sumber status lebih baru daripada repository dan report.
2. **Audit singkat aktual:** periksa branch/HEAD, `git status`, dirty/untracked files, dependency graph dan protection hashes terkait slice. Semua angka di report memiliki tanggal; **jangan asumsikan repo tidak berubah**. Arsip historis boleh ditelusuri sebagai bukti tambahan bila diperlukan, tetapi bukan dokumen keempat yang wajib dibaca agent.
3. **Keputusan:** jika instruksi dan scope **jelas serta sudah disetujui**, langsung kerjakan slice berikut tanpa meminta persetujuan administratif per fase. Jika pilihan produk, perubahan SDK/storage/backup/schema/backend, atau permission khusus **belum jelas**, ajukan **satu pertanyaan keputusan terarah** (ID, opsi, rekomendasi, dampak, boundary tertahan). Jangan memilih default yang mengubah keamanan/privacy pengguna. Lanjutkan bagian aman yang independen, bukan melewati P0.
4. **Implementasi:** perubahan terarah, pertahankan pekerjaan pengguna, original tests, Room8, SDK proof/source provenance, enam-issue baseline dan state aplikasi normal. Hindari reset/revert/checkout paksa, destructive migration/fallback, credential logging, dan modifikasi perangkat/production backend tanpa izin.
5. **Verifikasi:** jalankan acceptance test yang relevan dahulu, lalu regressions demo dan production, dan device/env yang diizinkan. Tuliskan *exact commands*, jumlah pass/fail/skipped, fixture/API, proof boundary, hash/path evidence. Test yang tak berjalan = `NOT_TESTED`, bukan PASS. Kegagalan desired assertion tidak boleh di-ignore/dilemahkan; hasil B0 characterization unsafe tidak menjadi safety PASS.
6. **Update hanya `IMFIT_IMPLEMENTATION_REPORT.md`:** perbarui checklist, tabel gate, blockers, keputusan, execution log dan next action dengan hasil terbaru. **Jangan membuat report/proposal/ADR/plan vN baru** untuk setiap fase. Semua proposal/decision record baru ditambahkan sebagai seksi/tabel pada **report yang sama**, lalu spec/plan ini hanya diperbarui jika kontrak/urutan yang disetujui memang berubah. Simpan test artifacts mentah di build/evidence path biasa, bukan dokumen laporan baru.
7. **STOP terarah:** pada safety P0, evidence tidak valid, perubahan di luar authorization atau destructive/real-backend risk, tandai gate `FAIL_SAFETY/BLOCKED_*`, tulis pilihan dan bukti ke report, tanyakan keputusan. Jangan lanjut ke boundary yang bergantung pada gate itu. Setelah keputusan diberikan, rekam approval verbatim/ringkas dengan tanggal dan lanjutkan siklus.
8. **Selesai:** hanya jika seluruh checkbox target dan mandatory gate Phase G PASS, tetapkan `READY_FOR_USER_RELEASE_APPROVAL`. Tidak otomatis melakukan commit/push, deployment, migrasi perangkat normal atau mengklaim rilis.

**Single-source editing:** spec = perilaku final, plan = langkah/gate, report = status/keputusan/evidence dinamis. Jangan menulis angka test PASS baru ke plan/spec. Arsip historis tetap boleh disimpan tanpa dihapus, namun agent tidak perlu membuat dokumen inti ke-4.

### Format pertanyaan saat keputusan/blocker

> **Decision [ID] — [boundary].** Temuan terverifikasi: [...]. Opsi A: [...] / opsi B: [...]. Rekomendasi dan risikonya: [...]. Yang tetap bisa dikerjakan tanpa keputusan: [...]. **Setujui opsi mana?**

Catat jawabannya dalam decision register report, jangan menganggap diam sebagai persetujuan. Persetujuan umum B1–G tidak mencakup automatic approval SDK-BIND, legacy/offline, D2/D6, backend, schema9, RELEASE atau E2E destruktif.

## 1. Baseline / persiapan (`PREP`, sebelumnya A+B0)

**Fakta baseline terakhir:** `fix-bug`, HEAD menurut audit terakhir `5c0c813e262ec7d004f9e72922e0ba98e7444087` (baca ulang); 578/578 baseline files identik pada audit desain terakhir, 20/20 Room8/revision files preserved; A/B0 characterization selesai, **bukan** safe-sync PASS.

- [ ] Snapshot aktual tracked+untracked paths, hash 20 file Room8/revision, schema6/7/8, baseline tests, SDK 58-source provenance and dirty work. Simpan manifest/evidence tanpa mengubah index.
- [ ] Reconcile dokumen inti dengan current working tree, tandai perbedaan di report, bukan restore source lama.
- [ ] Pastikan tests hanya isolated diagnostic packages pada device, aapt2 package-ID verified sebelum install/cleanup; jangan `clear`/uninstall app normal. SDK MockEngine menggunakan host invalid, tidak menembak project Supabase nyata.
- [ ] Siapkan test harness deterministic barriers, endpoint ledger, redacted logs; required toolchains dan permissions. Rerun baseline hanya jika perubahan yang akan dibuat memerlukan delta proof.

**Gate PREP:** inventory aman dan next slice diketahui. Tidak menuntut semua histori dibaca berulang atau membuat dokumen audit baru.

## 2. B1.1a — Durable Auth storage recovery: keputusan → proof → gate

### 2.1 Decision wajib sekarang (belum otomatis approved)

- **REC-AUTH-PROOF:** desain, implementasi, pengujian `AtomicSessionStore`, versioned envelope, receipt, conditional durable CAS dan filesystem-backed adapter **hanya** `sdk/auth-fence-proof` dengan credential sintetis.
- **REC-AUTH-COMMIT:** jika final commit **terbukti** COMMITTED secara durable untuk exact operation, `VerifiedCommitted` boleh menjadi candidate recovery meski caller sempat exception; bila unresolved → `Ambiguous/Unavailable`. Exception saja bukan bukti commit/rollback.
- **LEGACY-AUTH-PROOF:** raw `UserSession` lama tetap bytes-as-is dan typed `LegacyUnverified`; tidak dipercaya otomatis, diberi receipt palsu, dipindah constructor, dihapus, atau memaksa sign-out/reauth. Keputusan UX/migrasi legacy **normal app** terpisah.

**Jika belum disetujui:** STOP coding storage; boleh melakukan analisis/fixture non-mutating dan menunggu jawaban. Setelah approval, freeze format/API/failure policy lebih dahulu **di plan atau report ini**, tidak menulis ADR baru.

### 2.2 Urutan implementasi teknis proof-only

1. Review real SDK Auth3.0.3+Settings1.3.0 source and pinned artifacts, license/checksum. Existing module opt-in `-PsdkFenceProof=true`: 58 imported Kotlin SDK sources (56 upstream-identical+2 patched), `AtomicSessionAuthority`; normal app **tidak** disubstitusi.
2. Freeze `formatVersion`, canonical encoding/digest bounds, exact `storeIncarnation + generation + operationId + expected predecessor + phase + owner/session binding + receipt`, file naming/key, maximum size, typed outcomes dan compatibility/overflow. Hash bukan enkripsi/signature/server-token-validity proof.
3. Implement satu **atomicity domain**: private proof filesystem adapter, shared in-process coordination + cross-process lock/CAS, atomic replace, readback, file+directory durability assumptions yang benar-benar disokong platform. Jangan melabel `SharedPreferences.apply()`/`SettingsSessionManager` legacy sebagai atomic CAS/durable receipt; constructor legacy bisa memindahkan/menghapus key.
4. Implement `readVerified`, `prepare`, `verifyPrepared`, `commitIfCurrent`, `resolve(operationId)`, `readCommitted` dengan exact predecessor/generation checks. `PREPARED` tidak memberi trust; `COMMITTED` + exact verifiable receipt baru eligible, setelah account/epoch dan auth policy gate. Tidak ada fallback otomatis ke session A setelah B gagal.
5. Setiap operation menangkap credential+ticket sebelum network/restore; short lock order **SDK session fence → storage lock** atau **SDK session fence → Room**; tidak menjalankan network/Worker wait di fence, tidak `runBlocking` UI. Stale operation, cancellation dan storage error tidak menghidupkan owner lama/menimpa successor.
6. Uji original desired `ambiguousPersistFailureMustNotBecomeTrustedRestoreAfterAuthorityRecreation` **tanpa perubahan assertion**; tambahkan positive actual-committed restore serta proof receipt-write-then-throw, fresh handle, restart dan failpoints.

### 2.3 G-STORE01–10 — checklist acceptance wajib

| Gate | Must prove / minimum oracle |
|---|---|
| `G-STORE01` | Unsupported/missing capability, malformed envelope/receipt/owner/digest, oversize/overflow → typed safe outcome; bytes preserved |
| `G-STORE02` | before/after PREPARE, stage before-write & write-then-throw → no staged/legacy trust after recreation |
| `G-STORE03` | readback error, mismatched/torn/truncated bytes, unexpected predecessor → no memory publication or repair-delete |
| `G-STORE04` | exact CAS/ABA, interleaved prepare C, duplicate completion; positive COMMITTED restore & no stale B overwrite |
| `G-STORE05` | final receipt write-then-throw: exact durable COMMITTED B may resolve VerifiedCommitted; unknown → unavailable; never infer from exception |
| `G-STORE06` | cancellation before/during/after commit and publication, shutdown; no lock leak, no rollback claim of already committed record |
| `G-STORE07` | two instances/threads/**processes**, lock domain, A→B→A, clear/recreate, counter overflow; deterministic total order |
| `G-STORE08` | original red test + positive filesystem close/reopen SDK test; API31, API24/API34 as available; OS kill/restart separately; fsync/power loss claims limited |
| `G-STORE09` | raw/corrupt/empty legacy, unknown future version, offline/downgrade; non-mutating `LegacyUnverified`, no auto reauth/logout |
| `G-STORE10` | metadata-only/DB-only/session-only backup skew; detect what can be detected; self-consistent complete rollback **cannot** be detected by self-contained receipt only: record limitation/decision, no false PASS |

**Gate B1.1a:** original recovery test PASS, positive committed recovery PASS, G-STORE applicable test oracles PASS pada proof JVM + authorized API31; cross-process/durability/platform/backup limitations explicitly classified. Jika mandatory property tak dapat dibuktikan → `BLOCKED_DECISION/ENVIRONMENT`/`FAIL_SAFETY`, bukan bypass ke SDK-BIND.

## 3. B1.1b — Penuhkan Auth SDK mutation surface (proof SDK, belum normal app)

- [ ] Audit+patch **setiap** writer: startup/no-session, import, clear/sign-out transition, login/signup/anonymous/OTP, PKCE/deep link/MFA, user lookup/update/unlink, manual/auto refresh/expiry, error/session-missing/failure, storage restore, Android reset/lifecycle. Existing `enableLifecycleCallbacks=false` tetap, timer tak diganggu.
- [ ] Operation ticket captured **sebelum** asynchronous work; stale completion tidak mengubah memory/storage/jobs/owner/credential. Same-owner reauthentication new identity epoch; valid renewal credential generation terpisah.
- [ ] MockEngine actual SDK outgoing `Authorization` header dengan delayed send A→B: payload A tak dikirim sebagai B (`0 A-as-B`), tanpa token raw di logs.
- [ ] Cross-process lock, cancellation/failure, offline, credential persistence recovery, downgrade/legacy non-destructive paths. Maintain exact upstream patch/provenance, license/notice, no broad SDK/BOM upgrade or duplicate classes.

**Gate G-SDK01–06 (proof part):** all writer families covered by actual rebuilt SDK, storage gate PASS, credential pairing proven. SDK is still isolated; don't report actual app isolation PASS from proof.

## 4. SDK-BIND decision & B1.2 — Integrasi real SDK ↔ Room ↔ app

**Separate user decisions before normal app substitution:** `LEGACY-AUTH-NORMAL` (offline/reauth/legacy keys/read/migration/downgrade/restore), `SDK-BIND` (replacement dependency/DI), backup/privacy boundary if changed. No downgrade to APK schema7 over Room8. If scope outside approved Auth-only dependencies → ask first.

- [ ] Inspect dependency graph on demo/production to guarantee patched Auth artifact actually used once; no upstream duplicate; App normal not installed/cleared as verification workaround.
- [ ] Bind **one shared awaited SDK authority/fence** to `SessionCommitGate`/short Room transaction and `SyncManager` actual capture/ack, importer and publication; not a second independent mutex/listener. Only identity epoch eligible may acknowledge exact local Room8 revision; network outside transaction.
- [ ] Protect login/logout transition, auth init/restore, register, deeplink, refresh/expiry; owner-aware `observeCurrentUser` switch via epoch, user-scoped by-ID reads, ViewModel/pager/cache/selection/results/avatar URLs; invalidate A before B becomes visible. Don't change logout retention/purge policy here.
- [ ] Run unchanged `SyncAccountIsolationGateTest.oldUploadMustNotAcknowledgeAfterSdkAccountABATransition` both variants, commit-first/transition-first, A→B→A, same owner relogin, HTTP credential match and all original S06 tests; pending survives errors and account change.

**B1.2 gate:** real SDK/Room/SyncManager/observers S03–S06 PASS; desired account test **2/2 PASS**, S06 **6/6 PASS on current code** (old historical result is not enough), no wrong-owner delivery.

## 5. B1.3 — Shared refresh coordinator / Worker / outbox

- [ ] Wire existing `RefreshCoordinator` + typed `RefreshOutcome` to all production origins: `SyncRunner.run`, `refreshMasterData`, reconnect, initial/foreground, WorkManager, future gestures. No extra screen-specific sync engine.
- [ ] Keys `(owner, epoch, dataset)`, shared Deferred results, user mutable lane and independent catalog lane. Initial catalog/profile fetched once per generation, subset Home/Progress/Profile/History joins overlapping dataset flights. Manual joins while active; repeated pull storms bounded.
- [ ] Upload-first barrier/revision freshness, no network under Room/session lock; immutable credentials for each request. Prevent old flight finalization closing new spinner, handle offline/401/403/500/timeout/cancellation terminal outcomes truthfully.
- [ ] Durable pending queue with late-write-after-scan and process restart handshake; don't change WorkManager `APPEND_OR_REPLACE` to KEEP absent lost-wakeup proof. Blocked protocol queue never loops indefinitely or claims all-synced.
- [ ] Counter assertions for 10 callers, failed-auto storms, full/master overlap, worker burst, pending/retry and cancellation; log redacted counters not credentials.

**B1.3 gate:** S01/S02/S09/S10 on actual runtime seams; no lost wakeup or duplicate flights. Local/mock-only limitations explicit.

## 6. B1.4 — Technical safety regression & B0 reconciliation

- [ ] Rerun demo/production unit, actual Room8 migration 4/5 (known reconstructed limitations)/6/7→8, overflow, pending flags, revision/reopen and original desired S06; Auth proof+binding; sdk/worker, real SDK owner/credential tests.
- [ ] Reconcile **three B0 unsafe-expectation tests per variant** whose assertions now fail after legitimate fixes: map old case → positive safety regression; strengthen assertions and preserve original historical evidence. Cases: transactional parent+children capture, old-ack same-ms pending retention, dirty-child importer protection. Do **not** convert still-unsafe cases into PASS.
- [ ] Verify S01–S06 and S09/S10 technical gate; explicitly track S07/S08/S12 which await B2/D2/D6, S11 awaits D/E. No blanket "all S01–S12 PASS".

**B1 technical exit:** no desired P0 failures, no faked SDK safety, correct counts/test classifications, protected baseline intact. Only then tackle B2 policy changes.

## 7. B2 — D2/D6 safety policy, snapshots, coverage

**Ask choices (not auto-approved):**

- `D2`: online pending actions *retry/cancel*; offline pending sign-out-retain vs explicit discard; owner-scoped retention, expiration, data export/removal, active session/timer, shared device privacy, SDK local/server signOut outcomes, backup/transfer exclusions. Recommendation: no silent pending purge, offer explicit safe offline exit **only after privacy controls are approved and tested**; no permanent offline lockout.
- Subdecision `D2-BACKUP` kontrak A: implement auth-secret/trust-metadata exclusions untuk cloud/transfer dan fresh authentication setelah restore, preserve pending+owner isolation tanpa mengubah ordinary offline startup. Implementasi normal-app tetap pada ordered binding/privacy gates; pilihan ini bukan approval otomatis semua D2 atau SDK-BIND. Status approval dan evidence di report; proof receipt tidak dianggap anti-rollback anchor.
- `D6`: template CREATE/UPDATE/rename/empty child replacement currently multi-request destructive. Recommendation: block before **first remote write**; consider DELETE/tombstone policy separately, keep pending+typed blocked reason, don't retry-loop; let unrelated safe datasets continue. Full atomic server template protocol/RPC requires independent approval.
- `D3/D8`: scope overlay gating 6 screens; per-owner dataset coverage mark only after verified committed snapshot, crash gap/corrupt/restore → unknown, not legitimate empty; no schema9 automatic.

**Implementation after approval:** owner-safe/logout staged transition and failure UX; backup/restore safeguards incl Room WAL/SHM and SDK storage; importer/catalog no destructive apply from short page, cap, duplicate, parent-child version uncertainty; coverage and typed Partial/Blocked. Exercise cache may upsert valid verified individual records only if freshness safe, never silently deactivate missing. No new backend mutation without permission.

**Full Phase B exit:** S01–S10 and S12 safety requirements with approved D2/D6 behavior/pass proofs, no silent pending deletion, owner leaks, destructive unverified apply; remote server guarantees remain separately Phase G.

## 8. C — Availability contracts + enam content-aware shimmer

- [ ] Four-axis state (LocalContent/RemoteRefresh/LocalPage/PendingWrites) owner/context aware in relevant repositories/VMs; local read exceptions not coerced to empty.
- [ ] Same-context cache always wins over shimmer; unknown initial show section skeleton at ~150ms; offline/no cache → typed unavailable; verified empty only with valid coverage.
- [ ] Implement shape-aligned Home/List/Picker/Progress/Profile/Date-History skeleton per Feature Spec; neutral/light-dark/reduced motion static visible/accessibility, no fake values/checkbox role.
- [ ] Gating fullscreen initial overlay only per approved D3; preserve Navigation 3, dialog/scrim and timer. Unit fake clock 20ms/250ms + Compose cache/owner/empty/errors/locale/font/theme/IME tests.

**Exit C:** six skeletons implemented and tested, no flicker, no false zero/empty, no out-of-scope redesign.

## 9. D — Room Paging 3 + parity + benchmark

- [ ] Check compatible Paging runtime/compose/room-paging/testing deps; approve D4 tradeoff and D5 picker sorting behavior first; only related deps, no BOM-wide upgrade.
- [ ] SQL query/filter/count/ID-resolution consistent across Room production and local deterministic demo fixture. Active/category/subgroups/search literal `%` `_`, Unicode, excluded IDs, duplicate name, stable lexical ID ORDER BY, placeholder semantics.
- [ ] Pager `pageSize30/initial60/prefetch10` provisional; 81/150/1500 same-device variant measurements p50/p95 query/first content, allocation/frames + complete traversal oracle. No faux `take(fullList)` pagination.
- [ ] Same-query Room invalidation/anchor retained, new-filter old generation not displayed; full selected ID and metric query unaffected. Append/retry/search → 0 remote HTTP calls.

**Exit D:** correct SQL/count/ordering/traversal, performance decision documented in report, local S11 evidence; if not viable review alternative without silently dropping infinite-scroll scope.

## 10. E — Exercise List + Picker integration

- [ ] Pull `CATALOG` through shared coordinator; local pager invalidated by Room commit; append/footer error/retry local only, search/appbar/footer outside indicator.
- [ ] List with category/filter/query, stable ID anchors, empty/unavailable differentiations, >=150 fixture.
- [ ] Picker selection IDs independent of pages, full DAO COUNT/revalidation/exclusions, invalid selected states shown, confirm one-shot under owner+epoch, no lost selected on page append/query/filter, sticky footer/IME safe.
- [ ] Compose/device tests gestures, diagonal/short/empty scroll, simultaneous remote commit and account switch, footer, accessibility.

**Exit E:** **2/2** Pull-to-Refresh, **2/2** genuine local Infinite Scroll, no wrong-owner/result, 0 remote append.

## 11. F — Home/Progress/Profile/History Pull-to-Refresh

- [ ] Home `PROFILE+TEMPLATES+HISTORY`; section-level cache, no avatar blocking or routine reset.
- [ ] Progress `PROFILE+HISTORY`; owner history complete metrics, selected day/month preserved, current two cards unchanged. Keep `PROG-SEM-01`, `PROG-VIS-02` separately OPEN.
- [ ] Profile `PROFILE`; local language/theme/settings intact and avatar failure independent.
- [ ] History date `HISTORY`; chosen date remains; four daily totals & existing session summary no set-details/global history.
- [ ] All gestures non-blocking, auth-aware, preserve Navigation3, 8dp header gap, bottom nav, input/focus/scroll anchors, timer/notifications, trusted autofill/completed volume; C cache/offline/error regressions.

**Exit F:** **4/4** remaining Pull-to-Refresh, aggregate **6/6**, visual/owner/date/metrics parity PASS.

## 12. G — Complete regression, authorized E2E & release readiness

| Gate | Verifikasi wajib (tidak boleh disubstitusi oleh test primitive) |
|---|---|
| `G-UNIT` | Demo/production unit+instrumentation; all *desired* assertions green, historical unsafe transparently mapped |
| `G-MIGRATE` | Room8 7→8 rows/pending/active-session/provenance, supported old chains, reopen/OS death, forward-only policy |
| `G-AUTH` | G-STORE+G-SDK full, real SDK-BIND, legacy/reauth/outcome, A→B→A, header owner, no stale UI |
| `G-QUEUE` | Single-flight, retry/cancellation, wakeup/pending, degraded protocol no infinite retry |
| `G-LOCAL` | 6 refresh, 2 local scroll, 6 shimmer, reduced-motion/cache/accessibility |
| `G-PERF` | 81/150/1500 benchmark with parity and accepted tradeoff |
| `G-DEVICE` | Android API24/31/34 where available, real process kill/restart, offline, rotation, timer/background, minified release |
| `G-PRIVACY` | Approved D2 storage/backup/device transfer, merged manifest/evidence; no unproven encryption |
| `G-REMOTE` | Authorized disposable Supabase project & A/B test accounts, RLS, RPC, pagination/remote protocol; no prod destructive tests |
| `G-VISUAL` | Approved refs, six screens theme/ID-EN/fonts/IME/semantics; Progress separate backlog visible |

**Terminal states:** `READY_FOR_USER_RELEASE_APPROVAL` only when all mandatory gates PASS. Missing authorized E2E/device/visual/privacy evidence ⇒ `BLOCKED_ENVIRONMENT`/`BLOCKED_DECISION`, not production-ready. No automatic commit/push/deploy/normal app install.

## 13. Milestone & STOP rules

```text
PREP [baseline safe]
 → DECISION REC-AUTH-PROOF/COMMIT/LEGACY-AUTH-PROOF
 → B1.1a G-STORE01–10
 → B1.1b complete SDK writers/G-SDK proof
 → DECISION SDK-BIND + LEGACY-AUTH-NORMAL/backup boundary
 → B1.2 actual SDK+Room+SyncManager+observers
 → B1.3 shared coordinator+Worker
 → B1.4 B1 TECHNICAL PASS
 → DECISION D2/D6/D3/D8 (and D4/D5 before Paging)
 → B2 FULL B SAFETY PASS
 → C [shimmer] → D [paging] → E [list/picker] → F [4 screens]
 → G [full regression/E2E/visual] → READY_FOR_USER_RELEASE_APPROVAL
```

**STOP immediately at boundary:** unknown auth store receipt trust, unproven durability/CAS, old epoch ack, A request with B credential, wrong-owner publication, silent pending purge, destructive remote template write without policy, off-policy backup/legacy changes, data migration loss, lost wakeup, no required SDK hook, schema/backend/dependency-scope expansion, unsafe normal-device operation or failed desired P0 assertion. Record blocker + user-facing choice **only in implementation report**. Resume after approved corrective decision and regression PASS.

## 14. Per-slice update payload (append/update report only)

At completion of **every smallest slice**, modify `IMFIT_IMPLEMENTATION_REPORT.md` in place:

- timestamp/date; branch/HEAD; changed source files and protected-file comparison; decision IDs/approval;
- exact build/test commands, total executions PASS/FAIL/error/skipped, variant/API, raw evidence location/hashes and positive/negative assertions; distinguish **historical** and **new**;
- gate result (`DONE_VERIFIED`, `PARTIAL`, `NOT_STARTED`, `BLOCKED_DECISION`, `BLOCKED_ENVIRONMENT`, `FAIL_SAFETY`), unresolved risks and rollback implications;
- **one** next executable action, **one** decision question if truly required; update completion checklist.

If tests fail, preserve evidence and remedy before moving next. Never create `PHASE_B1_..._REPORT_2.md`, `PLAN_v5.md`, new ADR/report files for each run; update these three core documents. User may independently request separate artifacts, but not part of this recurring workflow.
