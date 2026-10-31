# IMFIT Phase 1 — Runbook macOS (review sebelum live)

**Environment:** `hybrid@b42153001232b2b781099e2352ca18de31faac5a`; Supabase `ltcxltahkrjnsjaxndsa` (PostgreSQL 17); expected catalog = **161**.

**BLOCKER:** Jangan deploy ke live sebelum Phase 0 PASS, backup sudah berhasil direstore, dan migration metadata drift telah selesai direkonsiliasi. Paket ini dibuat dari desain v2.1 dan audit read-only Supabase. DDL/DML belum diuji di engine PostgreSQL pada sesi pembuatan paket ini.

## Isi paket

- `supabase/migrations/20261101000100_exercise_multimodality_tracking.sql`: satu migration DDL + 161-row fixture + backfill + fail-fast assertions. Disiapkan untuk satu operasi atomik.
- `tests/phase1_preflight_readonly.sql`: gate read-only untuk memastikan schema v1 dan jumlah exercise belum berubah.
- `tests/phase1_assertions.sql`: assertions read-only yang menghentikan CI bila katalog salah.
- `evidence/exercise_tracking_fixture_161.csv`: 161 ID dan klasifikasi eksplisit untuk review domain satu-per-satu.

## Persiapan

1. Verifikasi `supabase --version`, `psql --version`, `docker version`.
2. Pastikan `~/IMFIT-SAFE/phase0/backups` lengkap dan restore Phase 0 PASS.
3. Gunakan working branch baru dari snapshot `hybrid@b4215300`, jangan mengedit worktree baseline.
4. Cocokkan **semua** `exercise_id` fixture dengan output `SELECT id,name,equipment FROM public.exercises ORDER BY id`. Review klasifikasi fisiologis/fitness terutama special cases.
5. Pastikan migration `20261031154000` direkonsiliasi dan `supabase migration list` sinkron. **Jangan** menjalankan `db push` saat masih ada migration lama yang tidak sinkron.
6. Jalankan preflight **sebelum apply**: `psql "$IMFIT_DB_URL" -X -v ON_ERROR_STOP=1 -f supabase/tests/phase1_preflight_readonly.sql` (dari working repo setelah file dicopy).

## Pembuatan migration pada repo kerja

```bash
cd ~/Projects/imfit-mobile
# Pastikan git HEAD sudah memuat commit rekonsiliasi Phase 0 (jangan mulai
# kembali dari baseline b4215300 tanpa mengambil perubahan Phase 0).
git status --short
git switch -c feature/exercise-multimodality-phase1
supabase migration new exercise_multimodality_tracking
# CLI membuat supabase/migrations/<timestamp>_exercise_multimodality_tracking.sql.
# Setelah memastikan file itu KOSONG dan belum dikomit, hapus file kosongnya.
# Gunakan tepat satu file SQL paket ini dengan planned version 20261101000100
# (pastikan timestamp ini lebih baru daripada seluruh applied migrations).
cp '/lokasi/IMFIT_Phase1_macOS/supabase/migrations/20261101000100_exercise_multimodality_tracking.sql' supabase/migrations/
mkdir -p supabase/tests
cp '/lokasi/IMFIT_Phase1_macOS/tests/phase1_preflight_readonly.sql' supabase/tests/
cp '/lokasi/IMFIT_Phase1_macOS/tests/phase1_assertions.sql' supabase/tests/
```

## Uji **lokal terisolasi** (restore Phase 0 yang sudah diuji)

```bash
# Pastikan LOCAL_DB_URL hanya 127.0.0.1/localhost.
LOCAL_DB_URL='postgresql://postgres:postgres@127.0.0.1:54322/postgres'
case "$LOCAL_DB_URL" in
  *'@127.0.0.1:'*|*'@localhost:'*) ;; 
  *) echo 'ERROR: target bukan database lokal'; exit 1;;
esac

# Jalankan pada RESTORE COPY/DATABASE LAB yang dibuat khusus untuk migration test.
# Jangan menggunakan database lokal hasil restore yang menjadi satu-satunya bukti Phase 0.
psql "$LOCAL_DB_URL" -X -v ON_ERROR_STOP=1 --single-transaction \
  -f 'supabase/migrations/20261101000100_exercise_multimodality_tracking.sql'
psql "$LOCAL_DB_URL" -X -v ON_ERROR_STOP=1 \
  -f 'supabase/tests/phase1_assertions.sql'
```

**Penting:** `SET LOCAL lock_timeout` bekerja ketika ada transaksi; `psql --single-transaction` menjamin itu untuk tes manual. Untuk deployment resmi gunakan CLI migration, bukan langsung `psql` ke live; periksa error/transactionality CLI versi terinstal.

### Negative test (WAJIB)

```bash
# Test hanya di database scratch hasil restore, bukan remote.
# Jalankan assertion yang sama saat salah satu latihan tidak memiliki mode.
# Karena ON_ERROR_STOP=1, psql berhenti dengan kode nonzero dan transaksi
# dibatalkan otomatis saat koneksi ditutup.
set +e
{
  printf 'BEGIN;\n'
  printf "DELETE FROM public.exercise_tracking_modes WHERE exercise_id='ex_chest_4';\n"
  cat supabase/tests/phase1_assertions.sql
  printf 'ROLLBACK;\n'
} | psql "$LOCAL_DB_URL" -X -v ON_ERROR_STOP=1 > /tmp/imfit-phase1-negative.log 2>&1
NEGATIVE_EXIT=$?
set -e
if [ "$NEGATIVE_EXIT" -eq 0 ]; then
  echo 'FAIL: assertion tidak mendeteksi missing modes'; exit 1
fi
# Pastikan negative test tidak mengubah database setelah transaksi gagal:
psql "$LOCAL_DB_URL" -X -v ON_ERROR_STOP=1 -f supabase/tests/phase1_assertions.sql

```

## Gate deployment live

**Penting:** `supabase db push` normal membungkus tiap migration dalam transaksi, kecuali migration yang secara khusus diberi `pg-delta: transaction=false` atau menggunakan DDL non-transactional. File paket ini tidak memakai pengecualian tersebut. Pastikan pada versi CLI macOS dan audit log.

```bash
# Setelah approval, dari repo working tree yang sudah berisi SEMUA migrations yang sinkron:
psql "$IMFIT_DB_URL" -X -v ON_ERROR_STOP=1 -f supabase/tests/phase1_preflight_readonly.sql
supabase migration list --db-url "$IMFIT_DB_URL"
supabase db push --db-url "$IMFIT_DB_URL" --dry-run
# Hanya 20261101000100 boleh tampil sebagai pending. Tidak boleh ada file migration lama.

# MUTASI LIVE: jalankan HANYA dengan sign-off dan backup restore PASS.
# supabase db push --db-url "$IMFIT_DB_URL"
```

## Verifikasi setelah deploy (read-only)

```bash
psql "$IMFIT_DB_URL" -X -v ON_ERROR_STOP=1 \
  -f supabase/tests/phase1_assertions.sql
supabase migration list --db-url "$IMFIT_DB_URL"
git diff --check
```

**Release gate:** setelah local test, `git diff --check`, review DDL/security, dan sign-off, commit file migration + test ke working branch. Pastikan hanya migration `20261101000100` yang muncul pending pada dry-run. **Tidak gunakan `--include-all` untuk mengatasi migration drift.**

**Status:** belum diaplikasikan live. Jika local test gagal, perbaiki migration di branch kerja dan ulangi pada salinan restore baru; jangan menerapkan partial SQL ke remote.
