# Changelog Olyuno

Catatan semua perubahan, perbaikan, dan fitur baru Olyuno, dari yang terbaru ke yang lama.

Nomor versi di sini dan di nama zip = `versionName` APK di `app/build.gradle.kts` (sekarang **V1.0.2**). Tidak ada nomor versi lain.

---

## Cara pakai file ini

### 1. Catat setiap perubahan
Tiap ada perubahan kode, tulis satu baris di bagian versi yang paling atas (lihat bagian 2 untuk menentukan versinya), di kategori yang cocok.

| Kategori | Dipakai untuk |
| --- | --- |
| Ditambahkan | Fitur atau bagian baru |
| Diubah | Perilaku atau tampilan lama yang berubah |
| Diperbaiki | Bug yang sudah dibereskan |
| Dihapus | Fitur atau pengaturan yang dibuang |
| Perlu diperhatikan | Hal penting: perubahan database, ekspor/impor, pengaturan tersimpan, atau belum diuji |

Aturan penulisan:
- Satu baris = satu perubahan, bahasa sederhana, tulis dampaknya bagi pengguna.
- Sebut nama file dalam tanda kutip balik, misalnya `HomeScreen.kt`.
- Tulis teks UI persis seperti di aplikasi, misalnya "Dengarkan lagi".

### 2. Nama zip dan nomor versi
**Versi APK hanya diubah kalau pengguna memintanya secara langsung** (misalnya "naikkan ke 1.0.2" atau "ubah versi apk"). Tanpa permintaan itu, anggap versi **tidak dinaikkan**: `versionName` dan `versionCode` tetap, perubahan dicatat di bagian versi yang sedang berjalan, dan nama zip tetap. Fitur atau perbaikan sebesar apa pun tidak menjadi alasan untuk menaikkan versi sendiri.

**Versi APK dinaikkan** (`versionName` di `app/build.gradle.kts` diubah, misalnya 1.0.1 menjadi 1.0.2):
1. Buat bagian baru di paling atas dengan judul `V1.0.2 (tanggal)`.
2. Ubah nama zip menjadi `olyuno-V1.0.2.zip`.
3. Naikkan juga `versionCode`, supaya APK baru bisa menimpa yang lama.

**Versi APK tidak dinaikkan** (hanya perbaikan atau tambahan kecil di versi yang sama):
1. Jangan buat bagian versi baru. Tambahkan baris perubahan ke bagian versi yang sudah ada (misalnya `V1.0.1`).
2. Nama zip **tidak diubah**, tetap `olyuno-V1.0.1.zip`.
3. Jangan ubah `versionName` maupun `versionCode`.

Aturan singkat: nama zip dan judul versi di sini selalu sama dengan `versionName` APK. Jangan pernah memakai nomor yang tidak ada di APK.

### Template bagian versi
```markdown
## V1.0.2 (2026-12-01)

### Ditambahkan
- ...

### Diubah
- ...

### Diperbaiki
- ...

### Dihapus
- ...

### Perlu diperhatikan
- ...
```

---

## V1.0.2 (2026-11-26)

