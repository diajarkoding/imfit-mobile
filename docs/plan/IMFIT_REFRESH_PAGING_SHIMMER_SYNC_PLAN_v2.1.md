# IMFIT — Finalize Plan v2.1 & Build Readiness Gate

**Mode: PLAN ONLY — NO IMPLEMENTATION.**
**Baseline:** [audit v2](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.md), working tree `fix-bug` pada audit 21 Oktober 2026, HEAD `37bfdf9d817d60a8b9fb2e6e2cae1478c5ffca8d` beserta perubahan enam issue yang belum committed.
**Tujuan addendum:** merapikan rekomendasi keputusan, batas keselamatan, urutan implementasi, dan gate terukur; bukan mengulang repository discovery.
**Izin saat ini:** pembaruan dokumentasi dan pemindahan baseline ke `docs/plan/` saja. D1–D9 tetap **rekomendasi menunggu persetujuan**, bukan izin mengubah kode.
**Verifikasi saat pembaruan:** branch/status serta baseline dibaca. Build, benchmark, runtime test, migrasi, dan Supabase E2E **NOT_TESTED / tidak dijalankan**. Tidak ada commit/push atau perubahan source/desain/perangkat.

## Status bukti

- **VERIFIED:** fakta hasil audit source v2; bukan hasil runtime baru. Referensi bagian/bukti v2 tetap berlaku sebagai snapshot audit, bukan jaminan source tidak akan berubah.
- **PROPOSED:** kontrak/perubahan/test yang disarankan setelah approval.
- **UNCONFIRMED:** memerlukan bukti runtime, benchmark, mockup final, atau backend aktual.
- **BLOCKED:** tidak boleh dilanjutkan pada boundary terkait sebelum keputusan/bukti terpenuhi.

Readiness **4/8** pada v2 adalah kelengkapan artefak desain, **bukan empat pengujian implementasi yang lulus**. Addendum ini tidak menaikkan skor runtime.

## 1. Decision register D1–D9

Seluruh rekomendasi berikut **belum disetujui untuk implementasi**. Status fakta/bukti dipisahkan dari status persetujuan.

| ID | Rekomendasi default untuk plan | Bukti / batas | Approval / gate |
| --- | --- | --- | --- |
| D1 | Pertahankan **dua kartu Progress** existing selama pekerjaan refresh | VERIFIED: model menghitung empat nilai, renderer dua kartu; Weekly Time memakai monthlyMinutes (v2 §1.3, §4.4) | Menunggu approval. Empat metrik final dan mismatch periode dicatat terpisah, **belum closed**; tidak diperbaiki diam-diam |
| D2 | Lindungi pending data sebelum logout; retry/batal, ditambah rancangan keluar aman saat offline | VERIFIED: logout existing menghapus data pending (v2 §9.5). Rancangan §4.4 addendum belum diterapkan | BLOCKED: kebijakan retention, privacy, dan jalur sign-out aman harus disetujui; jangan mengunci pengguna offline tanpa jalan keluar |
| D3 | Batasi perubahan gating initial-sync overlay pada enam konteks target | VERIFIED: overlay existing menutupi shell (v2 §2.3, §6.1) | Menunggu approval perubahan gating saja; Navigation 3, scrim, dialog lain tetap |
| D4 | Paging 3 conditional; evaluasi fixture **81/150/1500** | VERIFIED: SQL seed81, demo42; manfaat performa belum terukur (v2 §6.3) | Menunggu approval eksperimen/dependency terkait; benchmark gate sebelum integrasi E; tidak meng-upgrade dependency lain |
| D5 | Urutan **ID lexical deterministik**, parity browser/picker | VERIFIED: browser sort ID eksplisit, picker DAO tidak mempunyai ORDER BY (v2 §6.3) | Menunggu approval baseline urutan picker; gunakan unique tie-breaker jika kelak ada product sort lain |
| D6 | Non-destructive reconciliation bila remote snapshot belum terverifikasi | VERIFIED: transaksi lokal ada; coherence remote/atomic template replacement belum dijamin (v2 §9.1–9.4) | Menunggu approval degraded-sync policy §4.3; backend protocol work terpisah; tidak mengklaim aman hanya karena memilih mode ini |
| D7 | Supabase E2E hanya project disposable + akun A/B berizin | UNCONFIRMED: environment/credentials/izin belum tersedia untuk sesi ini | BLOCKED untuk E2E, bukan alasan memakai produksi; provisioning/test destruktif memerlukan otorisasi khusus |
| D8 | Coverage marker fail-safe tanpa migrasi otomatis | PROPOSED: marker setelah Room commit, crash gap menjadi unknown (v2 §5.3) | Menunggu approval; metadata/revision atomik yang memerlukan schema baru -> STOP dan migration plan terpisah |
| D9 | Source existing sebagai baseline konservatif; visual sign-off terhadap mockup yang disetujui | VERIFIED: Profile/History reference tersedia pada audit; empat konteks lain belum ditemukan pada daftar frame (v2 §2.3) | Menunggu approval baseline dan konfirmasi reference final; tidak menyatakan visual approved dari screenshot source saja |

**Aturan precedence:** addendum memperjelas rekomendasi dan gate v2. Bukti source tidak diubah menjadi bukti runtime; jika fakta source berubah, verifikasi lokasi terkait saja sebelum Build. Tidak ada perubahan backend/schema yang otomatis disetujui lewat dokumen ini.

### 1.1 Backlog Progress terpisah

