# IMFIT — Refresh, Paging, Shimmer & Safe Synchronization Plan v2

**Tanggal audit:** 21 Oktober 2026. **Mode:** ANALYSIS / PLAN ONLY.
**Lokasi dokumen:** `docs/plan/IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.md` (dipindahkan dari direktori plan lokal atas permintaan pengguna).
**Addendum review terbaru:** [Finalize Plan v2.1 & Build Readiness Gate](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.1.md). Gunakan addendum untuk rekomendasi D1–D9, prioritas risiko, dan gate pelaksanaan terbaru; bukti source audit tetap di baseline ini. Keduanya bukan izin implementasi.
**Repository:** `/Users/iskan/development/Project Android/IMFIT/IMFIT-MOBILE`.
**Baseline VERIFIED:** branch `fix-bug`, HEAD `37bfdf9d817d60a8b9fb2e6e2cae1478c5ffca8d`, dengan perubahan enam issue yang masih uncommitted. Bukti di bawah mengacu pada **working tree**, bukan hanya HEAD.
**Deliverable:** hanya dokumen ini. Tidak mengubah source, dependency, schema, data, navigasi, aset, konfigurasi perangkat, atau desain Pencil. Tidak menjalankan build, install, maupun operasi Supabase.
**Status pembaruan dokumen:** pengguna menegaskan **“hanya perbarui dokumennya”**. Izin ini tidak mencakup implementasi aplikasi atau persetujuan atas rekomendasi teknis yang masih terbuka.
**Status eksekusi fase A–G:** belum dimulai untuk implementasi. Temuan audit dan matriks test tetap merupakan baseline/rencana; tidak ada hasil pengujian baru pada pembaruan dokumentasi ini.

## Status dan cara membaca bukti

- **VERIFIED:** perilaku/struktur terbukti melalui inspeksi source atau pembacaan mockup; bukan berarti teruji runtime pada audit ini.
- **PROPOSED:** rancangan perubahan/test untuk implementasi setelah persetujuan.
- **UNCONFIRMED:** belum dibuktikan, memerlukan benchmark, runtime test, atau bukti backend aktual.
- **BLOCKED:** gate belum terpenuhi; jangan memperluas scope atau mengklaim aman tanpa keputusan/bukti.

Singkatan path, agar lokasi tetap eksplisit:

- `M/` = `app/src/main/java/com/diajarkoding/imfit/`.
- `P/` = `app/src/production/java/com/diajarkoding/imfit/`.
- `D/` = `app/src/demo/java/com/diajarkoding/imfit/`.
- `UT/` = `app/src/test/java/com/diajarkoding/imfit/`.
- `IT/` = `app/src/androidTestDemo/java/com/diajarkoding/imfit/`.
- Nomor baris adalah snapshot audit dan harus dicari ulang jika source berubah.

## 1. Executive technical review

### 1.1 Kesimpulan

**VERIFIED:** Room/reactive repository, durable pending aggregates, guarded acknowledgement, dan transaksi importer adalah fondasi yang layak dipertahankan. **Belum layak menganggap rencana awal memenuhi seluruh safety gate.** Shimmer existing belum seluruhnya sesuai bentuk konten; restart observer dapat mengganti cache dengan skeleton; mutex existing belum setara single-flight lintas entry point; guard identitas belum mencakup commit secara atomik untuk semua perubahan sesi.

**PROPOSED:** gunakan stale-while-revalidate berbasis Room, state availability per section, coordinator tunggal lintas entry point, serta local pagination hanya pada daftar dan picker. Shimmer dirancang bersama state, bukan lapisan kosmetik sesudahnya.

**BLOCKED sebelum Build penuh:** putuskan kontradiksi empat metrik versus dua kartu aktual, perilaku logout dengan pending data, initial-sync overlay, serta batas jaminan snapshot/conflict backend. Tidak ada izin tersirat untuk mengubah kebijakan autentikasi atau protokol backend.

### 1.2 Scope lock

| Target | Pull-to-refresh | Local infinite scroll | Content-aware skeleton |
| --- | --- | --- | --- |
| Home | Ya | Tidak | Ya, per section |
| Daftar exercise per kategori | Ya | Ya | Ya, hanya daftar |
| Exercise picker, termasuk caller edit/active yang sudah ada | Ya | Ya | Ya, hanya daftar |
| Progress | Ya | Tidak | Ya, sesuai komponen yang benar-benar ada |
| Profile | Ya | Tidak | Ya, selektif |
| Workout history per tanggal | Ya | Tidak | Ya, sesuai hierarki existing |

**PROPOSED / tetap terkunci:** tidak ada global history screen, pagination rutinitas, grid kategori/tahun, shimmer seluruh MainScreen, redesign kartu, perubahan MainScreen navigation, timer engine, completed-volume, ataupun aturan auto-fill.

### 1.3 Koreksi faktual penting

1. **VERIFIED:** SQL seed memuat **81 unique exercise IDs** (`supabase/migrations/20260828130132_imfit_catalog_seed.sql:26–110`); fixture demo hanya **42** (`M/data/local/FakeExerciseDataSource.kt`). **UNCONFIRMED:** jumlah aktual pada Supabase yang sedang dipakai. Tidak menghubungi backend untuk menghitungnya.
2. **VERIFIED:** `ProgressMetrics` menghitung empat nilai bulanan, tetapi `ProgressMetricOverview` hanya merender **dua kartu**. “Weekly Time” menerima `monthlyMinutes`; bukan perhitungan mingguan (`M/presentation/ui/progress/ProgressPresentation.kt:8–28`, `ProgressComponents.kt:74–115`). Empat total di history adalah total **harian**, bukan bukti empat kartu di dashboard.
3. **VERIFIED:** browser secara eksplisit `sortedBy(id)`; DAO exercise dan picker tidak memiliki urutan SQL eksplisit. Memaksakan satu urutan baru di semua jalur adalah perubahan perilaku, bukan sekadar plumbing paging.
4. **VERIFIED:** logout menghapus data lokal pengguna setelah drain sync. Pending rows ikut terhapus (`AuthRepositoryImpl.kt:274–282`, `IMFITDatabase.kt:51–58`). Persistensi retry tidak bertahan melewati logout tersebut.

## 2. Existing implementation audit dengan bukti

### 2.1 Entry points dan dependency map

**VERIFIED:** tidak ada refresh coordinator baru, PagingSource, atau UseCase refresh khusus saat ini. Diagram menunjukkan jalur existing; panah refresh terpisah dari jalur pembacaan Room.

```text
NavGraph ON_RESUME / route entry ──> RemoteRefreshViewModel.refresh
MainScreen tab callback ──────────> RemoteRefreshViewModel.refresh
                                   └─> SyncRunner.run -> SyncManager -> Supabase
                                                              └─> Room -> observers

HomeScreen -> HomeViewModel -> AuthRepository.observeCurrentUser -> UserDao
                           -> WorkoutRepository observers -> template/log/child DAOs
                           -> SyncStateProvider (status saja)

ExerciseListScreen -> ExerciseBrowserViewModel -> ExerciseRepositoryImpl -> ExerciseDao
                                             └─refreshExercises -> SyncRunner.refreshMasterData

ExerciseSelectionScreen -> ExerciseSelectionViewModel -> ExerciseRepository -> ExerciseDao
                                                    -> WorkoutRepository.getTemplateById (exclusions)
                                                    └─refreshExercises -> master refresh

ProgressScreen / ProfileScreen / WorkoutHistoryDetailScreen
  -> masing-masing default Hilt ProgressViewModel pada owner route-nya
  -> AuthRepository.observeCurrentUser -> UserDao
  -> WorkoutRepository.observeWorkoutLogs(userId) -> log/exercise-log/set DAOs
  -> ProgressPresentation / ProfilePresentation / WorkoutHistoryPresentation
```

| Status | Target / owner aktual | Bukti implementasi relevan |
| --- | --- | --- |
| VERIFIED | Root shell/tab | `M/presentation/ui/main/MainScreen.kt:92–98,171–215`: Navigation 3 decorators, tab refresh callback, progress refreshKey, initial-sync overlay |
| VERIFIED | Outer graph | `M/presentation/navigation/NavGraph.kt:44–54,178–208,238–264`: foreground/route triggers, catalog, tiga jalur hasil picker, history, profile |
| VERIFIED | Auto refresh | `M/presentation/navigation/RemoteRefreshViewModel.kt:18–37`: VM mutex, user ID, cooldown 30 detik berbasis attempt, `viewModelScope` |
| VERIFIED | Home | `M/presentation/ui/home/HomeScreen.kt:32–41,63–106`, `HomeViewModel.kt:24–35,55–110`: refresh lokal me-restart observer, satu isLoading untuk section |
| VERIFIED | List | `M/presentation/ui/exercise/ExerciseListScreen.kt:37–90,125–145`, `ExerciseBrowserViewModel.kt:35–71`: resume + init refresh, local getAll + Flow, sort ID, filter kategori di UI |
| VERIFIED | Picker | `M/presentation/ui/exercise/ExerciseSelectionScreen.kt:37–50,58–137`, `ExerciseSelectionViewModel.kt:19–36,52–104`: state selection/filter terpisah, exclusions, footer, initial retry |
| VERIFIED | Progress | `M/presentation/ui/progress/ProgressScreen.kt:32–38,50–99`, `ProgressViewModel.kt:22–90`: init dan refreshKey restart observer; profile dan history memakai loading/error bersama |
| VERIFIED | Profile | `M/presentation/ui/profile/ProfileScreen.kt:36–115`, `ProfilePresentation.kt:8–19`, `ProfileComponents.kt:42–95,249–291`: identitas diprioritaskan di atas error history; preferensi tetap |
| VERIFIED | History tanggal | `M/presentation/ui/progress/WorkoutHistoryDetailScreen.kt:43–59,74–171`, `WorkoutHistoryPresentation.kt:28–40`: date header tetap; existing content mendahului loading/error |
| VERIFIED | Exercise persistence | `M/data/local/dao/ExerciseDao.kt:9–29,31–41`, `M/data/repository/ExerciseRepositoryImpl.kt:25–47`: no ORDER BY; Flow; cooldown success-only; tidak fallback fixture production |
| VERIFIED | Workout reactive read | `M/data/repository/WorkoutRepositoryImpl.kt:78–102`: invalidationTracker + transaction + distinctUntilChanged; parent/child dibaca lengkap |
| VERIFIED | Backend transport | `M/data/sync/SyncManager.kt:429–592`, `M/data/remote/ImfitAggregateRemoteDataSource.kt:53–79`: PostgREST pages dan RPC workout |

