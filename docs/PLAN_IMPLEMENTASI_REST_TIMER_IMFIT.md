# Rencana implementasi rest timer IMFIT

Tanggal: 11 Oktober 2026. Branch tujuan: `timer`.
Status: **implementasi dilakukan di branch `timer`; build, unit test, lint,
dan pengujian emulator lolos. Kelulusan akhir pada HP/Instagram belum diverifikasi.**
Hasil eksekusi dicatat pada §15; baseline berikut adalah kondisi sebelum implementasi.

Fokus dokumen ini adalah implementasi fitur di branch `timer`.
Cakupan implementasi mengikuti rencana ini; perubahan kode tersedia di working tree.

## 1. Dasar perencanaan dan keputusan yang dikunci

Baseline di bawah terverifikasi baca langsung source sebelum implementasi
di branch `timer`, bukan hanya dari audit luar:

- `core/service/WorkoutService.kt:229-238` masih memakai `delay(1000)` dan
  pengurangan `remainingSeconds` tiap detik.
- Tidak ada `AlarmManager`, pemilik playback audio, `MediaPlayer`,
  `AudioAttributes`, `Vibrator`, `deleteIntent`, maupun `onTaskRemoved`
  (grep seluruh `app/src/main` nol hit).
- `core/notification/NotificationChannels.kt:19-41` mematikan suara dan
  getaran pada kedua channel (`setSound(null)`, `enableVibration(false)`).
- Notifikasi rest masih memakai channel workout aktif dan ID notifikasi
  tunggal `1001` (`WorkoutNotificationManager.kt:149,195`).
- UI memakai polling 500 ms (`ActiveWorkoutScreen.kt:201-212`) di atas dua
  loop 1 detik di ViewModel dan Service.
- `app/src/main/res/raw/` belum ada; belum ada aset audio di `res/`.
- `ActiveSessionEntity.rest_timer_end_time` / `rest_timer_exercise_name`
  hanya ada di entity + `MIGRATION_5_6` + test migrasi; tidak pernah
  dibaca/ditulis `WorkoutRepositoryImpl`. Kolom tersebut dead column.
- Saat baseline dibuat, folder `docs/` diabaikan Git. Aturan tersebut kini
  dihapus; dokumen ini dilacak melalui `git add` biasa bersama folder `docs/`.

Konfigurasi proyek aktual yang relevan:

| Item | Nilai |
| --- | --- |
| `compileSdk` / `targetSdk` | 37 |
| `minSdk` | 24 |
| AGP / Kotlin / KSP | 9.1.1 / 2.2.10 / 2.3.11 |
| Compose BOM / lifecycle / Room | 2026.08.00 / 2.6.1 / 2.8.4 |
| DataStore / Work / Hilt | 1.0.0 / 2.11.2 / 2.60.1 |
| Bahasa / desugar | JVM 17 + core library desugaring |
| Flavor | `demo` (`.demo`) dan `production` |
| Service kini | `WorkoutService`, `foregroundServiceType="dataSync"`, `exported=false` |
| Receiver kini | `WorkoutNotificationReceiver`, `exported=false`, 4 action `STOP/PAUSE/RESUME/SKIP` |
| Permission kini | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK` |
| Tanpa permission kini | `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED` |
| Production manifest | Hanya tambah `singleTop` + deep-link `imfit://auth` |
| Test existing | 5 file (`ExampleUnitTest`, `DemoRepositoriesTest`, `WorkoutRepositoryRoomFirstTest`, `IMFITDatabaseMigrationTest`, `ExampleInstrumentedTest`); nol test timer/notifikasi/alarm/audio |

Keputusan yang sudah ditetapkan dan tetap dipakai:

- Pause workout **diblokir selama rest aktif**; pengguna harus memilih Lewati
  terlebih dahulu. Ini sesuai perilaku existing
  (`ActiveWorkoutViewModel.kt:340-343`), bukan mengubahnya menjadi pause-ikut-rest.
- State rest disimpan dalam **DataStore baru** satu instance aplikasi.
- Tidak menambah migrasi Room; kolom rest yang belum digunakan tetap dibiarkan.
- Gunakan `imfit_rest_bright.wav` sebagai `app/src/main/res/raw/imfit_rest_done.wav`.
  Hanya WAV yang disalin; script dan LICENSE tidak ikut ke repository.
  Asal aset sintetis CC0 dicatat pada body commit.
- Konfigurasi durasi rest tetap mengikuti override sesi existing
  (`RestTimerConfigSheet` = override sesi, bukan per exercise).

## 2. Sasaran dan batas

Sasaran utama:

1. Rest mengikuti waktu nyata di layar IMFIT, halaman lain, dan background.
2. Rest selesai menampilkan alert dan bunyi berulang hingga dihentikan.
3. Swipe notifikasi selesai atau tombol Hentikan menghentikan audio/getaran.
4. Tap isi notifikasi membuka workout tanpa otomatis mengakui alert.
5. Pembukaan status bar tidak mengubah deadline atau memulai kembali timer.
6. Tidak ada dua timer/audio untuk satu rest, termasuk setelah service restart.
7. Workout dan sync tetap bekerja; timer lokal tidak bergantung Supabase/network.