- **PROG-SEM-01 — OPEN:** label Weekly Time menerima `monthlyMinutes`. Tentukan periode/label yang benar melalui review produk, lalu uji batas minggu/bulan/zona. Jangan mengganti label atau rumus sebagai efek samping shimmer.
- **PROG-VIS-02 — OPEN:** kebutuhan produk empat metrik final versus dua kartu aktual. Perlu layout/mapping yang disetujui, termasuk Monthly Workouts dan Monthly Completed Sets.
- Selama scope refresh: pertahankan dua kartu dan calculation baseline; test equality berarti **tidak regresi**, bukan membuktikan kontrak empat metrik sudah benar.

## 2. P0 / P1 / P2 risk classification

Prioritas ditentukan dampak, bukan frekuensi yang belum diukur. Semua perubahan remediasi **PROPOSED**.

| Risiko | Prioritas | VERIFIED dari v2 | Batas yang harus dijaga |
| --- | --- | --- | --- |
| R01 — Silent deletion saat logout | P0 Data Safety | Logout drain lalu deleteUserData, termasuk pending | Jangan purge unsynced rows; keputusan offline/private sign-out diperlukan |
| R02 — Account/epoch commit race | P0 Data Safety | Identity check di luar transaction, observer menangkap akun sekali | Fence sampai commit, owner-scoped publication, ABA protection; SDK session ordering belum terbukti |
| R03 — Template replacement parsial | P0 Data Safety | Parent/delete children/insert terpisah secara remote | Jangan mengeksekusi jalur destructive tanpa protokol aman; jangan switch RPC existing diam-diam |
| R04 — Snapshot completeness/coherence | P0 Data Safety | Offset500 bukan isolated snapshot; parent recheck bersyarat | Unverified snapshot tidak boleh menonaktifkan record atau mengganti children/cache valid |
| R05 — Payload/ack concurrent write | P0 Data Safety | Multi-query upload capture; timestamp+op CAS | Capture parent/children/revision konsisten; old ack tidak clear pending baru, termasuk same-ms/ABA |
| R06 — Request storm / cancellation / lost wakeup | P0 gate keselamatan; P1 gejala beban jaringan | Dua mutex lane, cooldown berlapis, Worker append chain | Shared flights lintas entry points; durable pending tetap terschedule, bounded concurrency |
| R07 — Flicker / false empty / false zero | P1 Loading & UX Stability | Restart loading observer menutupi cache Home/Progress | Availability per section; cache tetap; error bukan empty; reduced motion |
| R08 — Layout/scroll/gesture regression | P1 Loading & UX Stability | Skeleton generik tidak cocok seluruh shape | Header8, footer/IME/nav stable; same-context anchor dan semantics |
| R09 — Paging complexity/order/filter/count | P2 Pagination & Performance | Katalog kecil, no Paging deps, picker no SQL order | Benchmark/parity; full-dataset query; no network append |
| R10 — Picker invalid selection/result race | P0 bila salah owner/result; P2 untuk paging integration | Selected pruning dan confirm tanpa DAO validation | Owner/context guard adalah gate B; selection independence dan revalidation gate E, bukan ditunda sampai perf selesai |

UNCONFIRMED: insiden runtime, frekuensi overlap, server deploy/RLS, OEM/device performance. Tidak menurunkan P0 karena belum ada insiden terukur.

## 3. Dependency graph dan safe implementation order

```text
Approval keputusan terkait + A: baseline/characterization
  └─ B: safety coordinator, session fence, pending/snapshot protections
       ├─ B exit gate WAJIB sebelum remote refresh dihubungkan ke UI
       └─ C: availability contract + content-aware skeleton
            ├─ D: DAO/repository paging + filter/count parity + benchmark
            │    └─ E: Exercise List + Picker
            └─ F: Home + Progress + Profile + History by Date
                 E + F ──> G: regression + authorized E2E + release review
```

Urutan eksekusi yang diusulkan **A → B → C → D → E → F → G**. F tidak bergantung pada pager D/E secara arsitektur, tetapi tidak boleh dipakai untuk melewati exit gate B. Tidak ada agent delegation atau pekerjaan implementasi yang dimulai oleh graph ini.

| Phase | Deliverable dan impact terarah | Pemisahan risiko |
| --- | --- | --- |
| A | Baseline tests/diff inventory, keputusan D1–D9, fixture/counter, benchmark protocol. v2 §10–11 berisi lokasi existing | Test/design change set terpisah dari perubahan behavior |
| B | SyncManager/SyncRunner/coordinator facade, AuthRepository/session gate, scheduler/Worker, importer/repository guards, coverage/outcomes; DI seperlunya | High-risk safety-only change sets; tidak menyertakan shimmer/layout/dependency paging/backend migration |
| C | State/ViewModel/presentation Home/Progress/list/picker/Profile/History; scoped primitives dan resources ID/EN | Observer/content-state dahulu, visual primitives sesudahnya; perubahan helper shared harus punya caller regression |
| D | ExerciseDao, ExerciseRepository interface/production/demo, pure query spec; dependency Paging terkait saja jika disetujui | Query/count/ID-resolution tests dan benchmark sebelum renderer baru; tidak bump schema secara otomatis |
| E | ExerciseListScreen/ExerciseSelectionScreen + VMs + confirm boundary | Adapter list dan picker dipisah; selection/result guards tidak dicampur dengan desain baru |
| F | Empat konteks nonpaging, section skeletons, overlay gating terbatas bila disetujui | Tiap konteks dapat direview/rollback sendiri; calculation baseline tidak berubah |
| G | Regression artifacts per variant/device, authorized disposable Supabase E2E, visual sign-off | Status lokal, perangkat, dan backend dilaporkan terpisah; jangan menggabungkannya menjadi satu PASS |

