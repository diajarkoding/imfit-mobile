# IMFIT — Implementation Plan (TDD → Halaman → Cek Langsung → Perbaikan)

**ID:** IMFIT-RPS-PLAN-001 · **Versi:** 1.2 · **Diperbarui:** 24 Oktober 2026
**Dokumen inti:** `IMFIT_FEATURE_SPEC.md` = perilaku, file ini = cara kerja, `IMFIT_IMPLEMENTATION_REPORT.md` = status/keputusan/evidence terbaru.

> **Perubahan urutan yang diminta user:** utamakan fitur yang terlihat, bukan menyelesaikan seluruh fork SDK/API/safety dahulu. Urutan lama B1 → B2 → C → D → E → F **tidak lagi menjadi gerbang delivery UI**. Bukti/keputusan SDK dan risiko data tetap dipertahankan dalam laporan, bukan dianggap selesai atau dihapus.

> **Koreksi UX user yang berlaku:** jangan tambahkan tombol refresh/retry/load-more. Tarik konten ke bawah saat posisi paling atas untuk refresh manual; scroll mendekati akhir memuat batch lokal otomatis. `pageSize=10`, `initialLoadSize=10` untuk List/Picker; konfigurasi bersama produksi/demo (`prefetchDistance=2`). Kontrol existing tetap. Implementasi diperbaiki lebih dahulu, lalu ketiga dokumen ini diselaraskan; hasil aktual hanya di laporan yang sama.

## 1. Siklus kerja wajib

```text
BACA 3 DOCS + CEK REPO
  → PILIH SATU HALAMAN / PERILAKU KECIL
  → TDD: TES MINIMAL GAGAL (RED)
  → IMPLEMENTASI DI HALAMAN NYATA + TES LULUS (GREEN)
  → BUILD + CEK LANGSUNG
  → ADA ISSUE? REPRODUKSI → TES REGRESI → FIX → CEK ULANG
  → UPDATE LAPORAN YANG SAMA
  → LANJUT HALAMAN / PERILAKU BERIKUTNYA
```

1. Baca Feature Spec → Implementation Plan → Implementation Report. Periksa branch/HEAD/dirty files yang relevan; jangan reset/revert pekerjaan yang ada. Baca source yang benar-benar terkait halaman, bukan mengulang full audit SDK sebagai persiapan default.
2. Tulis acceptance test kecil untuk kontrak yang akan diimplementasikan. Jalankan dan pastikan gagal karena perilaku belum tersedia, **bukan** error compile/fixture. Jika perilaku sudah ada dan lulus, catat sebagai baseline, jangan memaksakan red palsu.
3. Implementasikan jalur utuh minimum dari UI → ViewModel → repository/Room, ditambah entrypoint refresh dataset yang diperlukan. Gunakan arsitektur dan SDK aplikasi existing; tidak membuat network call di Composable atau engine sync baru per layar.
4. Jalankan targeted test sampai green, build varian yang relevan, lalu cek halaman langsung. Jangan berhenti pada tes primitive atau menunda semua UI sampai seluruh backend/scheduler selesai.
5. Temuan konkret dibuatkan reproduksi dan regression test yang relevan; perbaiki penyebabnya tanpa melemahkan assertion, lalu ulangi kasus yang sama di layar. Jangan memperluas matriks hipotetis tanpa kaitan dengan fitur/issue aktif.
6. Catat perubahan, red/green, hasil build/cek langsung, issue dan next action di **laporan yang sama**. Spec diperbarui hanya jika perilaku berubah; plan jika urutan/gate berubah. Jangan membuat plan, ADR atau laporan baru per halaman/fase.
7. Tidak meminta approval administratif berulang untuk halaman yang sudah masuk scope. Tanyakan hanya keputusan produk/risiko/permission yang benar-benar diperlukan oleh slice tersebut.

## 2. Urutan delivery per halaman

Selesaikan satu vertical slice yang bisa dicoba, bukan semua lapisan untuk enam layar sekaligus. Loading/shimmer/error untuk halaman itu ikut dikerjakan sesuai spec; tidak menjadi proyek prasyarat terpisah.

