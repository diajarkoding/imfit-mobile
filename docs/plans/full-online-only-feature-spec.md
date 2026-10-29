# IMFIT Full Online-Only — Feature Spec

## 0. Metadata

| Field | Detail |
| --- | --- |
| Nama fitur | Full Online-Only Data Architecture |
| Feature ID / ticket | N/A — belum diberikan |
| Status | **Checkpoint pengembangan untuk handoff perangkat**, bukan release-ready. Working tree recovery/session/profile/clean cutover memiliki bukti host §7.11; scope transfer dan gate commit terisolasi di §7.12. SQL/HTTP, device/upgrade/UI tetap belum diverifikasi |
| Versi dokumen | 2.1 |
| Tanggal dibuat / diperbarui | 26 / 29 Oktober 2026 |
| Target rilis | Internal release-ready lokal, termasuk normal production release minified; tanpa deployment/cutoff Supabase utama dan bukan publish signing |
| Platform / topology | Android native; single application module `:app` |
| Source scope | Shared `app/src/main`, production/demo DI, SQL migration, test terkait |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` |
| Baseline eksekusi F1a/F1b | `online-only@c0be6b823c6e8350a4a0f1c157d239dcdfd28e57`; baseline audit source di atas tetap historis |
| Confidence | Audit/live/PASS lama tetap historis; bukti host dirty snapshot 29 Oktober ada pada laporan §7.11, bukan bukti backend, perangkat, commit terisolasi atau deployment |
| Dokumen pasangan | [Plan implementasi, gap analysis, dan evidence map](full-online-only-implementation-plan.md) · [Laporan implementasi berkelanjutan](full-online-only-implementation-report.md) |

**Aturan membaca:** **Baseline** adalah snapshot audit 27 Oktober, bukan runtime current. **Source current** mengikuti cutover yang dicatat pada laporan §7.9: kedua flavor memakai `OnlineWorkoutRepository`, `OnlineSessionStore`, dan guarded Start. **Historical runtime PASS** mencakup 387 production/395 demo unit, assemble, connected demo dan smoke REST lokal yang tercatat di §7.9; tidak dijalankan ulang pada revisi dokumentasi 29 Oktober. **Proposed** menandai kontrak tambahan, bukan kemampuan verified; gate implementasi/recovery/UI/upgrade/timer/release baru berstatus **NOT_TESTED** sampai dibuktikan. Keberhasilan historis bukan acceptance aplikasi penuh atau deployment. Spec tetap di `docs/plans`; tidak membuat dokumen kontrak/laporan keempat.

**Pembaruan v2.1:** label source-current/Proposed dan remaining gaps pada bagian v2.0 di bawah mempertahankan snapshot audit step 0 sebelum perubahan implementasi ditinjau. Working tree kini memuat implementasinya, tetapi source presence bukan acceptance PASS. Status terbaru dan batas izin host-only mengikuti [laporan §7.11](full-online-only-implementation-report.md#711-persiapan-commit-host-only--29-oktober-2026); D01–D06 dan AC01–AC34 tidak berubah.

**Scope handoff terbaru:** pengguna meminta commit dan push checkpoint agar pekerjaan dapat dilanjutkan di perangkat lain. [Laporan §7.12](full-online-only-implementation-report.md#712-checkpoint-handoff-perangkat) membedakan gate checkpoint host dari acceptance rilis penuh; tidak ada pengurangan AC atau klaim deployment.

Path Kotlin pendek relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`. Revisi 1.2 menambahkan audit metadata Supabase live secara **read-only** pada 27 Oktober 2026: tidak membaca row bisnis, tidak memanggil RPC aplikasi, dan tidak melakukan DDL/DML. Audit ini membuktikan schema/grants/policy yang terlihat oleh role read-only, bukan perilaku runtime, hasil serangan RLS, atau kesiapan environment uji. Tidak ada persetujuan visual yang diasumsikan.

## 1. Ringkasan (Overview)

IMFIT membutuhkan internet untuk mengambil atau mengubah data bisnis. Supabase menyimpan template/rutinitas, profil, riwayat, serta draft active workout yang telah dikonfirmasi. Android mempertahankan hasil baca dan input sementara dalam memori, bukan database workout lokal atau antrean upload baru.

### Problem statement

**Baseline:** remote reads masih diimpor ke Room; workout aktif disimpan lokal; finish menulis aggregate Room lalu mencoba upload. Katalog/picker memfilter dataset lengkap lokal; Progress/calendar menghitung dari seluruh history. Pola ini tidak dapat diubah menjadi remote-only dengan mengganti DAO menjadi request tanpa mengubah pagination, recovery, statistik, dan timer ownership.

### Goals

- Menghilangkan reconciliation/local-cloud sync dari runtime data baru.
- Read remote terukur melalui halaman summary dan detail on-demand.
- Refresh, loading/error/empty, retry, serta pergantian query/akun dapat diprediksi.
- Active session confirmed dapat dipulihkan setelah proses mati atau melalui device lain.
- Revision, CAS, transaksi, dan idempotency ditegakkan server.
- Menjaga data pengguna instalasi lama melalui recovery transisi yang aman.

### Non-goals

- Menjamin input belum acknowledged bertahan setelah process death tanpa local durability.
- Menyediakan offline catalog/history dari disk atau offline workout baru.
- Realtime subscription/collaborative editing, merge otomatis dua device, atau auto-upload background.
- Mengganti engine countdown/alarm, desain visual menyeluruh, kebijakan autentikasi, atau menambah fitur PR baru.
- Membangun layar edit profil baru semata karena repository memiliki fungsi update.
- Mengubah rumus volume/durasi historis secara diam-diam.

### Batas produk wajib

**Confirmed bukan sekadar tampil di layar.** Input dianggap tersimpan permanen hanya setelah receipt/snapshot server cocok. Input yang belum confirmed dapat hilang saat proses aplikasi mati. Keputusan G0 D01 menerima konsekuensi ini; target tetap Full Online-Only dan **tidak** menambahkan durable local draft atau antrean offline baru.

### Keputusan G0 (ditetapkan 27 Oktober 2026)

| ID | Keputusan | Konsekuensi kontrak |
| --- | --- | --- |
| D01 | Kehilangan input yang belum dikonfirmasi server saat process death **dapat diterima**; target tetap Full Online-Only. | Dirty input hanya di memori; recovery hanya memuat snapshot confirmed server. UI tidak boleh menampilkan input dirty sebagai tersimpan. |
| D02 | Setelah process mati/reboot dan sesi belum dapat diverifikasi, timer **menunggu verifikasi**. | Tidak ada business action atau alarm completion cold-offline yang mengklaim sesi aktif. Anchor boleh dipertahankan untuk reconcile, bukan untuk merekonstruksi draft. |
| D03 | Active draft kedaluwarsa setelah **30 hari tanpa aktivitas**; receipt lengkap disimpan **minimal 90 hari**. | Expiry menghasilkan status `EXPIRED`/review, bukan silent purge. Receipt terminal dan linkage dedup minimum dipertahankan selama resource terkait masih ada; cleanup memerlukan policy dan persetujuan terpisah. |
| D04 | Kompatibilitas legacy dibatasi pada build yang relevan, bukan seluruh riwayat. Baseline rilis adalah Room **9**; data Room **6/7** dari branch development harus dibaca aman lalu di-bridge atau direview. Schema lain, termasuk Room 4/8/tidak dikenal, **tidak ditebak**. | Deteksi memakai `user_version`/schema Room aktual, bukan `versionName` atau `versionCode` karena semua build yang diperiksa masih `1.0`/`1`. Upgrade destruktif dan fallback yang membuang data dilarang. |
| D05 | Konflik sesi lokal–remote atau hasil finish ambigu harus **direview sebelum Start**. | Hanya preflight `Ready` untuk owner/auth epoch current yang boleh memanggil create RPC; tidak ada auto-merge atau overwrite. |
| D06 | Project Supabase `ltcxltahkrjnsjaxndsa` adalah database bersama development/testing/production; **belum ada staging terpisah**. | Pengujian berisiko memakai schema terisolasi atau Supabase lokal. Reset, purge, dan perubahan destruktif memerlukan persetujuan eksplisit; migration harus additive bila memungkinkan. |

### Status kesiapan dan blocker (29 Oktober 2026)

Snapshot G0/live 27 Oktober dan hasil fase 28 Oktober tetap evidence historis. Cutover §7.9 sudah memasang facade/session writer/guard Start; pekerjaan berikutnya melengkapi protokol/UI/dependency dan proof, bukan mengulang pemasangan dari nol.

| Area | Status current / evidence | Kriteria siap lokal |
| --- | --- | --- |
| G0 keputusan produk | **Selesai** — D01–D06 dipertahankan verbatim | Tidak mengubah kehilangan input unconfirmed, cold-offline atau scope database bersama |
| G1 audit backend | **Selesai read-only historis** — 27 Oktober | Audit live tidak diganti klaim deployment baru |
| Backend lokal existing | **Historical PASS** — §5.4.G dan laporan §7.9 | Empat RPC recovery Proposed (§5.4.J), replay/migration/security final harus dibuktikan; gate baru NOT_TESTED |
| Active Workout remote | **Source current** — facade/store/guard terpasang kedua flavor | Dirty/debounce/flush AC13/15, typed UI, pending registration, timer authoritative dan proof production belum selesai; NOT_TESTED |
| Legacy recovery | Inventory/read-only reader, archive dan route `LegacyRecovery` existing | Selected-item receipt-backed import/resolution, intent/local-active/server-legacy dan installed-data proof NOT_TESTED |
| Room/runtime cutover | Facade bukan writer Room, tetapi auth/exercise/coordinator/template guard masih membawa graph lama | Migrasikan caller lalu hapus writer/API/dependency obsolete tanpa menghapus database pengguna; NOT_TESTED |
| Internal release-ready | **Belum siap** | Semua AC01–AC34/T01–T16 dipetakan ke evidence; normal/instrumented minified, HTTP/UI, upgrade/restore dan timer lulus; NOT_TESTED |
| Hosted rollout | **Deferred / di luar target** | Tidak deploy/cutoff/reset/purge Supabase utama; publish signing gate terpisah |

B01 ditutup D01. B02–B05 tetap harus dinilai melalui evidence akhir sesuai scope lokal: backend/recovery/integrity proof tidak boleh dianggap selesai hanya dari dokumen atau facade remote. Database bersama tidak dipakai fixture. Gunakan backend lokal dan emulator disposable; fake/demo bukan proof SQL/RLS atau upgrade production.

**Catatan evidence live:** audit read-only menemukan hanya tiga migration Agustus yang tercatat deployed (baseline, catalog seed, hardening). `save_template_revision`, `template_write_receipts`, kolom `workout_templates.revision`, serta seluruh 16 nama RPC target untuk read/session/receipt **tidak ditemukan**. Public schema memiliki 9 tabel dengan RLS aktif dan 29 policy; `anon` tidak memiliki table grant, tetapi `authenticated` masih memiliki grant tulis langsung (INSERT/UPDATE/DELETE sesuai tabel) pada 7 tabel; `profiles` tidak memiliki DELETE. `active_sessions` belum memiliki `version`/`status` dan FK template masih `ON DELETE CASCADE`. Ini bukti metadata, bukan uji runtime atau uji serangan RLS.

## 2. User Stories & Acceptance Criteria

Acceptance criteria berikut dipertahankan verbatim. Backend lokal dan runtime Android tertentu memiliki **PASS historis** (§5.4.G–I dan laporan §7.9); acceptance penuh belum dibuktikan end-to-end. Gate tambahan §5.4.J dan §6.1–6.7 tetap **NOT_TESTED**, termasuk installed upgrade/restore, production HTTP/UI dan normal/instrumented minified release. Jangan menggeneralisasi keterbatasan ini menjadi “semua Android belum diuji”.

| ID | User story / Given–When–Then |
| --- | --- |
| AC01 | Sebagai pengguna, saat membuka query baru, saya melihat initial loading lalu remote content, empty, atau error; tidak melihat hasil query/akun sebelumnya sebagai hasil baru. |
| AC02 | Given daftar dengan 60 item termuat, when refresh sukses, then daftar diganti oleh generation baru dari cursor awal; halaman lama tidak digabung kembali. Prefetch boleh memuat halaman berikutnya, bukan wajib mengunduh ulang 60 item. |
| AC03 | Given query yang sama dan data lama, when refresh gagal, then data lama tetap tampil sebagai belum diperbarui dengan pesan retry; cursor generation gagal tidak dipakai append. |
| AC04 | Given empty/initial error, when pull-to-refresh atau tombol Retry digunakan, then request halaman awal dapat dicoba tanpa harus ada item. |
| AC05 | Given mendekati akhir list, when tidak refresh/append/error-latched dan hasMore, then hanya satu append untuk cursor itu berjalan. |
| AC06 | Given append gagal, when list tetap discroll, then tidak muncul retry loop; item lama tetap ada dan footer Retry menunggu aksi eksplisit. |
| AC07 | Given search/filter/sort/owner berubah, when respons lama tiba, then respons diabaikan dan tidak mengubah items, cursor, loading, atau error query baru. |
| AC08 | Given akun A→B→A, when request dari epoch A pertama selesai, then request itu tetap stale walaupun user ID kembali A. |
| AC09 | Given cursor sama/tidak maju, metadata inkonsisten, atau cursor query lain, when respons diterima, then pagination error teramati dan append dihentikan. |
| AC10 | Given ID duplikat antarhalaman, when disajikan, then satu stable identity ditampilkan; count statistik tidak berasal dari jumlah item yang termuat. |
| AC11 | Given picker berpindah halaman/filter, when kembali atau confirm, then pilihan berdasarkan ID tetap ada, exclusion dihormati, dan result hanya diterapkan ke context/session asal. |
| AC12 | Given start request, when backend belum mengonfirmasi, then workout aktif belum dianggap dibuat; jika ada sesi aktif existing, tawarkan lanjutkan/review, bukan overwrite diam-diam. |
| AC13 | Given edit weight/reps lalu complete, when save debounce belum selesai, then complete menunggu flush sukses dan expected version terbaru; Saved hanya setelah acknowledgement. |
| AC14 | Given dua device mengubah version sama, when keduanya submit, then satu sukses dan satu conflict; tidak ada silent last-write-wins. |
| AC15 | Given jaringan terputus, when ada input unconfirmed, then input tetap di memori selama proses hidup, status belum tersimpan jelas, dan mutasi baru diblokir sampai recovery/retry. |
| AC16 | Given proses dimatikan lalu online kembali, when recovery, then aplikasi memuat confirmed snapshot server; tidak menjanjikan pemulihan input unconfirmed. |
| AC17 | Given finish berhasil di server tetapi respons hilang, when retry/resolve setelah restart, then workout ID dan receipt mengacu pada workout yang sama, tanpa log kedua. |
| AC18 | Given finish dan cancel berjalan bersamaan, when server memutuskan terminal state, then hanya satu hasil terminal menang dan UI menampilkan hasil authoritative tersebut. |
| AC19 | Given finish/cancel error atau outcome unknown, when UI menerima timeout, then tidak bernavigasi sebagai sukses, tidak menghapus draft server, dan menyediakan resolve/retry. |
| AC20 | Given sesi confirmed sedang berjalan, when countdown/ticker berjalan, then tidak ada request remote per detik; timer lokal tidak mengirim mutasi data bisnis. |
| AC21 | Given process cold-start tanpa jaringan, when ada timer anchor lokal, then anchor tidak dipakai untuk merekonstruksi set/draft, tidak menyalakan alarm completion, dan tidak menganggap session pasti aktif; tampilkan recovery unavailable/Unknown. |
| AC22 | Given history lebih dari satu halaman, when membuka Progres/calendar, then totals dan markers mencakup periode remote yang diminta, bukan hanya page yang sudah discroll. |
| AC23 | Given template save ambigu lalu proses mati, when membuka recovery, then server operation discovery memungkinkan review hasil; aplikasi tidak otomatis membuat template pengganti dengan ID baru. |
| AC24 | Given instalasi lama berisi pending A dan pengguna login B, when upgrade/logout/recovery, then data A tetap aman, tidak terlihat/terunggah sebagai B, dan database tidak dibuang menyeluruh. |
| AC25 | Given credential authenticated mencoba direct unversioned write/forged receipt, when final contract lockdown berlaku, then server menolak bypass walaupun owner ID cocok. |
| AC26 | Given screen direcompose atau kembali ke ViewModel yang masih hidup, when query tidak berubah/tidak invalidated, then tidak terjadi fetch berulang tanpa trigger yang sah. |
| AC27 | Given save terkonfirmasi, when invalidation diterbitkan, then hanya state/query terkait ditandai berubah; tidak menjalankan full snapshot pull seluruh fitur. |
| AC28 | Given instalasi upgrade dengan local active draft, pending completion ambigu, atau konflik sesi lokal/remote, when pengguna menekan Start, then create RPC belum dipanggil sampai inventory dan resolution owner current menghasilkan Ready. |
| AC29 | Given pembacaan schema/data legacy atau status remote gagal, when start preflight berjalan, then hasil Unknown/Blocked, bukan asumsi fresh install/tidak ada sesi; Retry/recovery tetap tersedia tanpa membuang data. |
| AC30 | Given konflik legacy owner A telah diselesaikan dan data owner B masih tertahan, when A memulai sesi setelah preflight ulang, then A dapat lanjut tanpa mengunggah/menghapus data B; restore data lama atau pergantian auth epoch memerlukan validasi ulang. |
| AC31 | Given active draft tidak aktif selama 30 hari, when pengguna membukanya, then server menandainya `EXPIRED`/review dan memblokir Start sampai review; draft tidak dihapus diam-diam dan timer/alarm tidak mengklaim sesi masih aktif. |
| AC32 | Given receipt terminal berumur kurang dari 90 hari, when retry/resolve atau discovery dijalankan, then server mengembalikan workout/receipt yang sama dengan payload lengkap; cleanup sebelum batas retention atau tanpa persetujuan tidak dilakukan. |
| AC33 | Given aplikasi `1.0` dengan Room 9, 6, atau 7, when upgrade berjalan, then aplikasi mendeteksi schema aktual: Room 9 menjadi baseline, Room 6/7 di-bridge atau direview, dan schema 4/8/tidak dikenal diblokir tanpa destructive fallback. |
| AC34 | Given integration/concurrency/security test berisiko, when staging terpisah tidak tersedia, then pengujian memakai schema terisolasi atau Supabase lokal; reset/purge/perubahan destruktif pada project bersama tidak dijalankan tanpa persetujuan eksplisit. |

