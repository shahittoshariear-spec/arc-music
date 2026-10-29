# A(rc) Music

A music player for Android with an on-device YouTube converter inside it. Search
YouTube, stream a song, or keep it as **MP3 (320 kbps)**, **FLAC**, or **Original** —
then play it from a library that lives entirely on the phone.

Everything runs on the device: the app bundles
[yt-dlp](https://github.com/yt-dlp/yt-dlp) (via
[youtubedl-android](https://github.com/yausername/youtubedl-android)) plus FFmpeg, so
there is no server and no account. Once installed it works offline, apart from search,
streaming and the download itself.

## Features

* **Player-first layout** — Library, Search and Settings in a floating pill at the
  bottom, with Search in the middle. A mini player sits above it, and tapping it opens
  the full Now Playing screen with artwork, seeking and skip controls.
* **Online / offline mode** — online searches all of YouTube; offline searches only the
  music already saved on the phone.
* **Play before you save** — tapping a search result resolves a direct audio stream and
  plays it immediately, without downloading anything.
* **Background playback** — audio runs in a `MediaSessionService`, so it keeps going
  when you leave the app. Controls appear in the notification, on the lock screen and
  on headset buttons, with artwork.
* **Up Next, shuffle and repeat** — the queue is visible and editable: jump to any
  track, remove one, or add a library song with "Play next" / "Add to queue".
* **Favorites** — heart a song in the library or on Now Playing, then filter the
  library to just those.
* **Local playlists** — create playlists from your own library, add songs with the
  ⋯ menu, rename or delete them, and play a whole playlist.
* **Lyrics** — timed lyrics from LRCLIB when an entry exists, with the current line
  following the song; otherwise paste your own in the editor.
* **Sleep timer** — pause automatically after 15, 30 or 60 minutes.
* **Built-in downloads** — a downloads sheet shows every job's real progress and lets
  you cancel or retry, instead of leaving the queue invisible.
* **Google Play-style download** — a circular ring fills with real conversion progress
  and becomes a check when the song is in the library. Tap it to choose MP3, FLAC or
  Original.
* **Playlists** — paste a playlist link into search, or share one to the app, and the
  whole list downloads as one job into a folder named after the playlist, in album
  order.
* **Sign in to YouTube** — a one-time Google sign-in unlocks private, liked and
  age-restricted videos. After that your liked videos, Watch Later and playlists are
  shown natively inside the app — there is no web browser to browse.
* **Tag editor** — rewrite title, artist, album, album artist, genre, year and track,
  and set album art from the gallery or from the video thumbnail. Tags are rewritten in
  place, so there is still one file per song.
* **Self-healing engine** — when a conversion fails with a stale-extractor error the app
  updates yt-dlp by itself and retries the item once.
* **SponsorBlock** (optional, in Settings) — trims sponsor reads, intros and outros out
  of the audio.
* **Plain-language failures** — bot checks, age gates, network problems and dead videos
  are explained, with a sign-in shortcut where signing in is the fix.

## Install

Download an APK from the
[latest release](https://github.com/shahittoshariear-spec/yt-converter/releases):

| File | Use it when |
| --- | --- |
| `app-arm64-v8a-release.apk` | **Recommended.** Any phone made since ~2017. |
| `app-armeabi-v7a-release.apk` | Older 32-bit-only devices. |
| `app-universal-release.apk` | You are not sure; works on either. |

Copy it to the phone, tap it, and allow "install unknown apps" for whatever app you
opened it from. The APK is signed with the key in `app/keystore/release.jks`.

The app is also a `text/plain` share target: sharing a YouTube link to it opens the
search tab with that video resolved and ready to play or download.

## Build

Requirements: JDK 17+ and an Android SDK with **platform 37** and **build-tools 36.0.0**.
`local.properties` already points at the SDK on this machine.

```sh
JAVA_HOME=/path/to/jdk ./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` (git-ignored); copy
`keystore.properties.example` to get started. If that file is missing, the release
build simply comes out unsigned — useful for a quick `:app:assembleDebug` instead.

### Toolchain notes

This project targets AGP 9, which **compiles Kotlin itself**:

* Do **not** apply `org.jetbrains.kotlin.android` — AGP 9 raises an error if you do.
  Built-in Kotlin (`android.builtInKotlin`, on by default) is used instead.
* AGP 9.3.2 ships Kotlin Gradle plugin **2.2.10**, so the Compose compiler plugin is
  pinned to 2.2.10 to match.
* Libraries therefore have to be built against Kotlin ≤ 2.3. Coil is pinned to 3.3.0
  for exactly this reason — 3.4+ is compiled against Kotlin 2.3/2.4 and the 2.2.10
  compiler cannot read its metadata.
* `packaging.jniLibs.useLegacyPackaging = true` is required: the bundled Python and
  FFmpeg payloads are shipped as compressed `.so` archives that the app unpacks to disk
  at runtime, which only works when native libraries are extracted from the APK.

## How it works

```
MainActivity ──▶ PlayerScreen  (Library · Search · Settings)
                       │
                       ├──▶ PlaybackController ──▶ ExoPlayer
                       │            ▲                  │
                       │            │        PlaybackService (MediaSession)
                       │            │        notification · lock screen · headset
                       ▼
                 MainViewModel ──▶ DownloadBus (StateFlow queue)
                       │                  ▲
                       │ probe / search   │ progress updates
                       ▼                  │
                 YtDlpEngine ◀──── DownloadService (foreground, dataSync)
                       │                  │
               yt-dlp + FFmpeg            ▼
                                MediaPublisher ──▶ Music/<folder> via MediaStore
```

* **Search** runs `ytsearch20:<query>` with `--flat-playlist --dump-json`, so one
  extraction pass returns twenty rows' worth of id, title, channel and duration.
* **Streaming** resolves a direct URL with `-g -f bestaudio[ext=m4a]/…`, preferring m4a
  because Android decodes it everywhere. Nothing is written to disk.
* **Downloading** is unchanged: `DownloadService` drains the queue sequentially, which
  is what makes per-item cancel and retry possible, and holds a foreground
  notification for the whole run.
* `runWithRepair` catches a stale-extractor failure, calls `updateYoutubeDL`, wipes the
  partial file and retries the item exactly once. A bot check that survives that is
  reported as a sign-in problem instead of an engine problem.
* Files are inserted into `MediaStore` with `IS_PENDING` so they appear in every music
  player, under `Music/<Save to>/`.
* History is a small JSON blob in `SharedPreferences`; deleting an entry also removes
  the file. Each record stores the page it came from, which is how search results know
  a song is already saved.

## Notes and limitations

* **Online playback can be refused.** If YouTube will not serve the stream, downloading
  usually still works, and the saved file then plays from the library.
* **The download queue is in memory only.** If the process is killed mid-run the pending
  queue is lost. Finished files are already safe in the media library and in history.
* **FLAC and 320 kbps MP3 cannot add quality that was never there.** YouTube serves
  lossy audio (Opus/AAC, roughly 128–160 kbps). Re-encoding to 320 kbps MP3 only makes
  the file bigger; FLAC rewraps the same lossy audio in a lossless container.
  **Original** is the only setting that keeps true source quality and is also the
  smallest of the three.
* **Not every Apple Music feature is here.** There is no equalizer, no lossless or
  Atmos playback, and no radio. Lyrics depend on LRCLIB having an entry, which many
  songs do not; the editor is there for those.
* `minSdk` is 29 (Android 10) because the app writes through `MediaStore` scoped
  storage instead of requesting legacy storage permissions.
* Downloading content may conflict with YouTube's Terms of Service and with copyright
  law depending on what you download and where you are. This is a personal-use tool;
  use it accordingly. It also cannot be published on Google Play, which prohibits
  video-downloading apps.

## Status

Built and verified by compiling and by exercising the yt-dlp search and stream paths on
a desktop, but **not yet run on a physical Android device**. Treat 1.0 as a beta.

## Credits

* The player layout is inspired by [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer).
  No PixelPlayer code is used: its licence is non-standard, so everything here is this
  repository's own code.
* [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
  [youtubedl-android](https://github.com/yausername/youtubedl-android) do the extraction
  and encoding. Playback uses [Media3](https://developer.android.com/media/media3) /
  ExoPlayer and its `MediaSessionService`. Lyrics come from
  [LRCLIB](https://lrclib.net).

## Renaming

The app id (`com.arcmusic`), label (`A(rc) Music`), and the default save folder
(`A(rc) Music`) are all easy to change in `app/build.gradle.kts`,
`app/src/main/res/values/strings.xml`, and
`app/src/main/java/com/arcmusic/data/SettingsRepository.kt`.
