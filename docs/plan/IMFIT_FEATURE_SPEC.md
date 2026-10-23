# IMFIT — Feature Specification

**ID:** IMFIT-RPS-001  
**Versi:** 1.0 — konsolidasi 23 Oktober 2026  
**Status:** TARGET PRODUK; implementasi belum selesai  
**Dokumen inti:** `IMFIT_FEATURE_SPEC.md` (APA yang harus dibangun), `IMFIT_IMPLEMENTATION_PLAN.md` (BAGAIMANA membangun), dan `IMFIT_IMPLEMENTATION_REPORT.md` (BUKTI/STATUS terbaru).

> **Aturan sumber tunggal:** Ketiga dokumen di atas cukup untuk melanjutkan pekerjaan. Dokumen lama Plan v2/v2.1, Phase A/B0/B1, ADR, dan laporan uji tetap menjadi arsip/evidence bila perlu diaudit, **bukan** file wajib yang harus terus dibaca atau diperbarui. Kontrak yang ditulis di sini tidak otomatis mengesahkan keputusan kebijakan yang masih terbuka. Status aktual dan approval selalu berada di `IMFIT_IMPLEMENTATION_REPORT.md`.

## 1. Tujuan dan hasil akhir

Bangun pengalaman refresh berbasis *Room-first stale-while-revalidate* pada enam konteks layar, local infinite scroll pada dua daftar exercise, dan enam skeleton/shimmer yang mengikuti bentuk konten. Perbaiki keamanan sinkronisasi yang menjadi prasyarat agar gesture baru tidak mengekspos data berbeda akun, menghapus pending write, atau menerapkan remote snapshot yang tidak terpercaya. Target akhir adalah **READY_FOR_USER_RELEASE_APPROVAL**, bukan deployment otomatis.

| Konteks layar | Pull-to-Refresh | Local Infinite Scroll | Content-aware skeleton | Dataset yang diminta saat remote refresh |
|---|---|---|---|---|
| Home/Beranda | Ya | Tidak | Ya, per bagian | `PROFILE + TEMPLATES + HISTORY` |
| Exercise List/gerakan per kategori | Ya | Ya | Ya, daftar | `CATALOG` |
| Exercise Picker/pemilih gerakan | Ya | Ya | Ya, daftar | `CATALOG` |
| Progress/Progres | Ya | Tidak | Ya, per bagian | `PROFILE + HISTORY` |
| Profile/Profil | Ya | Tidak | Ya, selektif | `PROFILE` |
| Workout History per tanggal | Ya | Tidak | Ya, per bagian | `HISTORY` |

**Batas scope:** Jangan membuat global-history screen, mengubah grid 12 kategori/kalender tahunan, pagination rutinitas, flow create/edit/detail rutinitas, sesi workout aktif, summary workout, autentikasi UI sebagai redesign, Navigation 3, timer/notifikasi, rumus completed-volume/trusted auto-fill, atau tema/bahasa tanpa keputusan scope baru. Kerja safety autentikasi/sinkronisasi memang prasyarat teknis, tetapi bukan izin mengubah kebijakan pengguna.

## 2. Perilaku universal yang wajib terlihat