**VERIFIED:** `refresh()` Home dan `loadData()` Progress bukan remote refresh. Menautkan gesture langsung ke keduanya hanya me-restart pembacaan lokal dan berpotensi flicker.

### 2.2 Lifecycle/loading gaps

| Status | Temuan | Bukti / konsekuensi |
| --- | --- | --- |
| VERIFIED | Home init + `LaunchedEffect(Unit)` + sync-success memanggil loader | HomeScreen:37–40; HomeViewModel:51–58. Data masih berada di state tetapi composable memilih shimmer saat isLoading |
| VERIFIED | Progress init + refreshKey mengulang load | ProgressViewModel:43–50; ProgressScreen:33,70–98. isLoading/hasError mengganti metric/calendar walau history lama masih tersedia |
| VERIFIED | Avatar signing mendahului observer workout | HomeViewModel:73–89; ProgressViewModel:69–83. Cache workout dapat tertahan network signed URL |
| VERIFIED | Browser dapat memublikasikan list kosong sebelum remote selesai | ExerciseBrowserViewModel:37,64–67; ExerciseListScreen:73–88. Tidak ada coverage marker untuk membedakan cache belum pernah terisi dan empty sah |
| VERIFIED | Picker memangkas selected saat catalog emission | ExerciseSelectionViewModel:73–78 menggunakan mapNotNull terhadap seluruh catalog. Untuk Paging ini tidak boleh diterapkan pada window loaded; nonaktif juga saat ini terhapus diam-diam |
| VERIFIED | Confirm picker belum validasi ulang DAO | ExerciseSelectionScreen:48–50 langsung meneruskan object selected; `confirmed` one-shot diset sebelum callback |
| VERIFIED | Profile dan History punya prioritas cache yang lebih baik | ProfilePresentation:11–18, WorkoutHistoryPresentation:33–40. Pertahankan prinsip, perlu state refresh/offline terpisah |
| VERIFIED | Home local error dapat menjadi empty | WorkoutRepositoryImpl:104–140 menangkap error dan mengembalikan empty; HomeViewModel:95–97 tidak memiliki load error field |
| VERIFIED | Observe identity menangkap akun sekali | AuthRepositoryImpl:335–341: belum flatMapLatest session identity; cacheUser check:344–347 terpisah dari insert |
| UNCONFIRMED | Scroll tahan re-entry / paging invalidation | Default LazyColumn state dan Navigation 3 decorators ada, tetapi belum diuji untuk pager baru/account switch; stable key saja bukan jaminan anchor |

### 2.3 Design tokens, loading existing, dan mockup

**VERIFIED:** `M/theme/DesignSystem.kt:202–283,293–356,371–391` menyediakan gutter 20 dp, headerContentGap **8 dp**, spacing 4/8/12/16/20/24, radius template 12, small 10, medium 14, avatarLg 72, minimum target 48, metricHeight 116, typography greeting/metric/profile/calendar. `IMFITColors.training` memiliki pasangan semantic surface/raised/border/text. Tidak perlu palette baru.

**VERIFIED:** `M/presentation/components/common/IMFITShimmer.kt:40–101` menyediakan brush animasi linear 1200 ms, ShimmerBox, ShimmerCircle. Brush memakai Material surfaceVariant; setiap primitive dapat membuat transition sendiri. showShimmer=false menghasilkan transparent, **bukan** static skeleton. Tidak ada kebijakan reduced-motion/delayed skeleton eksplisit di helper ini.

**VERIFIED:** ShimmerWorkoutCard:105–136 thumbnail 52 dp/elevation berbeda dari HomeWorkoutCard thumbnail 56 dp/bordered flat. ShimmerStatCard:205–230 dipakai Home dan Progress, padahal struktur kartu berbeda. ShimmerExerciseCard:282–309 tidak mempunyai slot checkbox/description; tidak boleh langsung dianggap cocok untuk dua daftar. Profile memiliki skeleton selektif existing; history mempunyai skeleton statis dua kali dua metrik dan blok sesi (`WorkoutHistoryComponents.kt:638–737`).

**VERIFIED — inspeksi MCP read-only:** app aktif menunjuk `/Users/iskan/development/Pen Dev/imfit.pen`. Tersedia frame Profile `NWdx5`, loading `HJCP9`, handoff `BrXAc`; history `a1m64P`, loading `TXJ9X`, handoff `SW6eK`. Screenshot kedua loading dan teks handoff diperiksa. Handoff menegaskan preferensi lokal tetap, content mendahului reload, dan history hanya ringkasan read-only. Daftar root yang dikembalikan saat membaca path Pencil historis juga memuat frame-frame ini; ini bukan bukti kedua file byte-identik.

**UNCONFIRMED:** final mockup Home/List/Picker/Progress tidak ditemukan pada daftar root yang tersedia; source aktif menjadi acuan konservatif. Persetujuan final keenam visual tidak diasumsikan. Teks handoff masih menyebut source redisgn lama; gunakan working tree sebagai fakta runtime. Tidak ada node/file Pencil diubah atau diekspor.

### 2.4 Dependency baseline

**VERIFIED:** `gradle/libs.versions.toml:3,21–23,32,41,48,60–63,82–84`: Compose BOM `2026.08.00`, Room `2.8.4`, WorkManager `2.11.2`; Material3 mengikuti BOM. `app/build.gradle.kts:107–111,127–139,159–164`: dependency Compose/Room/testing tersedia, **Paging belum ada**.

**PROPOSED:** pakai API PullToRefreshBox dari Material3 existing setelah compile compatibility check; jika memerlukan opt-in, batasi pada composable target. Jangan menaikkan BOM agar API tertentu tersedia tanpa review. Jika Paging disetujui, tambahkan hanya paging-runtime, paging-compose, room-paging dan paging-testing yang kompatibel; versi dipin sesudah pemeriksaan compatibility, bukan ditebak di audit ini.

## 3. Gap analysis terhadap plan awal

| Status | Gap / koreksi v2 |
| --- | --- |
| PROPOSED | Pisahkan local availability, remote refresh, Paging load states, pending writes; jangan memperluas isLoading lama |
| PROPOSED | Pertahankan observer lama; remote refresh tidak me-restart loader atau clear list; signed-avatar resolution tidak menghambat cache lain |
| PROPOSED | Persisted dataset coverage diperlukan untuk membedakan no cache dari verified empty setelah process restart; local query kosong saja tidak cukup |
| PROPOSED | Single-flight service application-scoped, bukan tambahan mutex per screen; semua jalur direct run/reconnect/Worker/master wajib terhubung |
| PROPOSED | Pilihan ID dan resolved metadata terpisah dari paging; confirm validasi aktif/exclusions/context melalui repository, bukan loaded items |
| PROPOSED | Browser mempertahankan urutan ID existing; urutan picker perlu keputusan karena baseline SQL tak terdefinisi |
| BLOCKED | Empat kartu metrik bukan layout existing; jangan menambahkan dua kartu atau mengubah weekly/monthly tanpa approval |
| BLOCKED | Fullscreen initial-sync overlay bertentangan dengan target skeleton area konten; perubahan gating memerlukan scope eksplisit tanpa mengubah navigation |
| BLOCKED | Logout safety tidak dapat disebut PASS sambil menghapus pending rows. Harus pilih kebijakan proteksi sebelum menjalankan skenario berisiko |
| UNCONFIRMED | Server pagination completeness, parent-version invariant, RLS deploy, conflict antar perangkat belum terbukti end-to-end |

## 4. Screen-by-screen content-aware shimmer mapping

Semua kolom **Existing UI Shape** di bawah **VERIFIED** melalui source; skeleton/trigger/perilaku baru **PROPOSED**. Unknown bukan sinonim empty. Skeleton tidak berperan sebagai tombol/pilihan. Aksesibilitas bersama: satu pengumuman loading terlokalisasi per section, live region polite hanya pada transisi, descendants dekoratif disembunyikan, tidak mengumumkan tiap frame animasi.

### 4.1 Home

Source: `HomeComponents.kt:48–55,59–124,160–201,205–253`; `HomeScreen.kt:64–106`.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Greeting | greeting 23sp/27.6 line + supporting 13sp | Hanya nama/greeting unknown, tinggi line sesuai typography; subtitle statis tetap | Profile lokal belum resolved setelah delay | Nama langsung, refresh tidak menutupnya | Cached name / fallback yang jujur; error profil tidak menutup routine | Key greeting, gap xs, gutter 20, header 8 | Satu label loading identitas; bukan “User” palsu |
| Strength banner | Artwork + headline statis, adaptive font layout | Tidak ada | N/A, lokal statis | Selalu tampil | Tetap aset/fallback existing | Jangan ubah banner atau tinggi token | Dekorasi tidak dibacakan |
| Last workout | Bordered CardSmall, heading/date, dua metric duration/volume | Heading/date bars, caption tetap bila diketahui, dua value bars dan divider 38dp | Last-log section belum resolved | Snapshot log lama tetap | Inline update warning, jangan ubah log null karena error | Padding16, summaryGap14, metric typography/column weights sama | Nilai unknown tidak dibaca nol |
| Routine cards | min80, radius12, pad12, thumbnail56, title+count, trailing20 | 2–3 bordered flat cards dengan slot identik, bukan legacy elevated card | First local template result belum tersedia | Cards/active badge tetap; add-day tidak diblok karena refresh | Cache + pesan; no-cache error bukan empty | Keys template_ID; item spacing12; font wrapping mengikuti content | Noninteractive placeholders; add action minimal48 |
| Sync status | HomeSyncStatusCard non-blocking | Tidak ada | Pending/sync state | Status akurat terpisah dari content | Partial/pending tetap disebut pending | Key sync_status; perubahan tinggi sah hanya jika status aktual berubah | Polite, tidak klaim all-synced jika pending>0 |

**PROPOSED:** greeting, last workout, routines mempunyai readiness terpisah; profile yang lambat tidak menahan seluruh Home.

### 4.2 Exercise list per kategori