## 3. User Flow

### Baseline entry points yang terverifikasi

- `Main` menampung tab Home, Gerakan, dan Progres.
- `WorkoutDetail(workoutId)` dan `EditWorkout(workoutId)` mengacu pada rutinitas/template.
- `WorkoutHistory(date)` membuka `WorkoutHistoryDetailScreen`, daftar/detail sesi per tanggal.
- `ExerciseList(categoryName)`, `ExerciseSelection(...)`, dan `ActiveExerciseSelection(sessionId, excludedIds)` adalah jalur katalog/picker.
- `ActiveWorkout(templateId)` dan `WorkoutSummary(workoutLogId)` adalah jalur sesi/hasil.
- `Profile` memiliki state remote terpisah; route `LegacyRecovery`, `LegacyRecoveryViewModel`/`LegacyRecoveryScreen` dan entry Profile → recovery sudah tersedia, bukan UI baru paralel.

### Target flow daftar

```text
Masuk query → initial load cursor awal → content / empty / initial error
Content → scroll → append → tambah item / footer Retry / end reached
Content → refresh → generation baru → ganti data / tampilkan stale + Retry
Query atau akun berubah → kosongkan state lama → initial load query baru
```

### Target flow workout

```text
Start → legacy/remote preflight → Ready → create RPC → confirmed active snapshot
Preflight conflict/Unknown → recovery/review/Retry → validasi ulang sebelum create
Edit → dirty input → flush command → receipt → confirmed version baru
Complete set → flush edit → command completed → receipt → mulai rest lokal
Finish → flush semua edit → atomic finish RPC → receipt → summary
Timeout → outcome unknown → resolve session/operation → receipt atau explicit retry
Restart → remote recovery → active / paused / completed receipt / cancelled / expired
```

Back dari active screen tidak otomatis membatalkan server session. Cancel adalah aksi terpisah dengan konfirmasi dan hasil remote. Server session yang completed tetapi summary belum dibuka tetap ditemukan melalui recovery.

Feed history global dan dedicated template list **belum merupakan route existing**. Default minimum adalah mempertahankan entry per tanggal dan Home template section dengan remote contracts. Penambahan feed/list route memerlukan keputusan scope, caller, parameter, dan navigasi baru sebelum PR UI dibuat.

## 4. UI/UX Specification

### 4.1 Pola per fitur

| Permukaan | Target read | Refresh | Pagination | Write |
| --- | --- | --- | --- | --- |
| Gerakan root/category | Metadata kategori bounded; catalog summary per query | Ya | Ya pada hasil list, bukan grid kategori statis | Read-only |
| Exercise picker | Catalog summary remote + selection memori terpisah | Ya | Ya | Tidak menulis katalog; apply ke context pemanggil |
| History per tanggal / feed bila ditambahkan | Summary remote dengan filter tanggal/periode | Ya | Ya bila hasil panjang | Tidak menambah editing log baru dalam scope ini |
| Detail log / summary | Aggregate detail remote | Ya / Retry | Tidak | Read-only kecuali fitur existing memang mendukung mutasi |
| Home | Bounded template summary, last workout, active session summary | Ya | Tidak pada dashboard; section/list template boleh paginated bila diperlukan | Template create melalui RPC |
| Rutinitas detail/editor | Complete template snapshot + revision | Reload terkontrol | Tidak pada editor | Revision RPC |
| Progres/calendar | Aggregate dan date markers per periode | Ya | Bukan infinite scroll history untuk menghitung totals | Read-only |
| Profile | Remote profile state terpisah dari history | Ya / Retry | Tidak | Update remote pada caller existing |
| Active workout | Remote recovery + confirmed snapshot memori | Reload sesi eksplisit | Tidak | Versioned commands dan atomic finish |

### 4.2 State → representasi → aksi

| State target | Representasi | Aksi / aturan |
| --- | --- | --- |
| InitialLoading | Skeleton/spinner dalam area layar | Aksi data konflik disabled; back tetap tersedia |
| Content | List/detail authoritative terakhir untuk query ini | Refresh; append bila eligible |
| Empty | Penjelasan belum ada data | Tetap scrollable; refresh dan CTA relevan |
| NoResults | Query/filter tidak cocok | Ubah/reset filter; bukan error jaringan |
| InitialError | Error contextual + Retry | Tidak mengklaim empty; pull refresh tetap tersedia |
| Refreshing | Content lama untuk query sama + refresh indicator | Append tidak berjalan; hanya satu generation current |
| RefreshError/Stale | Snapshot terakhir + Snackbar/banner retry | Jangan mengatakan data sudah terbaru |
| Appending | Footer loading | Tolak append tambahan |
| AppendError | Footer Retry | Tidak otomatis retry karena scroll tetap di ujung |
| EndReached | Akhir daftar | Tidak request cursor berikut |
| Saving / Resolving | Label jelas dekat operasi, aksi konflik disabled | Back/logout harus menangani risiko input/outcome unknown |
| Conflict | Pesan ada perubahan dari perangkat lain | Reload/review eksplisit; jangan silently merge |

Refresh list menggunakan target `PullToRefreshBox` dan lazy scroll container full-size. Empty/error adalah item di dalam container, bukan pengganti non-scrollable. Sediakan tombol Retry alternatif; gesture bukan satu-satunya jalur akses.

### 4.3 Refresh contract

1. Refresh mempertahankan search/filter/sort, tetapi menaikkan request generation dan memulai cursor awal.
2. Cancel/invalidate pekerjaan pagination lama. Respons lama diabaikan walaupun cancellation tidak menghentikan server request.
3. Last-successful snapshot hanya boleh tampil bila **query key lengkap + auth epoch** masih sama.
4. First-page success mengganti dataset generation lama, termasuk bila sukses mengembalikan kosong.
5. Refresh failure mempertahankan snapshot lama sebagai stale. Append dari cursor generation lama tidak diteruskan; pengguna harus refresh/retry dahulu.
6. Tidak mengunduh ulang semua halaman lama untuk mengisi ulang jumlah item yang sama.
7. Paging 3 boleh memicu prefetch sesudah first page berhasil. Spec tidak menjanjikan tepat satu request atau tepat 20 item akhir pada semua viewport.

Target library adalah Paging 3 dengan lapisan presentasi stale snapshot memori bila dibutuhkan. Detail perilaku harus dibuktikan dalam spike; bila exact-one-page replacement tanpa prefetch menjadi syarat produk, keputusan library diubah secara eksplisit sesuai plan, bukan diasumsikan otomatis dipenuhi Paging.

### 4.4 Saving dan offline copy

Copy berikut **usulan**, belum resource ID yang terpasang:

| Konteks | ID | EN |
| --- | --- | --- |
| Confirmed | Tersimpan di server | Saved to server |
| Dirty | Perubahan belum tersimpan | Changes not saved |
| In-flight | Menyimpan… | Saving… |
| Outcome ambigu | Memeriksa hasil penyimpanan… | Checking save result… |
| Offline | Koneksi terputus. Perubahan yang belum tersimpan dapat hilang jika aplikasi ditutup. | Connection lost. Unsaved changes may be lost if the app closes. |
| Conflict | Sesi berubah di perangkat lain. Muat ulang untuk meninjau. | This session changed on another device. Reload to review. |
| Refresh gagal | Data belum berhasil diperbarui | Could not update data |
| Legacy saja | Data lama di perangkat perlu ditinjau | Older on-device data needs review |

Copy existing “Saved on this device / Coba upload” hanya untuk legacy recovery, tidak dipakai sebagai sukses workout baru. Status tidak hanya dibedakan melalui warna.

### 4.5 Reuse, responsive, dan accessibility

- Reuse `IMFITTheme`, `MaterialTheme.typography`, `IMFITColors.training`, token spacing, serta keluarga `RoutineAppBar`/routine controls yang sesuai source.
- Root tab tetap menggunakan shell `MainScreen`; jangan menambah header/bottom-nav ganda.
- Tidak ada mockup baru/visual approval dalam audit ini. Layout existing menjadi acuan konservatif, bukan alasan meniru semua keterbatasannya.
- Target touch minimal 48 dp; font besar/narrow width harus wrap/stack; CTA tidak menutup item/input terakhir atau IME.
- Retry, loading, saving, stale, error, dan selected state memiliki semantics/label lokal; dekorasi tidak menjadi satu-satunya pembawa informasi.
- Gunakan resource `values` dan `values-in`, format angka/tanggal locale, dan istilah rutinitas/gerakan/sesi yang konsisten.

## 5. Technical Design

### 5.1 Architecture overview

```text
Compose → ViewModel/StateFlow/PagingData → domain repositories
    → Supabase remote data sources → PostgREST/RPC/Auth/Storage
    → PostgreSQL (RLS, version, transactions, idempotency)

UI/service/notification → shared session manager in memory → session RPC
Timer renderer/alarm → local clock + operational timer state, bukan workout DB
```

Tidak ada Room read model, RemoteMediator, sync queue baru, snapshot importer, atau pending-upload worker dalam runtime bisnis target. Legacy reader boleh tetap terisolasi selama upgrade membutuhkan recovery; ini pengecualian transisi yang harus dilabeli, bukan fallback produksi baru.

### 5.2 State management dan request identity

Nama berikut adalah **model usulan**, bukan symbols baseline:

| Model/konsep | Isi minimum |
| --- | --- |
| `QueryKey` | auth epoch/owner, normalized search, filter, sort, date window/timezone bila relevan, context katalog/picker |
| Request generation | Angka monoton per query lifecycle/refresh; respons harus cocok dengan query dan generation current |
| Remote list state | Initial/refresh/append load state terpisah; successful snapshot, stale flag, next cursor/end state, error contextual |
| Remote detail state | ID + owner/epoch, loading/content/not-found/error, last confirmed snapshot |
| Session presentation state | Confirmed version/snapshot; dirty input terpisah; in-flight command; saving/error/conflict/unknown; connectivity hint |
| Auth epoch | Berubah pada logout/login/account switch; bukan berubah setiap token refresh yang masih sesi login sama |

`flatMapLatest` dan cancellation membantu pergantian query, tetapi guard identity tetap dibutuhkan sebelum publish. `CancellationException` harus dilempar ulang; jangan dipetakan menjadi error jaringan atau empty. SDK token refresh tidak boleh membuat retry memakai owner berbeda.

StateFlow membawa metadata/detail; `Flow<PagingData<...>>` membawa daftar paginated. `cachedIn(viewModelScope)` adalah cache memori, bukan local business persistence. Recomposition hanya render; network dimulai dari load/query/action yang eksplisit.

### 5.3 Data layer dan pagination contract

Target repository memiliki suspend function untuk page/detail/mutation; implementasi tidak mengambil semua record untuk meniru observer Room.

| Kontrak usulan | Input | Output |
| --- | --- | --- |
| History page | Query tanggal/periode, cursor nullable, limit | Summary page, bukan child sets lengkap |
| Catalog page | Search/category/sort, cursor, limit | Exercise summary page |
| Template page/summary | Owner implicit dari session, cursor/limit bila diperlukan | Summary dengan revision dan count |
| Template detail | Template ID | Parent + semua konfigurasi child + revision konsisten |
| Workout detail | Workout ID | Parent + ordered exercise logs + completed sets konsisten |
| Progress/calendar | Window start/end, timezone | Totals dan date markers periode lengkap |
| Prefill | Exercise ID + target-default provenance | Last confirmed matching performance atau defaults |

Page envelope **usulan**:

```json
{
  "data": [],
  "page_info": {
    "next_cursor": null,
    "has_more": false,
    "limit": 20
  }
}
```

Aturan server/client:

- Limit positif dan bounded; server menolak atau menormalisasi sesuai kontrak, tidak silently full-pull.
- Backend menggunakan `limit + 1` atau cara ekuivalen untuk menentukan hasMore; total count bukan prasyarat append.
- `has_more=true` memerlukan cursor non-null yang maju; `has_more=false` berarti tidak ada cursor lanjut.
- Cursor opaque, versioned, dan terikat canonical query/sort; decode/validate di backend. Cursor bukan token otorisasi, RLS/owner check tetap wajib.
- History order `(end_time DESC, id DESC)`; keyset menggunakan tuple strict-less-than pada halaman berikutnya. Timestamp cursor mempertahankan precision server, tidak dibulatkan ke epoch milliseconds UI.
- Catalog memakai sort deterministik yang disepakati, misalnya normalized name + ID; template updated-time + ID memerlukan refresh/dedup ketika sort key berubah.
- Cursor tidak menjamin snapshot immutable jika record diubah/hilang. Deduplikasi per generation berdasarkan ID, cursor tetap mengikuti metadata server, bukan item terakhir setelah dedup.
- Empty page dengan hasMore/cursor berulang dianggap protocol error; jangan loop request tak terbatas untuk mengejar halaman berikutnya.
- Search debounce 300 ms. Perubahan filter/sort langsung menginvalidasi query; old search results tidak tampil sebagai query baru selama debounce.
- Picker selection disimpan terpisah dari loaded pages. Katalog terhapus/nonaktif divalidasi saat apply/start; item tidak boleh hilang dari pilihan hanya karena page tidak sedang termuat.

### 5.4 API contract dan server integrity

#### A. Existing source contracts

Tabel berikut mempertahankan snapshot baseline/live 27 Oktober; status implementasi lokal terbaru ada di §5.4.G, bukan perubahan pada project utama.

