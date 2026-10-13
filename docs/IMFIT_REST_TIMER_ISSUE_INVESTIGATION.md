# IMFIT — Pemetaan issue rest timer untuk agent coding

Tanggal analisis: 13 Oktober 2026.
Sumber: Record_2026-10-13-18-50-07_382d04aec7431c27fe4cf3713e3432fd.mp4, durasi 47,016 detik.
Timestamp di bawah relatif terhadap awal video, dibulatkan sekitar satu detik. Sisa detik pada UI bisa berubah di antara frame.

**Status terkini: REST-01, REST-02, dan REST-03 lulus verifikasi perilaku inti secara manual pada emulator Pixel 5 API 34 dan perangkat fisik REALME X Android 10 (API 29), menurut laporan pengguna.** Rincian hasil dan cakupannya ada di [bagian 10](#10-adendum-hasil-uji-manual-setelah-perbaikan).

Rencana dan catatan implementasi lanjutan: [IMFIT_REST_TIMER_FIX_PLAN.md](IMFIT_REST_TIMER_FIX_PLAN.md). Dokumen tersebut menambahkan audit checkout, kontrak concurrency/playback, matriks kompatibilitas API 24–37, dan hasil uji manual terbaru. Bagian 1–9 di bawah mempertahankan bukti serta panduan investigasi rekaman awal; hasil manual perbaikan tidak menetapkan root cause rekaman tersebut.

## 1. Kesimpulan dan batas bukti

Tiga gejala berhubungan, tetapi jangan langsung diperlakukan sebagai satu root cause:

1. Tampilan rest tidak mengikuti waktu yang lewat ketika IMFIT berada di background.
2. Isi notifikasi rest tetap `Rest: 00:06`, sementara penghitung pada header notifikasi berjalan melewati nol menjadi negatif.
3. Alert selesai tidak terlihat ketika pengguna berada di aplikasi lain; banner `Rest finished` baru terlihat setelah kembali ke IMFIT.

Hipotesis prioritas: deadline mungkin sudah tersedia, tetapi transisi expiry atau side effect notifikasi/audio masih bergantung pada lifecycle/collector saat foreground. Alternatifnya, state selesai sudah berubah di background tetapi pengiriman notifikasi gagal atau tertunda. Bukti kode dan log diperlukan untuk membedakan keduanya.

Video tidak membuktikan coordinator, DataStore, AlarmManager, receiver, atau FSI sudah diimplementasikan sesuai rencana sebelumnya. Source code, diff, logcat, dan hasil build/test tidak tersedia dalam sesi analisis video awal. Bagian ini adalah analisis bukti dan panduan investigasi awal; audit implementasi dan status perbaikan dicatat pada dokumen rencana serta adendum terbaru.

## 2. Timeline bukti

| Waktu video | Yang terlihat | Arti untuk investigasi |
| --- | --- | --- |
| 00:00 | IMFIT: workout `Upper A`, durasi `00:37:18`, dialog rest sekitar `0:13`. | Baseline. Jika timer tidak diubah, perkiraan deadline sekitar detik 13 video. |
| 00:03–00:05 | Rest menurun sekitar `0:10` ke `0:08`; pengguna membuka Recent Apps. | Countdown terlihat berjalan sebelum perpindahan. |
| 00:06–00:09 | Aplikasi lain tampil di foreground. | IMFIT tidak sedang menjadi layar aktif. |
| Sekitar 00:10 | Kartu IMFIT di Recent Apps memperlihatkan rest `0:07`. | Kartu Recent Apps dapat berupa snapshot; jangan gunakan sebagai bukti state internal membeku. |
| Sekitar 00:12–00:28 | Launcher dan aplikasi lain tampil. Tidak terlihat heads-up/halaman alarm IMFIT saat melewati estimasi deadline. | Tidak ada alert visual selesai yang terlihat di luar IMFIT pada interval tersebut. |
| 00:29 | Notification shade: `Rest: 00:06`, header IMFIT `-00:16`, aksi `SKIP`, `+30 SECONDS`, `STOP`. | Isi kartu rest sudah tertinggal; header telah melewati deadline. Ini masih kartu rest aktif, bukan kartu selesai. |
| 00:30–00:35 | Isi `Rest: 00:06` tetap; header berubah `-00:17` sampai `-00:22`. | Bukti kuat adanya dua representasi waktu yang tidak sinkron. |
| Sekitar 00:39 | Kartu IMFIT di Recent Apps masih menunjukkan `0:07` dan workout `00:37:25`. | Snapshot lama; tidak membuktikan waktu aktual aplikasi pada saat itu. |
| Sekitar 00:40 | IMFIT dibuka; dialog rest tidak terlihat lagi. Workout menjadi sekitar `00:37:59`. | Saat kembali, waktu workout mencerminkan waktu yang lewat. Rest tampaknya direkonsiliasi atau diproses selesai. |
| 00:41–00:43 | Banner IMFIT `Rest finished`, `Lat Pulldown`, tombol `STOP ALERT` muncul di atas workout. | Jalur alert visual ada dan bekerja pada kondisi ini, tetapi muncul sekitar 28 detik setelah perkiraan deadline. Hubungan sebab akibat dengan resume masih harus dibuktikan lewat log. |
| 00:44–00:46 | Banner tidak terlihat lagi; workout sekitar `00:38:03`–`00:38:06`. | Workout terus berjalan. Hilangnya banner saja tidak membuktikan acknowledge atau penghentian audio. |

Estimasi deadline sekitar detik 13 juga konsisten dengan header `-00:16` pada detik 29. Estimasi ini bukan pengukuran deadline internal; durasi awal, pembulatan tampilan, dan input sebelum video tidak diketahui.

## 3. Mapping issue

| ID | Issue dan status bukti | Perilaku yang diharapkan | Area pertama yang ditelusuri |
| --- | --- | --- | --- |
| REST-01 — P1 | Pengguna melaporkan timer berhenti saat berganti aplikasi. Video membuktikan representasi rest tertinggal; belum membuktikan deadline internal berhenti. | Sisa waktu berasal dari deadline yang sama; background tidak memperpanjang rest. Saat kembali setelah deadline, state sudah selesai. | Owner timer, clock, persistensi state, lifecycle collector, reconcile, callback expiry. |
| REST-02 — P1 | Isi notifikasi `Rest: 00:06` membeku, sementara header menjadi negatif. Terbukti secara visual. Workout saat kembali mencerminkan waktu yang lewat. | Isi/header menggambarkan timer yang sama. Sesudah deadline, kartu berubah menjadi status selesai; countdown tidak dibiarkan negatif. | Builder notifikasi, `setContentText`, chronometer, sumber state, mekanisme update dan penggantian kartu. |
| REST-03 — P1 | Tidak terlihat alert visual selesai di aplikasi lain; banner muncul setelah IMFIT aktif lagi. Terbukti untuk satu skenario rekaman. | Expiry dan alert diproses tanpa perlu membuka IMFIT. Dengan izin/channel yang mendukung, heads-up/FSI tersedia sesuai keadaan perangkat. | Alarm scheduling → receiver → coordinator → expired state → notification/audio/FSI. |

P1 adalah rekomendasi triase karena fungsi utama pengingat rest gagal pada skenario background. Tidak ada bukti kehilangan data workout.

Koreksi penting: angka `-00:16` dan seterusnya di header notifikasi bukan bukti durasi latihan berjalan. Itu tampak seperti chronometer countdown yang melewati nol; konfigurasi builder perlu diperiksa. Durasi workout diketahui dari layar IMFIT sebelum dan sesudah perpindahan.

## 4. Hipotesis dan cara membuktikannya

| Prioritas | Hipotesis, belum terkonfirmasi | Bukti yang mendukung | Bukti pembeda yang dicari |
| --- | --- | --- | --- |
| H1 | Expiry/side effect mengikuti coroutine atau collector yang aktif hanya saat UI foreground. | Alert muncul segera setelah kembali; kartu rest tertinggal selama background. | Log collector berhenti pada `onStop`, lalu `expire`/`notify` hanya berjalan pada `onStart`/`onResume`. Telusuri `repeatOnLifecycle`, `LaunchedEffect`, `DisposableEffect`, scope UI dan observer `restExpired`. |
| H2 | Alarm belum dijadwalkan, dibatalkan saat pindah aplikasi, atau receiver tidak meneruskan expiry. | Tidak terlihat transisi kartu pada deadline; foreground kemudian memulihkan. | Sebelum background, apakah `dumpsys alarm` berisi jadwal untuk timer yang benar? Apakah event `receiver_received` muncul sekitar deadline? Apakah `onStop`/dispose memanggil cancel? |
| H3 | Deadline/state otoritatif benar, tetapi teks notifikasi menggunakan nilai cache/tick dari UI. | Header berjalan negatif; body tetap 6 detik. | Bandingkan `deadlineElapsedMs`, `remainingMs` coordinator, nilai yang diterima builder, dan waktu `notify()`. Jika state benar tetapi body lama, masalah rendering/update. |
| H4 | State selesai sudah tersimpan, tetapi jalur notifikasi/audio hanya dikonsumsi UI atau ada kegagalan background service. | Banner foreground membuktikan jalur presentasi dapat bekerja. | `state_committed=expired` sudah ada saat background, tetapi `notify_attempt` tidak ada/terlambat; atau ada exception saat receiver/service memproses alert. |
| H5 | Alert dikirim tepat waktu tetapi izin, channel, DND atau kebijakan OEM menghalangi presentasi. | Kondisi sistem tidak ditampilkan dalam video. | `notify_attempt` tepat waktu, active notifications berisi kartu selesai, channel importance/permission/FSI capability tidak sesuai. Bandingkan dengan kondisi setelah resume. |
| H6 | Lebih dari satu owner timer atau identitas intent/jadwal bertabrakan. | Perbedaan UI/header/body bisa berasal dari state berbeda; belum ada bukti langsung. | Cocokkan `timerId` dan versi jadwal pada DataStore, UI, notification, receiver dan callback. Uji callback lama setelah `+30 detik` atau start timer baru. |

Mulai dengan H1–H4. Jangan menyimpulkan Doze sebagai penyebab awal: rekaman menunjukkan layar menyala dan perpindahan aplikasi aktif, tanpa bukti perangkat memasuki Doze.

## 5. Urutan audit kode yang diminta

1. Temukan owner sebenarnya, semua call site start/extend/skip/acknowledge, dan semua tempat yang memanggil expiry. Jangan menambah timer kedua sebelum mengetahui alur existing.
2. Periksa rumus waktu:
   `remainingMs = max(0, deadlineElapsedMs - SystemClock.elapsedRealtime())`.
   Tick hanya menyegarkan tampilan. Jika rumus ini sudah ada, fokus berikutnya adalah expiry dan side effect; jangan menganggap penulisan ulang countdown otomatis menyelesaikan issue.
3. Audit jalur background: start → state persisten → jadwal AlarmManager → BroadcastReceiver → coordinator/reconcile → transisi expired → notification/audio. Setiap tahap harus bisa dijalankan tanpa layar workout aktif.
4. Periksa DataStore sebagai state otoritatif: identitas timer, deadline, penanda boot, status, acknowledge dan metadata alert yang memang diperlukan implementasi. Jangan mengembalikan rest aktif dari cache UI yang lebih lama.
5. Audit lifecycle. Activity/composable boleh berhenti mengamati atau merender saat background, tetapi tidak boleh menghentikan rest, membatalkan jadwal, atau menjadi satu-satunya pemicu selesai.
6. Audit notification builder dan updater: sumber `Rest: 00:06`; apakah update bergantung pada collector foreground; `setUsesChronometer`, `setChronometerCountDown`, `setWhen`, custom `RemoteViews`, notification ID dan channel ID. Chronometer yang bergerak sendiri tidak membuktikan coroutine aplikasi hidup dan tidak otomatis menjalankan expiry.
7. Jangan mencampur clock: deadline coordinator menggunakan elapsed realtime; `Notification.when` menggunakan timestamp kalender. Jika perlu `setWhen`, konversikan untuk presentasi dari sisa waktu, bukan memasukkan deadline elapsed mentah. Custom Chronometer memiliki kontrak base sendiri; cek API yang benar-benar dipakai.
8. Saat expired, ganti kartu rest aktif dengan kartu selesai dan hentikan chronometer countdown. Periksa collision dengan notifikasi workout/foreground service agar penggantian kartu tidak memutus service workout.
9. Periksa channel selesai yang benar-benar terpasang di perangkat, bukan hanya konstanta builder. Channel lama dengan importance rendah tidak otomatis menjadi HIGH karena kode membuat ulang ID yang sama.
10. Audit FSI, manifest receiver/activity, intent flags, API guards, izin notifikasi/exact alarm/FSI sesuai OS dan target SDK. Jangan membuka activity dari UI sebagai pengganti jalur FSI background.
11. Periksa identitas `PendingIntent`: action/data/component/requestCode serta extras timer. Extras saja tidak membedakan identitas PendingIntent. Pastikan callback dari timer atau versi jadwal lama tidak mengubah timer baru, termasuk setelah `+30 detik`.
12. Audit audio/getaran dan acknowledge. Stop dan dismissal harus menuju acknowledge yang sama. Back ke workout bukan acknowledge. Bila kontrol notifikasi diblokir/hilang, hentikan audio dan pertahankan status selesai senyap tanpa auto-replay.

Lokasi awal dari riwayat proyek: `app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/ActiveWorkoutScreen.kt` dan ViewModel workout terkait. Path ini berasal dari riwayat, belum diverifikasi terhadap checkout terkini. Nama coordinator/service/receiver lainnya harus ditemukan dari repository; jangan mengarang nama kelas atau nomor baris.

Pencarian awal, dijalankan dari root repository:

```bash
rg -n 'restExpired|restRemaining|restTimer|RestTimer|CountDownTimer|elapsedRealtime|reconcile' app/src
rg -n 'repeatOnLifecycle|LaunchedEffect|DisposableEffect|onStop|onResume|collectLatest' app/src
rg -n 'AlarmManager|setExact|setAndAllowWhileIdle|PendingIntent|BroadcastReceiver|BOOT_COMPLETED' app/src
rg -n 'setUsesChronometer|setChronometerCountDown|setWhen|setContentText|RemoteViews|setFullScreenIntent|CATEGORY_ALARM|IMPORTANCE_HIGH|setDeleteIntent' app/src
rg -n 'acknowledge|MediaPlayer|Ringtone|Vibrator|startForeground|stopForeground' app/src
```

## 6. Instrumentasi minimum

Tambahkan log terstruktur sementara pada boundary, bukan hanya tick UI:

- Event: `start_requested`, `state_committed`, `alarm_scheduled`, `alarm_cancelled`, `receiver_received`, `reconcile_enter`, `expiry_decided`, `notify_attempt`, `notify_result`, `audio_start`, `audio_stop`, `acknowledge`, lifecycle start/stop dan service create/destroy.
- Field bersama: `timerId`, versi/generation jadwal jika ada, process ID, sumber event, `elapsedNowMs`, `deadlineElapsedMs`, `remainingMs`, status sebelum/sesudah, boot identity yang dipakai.
- Field notifikasi: notification ID, channel ID, importance aktual, izin/capability yang relevan, nilai when/base, string rest yang dipost, flags chronometer, exception bila ada.
- Jangan menafsirkan `notify()` tanpa exception sebagai bukti banner sudah terlihat; cocokkan active notification dan observasi HP.

Ambil log dan dumpsys sebelum background, setelah estimasi deadline ketika masih di aplikasi lain, dan setelah resume. Simpan hasil per flavor agar tidak mencampur paket.

```bash
adb logcat -v threadtime
adb shell dumpsys alarm
adb shell dumpsys notification
adb shell dumpsys activity services com.diajarkoding.imfit
adb shell dumpsys package com.diajarkoding.imfit
```

Perintah dumpsys dapat memuat data aplikasi lain; batasi cuplikan yang dibagikan pada IMFIT. Untuk demo, paketnya `com.diajarkoding.imfit.demo`; produksi `com.diajarkoding.imfit`. Gunakan `productionDebug` untuk pemulihan sesi setelah proses mati sesuai kontrak sebelumnya.

Tentukan cabang berdasarkan log:

| Hasil | Kesimpulan investigasi berikutnya |
| --- | --- |
| Jadwal tidak ada sebelum background | Audit start/persist/schedule dan exception izin. |
| Jadwal ada, kemudian cancel saat lifecycle stop | Audit coupling lifecycle dan pemilik cancel. |
| Jadwal ada tetapi receiver tidak terpanggil | Audit jenis alarm, izin, identitas PendingIntent, manifest dan penundaan platform. |
| Receiver masuk tetapi status tidak expired | Audit timer ID/versi, boot validation, persistensi dan race. |
| Status expired tetapi notify tidak dipanggil | Audit side effect yang lifecycle-gated atau jalur receiver/service. |
| Notify tepat waktu, kartu selesai ada, banner tidak muncul | Audit channel/FSI/DND/OEM dan kondisi terkunci/terbuka. |
| State remaining benar tetapi body kartu lama | Audit cache dan update builder, terpisah dari logika clock. |

## 7. Reproduksi dan acceptance criteria

Reproduksi video: workout aktif → rest aktif dengan sekitar 13 detik tersisa → pindah ke aplikasi A → launcher → aplikasi B → tetap di luar IMFIT sampai lewat deadline → buka notification shade → kembali ke IMFIT. Jangan menekan Skip, Stop, atau +30 selama skenario dasar.

Catat model HP, OS aktual, target SDK, versi aplikasi/commit, flavor, permission/channel, sound/vibration settings, DND, battery optimization dan apakah service/proses masih hidup. Metadata MP4 menyebut Android 10; itu petunjuk recorder, bukan verifikasi OS perangkat/target SDK. Status bunyi dan getaran tidak dikonfirmasi oleh analisis visual ini.

| Skenario | Kriteria yang diperiksa |
| --- | --- |
| Rest 120 detik, foreground | UI menghitung dari deadline; transisi expired hanya sekali; workout tidak berhenti. |
| Rest 120 detik, berpindah aplikasi dengan layar menyala | Sisa waktu cocok dengan deadline; expiry/alert tidak perlu menunggu resume; kartu selesai menggantikan kartu rest. |
| Buka shade sebelum dan sesudah deadline | Tidak ada `Rest: 00:06` yang tertinggal atau countdown negatif setelah pemrosesan expiry. |
| Layar mati/terkunci, kemudian Doze sebagai skenario terpisah | Ukur akurasi remaining, latensi receiver/alert, serta bunyi/getaran secara terpisah. Jangan menjanjikan alert tiap dua menit pasti tepat dalam Doze. |
| Proses dihentikan tanpa force-stop, lalu dipulihkan | State pulih dari persistensi dan jadwal tetap konsisten; tidak menambah durasi, replay atau alert ganda. Force-stop diuji/dicatat terpisah. |
| FSI didukung/diizinkan; perangkat unlocked vs locked | Alert hadir sesuai kemampuan platform: heads-up saat unlocked atau halaman alarm saat locked. Halaman penuh tidak harus muncul pada setiap perpindahan aplikasi. |
| Notifikasi/channel/FSI diblokir | State tetap selesai; catat kemampuan alert yang hilang. Jika kontrol notifikasi tidak tersedia, audio dihentikan, tanpa auto-replay. |
| Stop dan dismissal kartu | Satu acknowledge idempotent menghentikan audio/getaran dan menghapus kartu; workout tetap berjalan. Swipe yang hanya menurunkan banner dibedakan dari dismissal kartu. |
| Start/acknowledge berulang, timer baru, +30 detik dan intent lama | Event lama tidak mengubah timer/versi baru; penambahan durasi memperbarui deadline dan jadwal secara konsisten. |
| Boot identity tidak terverifikasi | Rest lama dibatalkan tanpa membunyikannya kembali. |

Tentukan toleransi pengukuran countdown dan latensi alert sebelum test, dengan kondisi OS/izin tercatat. Jangan menutupi keterlambatan alert dengan mengubah deadline atau menghitung ulang rest dari saat resume.

Jalankan build, lint dan unit test kedua flavor. Sesuaikan nama task setelah memeriksa Gradle; task yang diharapkan untuk kontrak dua flavor:

```bash
./gradlew :app:assembleDemoDebug :app:assembleProductionDebug :app:lintDemoDebug :app:lintProductionDebug :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest
```

Bukti penyelesaian: root cause per issue berisi file/simbol dan alur pemicunya; diff; hasil task build/test; trace timer yang sama melintasi background/expiry/resume; serta rekaman HP yang menunjukkan alert muncul saat masih di luar IMFIT. Jangan menandai selesai hanya karena banner kembali muncul setelah aplikasi dibuka.

## 8. Kontrak yang dipertahankan

Pertahankan satu coordinator, state DataStore, deadline elapsed realtime, AlarmManager sebagai pemicu expiry dan reconcile. Jangan menghidupkan kembali AlertDialog selesai berbasis `restExpired`; dialog countdown tetap dipertahankan. FSI + channel HIGH + CATEGORY_ALARM tetap menjadi desain utama, tanpa menjadikan overlay sebagai solusi. `setOngoing(true)` bukan mekanisme untuk mempertahankan heads-up di depan layar.

Start dan acknowledge idempotent, callback lama diabaikan, boot tak terverifikasi membatalkan rest lama, Stop/dismissal tidak menghentikan workout, Back tidak menghentikan alarm, dan kontrol notifikasi yang hilang tidak menghasilkan audio tanpa kendali atau auto-replay. Tidak perlu mengubah pemilih durasi/config UI untuk memperbaiki issue background ini.

## 9. Rujukan teknis primer

- Android SystemClock: elapsedRealtime mencakup deep sleep; AlarmManager dapat memicu event ketika aplikasi tidak berjalan. https://developer.android.com/reference/android/os/SystemClock
- Android Notification.Builder: chronometer otomatis menampilkan waktu dan dapat diatur countdown; tampilan tersebut bukan callback expiry aplikasi. https://developer.android.com/reference/android/app/Notification.Builder
- Android alarms: pilihan clock, jenis wakeup, exact/inexact, permission dan penundaan platform. https://developer.android.com/develop/background-work/services/alarms
- Android urgent notifications: FSI dapat menjadi activity saat locked dan notification expanded saat unlocked; deklarasi izin bergantung target SDK. https://developer.android.com/develop/ui/compose/notifications/create-notification
- Android channels: importance aktual dan kontrol pengguna; membuat ulang channel yang sama tidak menaikkan importance lama. https://developer.android.com/develop/ui/compose/notifications/channels
- Android 14 FSI: periksa capability dan pembatasan sesuai OS/target. https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi

## 10. Adendum hasil uji manual setelah perbaikan

Tanggal pencatatan laporan: 13 Oktober 2026. Pengguna mengonfirmasi ketiga hasil berikut pada **emulator Pixel 5 API 34 (Android 14)** dan **perangkat fisik REALME X Android 10 (API 29)**:

| Issue | Observasi manual pengguna pada kedua perangkat | Status terkini |
| --- | --- | --- |
| REST-01 | Timer tetap berjalan ketika berpindah-pindah aplikasi. | **Lulus untuk perilaku inti yang dilaporkan.** |
| REST-02 | Timer/countdown notifikasi tetap berjalan ketika dilihat dari luar IMFIT. | **Lulus untuk perilaku inti yang dilaporkan.** |
| REST-03 | Notifikasi atau warning rest selesai muncul saat pengguna berada di luar IMFIT. | **Lulus untuk perilaku inti yang dilaporkan.** |

Hasil ini merupakan verifikasi manual sesudah perbaikan, bukan reinterpretasi timeline video awal. Gejala inti telah diperbaiki pada dua konfigurasi yang diuji; hubungan sebab-akibat hipotesis H1–H6 dengan rekaman awal belum dipastikan melalui trace.

Laporan belum menyertakan flavor/versi APK/commit, waktu eksekusi, durasi rest, izin/channel/FSI/DND/battery settings, rekaman/log baru, atau pengukuran latency. Jenis presentasi alert, bunyi/getaran, countdown negatif sesudah expiry, Stop/dismissal, Doze, recovery proses/reboot, dan kompatibilitas versi lainnya belum dinyatakan lulus berdasarkan tiga observasi tersebut. Unit/instrumentation otomatis dan lint juga belum dilaporkan dijalankan.

Matriks uji, implementasi yang diperbaiki, dan pemeriksaan lanjutan tersedia di [IMFIT_REST_TIMER_FIX_PLAN.md](IMFIT_REST_TIMER_FIX_PLAN.md).