Source: `ExerciseListScreen.kt:59–90,97–145`. Thumbnail aktual adalah **artwork kategori 48dp**, bukan foto remote per exercise. Description opsional dan dapat multiline.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Appbar/search | RoutineAppBar, outlined search min52, top8 | Tidak ada | N/A, route/query diketahui | Search tetap interaktif | Retry tidak clear query/kategori | Appbar di luar refresh; IME/insets existing | Label search/clear tetap |
| Exercise rows | Surface radius12, border1, min76, pad12, art48, title+category+optional description | 5–7 sesuai viewport, art48, title/metadata/description slots proporsional | First query/page unknown setelah delay | List tetap, indikator remote overlay | Initial error hanya jika tak ada data; error update non-blocking | Spacing8; width-aware lines, bukan fixed giant card | Grup loading bukan sejumlah exercise fiktif |
| Append | Belum ada data pagination | Tidak ada skeleton ulang; footer spinner kecil | Paging append lokal | Item lama tetap | Footer retry lokal | Reserved footer area; tidak menyentuh header | Label memuat halaman / coba lagi |
| Empty/no results | Kartu empty dengan clear search | Tidak ada | Query selesai + coverage diketahui | Empty tetap saat refresh | Offline unavailable jika catalog belum pernah tersedia | Key terpisah dari rows; jangan sempat tampil empty saat unknown | Bedakan no-results dan offline |

### 4.3 Exercise picker

Source: `ExerciseSelectionScreen.kt:58–137`; state: `ExerciseSelectionViewModel.kt:19–36,52–104`.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Search/filter/context | Min52 search, quick chips ALL/CHEST/BACK + sheet, routine subtitle | Tidak ada | Known route/filter | Semua tetap; exclusions/context tidak direset | Error terpisah dari filter | Header8; chips/footer tidak masuk skeleton | Selected filter semantics tetap |
| Selectable row | radius12, min76, pad12, art48, title+category, checkbox48 | Slot sama termasuk ruang checkbox **noninteractive**; tanpa description baru | First local page unknown | Rows tetap bisa dipilih saat remote refresh bila valid | Cache rows + warning, tidak men-disable seluruh picker | Spacing8; preserve checkbox width48, no fake selected state | Loading group tanpa Role.Checkbox palsu |
| Result count | Full filteredExercises.size | Placeholder angka sampai COUNT query selesai | Total-query count unknown | Count seluruh filtered dataset, bukan itemCount loaded | Unknown count tidak jadi 0 | Label tetap di tempat yang sama | “Hasil belum tersedia” bila perlu |
| Confirmation footer | RoutineFooter sticky, count/reset/button | Tidak ada; count dari selected IDs | N/A; terpisah dari list load | Selected IDs/count stabil lintas append/filter/refresh | Invalid selection diberi pesan terarah; jangan reset diam-diam | Scaffold bottomBar dan imePadding tetap; refresh indicator hanya list | Count akurat, confirm busy saat validasi saja |
| Append | Belum ada | Footer kecil di dalam list | Local append | Pilihan tidak dipangkas oleh unloaded rows | Retry mempertahankan pilihan | Footer loading tidak menutupi bottomBar | Announce append sekali |

**PROPOSED:** selected ID yang menjadi nonaktif tetap tercatat sebagai unresolved/invalid sampai user menyelesaikan; confirm tidak meneruskan gerakan nonaktif. Batalkan confirm jika context berubah, tampilkan koreksi, jangan lock `confirmed=true` permanen saat validasi gagal. Metadata terpilih boleh di-resolve ulang dari seluruh DAO. Validasi terakhir juga diperlukan pada boundary penambahan agar race setelah confirm tidak menyisipkan data salah; jangan mengubah snapshot gerakan yang sudah berada di sesi aktif.

### 4.4 Progress

Source: `ProgressComponents.kt:40–115,119–195,209–232`; `ProgressScreen.kt:50–98`. Tidak ada histogram pada route ini. Month adalah bulan sekarang; selectedDate disimpan per month, bukan arbitrary month selector di route overview.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Profile row | Avatar48, name/email, arrow; min56 | Circle48 + name/email bars; bukan generic welcome | Identity lokal unknown | Identitas tetap, image fallback terpisah | Avatar gagal hanya fallback | Gap12, profileName typography, trailing48 | Profile action tetap dapat diakses |
| Metric overview | **Dua** kartu: volume dan time; min116, value25sp/27.5, caption12, period11 | Shell/card/label/period tetap; value-only bars pada posisi angka | History read/aggregate belum siap | Nilai lama tetap | Pesan update gagal, bukan mengganti dashboard | Responsive row/stack pada width<320 / font>1.3; heightIn | Unknown tidak dibaca sebagai 0 |
| Monthly workouts/completed sets | Dihitung model, **tidak dirender sebagai kartu** | N/A sampai layout disetujui | N/A | Kalkulasi tetap full dataset | Tidak memalsukan empat kartu existing | Tambahan dua kartu memerlukan review visual | Tidak menambah angka dekoratif |
| Month calendar | Month/header/weekdays, tanggal48, indicator34, legend | Month/tanggal tetap; unknown markers/data state saja; jangan shimmer seluruh tanggal | History coverage unknown | Markers lama dan selectedDate tetap | Unknown count tidak diucapkan “0 sesi” | monthDays menghasilkan 4–6 minggu, bukan template tetap 5 | Date label tersedia; statistik ditandai loading |
| Selected day summary | Icon40, date/title, aggregate summary, min64 | Date tetap; summary line unknown saja | Selected-date data belum diketahui | Summary lama tetap; buka history tetap sesuai data | Error terpisah dari legitimate no-session | Key selected_day, font wrap existing | Jangan menyatakan hari tanpa sesi sebelum known |

**BLOCKED / persetujuan:** default konservatif adalah mempertahankan dua kartu dan perhitungan bulanan. Memenuhi literal “empat kartu / Weekly Time mingguan” memerlukan perubahan produk/visual tersendiri; jangan diterapkan diam-diam.

### 4.5 Profile

Source: `ProfileComponents.kt:42–105,112–138,249–291`; `ProfileScreen.kt:90–114`.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Identity | Avatar72; name titleLarge, email supporting; stack width<320/font>1.3 | Reuse selective existing circle/lines; hilangkan line ketiga yang tak cocok konten aktual bila tak diperlukan | Profile benar-benar unknown | Identity langsung, refresh tidak menutupnya | Photo fallback tidak mengubah identity ke error | 72 slot tetap; adaptif sama; width clamp skeleton di layar sempit | Existing single polite loading label dipertahankan |
| Information | Tiga read-only rows name/email/DOB min76, icon40, divider | Reuse rows; label statis tetap, skeleton hanya value unknown | Profile field belum diketahui | Nilai cached tetap; DOB null sah tampil missing-value, bukan loading abadi | No-cache error + retry section saja | Padding16/12, radius CardSmall, heights grow with font | Static field labels terbaca; skeleton tidak jadi fake account |
| Preferences/logout | Rows min88, controls tema/bahasa, logout | Tidak ada | Data lokal sudah tersedia | Tetap interaktif sesuai action guards | Gagal profile tidak menutupi settings | Bottom spacing32 dan inset existing | Switch semantics existing; logout mengikuti keputusan gate |

**PROPOSED:** pisahkan identity readiness dari history errors dan avatar signed-URL status. Tidak wajib membuat ProfileViewModel baru; boleh memperjelas substate ProgressViewModel tanpa mengganti owner/navigasi.

### 4.6 History by date

Source: `WorkoutHistoryDetailScreen.kt:100–170`, `WorkoutHistoryComponents.kt:387–525,582–635,638–737`. Existing hanya menampilkan aggregate completed-set count/volume per exercise, **tidak merender rincian berat/reps setiap set**.

| Component | Existing UI Shape | Skeleton Shape | Loading Trigger | Cache Available Behavior | Error Behavior | Layout Preservation | Accessibility |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Date header | Weekday/full date terpilih | Tidak ada | Route date diketahui | Tanggal sama saat refresh | Tetap pada tanggal tersebut | Header8, key date_header | Heading tanggal tetap |
| Daily totals | Empat total harian duration/volume/exercises/completed sets | Value bars pada shell metrik existing; reuse SkeletonMetricCard setelah adaptasi | Date dataset unknown | Totals lama tetap | Cache + update banner | Ikuti stacked boolean width<=340/font>=1.25; skeleton kini selalu2x2 perlu diperbaiki | Unknown bukan nol |
| Session card | radius14 border1 pad16, title/duration badge, tiga summary metrics | Header/duration/value placeholders; satu-dua exercise summary rows | First date result unknown | Session cards tetap | Existing update-failure banner dipertahankan | Title stack/row sesuai breakpoint existing, stable session ID | Satu loading sesi, tidak ada sesi fiktif |
| Exercise summary | Name + completed set label + volume | Text/count/volume slots, divider antar rows | Bagian snapshot belum tersedia | Detail lama tetap | Jangan separuh menghapus children | Tidak menambah set-detail UI yang tak ada | Placeholder bukan “set selesai 0” |
| Empty | Date-scoped empty state | Tidak ada | Local query selesai dan coverage history diketahui | Verified empty tetap saat refresh | Offline unavailable / actual error dibedakan | Tidak ada transient empty sebelum loading selesai | Pesan tidak mengklaim gagal latihan/hari istirahat |

### 4.7 Token/reuse decision

**PROPOSED:** reuse primitive shape/brush dengan shared phase per section, bukan menyalin komponen legacy yang tak cocok. Reuse ProfileCard/Profile loading dan history skeleton secara terarah. Ukuran komponennya mengikuti source, bukan angka mockup baru. Gunakan training surface/raised/border untuk warna netral light/dark; tidak memakai kilatan primary biru. Tidak perlu token global baru. Delay awal150ms dan cycle1200ms menjadi parameter loading internal; baru dijadikan token motion jika pola terbukti lintas layar dan disetujui.

## 5. Global loading state machine

### 5.1 Empat sumbu orthogonal

**PROPOSED — kontrak konseptual, bukan nama class existing:**

1. **LocalContent per context/section:** Uninitialized, ReadingLocal, Available(data), Empty(coverage), Unavailable(reason). Context mencakup account/session epoch, dataset, query/category/exclusions atau selectedDate. Empty tidak dibuktikan oleh `emptyList()` default.
2. **RemoteRefresh:** Idle, Running(flightId), Success, PartialSuccess(dataset outcomes), Offline, NetworkFailure, ServerFailure, AuthChanged, Cancelled. Termasuk freshness timestamp, bukan mengganti content state.
3. **LocalPage:** initial/refresh/append LoadState + generation/queryKey + endReached. Paging state bukan sinonim remote sync state.
4. **PendingWrites:** durable pending count/status/last failure milik akun; status saving draft/confirm tetap terpisah. Available content dapat memiliki pending writes.