| RPC/table | Status yang dapat dibuktikan |
| --- | --- |
| `save_template_revision` | Ada pada source migration dan dipanggil Android; **tidak ditemukan pada schema live 27 Oktober 2026** dan belum terdeploy |
| `upsert_template_aggregate` | Tersedia live dan di source hardening; bukan kontrak revision/receipt yang dipakai target |
| `upsert_workout_aggregate` | Tersedia live dan di source hardening; bukan protokol finish-active-session |
| `active_sessions` | Snapshot live 27 Oktober: RLS dan `UNIQUE(user_id)`, tanpa `version`/`status`/receipt; **source current** sudah memakai writer remote versi (§7.9), bukan runtime lokal baseline |
| `profiles`, `exercises`, `workout_templates`, `workout_logs` | Nama tabel dan RLS terverifikasi live; grants/constraints live berbeda dari kontrak target dan belum diuji runtime |

Pada source migration (belum live), template RPC membawa `p_request_id`, `p_template_id`, `p_expected_revision`, `p_name`, `p_exercises`, `p_removed_exercise_ids`, `p_is_deleted`, dan `p_owner_id`. Receipt membawa request/template ID, revision, exercise count, dan server update time. Revision bersifat monoton; **jangan mengasumsikan selalu naik tepat satu**, karena source memiliki child-trigger revision juga.

#### B. Proposed backend operations

Nama pada tabel berikut adalah **kontrak target RPC** yang kini tersedia di lokal F1b (§5.4.G), bukan klaim deployment project utama. Tidak ada REST `/workout-logs` generik yang diklaim sebagai endpoint existing IMFIT.

| RPC usulan | Input utama | Hasil / invariant |
| --- | --- | --- |
| `get_workout_history_page` | Query canonical, cursor, limit | Summary envelope; owner dari auth |
| `get_exercise_catalog_page` | Search/category/sort/cursor/limit | Catalog envelope; active visibility rules |
| `get_template_page` | Sort/cursor/limit | Template summary + revision |
| `get_workout_detail` | Workout ID | Satu consistent aggregate snapshot atau not-found |
| `get_template_detail` | Template ID | Complete children dan revision dari snapshot konsisten |
| `get_progress_summary` | Periode + timezone | Totals lengkap, tidak bergantung loaded pages |
| `get_workout_date_markers` | Periode + timezone | Tanggal/count bounded untuk kalender |
| `get_exercise_prefill` | Exercise ID + target defaults | Matching provenance atau defaults |
| `create_active_session` | Request ID, session ID, template ID/revision | Server membuat initial snapshot; satu sesi aktif per owner |
| `get_active_session` | Tanpa owner arbitrer; optional session ID untuk resolve | Active/paused atau terminal/absence yang authoritative |
| `update_active_session` | Request ID, session ID, expected version, typed command | New snapshot/version atau conflict |
| `cancel_active_session` | Request ID, session ID, expected version | Terminal cancellation receipt |
| `finish_active_session` | Request ID, session ID, expected version | Atomic workout receipt; satu workout per session |
| `get_operation_status` | Request/resource identity yang diketahui | Receipt/status owner-scoped |
| `list_recent_operations` | Bounded window/cursor | Discovery create/template/finish setelah request ID hilang |

Pause/resume dapat berupa command pada `update_active_session`; tidak perlu membuat RPC terpisah bila kontraknya sama. Discovery harus hanya membaca receipt server, bukan memberi izin client memalsukannya.

#### C. Session schema target

Field berikut adalah **tambahan/perubahan schema target** yang diterapkan di lokal F1b; tidak dianggap tersedia pada baseline/live audit historis:

| Field/constraint | Kebutuhan |
| --- | --- |
| `id`, `user_id` | Stable UUID/session identity dan owner server-authenticated |
| `version` | Integer monoton; CAS dalam transaksi |
| `status` | ACTIVE, PAUSED, COMPLETED, CANCELLED, EXPIRED |
| `session_data` + schema version | Validated structured snapshot; exercise/set IDs stabil, target defaults, angka, completed flags, rest override |
| Template snapshot | Nama dan konfigurasi tetap dapat dipulihkan bila template berubah/dihapus |
| Time anchors | Start/pause/resume server timestamps, total paused duration, last activity, expiry |
| Terminal linkage | Completed workout ID dan receipt metadata; unique session→workout |
| Partial unique owner | Hanya satu ACTIVE/PAUSED, bukan UNIQUE owner untuk semua terminal history |
| Operation receipt store | Owner + request ID unik; payload identity/hash, result, retention metadata; client tidak dapat menulis langsung |

Baseline FK template cascade dan unique owner harus dimigrasikan; final design tidak boleh menghapus sesi/receipt saat template dihapus. D03 menetapkan expiry 30 hari sejak `last_activity_at` dan receipt lengkap minimal 90 hari; expired masuk review, bukan silent purge. Hard-delete policy, maksimum draft size/sets, serta cleanup setelah retention tetap memerlukan migration dan persetujuan terpisah.

#### D. Mutation dan idempotency protocol

1. Server mengambil owner dari auth, memeriksa p_owner bila dipakai, dan memvalidasi command/payload.
2. Cari receipt request yang sama pada owner tersebut **sebelum** CAS conflict untuk memungkinkan replay sesudah commit.
3. Request ID sama + payload berbeda ditolak. Payload retry tidak boleh berubah sambil memakai identitas lama.
4. Lock sesi/resource dan cek status/version. Compare-and-set hanya sukses jika expected version sesuai.
5. Terapkan command atomik, increment version, simpan receipt, commit, lalu balas.
6. Satu session finish hanya memiliki satu completed workout, termasuk ketika retry memakai request ID baru setelah restart. Return canonical completed outcome; jangan membuat log kedua.
7. Finish dan cancel diserialisasi pada sesi yang sama. Operation yang kalah menerima terminal outcome/conflict yang dapat di-resolve.
8. Timeout/cancellation client tidak membuktikan transaksi dibatalkan. Client memakai retry identity yang sama atau operation discovery, bukan menciptakan resource pengganti.

Finish menghitung totals dari server-confirmed **completed sets** dan menulis workout log, exercise logs, sets, terminal session, serta receipt dalam satu transaksi. Jangan memecah parent/child writes ke `async` independen. Identitas exercise/set tidak berbasis posisi UI yang dapat bergeser.

#### E. Error mapping target

Nama berikut kategori domain **usulan**, bukan enum/error code existing. Mapping HTTP/PostgREST/SQLSTATE harus diuji terhadap response nyata; jangan mengasumsikan semua conflict selalu HTTP 409.

| Kategori | Arti | UI / retry |
| --- | --- | --- |
| Offline / Transport / Timeout | Tidak mendapat hasil pasti | Reads: contextual retry; writes: Unknown bila commit mungkin terjadi |
| Unauthenticated | Sesi auth tidak valid | Re-auth; bersihkan state pribadi; tidak mengganti owner request |
| Forbidden / NotFound | Tidak punya akses/tidak ada record | Pesan aman; jangan membocorkan keberadaan resource owner lain |
| Validation | Payload/angka/command tidak valid | Error dekat input; tidak retry otomatis |
| RevisionConflict / VersionConflict | Data authoritative berubah | Reload/review; tidak rebase write secara diam-diam |
| IdempotencyMismatch | ID request dipakai untuk payload berbeda | Blokir retry payload berubah; resolve operasi lama |
| RateLimited / ServerUnavailable | Remote sementara tidak tersedia | Retry eksplisit/backoff bounded dengan Retry-After bila ada |
| PaginationProtocolError | Cursor/metadata tidak konsisten | Hentikan append dan tawarkan refresh |
| ContractUnavailable | RPC/schema/capability belum tersedia | Gagal jelas; tidak fallback ke write lama yang unsafe |

Cancellation milik scope tetap cancellation, bukan kategori error UI. Retry write hanya pada identitas/payload yang sama dan kontrak idempotent; reconnect tidak mengirim ulang dirty draft secara tersembunyi.

#### F. Kontrak desain F1a — 28 Oktober 2026

**Status: kontrak desain F1a, terimplementasi/teruji di backend lokal F1b (§G); foundation DTO/request Kotlin F2 (§H), kemudian runtime cutover §7.9.** Pengguna mengonfirmasi K01–K05 pada 28 Oktober 2026, termasuk precision weight 2 desimal. Signature/receipt template existing dipertahankan. Kedua flavor kini memakai facade/store remote; graph Room di luar facade belum seluruhnya dilepas. Tidak ada deployment project utama.

**Aturan wire bersama:** RPC melalui `POST /rest/v1/rpc/<nama>`, request JSON snake_case, response `jsonb`, owner selalu `auth.uid()`. Parameter optional memiliki default SQL eksplisit; jangan membuat overload ambigu bagi PostgREST. UUID berbentuk string; exercise ID tetap text katalog (bukan UUID). Timestamp RFC3339 UTC mempertahankan precision PostgreSQL; date berbentuk `YYYY-MM-DD`. Revision/version adalah `bigint` nonnegatif, dipetakan ke Kotlin `Long`, bukan `Float`. Weight/volume pada wire baru adalah decimal string, tidak melewati floating point. `null` hanya sah pada field nullable yang tertulis; field wajib tidak boleh diam-diam dianggap nol/array kosong. Semua counter/indices integer; flags boolean; ID UUID kecuali exercise ID text dan category ID integer.

Alias di tabel: `U=uuid`, `T=text`, `L=bigint`, `I=integer`, `J=jsonb`, `D=date`, `Z=timestamptz`; `?` berarti nullable. Default page 20 untuk history/discovery, 25 katalog, 15 template; maksimum limit 100. Limit di luar 1–100 ditolak, bukan full-pull. Maksimum window tanggal 366 hari; semua window harus nonempty dan start < end. Timezone wajib nama IANA valid (termasuk UTC), bukan offset/abbreviation yang ditebak. API tidak mengganti scope UI yang dikunci K03.

| # | RPC | Parameter SQL (semua memakai prefix `p_`) | Response DTO wire | Validation khusus |
| --- | --- | --- | --- | --- |
| 01 | `save_template_revision` | `request_id U, template_id U, expected_revision L?, name T, exercises J, removed_exercise_ids J, is_deleted boolean, owner_id U` | `TemplateWriteReceipt` existing | Signature existing dipertahankan; create memakai revision null, update/delete memakai revision current; owner_id harus sama auth; removed IDs harus tepat, bukan implicit delete |
| 02 | `get_workout_history_page` | `start_date D, end_date D, timezone T, cursor T?=null, limit I=20` | `Page<WorkoutSummary>` | Rentang tanggal start `[start_date,end_date)` dalam timezone query (K01); `deleted_at is null`; order end_time DESC, id DESC |
| 03 | `get_exercise_catalog_page` | `search T='', category_id I?=null, cursor T?=null, limit I=25` | `Page<ExerciseSummary>` | Search trim/case-normalized, maksimal 100 karakter; hanya active catalog; category harus valid; order normalized name ASC, id ASC dengan collation deterministik |
| 04 | `get_template_page` | `cursor T?=null, limit I=15` | `Page<TemplateSummary>` | Owner current, is_deleted=false; order updated_at DESC, id DESC; tidak memuat seluruh konfigurasi child |
| 05 | `get_workout_detail` | `workout_id U` | `WorkoutDetail` | Owner current dan visible; aggregate satu snapshot dengan ordered children; asing/tidak ada/deleted memberi not-found yang sama |
| 06 | `get_template_detail` | `template_id U` | `TemplateDetail` | Owner current dan nondeleted; revision serta semua child dari snapshot yang sama |
| 07 | `get_progress_summary` | `start_date D, end_date D, timezone T` | `ProgressSummary` | Window sama dengan history/calendar (K01); aggregate seluruh record visible, bukan count loaded pages; precision K02 |
| 08 | `get_workout_date_markers` | `start_date D, end_date D, timezone T` | `DateMarkers` | Window bounded; tanggal start per timezone query (K01); hanya tanggal dengan count >0, ascending |
| 09 | `get_exercise_prefill` | `exercise_id T, input_defaults J` | `ExercisePrefill` | Defaults tepat `{sets,reps}` dengan bounds template; cek provenance workout exercise terbaru owner; mismatch/unknown memakai defaults, bukan menelusuri workout lama agar terlihat cocok |
| 10 | `create_active_session` | `request_id U, session_id U, template_id U, expected_template_revision L` | `SessionMutationReceipt` | Tidak menerima owner/totals/start_time/whole draft dari client; template owned, visible, revision cocok dan katalog valid; existing active/expired belum direview memblokir create |
| 11 | `get_active_session` | `session_id U?=null` | `SessionLookup` | Null mencari sesi aktif/paused atau expired unresolved owner; ID eksplisit dapat resolve terminal; absence harus hasil sukses authoritative, bukan fallback transport failure |
| 12 | `update_active_session` | `request_id U, session_id U, expected_version L, command J` | `SessionMutationReceipt` | Typed command; session/child identity current; hanya transisi yang valid; request tak boleh mengubah owner, version, time anchor, totals, atau receipt secara langsung |
| 13 | `cancel_active_session` | `request_id U, session_id U, expected_version L` | `SessionMutationReceipt` | Terminal cancel transaksional tanpa menghapus snapshot/receipt; terminal outcome lain dikembalikan/dapat di-resolve, tidak ditulis ulang |
| 14 | `finish_active_session` | `request_id U, session_id U, expected_version L` | `FinishReceipt` | Minimal satu completed set valid; server menghitung totals; tidak menerima client workout aggregate; unique session→workout dan transaksi parent/child/session/receipt |
| 15 | `get_operation_status` | `request_id U` | `OperationLookup` | Hanya owner current; mencakup template dan session; NOT_FOUND berarti tidak ada receipt committed yang ditemukan, bukan bukti aman membuat request/resource baru |
| 16 | `list_recent_operations` | `from Z, until Z, cursor T?=null, limit I=20` | `Page<OperationReceipt>` | Window `[from,until)` maksimal 90 hari; order committed_at DESC, request_id DESC; discovery hanya owner sendiri, tanpa payload write mentah |

**DTO minimum:** field berikut wajib; `?` berarti nullable, bukan boleh hilang. Array kosong sah untuk hasil baca yang memang kosong. Class foundation F2 ada di `data/remote/online/OnlineDtos.kt`; template request/receipt existing digunakan kembali tanpa mengganti writer runtime.

| DTO | Field minimum |
| --- | --- |
| `Page<T>` | `data: T[]`, `page_info: {next_cursor: text?, has_more: boolean, limit: int}`; saat has_more=false cursor null, saat true cursor harus maju |
| `WorkoutSummary` | `id, template_id?, template_name, start_time, end_time, duration_seconds, total_volume, total_sets, total_reps`; duration_seconds adalah floor(end−start) termasuk pause; total_volume decimal string exact |
| `ExerciseSummary` | `id, muscle_category_id, name, image_url?, difficulty?, equipment?` |
| `TemplateSummary` | `id, name, revision, exercise_count, updated_at` |
| `TemplateDetail` | `id, name, revision, updated_at, exercises[]`; child `{id, exercise_id, order_index, sets, reps, rest_seconds}` |
| `WorkoutDetail` | Seluruh `WorkoutSummary` + `exercise_logs[]`; child `{id, exercise_id, exercise_name, muscle_category, order_index, input_defaults?, total_volume, total_sets, total_reps, sets[]}`; set `{id, set_number, weight, reps, is_completed}` |
| `ProgressSummary` | `start_date, end_date, timezone, workout_count, total_volume, total_sets, total_reps, duration_seconds`; jumlah durasi seluruh workout window termasuk pause, numeric exact (K01/K02) |
| `DateMarkers` | `start_date, end_date, timezone, dates: [{date, workout_count}]` |
| `ExercisePrefill` | `source: HISTORY/DEFAULTS, source_workout_id?, input_defaults: {sets,reps}, sets: [{set_number,weight,reps}]`; pilihan/perataan set mengikuti provenance, bukan completed reps |
| `TemplateWriteReceipt` | Existing `{request_id, template_id, revision, exercise_count, server_updated_at}` tetap kompatibel; discovery menyimpan metadata operasi terpisah tanpa mengganti field existing |
| `SessionSnapshot` | `id, version, status, effective_status, review_required, template_id?, template_name, template_revision, started_at, paused_at?, total_paused_ms, last_activity_at, expires_at, schema_version, current_exercise_id?, exercises[], completed_workout_id?`; child session `{id, exercise_id, name, order_index, rest_seconds, input_defaults: {sets,reps}, sets[]}`; set `{id, set_number, weight, reps, is_completed}` |
| `SessionLookup` | `found: boolean, session: SessionSnapshot?`; found=false hanya pada sukses baca tanpa sesi yang cocok |
| `SessionMutationReceipt` | `request_id, operation, session_id, version, status, committed_at, snapshot: SessionSnapshot`; replay mengembalikan snapshot receipt asli, bukan snapshot current yang mungkin sudah lebih baru |
| `FinishReceipt` | `SessionMutationReceipt` + `workout_id`; snapshot terminal dan linkage canonical tetap dipertahankan |
| `OperationReceipt` | `request_id, operation, resource_type, resource_id, committed_at, committed_at_source: COMMIT/OBSERVED_BACKFILL, result`; result adalah receipt asli template/session, tanpa payload bisnis write yang dikirim client |
| `OperationLookup` | `state: COMMITTED/NOT_FOUND, receipt: OperationReceipt?`; hasil NOT_FOUND tidak mengasumsikan operasi in-flight batal atau belum commit |

