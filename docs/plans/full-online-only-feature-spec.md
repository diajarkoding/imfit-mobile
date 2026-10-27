# IMFIT Full Online-Only — Feature Spec

## 0. Metadata

| Field | Detail |
| --- | --- |
| Nama fitur | Full Online-Only Data Architecture |
| Feature ID / ticket | N/A — belum diberikan |
| Status | **Draft usulan**; keputusan G0 dan audit live G1 selesai, implementasi belum dimulai |
| Versi dokumen | 1.2 |
| Tanggal dibuat / diperbarui | 26 / 27 Oktober 2026 |
| Target rilis | TODO/VERIFY — setelah product, backend, migration, dan release gates |
| Platform / topology | Android native; single application module `:app` |
| Source scope | Shared `app/src/main`, production/demo DI, SQL migration, test terkait |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` |
| Confidence | Baseline source Verified; target Proposed; schema live diperiksa read-only 27 Oktober 2026; runtime dan security attack test NOT_TESTED |
| Dokumen pasangan | [Plan implementasi, gap analysis, dan evidence map](full-online-only-implementation-plan.md) · [Laporan implementasi berkelanjutan](full-online-only-implementation-report.md) |

**Aturan membaca:** bagian berlabel **Baseline** menjelaskan source yang diperiksa. Semua kebutuhan MUST/harus dan nama kontrak baru di bawah adalah **target usulan**, belum diimplementasikan. Spec ini memakai struktur dokumentasi fitur repository, tetapi disimpan di `docs/plans` sesuai permintaan karena sifatnya rancangan.

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

### Status kesiapan dan blocker (review 27 Oktober 2026)

**G0 selesai dan audit live G1 selesai; implementasi backend/Android belum dimulai, sehingga aktivasi production tetap belum siap.** Konsep Full Online-Only, Coroutines/StateFlow, refresh, dan cursor pagination layak digunakan, tetapi audit metadata tidak membuktikan fitur target sudah berjalan atau teruji.

| Area | Status 27 Oktober 2026 | Kriteria siap |
| --- | --- | --- |
| G0 keputusan produk | **Selesai** — D01–D06 | Keputusan tercatat dan acceptance criteria diperbarui |
| G1 audit backend | **Selesai read-only** — tersedia matriks AVAILABLE/MISSING/NEEDS CHANGE dan evidence | Audit dapat direproduksi; tidak ada mutasi database |
| Backend final | **Belum siap** | Kontrak RPC/receipt/versioning tersedia dan terdeploy; integration/concurrency/security tests lulus |
| Active Workout remote | **Belum siap** | Start/update/pause/resume/finish/cancel/recovery terkonfirmasi server; timer tanpa per-tick network; D01/D02 diterapkan |
| Legacy recovery | **Sebagian** — scope versi ditetapkan, proof perangkat belum ada | Inventory/checkpoint/review aman untuk Room 9/6/7 dan akun; konflik diselesaikan sebelum create; data lain recoverable |
| Implementasi penuh | **Terblokir** | B02–B05 ditutup sesuai gate, acceptance criteria lulus, tidak ada risiko integritas data terbuka |

B01 telah ditutup oleh keputusan D01. Blocker yang masih wajib: **B02 backend final**, **B03 migrasi legacy**, **B04 pengamanan write server**, dan **B05 recovery/transaksi idempotent**. Backend/active/legacy adalah pengelompokan pekerjaan, bukan pengurangan blocker. Detail prioritas dan bukti penutupan ada pada plan bagian 1 dan 4.

Pekerjaan contract/fake transport, Paging spike, refresh state/UI, serta persiapan migration/legacy dapat dimulai tanpa mengaktifkan perubahan pada data pengguna. Penyempurnaan API, Paging, statistik/timezone, timer recovery, dan UX bukan alasan mengganti arah arsitektur, tetapi verifikasinya tetap wajib sebelum fitur terkait dirilis.

**Catatan evidence live:** audit read-only menemukan hanya tiga migration Agustus yang tercatat deployed (baseline, catalog seed, hardening). `save_template_revision`, `template_write_receipts`, kolom `workout_templates.revision`, serta seluruh 16 nama RPC target untuk read/session/receipt **tidak ditemukan**. Public schema memiliki 9 tabel dengan RLS aktif dan 29 policy; `anon` tidak memiliki table grant, tetapi `authenticated` masih memiliki grant tulis langsung (INSERT/UPDATE/DELETE sesuai tabel) pada 7 tabel; `profiles` tidak memiliki DELETE. `active_sessions` belum memiliki `version`/`status` dan FK template masih `ON DELETE CASCADE`. Ini bukti metadata, bukan uji runtime atau uji serangan RLS.

## 2. User Stories & Acceptance Criteria

Semua acceptance criteria belum diverifikasi terhadap implementasi target.

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
- `Profile` saat ini memakai `ProgressViewModel`; pemisahannya merupakan target refactor.

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

| RPC/table | Status yang dapat dibuktikan |
| --- | --- |
| `save_template_revision` | Ada pada source migration dan dipanggil Android; **tidak ditemukan pada schema live 27 Oktober 2026** dan belum terdeploy |
| `upsert_template_aggregate` | Tersedia live dan di source hardening; bukan kontrak revision/receipt yang dipakai target |
| `upsert_workout_aggregate` | Tersedia live dan di source hardening; bukan protokol finish-active-session |
| `active_sessions` | Tersedia live dengan RLS dan `UNIQUE(user_id)`; belum memiliki `version`/`status`/receipt; runtime sesi sekarang tetap lokal |
| `profiles`, `exercises`, `workout_templates`, `workout_logs` | Nama tabel dan RLS terverifikasi live; grants/constraints live berbeda dari kontrak target dan belum diuji runtime |

Pada source migration (belum live), template RPC membawa `p_request_id`, `p_template_id`, `p_expected_revision`, `p_name`, `p_exercises`, `p_removed_exercise_ids`, `p_is_deleted`, dan `p_owner_id`. Receipt membawa request/template ID, revision, exercise count, dan server update time. Revision bersifat monoton; **jangan mengasumsikan selalu naik tepat satu**, karena source memiliki child-trigger revision juga.

#### B. Proposed backend operations

Nama pada tabel berikut adalah **nama usulan RPC**, bukan endpoint yang sudah tersedia. Tidak ada REST `/workout-logs` generik yang diklaim sebagai endpoint existing IMFIT.

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

Field berikut **usulan tambahan/perubahan schema**, bukan field baseline yang dianggap sudah tersedia:

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
- Tambahan usulan: Paging 3 core/common yang sesuai target Android dan Paging Compose, serta testing helper bila dibutuhkan.
- Versi final dan kompatibilitas SDK/Gradle dipastikan pada spike; jangan memasang versi semata berdasarkan contoh tanpa build verification.
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
- Backup/restore rules harus eksplisit untuk auth secret, timer state, dan legacy recovery; restore tidak membuat operasi ganda atau alarm stale.
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

Status pengujian runtime target: **NOT_TESTED**. Audit live 27 Oktober 2026 hanya membaca metadata dengan role read-only dan `transaction_read_only=on`; detail matriks T01–T16 dan execution gates ada di plan implementasi.

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
| Strategi | Backend additive → bridge recovery → remote reads/writes → timer parity → staged rollout → compatibility lockdown |
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
| Feed history global/dedicated template list diperlukan? | Open scope; jangan menganggap route sudah ada | Product |
| Durasi history akan mengurangi pause? | Proposed: pertahankan baseline end−start; perubahan terpisah | Product/QA |
| Client lama masih boleh direct write? | **Open cutoff**; live audit mengonfirmasi bypass authenticated; B04 wajib ditutup pada final target | Backend/Release |
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

## 15. Referensi

- [Plan implementasi dan gap analysis](full-online-only-implementation-plan.md).
- [Template dokumentasi fitur](../features/template-feature-documentation.md).
- [Online-first integration history](../online_first.md), khususnya bagian integrasi/deployment limitation sebelum rencana historis.
- [Design system source audit](../skills/imfit-ui-ux/references/design-system.md); snapshot referensi harus dibandingkan source saat implementasi.
- [Android Paging overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), diperiksa 26 Oktober 2026.
- Design mockup baru / ticket / PR implementasi: N/A — belum tersedia dalam scope audit.

## 16. Evidence Map

Evidence baseline lengkap beserta path/line dan confidence ada pada [plan bagian 10](full-online-only-implementation-plan.md#10-evidence-map-dan-confidence). Ringkasan klaim utama:

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
| Data/API | Verified source + live metadata, runtime NOT_TESTED | Repository, coordinator, DTO, migration/RPC; schema/grants/policy live dibaca read-only; tidak ada row/RPC/runtime test |
| Routing/navigation | Verified | Routes/NavGraph/MainScreen dan notification entry flow utama |
| DI/wiring | Verified | Production dan demo binding; runtime target belum diimplementasikan |
| Localization | Partial | Resource ID/EN upload existing diperiksa; copy target belum dibuat resource |
| Analytics | Unverified existing coverage | Hanya usulan observability, tidak mengklaim tracker terpasang |
| Security/privacy | Partial | Manifest, SQL policies/grants live, backup config, owner fence; direct-write/bypass dan cross-owner risk ditemukan, tetapi tidak ada penetration/runtime test |
| Tests | Verified fixture source + audit script, NOT_TESTED execution | Selected unit/SQL fixtures dibaca; readiness audit SQL ditambahkan tetapi belum menjadi gate integration |
| Target feature | Draft | Semua acceptance criteria dan release proof masih harus diimplementasikan/diverifikasi |