1. Room adalah sumber presentasi; remote refresh menulis ke Room hanya setelah validasi dan melalui session fence. UI berlangganan hasil Room yang reaktif. Gesture memanggil satu coordinator bersama, **tidak** me-restart observer lokal untuk menyamarkan refresh, tidak langsung query network dari Composable.
2. Jika cache dengan owner/epoch yang benar tersedia, **konten tetap terlihat** sepanjang refresh, termasuk saat offline, gagal parsial, atau retry. Tampilkan indikator non-blocking + status aktual; jangan kembali ke shimmer/blank/angka nol.
3. Shimmer hanya untuk bagian yang belum mempunyai konten awal valid. Shell, toolbar, pencarian, pilihan tanggal, footer aksi, settings lokal, dan navigasi tetap tersedia sesuai konteks. Delay tampil awal sekitar **150 ms** (uji query 20 ms tidak flash, 250 ms mulai skeleton); **tanpa** minimum durasi paksa.
4. `Empty` sah hanya setelah local query dan cakupan data relevan terverifikasi. Cache kosong tanpa bukti completeness bukan `Empty`, dan kegagalan jaringan bukan nilai 0. Offline tanpa cache berakhir pada unavailable/error+retry, bukan shimmer tanpa akhir.
5. Dalam satu konteks (owner+epoch+query/filter+tanggal), pertahankan last valid presentation sampai invalidasi Room selesai; konteks baru tidak boleh memperlihatkan snapshot lama dengan label konteks baru.
6. Refresh manual boleh melewati *auto cooldown*, tetapi bergabung ke request dataset yang sedang berjalan. Sepuluh pull bersamaan tidak menjadi sepuluh request berurutan. Logout/account switch membatalkan publication lama; keluar layar hanya melepas waiter UI, bukan membatalkan durable pending upload.
7. Bedakan status `Success`, `PartialSuccess`, `Offline`, `NetworkFailure`, `ServerFailure`, `AuthRequired/AuthChanged`, `Cancelled`, `Blocked/Unverified`. Read success tidak sama dengan semua pending write tersinkron.
8. Pull indicator dibatasi viewport konten sesudah inset Scaffold; tidak memindahkan appbar/bottom navigation, header gap 8 dp, footer/IME, atau intercept checkbox/filter sheet/switch. Sediakan aksi refresh yang aksesibel selain gesture.

### 2.1 Empat state orthogonal

| Sumbu | Minimum state | Owner / arti |
|---|---|---|
| `LocalContent` | `Uninitialized`, `ReadingLocal`, `Available`, `VerifiedEmpty`, `Unavailable` | Per owner+epoch+section+query/tanggal; last valid data hanya same-context |
| `RemoteRefresh` | `Idle`, `Running(flightId)`, typed terminal outcome | Coordinator; terminal request lama tidak boleh menutup indikator request baru |
| `LocalPage` | `Loading/Error/NotLoading` untuk initial, refresh, append + generation | Pager lokal, terpisah dari refresh Supabase |
| `PendingWrites` | durable count, pending, retrying, blocked, failed, acknowledged | Room/outbox; tidak menjadi all-synced hanya karena refresh read selesai |

## 3. Spesifikasi per layar

### 3.1 Home
- Refresh `PROFILE + TEMPLATES + HISTORY`, mempertahankan greeting, last workout, routine cards, active badge, dan data draft. Readiness greeting, last-log, routines terpisah; resolusi avatar/signed URL tidak boleh menahan cache lainnya.
- Skeleton: hanya nama pada greeting yang unknown; last-workout heading/date+dua nilai duration/volume; 2–3 routine shells flat border dengan slot thumbnail **56 dp** dan trailing **20 dp**. Banner statis dan sync status tidak dibuat shimmer.
- Error parsial tidak mengubah cached routines menjadi empty. Stable routine ID dan scroll anchor.

### 3.2 Exercise List
- Filter kategori, subgroup, pencarian dan status aktif diterapkan pada **seluruh dataset Room**, bukan hanya page yang sudah dimuat. Scroll append lokal, footer spinner/retry lokal, urutan deterministik yang disetujui.
- Skeleton 5–7 rows sesuai viewport: minimum tinggi row **76 dp**, artwork kategori **48 dp**, title/category, description opsional multiline; search bar min **52 dp** tetap interaktif.
- Pencarian baru mengganti generation/query dengan aman; tidak memublikasikan hasil query lama sebagai query baru. Empty search result hanya ketika katalog relevan diketahui valid.

### 3.3 Exercise Picker
- Refresh katalog dan local paging **tidak** mereset pilihan. Selected exercise disimpan sebagai **set ID independen** dari page/filter/search; metadata dipastikan dari query seluruh DAO, bukan hanya loaded window.
- SQL `COUNT` mencerminkan seluruh filtered results; selected count berasal dari ID yang dipilih. Jangan memakai `LazyPagingItems.itemCount` sebagai total dataset.
- Confirm harus me-resolve seluruh selected ID, mengecek active/exclusions/owner+epoch/context di boundary akhir dan mengirim hasil **sekali** hanya jika valid; selected ID invalid ditandai eksplisit, tidak silent prune. Perubahan owner/context saat confirm membatalkan delivery.
- Skeleton row mempunyai artwork **48 dp** dan ruang checkbox **48 dp** **non-interactive**, tanpa `Role.Checkbox` palsu; sticky `RoutineFooter`, reset action dan `imePadding` tidak tertutup indikator. Jangan mengubah snapshot sesi workout aktif.