**Command:** object discriminated union `{type,...field sesuai tipe}`; unknown type/field ditolak. `edit_set` membawa `exercise_log_id,set_id,weight,reps`; `complete_set`/`uncomplete_set` membawa `exercise_log_id,set_id`; `add_set` membawa `exercise_log_id,set_id` baru; `remove_set` membawa `exercise_log_id,set_id`; `add_exercise` membawa `exercise_log_id` baru dan `exercise_id` katalog; `remove_exercise` membawa `exercise_log_id`; `set_rest_seconds` membawa `exercise_log_id,rest_seconds`; `select_exercise` membawa `exercise_log_id`; `pause`/`resume`/`acknowledge_expiry` hanya membawa `type`. Stable set ID dan set_number tidak didaur ulang; urutan exercise terpisah dari identity. Completed set perlu di-uncomplete sebelum diedit/dihapus; exercise dengan completed sets tidak boleh dihapus. Numeric completion mengikuti invariant existing, bukan perubahan bodyweight terselubung. Pause/resume pada state yang sudah sama menghasilkan STATE_CONFLICT (replay request lama tetap sukses); mutasi child hanya di ACTIVE/PAUSED, finish/cancel dari keduanya diperbolehkan.

**Batas dan konstruksi draft:** maksimal 100 exercise berbeda per template/session, 20 set hidup per exercise, 2.000 set hidup per session, dan 256 KiB UTF-8 JSON request/snapshot; integer set_number positif dan tidak overflow int32. Existing legacy di luar batas masuk review, bukan dipotong. Input weight baru harus decimal string finite unsigned 0–1000 kg, maksimal 2 digit pecahan; angka/exponent/NaN/Infinity/negative ditolak. Simpan numeric exact, canonical input/hash weight dinormalisasi ke 2 digit pecahan (misalnya `5`, `5.0`, `5.00` setara), bukan dibulatkan; `5.001` ditolak. Reps integer 0–1000; complete membutuhkan weight >0 dan reps >0. Rest integer 0–600. Template bounds nama/sets/reps mengikuti bagian 6.3. Field JSON salah tipe ditolak sebelum cast database.

Server membuat exercise/set IDs deterministik dari session dan child identity untuk create; `add_exercise` memakai exercise_log_id client yang baru, dengan 3 set/default reps 10/rest 60 (parity default TemplateExercise), sedangkan `add_set` memakai defaults reps exercise tersebut dan set_number max_ever+1. Prefill memeriksa workout visible terbaru yang memiliki completed set untuk exercise (end_time DESC, workout ID DESC), lalu membandingkan input_defaults persis. Mismatch/unknown memakai weight `0.00` dan reps defaults. Jika cocok, isi set_number 1..defaults.sets dari completed sets yang tersedia, gap memakai defaults; tidak mengambil data dari workout lain untuk gap. Nilai legacy dengan pecahan melebihi 2 yang akan dipakai untuk input baru menghasilkan LEGACY_REVIEW_REQUIRED, tanpa rounding/rewrite atau fallback diam-diam. Read history/detail/statistik tetap mempertahankan numeric legacy exact sebagai string meski skalanya lebih besar.

Create membutuhkan template berisi minimal satu exercise active yang berbeda; server mengambil snapshot template/revision di bawah lock, bukan memakai snapshot client. Semua nested IDs harus milik session tersebut, exercise tidak boleh duplikat, dan parentage tidak dapat diedit lewat command. Existing active_sessions JSON legacy yang tidak memenuhi schema baru harus ditandai review/blocked saat upgrade; jangan memberinya version/snapshot sintetis lalu menganggap sudah valid. Snapshot detail history menyimpan exercise_name/provenance saat finish, bukan mengikuti perubahan katalog kemudian.

**Expiry/review:** read menghitung `effective_status=EXPIRED` bila deadline lewat pada ACTIVE/PAUSED tanpa mengklaim write terjadi. `status` adalah stored state, `version` hanya naik pada commit. `acknowledge_expiry` pada `update_active_session` hanya sah pada effective EXPIRED: mengonfirmasi review secara eksplisit (CAS/receipt), menyimpan EXPIRED/review_required=false, mempertahankan draft, dan tidak menghidupkan kembali sesi atau membuat workout. Ini acknowledgment nondestruktif, bukan discard/purge. Saat expired unresolved, command lain/finish/cancel ditolak SESSION_REVIEW_REQUIRED. Create memeriksa expired unresolved di bawah owner lock, bukan hanya partial unique ACTIVE/PAUSED. Satu owner tetap blocked sampai review selesai; semua legacy/preflight di Android tetap wajib. Jangan melakukan update expiry lalu raise exception dan menganggap update bertahan: exception membatalkan transaksi. Read/replay tidak memperpanjang last_activity; successful mutasi bisnis ACTIVE/PAUSED memperbarui inactivity deadline 30 hari. Scheduler/expiry mutation harus commit sendiri; tidak ada purge/job production yang disetujui.

**CAS/idempotency:** ambil auth → lock `(owner,request_id)` → lookup receipt + cocokkan operation/resource/canonical payload → replay receipt sebelum CAS → lock owner untuk semua mutasi final, lalu lock resource secara deterministik → validate expected version/revision dan state → write aggregate + version + receipt atomik. Request ID baru membuat receipt baru, tetapi finish COMPLETED/cancel CANCELLED yang sudah terminal mengembalikan canonical outcome tanpa membuat workout kedua atau menaikkan version sesi; terminal lawan menghasilkan SESSION_TERMINAL dengan status/ID outcome milik owner untuk resolve. Urutan lock request→owner→resource harus seragam lintas RPC dan diuji deadlock/concurrency. Template revision tetap monoton (tidak harus +1); session version dimulai 1 dan naik +1 untuk accepted command nonterminal atau acknowledge_expiry, bukan read/replay/no-op terminal. Session snapshot dari receipt replay tetap historis, bukan current state; client tidak boleh memakai version lebih tua untuk menimpa state lebih baru.

Global request registry untuk semua mutasi menghubungkan receipt template existing dan receipt sesi baru; migration follow-up mengisi metadata/discovery, bukan membuat ulang migration revision. Canonical payload meliputi operation, resource IDs, expected version/revision dan command; object JSONB key order bukan perbedaan intent, array order tetap signifikan. Registry/receipt client tidak dapat INSERT/UPDATE/DELETE. Tidak menghapus receipt otomatis; D03 minimal 90 hari dan canonical session→workout tetap berlaku setelah window penuh. Request ID sama pada operation/payload berbeda ditolak, termasuk lintas RPC. Discovery dari existing template receipts yang belum punya timestamp menggunakan observed/backfill time berlabel provenance, bukan mengarang original commit time; receipt existing tidak ditulis ulang untuk mengubah hasil replay.

**Error mapping:** gunakan error envelope PostgREST `{code,message,details,hint}`; message adalah machine discriminator stabil, bukan copy UI. `PT400/VALIDATION` dan `PT400/INVALID_CURSOR` → validation/protocol, `PT409/REVISION_CONFLICT`, `PT409/VERSION_CONFLICT`, `PT409/IDEMPOTENCY_MISMATCH`, `PT409/SESSION_REVIEW_REQUIRED`, `PT409/STATE_CONFLICT`, `PT409/SESSION_TERMINAL`, `PT409/LEGACY_REVIEW_REQUIRED` → conflict/review terkait, `PT404/RESOURCE_NOT_FOUND` → not-found. `details` berupa JSON string `{field?,reason?,resource_id?,current_version?,terminal_status?,workout_id?}` tanpa data owner lain. Detail asing/tidak ada seragam not-found; session/operation lookup ID asing memberi found=false/NOT_FOUND sama dengan ID tak ada. `42501` → unauthenticated/forbidden sesuai konteks JWT, bukan selalu conflict. Error network/timeout → Unknown untuk mutasi. `40001`/`40P01` engine bukan otomatis revision conflict atau alasan auto-replay dengan payload baru. Implementasi template historis di private tetap memakai code lama; public wrapper memetakan code domain baru tanpa mengganti receipt lama. Status HTTP 400/409/404 dan discriminator terkait telah **Verified lokal**; mapping DTO/domain Kotlin dan ContractUnavailable belum diintegrasikan (F2/G2). Jangan fallback writer lama saat RPC/schema tidak tersedia.

**Cursor/detail/security:** cursor versioned berisi query fingerprint dan full-precision sort tuple; sort/query/owner mismatch atau malformed ditolak. Owner tetap diperiksa server meski cursor memuat owner. Detail dan summary harus berasal dari snapshot konsisten; jangan membangun aggregate dengan SELECT terpisah yang dapat melihat revision berbeda. SECURITY DEFINER memakai search_path aman dan validasi owner eksplisit; revoke default EXECUTE publik serta tutup direct writes sesuai K05. Konsistensi `workout_sets` ke exercise_log/workout/exercise ditegakkan constraint/trigger; row lama inkonsisten harus terdeteksi untuk review, bukan dipurge saat migration.

**Grant matrix target lokal:** anon tidak dapat membaca/memutasi data bisnis atau EXECUTE 16 RPC. Authenticated hanya EXECUTE RPC yang diizinkan, SELECT katalog active/category, serta SELECT profil sendiri dan UPDATE kolom `name,birth_date,avatar_url` profil sendiri; profil INSERT tetap trigger auth, bukan free-form owner/email write. Template/session/log/child/registry/receipt tidak boleh direct DML pada tahap final; receipt/registry juga tidak direct SELECT. Tahap kompatibilitas mempertahankan writer legacy yang disetujui hanya untuk membuktikan batas transisi, tidak diklaim aman terhadap bypass CAS. Semua SECURITY DEFINER memvalidasi auth/owner meski table owner bisa bypass RLS; uji lewat JWT authenticated/anon dan role SQL terpisah, bukan sekadar postgres. Perubahan grant production belum diizinkan.

| Keputusan dikonfirmasi pengguna 28 Oktober 2026 | Kontrak | Batas persetujuan |
| --- | --- | --- |
| K01 — tanggal/timezone/durasi | UTC storage; IANA timezone eksplisit pada query; grouping tanggal start; history end−start termasuk pause | Tidak mengganti metrik historical secara diam-diam |
| K02 — numeric | Input weight baru maksimal 2 digit pecahan kg; numeric exact; decimal string wire baru; lebih dari skala ditolak | Tidak rounding/rewrite legacy; sum legacy tetap exact |
| K03 — scope UI | History per tanggal + template Home; belum menambah route feed/template list | API page bukan izin menambah layar |
| K04 — profile | Tetap direct dengan RLS dan column grants minimum | Tidak menambah RPC profile; field owner/email auth-managed tidak dibuka sebagai free-form write |
| K05 — compatibility cutoff | Tahap kompatibilitas dan final RPC-only diuji terpisah secara lokal | Tidak cutoff/deploy production; B04 belum closed sebelum bukti final-lockdown |

**Contoh request sintetis untuk 16 RPC:** ini fixture desain, bukan command yang sudah dijalankan. ID/payload antarcontoh menunjukkan bentuk, bukan rangkaian operasi yang diasumsikan sukses tanpa mendapat actual revision/version dari server.

```json
[
  {"rpc":"save_template_revision","body":{"p_request_id":"10000000-0000-0000-0000-000000000001","p_template_id":"20000000-0000-0000-0000-000000000001","p_expected_revision":null,"p_name":"Fixture routine","p_exercises":[{"exercise_id":"fixture-squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}],"p_removed_exercise_ids":[],"p_is_deleted":false,"p_owner_id":"a0000000-0000-0000-0000-000000000001"}},
  {"rpc":"get_workout_history_page","body":{"p_start_date":"2026-10-01","p_end_date":"2026-11-01","p_timezone":"Asia/Jakarta","p_cursor":null,"p_limit":20}},
  {"rpc":"get_exercise_catalog_page","body":{"p_search":"squat","p_category_id":null,"p_cursor":null,"p_limit":25}},
  {"rpc":"get_template_page","body":{"p_cursor":null,"p_limit":15}},
  {"rpc":"get_workout_detail","body":{"p_workout_id":"30000000-0000-0000-0000-000000000001"}},
  {"rpc":"get_template_detail","body":{"p_template_id":"20000000-0000-0000-0000-000000000001"}},
  {"rpc":"get_progress_summary","body":{"p_start_date":"2026-10-01","p_end_date":"2026-11-01","p_timezone":"Asia/Jakarta"}},
  {"rpc":"get_workout_date_markers","body":{"p_start_date":"2026-10-01","p_end_date":"2026-11-01","p_timezone":"Asia/Jakarta"}},
  {"rpc":"get_exercise_prefill","body":{"p_exercise_id":"fixture-squat","p_input_defaults":{"sets":3,"reps":10}}},
  {"rpc":"create_active_session","body":{"p_request_id":"10000000-0000-0000-0000-000000000010","p_session_id":"40000000-0000-0000-0000-000000000001","p_template_id":"20000000-0000-0000-0000-000000000001","p_expected_template_revision":1}},
  {"rpc":"get_active_session","body":{"p_session_id":null}},
  {"rpc":"update_active_session","body":{"p_request_id":"10000000-0000-0000-0000-000000000012","p_session_id":"40000000-0000-0000-0000-000000000001","p_expected_version":1,"p_command":{"type":"pause"}}},
  {"rpc":"cancel_active_session","body":{"p_request_id":"10000000-0000-0000-0000-000000000013","p_session_id":"40000000-0000-0000-0000-000000000001","p_expected_version":2}},
  {"rpc":"finish_active_session","body":{"p_request_id":"10000000-0000-0000-0000-000000000014","p_session_id":"40000000-0000-0000-0000-000000000001","p_expected_version":2}},
  {"rpc":"get_operation_status","body":{"p_request_id":"10000000-0000-0000-0000-000000000014"}},
  {"rpc":"list_recent_operations","body":{"p_from":"2026-10-01T00:00:00Z","p_until":"2026-11-01T00:00:00Z","p_cursor":null,"p_limit":20}}
]
```

**Contoh response/error:** initial page tanpa data adalah `{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":20}}`, bukan transport error; absence sesi adalah `{"found":false,"session":null}`, absence receipt `{"state":"NOT_FOUND","receipt":null}`. Receipt template existing tetap `{"request_id":"10000000-0000-0000-0000-000000000001","template_id":"20000000-0000-0000-0000-000000000001","revision":4,"exercise_count":1,"server_updated_at":"2026-10-28T02:00:00.123456Z"}` (revision tidak dijanjikan +1). Bentuk version conflict `{"code":"PT409","message":"VERSION_CONFLICT","details":"{\"current_version\":3}","hint":null}` memiliki status HTTP 409 yang telah diverifikasi lokal; nilai contoh tetap ilustratif, bukan log transaksi production.

#### G. Implementasi dan bukti F1b lokal — 28 Oktober 2026

