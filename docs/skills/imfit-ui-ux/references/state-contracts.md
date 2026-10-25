# Kontrak state, navigasi, dan data

Baseline source `redisgn` @ `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`.
Path Kotlin pendek relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`.

## 1. Ownership: pertahankan batas ini

| Layer | Tanggung jawab UI/UX | Jangan dipindahkan ke composable |
| --- | --- | --- |
| Theme/resources | Palette, typography, locale copy, aset | Duplikasi global font/warna sebagai screen-local theme |
| Screen/content | Render state, local overlay/focus, kirim callback | Auth, query DB, sync, persistence atau timer authoritative |
| ViewModel | Validasi input, state operasi, job/observer, intent repository | Mutasi state langsung dari komponen visual |
| Repository/Room | State lokal tersimpan, transaksi/mutasi sesi, observer | Network-first refresh untuk membuat UI terasa realtime |
| Navigation graph | Typed route, backstack, hasil picker, deep-link consumption | Screen membuat graph baru saat theme/language berubah |
| Sync/timer runtime | Background operations, deadline/recovery dan ownership | Flag UI yang mengganti keberhasilan sync/expiry aktual |

Konvensi dominan ialah data class + private `MutableStateFlow` dan public read-only flow;
screen `collectAsStateWithLifecycle`, aksi eksplisit, dan effect navigasi dengan key hasil.
Bukan satu sealed `UiState` universal: jangan melakukan migrasi arsitektur state seluruh
repo dalam redesign visual. `SavedStateHandle` yang ada tidak berarti semua input tersimpan.

## 2. Matriks render per jenis kegagalan

| Kondisi | Kontrak presentasi | Yang harus tetap tersedia |
| --- | --- | --- |
| First load | Shimmer atau `RoutineLoading` mengikuti keluarga layar | Identitas layar/back yang aman |
| Legitimate empty | Pesan konteks + aksi yang valid | Bukan error jaringan; angka nol dari data sah |
| Query/filter kosong | No-results + clear filters bila relevan | Query, filter dan selected IDs lain |
| Load failure | Error lokal + retry ke loader terkait | Jangan mengubah kegagalan baca menjadi “tidak ada data” |
| Validation failure | Inline field/config error, error semantics | Semua input, fokus/IME yang relevan |
| Operation failure | Strip/callout dekat form/dialog dan retry bila ada | Draft; tidak navigate ke sukses |
| Submitting | Busy label/spinner, conflicting actions disabled | Konteks pekerjaan dan feedback aksesibel |
| Success | Navigate/close dari outcome; konsumsi result | ID sumber dan local observers |
| User cancel/back | Tutup overlay atau kembali sesuai owner | Jangan menerapkan filter draft / menyimpan mutation terselubung |

Ini checklist kualitas; beberapa layar legacy belum memenuhi semua baris. Untuk menutup
gap perlu scope yang disetujui, bukan mendokumentasikan state yang belum ada sebagai PASS.

## 3. Tiga kontrak operasi penting

### Auth: validation != operation error != registration outcome

- Login memakai field error dan operation resource terpisah. Register menambah foto/date overlay.
- Keep field values saat request error; editing membersihkan error field yang terkait.
- Jangan mengubah password rules, auth enum, email verification atau session lifecycle demi UI.
- `RegisterResult.CheckEmail` harus masuk confirmation, bukan Main. Deep-link auth ditangani
  `MainActivity.kt`/`presentation/navigation/NavGraph.kt` dan di-consume sekali.
- Never log credentials/PII sebagai metode debugging UI; source logging existing bukan izin
  menambahkan logging serupa. Uji pakai fixture, bukan akun production.

### Routine: create, draft edit dan direct add berbeda

```text
Home dialog valid name
  → createTemplate(empty exercises) → persisted local ID → WorkoutDetail

EditWorkout draft + picker result
  → addExercisesByIds(draft only) → valid config/name → explicit saveChanges
  → repository result → isSaved → back

WorkoutDetail + picker result
  → latest template + dedup IDs → repository update → observer refresh