### 3.4 Progress
- Refresh `PROFILE + HISTORY`; metrik, kalender, dan selected day dihitung dari **histori Room lengkap**, tidak dari page exercise atau cache parsial. Tanggal/bulan terpilih tidak di-reset saat refresh.
- Pertahankan renderer existing **dua kartu metrik**, min tinggi **116 dp**, nilai sekitar **25 sp**, avatar **48 dp**, kalender dengan target tanggal **48 dp**; hanya value/marker yang belum diketahui memakai skeleton. Date header/weekdays tetap.
- Dua backlog produk **terpisah dari pekerjaan refresh**: `PROG-SEM-01` label *Weekly Time* menerima `monthlyMinutes`; `PROG-VIS-02` model menghitung empat metrik tetapi UI menampilkan dua kartu. Keduanya tetap OPEN sampai persetujuan perubahan produk/visual, bukan dianggap diselesaikan oleh shimmer.

### 3.5 Profile
- Refresh `PROFILE` remote → Room; **tidak** menimpa pengaturan tema/bahasa/lokal atau interaksi logout existing di luar keputusan D2.
- Skeleton avatar **72 dp**, nama/email, tiga information rows (min **76 dp**, icon **40 dp**) dengan label statis dan value-only placeholders. Preferensi/settings/logout tetap; kegagalan avatar memakai fallback tanpa menutup identitas.

### 3.6 Workout History per tanggal
- Refresh `HISTORY` dan pertahankan tanggal yang dipilih. Tampilkan **empat total harian** (duration, volume, exercises, completed sets), cards sesi, dan ringkasan gerakan existing.
- Skeleton hanya nilai total serta slot judul/badge/summaries sesi; header tanggal tetap. Tidak menambah detail weight/reps per set atau history global. Error tidak menghapus cached sessions, `VerifiedEmpty` khusus tanggal terpilih.

## 4. Kontrak skeleton/aksesibilitas/visual

- Ikuti shape asli, token training neutral surfaces/light-dark, gap header **8 dp**, gutters **20 dp**, radius/padding original, min target interaktif **48 dp**, responsif width <320/340 dp dan font scale ≥1.25–1.3.
- Animation baseline shimmer **1200 ms**, phase per section; untuk reduced motion/animator-scale 0 render **static skeleton yang tetap terlihat**, bukan transparan. Satu announcement *polite* per loading group; placeholder dekoratif bukan data palsu/interaksi.
- Konten cache tidak crossfade menjadi salinan list lain; jangan me-reset LazyListState pada invalidation same-query. Semantics refresh/load/error/no-results lokal dan bahasa ID/EN harus teruji.
- Scope initial-sync overlay hanya eligibility pada enam layar (keputusan D3); jangan mengubah modal umum, scrim, Navigation 3 atau timer.
- Visual sign-off terhadap mockup yang benar-benar disetujui (D9). Ketiadaan mockup Home/List/Picker/Progress tidak berarti desain bebas diubah: gunakan source UI baseline konservatif sampai review produk.

## 5. Spesifikasi local Infinite Scroll/Paging