**PROPOSED:** simpan last valid rendered content **hanya untuk context yang sama** ketika Room sedang requery; bukan cache kedua yang authoritative. Account/session switch wajib purge display snapshot dan selection. Query baru tidak boleh menampilkan hasil query lama sebagai hasil query baru.

### 5.2 State transitions dan rendering

| State yang diminta | Kondisi / transisi | Representasi |
| --- | --- | --- |
| Uninitialized | Context baru, belum ada result | Shell/statics + reserved content slots; jangan empty/0 |
| Initial local loading | ReadingLocal tanpa result | Setelah sekitar150ms, skeleton section; bila data datang lebih cepat tampil langsung |
| Content available | Room result berisi data valid | Content langsung; stop skeleton tanpa minimum duration |
| Verified empty | Query sukses + coverage relevan known | Empty/no-results; 0 sah hanya untuk aggregate yang telah diketahui |
| Offline with cache | Available/verified-empty cache + remote Offline | Cache + non-blocking offline status |
| Offline without cache | Query kosong dan dataset belum pernah tersedia + Offline | Offline-unavailable + retry; skeleton berhenti |
| Refreshing with content | Content known + remote Running | Content + PullToRefresh indicator overlay |
| Append loading | Ada rows + local append Loading | Footer spinner saja |
| Append failure | Append Error | Rows + footer retry, tak menyentuh refresh result |
| Refresh failure | Remote/local refresh error dengan data lama | Data lama + pesan terarah; spinner selesai |
| Partial synchronization | Ada dataset sukses, upload/read lain gagal | Terbitkan dataset outcomes; pending tetap; bukan all-synced |
| Authentication changed | Epoch/account invalid | Batalkan publication/commit akun lama; kosongkan data sensitif lama dan tunggu context baru |

**PROPOSED:** no-cache online dengan Room kosong yang belum terhidrasi tetap unknown saat refresh berjalan, bukan verified empty. Jika remote gagal, akhiri skeleton menjadi error/offline-unavailable. Cache empty yang sudah authoritative tidak perlu shimmer ulang. Query no-results atas katalog valid adalah empty filter lokal, tidak membutuhkan remote request.

### 5.3 Coverage dan error semantics

**VERIFIED:** SyncPreferences menyimpan initial-completed/per-user timestamps, bukan typed per-section coverage. Katalog global tersimpan bersama tetapi timestamp existing per user; pengguna baru dapat punya katalog shared yang valid.

**PROPOSED:** perjelas provenance/coverage per dataset dan owner. Pilihan tanpa migrasi awal: DataStore marker versi berisi account/dataset/successfullyCommitted timestamp; hanya set sesudah transaksi berhasil, tidak pernah sesudah network saja. Karena Room dan DataStore tidak satu transaksi, crash di antaranya harus fail-safe **unknown**, jangan false empty. Jangan menganggap history complete hanya karena timestamp katalog ada. Jika exact crash-atomic coverage diperlukan, metadata tabel Room memerlukan proposal migrasi dan approval terpisah. Tidak ada migrasi direncanakan otomatis.

**PROPOSED:** audited read path harus mengeluarkan local error eksplisit, bukan exception -> empty/null (`getTemplates`, last-workout). Jaga kompatibilitas caller lain dengan jalur typed observation yang terarah. Catch CancellationException harus rethrow; cancellation tidak boleh terlihat sebagai hasil kosong atau terminal error palsu.

## 6. Pull-to-refresh and paging interaction design

### 6.1 Gesture, ownership, dan target dataset

**PROPOSED:** gesture mengirim intent ViewModel -> refresh coordinator; coordinator memakai engine existing yang diperkuat -> commit Room -> observers. Tidak ada network/DAO di composable body.

| Screen | Demand dataset | Upload prerequisite |
| --- | --- | --- |
| Home | PROFILE + TEMPLATES + HISTORY | Flush pending aggregate terkait sebelum pull dataset mutable |
| List / Picker | CATALOG | Tidak menunggu upload workout/routine yang tak terkait |
| Progress | PROFILE + HISTORY | Flush history terkait; metrik tetap dari database lengkap |
| Profile | PROFILE | Read-only route; jangan menjalankan ulang full history tanpa kebutuhan |
| History date | HISTORY | Tahap awal full owner history pull, bukan protokol range baru |

**PROPOSED:** Wrapper di dalam content area setelah inset Scaffold, appbar/bottom nav bukan anak yang bergerak. List/picker membungkus viewport daftar, search/filter/footer di luar indikator. Home/Progress/Profile/History membungkus lazy content; indikator overlay tidak menambah top padding8. Uji drag dari empty/error/short content, overscroll, keyboard, diagonal gesture, dan list bukan di atas. Tidak meng-intercept gesture checkbox/switch/filter sheet. Accessibility custom action refresh tersedia ketika gesture tak dapat dioperasikan.

**BLOCKED:** MainScreen selalu memasang SyncProgressDialog ketika isInitialSync. Usulan minimum: jadikan initial download non-blocking pada enam target lewat gating overlay existing, tanpa mengubah Navigation 3/backstack atau scrim policy. Jangan diam-diam menghapus overlay global; perlu approval karena shared shell berada di luar screen target.

### 6.2 Hubungan remote refresh dengan Paging

**PROPOSED:**

- Manual pull -> coordinator remote refresh. **Tidak** memanggil `LazyPagingItems.refresh()` sebagai pengganti Supabase sync.
- Commit Room meng-invalidasi PagingSource dan membentuk generasi lokal baru otomatis.
- Tidak memanggil items.refresh() lagi setelah setiap commit, sehingga tidak terjadi reload ganda.
- `retry()` append -> local paging retry saja, tidak Supabase.
- Jika local initial/refresh DB gagal, aksi retry lokal terpisah; jika catalog remote gagal, aksi refresh coordinator.
- Tidak ada RemoteMediator, remote fetch dari append, atau sync yang dipicu snapshotFlow itemCount.
- Selama invalidasi pada **query sama**, retain old items sampai generation valid siap; jika library tidak menjamin perilaku ini pada versi terpilih, gunakan publication/transition guard teruji. Tidak menyalin semua dataset ke UI demi menyamarkan paging.
- Query/filter/exclusions change -> flatMapLatest pager baru; jangan render stale generation sebagai hasil baru. Debounce search awal200ms dievaluasi, query text tetap langsung berubah. Selection bukan bagian cancellation pager.
- Jumlah hasil picker melalui SQL COUNT terfilter penuh; `itemCount` hanya loaded items. Selection count berasal dari ID set. Clear filters berbeda dari reset selection.
- Confirm resolve IDs + active status/exclusions terhadap seluruh repository dan context revision; validasi error tidak menavigasi/mereset pilihan.

### 6.3 Room Paging 3 trade-off dan urutan

| Opsi | Manfaat | Biaya / batas | Keputusan |
| --- | --- | --- | --- |
| Existing LazyColumn/full Flow | Sederhana; cukup untuk81; sudah virtualisasi komposisi | Semua rows dimaterialisasi/filter di memori; bukan data pagination | SAFE TO RETAIN sebelum scope baru disetujui |
| Slice/take dari full list | Dependency nol | Bukan DB paging, semua data masih dibaca; mudah salah count/filter/invalidation | Tidak dipilih sebagai klaim infinite data loading |
| Custom LIMIT/OFFSET/keyset | Dependency lebih kecil | Harus membangun retry/generation/invalidation/anchor sendiri | Alternatif jika dependency Paging ditolak, perlu desain ulang terpisah |
| Room Paging3 | SQL filter, lifecycle/generation/load states standar; sesuai local infinite scroll eksplisit | Untuk81 manfaat kecil; tambah runtime/compose/room-paging/testing, mapping subgroup/demo | **PROPOSED bersyarat:** pilih untuk fondasi scale dan scope infinite scroll, bukan klaim peningkatan performa terukur |

**UNCONFIRMED:** belum benchmark. Phase A/D membandingkan81/150/1500 rows, query latency p50/p95, allocations, time-to-first-content, invalidation/anchor, frame timing pada device representatif. Jika overhead/mapping tidak sepadan, pause phase paging dan review opsi; jangan menghilangkan scope user diam-diam.

**PROPOSED konfigurasi awal:** pageSize30, initialLoadSize60, prefetchDistance10, enablePlaceholders=false, Room PagingSource<Int, ExerciseEntity>, repository mapping, ViewModel cachedIn. Tidak set maxSize yang bisa men-drop page sebelum kebutuhan dan scroll test terbukti. Parameter final menunggu benchmark.

**VERIFIED:** browser sortedBy(id) (`ExerciseBrowserViewModel:56`), picker mempertahankan urutan DAO tanpa ORDER BY (`ExerciseSelectionViewModel:73–78`). ID string lexical saat ini berarti `ex_back_10` sebelum `ex_back_2`; jangan mengganti ke natural sort tanpa keputusan produk. SQL seed menyimpan urutan lexical, tetapi insert order bukan kontrak SELECT.

**PROPOSED:** browser SQL `ORDER BY id COLLATE BINARY ASC` mempertahankan explicit ID order existing, id sudah unique tie-breaker. Picker disarankan urutan deterministik sama agar dua katalog konsisten, **perlu approval** karena baseline tak terdefinisi; jika product sort lain dipilih, gunakan `<productSort>, id` dan test parity.

**PROPOSED SQL filtering sebelum paging/count:** is_active + category + subgroup verified IDs + literal trimmed case-insensitive name search + exclusions. `ExerciseCategory.includes` membedakan LEGS dari CALVES dan mapping GLUTES yang overlap; jangan memaksa subgroup baru berdasarkan nama, jangan mengubah persisted enum IDs. Ekstrak pure filter specification bersama untuk SQL/fixture parity (bukan DAO bergantung pada presentation enum). Uji wildcard `%`/`_` agar SQL LIKE tidak mengubah literal contains existing; escape atau parameterized INSTR yang teruji. Uji casefold Unicode karena SQLite NOCASE berbeda dari Kotlin ignoreCase. Exclusion list harus aman terhadap SQLite bind limit jika bertumbuh; jangan membangun SQL string dari input user.

**PROPOSED demo parity:** D/data/repository/DemoExerciseRepository.kt mengembalikan42 fixture, bukan Room. Buat deterministic local PagingSource fixture dengan filter/count/resolve ID contract yang sama; jangan mengganti production kosong dengan fixture. Fixture150 khusus pengujian tidak mengubah katalog produk.

## 7. Anti-flicker & visual stability strategy