## 4. Sync safety contracts dan state ownership

### 4.1 Invariant register

Semua invariant target **PROPOSED**. Kolom existing menunjukkan bukti parsial dari v2, bukan klaim target telah terpenuhi.

| ID | Invariant terukur | Existing evidence / gap | Gate pembuktian / kebutuhan protokol |
| --- | --- | --- | --- |
| S01 | Maksimal satu active flight untuk `(owner, epoch, dataset)`; semua waiter menerima shared result, bukan antrean duplicate runs | VERIFIED mutex/cooldown existing, belum lintas full/master/reconnect/Worker | Counter + barriers lintas seluruh entry point; tanpa perubahan backend |
| S02 | Manual melewati auto cooldown tetapi join active demand;10 overlapping pulls tidak menjadi10 remote flights berurutan | PROPOSED central registry + monotonic cooldown; manual bukan bypass keselamatan | Test overlapping serta immediate completed burst/debounce; intentional refresh setelah selesai bukan dilarang selamanya |
| S03 | Hanya epoch/owner sah dapat commit; fence tetap aktif sampai transaksi selesai | VERIFIED explicit logout drain, identity check belum atomic | Transition/fence lock order + A→B→A tests; SDK event ordering UNCONFIRMED, bukan cukup check dua kali |
| S04 | Room observers, pager, selection, cached presentation dan result delivery selalu context owner/epoch | VERIFIED list owner-filtered, beberapa by-ID query tidak owner-filtered | Invalidate old subscriptions/results sebelum publication akun baru; tidak ada stale callback ke sesi baru |
| S05 | Upload parent/children/revision merupakan satu snapshot lokal konsisten | VERIFIED beberapa query terpisah sebelum send | Capture transaksi singkat, network di luar Room lock, deterministic concurrent mutation tests |
| S06 | Pending/newer local tidak ditimpa; acknowledgement hanya untuk revision yang dikirim | VERIFIED importer parent guard dan timestamp/op CAS | Test child dirty invariant, same-ms mutations, retries/ABA. Bila perlu revision persisten baru, STOP untuk D8/migration review |
| S07 | Remote ownership, graph, completeness, coherence tervalidasi sebelum replacement | VERIFIED graph validator/parent recheck; offset/server cap/child-parent revision masih gap | §4.3 policy. Jaminan snapshot penuh dapat memerlukan backend revision/read protocol terpisah |
| S08 | Unverified remote data tidak memicu delete/deactivate-missing, child replacement, atau clear flags/cache valid | PROPOSED conservative mode | Instrument DB before/after partial/duplicate/truncated/mutating snapshot; tidak dianggap successful complete sync |
| S09 | Pending operation durable tetap terjadwal sampai ack sah; write-after-last-scan tidak lost wakeup | VERIFIED Room pending+WorkManager exponential; queue coalescing belum dibuktikan | Drain/revision handshake dan crash recovery; jangan mengganti APPEND_OR_REPLACE ke KEEP tanpa race proof |
| S10 | Typed outcomes dan finalization benar untuk success/partial/offline/network/server/auth/cancel | VERIFIED existing tiga enum/Boolean terlalu kasar | finally releases registry/locks/indicators pada setiap barrier; old flight tak menutup indicator flight baru |
| S11 | Local paging append/retry memicu **nol** request remote | PROPOSED local PagingSource tanpa RemoteMediator | Endpoint counter selama load/append/retry/search; metrics/prefill tidak memakai window loaded |
| S12 | Logout tidak menghapus data pending tanpa keputusan eksplisit; akun baru tidak membaca/mengunggah pending akun lama | VERIFIED existing purge melanggar target | Kebijakan §4.4 + test offline/sign-out/session-expiry/retention; approval privacy wajib |

### 4.2 Concurrency/lifecycle yang disarankan

- Service single-flight application-scoped; semua entry point join facade/registry yang sama, tidak menambah gesture-only mutex. Registry lock hanya register/join/remove; network tidak di bawah lock registry atau Room.
- Dataset demand: Home PROFILE+TEMPLATES+HISTORY; Progress PROFILE+HISTORY; History HISTORY; Profile PROFILE; List/Picker CATALOG. Subset requests join flight dataset yang sama.
- Mutable upload barrier per owner; hasil pull yang dimulai sebelum local write baru tidak otomatis memenuhi fresh-after-write demand. Recheck pending/revision saat commit; bounded follow-up demand, bukan satu run per waiter.
- Satu mutable-user lane + maksimal satu independent catalog lane sebagai batas awal; catalog tidak tertahan unrelated failed workout upload. Full sync dan master read berbagi catalog flight yang sama.
- Lock order target: session/commit fence → Room. Tidak menahan registry lock saat menunggu fence/worker. Auth transition invalidate/block, release gate, drain/cancel read flights, lalu cleanup yang disetujui; jangan menunggu job yang membutuhkan lock yang sedang ditahan.
- Session revision mencakup login ulang user ID sama. Unexpected SDK transition harus mempunyai linearization point yang teruji; listener async saja tidak membuktikan atomic fence.
- Navigasi keluar hanya melepas waiter/collection UI. Tidak membatalkan durable upload/pending operation. Process death menghilangkan in-memory flight, bukan pending DB; Worker melanjutkan sesudah eligible auth/network.
- Retry otomatis: existing WorkManager exponential dipertahankan; centralized backoff/jitter boleh diusulkan tanpa duplicate retry loop screen. Foreground manual refresh tidak menumpuk auto jobs.
- Logging baru hanya flight/dataset/origin/join/start/page count/elapsed/terminal reason tanpa token, email, DOB, signed URL, atau payload akun. Logging tidak dianggap bukti dedup tanpa counter assertions.