**Verified lokal:** 16 RPC di §F tersedia dan diuji dengan SQL serta JWT Auth/PostgREST lokal. Delapan follow-up migration (`20261028000100`–`20261028000600`, `20261028042753`, `20261028043417`) ditambahkan setelah empat migration existing, tanpa mengedit/duplikasi migration revision `20261025000100`. Function template lama dipindahkan ke `imfit_private` dan dipanggil wrapper kompatibel; receipt lama direplay persis, metadata discovery backfill berlabel `OBSERVED_BACKFILL`.

| Area | Bukti lokal | Batas |
| --- | --- | --- |
| Session/revision/CAS/receipt | Typed commands, stable IDs/set numbers, CAS, terminal linkage unik, atomic finish/cancel, operation discovery dan replay | Bukan repository/DTO Kotlin yang sudah diintegrasikan |
| Timeout/outcome unknown | Proxy loopback memutus respons setelah server finish commit; discovery dan retry mengembalikan workout/receipt yang sama | Bukan Android process-death/device test |
| Concurrency | Dua koneksi HTTP: template CAS, same-request replay, update/update, create/create, finish/finish, finish/cancel | Bukan stres/load test; tidak menjamin semua interleaving telah dieksplorasi |
| Read/pagination/progress | Fixture 602 workout pada window Oktober, timestamp ties/microseconds, owner/soft-delete, UTC/Jakarta, DST gap/fold New York, numeric legacy exact, provenance/gap defaults | Query plan history diverifikasi pada 10.000 row sintetis; bukan SLA/benchmark production |
| Security | Fase compatibility membuktikan residual direct write; fase final menolak direct session/child/template/legacy RPC, private receipt, anon dan cross-owner; profil hanya UPDATE name/birth_date/avatar_url | Final-cutoff migration hanya diterapkan lokal; B04 production belum ditutup |
| Expiry/timer anchors | Effective EXPIRED/review fail-closed, acknowledgment nondestruktif; finish/cancel menutup pause; completed pause memakai canonical workout end time | Tidak ada scheduler/purge production; rest timer Android belum diubah |
| Backup/replay/upgrade | Backup lokal direstore terpisah; replay seluruh 12 migration dari schema app kosong di atas platform lokal; upgrade dump empat migration mempertahankan draft legacy dan receipt | Bukan cluster baru/CI, bukan Room upgrade atau restore hosted Supabase |
| Static/backend diagnostics | db lint dan advisors lokal tanpa finding; readiness audit 16 nama hadir, public RLS aktif, table-level direct DML kosong | Metadata juga memeriksa tiga column UPDATE profil; tidak menganggap table-level grant audit cukup |