| Status | Risiko aktual / potensial | Mitigasi dan bukti penerimaan |
| --- | --- | --- |
| PROPOSED | Room cepat menampilkan kilatan skeleton | Delay presentation awal150ms (rentang evaluasi120–200), tidak menunda hasil; fake clock tests query20ms/250ms |
| PROPOSED | Blank selama delay | Reserve shell/shape space nonanimated sejak awal, statics terlihat; tidak empty/0. Result membatalkan timer segera |
| PROPOSED | Loader restart saat sync success/re-entry | Observer per context hidup di VM, replay last valid same-context state; hilangkan reset isLoading pada remote refresh |
| PROPOSED | Profile image menahan data lain | Publish local identity/history dulu; signing/image fetch independen, keyed path/owner, jangan re-sign setiap emission tak terkait |
| PROPOSED | Fullscreen recomposition/per-frame update | Shared shimmer phase per section, animate draw layer bila memungkinkan; loading state di area minimum; profile/history compute off-main bila mahal |
| PROPOSED | Card height melompat | Shell/radius/padding/slot sama; value bars mengikuti lineHeight fontScale, width clamp; min-height bukan fixed clip |
| PROPOSED | Refresh menggeser scroll | rememberLazyListState pada owner stabil; same-query anchor ID+offset, fallback tetangga jika item dihapus; jangan scrollToItem(0) tiap invalidation |
| PROPOSED | Filter cepat menampilkan generasi salah | Context/query revision; flatMapLatest; reset anchor hanya untuk perubahan filter eksplisit, bukan refresh; loading daftar baru saja |
| PROPOSED | Duplicate/animation ghost | Stable unique exercise/session keys; contentType; tidak crossfade dua salinan list atau animate insertion generik saat swap skeleton |
| PROPOSED | Theme flash | Ambil warna semantic live theme, tidak remember brush tanpa keys; jangan key seluruh graph/theme agar state hilang |
| PROPOSED | Motion dinonaktifkan | Static nontransparent skeleton ketika animator scale0/reduced-motion; hentikan infinite transition, jangan hanya set transparent; verifikasi Compose motion duration behavior pada versi resolved |
| PROPOSED | Footer/pull gesture konflik | Indicator dibatasi viewport, Scaffold bottomBar/IME tetap; test hit area dan scroll short-content |

**UNCONFIRMED:** existing theme memiliki animasi300ms; shimmer tidak akan mengubah animasi tema global. Ukur kebutuhan motion policy helper lokal sebelum mengubah primitive global yang dipakai layar di luar scope. Tidak ada forced minimum shimmer duration dan tidak menunda data siap demi animasi halus.

## 8. Concurrency and synchronization safety design

### 8.1 Audit synchronization existing

| Status | Temuan source | Implikasi |
| --- | --- | --- |
| VERIFIED | SyncManager:75–77,121–166 punya syncMutex dan masterMutex terpisah | Serialisasi per jalur, bukan shared result single-flight |
| VERIFIED | Full sync memanggil pullExercises tanpa masterMutex (183); master refresh (158) punya masterMutex | Dua pembacaan katalog dapat overlap dan commit dengan urutan berbeda |
| VERIFIED | Repository cooldown success-only, mutex (ExerciseRepositoryImpl:25–39); remote VM cooldown attempt/per-user (RemoteRefreshViewModel:21–35) | Force calls/failed attempts bisa antre dan mendownload berulang; cooldown ganda tidak dataset-aware |
| VERIFIED | performInitialSync mengulangi catalog/profile yang baru dipanggil (SyncManager:183–195,256–260) | Duplicate first-run read bahkan tanpa navigasi cepat |
| VERIFIED | Reconnect scope SupervisorJob + collectLatest (69,82–91) | Network transition dapat membatalkan sync yang berjalan; bukan UI lifecycle |
| VERIFIED | Worker unique APPEND_OR_REPLACE per user, exponential10s (P/data/sync/WorkManagerSyncScheduler.kt:24–37) | Persistent retry ada, tetapi enqueue burst tetap dapat membentuk chain; bukan KEEP/coalescing |
| VERIFIED | SyncRunResult hanya3 enum; refreshExercises Boolean (SyncRunner.kt, ExerciseRepositoryImpl) | Offline/auth/error/cooldown/partial tidak dapat dipresentasikan akurat |
| VERIFIED | SyncState pendingCount diupdate init/success, bukan Flow queue (SyncManager:95–114,206) | UI pending dapat terlambat; count bukan bukti semua child/job selesai |
| VERIFIED | Cancellation rethrow di outer sync tanpa universal finalization (214–221); initial catch luas (296) | Tidak ada jaminan status terminal Running selalu dibersihkan pada semua jalur |

### 8.2 Single-flight, demand merging, burst control

**PROPOSED:** satu service coordinator singleton, application SupervisorJob untuk read flights, engine tetap internal worker dari coordinator; tidak membuat screen memanggil coordinator lalu engine memanggil coordinator lagi. Entry points `SyncRunner.run`, `refreshMasterData`, reconnect dan WorkManager harus memakai registry yang sama atau facade yang join registry. Menambah coordinator khusus gesture saja tidak cukup.

- Flight key: `(ownerId, sessionEpoch, dataset)`; catalog global datanya tetapi request/credential epoch tetap di-key agar akun lama tidak publish. Stage mutable data juga mempunyai local-write revision/barrier.
- Registry mutex hanya untuk acquire/register/remove flight, **tidak dipegang selama network**. Deferred shared result, new waiter join tanpa enqueue run baru. `finally` membersihkan entry hanya jika flightId masih sama.
- Overlap Home/Progress/History berbagi HISTORY; Home/Profile berbagi PROFILE; full sync dan catalog berbagi CATALOG. Multi-target request mengembalikan outcomes per dataset, bukan satu full-sync Boolean.
- Upload-first barrier per akun untuk dataset mutable; jangan share hasil pull yang dimulai sebelum local write baru jika request menuntut fresh-after-write. Recheck pending/revision saat commit; follow-up paling banyak satu demand yang benar-benar belum tercakup, bukan N requests.
- Initial sync menggunakan hasil stage yang sudah selesai dalam flight yang sama, tidak mengulang katalog/profil.
- Manual melewati auto cooldown30s, tetapi join flight aktif. Burst gesture dalam frame/debounce awal500–750ms disatukan tanpa antrean; tidak mematikan refresh karena spinner masih aktif pada caller lain.
- Auto cooldown terpusat per account/epoch/dataset berbasis monotonic time. Failed-auto retry pakai exponential backoff + jitter bounded; WorkManager existing exponential dipertahankan. Jangan menambahkan loop auto retry dalam setiap screen atau mengklaim jitter existing sudah ada.
- Bound concurrency awal satu mutable-user lane, maksimal satu independent catalog lane; evaluasi profil lane agar tidak unbounded. Mutex tidak membuat urutan lock melingkar dengan logout.
- Worker/upload durable dimiliki scheduler/queue, bukan viewModelScope. Keluar layar hanya menghentikan waiter/UI collection; tidak menghapus pending rows. Background constraints/process death tetap ditangani WorkManager dari database.
- **Jangan ganti APPEND_OR_REPLACE langsung menjadi KEEP:** race write setelah worker scan terakhir bisa hilang wakeup. Jika queue coalescing diubah, tambahkan drain-until-quiescent + revision handshake/pending recheck dan enqueue race tests terlebih dahulu.
- Logging baru redacted: flightId, dataset, origin, joined/started, epoch tag tak berisi identitas, request/page count, elapsed, terminal reason. Tanpa token, email, signed URL, DOB, payload akun. Fakes memiliki counter per endpoint agar dedup dapat dibuktikan.

### 8.3 Identity isolation dan commit fence

**VERIFIED:** requireCurrentUser dipanggil **sebelum** `database.withTransaction`/importer (SyncManager:447–448,468–470,512–513,560–561), importer tidak menerima session epoch. ID check bukan atomis dengan commit. Explicit logout menambahkan blockedUsers lalu menunggu syncMutex/masterMutex sebelum delete (146–150), sehingga jalur logout ini memitigasi in-flight writes, tetapi bukan bukti semua SDK session transitions terlindungi. Login/register/cacheUser/updateProfile tidak memakai shared transition lock.

**PROPOSED:** satu owner session gate/epoch registry:

1. Semua app-controlled login/logout/account switch melakukan transition melalui gate yang sama; increment epoch dan block commits akun lama sebelum cleanup.
2. Network di luar gate dengan token `(owner, epoch)`. Tidak memegang gate saat request menunggu jaringan.
3. Sebelum apply: acquire commit fence; periksa owner/epoch/block; jalankan validasi owner/pending/revision **di transaksi**; fence tetap dipegang sampai Room transaction selesai. Lock order tunggal: session fence -> Room; coordinator registry lock tidak ditahan.
4. Auth transition tidak menghapus DB sambil memegang fence lalu menunggu job yang perlu fence. Block/invalidate, release, drain/cancel read jobs, baru cleanup yang telah diizinkan.
5. Observer identity mengikuti session revision dengan flatMapLatest; UI state/pager keys juga owner/epoch. clear old display/selected sensitive data sebelum publikasi akun baru. Repository by-ID caller perlu validasi owner, karena beberapa query existing tidak mempunyai user_id filter.
6. Cancellation sebelum commit rollback; jika commit sudah sah, hasilnya tetap di partisi owner dan tidak ditampilkan kepada owner lain. Jangan mengklaim cancellation dapat membatalkan transaksi yang sudah committed.

**UNCONFIRMED / gate:** SDK automatic expiration/restoration/sessionStatus event dapat terjadi di luar app lock. Audit integration event ordering diperlukan untuk mendefinisikan linearization point dan mencegah ABA login ulang userID sama. Sekadar membandingkan currentUser dua kali atau mengumpulkan event async tidak menutup TOCTOU. Tanpa bukti fencing seluruh transition, account-race gate tetap BLOCKED; jangan menjanjikan solusi cukup dengan check tambahan.

### 8.4 Outcomes dan job finalization

**PROPOSED:** Success, PartialSuccess, Offline, NetworkFailure, ServerFailure, AuthChanged, Cancelled dibedakan; no authenticated session bukan success sinkronisasi. Klasifikasi berdasarkan network/HTTP/auth exception aktual, bukan semua exception dianggap offline. Outcome berisi dataset yang committed/skipped conflict/pending, sehingga tidak menyatakan all-synced.

