# IMFIT Features

Dokumen ini merangkum fitur yang tersedia berdasarkan implementasi kode saat ini. Fitur dapat berbeda menurut build flavor `demo` dan `production`.

| Fitur | Penjelasan singkat |
| --- | --- |
| Autentikasi | Pengguna dapat mendaftar dan masuk menggunakan email serta password. Form registrasi memvalidasi nama, email, password, konfirmasi password, dan tanggal lahir. |
| Sesi login dan logout | Splash screen memeriksa status login untuk menentukan halaman awal. Pengguna dapat logout dari halaman profil. |
| Foto profil saat registrasi | Pengguna dapat mengambil foto dari kamera atau memilih dari galeri saat membuat akun. Pada production, foto dapat diunggah dan ditampilkan menggunakan URL avatar. |
| Dashboard workout | Halaman utama menampilkan sapaan pengguna, workout terakhir, daftar template workout, dan status sesi workout yang sedang aktif. |
| Membuat template workout | Pengguna dapat membuat template kosong dari halaman utama dengan memasukkan nama workout. |
| Mengelola template workout | Pengguna dapat melihat detail template, mengubah nama template, menambah atau menghapus exercise, mengatur set, repetisi, dan waktu istirahat, serta menghapus template. |
| Perpustakaan exercise | Pengguna dapat menelusuri exercise berdasarkan kategori otot dan melihat detail dasar setiap exercise. |
| Memilih exercise | Pengguna dapat mencari, memfilter berdasarkan kategori otot, memilih beberapa exercise, lalu menambahkannya ke template tanpa duplikasi. |
| Sesi workout aktif | Pengguna dapat memulai atau melanjutkan workout, mengisi berat dan repetisi, menyelesaikan set, menambah atau menghapus set, pause/resume, membatalkan, dan menyelesaikan sesi. |
| Prefill berat terakhir | Saat memulai workout baru, berat set dapat diisi dari berat workout terakhir untuk exercise yang sama apabila nilainya masih kosong. |
| Timer workout dan istirahat | Aplikasi menghitung durasi workout di luar waktu pause. Menyelesaikan set dapat memulai timer istirahat yang dapat dilewati atau diatur ulang untuk sesi aktif. |
| Notifikasi workout aktif | Foreground service menampilkan notifikasi berisi waktu workout, progres set, volume, serta aksi pause/resume, stop, dan skip rest. Notifikasi dapat membuka kembali sesi aktif. |
| Ringkasan workout | Setelah workout selesai, aplikasi menyimpan log dan membuka halaman ringkasan hasil sesi. |
| Progress dan riwayat | Pengguna dapat melihat volume total, durasi workout mingguan, kalender aktivitas, riwayat per tanggal, dan detail exercise dari workout yang telah selesai. |
| Profil dan preferensi | Halaman profil menampilkan data akun dan avatar. Pengguna dapat mengganti tema terang/gelap serta bahasa Indonesia/Inggris; preferensi disimpan secara lokal. |
| Penyimpanan lokal dan sinkronisasi cloud | Pada flavor `production`, data workout disimpan lokal di Room terlebih dahulu lalu perubahan tertunda dapat disinkronkan ke Supabase ketika jaringan tersedia. |
| Mode demo | Pada flavor `demo`, aplikasi memakai data in-memory dan akun demo otomatis untuk mencoba alur aplikasi tanpa backend production. |

## Catatan

- Layar pembuatan template terpisah dan fitur edit profil belum terhubung sebagai alur pengguna aktif.
- Rencana backup manual, fitur sosial, Wear OS, AI coach, ekspor data, dan dashboard web tidak termasuk dalam daftar karena belum merupakan implementasi aktif.