### 4.3 D6 — conservative snapshot dan template-write policy

**PROPOSED: fail closed untuk perubahan yang dapat merusak cache/data.** Lolos HTTP request dan EOF page pendek bukan bukti snapshot authoritative.

| Jenis hasil remote | Kebijakan apply yang direkomendasikan | Outcome / konsekuensi |
| --- | --- | --- |
| Ownership/epoch tidak sah, duplicate/invalid graph, request terputus/truncated | Jangan apply boundary snapshot itu; jangan set coverage complete | AuthChanged atau failure dengan reason spesifik; cache/pending tetap |
| Katalog individual records tervalidasi, tetapi daftar keseluruhan unverified | Hanya pertimbangkan upsert record server-owned yang valid jika ordering/freshness antar flights terjaga; **tidak** deactivate missing atau menganggap coverage lengkap | PartialSuccess/unverified-completeness; jika freshness row pun tidak terbukti, jangan overwrite row cached |
| Template/history aggregate yang completeness/coherence belum terbukti | Jangan replace parent/children cached, menghapus omitted child, atau clear pending. Jangan mengimpor graph parsial sebagai complete aggregate baru | SnapshotUnverified reason pada typed failure/partial outcome; cache tetap, no-cache menampilkan unavailable/retry, bukan skeleton abadi |
| Snapshot benar-benar tervalidasi sesuai kontrak read yang telah dibuktikan | Apply atomic boundary existing + owner/epoch/pending/revision guard | Success hanya untuk dataset/boundary yang memenuhi predicate; aggregate sebelumnya boleh berhasil ketika aggregate berikut gagal |
| Parent hilang dari collection tanpa tombstone authoritative | Jangan hard-delete lokal karena absence saja | Stale remote deletion mungkin belum terlihat; limitation harus ditampilkan/didokumentasikan |

Predicate verifikasi mencakup identity, ownership/graph, completeness tiap page/collection, coherence version parent+children, pending/local revision, serta commit fence. Total count, stable order, dua scan sama, atau parent DTO recheck **sendiri-sendiri tidak cukup** untuk membuktikan isolated snapshot. Coverage marker tidak membuat snapshot tak terpercaya menjadi verified.

**Batas template upload P0:** kebijakan download non-destructive saja tidak memperbaiki jalur upload delete-all-children+insert. Sebelum Phase B lulus, usulkan guard yang **menunda seluruh operasi template yang membutuhkan replacement destructive sebelum request pertama**, pending tetap durable dengan alasan jelas. Jangan menulis parent dahulu lalu memblokir children; jangan menandainya SYNCED. Jangan mengganti ke template RPC existing karena child UUID/pruning belum terselesaikan. Retry tidak boleh berulang otomatis melalui jalur destructive yang sudah diketahui berisiko.

**BLOCKED / perlu persetujuan D6:** penundaan ini menurunkan kemampuan sync rutinitas dan dapat membuat pending tetap ada. Atomic remote replacement yang benar memerlukan protocol/backend work terpisah. Jika freshness/perangkat baru wajib segera dapat mengunduh seluruh histori/rutinitas, konservatif-only tidak cukup: hentikan rollout boundary tersebut, jangan mengendurkan validasi untuk mengejar UI.

### 4.4 D2 — rancangan logout online/offline dan privasi

**PROPOSED, belum keputusan produk:** pisahkan **sign-out identitas** dari **purge penyimpanan lokal**. Jangan menjanjikan database terenkripsi atau server token revocation jika belum diverifikasi.

| Situasi | Rancangan perilaku | Syarat keselamatan |
| --- | --- | --- |
| Tidak ada pending durable data | Logout existing dapat dilanjutkan sesudah identity fence; cleanup sesuai kebijakan yang disetujui | Pending check+transition terserialisasi terhadap local writes; angka0 dari state UI bukan bukti queue kosong |
| Pending + online | Jelaskan jenis/jumlah data belum tersinkron; tawarkan **Coba sinkronkan**, **Batal**, serta opsi keluar aman bila retention disetujui | Retry tidak menghapus data; gagal/partial/conflict tidak memicu purge; spinner tidak memblokir selamanya |
| Pending + offline / sync diblokir protokol | Jelaskan data tersimpan pada perangkat dan belum di server; Batal tersedia. Usulkan **Keluar tanpa menghapus data lokal** sebagai jalur offline yang tidak mengunci akun | Opsi keluar hanya setelah kebijakan owner-scoped retention/privacy disetujui dan guard terbukti; bukan automatic discard |
| Keluar aman dengan retention | Invalidate epoch, drain/cancel bounded old reads, sign out sesuai lifecycle teruji, sembunyikan seluruh data akun dari shell/observer | Pending tetap ownerA, tanpa token disimpan di payload; akunB tidak dapat view/ack/upload milikA |
| Login ulang akunA | Resolve identity baru, buka dataA setelah auth sah, schedule durable retry | A→B→A memakai epoch baru; tidak replay callback/selection lama |
| Perangkat bersama / ingin menghapus data | Jelaskan retention dan risiko data belum ada di server | Destructive device-data removal/discard adalah keputusan eksplisit terpisah, tidak default tersembunyi dan tidak diklaim zero-data-loss |