Timeout read terbatas sesuai konfigurasi HTTP; lifecycle/cancel melepas locks dan running indicator melalui finally, keyed request token mencegah flight lama menutup spinner flight baru. UI waiter selesai walau durable upload tetap menunggu retry. Cancellation tidak ditelan; hard failure mempertahankan rows dan draft.

## 9. Room / Supabase data consistency analysis

### 9.1 Ordering dan atomic boundary existing

**VERIFIED:** SyncManager:181–204 membaca catalog/profile lebih dahulu secara independent, lalu upload pending templates/workouts, lalu pull template/history jika upload terkait berhasil. Ini bukan murni upload-first untuk semua dataset. Istilah komentar “delta” tidak cocok: pulls tetap complete owner collections, tanpa cursor updated_at.

**VERIFIED:** catalog dikumpulkan semua pages sebelum satu Room transaction deactivate-missing/upsert (429–455); page exception sebelum commit mempertahankan catalog. Template/history **atomic per aggregate**, bukan seluruh collection (493–587). Parent re-read dibandingkan dengan DTO awal sebelum importer; aggregate yang selesai lebih awal boleh committed walau aggregate berikut gagal. Ini partial sync sah, jangan disebut rollback seluruh history.

**VERIFIED:** importer memeriksa child ownership, duplicate exercise/set IDs, graph references; dalam transaksi menolak owner mismatch, local syncStatus non-SYNCED, atau local.updatedAt lebih baru (`RemoteSnapshotImporter.kt:10–47`). Child replacement dan history provenance memakai transaksi. Catalog belum punya duplicate-ID/domain validation yang setara.

### 9.2 Mutable-data protection matrix

| Mutable data | VERIFIED existing boundary | PROPOSED invariant / gap |
| --- | --- | --- |
| Routine parent/children | WorkoutRepositoryImpl:227–300 mutation transaksi; parent PENDING_SYNC + op + updatedAt; child replace | Pertahankan bump parent untuk semua child edit. Snapshot read upload parent/children konsisten dalam transaksi singkat, network setelahnya |
| Routine delete | Soft tombstone + pending DELETE (304–320) | Jangan hard-delete local pending saat refresh; remote parent hard-delete tanpa tombstone tidak terdeteksi |
| Finished workout/exercise/set | finishWorkout:438–543, completed-only persisted aggregate, stable IDs, enqueue; RPC send | Refresh tidak menyentuh finalization/session locks; typed outcomes jangan ubah payload perhitungan |
| Active session/completed edits | sessionMutex/lifecycleMutex/terminal set, Room active-session JSON | Snapshot importer tidak menulis active_sessions. Pertahankan template/session/history separation; jangan re-prefill on refresh |
| Acknowledgement | SyncManager:343–351,404–413; DAO markAsSyncedIfUnchanged compares timestamp+operation | Revision CAS mencegah banyak concurrent changes ditandai sync, tetapi timestamp millisecond collision/ABA belum diselesaikan. Wajib uji dan putuskan monotonic revision sebelum klaim universal |
| Editor draft | EditWorkoutViewModel:64–77 menolak remote adoption jika dirty/saving/adding | Draft tetap di VM; jangan menjanjikan persistensi process death. Tidak membuat autosave atau new draft table |
| Profile fields | UserEntity punya flags/time; updateProfile remote-first dan cache; no UI caller profile edit ditemukan | Pull profile saat ini unconditional insert tanpa dirty guard. Jangan mengklaim queue profile offline ada; guard row pending/version jika ada, serialize mutation/pull; jangan mengubah remote-first policy tanpa approval |
| Historical identity/provenance | WorkoutRepositoryImpl:637–669 memakai historical name/category; importer:35–42 retains inputDefaults jika performance sama | Jangan mengganti snapshot nama histori dari master atau kehilangan provenance karena UI paging |

**VERIFIED:** upload parent dan children dibaca dalam beberapa suspend queries sebelum network (SyncManager:326–329,392–395), bukan snapshot transaction tunggal. Parent timestamp CAS hanya mengamankan acknowledgement, bukan mencegah payload campuran dikirim. **PROPOSED MUST FIX:** capture aggregate dan revision atomik sebelum upload; sesudah response akui hanya revision tersebut. Gunakan guard child pending yang eksplisit atau invariant parent dirty yang diuji untuk semua mutator; importer saat ini memeriksa parent, bukan setiap child flag independen.

### 9.3 Snapshot completeness / pagination risk

**VERIFIED:** pages() memakai offset/range500 dan menganggap page<500 sebagai EOF (`SyncManager:479–487`). Order deterministik tersedia pada query remote. Jika page3 gagal setelah dua page500, catalog tidak committed; untuk history parent yang sebelumnya selesai dapat tetap ada. Test150 UI saja tidak menguji multi-page remote; gunakan setidaknya1001 remote rows.

**UNCONFIRMED:** deployed server row cap>=500; snapshot konsisten selama offset paging; count/ID tidak berubah di tengah scan; parent.updatedAt selalu berubah untuk child insert/update/delete. SQL triggers existing mengubah timestamp row child itu sendiri (`hardening.sql:315–341`), bukan bukti parent direvisi. Offset+stable order **bukan** isolated remote snapshot. Duplicate detection/total count saja tidak membuktikan snapshot konsisten bila perubahan saling mengimbangi.

**PROPOSED:** validate unique catalog IDs, category range, required fields, owner graph, expected response completeness sebelum transaksi destructive. Jangan silently distinctBy duplicate snapshot lalu deactivate records yang hilang. Parse/validation/network failure -> no replacement pada boundary snapshot terkait.

**BLOCKED / pilih sebelum Build:** untuk jaminan kuat pada snapshot multi-request diperlukan salah satu:

- Backend versioned snapshot/RPC read dengan revision/manifest dan parent-version invariant yang diverifikasi. Ini pekerjaan protokol terpisah yang memerlukan approval/backend test environment; tidak otomatis termasuk implementasi UI.
- Mode konservatif client: jangan melakukan destructive missing-record reconciliation / child replacement bila completeness/version consistency belum dapat dibuktikan; retain cache dan laporkan snapshot unverified/retry, bukan success. Ini mengorbankan freshness/removal propagation dan harus disetujui. Jangan menganggap dua scan identik sebagai bukti matematis transactional snapshot.

Untuk katalog81 satu response mengurangi risiko cross-page, tetapi masih perlu tahu server tidak truncate dan empty response benar-benar authoritative, bukan RLS/config berbeda. Partial **UI** pages tidak pernah diberikan kepada RemoteSnapshotImporter atau deactivate-missing.

### 9.4 Conflict policy dan backend limits

**VERIFIED:** pending protection + updatedAt comparison bukan distributed conflict resolution lengkap. Client wall-clock timestamp dibandingkan server time, equality diterima; device clock skew dan mutation pada millisecond sama belum dijamin. Workout RPC meng-upsert berdasarkan ID/ownership, tidak memakai expected server revision (`hardening.sql:490–566`). RPC mengembalikan serverUpdatedAt; client saat ini log/ack tanpa menyimpannya sebagai revision authoritative (`SyncManager:398–416`). Jangan memperkenalkan blind LWW baru.

**VERIFIED:** template remote update/delete-parent lalu delete-all-children + insert satu-satu **tidak satu remote transaksi** (`SyncManager:618–659`). Reader perangkat lain dapat melihat empty/partial children dengan parent version tak berubah selama replacement. Recheck parent DTO tidak menutup kasus ini. `docs/backend/imfit-supabase-compose-integration.md:105–107` melarang langsung beralih ke template RPC karena child UUID/pruning belum diselesaikan; Room memakai composite(template_id, exercise_id), bukan remote child UUID (`TemplateExerciseEntity.kt:7–10`).

**VERIFIED:** workout RPC memvalidasi auth/ownership dan upsert stable IDs; tidak boleh menganggap arbitrary omitted children otomatis dipruning. Retry identity idempotency berbeda dari exactly-once revision/timestamp semantics. Existing finished sessions kebanyakan immutable; jangan memperluas ke edit history tanpa protokol baru.

**PROPOSED:** pertahankan invariant existing dan tulis conflict-skip outcome, bukan mengklaim data ditimpa pasti remote lebih baru. Perubahan local revision column/server CAS/template RPC/tombstone adalah proposal terpisah jika dibutuhkan; tidak mengubah schema/protokol sebelum approval.

### 9.5 Logout/pending-data decision

**BLOCKED:** persyaratan “tidak ada kehilangan data, logout saat upload pending aman” tidak kompatibel dengan deleteUserData existing. Pilihan keputusan:

1. **PROPOSED recommended minimum:** cegah final logout/purge selama pending durable data ada; beri penjelasan, retry sync atau batal logout. Offline tidak boleh memaksa sync selesai; user tetap bisa batal. Perubahan UX/policy ini memerlukan persetujuan dan tanpa menyentuh timer engine.
2. Retain data per owner saat logout untuk upload sesudah akun itu login lagi; perlu keputusan privasi, retention, active-session cleanup, dan akun lain tidak boleh melihat/mengunggahnya.
3. Explicit discard confirmation adalah kebijakan kehilangan data **yang disengaja**, bukan memenuhi zero-data-loss gate. Jangan pilih ini diam-diam.

Menavigasi keluar screen berbeda dengan logout; pending queue harus tetap aman pada yang pertama. Audit ini tidak mengubah cleanup timer yang dipanggil logout.

### 9.6 RLS dan verifikasi remote

**VERIFIED config-only:** `supabase/migrations/20260828130154_imfit_backend_hardening.sql:69–278` membatasi profile ke auth.uid, parent ke user_id, child ke owned parent; RPC security invoker dan ownership checks:490–569. Storage paths berpemilik di policy:450–486. Client parent pulls owner-filtered dan importer memvalidasi graph.

**UNCONFIRMED:** deploy policy aktif, grants, akses UserB terhadap UserA, server cap, actual payload/atomicity, offline-reconnect-retry lintas perangkat pada project aktual. Dokumen backend historis bukan bukti audit runtime saat ini. E2E memerlukan disposable **authorized** project/account; jangan memakai akun produksi atau mengubah RLS melalui audit ini.

## 10. Implementation phases, dependency, file impact, rollback

Seluruh baris berikut **PROPOSED**, belum dieksekusi. Nama file baru adalah usulan dan dibedakan dari file existing.

