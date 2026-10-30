# IMFIT Online-Only — tasks.md

> **Dokumen kerja agent | 30 Oktober 2026 | Fokus: handoff — T7 PASS; T8 build/fresh-install terbaru PASS, anomali Summary belum selesai**
>
> Tujuan: menghasilkan **APK IMFIT online-only yang benar-benar bisa dipakai untuk menyelesaikan workout dan menyimpan hasilnya di Supabase**. Bukan menambah arsitektur, dokumen, RPC, atau jumlah test.

**Baseline — 29 Oktober 2026: MVP internal preview PASS, T0–T5 selesai.** APK dan konfigurasi uji ada pada hasil T5 di bawah; bukti final di [Implementation Report §7.13](full-online-only-implementation-report.md#713-mvp-internal-preview-t5). Bukan production/upgrade legacy atau distribusi klien eksternal.

**Status akhir sesi — T6/T7 selesai; T8 PARTIAL, bukan Closed Beta siap tanpa blocker.** Pengguna menyetujui cutoff global dan 11 migration kandidat `a0f9b9b` ke `ltcxltahkrjnsjaxndsa`; hosted kini 14 migration/21 RPC dan 108 smoke checks PASS. APK productionRelease HTTPS hosted sudah dibangun dan diuji fresh install melalui internet. Summary run pertama sempat loading >60s; run normal/fresh-install berikutnya PASS tetapi penyebab/fix belum terbukti dan waiver belum diberikan. Instruksi akhir: catat selesai/belum selesai, commit/push dokumentasi dan handoff perangkat lain; jangan lanjut investigasi spekulatif. Source/migration final tetap baseline, tanpa tracing permanen; `.idea/misc.xml` milik pengguna tetap defer.

## 1. Definisi MVP (Minimum Viable Product)

**Target aktif:** kandidat **Closed Beta fresh install melalui HTTPS Supabase Hosted**, bukan upgrade legacy atau rilis publik. T0–T5 adalah baseline internal preview lokal historis; izin deployment T7.6 dan hasil hosted/perangkat terbaru dicatat di §3 dan Report §7.16.

**Perjalanan pengguna yang wajib selesai:**

`Login → Katalog gerakan → Buat/pilih rutinitas → Start Workout → Edit/selesaikan set → Finish → Summary → History/Progress → Tutup & buka kembali → Hasil tetap ada`.

**Kondisi berhasil:**

- Data bisnis baru (template, active workout, workout log, progress, profile) dibaca/ditulis melalui Supabase; memori Android hanya menyimpan state sementara. Tidak ada penulisan bisnis baru ke Room, antrean upload offline, atau full-sync tersembunyi.
- Hasil **Saved** hanya tampil setelah server mengonfirmasi. Finish atomik menghasilkan **satu** workout log sekalipun respons hilang lalu dicoba di-resolve/retry. Error jaringan tidak ditampilkan sebagai data kosong atau sukses.
- Login/logout dan ganti akun tidak membocorkan data; auth/RLS pemilik data bekerja. Timer berjalan lokal tanpa request setiap detik; cold-offline tidak mengklaim sesi terverifikasi.
- APK internal dapat dipasang, skenario di atas lulus **melalui UI + HTTP/backend nyata**, dan data tersimpan setelah restart aplikasi.

**Di luar MVP ini (jangan jadikan blocker preview fresh install):** migrasi/restore Room 6/7/9, import legacy massal, matriks lintas perangkat/API lengkap, load test ribuan transaksi, optimasi prematur, audit seluruh skenario crash, publish signing, rollout/cutoff production, dan fitur/navigasi baru. Semua tetap menjadi **pekerjaan sebelum rilis production atau upgrade pengguna lama bila relevan**, bukan dianggap selesai/dihapus. Jika APK ditujukan untuk upgrade instalasi lama, MVP fresh-install **tidak boleh** dipakai sebagai bukti aman.

## 2. Sumber kebenaran dan baseline

**Hierarki pemakaian:** tasks.md = pekerjaan sekarang → Feature Spec = kontrak/kebutuhan → Plan = desain/backlog → Report = bukti/blocker. Prioritas MVP tidak membatalkan kontrak integritas data. Baca/perbarui checklist ini setiap iterasi; setelah penyelarasan satu kali, jangan kembali ke roadmap release penuh sebelum MVP selesai.

- **Kontrak bisnis/keputusan:** `docs/plans/full-online-only-feature-spec.md` (D01–D06, K01–K05, AC relevan).
- **Desain teknis dan kewajiban release penuh:** `docs/plans/full-online-only-implementation-plan.md`.
- **Bukti, blocker dan histori:** `docs/plans/full-online-only-implementation-report.md`.
- **Checklist dan prioritas pekerjaan saat ini:** **`tasks.md` ini**. Baca bagian dokumen lain **hanya yang relevan** dengan task aktif; jangan mengaudit/menyalin ulang semua dokumen di setiap iterasi.

**Baseline historis:** T0–T5 selesai untuk MVP lokal fresh install, bukan readiness hosted/upgrade. Ringkasan ada di §3; bukti detail, command, hasil gagal/deferred dan artefak tetap dipertahankan pada Implementation Report §1 dan §7.13. Checkpoint source aktual ditetapkan oleh T6, bukan diasumsikan dari dirty snapshot T5.

## 3. Prioritas kerja — kerjakan berurutan

Gunakan hanya status `[ ]` belum, `[x]` selesai, `[!]` blocker. Tandai `[x]` **hanya jika ada bukti saat eksekusi**; source presence atau PASS historis tidak otomatis berarti MVP end-to-end lulus.

### Selesai — T0–T5: MVP internal preview lokal

| Task | Status historis | Hasil / batas bukti |
| --- | --- | --- |
| T0.1–T0.3 | PASS — 29 Oktober | Audit baseline dan tiga blocker MVP; bukan audit live baru. |
| T1.1–T1.3 | PASS lokal | 14 migration/21 RPC, 35 smoke Auth/PostgREST/RLS/Finish-response-loss. Backup lokal T1 tidak diuji restore; bukan backup hosted. |
| T2.1–T2.3 | PASS | Fix exercise ID teks dan conflict review setelah reload; 51 unit terarah gabungan T2/T3. |
| T3.1–T3.5 | PASS preview | 4 UI/HTTP smoke production nyata: journey, restart, conflict review, response-loss/account isolation. |
| T4.1–T4.3 | PASS | Cutover runtime existing diverifikasi; 37 unit terarah. Legacy reader/database/WAL/schema 6/7/9 tetap dipertahankan. |
| T5.1–T5.4 | PASS preview | 417 production/424 demo unit + 3 smoke final UI/HTTP; bukan production/upgrade/minified/hosted PASS. |

**Artefak historis:** [APK internal preview 20261029](../../app/build/outputs/apk/internal-preview/imfit-online-only-internal-preview-20261029.apk), productionDebug, package `com.diajarkoding.imfit.modaldiagnostic`, signing debug/internal, SHA256 `56fda795603d4edc9f63c52a98e7235b07606cbacea2b4b1611420af3a006af4`. API baked-in `http://127.0.0.1:54321`; membutuhkan backend lokal dan koneksi perangkat sesuai Report, **bukan APK Beta internet**.

**Rujukan bukti:** [Report §1](full-online-only-implementation-report.md#1-ringkasan-status) untuk milestone T0–T4; [Report §7.13](full-online-only-implementation-report.md#713-mvp-internal-preview-t5) untuk test/build/UI, artefak/config/evidence T5 dan batas FAIL/deferred. T5 memakai dirty snapshot saat pengujian; bukti itu tidak otomatis menjadi verifikasi commit terisolasi. Tidak mengulang implementasi atau full suite hanya untuk merapikan histori.

### P0 — T6: Git checkpoint dan baseline source final

- [x] **T6.1 — Inventaris checkout.** Branch/HEAD/tree dan staged/unstaged/untracked dicatat; index kosong. `.idea/misc.xml` milik pengguna di-defer dan tidak disentuh; `tasks.md` direvisi sesuai scope.
- [x] **T6.2 — Tetapkan baseline Android dan migration.** Gunakan commit existing `a0f9b9b41284dd40fddfac2897c6df59f4052d39` (tree `925481423304dd7388ae642bdbca0be1fd4ea571`). Semua 14 migration dan 404 input fingerprint source tercakup commit; tidak perlu commit baru/staging/push.
- [x] **T6.3 — Bekukan kandidat persiapan.** Fingerprint byte Git committed `544c6a58ebb2b32e978c82a64b9dfc499f04cc7ad1c736e0d885739fb2201e44` **sama dengan source T5**. Perbandingan 404 file: 267 hanya berbeda CRLF/LF, **0 drift isi non-EOL**. Bukti build/UI T5 tetap historis; tidak mengklaim build/CI/perangkat baru. Perubahan source/migration berikutnya wajib direview ulang sebelum T7.6.

**Selesai jika:** baseline commit Android + 14 migration dapat diidentifikasi dan dicocokkan dengan input review/rehearsal/deployment. Checkpoint bukan izin deploy dan bukan klaim build/CI baru.

**Hasil T6 — 30 Oktober 2026: PASS checkpoint source existing.** Commit T2/T3/T5 sudah ada dalam histori lokal (`2a2a5c6`, `b3a76b7`, `3555209`, `a0f9b9b`); asumsi source T5 masih belum di-commit tidak berlaku pada checkout ini. SHA256 migration berdasarkan byte committed dicatat di Report §7.14; raw checksum checkout Windows berbeda untuk empat migration awal karena EOL, bukan migration SQL baru. Tidak fetch atau mengklaim sinkron server; tidak commit/push.

### P0 — T7: Persiapan Supabase Hosted Closed Beta

**Target tetap:** project utama existing **`ltcxltahkrjnsjaxndsa`**, API **`https://ltcxltahkrjnsjaxndsa.supabase.co`**. Jangan membuat project Supabase baru. Backend bersama ini **bukan database kosong**; data existing harus dipertahankan.

**Baseline handoff pengguna, bukan pemeriksaan baru pada pembaruan checklist ini:** status `ACTIVE_HEALTHY`, PostgreSQL `17.6.1.054`, Mumbai (`ap-south-1`); 3 migration applied versus target lokal 14 migration/21 RPC. Metadata melaporkan 7 template dan 9 workout log; jumlah ini bukan inventaris backup terverifikasi. RPC Online-Only 0/21; RLS aktif pada 9 tabel public; authenticated masih dapat direct write pada 6 tabel bisnis. `active_sessions.version/status` dan `workout_templates.revision` belum ada; FK active session → template masih `ON DELETE CASCADE`. Publishable key tersedia, tetapi nilainya tidak dicantumkan di dokumen.

- [x] **T7.1 — Identifikasi target.** Project existing dan URL hosted ditetapkan sesuai handoff pengguna; tidak perlu membuat project baru. **PASS handoff, bukan verifikasi live baru.**
- [x] **T7.2 — Audit awal hosted.** Migration/RPC/schema/RLS dan gap terhadap backend lokal telah diperiksa read-only menurut handoff pengguna. **PASS audit awal handoff**; pencocokan seluruh file migration checkout aktual tetap T7.5, bukan dianggap selesai di sini.
- [x] **T7.3 — Analisis kompatibilitas dan keputusan inventaris selesai untuk cutoff Beta.** Review source/caller legacy ada di Report §7.14. Perangkat terhubung SM-A315G tidak memiliki package IMFIT saat inventaris ADB. Pengguna mengonfirmasi tidak ada instalasi aktif/sesi/pending lokal relevan yang perlu dipertahankan; ini pernyataan operator, bukan scan seluruh perangkat. Cutoff global diterima; angka `1 / 1.0` tetap bukan identitas generasi client. Tidak mengklaim upgrade/restore legacy PASS.
- [x] **T7.4 — Backup hosted dan pemulihan terisolasi PASS.** Credential DPAPI operator dari folder `IMFIT\HostedPrep` berhasil dipakai pada akun Windows yang sama; psql read-only/TLS 1.3 dengan CA resmi dan `sslmode=verify-full` PASS. Bundle backup privat mencakup snapshot database penuh, metadata roles tanpa password dan pelengkap objek/initial ACL platform yang tidak otomatis direplay pg_dump. Restore pada PostgreSQL `17.6.1.054` tanpa jaringan/port publik memverifikasi isi 49 tabel, schema/ownership/RLS, definisi fungsi, 14 role/19 membership dan 1.853 privilege/grantor/grant-option. Bundle, cakupan dan prosedur di Report §7.15; jangan menganggap file dump tunggal sebagai bundle lengkap. Salinan sementara sudah dibersihkan; database existing/hosted tidak ditimpa.
- [x] **T7.5 — Rekonsiliasi, rehearsal dan dry-run PASS.** 14 source migration cocok baseline T6; isi tiga applied hosted cocok source, sebelas pending direhearsal sebagai role `postgres`, transaksi per migration. Salinan menghasilkan 14 versions/21 RPC dengan grants/RLS final; 48 tabel tetap identik, tiga history lama utuh dan tepat 11 history baru. CLI pinned **2.116.0** melalui URL eksplisit tanpa password di argumen berhasil `migration list --db-url` dan `db push --dry-run --db-url` (exit 0, tepat 11 file berurutan), tanpa bootstrap role. Setelahnya history hosted tetap tiga applied; seluruh schema/roles/grants/data snapshot tidak berubah. Penilaian GraphQL dan batas warning Auth dicatat, tidak mengubah konfigurasi atau mengklaim smoke hosted T7.8.
- [x] **T7.6 — APPROVED eksplisit.** Pengguna memilih “Tidak ada yang perlu dipertahankan”, “Izinkan cutoff global”, dan “SETUJUI deployment + T7.8/T8”. Izin mencakup target `ltcxltahkrjnsjaxndsa`, kandidat `a0f9b9b`, 11 migration dalam urutan rehearsal termasuk pencabutan SELECT/DML bisnis dan EXECUTE RPC legacy global, smoke akun/data uji khusus, serta APK production flavor HTTPS hosted + publishable key dan uji fresh install internet. Client lama boleh kehilangan akses bisnis; tidak ada jaminan upgrade legacy. Restore destruktif/reset/purge/penghapusan data existing/push/rilis publik tidak termasuk.
- [x] **T7.7 — Deployment hosted PASS.** Approval T7.6 dipenuhi. Checksum komponen bundle, 14 migration/source kandidat dan tiga history/SQL cocok; 49 tabel pra-deploy identik snapshot sehingga backup baru tidak diperlukan. Sebelas migration diterapkan urutan rehearsal sebagai postgres, transaksi per migration + history, seluruhnya COMMIT; lock timeout 15s/statement timeout 120s tidak terpicu. Final 14 versions/SQL exact, tiga history asli utuh, 21 signature/grant RPC sesuai, RLS/private deny dan profile grants minimum PASS. Digest/count 48 tabel non-history pada kolom legacy tetap identik; kolom schema baru tidak disamakan dengan perubahan isi lama. Bukti privat `..\HostedPrep\deploy-20261030T090156Z\`.
- [x] **T7.8 — Smoke hosted PASS: 108 pemeriksaan.** Dua akun synthetic, real Auth password JWT, RLS/profile/cross-owner/anon denial, direct business read/write dan legacy writer deny, CAS stale reject, immutable request ID/exact replay PASS. Template → Start → edit/complete → Finish → Home/History/Progress/markers/prefill dan persistence sesudah reauth PASS. Finish upstream HTTPS 200 lalu respons diputus; discovery + retry menghasilkan receipt/workout sama dan SQL scoped tepat satu workout per sesi. Recovery v1/fail-closed, blockers, cursor owner binding, archive/fingerprint CAS/replay PASS. Digest/count sembilan tabel bisnis existing tetap identik baseline, 7 template/9 log lama utuh. Setup signup awal HTTP400 sebelum akun tercipta; penyebab tidak tertangkap/NOT_TESTED. Akun synthetic disiapkan SQL scoped, lalu login HTTP nyata PASS; Auth config tidak diubah. Bukti `..\HostedPrep\hosted-smoke-evidence.json`; ini bukan bukti APK/T8.

**Urutan handoff:** T6/T7 PASS → T8.1/T8.2 PASS → fresh-install/internet/persistence terbaru PASS → selesaikan anomali Summary T8.3 atau minta penerimaan known issue eksplisit. Jangan mengulang deployment/rehearsal yang sudah selesai atau menganggap pergantian perangkat sebagai izin reset/regrant/restore.

**Definition of Done T7:** **21 RPC tersedia di hosted**, schema/grants sesuai kontrak final, data lama tetap aman, dan alur workout hosted/persistence/response-loss lulus tanpa duplikasi. Backup dengan restore terverifikasi, review kompatibilitas dan approval eksplisit merupakan prasyarat; PASS lokal T1–T5 atau metadata read-only tidak menggantikannya.

**Hasil deployment — 30 Oktober 2026:** backup/rehearsal historis ada di [Report §7.15](full-online-only-implementation-report.md#715-hosted-backup-restore-dan-dry-run); keputusan dan eksekusi sekarang di §7.16. Hosted kini **14 migration/21 RPC**, bukan lagi tiga migration. Data existing 7 template/9 workout log tetap utuh pada verifikasi pascadeploy; data synthetic smoke dicatat terpisah. Source/migration/Spec/Plan tetap; konfigurasi lokal APK berada dalam izin T8.

**Batas credential dan koneksi:** password didekripsi hanya di memori, dipasok lewat environment proses anak, tidak dicetak atau dimasukkan ke argumen URL/dokumen/Git. Referensi plaintext sudah dibersihkan; file credential/CA operator dipertahankan. TLS client→session pooler terverifikasi, sedangkan `pg_stat_ssl` pada leg pooler→PostgreSQL melaporkan false; batas ini dicatat tanpa mengubah SSL enforcement hosted. Login/link dan MVP tidak diulang.

**T7 keseluruhan PASS.** Cutoff global sudah diterapkan; Auth/RPC/RLS/CAS/idempotency/response-loss/persistence dibuktikan hosted. T8 masih PARTIAL karena satu anomali Summary awal belum terjelaskan, bukan karena backend belum dideploy.

**Perintah operasional:** persiapan memakai CLI pinned **2.116.0**, URL eksplisit dan `db push --dry-run --db-url`. Deployment approved mengikuti rehearsal melalui `psql -X -w -v ON_ERROR_STOP=1`, stdin UTF-8 binary, transaksi SQL + history per migration, CA verify-full, password hanya environment anak. Tidak memakai bootstrap cli_login_postgres, include-all/roles/seed, history repair atau restore hosted.

### P0 — T8: APK Closed Beta melalui internet

**Prasyarat:** T7 PASS dan scope distribusi tester disepakati. Target fresh install tidak menjadi bukti upgrade aman; jika tester memakai instalasi/data lama, gate recovery/upgrade terkait tetap wajib, bukan clear-data/bypass preflight.

- [x] **T8.1 — Konfigurasi build Beta PASS.** Dua nilai Supabase local.properties diganti melalui mekanisme existing ke HTTPS hosted `https://ltcxltahkrjnsjaxndsa.supabase.co` dan key resmi `sb_publishable_`; properti lain dipertahankan. Pemeriksaan DEX APK kandidat menemukan URL/key tersebut dan tidak menemukan `sb_secret_`. Tidak memasukkan service-role ke APK/Git.
- [x] **T8.2 — Build production flavor dan identifikasi APK PASS.** Normal minified `:app:assembleProductionRelease` sukses, internal debug signing existing, package/version `com.diajarkoding.imfit` / 1.0 (1). APK kandidat: [imfit-production-closed-beta-1.0-1.apk](../../app/build/outputs/apk/closed-beta-candidate/imfit-production-closed-beta-1.0-1.apk), 9.760.915 byte, SHA256 `ff4c77f3db2ef01bbae1079197560d3f53f19329043b3587a53b260906ddf05d`. Byte identik dengan APK normal yang diuji; bukan diagnostic/instrumentation APK, bukan publish signing.
- [!] **T8.3 — Skenario terbaru PASS; anomali awal belum selesai.** Samsung SM-A315G, fresh install ulang APK normal yang sama + login → catalog → Start → edit21.50kg×13 → complete → Finish → Summary279.50kg/set1 → Home/Progress/History → force-stop/reopen PASS melalui Wi-Fi internet tervalidasi, mobile0/reverse kosong. Offline refresh mempertahankan data + error; restore jaringan dan retry eksplisit pulih. SQL: lima sesi UI berbeda → lima workout canonical COMPLETED, count1 per sesi, running0. Initial Summary loading >60s tetap FAIL historis/unresolved; dua diagnostic hanya investigasi, semua tracing sudah dihapus. Belum ada fix, waiver atau label Closed Beta siap. Bukti `..\HostedPrep\final-fresh-install\`, `original-normal-recheck\`, `hosted-ui-persistence-after-five-sessions.json`.
- [x] **T8.4 — Handoff teknis dan dokumentasi selesai.** APK kandidat/hash, hasil PASS/FAIL, blocker dan langkah perangkat lain dicatat di Report §7.16/§9; artefak privat tidak ikut Git. Instruksi akhir mengizinkan satu commit dokumentasi dan push `origin/online-only`; hasil SHA/verifikasi snapshot/push dicatat pada handoff chat. Handoff kandidat bukan penerimaan anomali atau rilis publik.
- [ ] **Remaining — Summary/onboarding/release.** Diagnosis kausal dan fix/regresi Summary minified atau waiver eksplisit masih diperlukan. Penyebab setup signup HTTP400 belum diketahui; login akun synthetic PASS bukan bukti signup tester baru. Upgrade/restore legacy, matriks penuh, publish signing dan rilis publik tetap di luar scope/NOT_TESTED.

**Melanjutkan di perangkat lain:** gunakan branch `online-only` terbaru setelah push, baca Report §7.16 dan §9; siapkan JBR/JDK21, Android SDK37, Gradle wrapper9.5.0 dan local.properties HTTPS/publishable key. Transfer APK/evidence dan akun uji secara aman di luar Git; DPAPI credential Windows tidak portabel. Hosted sudah 14 migration/21 RPC: jangan replay migration, reset/purge atau menyiapkan backend Docker untuk mencoba APK ini.

**Definition of Done T8:** APK yang diserahkan terbukti memakai HTTPS hosted + publishable key dan alur workout/persistence lulus dari jaringan tester tanpa backend lokal. Status **Closed Beta siap** hanya setelah T7 dan T8 PASS; bukan klaim production release/upgrade legacy penuh.

## 4. Aturan kerja agent (anti-scope-creep)

1. **Satu task aktif → ubah kode minimal → test terkait → checklist → lanjut.** Kerjakan perubahan yang bisa dibuktikan menghasilkan kemajuan alur MVP.
2. **Reuse first:** jangan menambah RPC, migration, repository, abstraction, framework paging/session, runner test, atau dokumen bila yang existing cukup. Perubahan schema hanya untuk gap nyata, additive bila mungkin.
3. **Dilarang mengulang audit menyeluruh**, membuat laporan baru, menguji semua varian berkali-kali, atau memperluas acceptance criteria tanpa blocker nyata/izin pengguna.
4. **Test proporsional:** perbaikan UI → test UI/VM terkait; perubahan SQL/RLS → test SQL/Auth/HTTP terkait; perubahan finish → receipt/idempotency. Full regression satu kali pada kandidat final atau setelah perbaikan risiko besar.
5. **Tidak boleh menghapus safety dasar:** RLS, owner isolation, CAS, request ID/idempotency, finish atomik, unknown≠success, timer cold-offline verification, dan pemeliharaan data legacy. Ini bukan fitur opsional.
6. **Izin mengikuti milestone:** T7.6 mengotorisasi 11 migration/cutoff dan T7.8/T8; instruksi akhir mengotorisasi commit/push dua dokumen hasil yang direview. Ini tidak mengizinkan reset/purge, restore menimpa hosted, penghapusan data existing, regrant legacy atau rilis publik. `.idea/misc.xml`, credential, local.properties, backup dan APK tidak ikut commit.
7. **Bila blocker:** tulis penyebab, dampak MVP, opsi perbaikan terkecil. Minta keputusan pengguna **hanya** jika membutuhkan perubahan scope, risiko data/hosted, atau pilihan produk yang belum diputuskan. Lanjutkan task independen yang aman.
8. **Dokumentasi hemat token:** update checklist `tasks.md` per task; Implementation Report hanya satu ringkasan milestone (bukti, status, blocker). Feature Spec/Plan diubah **hanya** jika kontrak/kebutuhan berubah. Jangan menyalin log panjang atau fingerprint berulang.
9. **Titik berhenti terbaru:** finalisasi bukti dan handoff Git/perangkat lain sesuai instruksi pengguna. Jangan meneruskan investigasi Summary, menerima waiver, atau membuka scope production/legacy secara otomatis; catat pekerjaan yang belum selesai secara eksplisit.

## 5. Format laporan agent (wajib singkat)

Setelah satu task selesai, laporkan maksimal lima baris:

```text
Task: Tn.n — PASS / FAIL / BLOCKED
Perubahan: <file/fitur inti>
Bukti: <tes relevan atau skenario UI + hasil>
Blocker: <tidak ada / satu blocker spesifik>
Berikutnya: <ID task berikutnya>
```

**Definition of Done per tahap:** **MVP internal preview T0–T5 PASS dan APK diserahkan** (baseline selesai). Kelanjutan **Hosted Closed Beta** mengikuti DoD T7/T8 dan approval terpisah di §3; belum selesai hanya karena baseline lokal PASS. **Production release/upgrade legacy tetap gerbang terpisah** dengan keputusan dan pengujian sendiri.