**Unresolved privacy gate:** data offline tetap berada pada storage app meskipun tidak ditampilkan. Tentukan retention duration tanpa auto-deletion pending, perlindungan storage/backup existing, threat model perangkat bersama, akun terhapus/tidak bisa login kembali, dan mekanisme user-informed removal. Tidak mengklaim UI isolation sama dengan encryption. Jangan mengubah active-workout/timer cleanup semantics; bila existing cleanup tak dapat dipisahkan dengan aman, STOP untuk review lifecycle khusus.

Jika kebijakan retention belum disetujui, implementasi logout baru tetap BLOCKED; **melarang logout offline selamanya bukan penyelesaian akhir**. Safe logout dan penundaan template upload harus dirancang bersama agar tidak membuat dead-end pengguna.

### 4.5 Coverage, outcome dan pending-write separation

**PROPOSED:** coverage marker per owner/dataset dan catalog scope yang sesuai. Set hanya setelah verified successful Room commit; DataStore/Room crash gap -> **unknown**, bukan verified empty. Marker milik akunA tidak mengesahkan historyB. Corruption/invalid schema marker -> unknown. Tidak ada migrasi otomatis.

Typed refresh outcome: Success, PartialSuccess (dataset/boundary outcomes), Offline, NetworkFailure, ServerFailure, AuthChanged, Cancelled; reason seperti SnapshotUnverified, PendingBlocked, ConflictSkipped ditampilkan sesuai konteks, bukan semuanya offline. Success read bukan “semua upload selesai”. Pending status berasal durable queue, bukan spinner pull-to-refresh.

## 5. UI state dan visual contract

### 5.1 Empat dimensi independen

| Dimensi | State target | Owner dan aturan |
| --- | --- | --- |
| Local section availability | Uninitialized, ReadingLocal, Available, VerifiedEmpty, Unavailable | Repository result/VM per owner+epoch+context+section; jangan default empty/0 saat belum selesai |
| Remote refresh | Idle, Running, typed terminal outcome | Coordinator + ViewModel waiter token; tidak clear content atau restart local observer |
| Local paging | initial/refresh/append Loading/Error/NotLoading+endReached+generation | Pager/VM; append/error lokal berbeda dari Supabase refresh |
| Pending writes/sync | count, pending, retrying, blocked, failed, acknowledged | Durable storage/scheduler; tidak hilang karena berpindah layar |

**PROPOSED transitions:**

- Cold cache hit: hasil Room langsung tampil; jangan menunggu network avatar/profile/refresh.
- Cold unknown: shell/statics tetap, reserved section layout; delayed skeleton awal **150ms** (evaluasi120–200). Query20ms tidak menampilkan shimmer; query250ms menampilkan section skeleton. Hasil siap langsung mengganti skeleton tanpa minimum duration.
- Available/VerifiedEmpty + remote Running: content/empty yang sah tetap + indikator refresh non-blocking, bukan skeleton ulang.
- Remote failure: cache tetap + pesan; tanpa cache -> offline-unavailable atau actual error/retry. Empty hanya sesudah coverage/query sah. Unknown tidak menjadi angka0.
- Same-context Room invalidation: retain last valid presentation sampai result baru; bukan cache authoritative kedua. Query baru/ganti akun tidak boleh diberi hasil lama berlabel query/akun baru.
- Paging append: footer spinner/retry; no network. Commit Room invalidates pager otomatis; tidak memanggil LazyPagingItems.refresh lagi setelah setiap remote commit.
- Publication, pending state, append, refresh, dan confirm/save mempunyai lifecycle/token terpisah; finally flight lama tidak menutup flight baru.

### 5.2 Screen-level skeleton specifications

Seluruh shape existing di bawah **VERIFIED pada audit v2 §4**; skeleton/availability/rendering baru **PROPOSED**. Dimensi adalah token/min-height existing, bukan fixed clipping heights. Metadata/font wrapping mengikuti layout actual.