| Fase | Dependensi / deliverable | File impact potensial | Risiko dan exit gate | Rollback terarah |
| --- | --- | --- | --- | --- |
| A — Baseline & invariants | Approval keputusan bagian12; inventory pending, auth, snapshot, ordering; characterization tests; perf baseline81/150/1500 | Existing sync/repository/test files untuk penambahan test setelah approval; tidak edit produk dahulu | Dirty working tree enam issue tidak boleh tertimpa; baseline lint lama dipisahkan | Tidak ada runtime change; simpan diff awal, jangan reset pengguna |
| B — Safe coordinator | A; shared single-flight/outcomes, session fence, aggregate snapshot capture, coverage, cancellation; gates backend/logout terjawab | `M/data/sync/{SyncManager,SyncRunner,SyncState,SyncWorker}.kt`, `P/data/sync/WorkManagerSyncScheduler.kt` bila queue diubah, `M/data/repository/{AuthRepositoryImpl,ExerciseRepositoryImpl}.kt`, remote refresh VM/DI; usulan `M/data/sync/RefreshCoordinator.kt`, `RefreshOutcome.kt` | Paling berisiko; fake concurrency dan identity barrier tests wajib sebelum UI; jangan ubah backend atau schema terselubung | Komit terpisah setelah diminta; revert hanya change set B yang dimiliki, jangan discard pending data |
| C — Availability & shimmer | B contracts, skeleton mapping disetujui; local observer tidak re-start | Home/Progress/Exercise VMs, Profile/History presentation, `IMFITShimmer.kt` terarah; usulan `presentation/components/common/ContentLoading.kt`, screen-local skeletons, strings ID/EN | Legacy helper dipakai luar target; static reduced-motion fallback dan no-flicker tests | Pertahankan API kompatibel, rollback renderer tanpa mengubah DB |
| D — Room paging | A decision Paging & order; B/C contracts; SQL filters/count/resolve; demo parity; benchmark | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `ExerciseDao.kt`, `domain/repository/ExerciseRepository.kt`, ExerciseRepositoryImpl, DemoExerciseRepository; pure query spec usulan | Small catalog benefit belum diukur; wildcard/subgroup/exclusions; no schema bump hanya query | Dedicated dependency/API change set, old full-read API tetap bagi caller lain |
| E — List & picker | B+C+D; pager + gesture + validation + count/anchor | `ExerciseListScreen.kt`, `ExerciseSelectionScreen.kt`, kedua VMs, tests; boundary caller hanya jika validation invariant menuntut dan direview | Selection/one-shot confirm/active result paling sensitif | Rollback screen adapter/pager tanpa menghapus selected/draft/database |
| F — Home/Progress/Profile/History | B+C; bisa paralel konseptual dengan D/E, tanpa perlu agent delegation | Enam screen terkait/HomeComponents/ProgressComponents/ProfileComponents/WorkoutHistoryComponents, VMs, presentation; SyncProgressDialog gating hanya jika approved | Dua vs empat metrics; false empty; overlay scope; signed-avatar latency | Renderer change set terpisah per konteks; tidak mengubah perhitungan |
| G — Verification & release gate | B–F; test matrix, benchmark, visual review, authorized E2E terpisah | Test suites + report; update skill/docs hanya jika diminta | Mock PASS bukan Supabase/OEM PASS; minified/device belum teruji | Hentikan rollout jika safety gate gagal; non-destructive forward fix/revert own commits dengan izin |

**PROPOSED rollback policy umum:** snapshot daftar diff/hash file sebelum implementasi; current uncommitted work bukan milik fase baru. Tidak `git reset --hard`, checkout file pengguna, uninstall app normal, clear data, downgrade Room, destructive migration, atau ubah setting perangkat. Kalau schema/revision metadata akhirnya diperlukan, hentikan fase dan minta migrasi/forward-compatibility plan baru. Commit/push hanya atas permintaan baru.

**PROPOSED compile/test saat Build telah diizinkan:** demo dan production compile/unit tests, isolated diagnostic instrumentation yang sudah ada, targeted lint + report full lint terpisah. Audit ini tidak menjalankan Gradle. Report enam issue sebelumnya mencatat dua NonObservableLocale pre-existing; jangan suppress atau mengklaim bersih tanpa run baru.

## 11. Regression and acceptance test matrix

### 11.1 Fixture dan kategori test

Semua test berikut **PROPOSED / NOT RUN** pada audit ini.

- **Unit (U):** pure state reducers, fake monotonic clock, delayed skeleton, coordinator request counters, filter parity, count/selection validation, metric equality.
- **DAO/integration (I):** in-memory Room versi7, PagingSource invalidation/count/exclusion, transactions/rollback, importer guards; jangan migrasi installed DB pengguna.
- **Compose (C):** controllable state/clock/semantics, screenshot before/during/after load, drag gestures, fixed header/footer bounds, selected count dan TalkBack semantics.
- **Concurrency (X):** latches/barriers/channels pada before-request, before-commit, inside-transaction, after-upload-before-ack. Bukan sleep acak.
- **Device/manual (M):** actual lifecycle/network/rotation/font/gesture/reduced-motion/perf; isolated diagnostic package + setting baseline/restore jika kelak disetujui.
- **Supabase E2E (S):** hanya authorized disposable project/accountA/B; verifikasi payload/DB/RLS server nyata, bukan fake repository.

Fixture minimum **150 unique exercises**, lintas kategori, 2 inactive, repeated names tetapi IDs unik, beberapa description panjang/Unicode/wildcards, subgroup verified, exclusions, selections pada item>60/>120. Perf fixtures81/150/1500; **remote multipage1001+** agar page3 benar-benar digunakan. History lintas bulan/zona/durasi/completed-only dan pending edits. Jangan menambah fixture ke production fallback.

### 11.2 Skenario wajib dan acceptance

| ID | Skenario | Jenis | Acceptance observable |
| --- | --- | --- | --- |
| T01 | First-install authenticated online, no cache | U/I/C/M/S | Shell/statics tampil; delayed section skeleton; data Room kemudian content; no false empty/0; initial overlay gate sesuai keputusan |
| T02 | First-install offline no cache | U/C/M | Skeleton berhenti; offline-unavailable, retry; tidak memunculkan fixture/fake history |
| T03 | Startup Room cache | I/C/M | Data langsung setelah first local result, tanpa skeleton flash untuk query cepat; signed avatar tidak menahan history |
| T04 | Manual refresh with cache | C/X/M/S | Same rows/values/selection/date selama request; hanya refresh indicator; header8 tetap |
| T05 | Tarik cepat10 kali | U/X/C | Satu active flight per key, jumlah network request = satu rangkaian dataset, tidak10 run berurutan |
| T06 | Home+Progress+History overlap | X | HISTORY download sekali, PROFILE sekali bila overlap; waiters menerima dataset outcomes masing-masing |
| T07 | Disconnect page3 remote snapshot | I/X/S | Catalog tidak berubah/tidak deactivate missing; history aggregate gagal tidak tersisa parsial; prior complete aggregates boleh committed dan outcome PartialSuccess |
| T08 | Supabase HTTP500/401/403/timeout; Room valid | U/C/S | Cache tetap, cause tepat, spinner selesai, tidak success palsu atau endless skeleton |
| T09 | AccountA -> B saat download / before transaction / commit fence | X/I/C/S | No stale write setelah epoch invalidated, no cross-account display/selection; test juga A->B->A epoch ABA |
| T10 | Logout upload pending, offline, in-flight upload | X/I/M/S | Sesuai pending protection approved; tidak auto-delete unsynced data; locks bebas/deadlock-free; tidak menggunakan akun B untuk payload A |
| T11 | Refresh bersamaan workout/routine local edit | X/I/S | Parent/child snapshot konsisten, old ack tidak clear newer pending, dirty snapshot tidak di-overwrite; same-millisecond mutation test |
| T12 | Dirty edit draft + remote update | U/I/C | Nama/config/input invalid/picker exclusions tetap; clean editor boleh adopt remote; process-death persistence tidak diklaim |
| T13 | Search menemukan exercise>120 | I/U/C | SQL filter seluruh dataset; count benar; `%`, `_`, Unicode dan trim parity, bukan filter loaded pages |
| T14 | Invalidation + insert/update/deactivate saat scroll page2 | I/C/X | No duplicate stable IDs; order deterministik, generation benar, anchor ID/offset dipertahankan bila masih ada |
| T15 | Select lintas page/filter/category | U/I/C | Count selected tidak berubah karena page unload; reset eksplisit saja; invalidated/deactivated ID perlu koreksi confirm, tidak silent removal |
| T16 | Local append error + retry | U/I/C | Footer error, previous rows tetap; retry tidak membuat remote request; complete local end tidak retry-loop |
| T17 | Progress & auto-fill sebelum/sesudah paging | U/I | Empat calculated fields identik, trusted prefill weight/reps/set-count identik; owner/history query tidak memakai paging window |
| T18 | Verified empty online/cache dan unknown-empty | U/I/C | Verified empty bukan endless skeleton; unhydrated empty bukan definitive no sessions; pending dataset markers tak tertukar |
| T19 | Light/dark, ID/EN, font130/150/200%, width320 | C/M | No clipping, neutral shimmer, static reduced-motion fallback, labels benar; theme/language tidak reset navigation/draft/selection |
| T20 | Header8, appbar/nav, sticky footer, IME | C/M | Bounding positions stabil saat pull/append; last row/CTA dapat disentuh; no extra top gap |
| T21 | Re-entry / rotation / fast tab navigation with cache | U/C/M | No unnecessary skeleton/re-signing/restart observer; restored query/date/anchor sesuai context; new account tidak reuse cache display |
| T22 | Rapid navigation request counts | X/M | Counters per dataset membuktikan cooldown/join bekerja; tidak N queued syncAll |
| T23 | Catalog duplicate IDs / invalid category / server cap<500 | I/X/S | Validation gagal tanpa destructive commit; jangan menganggap page pendek otomatis complete |
| T24 | Remote children berubah tanpa parent revision / parent hard-delete | X/S | Tidak dilabel safe snapshot; tampil limitation/retry sesuai keputusan protocol; tidak delete lokal berdasarkan UI pages |
| T25 | Worker enqueue burst/write tepat sesudah scan terakhir | X/I | Semua pending akhirnya diproses; tidak lost wakeup; bounded run/queue sesuai kontrak, retry durable after process death |
| T26 | Cancellation/timeouts di semua await/lock boundaries | U/X | Finally release flights/locks/indicators; cancelled bukan empty/success; navigation tidak menghapus pending upload |
| T27 | Unknown cepat20ms vs lambat250ms | U/C | Cepat tidak shimmer, lambat skeleton section muncul; hasil siap tidak dipaksa menunggu minimum duration |
| T28 | Avatar signing/image failure/lambat | U/C/M | Local identity/history tersedia; fallback avatar only; tidak global error/skeleton |
| T29 | Template upload gagal setelah delete children | X/S | Pending tetap retry; keterbatasan remote partial exposure ditunjukkan, tidak dianggap atomic; jangan switch RPC tanpa protokol |
| T30 | Completed-only/session/timer regressions | U/I/C/M | Sesi/volume/auto-fill rules unchanged; notification navigation/Stop/native theme existing acceptance tidak di-reinterpretasi |

