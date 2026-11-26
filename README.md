<div align="center">

# Olyuno

**Pemutar musik Android dengan lirik tersinkron, Dynamic Island, dan asisten AI untuk membedah makna lagu.**

🇮🇩 Bahasa Indonesia · [🇬🇧 English](README.en.md)

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Buatan Indonesia](https://img.shields.io/badge/Buatan-Indonesia%20%F0%9F%87%AE%F0%9F%87%A9-D52B1E)

</div>

---

Olyuno dibuat oleh pengembang Indonesia 🇮🇩, dengan antarmuka berbahasa Indonesia sebagai bahasa utama.

## Fitur

**Memutar & menemukan musik**
- Beranda dengan rak **Pilihan cepat**, **Sedang trending**, **Paling sering diputar**, **Dengarkan lagi**, **Rilis baru**, dan **Mirip dengan [artis]**, plus chip genre cepat (Feel good, Sad, Romance, dan lainnya).
- **Cari lagu**: ucapkan atau nyanyikan potongan lirik, Olyuno mencari lagunya.
- Pemutaran lagu perangkat dan unduhan untuk didengar offline.

**Lirik**
- Lirik tersinkron per baris atau per kata dari beberapa layanan (LRCLIB, Better Lyrics, BiniLyrics, Lyrics Plus, pilihan "Otomatis").
- Menu lirik: muat ulang, salin, ikuti per lirik, terjemahkan, bagikan, dan **Cari lirik**.

**Tanya AI (Olyuno AI)**
- Tanyakan makna lagu, rekomendasi, atau isi album dan playlist.
- Olyuno AI memakai alat sungguhan (cari lagu, baca lirik, cari artis/album/playlist) dan hanya menampilkan indikator saat alat itu benar-benar berjalan.
- Dua model: **Olyuno 3.8** dan **Olyuno 4**, dengan riwayat obrolan.

**Pemutar & suara**
- Kartu **Dynamic Island** saat aplikasi ditinggalkan.
- Ekualiser, penguat bass, **Normalisasi volume**, **Lewati bagian hening**, dan timer tidur.

**Tampilan & lainnya**
- Tema Putih, Hitam, atau Kustom, mode Kaca (liquid glass), dan animasi smooth yang bisa dimatikan.
- Skeleton shimmer saat memuat, dan tombol "Coba lagi" di setiap tampilan gagal muat.
- Halaman **Statistik** dengarmu.
- **Pengumuman** di halaman Informasi, lengkap dengan notifikasi.
- Bahasa Indonesia dan English.

## Pasang

Unduh APK terbaru dari tab [Releases](../../releases). Kalau belum ada rilis, buka tab **Actions**, pilih workflow **Build APK**, lalu unduh artifact `Olyuno-apk`.

Butuh Android 8.0 (API 26) atau lebih baru. Izinkan notifikasi supaya kontrol media dan pengumuman muncul.

## Build sendiri

Prasyarat: JDK 17 dan Android SDK.

```bash
git clone https://github.com/Alfin-Olyuno/olyuno.git
cd olyuno
./gradlew assembleDebug
```

APK debug ada di `app/build/outputs/apk/debug/`.

Build release memakai keystore dari environment variable atau GitHub Secrets (lihat `.github/workflows/build.yml`). Keystore tidak disimpan di repo.

## Struktur proyek

| Folder | Isi |
| --- | --- |
| `app/src/main/java/app/tunes/ui` | Layar dan komponen Jetpack Compose |
| `.../player` | Pemutaran (Media3), Dynamic Island, ekualiser, notifikasi |
| `.../lyrics` | Layanan lirik dan pencocokan |
| `.../ai` | Olyuno AI: obrolan, alat (function calling), analisis lirik |
| `.../data` | Penyimpanan lokal (Room), Informasi, statistik |
| `.../i18n` | Terjemahan Indonesia dan English |

## Teknologi

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer, Room, OkHttp, Coil, WorkManager, kotlinx.serialization.

## Catatan perubahan

Lihat [CHANGELOG.md](CHANGELOG.md).

## Kontribusi

Olyuno dikembangkan oleh satu orang. Bug, saran, atau ide fitur sangat disambut lewat [Issues](../../issues).

## Penafian

Olyuno adalah proyek independen dan tidak berafiliasi dengan YouTube, Google, atau layanan lirik mana pun. Seluruh konten musik dan lirik adalah milik pemegang haknya masing-masing.