| Target / exact existing shape | Skeleton + section availability | Initial / refresh with cache | Offline / error | Scroll / selection | Accessibility / theme / motion |
| --- | --- | --- | --- | --- | --- |
| **Home:** greeting23sp/line27.6 + supporting13sp; banner statis; last-workout CardSmall pad16 gap14, duration+volume/divider38; routine min80 radius12 pad12 thumbnail56 + trailing20 | Pisah readiness identity, last-log, routines. Greeting unknown hanya name line; last-log heading/date/value bars;2–3 bordered routine shells dengan slot56 dan text/count. Banner tidak shimmer | Initial section unknown setelah150ms saja; greeting siap boleh tampil saat routines unknown. Refresh mempertahankan kartu, nilai dan active badge | Profile/avatar gagal tidak menahan workout; cache+warning; no-cache error tidak menjadi empty routines | Stable keys section/template_ID, anchor same-context; dialog create draft tak berubah | One loading announcement/section, no fake user/count; training neutral surfaces live light/dark; static skeleton jika reduced motion |
| **Exercise List:** radius12 border1 min76 pad12, art kategori48, title/category/optional multiline description; search min52/top8 | Readiness query/list berbeda catalog freshness.5–7 rows sesuai viewport, art48/text slots; optional description proporsional, bukan foto remote baru | Search/appbar tetap; first local-page unknown memakai list skeleton. Refresh cache tetap; append hanya footer kecil | No-results dari full query atas known catalog; tanpa catalog offline -> unavailable, bukan hasil kosong sah | ID lexical stable key, same-query anchor; filter/query baru membatalkan generation lama tanpa remote fetch append | Loading group tanpa exercise palsu; search/clear labels tetap; neutral brush/static reduced-motion |
| **Exercise Picker:** radius12 min76 pad12, art48, title/category, checkbox slot48; search/filter/result-count; sticky RoutineFooter | Readiness list, total COUNT, selected metadata terpisah. Row skeleton dengan **slot checkbox noninteractive**, tanpa description baru; count unknown bukan0 | Footer/count selected tetap. Remote refresh tidak men-disable seluruh picker; append spinner di list, bukan footer CTA | Invalid/deactivated selected ID diberi pesan dan resolve/revalidate, tidak silent prune; retry tak reset selection | Selected ID set independen paging/filter; confirm validates full DAO owner/context/active/exclusions; result delivery one-shot setelah validasi sukses | Skeleton bukan Role.Checkbox; count/status terlokalisasi;48dp controls/IME; static neutral reduced-motion |
| **Progress:** profile row min56/avatar48; **dua** metric cards min116 pad16 value25sp/line27.5; calendar indicator34/date target48; selected-day min64/icon40 | Readiness identity/history metrics/calendar data/selected-day. Profile circle48+text; exact two metric shells, value bars saja; month/weekdays/date statis, unknown markers/counts; selected summary line unknown | Cached metrics/date markers tetap saat refresh. Tidak mengganti unknown dengan0; tidak menambah dua metric cards/histogram | Cache+warning, tidak mengganti dashboard dengan full error; marker unknown bukan ucapan “0 sesi” | selectedDate/month behavior existing; keys profile/metrics/calendar/selected_day; tidak reset ke today saat refresh | Unknown values tidak dibaca sebagai0; month grid4–6 minggu; neutral light/dark/static; profile navigation tetap |
| **Profile:** avatar72; name titleLarge/email supporting; tiga information rows min76/icon40; settings min88; stack width<320/font>1.3 | Identity/fields/avatar readiness terpisah. Circle72/name/email; field label tetap, value-only skeleton dan dividers actual. DOB null sah missing-value | Settings/lang/theme/logout terlihat; initial skeleton hanya unknown identity/field; refresh identity tetap | Photo fallback saja, bukan error seluruh profile; identity yang known mendahului history error | Lazy state/key identity/information/preferences/logout tetap; refresh tidak toggle settings | Satu polite loading identity; skeleton descendants decorative; widths clamp pada layar sempit; static reduced-motion |
| **History by Date:** date header; empat **total harian**; session radius14 border1 pad16, title+duration badge, tiga session metrics, exercise summary/count/volume | Date selalu known. Skeleton total-value shells, session heading/badge/summary/exercise rows; **tidak menambah weight/reps set-detail** karena UI existing tak merendernya | Date context tetap; initial unknown150ms; cached sessions/totals tetap refresh, update banner sesuai kegagalan | Verified date-empty berbeda unknown/offline/error. No-cache failure mengakhiri skeleton | Stable workout IDs; stacked width<=340/font>=1.25; anchor/date dipertahankan, bukan global history list baru | Heading tanggal tetap, satu loading group, no fake sessions; neutral palette/static motion-off |

**PROPOSED bersama:** shared animation phase per section, cycle existing1200ms sebagai baseline; reduced-motion menghentikan animasi namun **tetap terlihat** sebagai static skeleton. Tidak key ulang graph berdasarkan theme/locale, tidak crossfade dua salinan list, tidak memaksa shimmer lebih lama untuk menutupi restart observer. Satu owner inset per area, gutter20/header8 tetap.

### 5.3 Initial overlay dan mockup gate

- **PROPOSED D3:** ubah eligibility/gating overlay existing, bukan desain/modal policy. Untuk enam konteks, content availability/refresh non-blocking menjadi penyajian loading; route non-target/dialog lain tetap existing.
- Bila route eligibility tidak dapat dinyatakan tanpa perubahan kontrak navigation, STOP dan minta review; jangan remap Navigation 3 atau menambah root header/nav baru.
- **UNCONFIRMED D9:** final visual sign-off keenam target memerlukan comparison against approved mockup. Source baseline dapat dipakai untuk persiapan test/layout, bukan menggantikan approval mockup yang belum tersedia. Tidak membuka/mengubah desain Pencil pada pembaruan addendum ini.

## 6. Test-to-requirement traceability

Semua test berikut **PROPOSED / NOT_TESTED** untuk implementasi v2.1. Nomor T mengacu matriks v2 §11. Jenis: U unit, I Room/integration, C Compose, X concurrency, M device/manual, S authorized Supabase E2E. Test lokal/mock bukan bukti S.