| Urutan | Halaman / fitur | TDD awal minimum | Hasil yang harus terlihat dan dicek |
|---|---|---|---|
| 1 | **Exercise List: refresh + infinite scroll** | Refresh menargetkan CATALOG dan selesai ke state terminal; initial10/append10 tanpa duplikat/HTTP; cache bertahan saat gagal | Gesture dari posisi atas, tanpa tombol baru, indikator berhenti, append otomatis, pesan error+tarik ulang, search/filter tetap sesuai seluruh dataset |
| 2 | **Exercise Picker: refresh + infinite scroll** | Reuse kontrak katalog/paging; selected ID bertahan lintas append/refresh/filter; confirm resolve ID lengkap dan sekali | Pilih item → scroll/filter/refresh → pilihan tidak hilang; count benar, confirm valid, footer/keyboard tidak tertutup |
| 3 | **Home: refresh** | Request PROFILE+TEMPLATES+HISTORY tanpa reset cache/draft; kegagalan bagian tidak mengosongkan bagian lain | Greeting/last workout/routine cards tetap terlihat, status parsial jujur, scroll dan draft bertahan |
| 4 | **Progress: refresh** | Request PROFILE+HISTORY; tanggal/bulan bertahan, metrik memakai histori penuh | Refresh tidak mereset kalender; dua kartu existing dan tanggal tetap benar, bukan nol saat network gagal |
| 5 | **Profile: refresh** | Hanya PROFILE; tema/bahasa/preferences tidak berubah; error avatar tidak menutup identitas | Identitas terbarui, cache/fallback tersedia, settings/logout tetap dapat digunakan tanpa perubahan kebijakan |
| 6 | **History per tanggal: refresh** | HISTORY dengan konteks tanggal dipertahankan; respons usang tidak mengganti konteks baru | Empat total harian/session cards tetap sesuai tanggal; error tidak menghapus cache, tidak membuat global history baru |

Tes awal dapat berupa unit/ViewModel/repository/DAO sesuai seam source aktual, bukan wajib membuat seluruh daftar tes sebelum menulis UI. Tambahkan tes Compose/integrasi saat perilakunya membutuhkan itu atau issue konkret ditemukan. Reuse komponen yang sudah terbukti pada halaman pertama.

## 3. TDD yang proporsional

### 3.1 Kontrak awal refresh

- Satu aksi refresh mencapai repository/coordinator dataset yang tepat, bukan hanya mengganti spinner atau memulai ulang observer Room.
- Pull berulang bergabung ke request yang berjalan; success/failure/cancellation menyelesaikan indikator. Cache tetap terlihat; error bukan empty/angka nol palsu.
- Tidak ada tombol refresh/retry/load-more tambahan pada normal/loading/error/empty. Uji pull ketika di atas, bukan hanya callback/click; di tengah daftar gesture menggulir dulu. Retry dengan tarik ulang (failed local paging di-retry, remote lewat repository); custom accessibility action boleh, tidak membuat tombol visual.
- State halaman yang relevan (query/filter/selected ID/tanggal/draft) tidak di-reset. Jika respons lama datang setelah konteks berubah, hasil lama tidak ditampilkan sebagai konteks baru.

### 3.2 Kontrak awal infinite scroll

- Muat batch dari query Room/pager nyata; bukan memuat seluruh list lalu `take()` agar tampak paginated. Konfigurasi bersama `pageSize=10`, `initialLoadSize=10`, `prefetchDistance=2`, placeholders false; regression test actual Pager/differ memeriksa initial10→20 setelah append, source traversal/prepend memakai10 dan batch terakhir <10.
- Traversal tidak kehilangan/menggandakan ID; filter/count tetap mencakup dataset penuh. Append/retry/search lokal menghasilkan0 request remote.
- Initial, append, end-of-list dan error/retry terlihat benar **tanpa tombol baru**; pilihan Picker tidak bergantung pada loaded window. Tes Room nyata harus memeriksa batch10, total traversal sama oracle, tanpa ID ganda/hilang; tes source demo tidak menggantikan bukti Room.
- Cek dependency Room Paging yang kompatibel pada saat implementasi. **Size10 adalah keputusan user**, bukan angka benchmark bebas diubah. Ukur/tune81/150/1500 setelah scroll pertama bisa dicoba, tanpa mengubah batch size secara diam-diam atau menunggu seluruh benchmark sebelum menampilkan halaman.
- Pertahankan urutan baseline; perubahan sorting Picker yang mengubah perilaku produk tetap ditanyakan saat boundary itu tercapai (D5), bukan sebagai alasan menahan UI lain.