Runtime bukti: PostgreSQL 17.6, CLI 2.116.0, Docker Engine 29.9.2, PostgREST v16.1. Hasil/command/fingerprint F1b ada di [laporan §7.3](full-online-only-implementation-report.md#73-hasil-implementasi-dan-exit-f1b-lokal). Bukti F1b berasal dari dirty working tree c0be6b8, bukan commit/CI/device. Foundation DTO/Paging/auth epoch kemudian ditambahkan di F2 (§H); refresh UI, SDK auth bridge, Room recovery, timer perangkat dan deployment utama belum diaktifkan. Versi patch PostgreSQL/TLS/API hosted tetap harus direview sebelum deployment.

#### H. Android Remote Foundation F2/G2 — 28 Oktober 2026

Implementasi terisolasi di `app/src/main/java/com/diajarkoding/imfit/data/remote/online/`:

| File | Kontrak |
| --- | --- |
| `OnlineDtos.kt` | Response 16 RPC: required nullable fields tidak diberi default, versi/counter Long, timestamp string microsecond dan decimal string exact; unknown additive response fields diizinkan, schema/status tak dikenal fail-closed |
| `OnlineRpc.kt` | Factory typed untuk 16 request + 12 typed session commands; body immutable dibangun sekali, CAS/ID tidak dibuat ulang oleh repository; input weight canonical 2 desimal tanpa rounding legacy; template request/receipt existing dipakai ulang |
| `OnlineTransport.kt` | Ktor POST RPC mempertahankan code/message/details/hint; token ditangkap per request; auth epoch berubah login/logout/account switch, tidak pada refresh token login yang sama; identity tidak membutuhkan profile |
| `OnlineRepository.kt` | RPC-only tanpa DAO/importer/offline fallback; mutasi diserialkan konservatif di satu mutex repository, original intent/replay/unknown gate/discovery; receipt lama tidak menurunkan confirmed version; confirmed state dibersihkan saat identity berubah |
| `OnlinePaging.kt` | PagingSource forward-only + Pager/cachedIn/flatMapLatest; StateFlow display snapshot hanya query+epoch yang sama; refresh generation baru dari awal, stale read-only jika gagal, append error latch, cursor validation/dedup ID, search debounce 300 ms |

**Keputusan spike:** Paging common **3.3.6**, dependency terlokalisasi di version catalog (metadata Google Maven memastikan versi ada); belum menambah Paging Compose sebelum consumer layar F3. Gunakan history 20/catalog 25/templates 15, initialLoadSize=pageSize, prefetch=5, placeholders=false, tanpa RemoteMediator. `RemotePagingFeed` adalah adapter `PagingDataPresenter`, bukan paginator kedua; Paging tetap menangani load/retry/cursor. Prefetch diperbolehkan, tidak menjanjikan tepat satu request saat refresh. Semua-duplikat nonterminal page dianggap protocol error untuk mencegah loop prefetch tak terbatas; refresh eksplisit diperlukan.

**Error adapter:** SDK Supabase 3.0.3 `PostgrestImpl.parseErrorResponse` membuang SQLSTATE `code`. Karena itu adapter baru memakai HttpClient Ktor terpisah (future DI: tanpa auth/retry/logging plugin otomatis), Authorization hasil capture eksplisit dan body error raw; adapter existing tidak diubah. Network/timeout/malformed success/gateway ambigu pada write menjadi UnknownOutcome, bukan Saved/absence. NOT_FOUND discovery tidak membatalkan in-flight write atau memberi izin request ID baru. Foundation memakai **owner-wide unknown gate** yang konservatif: mutasi berbeda diblokir sampai resolve/retry, termasuk jika create memakai resource ID baru; bukan hanya lock ID sesi lama. `resolve(original)` hanya untuk original intent dalam memori login itu; sesudah process death, gunakan discovery/read/preflight terpisah, jangan merekonstruksi payload tebakan. Tidak ada durable draft/queue baru.

**Batas historis F2:** pada snapshot fase ini Hilt/SDK bridge/Compose/session belum dipasang. Batas tersebut bukan status current: integrasi reader §I dan facade/store/guard Start pada laporan §7.9 telah menyusul. Guard identity/query/generation tetap wajib; fake tests tidak membuktikan actual SDK/HTTP atau semua acceptance perangkat.

**Verified foundation:** 37 host tests per flavor, regresi 275 production/281 demo, kedua APK debug berhasil dibangun pada input F2 yang dibekukan (fingerprint/command/batas dan percobaan gagal sebelum freeze ada di laporan §7.4). Ini exit G2 foundation, bukan acceptance UI/perangkat atau aktivasi data remote.

#### I. G3 runtime reader integration — 28 Oktober 2026

Snapshot G3 mengintegrasikan foundation melalui `RemoteReadGateway`, binding production/demo, SDK auth epoch bridge, `RemoteResource`, dan Compose consumers. Bukti fase ada di laporan §7.5–§7.7. Pada fase berikutnya §7.9, session writer/facade/guard Start remote sudah terpasang; Room/recovery dan dependency runtime obsolete belum seluruhnya dihapus.

- History tetap per tanggal, template tetap section Home (K03); tidak ada route feed/list baru.
- Progress/calendar memakai aggregate penuh dan date-marker count; tidak menghitung page pertama atau membuat workout log palsu.
- Template editor memakai revision Long dan removed IDs dari remote snapshot yang diadopsi, bukan cache Room. Dirty/invalid draft tidak ditimpa refresh; legacy pending/intent guard tetap fail-closed dan read-only.
- Tambahan bounded all-time read: `public.get_home_summary()` tanpa parameter → `{"last_workout": WorkoutSummary|null}`. Hanya owner authenticated, nondeleted, urutan `end_time DESC,id DESC`, `LIMIT 1`; tidak mengubah aturan last-workout menjadi 366 hari. Migration additive `20261028090758_online_only_home_summary.sql` diterapkan/diuji hanya lokal, bukan deployed utama. Jumlah kontrak lokal menjadi 17 RPC; 16 kontrak F1/F2 tidak berubah.
- Direct table read hanya Profile dengan owner filter/kolom minimum (K04), dan bounded lookup exercise IDs untuk detail/taxonomy existing. Tidak ada read fallback/full snapshot import dari layar.
- Global route/tab/foreground pull dilepas; initial load/invalidation/explicit refresh dimiliki reader. Cutover sesi remote kemudian tercatat pada laporan §7.9; G4/G5 final tetap memerlukan confirmed/dirty UI, timer recovery dan production/release proof, bukan pemasangan facade ulang.
- Viewport paging dipetakan melalui **stable lazy-item key → data ID**, bukan indeks layout yang mencakup header/footer/error/loading. Baris nondata tidak mengirim hint; refresh/stale menahan hint, lalu viewport dikirim ulang setelah refresh walaupun ID halaman tidak berubah. Regression/acceptance iterasi lanjutan dicatat di laporan §7.6.
- Context error picker tetap berbeda dari no-results/page error: search/filter tidak menghapus kegagalan exclusions, retry/refresh memuat ulang konteks dahulu, dan halaman baru boleh dipublish setelah context owner/generation valid. Caller name/exclusions tidak diwariskan otomatis ke akun lain. Retained editor/detail memuat ulang saat epoch login berubah; source-generation serta saveable key baris editor berubah ketika snapshot baru diadopsi agar input lama tidak mem-publish ulang lintas akun/query. Bukti state/race/compact di laporan §7.7; bukan proof SDK/HTTP production atau proses mati.
- Auth epoch dipublish ke collector **setelah seluruh synchronous reset listener selesai**. Collector Main.immediate dapat berjalan reentrant di setter StateFlow; mem-publish dahulu lalu reset dapat menghapus respons epoch baru yang selesai cepat. Listener ledger menerima identity transisi secara eksplisit; refresh token dalam login sama tetap mempertahankan epoch sesuai kontrak §H.

#### J. Recovery additive — Proposed, 29 Oktober 2026

**Status seluruh kontrak tambahan di bagian ini: Proposed; implementation/runtime/security gates NOT_TESTED.** Tambahkan migration `20261029000100_online_only_legacy_recovery.sql` di atas 13 migration existing, tanpa rewrite atau membuka `upsert_workout_aggregate`. Inventory akhir memuat 17 RPC existing + empat berikut; typed `OnlineDtos`/`OnlineRpc` dan demo harus mengikuti signature yang sama.

```sql
public.get_start_recovery_status(p_contract_version integer default 1) returns jsonb
public.get_legacy_recovery_page(p_cursor text default null,p_limit integer default 20) returns jsonb
public.resolve_legacy_session(p_request_id uuid,p_session_id uuid,p_expected_source_fingerprint text,p_resolution text) returns jsonb
public.import_legacy_workout(p_request_id uuid,p_source jsonb,p_workout jsonb,p_exercises jsonb,p_sets jsonb) returns jsonb
```

**Readiness authoritative:** response `{contract_version:1,checked_at:timestamptz,can_create:boolean,blockers:[{kind,resource_id,version,source_fingerprint,reason}],has_more_blockers:boolean}`. `version`/`source_fingerprint` nullable; kind `LEGACY_SESSION`, `ACTIVE_SESSION`, `EXPIRED_SESSION`; reason `LEGACY_REVIEW_REQUIRED` atau `SESSION_REVIEW_REQUIRED`. Satu snapshot query, preview maksimal 20 urut resource ID, `can_create` dari EXISTS seluruh blocker, bukan panjang preview. ACTIVE/PAUSED efektif, expiry unresolved dan legacy null-version/schema belum diakui memblokir; resolved expiry tidak. Receipt terminal historis tidak discan. Contract selain 1 → `PT400/VALIDATION`.

**Legacy page:** envelope `RemotePage`, limit 1–100, keyset `id`, cursor owner/query-bound; explicit UI paging saja. Item `{session_id,source_fingerprint,resolution,raw_source}` mencakup legacy unresolved/archived; resolution null atau `KEEP_ARCHIVED`. Raw null-version/schema bukan `SessionSnapshot` palsu. Helper fingerprint tunggal pada page/readiness/resolve/create memakai lowercase hex SHA-256 UTF-8 JSONB text field asli `id,user_id,template_id,template_name,start_time,current_exercise_index,session_data,last_activity_at,expires_at,created_at`; exclude status/review/updated_at. Original updated_at tetap dalam raw source; timestamp trigger tidak dimatikan.

**Legacy server archive:** hanya resolution `KEEP_ARCHIVED`; private `imfit_private.legacy_session_reviews(user_id,session_id,source_fingerprint,raw_source,request_id,reviewed_at)`, PK `(user_id,session_id)`, RLS/no direct grants. Replay sebelum source/CAS, owner lock + row lock; foreign/missing `PT404/RESOURCE_NOT_FOUND`, fingerprint berubah `PT409/LEGACY_SOURCE_CHANGED`. Simpan raw row utuh sebelum perubahan; review+receipt atomik, status EXPIRED/review_required=false tanpa fabricate version/schema atau delete. Receipt `{request_id,operation:"resolve_legacy_session",session_id,source_fingerprint,resolution:"KEEP_ARCHIVED",committed_at}`, resource SESSION. Latest create/current lookup mengabaikan archived legacy hanya bila review fingerprint cocok; unresolved tetap conflict. Versioned expiry memakai `AcknowledgeExpiry`, bukan RPC legacy.

**Completed import insert-or-exact-match:** `p_source={schema_version:6|7|9,kind:"WORKOUT",id:<original UUID>,fingerprint:<aggregate SHA256>,mapping_version:1}`. Owner token dan `user_id` payload harus cocok; preserve seluruh parent/exercise-log/set UUID; non-UUID masuk blocked review/archive, bukan ID pengganti. `source_session_id=null`; uncompleted sets ikut dikirim. Business allowlist exact:

- Parent: `id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,deleted_at,created_at,updated_at`.
- Exercise: `id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,total_volume,total_sets,total_reps,input_defaults,created_at,updated_at`.
- Set: `id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,created_at,updated_at`.

Sync/pending flags bukan kolom bisnis. Epoch-ms → ISO UTC tanpa fallback sekarang; date dari epoch tersimpan dalam UTC. REAL dibaca text → BigDecimal → exact decimal string tanpa Float/Double atau rounding 2 desimal. Schema tanpa input_defaults → null, bukan inferred provenance; metadata tak representable tetap raw archive. Canonical UUID/timestamp/numeric tanpa rounding; exercise urut `(order_index,id)`, set `(exercise_log_id,set_number,id)`. Validasi parentage, identity/order/set uniqueness, dependency owner, bounds DB dan totals completed sets. Totals mismatch `PT409/LEGACY_REVIEW_REQUIRED`; malformed `PT400/VALIDATION`; template missing/foreign masuk review, tidak silently null.

Private `imfit_private.legacy_workout_imports` PK `(user_id,source_schema_version,source_id,mapping_version)` menyimpan source_fingerprint/canonical_payload/payload_digest/workout_id/id_mapping/result; RLS/no direct grants. Digest canonical JSONB dihitung server; source key sama dengan payload/fingerprint berbeda conflict. Extend receipt resource constraint dengan WORKOUT. Reuse request replay/owner lock: parent+children+registry+receipt atomik. Aggregate existing owner dibandingkan seluruh projection, termasuk child IDs/timestamps/flags/provenance; metadata target tak dikirim harus default null/false, bukan dihapus. Exact match → MATCHED tanpa rewrite; beda → `PT409/LEGACY_IMPORT_CONFLICT`. Foreign UUID collision owner-hiding NotFound; partial child collision rollback seluruh transaksi.

Receipt import `{request_id,operation:"import_legacy_workout",workout_id,source_fingerprint,payload_digest,outcome:"IMPORTED"|"MATCHED",id_mapping,exercise_count,set_count,committed_at}`. Same request/different payload → IDEMPOTENCY_MISMATCH; new request/same source → workout/mapping sama. Receipt minimal 90 hari, dedup linkage tetap, tanpa purge. Kedua mutation memakai private `require_owner`, `request_replay`, `record_operation`, `fail`; paging memakai cursor helpers existing. SECURITY DEFINER `search_path=''`, revoke public/anon EXECUTE; authenticated hanya public RPC terdaftar, tanpa direct private table grants.

### 5.5 Routing / navigation

| Existing key | Destination saat ini | Target perubahan |
| --- | --- | --- |
| `Main` | MainScreen | Hapus refresh full-pull global; load per fitur |
| `ExerciseList(categoryName)` | ExerciseListScreen | Remote query per kategori/search |
| `ExerciseSelection(...)` | ExerciseSelectionScreen | Selection/query terpisah, result consumption tetap |
| `ActiveExerciseSelection(sessionId, excludedIds)` | Picker active session | Pertahankan session identity dan stale-result guard |
| `WorkoutDetail(workoutId)` | WorkoutDetailScreen | Tetap detail template; jangan dipakai seolah log detail |
| `EditWorkout(workoutId)` | EditWorkoutScreen | Revision-aware editor dan dirty draft protection |
| `ActiveWorkout(templateId)` | ActiveWorkoutScreen | Tambah jalur resolve/recovery session identity; exact route shape ditentukan implementasi |
| `WorkoutSummary(workoutLogId)` | WorkoutSummaryScreen | Hanya setelah confirmed finish/resolve |
| `WorkoutHistory(date)` | WorkoutHistoryDetailScreen | Query remote per tanggal dan detail on-demand |
| `YearlyCalendar(year, month)` | YearlyCalendarScreen | Markers period-bound, bukan seluruh logs |
| `Profile` | ProfileScreen | Dedicated profile state, tanpa history dependency |

ID route bukan otorisasi. Notification membuka recovery sesi yang sesuai owner/session; jangan memulai workout baru hanya karena template ID masih ada. Nama route feed global/dedicated list belum ditentukan dan tidak diklaim existing.

### 5.6 Dependencies (package baru)

- Baseline telah memakai Kotlin/Compose, Hilt, Supabase Kotlin, Ktor, DataStore, Room, dan WorkManager.
- F2 menambahkan Paging common 3.3.6; Ktor MockEngine 3.0.2 hanya untuk host test. Paging Compose belum ditambah sebelum consumer F3.
- Kompatibilitas compile/test/assemble harus dicatat pada snapshot aktual di laporan §7.4; versi dipilih konservatif untuk Kotlin existing, bukan upgrade seluruh dependency.
- Room baru dilepas setelah recovery compatibility selesai; WorkManager tidak dilepas jika timer recovery masih memakainya.

## 6. Business Logic & Edge Cases

### 6.1 Active workout state machine

Server state: `ACTIVE ↔ PAUSED → COMPLETED/CANCELLED/EXPIRED`; expiry 30 hari sejak `last_activity_at` (D03). Tidak ada transisi client yang menghidupkan kembali sesi terminal tanpa operasi recovery eksplisit.

Client save state (usulan):

```text
Confirmed → Dirty → Saving → Confirmed(new version)
Saving → Unknown → Resolving → Confirmed / terminal outcome / Retry
Saving → Conflict → Review/Reload
Dirty → OfflineBlocked → explicit Retry/Reload saat online
```

Confirmed snapshot dan text input dirty terpisah. Respons save edit A tidak boleh menghapus input B yang diketik sesudah A dikirim. Maksimum satu mutasi in-flight per sesi; command antrean sementara dibatasi dan tidak durable. Setelah outcome ambigu, hentikan mutasi lanjutan sampai resolve; jangan mengirim expected version tebakan.

**Kontrak store lanjutan — Proposed / NOT_TESTED:** reuse application-scoped `OnlineSessionStore`/`OnlineRepository`, bukan manager/command hierarchy baru. Tambahkan `SessionSetKey(exerciseLogId:String,setId:String)`, `SessionSetDraft(weightText:String,repsText:String,editRevision:Long)` dan `SessionSavePhase { CONFIRMED,DIRTY,SAVING,OFFLINE_BLOCKED,UNKNOWN,RESOLVING,CONFLICT,RELOADING }`; state memuat drafts/savePhase/connectivity hint/requiresVerification dan snapshot confirmed-only.

API target: `editSet(sessionId:String,key:SessionSetKey,weightText:String,repsText:String)`; suspend `flushEdits(sessionId):SessionSnapshot`, `resolvePending():SessionMutationReceipt?`, `reload(sessionId:String?=null):SessionSnapshot?`, `acknowledgeExpiry(sessionId:String):SessionSnapshot`. Debounce application scope **400 ms** tetap wajib AC13/15. Reset synchronous/cancel delayed jobs pada epoch change. Draft urut child/set; koma→titik, BigDecimal, weight 0–1000kg maksimal 2 desimal/reps 0–1000; incomplete/invalid tetap draft dan flush validation error, bukan zero. Legacy field tak diedit tidak diround.

Raw text hanya memori, dihoist dari `ActiveSetRow rememberSaveable`; tidak SavedStateHandle/Room/DataStore/file. Receipt edit A clear hanya revision A; input B saat A saving tetap ada. Saved hanya tanpa drafts/pending/error/review/verification. Dependent action satu mutex: freeze staging→private flushLocked→RPC latest version; jangan recursive debounce melalui public command. Cancel delay worker saja, bukan mutation dispatched; failure pertama stop sequence; reconnect tidak auto-write.

Extend `OnlineRepository.execute(call,expectedIdentity=null,onRegistered:()->Unit={})`: callback sesudah immutable ledger/owner gate dan sebelum dispatch; pindahkan pending/store/guardedStart preservation ke boundary ini agar UnresolvedMutation sebelum registration tidak membuat pending sesi palsu. Resolve original call/epoch: COMMITTED acknowledge tanpa downgrade snapshot; NOT_FOUND tetap pending/retry original; read error locked. Reload tidak acknowledge unknown. Conflict definitif perlu authoritative reload/review dan explicit discard/reapply raw; unknown tidak boleh request baru.

Complete flush→positive confirmed values→CompleteSet receipt→rest. Finish flush→atomic receipt→summary ID; `WorkoutRepository.finishWorkout():String?` hanya receipt.workoutId, detail fetch milik summary dan gagal read bukan retry finish. Cancel tidak flush draft dibuang, tetapi tidak bypass pending unknown; clear/navigate hanya terminal receipt. `acknowledgeExpiry` memakai mutex/pending/epoch guard, targeted latest reload dan explicit confirmation; hanya efektif EXPIRED/review, tidak lewat editable ACTIVE/PAUSED atau flush expired; draft lama dibuang sesudah konfirmasi.

UI/service memakai typed `SessionCommand` dengan remote IDs, bukan transform/index/Float setelah suspend. Callback `onSetInput(SessionSetKey,String,String)`, `onSetComplete(SessionSetKey)`, `onSetRemove(SessionSetKey)`, `onExerciseRemove(String)`, `onSetAdd(String)`; keys epoch/session/child/set. AddExercise sequential skip confirmed IDs pada explicit retry, stop failure; rest 0–600 dan partial success tetap partial. Store collector menangani confirmation/raw; ticker hanya clock/peek. Cold entry coalesced reload sekali, Loading/Blocked/Unknown/Confirmed; notification known target tidak create dari template. Known terminal target resolve receipt/summary; tanpa target gunakan explicit paginated recent-operation review. Network validated capabilities hanya hint; reconnect coalesced read verification, bukan retry write. Localized status/Retry/Reload bukan warna saja; Back/logout warn dirty/unknown dan logout tidak cancel server.

### 6.2 Kebijakan per aksi

| Aksi | Kebijakan target |
| --- | --- |
| Start | Preflight legacy/remote owner current harus Ready; remote confirmed, template snapshot/revision divalidasi; existing active tidak ditimpa |
| Ketik weight/reps | UI input memori; debounce awal 400 ms; flush pada selesai edit/aksi dependent |
| Complete set | Flush edit dahulu; command segera; rest lokal dimulai setelah acknowledgement |
| Add/remove set/exercise | Stable identity, server validation; tampilkan hasil confirmed; jangan apply result picker milik session lama |
| Rest override | Remote save bila bagian draft; preferensi sound/vibration tetap perangkat |
| Pause/resume | Command server-confirmed; shared serializer UI dan notification |
| Finish | Tolak jika edit belum flush/gagal atau tidak ada completed set; atomic remote receipt |
| Cancel | Konfirmasi pengguna + remote terminal acknowledgement; tidak berhenti sebagai sukses hanya dari request dispatch |
| Back | Tidak membatalkan server session; beri peringatan bila dirty input akan hilang |
| Logout | Bersihkan state memori/PII/timer; confirmed server session tetap dapat dipulihkan owner saat login; warn dirty/unknown terlebih dahulu |

Logout lokal dapat diteruskan setelah pengguna memahami kehilangan input memori. Logout tidak diam-diam membatalkan sesi remote, dan tidak menghapus pending legacy owner lain. Kebijakan SDK sign-out remote/offline harus diverifikasi terpisah; spec ini tidak mengubah autentikasi untuk menghindari failure.

**Preflight upgrade wajib:** inventory schema/data lama dan cek sesi/hasil ambigu remote sebelum create sesi baru. Local active draft, completion belum jelas, atau konflik lokal–remote harus masuk review/resolution terlebih dahulu. Read failure tidak berarti database kosong, fresh install, atau sesi tidak ada. Hanya hasil `Ready` yang terikat owner/auth epoch current dapat memanggil create; `RecoveryRequired` dan `Unknown/Blocked` tidak dapat melewatinya. Ini pemeriksaan client instalasi upgrade, bukan bukti bahwa device lain tidak memiliki draft lokal.

Data historis pending yang sudah diklasifikasi tidak berkonflik dapat tetap berada di recovery terisolasi; tidak dianggap synced. Owner lain tidak dipaksa menyelesaikan data untuk membuka sesi akun current. Checkpoint transisi durable menyimpan hasil inventory/resolution, bukan antrean workout baru; restore, perubahan owner, atau data baru yang belum direview mengharuskan preflight ulang. Constraint unique session/idempotency server tetap wajib walaupun preflight client sukses.

#### Implementasi foundation F6.1 (28 Oktober)

`data/legacy` kini menyediakan inventory standalone read-only, owner metadata/disposition/dependencies, checkpoint evidence atomik/no-backup, dan preflight `READY`/`RECOVERY_REQUIRED`/`UNKNOWN`. Schema diverifikasi melalui user_version + Room identity + digest kolom; export 6/7/9 dikenali tanpa migrasi, 6/7 tetap bridge/review dan layout unknown/4/8 blocked. Corrupt file tidak dihapus oleh corruption handler Android; absent tidak disimpulkan dari read error. Scan memakai data/schema-version dan file-identity stability fence, bukan write transaction terhadap legacy.

`SYNCED_CACHE_CANDIDATE` bukan bukti receipt/izin cleanup. Source preflight saat ini memeriksa recent SESSION operations pada satu page 100/window 90 hari; keterbatasan ini dapat memblokir owner dengan >100 receipt terminal dan bukan kontrak final readiness. Proposed penggantinya (§5.4.J/§6.2.1) memakai blocker authoritative, bukan menaikkan cap atau full-pull. Ready selalu memerlukan re-inventory dan original auth epoch sebelum guarded create.

**Batas current:** guard Start dan session writer remote **sudah terpasang** pada kedua flavor (laporan §7.9); §7.8 adalah evidence foundation historis. Inventory/read-only reader, archive, route dan Profile recovery existing belum sama dengan selected-item import/receipt resolution. Recovery lengkap, pelepasan graph Room, installed-data upgrade/restore dan proof production/release tetap **NOT_TESTED**.

#### 6.2.1 Selected-item recovery — Proposed / NOT_TESTED

Reuse reader/inventory/checkpoint/archive, `LegacyRecoveryViewModel`/`LegacyRecoveryScreen` dan route existing; tidak upload-all/autosync atau UI paralel. `LegacyAggregateReader` membaca parent+children+dependencies dalam **satu read-only stable snapshot** memakai fence/inventory SQL existing, bukan merakit record dari scan berbeda. Exact schema 6/7/9, cap 10.000 owned rows; overflow/corrupt/unknown/layout/read error blocked, bukan truncated success. `LegacyImportMapper` memetakan raw maps ke §5.4.J, bukan Room entities/WorkoutUploadSnapshot/DTO Double.

`LegacyRecoveryCheckpoint`, `FileLegacyRecoveryCheckpointStore`, `LegacyRecoveryCoordinator` target baru: `noBackupFilesDir/legacy_recovery`, AtomicFile+fsync, owner-hashed filename, maksimum 4 MiB/owner dan 10.000 entries. Foreign/corrupt/unsupported/overflow blocked, tidak overwrite kosong. Durable action berisi `formatVersion=1,ownerId,sourceSchemaVersion,source kind/id/fingerprint,aggregate/dependency fingerprint,actionId,operation,requestId,immutable request JSON,body digest,source→remote mapping,review decision,state,validated receipt`; states `AWAITING_REVIEW,PREPARED,SUBMITTED,CONFIRMED,CONFLICT,BLOCKED`, tanpa auto-clean/delete. Ini transisi legacy saja, bukan durable draft/runtime offline queue.

Coordinator API seluruhnya suspend: `review():LegacyRecoveryReview`, `prepare(identity:AuthIdentity,selection:LegacySelection):LegacyRecoveryAction`, `submit(identity:AuthIdentity,actionId:String):LegacyRecoveryResult`, `resolve(identity:AuthIdentity,actionId:String):LegacyRecoveryResult`. Models memuat displayed identity/evidence, selection dan typed outcome, bukan boolean synced. Prepare revalidasi owner/epoch/displayed evidence sebelum durable PREPARED; submit durable SUBMITTED sebelum network. Crash replay exact request/body/IDs; CONFIRMED hanya validated receipt identity/operation/resource/source/mapping/counts. Simpan server payload_digest dan pastikan replay konsisten, bukan menyamakannya dengan hash serialisasi Android. Source berubah sebelum submit blocked; sesudah dispatch resolve original dahulu. Switch akun tidak publish ke akun baru; owner asal resolve nanti, epoch lama bukan durable authority.

Pending CREATE workout memakai explicit selected aggregate import. UPDATE/DELETE tanpa CAS evidence atau unrepresentable/invalid/mismatch → review+keep-history, bukan disguised create/upsert. Historical profile/template tetap keepHistory; template apply bila dipilih melalui CAS editor existing, tanpa janji child UUID preservation RPC template. Room9 template intent harus memuat full `TemplateWriteRequest`: targeted `get_operation_status`; COMMITTED tetap exact original `save_template_revision` replay karena lookup tidak membawa payload. NOT_FOUND hanya explicit retry original, tidak request/template ID baru. Persist sidecar resolution tanpa delete/mark synced sumber; reuse `OnlineRepository.execute(original,expectedIdentity)`, jangan melemahkan runtime resolve ledger.

Local active JSON hanya selesai sesudah explicit abandonment confirmation **“Simpan sebagai arsip lama, tidak dapat dilanjutkan”**. Sumber tetap utuh, archive non-resumable; tidak import draft versioned dengan provenance/child IDs tebakan. Checkpoint menyimpan fingerprint-bound resolution; generic keepHistory tidak mengakui local-active/intent. Remote ACTIVE/PAUSED → resume atau cancel await receipt; versioned expired → review+AcknowledgeExpiry; server legacy → explicit page+KEEP_ARCHIVED. Tidak auto-merge/replace local/remote.

Layar existing menampilkan inventory/dependencies, local-active/intent, remote session/outcome, historical aggregate dan blocked reason; contextual inspect/keep/resolve/retry-original/import-selected/resume/cancel/acknowledge memakai confirmation. Epoch change cancel job/dialog/selected raw details. Preflight memakai readiness RPC+targeted request IDs checkpoint/intent, bukan all-receipt loop; has_more_blockers → RecoveryRequired, transport/malformed/checkpoint error → Unknown. Resolution hanya valid bila source+aggregate+dependency fingerprints cocok; restore/perubahan wajib review ulang. Pertahankan second inventory/expected-epoch sebelum create.

Production template legacyGuard beralih dari database ke standalone inventory+validated resolutions; resolved intent tidak memblokir template selamanya, unknown fail-closed. Bind reader/store/coordinator pada kedua `LegacyRecoveryModule`; demo default Absent bukan installed-upgrade proof. Home dan error Start menuju route LegacyRecovery; kembali tidak auto-create, Retry preflight baru. Owner A resolved dapat Start meski B pending; B tidak terlihat/diunggah/dihapus.

### 6.3 Invariants yang dipertahankan / diangkat ke server

**Baseline source:** volume hanya berasal dari completed sets; complete di UI membutuhkan weight >0 dan reps >0; exercise yang sudah memiliki completed set tidak dapat dihapus; finish membutuhkan set completed; prefill memakai target-default provenance.

**Target:** tegakkan invariants tersebut di server, bukan hanya UI. Set identity/nomor tidak didaur ulang akibat penghapusan posisi. Bounds template mengikuti kontrak revision: nama trim 2–100 karakter, sets 1–20, reps 1–100, rest 0–600 detik. Source client sekarang memeriksa nama nonblank/≤100 sedangkan server minimal 2; parity validation harus diperbaiki.

Bounds sesi harus konsisten dengan schema/backend dan aturan produk; baseline workout set SQL memakai weight 0–1000 dan reps 0–1000, sedangkan complete lebih ketat. Jangan mengubah dukungan bodyweight/zero-weight sebagai efek samping migrasi tanpa keputusan produk.

Prefill tidak boleh menebak provenance dari completed reps. Backend harus menyimpan target defaults saat sesi dibuat/ditambah dan meneruskannya ke history; jika provenance lama tidak diketahui, gunakan defaults, bukan mengklaim matching history.

### 6.4 Time, calendar, dan statistics

- History default sort memakai end_time; filter kalender mengikuti **tanggal start dalam timezone query** untuk parity baseline Progress. Keduanya berbeda dan harus tertulis di query contract.
- Periode memakai interval `[start, end)` dan timezone eksplisit; tanggal lintas UTC/DST diuji. Cursor membawa timestamp precision asli server.
- Baseline `WorkoutLog.durationMinutes` memakai end−start termasuk pause; elapsed active mengurangi pause. Target mempertahankan semantics history ini kecuali perubahan produk disetujui terpisah.
- Server mengembalikan aggregate periode lengkap dan markers; client tidak menjumlah halaman history sebagai statistik global.
- Numeric storage/aggregation memakai precision backend yang disepakati; UI formatting locale tidak mengubah nilai authoritative.

### 6.5 Timer dan cold recovery

Timer adalah fungsi operasional lokal, bukan penyimpanan draft workout offline.

- Saat proses hidup dan session sudah verified untuk auth epoch current, countdown dan render notification memakai clock/confirmed snapshot memori. Network loss tidak menghentikan hitungan lokal yang sudah sah.
- DataStore timer boleh berisi identity/deadline/generation/boot metadata, bukan set/reps/weight atau antrean mutasi.
- Setelah process death, sesi dibaca dari server sebelum memulihkan data workout dan business actions. Failure berarti **Unknown**, bukan null/absent.
- **Keputusan G0 D02 — cold-offline menunggu verifikasi:** tanpa verifikasi sesi current, jangan mengaktifkan ulang business actions atau alarm completion yang mengklaim sesi masih aktif. Pertahankan anchor untuk reconcile setelah online dan tampilkan recovery unavailable bila UI dibuka.
- Perubahan ke kebijakan alarm cold-offline memerlukan revisi keputusan produk dan kontrak timer-only yang eksplisit, termasuk review stale/cross-device risk.
- Reboot/boot identity berbeda tidak ditangani dengan menebak deadline monotonic lama. Pertahankan policy keselamatan engine dan uji ulang adapter baru.
- Logout/owner switch membersihkan alarm/effects owner sebelumnya. Sesi completed/cancelled authoritative membatalkan timer terkait.

Tidak boleh mengganti fungsi repository yang dibaca ticker menjadi HTTP lalu mempertahankan loop satu detik. VM/service/validator harus dipisahkan antara hot snapshot access dan cold remote verification.

**Penguatan timer — Proposed / NOT_TESTED:** current hot path store.peek sudah tercatat source/historical §7.9, tetapi validitas akhir wajib identity current + verified loaded snapshot + effective ACTIVE/PAUSED + !reviewRequired. Cold Unknown bukan null/false absence. Service cold verification sekali pada entry/recovery, bukan ticker/validator. Pertahankan monotonic/generation/boot/late-window. Cold-offline anchor tetap recoverable tanpa expiry commit/completion/audio/business RPC; setelah online verify, reconcile deadline asli, bukan reset duration. `WorkoutService` memakai typed store pause/resume dengan flush dan caught error; STOP hanya `stopWorkout()` setelah cancel receipt/authoritative absence, failure mempertahankan locked/error notification, bukan success. Demo parity revision conflict/SESSION_REVIEW_REQUIRED/effective expiry/retained set numbering/canonical paused terminal diperlukan, tetapi bukan proof SQL/RLS.

### 6.6 Error, empty, dan operasi ambigu

| Kasus | Hasil wajib |
| --- | --- |
| Tidak ada jaringan pada initial read | Initial error/Retry, bukan synthetic data atau legitimate empty |
| Network hilang sesudah data termuat | Data memori tetap boleh tampil sebagai stale; tidak ada disk fallback baru |
| Request read cancellation | Tidak publish error/cursor pada query baru |
| Template editor dirty lalu refresh | Tawarkan review/reload; jangan overwrite draft dengan remote result |
| Finish response putus | Unknown/resolve; jangan menyimpan pending workout Room baru |
| Session sudah selesai di device lain | Resolve terminal receipt dan tampilkan outcome; blokir update stale |
| Session expired | Review/recover/discard sesuai policy; tidak hilang diam-diam |
| Duplicate IDs/cursor tidak maju | Dedup atau protocol error sesuai bagian 5.3; tidak infinite request |
| API contract belum deployed | ContractUnavailable, input dipertahankan di memori; tidak fallback protokol write lama |
| Avatar gagal dimuat | Placeholder; jangan menyembunyikan profil/history yang berhasil dimuat |
| Upgrade data legacy unresolved | Recovery notice owner-scoped; konflik sesi/hasil ambigu memblokir create sampai resolved; jangan membuang database agar runtime terlihat bersih |

### 6.7 Profil authoritative dan scoped invalidation — Proposed / NOT_TESTED

Tidak menambah profile editor. Ganti kontrak lama dengan `suspend fun updateProfile(patch:ProfilePatch):Result<User>`. `ProfileField<out T>` sealed berisi `Omitted:ProfileField<Nothing>` dan `Value<T>(value:T):ProfileField<T>`; `ProfilePatch(name:ProfileField<String>=Omitted,birthDate:ProfileField<String?>=Omitted,avatarPath:ProfileField<String?>=Omitted)`. Omitted tidak dikirim, Value(null) JSON null.

`KtorReadTables` owner/epoch-bound PATCH, `Prefer: return=representation`, select `id,name,email,birth_date,avatar_url`; writable hanya name/birth_date/avatar_url. Empty patch authoritative GET tanpa PATCH. Tepat satu row valid/owner cocok; zero/wrong/malformed gagal. Return/cache hanya server row, cancellation rethrow. Production/demo `RemoteReadGateway.updateProfile(patch):RemoteProfile`; auth memakai lazy `Provider<RemoteReadGateway>` agar tidak cycle demo. Current/observe user remote+memory per epoch, local identity SDK-only tanpa profile network. Error profile bukan logout/absence auth dan tidak memakai profil owner lama.

Upload helper throwing path-returning: gagal upload bukan metadata avatar-null sukses; upload berhasil/PATCH gagal tidak cache path committed. Registration auth sukses/enrichment gagal mengembalikan failure dengan pesan akun mungkin sudah dibuat, tidak auto signUp ulang atau rollback/delete akun/storage. Canonical avatar storage path; signed URL presentation-only. Satukan helper signing Home/Profile/Progress; failure placeholder tanpa menyembunyikan data lain.

Lepas database/sync/preferences/cache DAO/logout prune dari AuthRepositoryImpl dan scheduler/lifecycle dari deep-link handler. Logout clear memory/PII/timer, bukan legacy/pending. Migrasikan seluruh updateProfile production/demo/fake/stub tanpa alias lama; jangan log email/token/payload/signed URL.

`TemplateEditBridge.changes` hanya template list/detail terkait. Application-scoped `OnlineMutationChanges` typed flow `WorkoutCommitted(identity,requestId,workoutId,startedAt)` dan `ProfileChanged(identity)`; dedup request per login, publish hanya setelah receipt/returned row. Finish resolve/retry emit sekali, import setelah confirmed aggregate; bukan durable event queue. Home workout→summary/session, profile→profile saja; Progress workout→aggregate/markers/selected day bila DateWindow timezone query cocok, profile→profile saja; history feed/totals hanya tanggal cocok, bukan detail lain; ProfileViewModel profile saja. Retained collectors epoch-fenced; VM baru initial load; tidak global route/tab/foreground full-pull.

## 7. Permissions, Security & Privacy

### Baseline

Manifest memiliki INTERNET, ACCESS_NETWORK_STATE, foreground-service/special-use, notification, exact alarm, full-screen intent, wake lock/vibration, serta permission media/kamera. Migration online-only tidak dengan sendirinya menghapus kebutuhan timer/media tersebut. `allowBackup=true` dan file aturan backup masih template.

**Live metadata 27 Oktober 2026:** 9 tabel `public` memiliki RLS aktif dan 29 policy (owner-based untuk data pengguna; SELECT katalog untuk authenticated); `anon` tidak memiliki table grant. Namun `authenticated` masih memiliki grant tulis langsung pada `profiles`, `workout_templates`, `template_exercises`, `workout_logs`, `exercise_logs`, `workout_sets`, dan `active_sessions`; `profiles` hanya INSERT/UPDATE, enam tabel lain juga DELETE. Policy `workout_sets` memeriksa ownership melalui `exercise_log_id`, sedangkan `workout_log_id`/`exercise_id` adalah kolom denormalisasi tanpa composite constraint yang memaksa konsistensi parent. `active_sessions.template_id` masih `ON DELETE CASCADE`.

### Target requirements

- Authenticated reads/writes; owner dari token. Caller-provided user ID atau route ID bukan authority.
- RLS untuk tables/children/storage; SECURITY DEFINER RPC harus memiliki ownership validation, search_path aman, dan execute grants minimum.
- `workout_sets.workout_log_id` dan `exercise_id` harus konsisten dengan parent `exercise_logs`; policy insert/update memeriksa kedua relasi, bukan hanya `exercise_log_id`.
- Final session/log writes tidak dapat melewati version/transaction protocol lewat REST atau legacy RPC. Compatibility window harus disetujui sebelum pencabutan grants. Direct DML pada session/log/child/template ditutup pada final target; profile dapat memakai kebijakan terpisah bila tetap direct.
- Active session/receipt tidak boleh terhapus oleh cascade template. Expiry 30 hari masuk review; receipt lengkap minimal 90 hari dipertahankan sesuai D03.
- Receipt server-only dan bound ke owner/request/payload; status/discovery hanya menampilkan operasi owner sendiri.
- State/cache memori dibersihkan pada logout/owner epoch change; signed avatar URL tidak dipakai lintas owner.
- Jangan log token, payload workout lengkap, email, atau signed URL sensitif sebagai telemetry normal. Audit logging existing sebelum release.
- **Proposed / NOT_TESTED backup boundary:** backup_rules.xml serta cloud-backup/device-transfer data_extraction_rules.xml allowlist domain database `.` dan domain file `datastore/imfit_theme.preferences_pb`, `datastore/imfit_locale.preferences_pb`, `datastore/imfit_workout_preferences.preferences_pb` (DataStore, bukan sharedpref). Tidak include root/file/sharedpref keseluruhan; token/timer/effects/sync/recovery acknowledgement tidak direstore sebagai authority. Restored legacy wajib inventory/review ulang dan server dedup.
- Tidak ada klaim enkripsi-at-rest lokal tambahan atau kepatuhan UU PDP/GDPR yang diverifikasi pada audit ini; review privacy/retention tetap TODO/VERIFY.

## 8. Analytics & Tracking

Tidak ada analytics event baru yang diimplementasikan dalam pekerjaan dokumentasi ini. Instrumentasi berikut **usulan observability teknis**, bukan nama event existing:

| Konsep metrik | Data aman minimum | Kegunaan |
| --- | --- | --- |
| Read/page load | Operasi, durasi, status, limit, generation result | Latensi dan bandwidth |
| Mutation resolution | Jenis command, success/conflict/unknown/replay | Integritas write dan retry |
| Legacy recovery | Jumlah unresolved per kategori tanpa payload | Menentukan kesiapan cleanup |
| Timer request budget | Jumlah remote call saat ticker berjalan | Membuktikan nol per-tick polling |
| Contract mismatch | API/schema version, error category | Deployment/capability mismatch |

Request correlation dapat memakai identitas pseudonymous/request ID sesuai policy. Tidak mengirim reps/weight, profil, token, atau SQL payload mentah ke analytics tanpa kebutuhan dan persetujuan terpisah.

## 9. Testing Plan

**Evidence existing:** backend lokal F1b (§5.4.G/laporan §7.3), reader G3 dan cutover Android §7.9 memiliki hasil historis tercatat, termasuk 387 production/395 demo unit dan smoke REST lokal. Tidak ada rerun baru dalam revisi ini. Checklist berikut adalah verification target source final: gate tambahan semuanya **NOT_TESTED**, bukan klaim semua runtime Android belum pernah diuji. Audit live 27 Oktober tetap metadata-only; project utama tidak diuji/diubah.

### Unit / coroutine / Paging

- [ ] Refresh-vs-append, cancel-vs-response, refresh gagal mempertahankan stale query yang sama.
- [ ] Query/filter/sort switch, debounce, cursor reset, account A→B→A, snapshot cleanup.
- [ ] Stable ID dedup, invalid cursor/metadata, append error latch dan retry eksplisit.
- [ ] Dirty input tidak ditimpa receipt lama; serializer, flush-before-complete/finish, cancellation propagation.
- [ ] Prefill provenance dan completed-volume/duration parity.
- [ ] ViewModel re-entry/recomposition tidak menghasilkan request berulang.

### Compose UI

- [ ] Loading/content/empty/no-results/initial error/stale/append footer.
- [ ] Pull refresh saat kosong/error, Retry accessible, filter tetap, scroll reset sesuai generation baru.
- [ ] Picker selection lintas page/filter dan result consumption session/context yang benar.
- [ ] Saved/dirty/saving/unknown/conflict/offline copy, no premature navigation.
- [ ] ID/EN, light/dark, narrow width, large font, semantics/TalkBack, IME/insets.

### SQL / integration / E2E

- [ ] CAS update/update, finish/finish, finish/cancel, replay payload sama/berbeda pada koneksi paralel.
- [ ] Transaction rollback parent/child/receipt dan unique session→workout.
- [ ] Cross-owner dan bypass direct-write/legacy RPC, forged receipt, unauthenticated request.
- [ ] Jalankan `supabase/tests/full_online_only_readiness_audit.sql` pada environment target dan simpan role/read-only/observed timestamp sebagai evidence; script ini bukan pengganti attack test.
- [ ] Timestamp collision/precision, changed sort key, query-bound cursor, date/timezone windows.
- [ ] Commit lalu respons hilang, process death sebelum/selesai acknowledgement, discovery operasi setelah restart.
- [ ] Real Supabase SDK token refresh/logout/account switch dengan test accounts pada environment terisolasi.

### Manual/device/upgrade

- [ ] Confirmed session recovery online dan cold-offline Unknown sesuai policy.
- [ ] Background/deep sleep/reboot/clock shift, permission denied/revoked, notification command failure.
- [ ] Ukur remote call saat UI dan service ticker aktif: tidak meningkat satu request per detik.
- [ ] Upgrade Room 9/6/7, skip bridge, REVIEW, akun A/B, corrupt schema, backup restore, crash checkpoint.
- [ ] Preflight conflict/Unknown tidak memanggil create RPC; resolved owner dapat lanjut, data owner lain tetap utuh; restore membatalkan asumsi inventory lama sudah bersih.
- [ ] Production release minified + instrumentation; demo fake tidak dianggap production E2E.

Fixture existing seperti `TemplateSaveRecoveryTest`, `WorkoutAccountIsolationTest`, `RestTimerSessionValidatorTest`, dan SQL template revision berguna sebagai invariants awal, tetapi menguji arsitektur/adapter lama atau fake. Test harus diadaptasi, bukan dihapus hanya karena Room dilepas.

## 10. Performance Considerations

- Page awal history 20, catalog/picker 25, template bila paginated 15; prefetch awal 5. Ini parameter usulan yang dituning dengan pengukuran, bukan SLA/backend limit permanen.
- List mengunduh summary bounded; detail aggregate hanya saat diperlukan. Hindari N+1 child fetch seluruh history/template.
- Calendar/statistics dibatasi periode dan dihitung server dengan indeks yang dibuktikan EXPLAIN.
- Satu in-flight append per generation, satu in-flight mutation per sesi, debounce search/input, dan bounded retry.
- Last-successful snapshot memori dibatasi pada query current dan dibuang saat epoch berubah; jangan menyimpan semua query tanpa batas.
- Ticker dan countdown tidak melakukan request periodik per detik. Remote revalidation dibatasi oleh lifecycle/recovery action, bukan render loop.
- Payload size, maksimum session items, timeout, retry budget, dan target latensi p95 adalah TODO/VERIFY sebelum load test/release. Jangan mengklaim performa meningkat hanya dari penghapusan Room.

## 11. Feature Flag & Rollout Plan

| Item | Target usulan |
| --- | --- |
| Flag/capability | Contract version/capabilities dan rollout gate; nama key belum ditetapkan/diimplementasikan |
| Strategi | Lengkapi recovery additive → selected-item UI → confirmed/dirty/typed session + profile → clean cutover → proof backend/perangkat/minified lokal; hosted rollout/cutoff deferred |
| Session mode | Dipin saat start; tidak beralih local/remote di tengah sesi karena flag berubah |
| Start upgrade gate | Inventory/resolution legacy owner current sebelum create; unknown/conflict tidak boleh dilewati oleh rollout flag |
| Rollback | Hentikan cohort/start baru; tetap layani recovery/finish sesi remote existing |
| Cleanup | Room/sync runtime dilepas setelah consumer dialihkan; legacy reader tetap bila skip-upgrade belum aman |

Tidak ada automatic fallback remote failure → local write. Downgrade APK/flag tidak dapat dianggap memulihkan state server-only ke Room. Release membutuhkan backend integrity proof, device migration/timer proof, serta persetujuan produk atas kehilangan unconfirmed input.

## 12. Dependencies & Related Features

**Bergantung pada:** auth identity lifecycle, backend revision/session RPC, RLS/grants, operation discovery, pagination/aggregate endpoints, legacy recovery protocol, serta timer adapter yang terpisah dari Room.

**Berdampak pada:** Home, Gerakan/catalog, exercise picker rutinitas dan sesi aktif, template detail/editor, workout start/edit/finish/cancel, summary/history, Progres/calendar, Profile, logout, foreground service, alarm/notification, DI kedua flavor, backup policy, dan test suites.

PR/statistics tambahan yang belum memiliki surface existing tidak otomatis menjadi fitur baru dalam migrasi. Aggregation untuk metrik Progres existing tetap wajib agar pagination tidak menyebabkan nilai parsial.

## 13. Open Questions & Risks

| Pertanyaan/risiko | Status / rekomendasi | PIC usulan |
| --- | --- | --- |
| Apakah kehilangan unconfirmed input setelah process death dapat diterima? | **Diputuskan D01**: diterima; target tetap Full Online-Only tanpa durable local draft | Product |
| Apakah alarm cold-start offline harus tetap berbunyi? | **Diputuskan D02**: tidak; timer menunggu verifikasi sesi | Product/Android |
| TTL active draft dan retention receipts/recent operations? | **Diputuskan D03**: 30 hari sejak aktivitas terakhir, receipt lengkap minimal 90 hari; expiry review/no silent purge | Backend/Product |
| Versi/database mana yang pernah dibagikan, termasuk schema8? | **Diputuskan D04**: Room 9 baseline; Room 6/7 bridge/review; schema 4/8/tidak dikenal blocked; deteksi dari schema aktual | Release/Android |
| Live deployment revision template/RLS/aggregate sesuai source? | **Audit live 27 Okt 2026**: hanya 3 migration Agustus; revision/receipt/RPC session tidak ada; RLS aktif tetapi direct write terbuka | Backend |
| Exact-one-page refresh atau Paging prefetch diperbolehkan? | Proposed: first-page replacement + prefetch boleh; spike wajib | Product/Android |
| Feed history global/dedicated template list diperlukan? | **K03 disetujui 28 Okt**: tidak menambah route; history per tanggal dan template Home | Product |
| Durasi history akan mengurangi pause? | **K01 disetujui 28 Okt**: tidak; end−start termasuk pause, storage UTC dan grouping tanggal start dalam timezone IANA query | Product/QA |
| Precision weight/volume dan format wire? | **K02 disetujui 28 Okt**: input weight baru maksimal 2 desimal, numeric exact dan decimal string wire; tidak rewrite/round legacy | Product/Backend |
| Profile direct atau RPC? | **K04 disetujui 28 Okt**: direct dengan RLS/column grants minimum; tidak menambah RPC profile | Product/Backend |
| Client lama masih boleh direct write? | **K05 disetujui 28 Okt**: uji compatibility dan final-lockdown terpisah; cutoff production tetap open, B04 wajib ditutup pada final target | Backend/Release |
| Prefill provenance server dan data lama tanpa provenance? | Proposed: simpan provenance baru; unknown memakai defaults | Backend/Android |
| User menolak recovery/discard data lama? | Pertahankan data owner-scoped; jangan paksa delete demi cleanup | Product/Android |
| Konflik local active/pending completion belum selesai ketika Start? | **Diputuskan D05**: review sebelum create; tidak ada auto-merge/overwrite | Android/Backend |
| Process mati setelah create template sukses tetapi ID request hilang? | Server operation discovery + review; jangan auto-create ulang; B05 masih terbuka | Backend/Android |
| Project Supabase tunggal untuk development/testing/production? | **Diputuskan D06**: ya; pengujian berisiko memakai schema terisolasi/local, tanpa reset destruktif | Backend/QA |

Risiko runtime utama adalah write outcome ambigu, stale cross-account response, timer hot-path menjadi network loop, dan cleanup upgrade prematur. Plan implementasi menempatkan semuanya sebagai gate, bukan catatan opsional setelah release.

## 14. Changelog

| Versi | Tanggal | Perubahan | Author |
| --- | --- | --- | --- |
| 1.0 | 2026-10-26 | Feature spec usulan berdasarkan audit source; acceptance, contracts, edge cases, dan release gates | AI-assisted repository audit |
| 1.1 | 2026-10-27 | Integrasi review pengguna: status kesiapan/blocker, AC28–AC30, preflight upgrade sebelum start, dan batas evidence deployment | AI-assisted repository audit |
| 1.2 | 2026-10-27 | Keputusan G0 D01–D06, audit metadata Supabase read-only, koreksi status deployment/RPC, AC31–AC34, TTL/receipt, dan scope legacy Room 9/6/7 | AI-assisted repository + live metadata audit |
| 1.3 | 2026-10-28 | Baseline eksekusi c0be6b8; kontrak desain F1a untuk 16 signature/DTO/contoh request, CAS/discovery/error/expiry review; K01–K05 dikonfirmasi pengguna, belum implementation proof | Repository review |
| 1.4 | 2026-10-28 | Backend 16 RPC diimplementasikan/diuji lokal; security cutoff dua tahap, replay/upgrade/restore, pause/snapshot invariants dan batas bukti; Android/production tetap belum diaktifkan | Local backend implementation |
| 1.5 | 2026-10-28 | Fondasi Android terisolasi: typed RPC/DTO, Ktor error envelope, auth epoch, memory-only mutation resolution dan Paging spike; belum mengganti DI/runtime | Android remote foundation |
| 1.6 | 2026-10-28 | Runtime readers G3 dan bounded Home summary lokal; minimum template CAS bridge, sesi/timer legacy tetap terpisah | Android remote readers |
| 1.7 | 2026-10-28 | Stable-key viewport dan refresh re-hint; scope/bukti acceptance demo terarah dicatat di laporan §7.6, bukan full production readiness | G3 regression verification |
| 1.8 | 2026-10-28 | Picker context/owner lifecycle, cancellation child lookup, editor source/saveable isolation dan konsistensi stale detail/calendar; matriks state/error lokal di laporan §7.7 | G3 state and race verification |
| 1.9 | 2026-10-28 | Foundation F6.1 read-only inventory/schema/owner guards, evidence checkpoint dan preflight; belum review/import atau aktivasi session writer | Legacy foundation |
| 2.0 | 2026-10-29 | Source facade/store/guard Start diselaraskan dengan historical §7.9; empat RPC dan selected-item recovery Proposed, confirmed/dirty/timer/profile, archive local-active non-resumable; target internal tanpa deployment; gate baru NOT_TESTED; AC01–AC34/D01–D06 tetap | Release-ready step 0 |
| 2.1 | 2026-10-29 | Persiapan commit host-only: koreksi PATCH profil, resume tanpa create, handoff reverse timer dan EOF; bukti host dirty snapshot di laporan §7.11, backend/device/commit terisolasi tetap tidak diklaim lulus | Scoped preparation |

## 15. Referensi

- [Plan implementasi dan gap analysis](full-online-only-implementation-plan.md).
- [Template dokumentasi fitur](../features/template-feature-documentation.md).
- [Online-first integration history](../online_first.md), khususnya bagian integrasi/deployment limitation sebelum rencana historis.
- [Design system source audit](../skills/imfit-ui-ux/references/design-system.md); snapshot referensi harus dibandingkan source saat implementasi.
- [Android Paging overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), diperiksa 26 Oktober 2026.
- Design mockup baru / ticket / PR implementasi: N/A — belum tersedia dalam scope audit.

## 16. Evidence Map

Evidence baseline lengkap beserta path/line dan confidence ada pada [plan bagian 10](full-online-only-implementation-plan.md#10-evidence-map-dan-confidence). Tabel berikut **khusus snapshot baseline 27 Oktober**, bukan klaim source current. Cutover current dan hasil runtime historis terpisah ada pada laporan §7.9; kontrak tambahan §5.4.J/§6.2.1/§6.7 masih Proposed/NOT_TESTED.

| Claim baseline | Sumber | Evidence type | Confidence |
| --- | --- | --- | --- |
| Production masih memakai facade→template/log/session local projection | `data/repository/WorkoutRepositoryImpl.kt`, `WorkoutSessionManager.kt`; production `di/AppModule.kt` | code | Verified |
| Remote read full-pull memakai offset pages dan Room importer | `data/sync/RemoteWorkoutCoordinator.kt:303–479` | code | Verified |
| Template revision/idempotency source tersedia | `data/repository/WorkoutTemplateRepository.kt:86–144`; `supabase/migrations/20261025000100_template_revision_writes.sql` | code | Verified source; **live absent 27 Okt 2026** |
| Session server baseline belum versioned lifecycle | `supabase/migrations/20260828130114_imfit_schema_baseline.sql:120–133` | code + live metadata | Verified: live table has no `version`/`status` |
| Live schema/RLS | MCP Supabase read-only: 9 public tables, 29 policies, all tables RLS-enabled, `anon` no table grants | live metadata | Verified 27 Okt 2026 |
| Live RPC inventory | 5 public routines; 16 target read/session/receipt names absent; no revision column/receipt table | live metadata | Verified 27 Okt 2026 |
| Live write exposure | `authenticated` direct DML on 7 tables; `active_sessions.template_id` cascade; `workout_sets` ownership check only via `exercise_log_id` | live metadata | Verified 27 Okt 2026 |
| Branch/Room inventory | `online-only` Room 9 dengan 7→9 `OnlineRecoveryMigration`; `development` local Room 6; `origin/development` Room 7; `main`/`offline-first` Room 4; semua `versionCode=1`, `versionName=1.0` | git refs | Verified source 27 Okt 2026; device upgrade NOT_TESTED |
| Readiness audit script | `supabase/tests/full_online_only_readiness_audit.sql` | SQL audit script | Verified source; query setara dijalankan read-only via MCP, bukan migration/attack test |
| Workout completion lokal lalu upload attempt | `data/repository/WorkoutLocalStore.kt:477–585`; `WorkoutSessionManager.kt:21` | code | Verified |
| Ticker UI/service dan validator bergantung repository session | `presentation/ui/workout/ActiveWorkoutViewModel.kt:164–209`; `core/service/WorkoutService.kt:103–163`; `core/timer/RestTimerAndroidRuntime.kt:80–97` | code | Verified |
| Progres memakai semua log dan Profile memakai ProgressViewModel | `presentation/ui/progress/ProgressViewModel.kt:83–90`; `ProgressPresentation.kt:17–28`; `presentation/ui/profile/ProfileScreen.kt:43–54` | code | Verified |
| History route sekarang per tanggal, WorkoutDetail adalah template | `presentation/navigation/Routes.kt:21–50`; `NavGraph.kt:146–265`; `presentation/ui/progress/WorkoutHistoryDetailScreen.kt:43–79` | code | Verified |
| Pending/REVIEW/intents dan sesi dipertahankan saat upgrade/logout | `data/local/database/OnlineRecoveryMigration.kt`; `LogoutRecoveryStore.kt` | code | Verified source; device upgrade NOT_TESTED |
| Target RPC/state/model baru pada spec | Kebutuhan pengguna + rekomendasi audit; belum source implementasi | proposed design | Unverified implementation |

## 17. Coverage Map

| Area | Coverage inspeksi | Batas |
| --- | --- | --- |
| UI screens/widgets | Partial | Caller/route dan screen relevan dibaca; tidak ada screenshot/device audit baru |
| State management | Verified jalur utama | Home/Progress/catalog/picker/active/editor/summary; bukan seluruh state di seluruh repository |
| Data/API | Baseline/live metadata historis; backend lokal dan REST smoke historical PASS | 17 RPC existing termasuk Home summary; empat RPC recovery Proposed/NOT_TESTED, bukan deployment utama |
| Routing/navigation | Verified | Routes/NavGraph/MainScreen dan notification entry flow utama |
| DI/wiring | Source current: facade/store/guard Start kedua flavor | Graph auth/exercise/coordinator/template guard Room masih harus dilepas; bukan full clean cutover |
| Localization | Partial | Resource ID/EN upload existing diperiksa; copy target belum dibuat resource |
| Analytics | Unverified existing coverage | Hanya usulan observability, tidak mengklaim tracker terpasang |
| Security/privacy | Partial keseluruhan; backend lokal security fixtures Verified | SQL grants/RLS/cross-owner dan JWT HTTP lokal lulus; bukan penetration test menyeluruh, hosted security proof, atau review backup/privacy Android |
| Tests | Historical backend dan Android lokal PASS | Laporan §7.3–§7.9 termasuk 387/395 unit dan smoke REST; tidak direrun pada revisi ini, bukan production/release readiness |
| Target feature | Belum release-ready | Gate implementasi baru NOT_TESTED; setiap AC dan proof source final tetap harus dipetakan, bukan mengulang seluruh fase historis |