### 11.3 Existing regression anchors

**VERIFIED tersedia di tree:**

- `IT/data/sync/RemoteSnapshotImporterTest.kt:28–63`: replacement, pending/newer-local, invalid graph, template owner; belum menguji network pages/session fencing.
- `IT/data/repository/ExerciseReactiveRepositoryTest.kt:22–61`: reactive cache, concurrent entry cooldown success path, failure/cancellation, profile owner. Ini bukan full cross-entry single-flight proof.
- `IT/data/repository/WorkoutRepositoryRoomFirstTest.kt`: local/session/aggregate/prefill baseline.
- `UT/presentation/ui/exercise/ExercisePickerStateTest.kt`, `UT/presentation/ui/progress/{ProgressPresentationTest,WorkoutHistoryPresentationTest}.kt`, `UT/presentation/ui/profile/ProfilePresentationTest.kt`.
- `IT/presentation/ui/exercise/ExerciseListScreenTest.kt`, `IT/presentation/ui/progress/{ProgressOverviewTest,WorkoutHistoryDetailScreenTest}.kt`, `IT/presentation/ui/profile/ProfileScreenTest.kt`, `IT/presentation/ActiveSessionEditingTest.kt`.

**PROPOSED:** tambah dedicated coordinator concurrency tests, SQL/Paging parity tests, account commit-fence tests, six-screen loading/refresh tests dan synthetic performance evidence. Jangan mengubah assertion metrik menjadi nilai baru hanya agar test lulus. Existing test counts dari laporan lama bukan hasil run audit v2.

### 11.4 Release/acceptance gate

**PROPOSED:** catat per variant/device dan evidence path: compile, unit, DAO, concurrency, UI, benchmark, manual, S-E2E terpisah dengan PASS/FAIL/BLOCKED/NOT_TESTED. Supabase unavailable boleh membatasi release claim, bukan diubah menjadi PASS. FULL safe-sync claim dilarang selama gate identitas/protokol/pending belum selesai. Safety failures menghentikan rollout; kosmetik tidak menutupi kegagalan data.

## 12. Known risks, open questions, approval decisions

### 12.0 Catatan persetujuan dan batas eksekusi

**VERIFIED — konfirmasi pengguna:** permintaan “eksekusi update” telah diperjelas menjadi pembaruan dokumen saja, bukan menjalankan rencana ke kode aplikasi.

- **Diizinkan:** memperbarui dan memindahkan dokumen perencanaan ini ke `docs/plan/`, serta menambahkan addendum v2.1 di folder yang sama sesuai permintaan terbaru. Tidak mencakup perubahan aplikasi.
- **Belum disetujui:** keputusan D1–D9 pada tabel berikut. Penyebutan rekomendasi dalam percakapan tidak mengubah statusnya menjadi keputusan pengguna.
- **Tidak dilakukan:** perubahan source/dependency/schema/backend/desain, build/test/benchmark, install/clear data, commit, atau push.
- **Syarat melanjutkan implementasi:** permintaan implementasi yang eksplisit serta penyelesaian keputusan BLOCKED yang relevan. Izin dokumentasi tidak digunakan untuk melewati gate keamanan data.
- **Pelaporan:** readiness tetap 4/8 sebagai kelengkapan artefak rencana, bukan 4/8 pengujian runtime yang lulus. Status Supabase E2E dan runtime acceptance tidak berubah.

### 12.1 Keputusan yang membutuhkan persetujuan

| ID | Status | Keputusan yang diperlukan | Rekomendasi |
| --- | --- | --- | --- |
| D1 | BLOCKED | Progress dua kartu existing vs empat yang diminta; weekly label vs monthly logic | Pertahankan dua kartu/perhitungan dulu; perubahan tambahan menjadi scope metrik/visual terpisah |
| D2 | BLOCKED | Pending data saat logout | Cegah destructive logout/purge jika pending, dengan retry/cancel; detail UX harus disetujui |
| D3 | BLOCKED | Initial-sync full-shell overlay vs content-only loading | Izinkan perubahan gating overlay existing saja; navigation/scrim/timer tetap |
| D4 | PROPOSED | Tambah Paging3 untuk81 seeded rows, target ekspansi150+ | Setujui conditional PhaseD/benchmark; jangan klaim performa sebelum diukur |
| D5 | PROPOSED | Urutan picker deterministik | ID lexical seperti explicit browser order; bila produk punya urutan lain, butuh acuan sebelum SQL ditulis |
| D6 | BLOCKED | Completeness/versioning cross-request snapshot dan template remote replacement | Pilih protokol backend terpisah atau mode conservative non-destructive saat snapshot unverified; jangan janji atomic server snapshot dari offset paging |
| D7 | UNCONFIRMED | Authorized Supabase E2E environment/accountA/B | Sediakan disposable project/account berizin; tidak mengakses produksi |
| D8 | PROPOSED | Coverage marker dan revision proof tanpa migrasi | DataStore fail-safe unknown untuk coverage; jika revision fencing butuh schema, review migration terpisah sebelum edit |
| D9 | UNCONFIRMED | Final reference visual Home/List/Picker/Progress | Pakai source existing sebagai acuan konservatif; konfirmasi mockup jika ada yang berbeda |

### 12.2 Readiness checklist 8 gate

Ini **readiness audit/design**, bukan klaim implementasi baru selesai. **4/8 lengkap sebagai artefak rencana; 4 masih parsial/blocked. Semua runtime acceptance v2 belum dijalankan.**

| Gate | Status audit | Bukti / sisa pekerjaan |
| --- | --- | --- |
| Shimmer map keenam konteks dari UI existing | Lengkap — VERIFIED source + PROPOSED map | Bagian4; mockup tersedia untuk Profile/History; mismatch Progress eksplisit, final visual tetap review |
| Initial/refresh/append/pending terpisah | Lengkap sebagai desain — PROPOSED | Bagian5; source belum mengimplementasikan |
| Valid Room content selalu dipertahankan refresh | Parsial | History/Profile punya prioritas cache; Home/Progress belum; acceptance T03/T04/T21 wajib |
| Account race dan pending protection terverifikasi | BLOCKED | Explicit logout drain ada, full commit fence/SDK transitions/logout pending belum memenuhi |
| Partial snapshot tidak merusak catalog/history | Parsial | Local aggregate transaction VERIFIED; cross-request consistency/server cap/template upload protocol belum terbukti |
| Selection/metrics/draft/scroll aman | Parsial | Draft guards dan full metrics VERIFIED; selected pruning/confirm/anchor baru perlu perbaikan/test |
| Paging3 justified + test plan | Lengkap sebagai keputusan bersyarat — PROPOSED | Bagian6.3/11, benchmark belum dijalankan, dependency perlu persetujuan |
| Test matrix dan rollback tersedia | Lengkap sebagai desain — PROPOSED | Bagian10–11; tidak menyatakan tests PASS |

### 12.3 MUST FIX BEFORE BUILD

- **BLOCKED:** review D1/D2/D3/D6 agar scope produk/sync safety tidak kontradiktif. Tidak mengubah auth policy/backend/schema secara terselubung.
- **PROPOSED:** setujui kontrak availability/coverage dan outcomes, bukan one-boolean loading; no false empty/no fake0.
- **PROPOSED:** finalisasikan coordinator lintas semua entry point, session-epoch commit fence/lock order, capture aggregate/revision, failure finalization. Bukti runtime adalah gate PhaseB sebelum integrasi UI.
- **PROPOSED:** picker selected IDs independen paging, validasi confirm, no silent prune; SQL filter/count parity dan deterministic order disetujui.
- **PROPOSED:** tetapkan no-destructive-commit untuk incomplete/unverified snapshots; jangan memberi importer halaman UI parsial.
- **PROPOSED:** preserve baseline dirty working tree, installed app/data/settings, Pencil, protected timer/notification contracts.

### 12.4 SHOULD IMPROVE

- **PROPOSED:** shared draw-phase shimmer + static reduced-motion fallback, delayed skeleton terukur, width/font adaptive placeholders.
- **PROPOSED:** profile signed-URL resolution independen, hindari re-sign pada emission history/routine yang tak relevan.
- **PROPOSED:** reactive pending counts dan privacy-safe flight/page counters.
- **PROPOSED:** benchmark81/150/1500, test remote1001+, narrow/font/locale/TalkBack dan gesture short-content.

### 12.5 SAFE TO RETAIN

- **VERIFIED:** Room sebagai sumber presentasi, reactive observers dan transactional aggregate import dengan pending/newer-local guards sebagai fondasi.
- **VERIFIED:** owner-filtered lists, historical name/category snapshot, trusted local auto-fill provenance dan completed-only volume; tetap jalankan regresi.
- **VERIFIED:** Navigation3 result channels terpisah untuk detail/edit/active picker, dirty editor guard dan active-session snapshot boundary.
- **VERIFIED:** header8dp, Home avatar/Progress profile row, no exercise appbar avatar, theme/locale system, shared modal scrim0.50, native-notification system theme.
- **PROPOSED:** durable WorkManager retry, independent catalog demand, no RemoteMediator, no remote fetch from append. Ini dipertahankan sambil menutup gap dedup yang ditemukan.

### 12.6 OUT OF SCOPE

- Implementasi apa pun pada tahap audit ini; commit/push; perubahan Room schema/dependency/asset/navigation tanpa persetujuan.
- Global history screen, infinite scroll Home/routines/Progress/Profile/date history, grid12 kategori/12bulan, editor/session task pagination.
- Timer engine/alarm dialog/settings, native notification redesign, auth password/RLS policy changes.
- Backend RPC/tombstone/CAS/migration implementation sebelum scope D6 disetujui; produksi Supabase/OEM acceptance baru tanpa otorisasi.
- Memperbaiki lint pre-existing atau menyatakan semua enam issue sebelumnya sudah closed end-to-end.

**STOP — dokumen diperbarui sesuai scope dokumentasi saja dan tetap menunggu review. Tidak ada implementasi dimulai atau keputusan terbuka yang dianggap disetujui.**