Kontrak ketepatan:

> Countdown selalu dihitung dari deadline. Alert menggunakan exact alarm jika
> tersedia. Ketepatan alert saat Doze dilaporkan berdasarkan batas platform
> dan hasil pengujian; tidak dijamin untuk setiap interval dua menit.

Keputusan arsitektur yang masih terbuka: jika kebutuhan produk tetap **semua
rest dua menit wajib berbunyi tepat waktu dalam Doze**, keputusan arsitektur
tersebut belum selesai. Pengujian saja tidak menghilangkan pembatasan
platform (kuota alarm allow-while-idle, wake lock diabaikan saat Doze).

Batas platform:

- Android juga memiliki Clear all, force-stop, dan kontrol izin; klaim bahwa
  alert hanya bisa dihentikan lewat dua kontrol aplikasi tidak berlaku mutlak.
- Kartu notifikasi selesai tetap di panel sampai dihentikan. Banner heads-up
  mengikuti kebijakan sistem dan tidak dijamin terus tampil di atas layar.
- Force-stop manual berbeda dari process death normal dan berbeda dari
  Task Manager Stop; jangan menjanjikan alarm tetap bekerja setelah pengguna
  force-stop aplikasi.
- Countdown akurat tidak otomatis berarti alarm dapat dikirim tepat waktu.
  Kriteria penerimaan memisahkan tiga metrik: akurasi sisa waktu,
  keterlambatan alert, dan keberhasilan bunyi.

## 3. Perbaiki fondasi service dan action terlebih dahulu — P0

Temuan yang perlu diselesaikan sebelum menambahkan alarm/audio adalah
penggunaan `dataSync` untuk service workout, dua namespace action, dan
wake lock empat jam.

Pekerjaan:

- Audit pekerjaan aktual `WorkoutService`, lalu pilih tipe foreground service
  yang sesuai. `dataSync` ditujukan untuk operasi transfer/pengolahan data;
  tipe service harus sesuai dengan aktivitas yang benar-benar dilakukan.
- Satukan seluruh action Service, Notification Manager, dan Receiver dalam
  satu sumber konstanta, misalnya `WorkoutActions`. Kondisi kini memakai dua
  prefix berbeda (`com.diajarkoding.imfit.action.*` di Service vs
  `com.diajarkoding.imfit.ACTION_*` di Manager/Receiver); ini risiko salah
  routing dan gagal cancel PendingIntent.
- Pertahankan pemisahan makna antara **mengakhiri workout**,
  **melewati rest**, dan **menghentikan bunyi selesai**. Ketiganya tidak boleh
  saling menggantikan.
- Audit wake lock empat jam (`PARTIAL_WAKE_LOCK`, `acquire(4 jam)` di
  `onCreate`): batasi penggunaan berdasarkan kebutuhan terukur dan pastikan
  dilepas pada seluruh jalur cleanup. Wake lock bukan bypass Doze universal
  dan bukan perbaikan default untuk deep sleep.
- Terapkan aturan blokir pause di coordinator/service juga, sehingga tombol
  notifikasi Pause tidak dapat melewati aturan ViewModel yang sudah ada.

Kriteria selesai: seluruh kontrol layar dan notifikasi menjalankan action
yang sama, tanpa salah menghentikan workout atau melewati aturan pause.

## 4. Jadikan satu coordinator sebagai sumber state timer — P0

Saat ini terdapat tick di ViewModel (1 detik via `session.actualElapsedMs`
wall-clock), Service (1 detik), dan polling layar (500 ms). Ketiganya harus
membaca sumber state yang sama.

Tambahkan komponen berikut melalui Hilt:

| Komponen | Tanggung jawab |
| --- | --- |
| `RestTimerCoordinator` | Memproses command dan perubahan status |
| `RestTimerStore` | Menyimpan state dalam DataStore |
| `MonotonicClock` | Membaca waktu dan mendukung pengujian |
| `RestTimerAlarmScheduler` | Menjadwalkan serta membatalkan deadline |
| `RestTimerAudioPlayer` | Mengelola satu playback audio |

Belum ada binding untuk kelima komponen di atas; satu-satunya binding notif
kini adalah `di/NotificationModule.kt` untuk `WorkoutNotificationManager`.
Binding baru dipasang di module Hilt yang sesuai dan harus bekerja untuk
flavor `demo` dan `production` (`di/AppModule.kt` keduanya berbeda).

State minimum:

```text
timerId
generation
userId
workoutId
exerciseId / setId
exerciseName
durationMs
deadlineElapsedMs
bootIdentity
schedulingMode
status
```

Status timer dan mode penjadwalan adalah dua informasi berbeda dan tidak
boleh dicampur:

```text
timerStatus: RUNNING / EXPIRED / terminal (ACKNOWLEDGED, SKIPPED, CANCELLED)
schedulingMode: EXACT / BEST_EFFORT / UNAVAILABLE
```

Status yang diperlukan untuk v1:

```text
RUNNING
EXPIRED
ACKNOWLEDGED
SKIPPED
CANCELLED
```

Rest tidak membutuhkan status `PAUSED` pada v1 karena pause selama rest
tetap diblokir.