### Ditambahkan
- Kartu "Olyuno AI" di Beranda, di bawah deretan genre dan di atas "Lanjutkan". Judul + panah membuka Olyuno AI dengan obrolan kosong. Di bawahnya ada chip saran yang bisa digeser: saran menurut jam (pagi/siang/sore/malam, sama dengan sapaan), "Mirip lagu terakhir" dan "Makna lagu terakhir" (hanya kalau sudah ada riwayat putar), dan "Buatkan playlist". Mengetuk chip membuka obrolan baru yang langsung mengirim pertanyaannya; ketukan ganda cepat tidak membuat obrolan kedua. Teks saran ikut bahasa aplikasi (terjemahan English ditambahkan). Kode di `HomeScreen.kt` (`AiHomeCard`), `OlyunoRoot.kt`, `EnglishStrings.kt`. Belum diuji build di sini: perlu dicek di perangkat.
- Model AI buatan sendiri **Olyuno 1.0** di pilihan model Olyuno AI (kanan atas). Berjalan di server Olyuno sendiri (hasil fine-tune), dan kalau server itu tidak tersedia, permintaan otomatis dialihkan ke Gemini tanpa perlu berbuat apa-apa. Kode di `AiPrefsStore.kt`.
- Pengumuman di halaman Informasi. Pengumuman yang dikirim dari aplikasi admin (tab "Pengumuman") muncul di daftar Informasi, terbaru di atas, dengan titik "belum dibaca" dan ikut hitungan badge. Daftar diambil dari server saat aplikasi dibuka dan saat halaman Informasi dibuka, disimpan di cache supaya tetap tampil saat offline, dan pesan "Selamat Datang" tetap ada di bawahnya. Kalau bahasa aplikasi English, yang tampil versi terjemahannya; kalau terjemahan tidak ada, tampil teks Indonesia. Kode di `InfoStore.kt`, `InfoPage.kt`, `OlyunoRoot.kt`, `MainViewModel.kt`.
- Notifikasi pengumuman. Saat kamu mengirim pengumuman dari aplikasi admin, pengguna Olyuno mendapat notifikasi "Pengumuman" (judul + cuplikan isi); diketuk membuka halaman Informasi. Tanpa Firebase: `AnnouncementWorker` (WorkManager) memeriksa server tiap sekitar 15 menit, jadi notifikasi bisa terlambat beberapa menit (lebih lama kalau HP hemat daya). Tidak muncul kalau aplikasi sedang terbuka (titik "belum dibaca" sudah terlihat), dan pengumuman lama tidak dimunculkan saat pertama kali. Kode di `AnnouncementNotifier.kt`, `InfoStore.kt` (`takeUnnotified`), `OlyunoApp.kt`, `MainActivity.kt`, `OlyunoRoot.kt`, ikon `ic_notif_announce.xml`. Dependensi baru: `androidx.work:work-runtime-ktx` 2.9.1.
- Saklar "Pengumuman baru" di Pengaturan > Notifikasi (bagian "Pengumuman", bawaan nyala) untuk mematikan notifikasi pengumuman. Terjemahan English ditambahkan di `EnglishStrings.kt`.
- Terjemahan bahasa Inggris "Ketuk mikrofon" ("Tap the microphone") di `EnglishStrings.kt`.
- Bagian "Rilis baru" di Beranda, di bawah "Andalanmu" dan di atas "Mirip dengan". Isinya album, single, dan EP tahun ini (Januari-Februari: sejak tahun lalu) dari 8 artis yang paling sering kamu putar, diambil dari halaman artis YouTube Music. Terbaru di depan, maksimal 3 rilisan per artis dan 16 kartu. Ketuk kartu membuka halaman albumnya. Memakai skeleton shimmer saat memuat, dan disembunyikan kalau belum ada riwayat putar atau tidak ada rilisan baru. Ikut dimuat ulang oleh tombol muat ulang Beranda. Terjemahan "New releases" ditambahkan di `EnglishStrings.kt`.
- Bagian "Dengarkan lagi" di Beranda, tepat di bawah "Paling sering diputar". Isinya lagu yang pernah diputar minimal 2 kali tapi terakhir diputar lebih dari 14 hari lalu, paling sering diputar di depan, maksimal 30 lagu. Lagu yang sudah tampil di "Paling sering diputar" tidak diulang. Disembunyikan kalau tidak ada yang memenuhi syarat.
- Terjemahan bahasa Inggris "Listen again" untuk "Dengarkan lagi" di `EnglishStrings.kt`.

