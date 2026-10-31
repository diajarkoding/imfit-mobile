# Memilih Exercise — Personal Feature Documentation
## 0. Metadata
|Field|Detail|
|---|---|
|**Nama Fitur**|Memilih exercise|
|**Status**|`Implemented`|
|**Versi Dokumen**|v2.3|
|**Tanggal Dibuat / Update**|2026-10-31|
|**Android Module / Flavor**|`:app`; demo, production|
|**Source Scope**|Exercise selector dari template detail|
|**Last Verified Against Commit**|`5b3dd1af81698b0e47de8db598d5cba694907a65`|
|**Confidence Summary**|Verified|
## 1. Ringkasan dan Tujuan
Selector memuat semua exercise, mendukung search/filter kategori dan multi-select, lalu menambahkan hasil ke template tanpa duplikasi.
**Tujuan utama**: menambah exercise ke template.
**Di luar cakupan**: preselect exercise template dan restore selection: tidak ada.
## 2. Entry Point dan User Flow
**Entry point**: `WorkoutDetailScreen` -> `ExerciseSelection(templateId)`.
```mermaid
flowchart TD
    P[Picker: ExerciseSelectionScreen] -->|Pilih kategori| C[Filter Reaktif on-demand + Page 10 + Infinite Scroll]
    P -->|Buka MuscleFilterSheet| S[Single-Tap Chip: Langsung aktif & auto-dismiss tanpa Cancel/Apply]
    P -->|Konfirmasi Tambahkan| H[Handoff Otomatis Langsung]
    H -->|Alur Initial| SETUP[SetupRoutine: Mode SETUP]
    H -->|Alur Edit| EDIT[EditWorkout: Mode EDIT]
```
## 3. UI/UX dan Interaksi
|Screen / Composable|Perilaku aktual|Evidence|
|---|---|---|
|`ExerciseSelectionScreen`|Search, chip filter kategori reaktif, shimmer, infinite scroll page 10, checkbox selection, Card bottom compact (topPadding 4.dp, contentGap 4.dp)|`ExerciseSelectionScreen.kt`|
|`MuscleFilterSheet`|Daftar opsi kategori otot, single-tap langsung aktif dan seketika dismiss, tanpa tombol Cancel dan Apply filter|`ExerciseSelectionScreen.kt:368-410`|
|`RoutineFooter`|Card bottom footer compact dengan jarak vertikal masing-masing 4.dp|`ImfitRoutineComponents.kt:80-92`|
## 4. Implementasi Teknis
|Peran|File / Symbol|Evidence|
|---|---|---|
|State|`ExerciseSelectionViewModel`|`ExerciseSelectionViewModel.kt`|
|Data|`ExerciseRepository`, `updateTemplateExercises`|`WorkoutRepositoryImpl.kt`|
|Navigation|saveable `exerciseSelectionResult`|`NavGraph.kt:119-160`|
|DI|Hilt repository binding|flavor `AppModule.kt`|
**State dan Data Flow**: Selector -> VM -> exercise repo -> selected IDs -> NavGraph result -> detail -> workout repo/Room.
**Storage, API, dan Dependency**: pilihan transient; template update local-first production.
## 5. Aturan Bisnis, Validasi, dan Edge Cases
|Skenario|Perilaku aktual / diharapkan|Evidence|Confidence|
|---|---|---|---|
|Search/filter|Trim, case-insensitive name/category display name|`ExerciseSelectionViewModel.kt`|Verified|
|Duplicate|VM dan detail dedupe; repository require distinct|`WorkoutRepositoryImpl.kt:638-642`|Verified|
|Recreation|Selection tidak dipulihkan|`ExerciseSelectionScreen.kt`|Verified|
## 6. Android Platform, Security, dan Privacy
|Area|Detail|Evidence|Status|
|---|---|---|---|
|Platform/security|Tidak ada integrasi khusus|source scope|N/A|
## 7. Testing dan Manual QA
**Existing Tests**: tidak ada selector VM/UI/result-handoff test.
### Manual QA Checklist
- [ ] Search, tiap filter, multi-select, duplicate.
- [ ] Konfirmasi lalu cek exercise pada template.
- [ ] Rotate/recreate selector: TODO/VERIFY behavior.
## 8. Performance dan Reliability
|Area|Implementasi aktual / risiko|Evidence|Tindakan lanjut|
|---|---|---|---|
|State restore|`templateId` tidak dipakai; selection transient|`ExerciseSelectionScreen.kt:74`|Open|
## 9. Dependency, Risiko, dan TODO
**Bergantung pada**: Navigation 3 saveable state dan workout repository.
|Risiko / Pertanyaan / TODO|Evidence / Source|Status|
|---|---|---|
|Tidak ada empty-result state|`ExerciseSelectionScreen.kt`|Open|
## 10. Changelog
|Versi|Tanggal|Perubahan|Commit|
|---|---|---|---|
|v1.0|2026-08-27|Dokumen awal dibuat|`5b3dd1a`|
|v2.3|2026-10-31|Update spesifikasi v2.3: filter kategori reaktif on-demand page 1 + pagination limit 10/infinite scroll; bottom sheet single-tap auto-dismiss tanpa Cancel/Apply; card bottom spacing 4.dp/4.dp; handoff otomatis langsung ke SETUP/EDIT; batasan ketat scope tidak menyentuh session aktif|current|
## 11. Referensi
- Source code: `ExerciseSelectionScreen.kt`, `ExerciseSelectionViewModel.kt`.
- Design / screenshot: N/A.
- External API documentation: N/A.
- Existing documentation: `docs/features/imfit-features.md`.
## 12. Evidence Map
|Claim|Source|Evidence Type|Confidence|
|---|---|---|---|
|NavGraph mengembalikan selected IDs|`NavGraph.kt:152-160`|code|Verified|
## 13. Coverage Map
|Area|Files Inspected|Coverage|Notes|
|---|---:|---|---|
|UI / Compose|1|Verified|Selector|
|State / ViewModel|1|Verified|Search/filter|
|Data / storage / API|1|Verified|Template update|
|Navigation / DI|2|Verified|Result/flavor|
|Android platform|0|N/A|Tidak ada khusus|
|Tests|0|N/A|Tidak ditemukan|