Aturan penjadwalan yang menggantikan invarian lama: setiap timer `RUNNING`
memiliki deadline valid dan status penjadwalan yang diketahui. Jika jadwal
tidak tersedia, coordinator mencoba pemulihan sesuai capability dan
menampilkan keterbatasannya. Jangan menandai penjadwalan berhasil sebelum
API scheduler berhasil. Hindari retry tanpa batas ketika izin tetap tidak
tersedia. `UNAVAILABLE` berarti diketahui tak terjadwal, bukan kegagalan
sementara yang boleh diasumsikan pulih sendiri.

Identitas boot (`bootIdentity`):

- Tentukan sumbernya pada perangkat target dan fallback jika tidak dapat dibaca.
- Jika identitas boot tidak dapat dibaca atau diverifikasi, jangan memakai
  deadline lama secara membabi buta: perlakukan sebagai reboot dan terapkan
  kebijakan `BOOT_UNVERIFIED` pada §6.

Idempotensi start:

- Command start membawa identitas kejadian/set.
- Event penyelesaian set yang sama tidak boleh memulai rest dua kali.
- Start duplikat tidak mereset deadline.
- Rest baru yang disengaja memperoleh identitas baru (`timerId`/`generation`).

Gunakan satu antrean command atau mekanisme serialisasi (actor/queue/Mutex
terpusat) agar start, expiry, skip, dan dismiss tidak saling bertabrakan.
Simpan state sebelum efek eksternal; sesudah setiap efek async, periksa
`generation` lagi. Jangan menahan transaksi database ketika mempersiapkan
`MediaPlayer`. Kegagalan menulis DataStore memiliki jalur error sendiri;
jangan memulai efek timer baru seolah state sudah berhasil tersimpan.

Kriteria selesai: UI dan service tidak lagi mempunyai state rest yang dapat
berbeda.

## 5. Ganti penghitungan decrement menjadi deadline — P0

Pada `WorkoutService.kt`, ganti pengurangan `remainingSeconds` dengan:

```kotlin
val deadlineMs = clock.elapsedRealtime() + durationMs

val remainingMs =
    (deadlineMs - clock.elapsedRealtime()).coerceAtLeast(0L)

val displaySeconds = (remainingMs + 999L) / 1_000L
```

`Clock` dibungkus interface agar unit test dapat mensimulasikan waktu.
Tidak perlu menyimpan `remainingSeconds` setiap detik.

Perubahan terkait:

- Loop satu detik hanya menyegarkan tampilan dan memeriksa expiry; loop
  refresh bukan sumber kebenaran. Expiry diperiksa juga saat
  restore/foreground/alarm dan melalui `reconcile()`.
- Polling 500 ms di `ActiveWorkoutScreen` diganti dengan pengamatan state
  bila memungkinkan.
- `ActiveWorkoutViewModel` tidak menjalankan countdown rest sendiri.
- Kembali ke foreground langsung menghitung ulang dari deadline.
- Durasi workout juga diaudit agar tick ViewModel dan Service tidak
  menghasilkan nilai berbeda. `calculateElapsedSeconds()` kini memakai
  `currentTimeMillis`; gunakan sumber waktu serta aturan pause yang konsisten.

Kriteria selesai: kembali pada detik 90 menunjukkan sekitar 30 detik;
kembali pada detik 130 langsung menunjukkan rest selesai. Rotasi dan navigasi
tidak menambah ticker.

## 6. Tambahkan persistensi, pemulihan, dan rekonsiliasi — P0

Gunakan DataStore baru dengan satu instance untuk aplikasi. Semua perubahan
state melewati coordinator.

Aturan penyimpanan:

- Simpan saat start, perubahan deadline, expiry, skip, acknowledge, dan cancel.
- Jangan menulis sisa detik ke DataStore setiap tick.
- Kaitkan state dengan pengguna dan sesi workout.
- Jangan memulihkan timer User A setelah User B login.
- Biarkan `rest_timer_end_time` dan `exercise_name` di Room tanpa digunakan
  oleh fitur ini. `sessionDataJson` tetap tidak menyimpan rest.
- State korup diperlakukan dengan perilaku aman seperti boot tak
  terverifikasi: batalkan jadwal lama, bersihkan alert/audio, pulihkan
  workout tanpa memulai rest lama.

Aturan pemulihan:

| Kondisi | Tindakan |
| --- | --- |
| Boot sama, deadline belum lewat | Pulihkan countdown dan jadwal |
| Boot sama, deadline sudah lewat | Pulihkan sebagai `EXPIRED` |
| Sudah `ACKNOWLEDGED` | Jangan membunyikan ulang |
| Sesi workout sudah tidak valid | Bersihkan state timer |
| Perangkat reboot | Invalidasi deadline lama; jangan memulai rest lama lagi |
| Boot tidak terverifikasi | `CANCELLED` alasan `BOOT_UNVERIFIED` (lihat di bawah) |