### Diperbaiki
- Notifikasi pengumuman dari aplikasi admin sering tidak muncul. Penyebab: saat Olyuno dibuka (dan saat halaman Informasi dibuka) pengumuman baru langsung ditandai "sudah ditangani" tanpa notifikasi, jadi pekerja latar belakang (~15 menit sekali) tidak punya apa-apa lagi untuk diberitahukan; notifikasi juga sengaja ditahan selama aplikasi terbuka. Sekarang pengumuman baru diperiksa tiap menit selama Olyuno tampil dan langsung muncul sebagai notifikasi, saat membuka Olyuno pengumuman yang belum diberi notifikasi ikut dimunculkan, dan pekerja latar belakang tidak lagi menahannya saat aplikasi terbuka. Membuka halaman Informasi tetap hanya menandai (tanpa notifikasi). Kode di `MainViewModel.kt` (`refreshInfo`), `OlyunoRoot.kt`, `AnnouncementNotifier.kt`.
- Kanal notifikasi "Pengumuman" dinaikkan ke kepentingan tinggi (kanal baru `announcements_v2`, kanal lama dihapus) supaya muncul sebagai pop-up, bukan hanya ikon di bilah status.
- Indikator "Mencari lagu" / "Mencari playlist" / "Mencari artis" / "Mencari album" di Tanya AI tidak lagi muncul hanya karena pertanyaan memuat kata seperti "lagu" atau "makna". Indikator kini hanya tampil saat Olyuno benar-benar menjalankan alat, dengan nama kegiatan yang sesuai (mis. "Membaca lirik", "Mencari lagu dari lirik"), dan padam begitu alat selesai. Indikator tebakan di samping "Ringkasan" juga dihapus. Kode di `LyricsAnalysisPage.kt`, `LyricsAnalyzer.kt`.

### Diubah
- Deskripsi model Olyuno AI diperjelas supaya tidak salah paham: Olyuno 1.0 = model buatan Olyuno sendiri yang berjalan lokal di server Olyuno (Gemini hanya sebagai cadangan kalau server mati), Olyuno 2.0 = bukan buatan Olyuno sendiri, memakai Gemini dari Google. Pernyataan data dikirim ke Google dipertegas: hanya saat memakai Olyuno 2.0 atau saat dialihkan. Kode di `AboutPage.kt`, `EnglishStrings.kt`, `LyricsAnalyzer.kt`.
- Nama model **Olyuno 3.5** diganti menjadi **Olyuno 2.0** di pilihan model Olyuno AI (kanan atas), teks halaman Tentang (dan terjemahan English), serta panduan aplikasi di `LyricsAnalyzer.kt`. ID model Gemini (`gemini-3.5-flash`) tidak berubah. Kunci penyimpanan internal tetap `Olyuno35`, jadi pengguna yang sebelumnya memilih model ini tetap memakainya. Kode di `AiPrefsStore.kt`, `AboutPage.kt`, `EnglishStrings.kt`, `LyricsAnalyzer.kt`.
- Teks Olyuno AI di halaman Tentang (dan terjemahan English) diperbarui: menjelaskan dua model dan bahwa data dikirim ke server Olyuno, serta ke Google hanya bila memakai Olyuno 3.5 atau saat dialihkan. Panduan aplikasi untuk Olyuno AI di `LyricsAnalyzer.kt` ikut disesuaikan. Kode di `AboutPage.kt`, `EnglishStrings.kt`.
- Model bawaan Olyuno AI sekarang **Olyuno 1.0**. Pilihan model tinggal dua: Olyuno 1.0 dan Olyuno 3.5. Pengguna yang sebelumnya memilih Olyuno 3.5 tetap memakai 3.5; yang sebelumnya memilih 3.8 atau 4 pindah ke Olyuno 1.0. Kode di `AiPrefsStore.kt`.
- Tanya AI (Olyuno AI): setiap pertanyaan tentang lagu, artis, album, playlist, atau lirik sekarang diarahkan agar dijawab setelah Olyuno memakai alat (mis. "apa arti lagu Snowman" membaca liriknya dulu lewat alat lirik), bukan dari ingatan. Alat yang dipakai dipilih sendiri oleh Olyuno. Pertanyaan umum yang tidak soal musik tetap dijawab langsung. Kode di `LyricsAnalyzer.kt` (`SYSTEM_TOOLS`).
- Halaman Cari lagu (`SongFinderPage.kt`): teks "Cari lagu" yang besar di tengah dihapus, dan teks kecil "Ketuk mikrofon, lalu nyanyikan atau ucapkan lirik lagunya" diganti "Ketuk mikrofon" dengan ukuran dan posisi teks "Cari lagu" tadi. Saat mendengarkan, teks di posisi itu menjadi "Mendengarkan…" dan teks "Ketuk lagi kalau sudah selesai" dihapus (teks kecil di bawahnya hanya muncul untuk hasil suara sementara atau petunjuk coba lagi). Judul "Cari lagu" di bar atas tidak berubah.
- Halaman Cari lagu: tiga tombol bahasa suara (Otomatis, Indonesia, English) digabung menjadi satu tombol "Otomatis >" di bawah teks "Ketuk mikrofon". Diketuk membuka dropdown berisi tiga pilihan dengan centang di yang aktif. Pilihan tetap tersimpan antar-sesi, dan tombol yang sama dipakai di tampilan hasil.
- "Mirip dengan [artis]" di Beranda sekarang menampilkan skeleton shimmer (header + deretan avatar bulat) selama memuat, sebelumnya kosong sampai datanya siap. Ukurannya sama dengan tampilan asli sehingga tidak ada loncatan layout, dan mengikuti pengaturan "Kilau memuat" di Tema.
- Nama zip dan nomor versi di changelog disamakan dengan versi APK (V1.0.2).
- Versi APK (`versionName`) dinaikkan ke 1.0.2 di `app/build.gradle.kts`; `versionCode` tetap 1.
- Kategori notifikasi "Pengumuman" sekarang dibuat sejak aplikasi dibuka (`AnnouncementNotifier.kt`), jadi langsung terlihat di pengaturan notifikasi sistem, tidak menunggu pengumuman pertama.