### 3.3 Cek langsung dan perbaikan issue

1. Build halaman/varian yang disentuh. Gunakan preview untuk bentuk/layout; preview tidak membuktikan gesture, network atau invalidasi Room.
2. Jalankan aplikasi/demo pada environment yang **sudah diizinkan**, atau minta user mencoba build. Izin paket diagnostik SDK tidak otomatis mengizinkan install/update/clear/uninstall aplikasi normal, interaksi browser atau perubahan device/AVD.
3. Periksa alur normal: daftar di atas → tarik ke bawah → loading → hasil/indikator berhenti, tanpa tombol baru; di tengah daftar tarik hanya scroll sebelum mencapai atas. Scroll sampai append/end dengan page10; ubah search/filter/pilihan/tanggal sesuai halaman.
4. Periksa cache/offline/error/retry dengan fixture atau environment aman. Demo sintetis tetap diberi label demo, bukan hasil Supabase production.
5. Jika ada issue, catat **langkah reproduksi → expected → actual → penyebab → tes gagal → fix → tes lulus → hasil cek ulang** pada laporan yang sama. Jika tidak ada issue, cukup catat yang benar-benar diperiksa.
6. Jika perangkat/build/runtime tidak tersedia, tandai `BLOCKED_ENVIRONMENT`/`NOT_TESTED` untuk pemeriksaan tersebut. Coding/tes lokal dan halaman aman lain boleh lanjut; jangan mengklaim cek langsung sudah PASS.

## 4. Batas safety yang langsung relevan

- Room tetap sumber presentasi; request dataset melalui repository/use-case bersama. Tambahkan koordinasi/join minimum yang diperlukan sekarang; integrasi semua Worker/reconnect/full sync tidak harus selesai sebelum gesture pertama.
- **Jangan** menghubungkan gesture ke full sync/upload atau destructive importer yang sudah diketahui dapat menghapus pending, salah ack atau mencampur akun. Telusuri jalur aktual sebelum wiring; hasil aman tidak boleh diasumsikan dari nama method.
- CATALOG dikerjakan lebih dahulu agar tidak bergantung pada write template/workout. Untuk jalur PROFILE/HISTORY/TEMPLATES yang bermasalah, batasi dataset/efek yang berisiko, perbaiki seam relevan secara terarah, atau tampilkan blocked/partial+retry yang jujur. Jangan menyatakan remote refresh selesai jika hanya UI/demo yang bekerja.
- Data pengguna/pending, Room8/revisions/migrations, timer, active session, metrics/trusted prefill, navigasi, draft, pilihan, tanggal dan tema/bahasa tidak boleh dikorbankan demi demo.
- Jika ditemukan bug akun/pending pada jalur aktif: hentikan **efek yang terdampak**, buat reproduksi/regresi, lalu perbaiki dalam izin yang ada. Tanyakan jika fix membutuhkan kebijakan/scope baru; lanjutkan halaman/komponen aman yang independen.
- Jangan otomatis memperluas pekerjaan menjadi fork SDK, SDK-BIND, perubahan schema/backend, logout/retention/privacy, backup atau seluruh scheduler. Blocker harus menjelaskan halaman/jalur yang benar-benar terdampak, bukan menahan semua UI.

## 5. Pekerjaan SDK/safety terpisah dan keputusan

Pekerjaan B1/B2 dan proof G-STORE/G-SDK sebelumnya disimpan sebagai **backlog terpisah**, bukan dibuang, ditandai PASS atau dilanjutkan otomatis. Kontrak/approval/evidence historis ada pada Implementation Report. Tidak perlu menunggu Samsung/API31, full Auth writer audit, expired recovery, lifecycle browser/provider atau remote E2E untuk mengimplementasikan local paging/UI yang independen.