- Setelah gate benchmark/dependency D4, gunakan **Room Paging 3** pada Exercise List dan Picker: `PagingSource<Int, ExerciseEntity>`/repository `Pager`/ViewModel `cachedIn`; awal yang diusulkan `pageSize=30`, `initialLoadSize=60`, `prefetchDistance=10`, placeholders false. Setelan final berdasar benchmark 81/150/1500 items, bukan angka hard-coded yang tidak diuji.
- Tidak ada `RemoteMediator`. Appending, retrying, changing search/filter/scroll **0 remote requests**; hanya manual/authorized coordinator yang mengambil katalog remote. Remote Room commit meng-invalidasi PagingSource secara alami; jangan memanggil `LazyPagingItems.refresh()` sebagai pengganti remote sync/berulang setelah tiap commit.
- Browser existing mengurut ID lexical; target konsisten kedua daftar memakai `ORDER BY id COLLATE BINARY ASC` setelah persetujuan D5 (picker sebelumnya tidak memiliki kontrak ORDER BY). Unique tie-breaker wajib.
- SQL filter/count/query parity: active, kategori, subgroup yang akurat (termasuk LEGS/CALVES/GLUTES overlap), search literal `%`, `_`, Unicode/casefold, nama duplikat, exclusions aman terhadap bind-limit; mapping demo fixture 42 tidak digunakan sebagai benchmark 150 dan tidak menjadi fallback data production.
- Traversal page complete, no missing/duplicate IDs, correct latest-generation oracle jika DB berubah; selection tetap saat append/refresh/filter, footer tidak tertutup keyboard.

## 6. Invariant sinkronisasi/safety yang wajib untuk rilis

| ID | Invariant / minimum proof |
|---|---|
| **S01** | Shared single-flight `(owner, identity epoch, dataset)` across full/master/reconnect/Worker/manual, same outcome joined callers |
| **S02** | Manual refresh joins active flight; burst/auto cooldown bounded; no duplicate serial request storm |
| **S03** | SDK identity mutation dan Room commit berada di **satu awaited fence**; A→B→A/relogin lama tidak dapat commit/ack |
| **S04** | Observer, pager, by-ID read, selection, cached view, result delivery terikat owner+epoch; tak ada data A tampil ke B |
| **S05** | Parent+children+operation+revision captured atomically, network di luar Room transaction |
| **S06** | Ack hanya exact owner+operation+durable aggregate revision, tahan same-ms, child-only edit, ABA/retry; pending baru tidak terhapus |
| **S07** | Remote ownership, graph, completeness, coherence tervalidasi sebelum authoritative apply; offset/paging remote bukan snapshot isolation otomatis |
| **S08** | Unverified remote snapshot **tidak** deactivate-missing/delete/replace children/clear flags atau cache valid |
| **S09** | Pending durable tetap terjadwal sampai ack; write-after-last-scan, cancellation, process restart tidak lost wakeup |
| **S10** | Typed outcomes, finalization, retry eligibility dan UI indicator/locks benar untuk semua terminal/error/cancel paths |
| **S11** | Append/retry/search local paging menghasilkan 0 remote requests; global metrics/count tetap full dataset |
| **S12** | Logout tidak silently menghapus pending; akun lain tidak membaca/mengunggah data lama; offline exit/retention sesuai kebijakan D2 disetujui |

### 6.1 Kontrak khusus Auth dan storage (G-STORE/G-SDK)

- Supabase Auth 3.0.3 mempunyai internal session mutation paths yang tidak tercakup wrapper login/logout. Proof fork terisolasi `sdk/auth-fence-proof` memiliki staged `AtomicSessionAuthority`, tetapi **belum** mengikat aplikasi normal.
- Recovery membutuhkan `AtomicSessionStore` berbasis capability nyata, versioned envelope, atomic conditional replace/CAS dan receipt terverifikasi; `PREPARED`/raw legacy `UserSession` **tidak** otomatis trusted. Target proposed untuk proof: exact `COMMITTED` receipt yang terbukti durable boleh menjadi recovery candidate meski call sempat throw; ambiguous/unreadable tetap `Unavailable`.
- Legacy raw session proof target `LegacyUnverified`, preserve bytes, tidak auto trust/migrate/delete/logout. **Kebijakan legacy/offline aplikasi normal dan SDK-BIND tetap keputusan terpisah.** Tidak menjanjikan complete rollback detection bila seluruh store+receipt ikut rollback; perlu keputusan backup/restore/privacy.
- Kontrak `D2-BACKUP` opsi A: auth secrets dan trust metadata tidak ikut cloud backup/device transfer; setelah restore/transfer, sesi tidak dipercaya otomatis dan perlu autentikasi baru sebelum akses data akun. Data latihan/pending tidak dihapus dan tetap terisolasi per owner. Ini tidak mengubah startup offline biasa, tidak mengesahkan semua kebijakan legacy/logout/backup data latihan, dan bukan izin SDK-BIND; approval/evidence implementasi tetap di report.
- G-STORE01–10 harus membuktikan capability/envelope, prepare failpoints, readback, exact final CAS, write-then-throw receipt resolution, cancellation/publication, multi-client/multi-process ABA, fresh recreation+filesystem/device, legacy/downgrade dan backup skew. G-SDK01–06 membuktikan semua Auth writers, actual Authorization-header/owner pairing, SDK→Room fence, error/expiry/PKCE/MFA, no stale publication dan regression.
- Jangan memakai clock, hash, userId equality, StateFlow async collector, `SharedPreferences.apply()`, atau independent app-only mutex sebagai pengganti atomic recovery/session authority. Jangan mengklaim keamanannya hanya dari proof/mock tests.

