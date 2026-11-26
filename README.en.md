<div align="center">

# Olyuno

**An Android music player with synced lyrics, a Dynamic Island card, and an AI assistant that explains what songs mean.**

[🇮🇩 Bahasa Indonesia](README.md) · 🇬🇧 English

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-1.0.2-2E4A8A)

</div>

---

## Features

**Playing & discovering music**
- A home screen with **Quick picks**, **Trending now**, **Most played**, **Listen again**, **New releases**, and **Similar to [artist]** shelves, plus quick genre chips (Feel good, Sad, Romance, and more).
- **Find a song**: say or sing a snippet of the lyrics and Olyuno finds the track.
- Playback of songs stored on your device, and downloads for offline listening.

**Lyrics**
- Synced lyrics, line by line or word by word, from several providers (LRCLIB, Better Lyrics, BiniLyrics, Lyrics Plus, with an "Auto" option).
- Lyrics menu: reload, copy, follow along per line, translate, share, and **Search lyrics**.

**Ask AI (Olyuno AI)**
- Ask what a song means, get recommendations, or see what is inside an album or playlist.
- Olyuno AI uses real tools (search songs, read lyrics, search artists/albums/playlists) and shows its activity indicator only while a tool is actually running.
- Two models, **Olyuno 3.8** and **Olyuno 4**, with chat history.

**Player & sound**
- A **Dynamic Island** card when you leave the app.
- Equalizer, bass boost, **Volume normalization**, **Skip silence**, and a sleep timer.

**Look & feel**
- White, Black, or Custom themes, a Glass (liquid glass) mode, and smooth animations you can switch off.
- Shimmer skeletons while loading, and a "Try again" button on every failed-to-load view.
- A **Statistics** page for your listening.
- **Announcements** on the Information page, with notifications.
- Indonesian and English.

## Install

Download the latest APK from the [Releases](../../releases) tab. If there is no release yet, open the **Actions** tab, pick the **Build APK** workflow, and download the `Olyuno-apk` artifact.

Requires Android 8.0 (API 26) or newer. Allow notifications so media controls and announcements can appear.

## Build it yourself

Prerequisites: JDK 17 and the Android SDK.

```bash
git clone https://github.com/Alfin-Olyuno/olyuno.git
cd olyuno
./gradlew assembleDebug
```

The debug APK ends up in `app/build/outputs/apk/debug/`.

Release builds read the keystore from environment variables or GitHub Secrets (see `.github/workflows/build.yml`). The keystore is not stored in the repo.

## Project structure

| Folder | Contents |
| --- | --- |
| `app/src/main/java/app/tunes/ui` | Jetpack Compose screens and components |
| `.../player` | Playback (Media3), Dynamic Island, equalizer, notifications |
| `.../innertube` | YouTube Music client |
| `.../lyrics` | Lyrics providers and matching |
| `.../ai` | Olyuno AI: chat, tools (function calling), lyrics analysis |
| `.../data` | Local storage (Room), Information, statistics |
| `.../i18n` | Indonesian and English translations |

## Built with

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer, Room, OkHttp, Coil, WorkManager, kotlinx.serialization.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) (written in Indonesian).

## Contributing

Olyuno is built by one person. Bug reports, suggestions, and feature ideas are very welcome through [Issues](../../issues).

## Disclaimer

Olyuno is an independent project and is not affiliated with YouTube, Google, or any lyrics provider. All music and lyrics belong to their respective rights holders.