| Requirement | Invariant / risiko | Test rujukan + tambahan | Minimum acceptance / gate |
| --- | --- | --- | --- |
|10 repeated pulls tidak10 flights berurutan | S01/S02, R06 | T05/T22; U/X counters origin foreground/manual/reconnect/Worker/master | Shared active flight/result; no queued duplicate after join; registry kosong setelah terminal; B |
| Home+Progress+History overlap | S01/S02 | T06; X barriers per dataset | HISTORY download satu per overlapping generation; PROFILE shared jika diminta; B |
| Cache tetap selama refresh | State availability, R07 | T03/T04/T21/T28; U/C | Rows/metrics/identity tak menjadi skeleton, avatar signing tidak menghambat Room; C/F |
| Fast unknown no flash; slow unknown section skeleton | R07/R08 | T27; fake clock20ms/250ms | No shimmer pada20ms; section-specific150ms onset saat250ms; konten siap tidak tertunda; C |
| Offline no cache bukan false empty | Coverage, S07/S08 | T02/T18/T23 | Terminal offline-unavailable; verified-empty marker terpisah, marker crash gap unknown; B/C |
| Account switch saat download/commit | S03/S04/S12, R02/R10 | T09; X/I/C/S, A→B→A, SDK expiry/login restore | Old epoch tidak commit/publish setelah transition linearization; result callback/selectionA tak mencapaiB; B |
| Pending upload + logout tidak silent deletion | S06/S12, R01 | T10/T11; offline & online & retained-owner login tests | No purge pending, no uploadA asB; retry/cancel/offline-exit sesuai approved policy; privacy limitations jelas; B |
| Interrupted/truncated/mutating snapshot | S07/S08, R03/R04 | T07/T23/T24/T29; remote1001+ rows, server cap<500 | Unverified graph tak replace/deactivate; unsafe template write ditahan sebelum request pertama; accurate partial/unverified outcomes; B |
| Consistent upload snapshot + ack | S05/S06, R05 | T11 plus same-ms/ABA/retry/child-only dirty mutation | Payload satu revision; old ack tak clear newer flags; tanpa revision proof gate tetap BLOCKED; B |
| Worker enqueue/process-death safety | S09/S10 | T25/T26; X/I/M, write after final scan, crash pre/post RPC/ack | Pending durable terschedule ulang saat eligible; idempotent identity, no lost wakeup/deadlock; B/G |
| No network append/retry | S11, R09 | T13/T16 + endpoint counters; I/C/X | Remote request count append/retry lokal=0; manual refresh terpisah; D/E |
| Paging no duplicate/missing IDs | R09/S11 | T14 + complete traversal oracle; I/C | Untuk stable revision/query, traversal IDs persis expected full filtered dataset. Jika dataset berubah, assert latest-generation oracle, bukan count lama; D/E |
| Picker valid lintas page/filter | S04, R10 | T12/T15; selected>60/>120, deactivate/context switch during confirm | Count full selection, no silent prune, revalidate seluruh IDs/exclusions; footer tetap, no duplicate delivery; E |
| Metrics/trusted auto-fill unchanged | D1, R05 | T17/T30; U/I | Empat calculated fields & trusted prefill equality terhadap baseline complete DB; mismatch Weekly Time tetap open; E/F/G |
| Visual/header/footer/dialog/navigation/timer | R08/D3/D9 | T19/T20/T21/T30; C/M | Header8, min48 controls, no clipped footer/IME, no scrim/timer/nav changes; mockup review terpisah; C–G |
| Paging3 justified | D4/D5, R09 |81/150/1500 same-device/variant benchmark + SQL parity | Report p50/p95 query/first-content, allocation/frame evidence, deterministic sort/search/subgroups/exclusions/count parity; D |
| Backend isolation dan protocol behavior | S03/S07/S12, R02/R03/R04 | T08/T09/T10/T24/T29; S akunA/B disposable | RLS/RPC reject/hide wrong owner, actual payload dan protocol limitations dilaporkan; G. Tanpa environment: BLOCKED, bukan PASS |

**Fixture minimum:**150 unique exercises, kategori/subgroup verified, inactive, duplicate names/unique IDs, literal `%`/`_`, Unicode/description panjang, exclusions dan selected di luar initial60. Demo42 tidak dipakai sebagai bukti paging150. Remote1001+ berbeda dari fixture UI/perf; semua sintetis atau disposable berizin.

**Bukti wajib saat kelak menjalankan test:** test ID/command, variant/device/API, dataset/revision, counter started/joined/completed/page count, assertions dan terminal outcomes, path log/screenshot/benchmark. Safety assertions harus menggunakan barriers/state, bukan sleep acak. Jangan menambah logging PII untuk memperlihatkan ownership.

## 7. Phase exit gates

| Phase | Exit gate yang dapat diuji | Status saat ini / aturan berhenti |
| --- | --- | --- |
| A | Baseline dirty diff tercatat; approval keputusan terkait; fixtures dan regression characterization tersedia; perf protocol disepakati | PLAN ONLY. Jangan mengubah baseline pengguna untuk membuat tests hijau |
| **B** | S01–S10/S12 dibuktikan local/concurrency: join shared flights, identity commit fence dan publication, consistent capture/revision ack, pending-safe logout, guarded unverified snapshots/template writes, reliable finalization/no lost wakeup. Degraded-sync UX disetujui | **BLOCKED untuk remote UI integration.** Test belum dijalankan, privacy/SDK epoch/revision proof belum selesai. Backend-dependent guarantees tetap terbuka sampai S-E2E; no UI workaround dapat menutup P0 |
| C | Four-axis reducers + fast/slow/cached/offline tests; no observer restart on refresh; exact section skeleton/reduced-motion/semantics; no fullscreen refresh skeleton | NOT_TESTED. Gate B wajib sebelum menghubungkan gesture ke remote; no false-zero/empty |
| D | DAO full-dataset filter/count/resolve parity, lexical order tests, invalidation;81/150/1500 benchmark + keputusan cost/benefit ditinjau | NOT_TESTED. Tidak mengklaim speedup. Jika tidak justified, pause dan review alternatif; tidak diam-diam ganti paging dengan take(fullList) |
| E | List/picker all load states,0 remote append, anchor/generation tests, selected/context validation dan result isolation, sticky footer | NOT_TESTED. Stop bila pilihan hilang/invalid silently atau result lintas owner |
| F | Home/Progress/Profile/History section/cache tests, scoped overlay gate, date/metrics parity; visual comparison agreed mockups | NOT_TESTED; D9 visual sign-off UNCONFIRMED. Tidak menutup PROG-SEM-01/PROG-VIS-02 |
| G | Unit/DAO/concurrency/Compose/build evidence per variant; device regression; authorized disposable E2E; unresolved limitations signed off | NOT_TESTED / E2E BLOCKED. Local PASS tidak menjadi Full Safe-Sync PASS |