| Boundary | Aturan saat benar-benar diperlukan |
|---|---|
| SDK-BIND / LEGACY-AUTH-NORMAL | Tetap keputusan terpisah sebelum mengganti SDK/DI atau perilaku offline/legacy aplikasi normal; tidak menjadi dependency umum gesture/pager |
| D2 / D2-BACKUP | Approval restore-only yang sudah ada tetap berlaku pada scope-nya, bukan semua logout/privacy. Jangan mengubah retention/purge/backup normal tanpa izin |
| D6 / remote template writes | Jangan menjalankan protokol parent→delete children→insert yang diketahui berisiko. Read refresh tidak otomatis mengesahkan write; blok jalur terdampak dan minta pilihan jika kebijakan/protokol harus berubah |
| D3/D8 | Ikuti state/coverage unknown dan baseline overlay secara konservatif. Keputusan scope overlay/coverage khusus ditanyakan hanya ketika akan diubah, tidak butuh schema baru otomatis |
| D4/D5/D9 | Cek dependency/performa saat mengerjakan paging; perubahan sorting/desain produk tetap eksplisit. Tidak melakukan redesign bebas sambil menunggu mockup; pakai baseline |
| REMOTE-G / perangkat / RELEASE | Disposable backend, akun uji, install aplikasi normal, perangkat baru, commit/push/deploy butuh izin sesuai scope. Approval historis yang sudah dipakai bukan perintah publikasi otomatis pada slice baru |

Pertanyaan terarah: **temuan konkret → halaman/jalur terdampak → opsi+rekomendasi → pekerjaan aman yang tetap bisa lanjut**. Jangan mengulang approval yang sudah diberikan atau meminta pengujian SDK yang tidak terkait halaman aktif.

## 6. Regresi dan completion

- Setiap slice: targeted red/green, build terkait dan cek langsung sesuai izin. Perubahan UI-only tidak memerlukan full SDK proof suite. Jalankan demo/production regressions jika shared repository/DAO/refresh logic berubah, dan ulangi tes/cek yang relevan dengan issue.
- Setelah halaman pertama dapat dicoba, rapikan/reuse komponen dan refactor dengan tes tetap green. Setelah keenam halaman selesai, lakukan regresi gabungan, visual ID/EN/light-dark/font/IME, selection/count/date/cache/metrics/timer dan benchmark yang relevan.
- Bedakan **IMPLEMENTED** (kode/build), **TESTED** (tes dieksekusi) dan **CHECKED_DIRECTLY** (alur runtime benar-benar dicoba). Tidak ada status otomatis PASS dari compile saja, tes primitive, fake/delay spinner atau screenshot saja.
- Fitur keseluruhan:6 refresh +2 infinite scroll +6 loading/shimmer sesuai spec, issue konkret terselesaikan, state terlindungi. Release tetap tertahan bila ada P0 jalur pengguna, bukti remote/privacy/perangkat atau approval final yang dibutuhkan belum tersedia. Jangan menyamakan fitur bisa dicoba dengan aman rilis.
- Tidak otomatis commit/push/install/deploy. Tidak ada clear/uninstall data/aplikasi normal untuk mempercepat pengujian.

## 7. Isi update laporan setiap slice

Catat pada **`IMFIT_IMPLEMENTATION_REPORT.md` yang sama**:

- Halaman/perilaku yang dikerjakan, file berubah, scope dan izin yang dipakai.
- Tes red/green, exact command, counts PASS/FAIL/SKIP dan hasil build; compile failure dibedakan dari desired assertion failure.
- Environment/varian yang benar-benar dicoba, langkah cek langsung, expected/actual; keterbatasan demo/fixture/preview dinyatakan.
- Issue case-by-case, reproduksi/regresi/fix/cek ulang; hasil belum diuji tidak menjadi PASS.
- Progres per halaman, blocker yang hanya berlaku pada jalur terkait, dan **satu next action fitur**. Histori SDK bukan next action default.

**Next action awal:** buat TDD kecil Exercise List (refresh CATALOG, append lokal tanpa duplikasi/HTTP, cache saat error), implementasikan halaman utuh, build dan cek langsung; perbaiki issue yang benar-benar muncul, lalu lanjut Exercise Picker.