## 7. Keputusan eksplisit yang memengaruhi scope

**Keputusan dan persetujuan aktual dicatat hanya di `IMFIT_IMPLEMENTATION_REPORT.md`.** Jika belum diputuskan, agent bertanya **hanya saat boundary relevan akan dilintasi**; aktivitas aman yang independen boleh diteruskan. Tidak boleh diam-diam menetapkan kebijakan:

- `REC-AUTH-PROOF`, `REC-AUTH-COMMIT`, `LEGACY-AUTH-PROOF`: implementasi proof-only durable store/receipt, ambiguity resolution, raw legacy typed-unverified.
- `LEGACY-AUTH-NORMAL`, `SDK-BIND`: UX offline/reauth/downgrade dan substitusi patched Auth ke aplikasi normal.
- `D2`: pending-safe logout, retain/discard, backup/privacy/device transfer, session lifetime.
- `D6`: guard template CREATE/UPDATE/DELETE dan degraded sync vs atomic server protocol.
- `D3/D8`: initial overlay scope, fail-safe per-owner coverage markers tanpa schema otomatis.
- `D4/D5/D9`: cost/benefit Paging, perubahan sorting picker, final visual sign-off.
- `REMOTE-G`: disposable authorized Supabase A/B E2E. `RELEASE`: normal install/commit/push/deploy.

## 8. Acceptance / Definition of Done

- [ ] B1 SDK store/fence/revision/scheduler safety tests PASS pada demo+production, original desired assertions utuh; tidak ada unresolved P0.
- [ ] Full Phase B exit: D2/D6 disetujui dan dites, non-destructive unverified snapshot, coverage/privacy dibuktikan.
- [ ] Home, Exercise List, Picker, Progress, Profile, History: **6/6 Pull-to-Refresh**.
- [ ] List+Picker: **2/2 genuine local Infinite Scroll**, 0 network append/retry.
- [ ] Keenam context: **6/6 content-aware skeleton** dengan no-flicker, offline/cache/reduced-motion.
- [ ] Selection/count/owner/date/metrics parity serta Navigation3, theme/language, timer, active-session dan trusted prefill regression lulus.
- [ ] Unit, integration, Compose, device API24/31/34 (sesuai ketersediaan), process-death/backup dan minified release ditindaklanjuti, keterbatasan evidence ditulis jujur.
- [ ] Authorized disposable Supabase E2E dan visual sign-off diselesaikan atau status release **BLOCKED** secara eksplisit.
- [ ] Final status `READY_FOR_USER_RELEASE_APPROVAL` hanya setelah mandatory gates tervalidasi; jangan otomatis commit/push/install/deploy.

**Aturan klasifikasi:** `PASS` = benar-benar diuji pada boundary yang dinyatakan; `PARTIAL` = sebagian path; `BLOCKED_DECISION` = perlu approval; `BLOCKED_ENVIRONMENT` = device/project tidak tersedia; `FAIL_SAFETY` = assertion target gagal; `NOT_TESTED` = belum pernah dieksekusi. Historis sukses characterization unsafe **bukan** PASS safety.