**Phase B proof rule:** bila atomic session ordering atau safe acknowledgement memerlukan schema/backend yang belum disetujui, B tidak boleh diberi PASS dengan alasan UI sudah terlihat benar. Pisahkan persiapan visual stateless dari integrasi data; persiapan tersebut pun membutuhkan izin implementasi baru.

## 8. Rollback strategy

**PROPOSED, setelah implementasi diizinkan:**

1. Catat baseline working tree beserta tracked/untracked diff sebelum perubahan. Perubahan enam issue existing bukan rollback target v2.1.
2. Pisahkan safety B, loading C, dependency/query D, screen E/F dalam change sets yang bisa direview mandiri. Commit/push tetap memerlukan permintaan tersendiri.
3. Jika safety P0 gagal: hentikan integrasi/rollout. Jangan memulihkan jalur destructive lama sekadar agar sync terlihat berjalan; fail-safe disabled/degraded boundary dengan pending data tetap ada lebih aman daripada silent corruption.
4. Rollback renderer/pager tidak boleh menghapus Room/cache/pending/selection/draft. Old full-read API tetap kompatibel selama transisi; jangan clear DB untuk menghindari invalidation bug.
5. Schema tidak berubah dalam default plan. Jika dibutuhkan revision/coverage table, STOP dan ajukan migration+forward-compatibility plan. Jangan downgrade app/DB atau destructive fallback sebagai rollback.
6. Perubahan overlay hanya eligibility enam target; navigation/dialog lain/scrim0.50/header8/timer/notification tetap baseline. Revert terarah perubahan sendiri bila disetujui, bukan reset/checkout seluruh file pengguna.
7. Device tests hanya isolated diagnostic package dengan izin; no uninstall normal app/clear data/settings changes/Pencil mutations. Backend fixture cleanup hanya project disposable dan scope izin, bukan operasi produksi.

## 9. Explicit unresolved blockers

| Blocker | Apa yang belum selesai | Boundary yang ditahan |
| --- | --- | --- |
| U01 | Approval implementasi dan rekomendasi D1–D9 belum diberikan | Semua fase kode/build; dokumen ini bukan approval |
| U02 | Retention/privacy/offline-exit D2 dan account/active-session lifecycle belum disepakati/diuji | Safe logout, Phase B exit |
| U03 | Commit-fence linearization untuk SDK session transitions dan ABA belum terbukti | Account isolation claim, Phase B exit |
| U04 | Upload revision/ack proof termasuk timestamp collision; kebutuhan migration belum diputuskan | Pending-write safety, Phase B exit; bila perlu schema baru STOP |
| U05 | Complete/coherent remote read belum dijamin; unsafe template upload belum diguard; freshness trade-off belum disetujui | Destructive reconciliation/template replacement dan Full safe-sync claim |
| U06 | Authorized disposable Supabase environment/accountA/B belum tersedia di sesi ini | Runtime backend/RLS/protocol E2E, Phase G remote verdict |
| U07 | Paging benchmark/parity dan pilihan dependency belum dijalankan/disetujui | Phase D/E integration; tidak menghalangi penulisan spesifikasi |
| U08 | Mockup final enam konteks belum seluruhnya dikonfirmasi; Progress semantic/visual backlog tetap open | Visual sign-off dan claim empat metrik sesuai kontrak |

## 10. Final Build Readiness Verdict

**BELUM SIAP UNTUK FULL BUILD.**

- **Perencanaan:** addendum dan baseline tersedia untuk review; bukan runtime verification.
- **Implementasi UI:** bersyarat, belum diizinkan; remote integration harus menunggu Phase B exit.
- **Keamanan end-to-end:** **UNCONFIRMED / BLOCKED** pada boundary identity/logout/protocol yang disebutkan; memilih mode konservatif belum sama dengan mengimplementasikan/menguji proteksi.
- **Pengujian implementasi v2.1:** **NOT_TESTED**. Tidak ada runtime test atau benchmark ditandai PASS dalam addendum ini.
- **Langkah berikut:** review/approve keputusan dan scope Phase A/B secara eksplisit; lalu jalankan per fase dengan bukti exit gate. Jangan langsung mengeksekusi seluruh fitur sekaligus.

### MUST FIX BEFORE BUILD

Approval boundary yang relevan; pending-safe logout/privacy; complete session/commit fence dan result isolation; consistent upload snapshot/revision ack; conservative snapshot/template-write guard; shared single-flight dan durable scheduling proof. Tidak ada scope backend/schema tersirat.

### SHOULD IMPROVE

Section availability dan no-flicker observer lifecycle; shared neutral shimmer/reduced-motion; stable anchor dan footer; redacted request counters; benchmark/parity dan final mockup comparison.

### SAFE TO RETAIN

Room sebagai presentation source, aggregate transactional import/pending guards sebagai fondasi, independent catalog demand, Navigation3 contracts, dirty editor/session snapshot boundary, completed-only volume/trusted auto-fill, header8dp, modal/native-notification/timer behavior existing. Semua tetap membutuhkan regression setelah perubahan baru.

### OUT OF SCOPE

Implementasi pada update dokumen ini; Full Build; commit/push/reset; schema/backend/RLS migration; destructive test produksi; global history atau pagination halaman opsional; redesign/metrik Progress tersembunyi; perubahan timer/alarm/workout rules/nav/desain.

**STOP — dokumen v2.1 selesai untuk review. Tunggu approval eksplisit sebelum Build.**
