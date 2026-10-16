# IMFIT — Rencana perbaikan rest timer lintas versi Android

Tanggal: 13 Oktober 2026.

**Status: tiga skenario inti rest timer lulus uji manual pada emulator Pixel 5 API 34 dan perangkat fisik REALME X Android 10 (API 29), berdasarkan laporan pengguna.** Timer tetap berjalan saat berpindah aplikasi, countdown notifikasi berjalan di luar IMFIT, dan alert selesai muncul saat pengguna berada di luar IMFIT. Rincian hasil ada di [bagian 10](#10-hasil-verifikasi-manual).

Kompilasi kedua flavor dan source test telah berhasil. Unit test otomatis, instrumentation test otomatis, dan lint belum dijalankan dalam sesi ini. Pengujian manual tersebut membuktikan tiga perilaku pada dua konfigurasi yang dilaporkan; atribusi root cause rekaman awal, pengukuran latency, dan skenario matriks lainnya belum dikonfirmasi.

Dasar issue dan reproduksi: [IMFIT_REST_TIMER_ISSUE_INVESTIGATION.md](IMFIT_REST_TIMER_ISSUE_INVESTIGATION.md). Scope checkout: `minSdk = 24`, `compileSdk = 37`, `targetSdk = 37`, flavor `demo` dan `production` (`app/build.gradle.kts:19–43`). Aturan platform perlu diperiksa terhadap OS aktual dan target SDK, terutama API 37; nomor SDK saja bukan bukti kompatibilitas.

## 1. Temuan audit dan batas kesimpulan

Path Kotlin pada tabel relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`. Nomor baris mengacu checkout saat audit sebelum implementasi dan dapat bergeser setelah implementasi.

| ID | File / simbol / bukti kode | Kesimpulan dan tindak lanjut |
| --- | --- | --- |
| A1 | `core/timer/RestTimerCoordinator.kt:37–38, 119–138, 178–180`; `remainingSeconds()`, `start()`, `reconcileLocked()`: deadline dari elapsed realtime, remaining di-clamp dan dibulatkan ke atas. | Mekanisme deadline sudah benar secara desain. REST-01 belum membutuhkan penulisan ulang clock; telusuri pemicu expiry dan presentasi. |
| A2 | `di/RestTimerModule.kt:23–28`; provider `@Singleton`. `core/timer/RestTimerCoordinator.kt:75, 93–116, 268–271`; `Mutex`, persist sebelum publish, `expiry()` di dalam `guarded`. | Serialisasi mutasi sudah ada dalam satu proses. Pertahankan dan perluas ke keputusan side effect; jangan menambah owner atau writer DataStore kedua. |
| A3 | `core/timer/RestTimerAlarmReceiver.kt:28–48`; `onReceive()` membaca store lalu memvalidasi di luar mutex coordinator, mencoba start FGS, fallback expiry hanya dalam catch startup. `core/service/WorkoutService.kt:45–65`; promosi foreground dapat gagal kemudian. | Precheck receiver dapat kedaluwarsa. Startup yang diterima tetapi promosi gagal tidak memicu fallback receiver. Celah kode teridentifikasi; hubungannya dengan rekaman belum terbukti. |
| A4 | `core/service/WorkoutService.kt:50–63, 107–120`; ticker memanggil reconcile/render tiap detik tanpa catch per iterasi; cache `update to remaining` dan diisi sebelum notify. | Kegagalan rendering yang lolos berpotensi menghentikan ticker; cache belum memuat identitas rest. Tambahkan recovery terkontrol dan cache hanya setelah hasil post yang diketahui. |
| A5 | `core/notification/WorkoutNotificationManager.kt:147–187`; `buildRestTimerNotification()`: judul memuat angka snapshot, `when = currentTimeMillis + remainingSeconds * 1000`, chronometer countdown. | Menjelaskan dua representasi waktu yang dapat berbeda ketika updater berhenti, bukan bukti updater berhenti karena lifecycle. Gunakan satu countdown dinamis dan deadline yang sama. |
| A6 | `core/timer/RestTimerCoordinator.kt:195–205`; `reconcileLocked()` memanggil showFinished lalu startAudio, kegagalan audio disimpan sebagai suppression. `core/timer/RestTimerAndroidRuntime.kt:176–187`; grace kartu 1.500 ms, audio membutuhkan kontrol dan Activity resumed atau FGS aktif. | Readiness sementara dan kegagalan permanen belum dipisahkan. Memindahkan expiry sebelum startup service tanpa perubahan ini dapat menekan audio terlalu dini. |
| A7 | `core/timer/RestTimerAndroidRuntime.kt:90–108, 258–260`; `schedule()`, `exactAlarmAvailable()`, `fullScreenIntentAvailable()`: schedule exact jika diizinkan, fallback best-effort, guard S/34. `app/src/main/AndroidManifest.xml:7–13, 75–82`; FGS `specialUse`, subtype, permission exact/notification/FSI. | Capability dasar tersedia. Audit jalur best-effort yang tidak otomatis mendapat pengecualian background-start FGS dan kelayakan tipe FGS API 34+. |
| A8 | `core/timer/RestTimerAndroidRuntime.kt:131–138`; `notificationsAvailable()`: channel tersedia selama importance bukan NONE. `core/notification/NotificationChannels.kt:47–54`; `createNotificationChannels()`: channel selesai HIGH tanpa sound. `core/notification/WorkoutNotificationManager.kt:250–264`; `showRestFinished()`: FSI selalu dipasang. | Pisahkan kartu tersedia, heads-up layak, dan FSI diizinkan. HIGH/notify sukses tidak membuktikan banner terlihat; verifikasi juga channel silent pada OS/OEM. |
| A9 | `presentation/ui/workout/ActiveWorkoutViewModel.kt:182–189`; `startElapsedTimeCounter()`: restLimited berdasarkan scheduling mode. `presentation/ui/workout/ActiveWorkoutScreen.kt:273–284, 300–307`; `ActiveWorkoutScreen()`: pengaturan exact/notifikasi dan FSI terpisah. | Model keterbatasan perlu membedakan permission, channel, exact alarm, FSI, serta kesiapan playback; tujuan Settings mengikuti sebabnya. |
| A10 | `core/notification/NotificationChannels.kt:13–15`; `WORKOUT_NOTIFICATION_ID = 1001`, `REST_FINISHED_NOTIFICATION_ID = 1002`. `core/service/WorkoutService.kt:113–120`, `renderNotification()`, dan `core/timer/RestTimerAndroidRuntime.kt:140–161`, `showFinished()`: dua entry presentasi. | Pertahankan ID 1001 foreground workout dan 1002 selesai, tetapi gunakan satu jalur keputusan rendering yang menolak snapshot lama. |
| A11 | `data/repository/AuthRepositoryImpl.kt:280–282`; `getLocalUserId()` menunggu auth initialization. `data/repository/WorkoutRepositoryImpl.kt:327–349`; recovery Room gagal dikonversi menjadi null. `app/src/demo/java/com/diajarkoding/imfit/data/repository/DemoWorkoutRepository.kt:70–71`; sesi fake. | Recovery receiver harus bounded. Bedakan read failure dari sesi benar-benar tidak ada. Process-death acceptance menggunakan productionDebug; demo tidak membuktikan pemulihan sesi persisten. |
| A12 | `app/src/androidTestDemo/java/com/diajarkoding/imfit/core/timer/RestTimerBackgroundTest.kt:30–58, 80–114, 129–181`; `deniedFsiFallsBackAndBlockedNotificationsStaySilentWithoutReplay()`, `repeatedTwoMinuteRestsWithScreenOffKeepDeadlineAndStopCorrectly()`, `twoMinuteRestInAnotherAppDismissAndStopDoNotReplay()`: setup permission/appops tanpa seluruh guard versi dan beberapa reconcile sebelum assertion audio. | Sesuaikan tes lintas API dan buktikan expiry/audio otomatis tanpa reconcile bantuan test. Tes existing bukan bukti kelulusan skenario rekaman. |

REST-01/02/03 tetap issue terpisah. Mulai dengan memastikan commit APK, OS/model, flavor, service aktif, jadwal alarm, serta log sebelum/sesudah deadline. Metadata recorder Android 10 bukan verifikasi OS. Layar menyala pada rekaman juga bukan bukti Doze.

## 2. Kontrak concurrency dan satu jalur rendering

1. Semua start/extend/expiry/reconcile/acknowledge/cancel, callback kesiapan, dan keputusan rendering masuk coordinator singleton. DataStore hanya ditulis melalui coordinator. Mutex ini berlaku dalam satu proses; jangan menambahkan komponen `android:process` terpisah tanpa desain store/locking lintas proses.
2. Receiver meneruskan event identity, bukan mengambil keputusan dari precheck store yang tidak terlindungi. Di dalam operasi terserialisasi: load state, cek `timerId`, `generation`, boot identity, status, validitas sesi dan deadline, kemudian commit `EXPIRED`. Alarm terlalu awal tidak boleh memaksa expiry; event lama menjadi no-op.
3. Persistensi mendahului publish dan side effect. ACK/replacement yang masuk lebih dulu membuat event expiry lama no-op. Jika expiry masuk lebih dulu, ACK berikutnya membatalkan semua pekerjaan playback/presentasi terkait dan membersihkan kartu/audio. Jika disk gagal, pertahankan penghentian langsung dan terminal pending seperti kontrak existing; jangan menjalankan efek dari state yang belum berhasil ditulis.
4. Gunakan token efek `(timerId, generation, revision)`, dengan revision berubah saat keputusan terminal/presentasi/playback berubah. Revision berbeda dari generation alarm. Callback async wajib memeriksa token, boot, status EXPIRED, dismissal, dan suppression kembali di coordinator sebelum post/audio. Cek dan efek sinkron berada dalam operasi serial yang sama, bukan cek lalu efek di luar mutex.
5. Untuk input session/repository yang diambil di luar lock agar waktu lock terbatas, validasi ulang identitas/revision setelah kembali. Snapshot lama tidak boleh diterapkan. Sinyal logout/session invalid juga harus menginvalidasi token.
6. Ticker dan receiver meminta render melalui entry yang sama. Renderer mengambil state terbaru ketika mengeksekusi, bukan menerima snapshot siap-post dari ticker. Perubahan ke selesai segera mengubah 1001 menjadi workout tanpa countdown dan mempost 1002; jangan membatalkan 1001 atau memakai 1002 untuk `startForeground()`.
7. Reconcile berulang tidak mengulang heads-up/audio untuk token yang sama. Cache hasil rendering memuat session, timer ID, generation, revision/status dan field tampilan; hasil notify gagal tidak dianggap post sukses. ACK/Stop Alert/dismissal satu jalur idempotent; Back/open workout bukan ACK.

## 3. State machine readiness audio

Angka berikut adalah parameter desain awal yang akan diverifikasi manual, bukan janji latency platform. Timeout menggunakan elapsed realtime.

| State | Syarat dan tindakan |
| --- | --- |
| `WAITING` | EXPIRED sudah tersimpan, token masih berlaku, audio diizinkan, tetapi kontrol kartu belum terlihat oleh activeNotifications atau service yang sah masih startup. Ini belum suppression permanen. |
| `READY` | Token/status/boot masih valid, tidak ACK/dismissed/suppressed, notifikasi dan kontrol tersedia, DND/pengaturan mengizinkan, owner FGS berhasil dipromosikan atau Activity resumed yang memang dapat menjadi owner audio sementara. API/target audio focus juga memenuhi syarat. Baru request focus dan mulai audio. |
| `FAILED_PERMANENT` | Startup/promosi ditolak, izin/kontrol hilang, DND/focus/playback gagal, atau batas WAITING habis. Hentikan audio/getaran, simpan suppression untuk timer itu, dan pertahankan EXPIRED senyap. Capability yang kembali tersedia tidak auto-replay. |
| `INVALIDATED` | ACK, timer diganti, boot/sesi invalid, atau cancel. Batalkan timeout/callback, hentikan playback, dan ikuti terminal state; callback terlambat no-op. |

- Pemilik timeout: satu supervisor playback pada application scope yang berkomunikasi melalui coordinator, bukan scope receiver atau composable. Service melaporkan ready/failed; tidak mengaktifkan audio langsung dari callback.
- Batas WAITING: **5.000 ms sejak request playback pertama untuk token expiry**. Reconcile tidak memperpanjang batas ini. Kartu yang baru dipost mendapat grace observasi **1.500 ms**, di dalam batas 5.000 ms; callback/post ulang tidak mereset grace.
- Supervisor memiliki paling banyak satu job readiness aktif; observasi kontrol tiap 250 ms selama WAITING serta callback service. Jangan menunggu dalam mutex. Keputusan setiap callback/timeout kembali diserialisasi.
- Timeout yang tiba setelah READY menjadi no-op. READY setelah timeout atau Stop menjadi no-op. Stop ketika service startup harus menghentikan efek segera; promosi service kemudian tidak menghidupkan audio timer lama dan tidak menghentikan workout yang masih sah.
- WAITING bukan state yang dipersistensikan sebagai playback aktif. Pada process recovery, pulihkan state timer/suppression dan validasi ulang owner/kontrol; hanya satu readiness request baru untuk timer yang masih EXPIRED dan belum suppressed/terminal. Tidak menyimpan janji callback proses lama.
- Uji khusus: expiry → WAITING → Stop Alert → service READY terlambat; hasil wajib ACKNOWLEDGED, tidak ada audio/getaran/post ulang. Tambahkan varian replacement, generation lama, timeout vs READY, kegagalan persist ACK, dan capability loss saat WAITING/READY.

## 4. Recovery receiver dan batas FGS

Urutan: event alarm → validasi atomik → commit EXPIRED → render kartu selesai/workout → request playback nonblocking. Expiry/presentasi tidak menunggu playback atau keberhasilan startup service.

- `goAsync()` selalu memiliki `try/finally` dengan `PendingResult.finish()`, termasuk pada cancellation, timeout, identitas lama, sesi invalid, dan exception. Batas internal recovery receiver **4.000 ms termasuk menunggu mutex, initialization lokal, persist, dan post**; verifikasi pada perangkat. Ini budget aplikasi, bukan definisi universal timeout broadcast Android.
- Tidak ada network recovery, polling playback, `join()` service, atau delay readiness di receiver. Supervisor mengurus playback setelah handoff; receiver menyelesaikan pekerjaan bounded lalu finish. Commit yang berhasil sebelum timeout tetap sah; cancellation tidak melakukan rollback semu. Kegagalan recovery tercatat dan dicoba lewat jalur service/reconcile berikutnya tanpa mengganti deadline.
- Gunakan validasi sesi lokal dengan hasil yang membedakan `VALID`, `ABSENT/INVALID`, dan `READ_FAILED/NOT_READY`. Timeout/read error bukan alasan menghapus workout. Hindari await auth tak berbatas di boundary receiver.
- API 31+: exact alarm untuk tindakan yang diminta pengguna dapat memenuhi pengecualian background-start FGS; **best-effort/inexact tidak otomatis memenuhi pengecualian tersebut**. Reuse service aktif; jika belum aktif, evaluasi eligibility dan tangani startup yang ditolak. Tidak perlu mencoba berulang dari ticker/receiver jika tidak eligible.
- Exception saat permintaan start dan kegagalan promosi FGS adalah dua boundary berbeda; keduanya melaporkan failure ke token readiness. State/kartu selesai tidak bergantung pada kedua tahap itu.
- API 34+: audit manifest dan merged manifest untuk `specialUse`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, subtype, dan type yang dikirim ke ServiceCompat. Validasi kesesuaian use case dan aturan target SDK; jangan mengganti ke tipe lain hanya untuk melewati pembatasan.
- FSI tidak digunakan sebagai trik untuk memperoleh eligibility FGS. Bila playback tidak bisa dimulai secara sah, tampilkan selesai senyap dengan kontrol/status yang sesuai.

## 5. Ketahanan ticker dan notifikasi

- Satu `ensureTicker()` milik service: serialisasi start/restart, jika job aktif jangan membuat job baru; jika mengganti job, cancel dan tunggu completion sebelum membuat pengganti. Shutdown membatalkan job/retry dan meneruskan CancellationException.
- Normal tick 1.000 ms. Error sementara memakai backoff **1, 2, 4, 8, 16, lalu maksimum 30 detik**, terpisah untuk read sesi dan rendering; sukses mereset backoff. Saat render backoff, expiry/reconcile tidak ikut ditunda. Hindari log spam; catat awal failure, jumlah attempt, dan recovery.
- Read sesi gagal: pertahankan state/sesi terakhir yang terverifikasi, tandai recovery terbatas, retry dengan backoff. Sesi benar-benar absent/logout/workout selesai: cleanup sesuai kontrak. Sebelum perbaikan, repository mengubah exception recovery menjadi null; implementasi `getActiveSessionForTimer()` kini meneruskan error agar ticker tidak menyamakan kedua kondisi.
- Rendering gagal: catat failure, retry render saja, tidak cancel workout atau menyatakan sesi invalid. Scheduler expiry tetap independen. Repeated start/service action tidak membuat ticker atau retry ganda.
- Judul rest menjadi label stabil, dengan satu countdown dinamis. `when` dihitung dari `epochNow + max(0, deadlineElapsedMs - elapsedNow)`; jangan memasukkan elapsed deadline mentah ke timestamp kalender. Re-render pada extend/perubahan jam. Chronometer bukan callback expiry dan harus nonaktif pada hasil render selesai.
- Channel HIGH, CATEGORY_ALARM, dan FSI yang diizinkan dipertahankan. Periksa capability sebelum memasang FSI; fallback kartu biasa/heads-up sesuai OS. Jangan menaikkan channel lama lewat create ulang atau mengganti ID untuk mengabaikan pilihan pengguna.

## 6. Matriks guard, fallback, dan assertion

Target SDK checkout adalah 37. Tiga skenario inti sudah lulus manual pada API 29 dan 34 menurut laporan pengguna (bagian 10); assertion lanjutan pada tabel belum otomatis dinyatakan lulus. Flavor, versi APK, dan target APK yang benar-benar diuji belum dicatat. Klaim aturan API 37 harus dicocokkan dengan dokumentasi/SDK dan image yang digunakan.

| OS / API | Guard dan kontrak API | Fallback | Assertion yang diperlukan |
| --- | --- | --- | --- |
| Android 7 / 24–25 | Channels hanya >=26; audio focus request modern hanya >=26; showWhenLocked/turnScreenOn methods hanya >=27, sebelumnya window flags. Countdown chronometer tersedia mulai 24. | NotificationCompat priority HIGH sebelum channel; focus legacy. | Tidak memanggil API channel/permission yang belum ada. Background expiry otomatis; kartu 1002, kontrol Stop dan lock-screen bekerja; audio terkontrol. |
| Android 8–9 / 26–28 | Start FGS melalui ContextCompat/platform >=26 dan promosi tepat waktu; channel actual importance; focus AudioFocusRequest >=26; lock-screen guard 27. | Channel rendah/blokir → kemampuan visual terbatas atau silent tanpa uncontrolled audio. | Promosi/type sesuai OS; satu ticker; permission/channel block tidak mengubah deadline; failure promosi tidak menunda commit EXPIRED. |
| Android 10–11 / 29–30 | USE_FULL_SCREEN_INTENT untuk target >=29; jangan direct-start Activity sebagai pengganti FSI. HistoricalProcessExitReasons hanya >=30. | Unlocked → presentasi notifikasi sesuai sistem; locked → FSI jika layak. API 29 tidak memakai exit API 30. | Reproduksi rekaman tanpa resume; FSI locked/unlocked teramati; startup receiver tidak membuat countdown lama muncul kembali. |
| Android 12–12L / 31–32 | canScheduleExactAlarms dan Settings exact hanya >=31; PendingIntent immutable; evaluasi exception background-start FGS sesuai sumber alarm. | Tidak ada exact → setAndAllowWhileIdle/BEST_EFFORT. FGS denied → EXPIRED/kartu selesai, audio senyap. | EXACT vs BEST_EFFORT dicatat; deny/revoke tidak crash; event lama setelah extend no-op; tidak menganggap best-effort eligible FGS. |
| Android 13 / 33 | POST_NOTIFICATIONS request/check hanya >=33; system task-manager Stop diuji terpisah dari dismiss kartu. | Izin ditolak → selesai tersimpan, kemampuan kartu/kontrol hilang, audio suppressed. | Deny/revoke menghentikan audio tanpa replay; foreground-service presence bukan bukti kartu terlihat di shade. |
| Android 14 / 34 | canUseFullScreenIntent/Settings FSI hanya >=34; audit FGS type/permission/subtype; periksa exact access pada fresh install. | FSI denied → kartu biasa/heads-up jika channel/sistem mendukung; promosi gagal → silence. | Fresh install dan upgrade; FSI allowed/denied; type/permission failure; Stop saat startup menolak callback READY terlambat. |
| Android 15–16 / 35–36 | Target >=35: audio focus membutuhkan app top atau FGS; validasi owner aktual sebelum request. Audit background-start dan PendingIntent/FSI rules target yang berlaku. | Owner tidak eligible atau focus denied → silent EXPIRED, bukan retry audio tanpa batas. | Activity background + FGS aktif vs gagal; request focus result dan audio start terpisah; callback lama tidak menyalakan audio. |
| API 37 | Periksa aturan OS/target 37 dari SDK dan dokumentasi yang tersedia sebelum menetapkan guard baru. Seluruh guard >=31/33/34/35 tetap diterapkan sesuai kontrak yang diverifikasi. | Capability tidak tersedia/startup denied → deadline tetap, selesai/kartu bila diizinkan, silent. | Build/lint target 37, runtime image 37 dan perangkat bila tersedia; laporkan aturan belum diverifikasi atau image tidak tersedia sebagai gap, bukan lulus. |

Setup instrumentation harus guard `pm grant POST_NOTIFICATIONS` pada >=33, appops exact pada >=31, FSI capability pada >=34, serta channel/timeoutAfter assertion pada >=26. Assertion FSI intent disesuaikan capability: tidak mewajibkan non-null pada denied path. Perangkat dengan DND/volume/sound disabled punya expected audio berbeda.

## 7. Instrumentasi dan ukuran keberhasilan

Setiap event menyertakan timerId/generation/revision, PID, sumber, boot identity, elapsed time/deadline, status sebelum/sesudah, scheduling mode, channel/permission aktual, dan exception. Gunakan elapsed realtime untuk latency; epoch untuk korelasi saja.

| Ukuran | Boundary dan target awal |
| --- | --- |
| Deadline → receiver masuk | `receiver_received - deadlineElapsedMs`. Target <=2.000 ms hanya untuk exact, layar menyala, OS tidak menunda, capability sesuai. Catat callback awal/terlambat; best-effort/Doze diukur terpisah. |
| Receiver → EXPIRED tersimpan | `expired_committed - receiver_received`. Target <=1.000 ms pada recovery lokal sehat; budget keseluruhan receiver 4.000 ms tetap berlaku. Jika ticker commit lebih dahulu, tandai receiver redundant dan jangan mengarang durasi commit receiver. |
| State selesai → notify | `notify_result - expired_committed`. Target <=1.000 ms dengan permission/channel tersedia; bedakan attempt, success, failure, serta active card observed. |
| Notify → alert terlihat | Timestamp observasi heads-up/FSI dikurangi notify_result; dari rekaman/perangkat, bukan log notify. Catat visible/not visible dan kondisi OS/OEM. Belum ada SLA dua detik untuk presentasi sistem. |
| Readiness → audio mulai | `audio_started - playback_ready`. Target <=1.000 ms dengan focus/sound/owner memenuhi syarat; log READY, focus result, start request, dan playback actual. |

Target di atas berdiri sendiri, tidak berarti total deadline → alert <=2 detik. Ticker expiry yang mendahului receiver harus dicatat sebagai sumber keputusan tersendiri. Selisih countdown UI dari deadline ditargetkan <=1 detik; semua latency dicatat tanpa menggeser deadline. `notify()` tanpa exception maupun kartu aktif tidak membuktikan banner, bunyi, atau getaran telah teramati.

## 8. Urutan eksekusi manual dan bukti penutupan

1. Ambil baseline reproduksi video, metadata APK/commit/flavor/model/OS/target, channel/DND/battery settings, trace dan dumpsys sebelum/sesudah deadline saat masih di luar IMFIT. Pisahkan root cause tiap REST issue.
2. Implementasikan kontrak atomik/token dan satu renderer; tambahkan receiver bounded serta pemisahan expiry dari readiness. Pertahankan DataStore, elapsed deadline, dan AlarmManager existing.
3. Implementasikan readiness supervisor/timeout, failure startup/promosi, single ticker/backoff, hasil read sesi eksplisit, capability guards dan judul countdown stabil.
4. Perluas unit/integration tests existing: race receiver/ticker/ACK/replacement, Stop saat startup, READY setelah timeout, persist failure, render exception, read exception, repeated starts dan intent lama. Verifikasi tidak ada ticker ganda atau looping failure tanpa backoff.
5. Jalankan expiry background tanpa bantuan `reconcile()` dari test. Test boleh mengamati/poll state, tetapi tidak memicu reconcile/capability refresh atau membuka IMFIT untuk meluluskan expiry/audio. Setup capability dilakukan sebelum start timer. Jalur receiver diuji dengan ticker tidak tersedia; jalur ticker diuji terpisah. Jangan injeksi expiry callback pada tes delivery AlarmManager nyata.
6. Jalankan build gate manual:

   ```bash
   ./gradlew :app:assembleDemoDebug :app:assembleProductionDebug :app:lintDemoDebug :app:lintProductionDebug :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest
   ```

7. Jalankan pengujian perangkat sesuai matriks: foreground, aplikasi lain layar menyala, locked, screen-off, Doze terpisah, exact denied/revoked, notification/channel/FSI blocked, startup/promosi FGS gagal, Stop/dismiss/extend/stale intent, perubahan jam, process death, force-stop terpisah, reboot. Gunakan productionDebug untuk process recovery; catat demo tidak memiliki recovery sesi persisten.
8. Prioritaskan Android 10 untuk reproduksi dan setidaknya satu OEM dengan pembatasan background agresif. Simpan rekaman alert saat masih di aplikasi lain. Stop/dismiss wajib menghentikan audio/getaran tanpa menghentikan workout; kartu 1001 tetap sah dan 1002 tidak repost setelah ACK.

Checklist penutupan (tiga skenario inti terverifikasi manual pada dua perangkat; pemeriksaan lanjutan dipisahkan):

- [x] REST-01: timer tetap berjalan saat berpindah aplikasi — Pixel 5 API 34 dan REALME X Android 10.
- [x] REST-02: countdown notifikasi tetap berjalan saat dilihat dari luar IMFIT — kedua perangkat.
- [x] REST-03: notifikasi/warning rest selesai muncul di luar IMFIT — kedua perangkat.
- [x] Source aplikasi kedua flavor dan source test berhasil dikompilasi; diff implementasi ditinjau.
- [x] Hasil manual, perangkat, dan batas cakupan dicatat pada kedua dokumen timer.
- [ ] Root cause tiap issue dengan file/simbol, trace dan versi APK rekaman.
- [ ] Verifikasi runtime seluruh ketentuan concurrency/recovery/readiness/FGS/ticker/latency.
- [ ] Build/lint/unit test dua flavor tercatat hasil aktualnya.
- [ ] Pengujian otomatis tanpa reconcile bantuan dan pengujian perangkat tiap kelompok API tercatat.
- [ ] Bukti latency lima boundary, countdown, alert visual, audio/getaran serta failure fallback.
- [ ] Gap perangkat/API/OEM dicatat; perbaikan tidak dinyatakan selesai hanya karena banner muncul setelah resume.

### Catatan implementasi checkout

| Area | Implementasi |
| --- | --- |
| Coordinator | `RestTimerCoordinator`: mutasi dan keputusan render/playback di mutex yang sama; `expiry(..., alarmDelivery = true)` commit serta render selesai lalu enqueue playback, tanpa menunggu playback. Token timer/generation/revision, invalidasi ACK/replacement, timeout 5.000 ms tanpa reset oleh reconcile, dan suppression durable termasuk retry jika write gagal. Cancellation memaksa reload store sebelum mutasi berikutnya; callback generation lama hanya dapat memperbaiki jadwal generation otoritatif yang belum terpasang setelah interrupted extend, tanpa mengubah deadline atau memicu expiry langsung. |
| Receiver | `RestTimerAlarmReceiver`: cek action/data/payload, teruskan identitas ke coordinator, recovery `withTimeout(4000)` mencakup menunggu lock, dan `finish()` dalam finally. Tidak lagi read/precheck DataStore atau menjadikan startup service prasyarat expiry. |
| Playback | `RestTimerAndroidRuntime`: application-scope poll readiness 250 ms; grace kartu 1.500 ms dan verifikasi extras timer/generation kartu aktif; permission/owner/DND/focus failure menjadi selesai senyap. Job polling dibatalkan tanpa membatalkan operasi suppression yang sedang commit. Callback service/audio lama kembali melalui pemeriksaan token. |
| FGS | Exact delivery user-requested hanya membuka kandidat startup yang dijaga API/mode; inexact pada API 31+ tanpa owner eligible tidak dianggap exemption. Permintaan start maupun promosi yang ditolak tercatat dan tidak menggagalkan commit EXPIRED. `specialUse`/permission existing dan guard ServiceCompat >=34 dipertahankan; eligibility perangkat belum teruji. |
| Rendering | `RestTimerAndroidRuntime.renderWorkout()` dipanggil dari coordinator; ticker memvalidasi ulang sesi sebelum render, tidak mengirim snapshot rest siap-post. 1001 tetap workout/foreground; 1002 selesai. `WorkoutNotificationManager` memakai label stabil, deadline elapsed yang dikonversi ke epoch, FSI capability guard, dan metadata identitas kartu. |
| Recovery | `WorkoutService.ensureTicker()` menjamin satu loop dan menunggu predecessor selesai; read retry dan render retry terpisah 1/2/4/8/16/30 detik. `WorkoutRepository.getActiveSessionForTimer()` membedakan absent dari read failure pada production; ViewModel tidak menganggap exception sebagai sesi selesai. |
| Lifecycle/UI | Update UI ke service melalui binder, bukan startService berulang dari collector background. Startup FGS UI yang ditolak ditangani. Capability issue mempunyai tujuan Settings berbeda untuk notification/channel/exact/FSI. `TimerSessionCleanup` secara eksplisit membersihkan kartu workout saat sesi benar-benar diakhiri; ACK rest tidak melakukannya. |
| Instrumentasi | `RestTimerTrace` mencatat boundary dengan PID, timer/generation/boot/deadline/elapsed, revision pada playback/state, notify result, owner/promosi, focus, audio/getaran, retry dan exception. Kemunculan alert di luar IMFIT sudah dikonfirmasi manual pengguna pada dua perangkat; trace latency dan rekaman uji baru belum dilampirkan. |
| Source test | Enam unit scenario baru: receiver handoff tanpa playback inline, Stop saat startup, timeout tanpa reset, replacement/render exception, terminal write gagal, dan cancellation setelah durable extend commit yang wajib reload generation sebelum event lama. Setup integration test diberi guard API, assertion FSI denied disesuaikan, dan expiry/audio tidak dibantu reconcile test. Source test belum dieksekusi. |

Kompilasi source (bukan menjalankan test):

```bash
./gradlew :app:compileDemoDebugKotlin :app:compileProductionDebugKotlin :app:compileDemoDebugUnitTestKotlin :app:compileProductionDebugUnitTestKotlin :app:compileDemoDebugAndroidTestKotlin
```

Hasil pemeriksaan kompilasi: **BUILD SUCCESSFUL**. Tidak ada task unit test atau connected test yang dieksekusi. `git diff --check` juga tidak melaporkan whitespace error.

Pengujian lanjutan mengikuti assertion matriks dan lima boundary latency yang belum dicatat di atas. Khusus receiver-only/process-death gunakan productionDebug, karena demo memiliki sesi in-memory. Untuk source instrumentation demo yang disiapkan:

```bash
./gradlew :app:connectedDemoDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.diajarkoding.imfit.core.timer.RestTimerBackgroundTest
```

Perintah ini disediakan untuk eksekusi manual dan belum dijalankan. Tes yang mengubah capability setelah expiry boleh memanggil refresh/reconcile untuk fase capability loss/recovery; fase pembuktian expiry dan audio pertama tidak boleh dibantu panggilan tersebut.

## 9. Rujukan platform untuk verifikasi manual

Fetch dokumentasi online pada sesi penyusunan mengalami timeout; rujukan berikut perlu dibuka saat implementasi, khususnya aturan target/API terbaru.

- Background-start FGS exemptions, termasuk exact alarm tindakan pengguna: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- PendingResult/finish dan batas broadcast async: https://developer.android.com/reference/android/content/BroadcastReceiver.PendingResult
- AlarmManager, exact access dan idle restrictions: https://developer.android.com/develop/background-work/services/alarms
- FGS types/specialUse: https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
- Notification permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Android 14 FSI: https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi
- Audio focus, termasuk target 35+: https://developer.android.com/media/optimize/audio-focus
- Android 17/target 37 behavior changes untuk diperiksa terhadap SDK aktual: https://developer.android.com/about/versions/17/behavior-changes-17

## 10. Hasil verifikasi manual

Tanggal pencatatan laporan: 13 Oktober 2026. Sumber hasil: konfirmasi pengguna setelah menjalankan pengujian manual. Ini adalah observasi perilaku aplikasi pada perangkat, bukan kesimpulan dari keberhasilan `notify()` atau kompilasi.

| Issue / skenario yang dilaporkan | Emulator Pixel 5 — API 34 / Android 14 | REALME X fisik — Android 10 / API 29 |
| --- | --- | --- |
| REST-01 — timer aktif, lalu berpindah-pindah aplikasi | **Lulus:** timer tetap berjalan. | **Lulus:** timer tetap berjalan. |
| REST-02 — melihat notifikasi timer dari luar IMFIT | **Lulus:** countdown di notifikasi tetap berjalan. | **Lulus:** countdown di notifikasi tetap berjalan. |
| REST-03 — rest selesai saat berada di luar IMFIT | **Lulus:** notifikasi/warning selesai muncul di luar IMFIT. | **Lulus:** notifikasi/warning selesai muncul di luar IMFIT. |

Status ketiga issue: **perbaikan perilaku inti terverifikasi manual pada kedua perangkat yang dilaporkan**. Bukti ini tidak menentukan hipotesis H1–H6 mana yang menyebabkan rekaman awal.

Metadata yang belum diberikan: tanggal/jam eksekusi tes, flavor, versi APK/commit yang dipasang, durasi rest/jumlah pengulangan, izin/channel/FSI, DND, battery settings, serta rekaman/log uji baru. Tidak ada angka latency atau jenis presentasi heads-up/FSI tertentu yang diasumsikan dari kata "notifikasi/warning".

Cakupan lanjutan yang belum dilaporkan: layar terkunci/screen-off/Doze, process death/reboot/force-stop, izin ditolak/dicabut, Stop/dismissal saat service startup, callback lama setelah extend/replacement, audio/getaran, serta versi Android lainnya. Source unit/instrumentation test yang telah disiapkan belum dilaporkan dieksekusi; hasil uji manual di atas tidak mengubah statusnya menjadi lulus otomatis.

### Riwayat perubahan per kategori

| Commit | Kategori | Perubahan |
| --- | --- | --- |
| `ae5873e` | Recovery sesi | Pisahkan kegagalan baca sesi dari sesi yang benar-benar tidak ada. |
| `64d5421` | Runtime / notifikasi | Expiry atomik, readiness playback bounded, single renderer, receiver/service recovery, retry dan trace. |
| `80daebc` | UI workout | Capability Settings, penanganan startup yang ditolak, binder updates, dan pesan EN/ID. |
| `3476874` | Source test | Coverage race/timeout/cancellation serta guard API dan expiry tanpa reconcile bantuan. |

Commit ini mencatat implementasi dan source test; identitas APK yang digunakan dalam laporan manual belum diberikan. Kedua dokumen timer kini dilacak melalui pengecualian `.gitignore` yang spesifik.

## 11. Follow-up recovery alarm — 16 Oktober 2026, branch `timer`

**Status: celah recovery diperbaiki pada source; reproduksi rekaman dan penerimaan perangkat masih perlu uji manual.** Branch `redisgn` sudah memuat hardening sebelumnya melalui merge `2694e0b`. Patch follow-up ini dibuat di `timer`, belum di-merge ke `redisgn`. Timeout receiver merupakan defect yang terverifikasi dari source, bukan root cause rekaman yang sudah dikonfirmasi log.

### Perubahan

- `RestTimerAlarmDelivery` mendaftarkan recovery durable **sebelum** receiver menunggu DataStore, mutex, atau validasi auth/sesi. Receiver tetap bounded 4.000 ms dan selalu menyelesaikan `PendingResult`.
- `RestTimerRecoveryScheduler` memakai WorkManager existing, unique per `timerId/generation` dengan kebijakan KEEP. Initial delay 10 detik, backoff exponential mulai 10 detik, maksimal tiga eksekusi worker. Ini batas minimum penundaan, **bukan SLA delivery**; Android/Doze/OEM dapat menundanya lebih lama. AlarmManager tetap pemicu deadline utama, WorkManager hanya fallback kegagalan recovery receiver.
- `expiry()` mengembalikan `HANDLED/RETRY`. Error baca/tulis yang ditangkap coordinator tidak lagi dianggap delivery sukses. Timeout, cancellation, dan transient notification-post failure tidak membatalkan recovery. Delivery sukses, event stale, terminal state, atau notifikasi diblokir pengguna menyelesaikan recovery tanpa retry berulang.
- Worker memvalidasi ulang timer/generation, boot, owner/sesi, status dan deadline melalui coordinator yang sama. Deadline tidak digeser, dan stale generation tidak menyelesaikan timer baru. Request lama yang masih antre menjadi no-op/repair jadwal otoritatif sesuai aturan existing, bukan sumber expiry palsu.
- Worker menggunakan `alarmDelivery=false`: bukan exact-alarm FGS-start exemption. Tanpa Activity/FGS yang sah, kartu selesai tetap dapat dipost tetapi audio boleh suppressed. Tidak ada replay audio yang sudah suppressed/acknowledged.
- `showFinished()` membedakan POSTED, BLOCKED, dan RETRY; kegagalan post sementara tidak langsung mengunci suppression audio sebelum retry. POSTED berarti panggilan notify diterima, **bukan bukti heads-up terlihat**.
- ViewModel branch `timer` tidak lagi menimpa countdown dengan durasi request setelah start ditolak. UI mengikuti state coordinator. Penghapusan optimistic update ini sudah ada secara terpisah pada `redisgn`; pertahankan refactor session `redisgn` ketika merge, jangan menimpanya dengan ViewModel lama.
- Trace debug menambahkan wall timestamp, API, lateness, `receiver_result`, `recovery_enqueued`, `recovery_worker_result`, `recovery_exhausted`, dan `notify_blocked`. Tidak ada permission, dependency, migrasi DataStore/Room, channel ID, atau PendingIntent identity baru untuk alarm utama.

### Verifikasi source

Satu proses verifikasi telah dimulai sebelum pengguna meminta seluruh build/test berikutnya dijalankan manual. Proses awal tersebut selesai **BUILD SUCCESSFUL**:

```bash
./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest :app:assembleDemoDebug :app:assembleProductionDebug :app:compileDemoDebugAndroidTestKotlin --console=plain
```

`RestTimerCoordinatorTest`: **38 tests, 0 failures, 0 errors** pada masing-masing flavor. Coverage tambahan: cold recovery timeout lalu retry tanpa resume, storage/session failure, expiry-write failure, post failure sesudah EXPIRED, cancellation, successful recovery cancellation, stale/replacement/extend, skip/finish/logout/reboot/user stop, notification denied, enqueue failure, dan early alarm saat 115 detik tersisa. Source instrumentation hanya dikompilasi; tidak ada connected/device test yang dijalankan dalam follow-up ini. Tidak ada build/test lanjutan sesudah arahan pengguna.

### Checklist manual sebelum merge

1. Pasang APK branch `timer` yang baru, catat flavor/API/OEM, notification/channel/FSI/exact capability, DND dan battery mode. Pastikan tidak tertukar dengan APK demo/production atau build `redisgn` lama.
2. Rest 120 detik → aplikasi lain → tetap di luar IMFIT melewati deadline → lihat shade **sebelum** membuka IMFIT. Cocokkan `alarm_scheduled`, `receiver_received`, `expired_committed`, `notify_result`, dan `activity_resumed` untuk timer/generation yang sama.
3. Recovery sementara gagal: harapkan `recovery_enqueued → receiver_timeout/receiver_result RETRY → recovery_worker_result HANDLED`. Notifikasi tidak harus menunggu resume. Ukur lateness asli; jangan menganggap delay worker pasti 10 detik.
4. Uji proses dingin pada productionDebug dan sesi Room persisten. Test demo tidak membuktikan recovery lintas process. Jangan menyamakan force-stop dengan low-memory process death; WorkManager juga tunduk pada force-stop dan kebijakan OS.
5. Ulangi extend, skip, finish, logout/account switch dan intent lama sebelum recovery berjalan. Tidak boleh ada completion atau audio sesi lama. Setelah ACK, retry tidak boleh repost/memutar ulang alert.
6. Notification/channel blocked: tidak crash, tidak retry tanpa batas, tidak ada looping tanpa kontrol. FSI denied: kartu completion tetap menjadi fallback; heads-up tetap keputusan sistem. WorkManager tidak memulihkan delivery receiver yang sama sekali tidak pernah dipanggil; kasus itu tetap membutuhkan audit AlarmManager/OS.
7. Pisahkan angka `1:55` pada task snapshot dari UI live dengan timestamp resume. Jangan menetapkan false expiry hanya dari frame pembukaan task; validasi deadline dan identitas dari trace.

### Integrasi ke `redisgn` — 16 Oktober 2026

Update `timer` sampai `92d9ce5` diintegrasikan setelah push ke remote timer. Konflik `ActiveWorkoutViewModel.completeSet()` diselesaikan dengan mempertahankan mutasi sesi, identitas set, local-auth lookup, serta error handling versi `redisgn`; countdown tetap mengikuti collector coordinator. Bagian di atas mencatat status historis saat patch masih berada di `timer`. Build/test ulang hasil integrasi dan penerimaan perangkat dilakukan manual oleh pengguna; merge tidak menandakan issue rekaman telah terverifikasi selesai.