```

- Pertahankan `isCreating/isSaving/isAdding/isUpdating/isDeleting` dan guards yang sesuai.
- Jangan menunggu cloud sync untuk menampilkan nama routine yang sudah berhasil disimpan lokal.
- Tidak boleh mengubah histori selesai saat template berubah; template bukan active session.
- Existing `CreateTemplateScreen` memerlukan nonempty selection untuk Save; Home dialog
  menerima routine kosong. Ini dua kontrak berbeda, bukan alasan menyatukan aturan diam-diam.

### Picker: view/filter state != selection state

- Search/category memengaruhi `filteredExercises`, bukan menghapus `selectedExercises`.
- Toggle, dedup, exclusion dan list key menggunakan ID.
- Full filter sheet menyimpan draft sampai Apply. Clear filter != Reset selection.
- Graph mempunyai `exerciseSelectionResult`, `editExerciseSelectionResult`, dan
  `activeSelectionResult` terpisah; active juga punya session ID.
- Pertahankan `onSelectedExercisesConsumed`; jangan replay hasil saat recomposition/resume.
- Explicit exclusions dari edit harus mengikuti draft terbaru termasuk gerakan yang dihapus
  atau ditambahkan sebelum Save, bukan snapshot persisted yang ketinggalan.

## 4. Local-first dan sync

Source: `data/repository/WorkoutRepositoryImpl.kt`, `domain/repository/WorkoutRepository.kt`,
`data/sync/SyncState.kt`, `presentation/ui/home/HomeViewModel.kt`,
`presentation/ui/progress/ProgressViewModel.kt`, `presentation/components/common/SyncProgressDialog.kt`.

- Home meng-observe templates/last workout; Detail template; Progres workout logs. Jangan
  mengganti observer dengan reload sekali atau API remote per recomposition.
- UI memisahkan local save sukses dari pending upload. Pending/IDLE, SYNCING, SYNCED,
  OFFLINE dan FAILED bukan variasi loading yang sama.
- Jika `SYNCED` masih punya pendingCount > 0, helper Home memperlakukannya sebagai pending
  IDLE. Jangan menampilkan klaim “semua tersinkron” dari status saja.
- Main memasang initial-sync scrim di atas seluruh shell saat `isInitialSync`; jangan
  menduplikasi overlay dalam masing-masing tab. Tampilan overlay bukan bukti touch/back
  benar-benar terblokir: source tidak memakai Dialog/pointer interception eksplisit.
- Jangan memperbaiki protokol sync/retry atau permission sebagai efek samping redesign.

## 5. State persistensi dan lifecycle

- `remember`: transient composition state. `rememberSaveable`: local UI state yang serializable.
  `ViewModel`: state screen. `SavedStateHandle`: hanya keys yang benar-benar disimpan.
  Room/DataStore: persisted source sesuai owner. Jangan menganggap level ini ekuivalen.
- Register menyimpan overlay/camera URI saveable, tetapi form berada di StateFlow VM.
  Edit config menyimpan raw strings keyed exercise ID, bukan seluruh edit draft ke disk.
- `MainScreen` memakai Navigation 3 decorators untuk saved state/ViewModel store; outer
  graph menjaga route dan picker results. Jangan memperkenalkan Navigation 2 controller
  atau Activity recreation untuk refresh bahasa tanpa alasan dan persetujuan.
- Locale root memperbarui `LocalResources`/`LocalConfiguration` sambil mempertahankan
  `LocalContext` Activity untuk Hilt owners. Jangan membungkus seluruh graph dengan context
  locale baru atau `key(language/theme)` yang mereset backstack.
- Date grouping Progres menggunakan `startTime` dan zone lokal. Last-workout ordering,
  duration/paused-time semantics dan monthly bounds adalah kontrak terpisah; bukan format teks.
- Cancel job lama ketika mengganti query/loader sesuai existing VM. `CancellationException`
  jangan dijadikan generic empty/error yang menyembunyikan pembatalan operasi.

## 6. Active workout/rest timer boundary

Jika desain baru bersinggungan dengan ActiveWorkout, baca source target dan regresi timer:
`presentation/ui/workout/ActiveWorkoutViewModel.kt` dan `core/timer/RestTimerCoordinator.kt`.

Pertahankan stable session/set IDs, mutation/revision/cancellation guards, dan pemisahan
  template/session/history. Countdown harus mengikuti state committed coordinator; jangan
  menulis durasi optimistic dari mockup, menduplikasi ticker, atau merombak AlarmManager/FGS.
  UI-only skill tidak berwenang mengubah auth lifecycle, recovery policy atau deadline.

## 7. Addendum fix-bug — 20 Oktober 2026

Permintaan business/data terpisah dari redesign mengubah kontrak berikut. Ini bukan izin
bagi tugas UI-only untuk mengubah sinkronisasi/migrasi/timer di kemudian hari.

- Katalog/picker dan profil sekarang mengamati Room; jangan menambahkan cache snapshot
  atau mengganti hasil kosong production dengan fixture demo. Query/filter/selection tetap
  milik ViewModel. Refresh remote terkoaleskan pada route/foreground, bukan saat recomposition.
- Home/Progres/Profil mengamati identitas owner lokal. Summary mengamati satu log. Editor
  memperbarui source ketika bersih; source revision mereset field hanya saat source diadopsi,
  bukan setiap ketikan. Refresh tidak boleh menimpa draft yang dirty atau snapshot sesi aktif.
- Volume hanya completed sets. Input/auto-fill belum selesai tetap berkontribusi **0**.
  Log akhir tidak memasukkan gerakan tanpa completed set maupun set belum selesai.
- Auto-fill mengikuti exercise ID/owner dan target set/reps asal yang tepercaya. Target
  berubah → default untuk gerakan itu; rename saja tidak membatalkan. Tambah set manual
  memakai default target, bukan otomatis menyalin beban terakhir. Resume tidak mengisi ulang.
- Notifikasi selesai: bodi membuka sesi sambil menghentikan alarm; Stop background saja.
  Notifikasi native mengikuti tema Android, bukan toggle Compose. Pengaturan/dialog alarm
  tidak diubah. Jangan mengganti surface native/custom background demi menyeragamkan tema.
- Pengujian lokal/importer bukan bukti E2E dashboard Supabase atau persetujuan OEM.