### Dihapus
- Pilihan model Olyuno 3.8 dan Olyuno 4 dari Olyuno AI.

### Perlu diperhatikan
- Id model `olyuno-1.0` harus ada di `app_settings.allowed_models` dan `app_settings.local_llm_models`, dan edge function `ai-proxy` v7 (cadangan `olyuno-1.0` ke `gemini-3.5-flash`). Sudah dipasang di server. Olyuno 3.5 sekarang murni Gemini (tidak lagi lewat server lokal).
- Tanda tangan APK diganti ke keystore tetap yang baru (disimpan di GitHub Secrets repo Olyuno dan Olyuno Admin). Build sebelumnya memakai debug key sementara yang berubah di setiap build. Karena itu pemasangan V1.0.2 pertama kali **harus menghapus Olyuno lama dulu** (cadangkan data lewat ekspor), dan sesudahnya update berikutnya bisa langsung menimpa tanpa hapus. File keystore dan kata sandinya wajib disimpan: kalau hilang, update tanpa hapus tidak mungkin lagi.
- Pengumuman memakai tabel `announcements` di Supabase (kolom terjemahan `title_en`, `preview_en`, `blocks_en` ditambahkan) dan edge function `admin-api` v2 (aksi `announce_send`, `announce_list`, `announce_delete`). Aplikasi admin terpisah dan perlu dipasang ulang untuk tab "Pengumuman" (kolom Judul, kosong = "Pengumuman"; edge function `admin-api` sekarang v3). Terjemahan English dibuat otomatis lewat Gemini saat pengumuman dikirim; kalau gagal, pengumuman tetap terkirim tanpa English.
- Tidak ada perubahan skema database: "Dengarkan lagi" hanya menambah query baru (`replayPool`) di `Database.kt`, jadi data lama aman dan tidak perlu migrasi.
- Batas "Dengarkan lagi" bisa diatur lewat `REPLAY_MIN_COUNT` (minimal putaran) dan `REPLAY_GAP_DAYS` (hari tidak diputar) di `Database.kt`.
- "Rilis baru" hanya memakai tahun rilis dari YouTube Music (tanggal lengkap tidak tersedia di halaman artis), jadi urutannya per tahun, lalu menurut artis yang lebih sering diputar. Kartu yang tidak punya tahun dilewati. Batas jumlah artis, kartu, dan rilisan per artis ada di `NEW_RELEASE_ARTISTS`, `MAX_NEW_RELEASES` (`MainViewModel.kt`) dan `MAX_NEW_RELEASES_PER_ARTIST` (`Browse.kt`).
- Belum diuji build di perangkat saat catatan ini ditulis.