Kebijakan boot tidak terverifikasi (menggantikan aturan lama "anggap reboot,
akhiri rest" yang ambigu):

```text
boot tidak terverifikasi
→ CANCELLED dengan alasan BOOT_UNVERIFIED
→ batalkan jadwal lama
→ bersihkan alert/audio
→ pulihkan workout tanpa memulai rest lama
```

Jangan memetakannya menjadi `EXPIRED` biasa karena `reconcile()` dapat
memulihkan alert dan membunyikannya. Tidak perlu boot receiver untuk
membunyikan rest lama pada v1.

Operasi `reconcile()` (wajib ada; menutup celah crash antara simpan state
dan penyelesaian efek eksternal):

| State tersimpan | Efek yang harus dicocokkan |
| --- | --- |
| `RUNNING` | Jadwal aktif sesuai `schedulingMode`; playback selesai tidak berjalan |
| `EXPIRED` | Alert dipulihkan sesuai capability dan kebijakan playback §8-9 |
| `ACKNOWLEDGED` / `SKIPPED` / `CANCELLED` | Alarm, playback, dan alert lama dibersihkan |

Contoh yang ditutup: `EXPIRED` tersimpan lalu proses mati sebelum notifikasi
tampil; `ACKNOWLEDGED` tersimpan lalu penghapusan notifikasi gagal.
Pemanggilan acknowledge berulang tetap mencoba cleanup; dilarang keluar
lebih awal hanya karena status sudah `ACKNOWLEDGED`.

Kriteria selesai: process death tidak membuat countdown kembali ke durasi awal,
dan tidak ada state tersimpan yang dibiarkan tanpa efek yang cocok.

## 7. Tambahkan alarm dengan validasi background — P0

Implementasikan scheduler dan receiver khusus expiry. Jangan memakai
`WorkManager` sync sebagai pengganti alarm tepat waktu.

Mekanisme yang dikunci:

| Situasi | Mekanisme |
| --- | --- |
| Workout aktif | Exact alarm + deadline sebagai utama |
| Proses mati | Exact alarm + deadline + `reconcile()` saat recovery |
| Exact alarm tidak diizinkan | Fallback best-effort + countdown benar saat aplikasi kembali dibuka, dengan status keterbatasan jelas di UI |
| Kondisi dukung tepat waktu | Izin tersedia; perilaku normal dicatat pada kondisi/perangkat yang diuji, tanpa meniadakan batas Doze |
| Selain itu | Hanya dijamin countdown benar saat aplikasi kembali dibuka |

Pekerjaan:

- Periksa kemampuan exact alarm (`canScheduleExactAlarms()`) sebelum
  menjadwalkan.
- Gunakan `PendingIntent` explicit dan immutable dengan identitas stabil
  untuk cancel/reschedule. Extras saja tidak membedakan PendingIntent;
  pakai action/data/requestCode mencakup timer dan generation.
- Batalkan jadwal sebelumnya hanya pada jalur eksplisit: deadline berubah,
  rest baru, skip, +30, finish, logout. Teardown service dilarang
  membatalkan alarm aktif (lihat §9-10).
- Receiver memeriksa timer, generation, pengguna, sesi, status, dan deadline.
  Callback lama tidak boleh menyelesaikan timer baru; callback terlalu awal
  diabaikan/dijadwalkan ulang secara aman.
- Receiver menjalankan pemeriksaan recovery yang sama sebelum memulai
  playback (alarm lama dapat tetap berjalan setelah Task Manager Stop yang
  tanpa callback). Periksa juga korelasi penghentian pengguna §10 sebelum
  membunyikan alert sesi yang sengaja dihentikan.
- Jangan melakukan pekerjaan panjang/network di receiver; persist transisi
  dan serahkan playback ke pemilik yang memiliki jalur start valid. Tangani
  penolakan start dengan fallback, jangan biarkan exception menutup aplikasi.

Uji beberapa rest 120 detik berturut-turut saat Doze dan battery saver.
Alarm allow-while-idle (`setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`)
memiliki pembatasan frekuensi, sehingga satu alarm lulus tidak menjamin
seluruh rest dua menit tepat waktu. Foreground service sendiri juga tidak
menjamin CPU tetap aktif dalam deep sleep.

Kriteria selesai: jalur background teruji pada perangkat pengguna;
keterlambatan Doze diukur terpisah dari akurasi countdown, dan callback timer
lama tidak pernah mempengaruhi timer baru.

## 8. Pisahkan notifikasi sesi dan notifikasi selesai — P0

Saat ini rest memakai channel aktif dan ID tunggal 1001. Buat channel serta
ID berbeda untuk alert selesai. Buat channel finished baru; jangan mencoba
mengubah channel existing yang pengaturannya sudah tersimpan pada perangkat.

| Notifikasi | Perilaku |
| --- | --- |
| Sesi workout | Menunjukkan workout aktif dan countdown; mewakili service sesi |
| Rest selesai | Menunjukkan exercise dan tombol **Hentikan**; dapat di-swipe |

Konfigurasi alert selesai:

```text
setOngoing(false)
setAutoCancel(false)
setDeleteIntent(dismissPendingIntent)
addAction("Hentikan", stopPendingIntent)
category = CATEGORY_ALARM
tanpa timeout otomatis
```

Ketentuan:

- Swipe, Clear all, dan tombol Hentikan memanggil satu operasi
  `acknowledge(timerId)` yang idempotent dan selalu mencoba cleanup
  (lihat §6, §9). Cancel dari aplikasi tidak diasumsikan menjalankan
  `deleteIntent`; lakukan cleanup eksplisit sebelum cancel.
- Tap isi notifikasi membuka active workout tanpa acknowledge.
  `MainActivity.handleNotificationIntent` harus membedakan navigasi dari
  tindakan menghentikan alert. Membuka aplikasi tidak auto-acknowledge.
- Menghapus alert selesai tidak mengakhiri workout dan tidak mencatat set baru.
- Callback dismiss lama tidak memengaruhi alert timer baru (identitas
  PendingIntent/alarm cocok dengan timer aktif, termasuk saat dibatalkan).
- Saat RUNNING, notifikasi sesi dapat menampilkan countdown (chronometer)
  dan aksi Lewati/+30 tanpa bunyi tiap refresh. `setWhen` chronometer memakai
  epoch (`currentTimeMillis + remainingMs`), bukan `elapsedRealtime` langsung.
- Importance HIGH pada channel finished memberi kesempatan heads-up, tetapi
  pengguna/sistem tetap mengendalikan tampilan dan bunyi. Banner boleh hilang
  sementara kartu tetap di panel. Jangan memakai full-screen intent sebagai
  default untuk rest gym.

Gerbang audio dan kontrol penghentian:

- Sebelum looping, pastikan kontrol penghentian tersedia melalui notifikasi
  atau UI foreground.
- Jika notifikasi diblokir dan aplikasi di background saat `EXPIRED`, simpan
  `EXPIRED` dengan fallback senyap yang jelas; jangan menjalankan looping
  tanpa kontrol. Sediakan tombol Hentikan ketika aplikasi dibuka kembali.
- Perubahan izin/channel saat playback berjalan harus direkonsiliasi
  melalui `reconcile()`.
- Preferensi aplikasi adalah pemilik pilihan bunyi (channel finished sengaja
  tanpa suara; nilai suara channel yang kosong tidak dipakai untuk membedakan
  konfigurasi aplikasi dari pilihan pengguna). Preferensi tersebut tetap
  tunduk pada notifikasi/channel yang diblokir dan DND.

Kriteria selesai: alert dapat dihentikan tanpa kehilangan sesi latihan, dan
tidak pernah ada looping tanpa kontrol penghentian yang tersedia.

## 9. Tambahkan audio berulang dengan pemantauan kontrol — P0

Salin bright WAV ke `app/src/main/res/raw/imfit_rest_done.wav`, lalu
implementasikan satu pemilik playback (`RestTimerAudioPlayer`).

Pekerjaan:

- Putar `R.raw.imfit_rest_done` secara looping. Aset sudah mengandung jeda;
  jangan menambahkan loop coroutine kedua.
- Gunakan atribut audio `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION` dan kelola
  audio focus sesuai target (`targetSdk 37`). Pada target baru, permintaan
  focus memerlukan aplikasi top atau foreground service; validasi aktual.
- Untuk mode managed looping, channel finished tidak membunyikan tone
  tambahan; hindari suara notification channel dan MediaPlayer bersamaan.
- Tambahkan getaran bila diaktifkan (perlu permission `VIBRATE` bila dipakai),
  dengan satu pemilik dan dibatalkan ketika acknowledge.
- Tangani DND, volume nol, channel mute, panggilan, Bluetooth, dan musik.
  Hormati pilihan pengguna; jangan menulis volume sistem, jangan bypass DND,
  dan jangan menyalakan audio manual saat alert di-silent-kan pengguna.
- Pemantauan kontinu (menggantikan pemeriksaan sekali-sebelum-looping):
  jika playback kehilangan seluruh kontrol penghentian yang tersedia
  (contoh: audio dimulai saat UI foreground lalu pengguna membuka
  Instagram), hentikan audio/getaran dan pertahankan status `EXPIRED`
  senyap. Saat pengguna kembali membuka aplikasi, tampilkan status selesai
  dan kontrol yang sesuai; jangan otomatis membunyikan ulang hanya karena
  UI kembali tersedia.
- Tangani kegagalan playback tanpa menghilangkan status rest selesai.

Pisahkan teardown dari pembatalan timer (menggantikan aturan lama yang
mencampur keduanya):

- `onDestroy`/teardown service hanya melepas resource milik proses:
  player, vibrator, collector, dan wake lock. Teardown dilarang mengubah
  status timer dan dilarang membatalkan alarm aktif.
- Pembatalan alarm + perubahan state hanya lewat jalur eksplisit: finish,
  cancel, logout, skip, atau pergantian timer (§10).
- Saat recovery, cocokkan kembali state persisten, alarm, dan notifikasi
  melalui `reconcile()`.

Operasi acknowledge (idempotent, aman dipanggil berulang, selalu mencoba
cleanup):

```text
simpan ACKNOWLEDGED
→ hentikan dan release audio
→ hentikan getaran
→ lepaskan audio focus
→ batalkan alarm
→ hapus alert selesai
```

Kriteria selesai: tidak ada audio ganda; swipe/Hentikan segera menghentikan
bunyi dan getaran; musik dapat kembali normal setelah focus dilepas; tidak
ada timer `RUNNING` yang kehilangan jadwal akibat teardown.

## 10. Perbaiki finish, cancel, logout, dan penghentian sistem — P0

Cleanup tidak boleh hanya bergantung pada intent dari layar. Kondisi kini:
`stopWorkout()` hanya dipicu intent Screen (finish/cancel);
`finishWorkout`/`cancelWorkout` dan `logout()` tidak menghentikan service
maupun efek timer.

Tambahkan jalur cleanup bersama yang dipanggil dari alur aplikasi:

- Finish workout.
- Cancel workout.
- Logout (hentikan timer dan playback sebelum identitas pengguna serta data
  sesi dibersihkan; jangan pulihkan timer User A setelah User B login).
- Penggantian active session.
- Start rest baru (bersihkan alarm/alert lama; receiver timerId lama tidak
  boleh mengubah timer baru).

Bedakan tiga penghentian sistem (menggantikan penyamaan lama):

- Swipe kartu aplikasi di recent apps.
- Task Manager Stop: menghentikan proses dan playback tanpa callback
  aplikasi, tetapi alarm terjadwal dapat tetap berjalan. Rest lama tidak
  otomatis dibunyikan ulang setelah penghentian disengaja pengguna;
  receiver/recovery memeriksa konteks penghentian sebelum playback.
- Force-stop manual: limitasi platform, bukan process death normal.

Korelasi `REASON_USER_REQUESTED` dengan sesi (bukan cek global "pernah ada"):

- Pemeriksaan API sesuai versi Android (`ApplicationExitInfo`).
- Korelasi waktu penghentian dengan sesi/timer yang aktif saat itu.
- Pencatatan bahwa penghentian tersebut sudah diproses agar riwayat lama
  tidak membatalkan workout baru.
- Penolakan replay alert dari sesi yang sengaja dihentikan.

Tidak ada `onTaskRemoved` kini; penghentian oleh sistem tidak boleh
diasumsikan selalu memberi kesempatan menjalankan cleanup callback.

Kriteria selesai: tidak ada bunyi atau alarm sesi lama setelah finish/logout
atau penghentian disengaja pengguna.

## 11. Sesuaikan UI, string, dan dependency injection — P1

- Tambahkan string EN/ID untuk rest selesai, Hentikan, dan keterbatasan izin
  (string kini hanya punya `Rest: %s`, `Resting after`, `Skip/Stop`, nama
  channel; belum ada teks selesai/keterbatasan). Tambahkan string status
  `schedulingMode` (`EXACT`/`BEST_EFFORT`/`UNAVAILABLE`) dan fallback senyap.
- Perbaiki permintaan `POST_NOTIFICATIONS` yang sekarang hanya menghasilkan
  log (`ActiveWorkoutScreen.kt:128-150`): tambah rationale, jangan blokir
  pencatatan set saat ditolak, jangan tampilkan status seolah alert terjamin.
- Tambahkan pemeriksaan ulang capability setelah kembali dari Settings
  (terutama Alarms & reminders bila exact alarm dipakai).
- Pertahankan `RestTimerConfigSheet` sebagai override sesi.
- Foreground dan background menghasilkan event selesai yang sama. Dialog
  selesai foreground memiliki Hentikan dengan efek sama seperti notifikasi.
  Saat kembali ke aplikasi dalam kondisi `EXPIRED` senyap, tampilkan status
  selesai dan tombol Hentikan; jangan auto-replay bunyi.
- Tap notifikasi membuka active workout yang benar; periksa cold/warm start.
- Skip/+30 tersedia saat RUNNING; jangan mencampurkannya dengan acknowledge
  alert EXPIRED. Tambahan durasi setelah EXPIRED merupakan rest baru.
- Pasang binding baru melalui module Hilt yang sesuai; pastikan bekerja pada
  demo dan production.
- Hindari mengubah dependency atau versi SDK di pekerjaan ini tanpa kebutuhan
  konkret.

## 12. Pengujian dan urutan pengerjaan

Urutan implementasi:

```text
action/service
→ coordinator + deadline
→ DataStore + restore + reconcile
→ scheduler/receiver
→ notifikasi selesai
→ audio + cleanup
→ UI/izin
→ pengujian perangkat
```

Setiap commit diusahakan buildable; jangan membuat commit receiver/manifest
yang belum memiliki dependency.

Tambahkan test yang belum tersedia saat ini (fake clock dan fake effect ports).
Setiap aturan recovery, penjadwalan, dan penghentian audio wajib memiliki test:

| Pengujian | Hasil wajib |
| --- | --- |
| Majukan fake clock melewati deadline, jalankan `reconcile`/trigger expiry | `EXPIRED` tanpa bergantung jumlah tick |
| UI dan alarm selesai bersamaan | Satu event selesai |
| Alarm lama setelah rest baru | Diabaikan |
| Pause via notifikasi saat rest | Tetap diblokir |
| Process death | Deadline pulih sesuai tabel pemulihan §6 |
| Gagal tulis DataStore | Jalur error; tidak ada efek timer baru seolah tersimpan |
| State persisten rusak | Perilaku aman seperti `BOOT_UNVERIFIED` |
| Crash setelah simpan state tetapi sebelum efek selesai | `reconcile()` mencocokkan efek |
| Start duplikat dari set yang sama | Deadline tidak direset |
| Notifikasi diblokir saat audio hendak dimulai | `EXPIRED` senyap + Hentikan saat buka |
| Kontrol hilang di tengah playback | Audio/getaran berhenti; `EXPIRED` senyap |
| Acknowledge berulang | Cleanup dicoba lagi; tidak ada replay |
| Service teardown saat rest masih `RUNNING` | Timer tidak tertinggal tanpa jadwal |
| Task Manager Stop | Tidak ada auto-replay sesi yang dihentikan; diuji terpisah dari force-stop |
| User berganti | Timer lama tidak dipulihkan |
| Swipe/Stop bersamaan | Cleanup aman, tidak ada replay |
| Finish/logout | Seluruh efek timer dibersihkan |

Tidak perlu test khusus yang sekadar mengulang operasi copy aset.

| Skenario perangkat | Kriteria lulus |
| --- | --- |
| Rest 120 detik foreground | Deadline benar; satu alert selesai |
| Instagram setelah 20 detik | Alert sesuai target kondisi normal |
| Kembali detik 90 | Sisa sekitar 30 detik |
| Kembali detik 130 | EXPIRED, tidak melanjutkan countdown |
| Buka/tutup status bar | Deadline tidak berubah |
| Screen lock | Alert terukur, tanpa countdown berhenti |
| Beberapa rest berurutan saat Doze | Seluruh hasil laten dicatat; tidak boleh hanya satu rest |
| Process death normal | Recovery deadline/state, tanpa replay acknowledged |
| Task Manager Stop | Recovery sesuai §10; tanpa auto-replay; skenario terpisah |
| Force-stop manual | Tidak crash; limitation dinyatakan |
| Swipe/Stop/Clear all | Bunyi/getaran stop, alert hilang, workout tetap berjalan |
| Tap isi notifikasi | Buka workout; alert tidak auto-acknowledge |
| Izin ditolak/dicabut | Tidak crash; fallback dan status capability benar |
| DND/volume nol/channel mute | Tidak bypass pilihan pengguna |
| Musik/Bluetooth/panggilan | Audio routing/focus dan cleanup benar |
| Skip/finish/logout/rest baru | Tidak ada alarm atau suara lama |

Target awal pada perangkat normal: display/alert terlambat <= 1 detik dan
audio berhenti <= 0,5 detik setelah acknowledgement. Ini target pengujian,
bukan jaminan Android universal. Gunakan log monotonic
(timerId/generation, status sebelum/sesudah, deadline, `elapsedRealtime`,
`schedulingMode`, alarm scheduled/delivered, audio start/stop, capability)
untuk mengukur; timer pembanding manual hanya sanity check. Jangan mencatat
email/token atau data pribadi yang tidak dibutuhkan.

Kriteria penerimaan memisahkan tiga metrik: akurasi sisa waktu,
keterlambatan alert, dan keberhasilan bunyi. Jaminan alert dua menit berulang
saat Doze tetap keputusan arsitektur terbuka (§2); hasil pengujian tidak
menghapus pembatasan platform.

Matriks perangkat minimal: perangkat pengguna yang mengalami bug; emulator
sesuai `minSdk 24`/`targetSdk 37`; satu perangkat fisik OEM lain bila
tersedia. Jangan menyatakan versi yang belum diuji sudah didukung.

Perintah dari root IMFIT-MOBILE, sesuaikan task flavor yang benar
(`demo`/`production` × `debug`/`release`):

```bash
./gradlew :app:tasks --all
./gradlew :app:testDemoDebugUnitTest :app:lintDemoDebug :app:assembleDemoDebug
./gradlew :app:connectedDemoDebugAndroidTest
```

Connected test memerlukan perangkat dan konfigurasi test yang tersedia.
Jika nama task berbeda, ambil dari tasks; jangan mengarang build lulus.

Syarat penerimaan utama: kasus asli pengguna lulus — rest 120 detik tetap
mengikuti deadline saat Instagram terbuka, lalu alert berbunyi dan berhenti
melalui swipe/Hentikan tanpa muncul kembali. Build dan lint kedua flavor
harus lulus, dengan hasil pengujian fisik dicatat.

## 13. Urutan commit dan penyelesaian

1. `feat(timer): add rest finish tone asset`
2. `fix(timer): unify workout actions and audit service foundation`
3. `fix(timer): calculate rest from monotonic deadline`
4. `feat(timer): persist rest state and schedule expiry`
5. `feat(timer): add dismissible rest completion notification`
6. `feat(timer): play rest alert until acknowledged`
7. `feat(timer): add permission handling and rest controls`
8. `test(timer): cover background expiry and dismissal`

Commit aset memakai `git add -f` untuk file di `docs/` bila dokumen ikut
dikomit (lihat §1). Tidak push/publish APK secara otomatis dalam pekerjaan
perencanaan.

Definition of done:

- Decrement bukan sumber waktu di rest maupun duration workout terkait.
- UI, notifikasi dan scheduler mengonsumsi state yang sama.
- Kasus reproduksi pengguna lulus dengan rekaman sejak timer dimulai.
- Alarm timer lama, audio ganda, dan replay setelah dismiss tidak terjadi.
- Permission denial, process recovery, dan batas Doze terdokumentasi.
- Build, unit test dan lint lulus; hasil perangkat fisik dicatat.
- Diff tidak menyentuh migrasi backend, katalog exercise, atau kontrak sync.
- Pause selama rest tetap diblokir; DataStore baru dipakai tanpa migrasi Room.
- Teardown service tidak membatalkan alarm aktif; acknowledge berulang tetap
  mencoba cleanup; start duplikat tidak mereset deadline.
- Boot tak terverifikasi dan state korup memakai jalur aman
  (`BOOT_UNVERIFIED`/setara); bukan `EXPIRED` biasa.
- Jaminan alert dua menit berulang saat Doze dinyatakan terbuka (§2);
  hasil pengujian hanya berlaku pada kondisi/perangkat yang diuji.

## 14. Tambahan setelah fitur inti stabil

- Preset durasi per exercise: 60/90/120/180 detik (saat ini override sesi).
- Preview bunyi dan pilihan bunyi singkat/berulang/getaran saja.
- Overrun "selesai 00:15 lalu" dan riwayat rest aktual sebagai perubahan
  terpisah jika membutuhkan schema baru.
- Crescendo opsional dengan batas volume aplikasi, tanpa menaikkan volume
  perangkat secara otomatis.

## 15. Hasil eksekusi implementasi — 11 Oktober 2026

- Action Kotlin terpusat di `WorkoutActions`; action rest membawa identitas
  timer/generation untuk menolak callback lama.
- `WorkoutService` memakai `specialUse` dengan penjelasan subtype sesi workout
  user-started. Wake lock empat jam dihapus; teardown tidak membatalkan alarm.
- Coordinator memakai deadline monotonic, Mutex, deduplikasi set, DataStore
  versi 1, dan `reconcile()`. BOOT_COUNT menjadi identitas boot; boot yang
  tidak terverifikasi membatalkan timer. State korup dibuang dan alarm orphan
  dibersihkan. Error penyimpanan dilaporkan tanpa mengaktifkan efek baru.
- Android runtime mengelola exact/best-effort alarm, notifikasi selesai,
  satu MediaPlayer looping, audio focus, dan getaran opsional. Preferensi
  bunyi/getaran tersedia di konfigurasi rest. Hilangnya kontrol/focus
  menghentikan playback dan menyimpan suppression agar tidak auto-replay.
- Finish/cancel/logout dan pergantian sesi membersihkan timer melalui
  repository. Recovery production membaca identitas auth lokal tanpa fetch
  profile jaringan. Demo tetap memakai sesi in-memory existing; sesudah
  process death tanpa sesi valid, timer dibatalkan, bukan diputar ulang.
- UI membaca state coordinator, menyediakan Hentikan dan +30 detik, serta
  pesan capability/penyimpanan. Override sesi dan blokir pause dipertahankan.
- Room/backend/katalog dan versi dependency/SDK tidak dimigrasi atau diubah.

Validasi terakhir (seluruh task sukses):

```bash
./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest \
  :app:assembleDemoDebug :app:assembleProductionDebug \
  :app:lintDemoDebug :app:lintProductionDebug \
  :app:connectedDemoDebugAndroidTest --console=plain
```

- Unit test: 21 pada demo, 17 pada production; termasuk 16 test timer yang
  dijalankan pada setiap flavor. Tidak ada failure/error.
- Connected test: 5 test lolos pada Pixel 5 emulator Android 14 / API 34.
- `RestTimerBackgroundTest` menjalankan rest 120 detik dengan Settings sebagai
  aplikasi lain, memeriksa sisa sekitar 30 detik pada detik 90, notifikasi
  selesai, MediaPlayer aktif, dismissal melalui `deleteIntent`, serta tombol
  Hentikan pada rest berikutnya. Audio mati, notifikasi tidak muncul kembali,
  dan workout tetap valid setelah acknowledge.
- Uji tersebut bukan gesture swipe nyata atau bukti bunyi terdengar pada
  speaker fisik: dismissal mengirim `deleteIntent`, dan audio diverifikasi
  melalui `MediaPlayer.isPlaying`. Instagram tidak diuji pada emulator ini.
- Laporan: `app/build/reports/tests/`, `app/build/reports/lint-results-*.html`,
  dan `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`.

Belum lulus penerimaan akhir: HP fisik belum terhubung. Masih diperlukan
rekaman rest 120 detik → Instagram → alert selesai → swipe/Hentikan,
pengujian process death/reboot/Task Manager Stop nyata, DND/Bluetooth,
dan beberapa rest berurutan saat Doze. Hasil emulator tidak menjamin
ketepatan semua interval dua menit saat Doze; keputusan arsitektur pada §2
tetap terbuka.

## Referensi Android

- SystemClock: https://developer.android.com/reference/android/os/SystemClock
- Alarms: https://developer.android.com/develop/background-work/services/alarms
- Doze: https://developer.android.com/training/monitoring-device-state/doze-standby
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Audio focus: https://developer.android.com/media/optimize/audio-focus
- Notification: https://developer.android.com/reference/android/app/Notification
- Notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission
