# abcd design decisions

Format: date · decision · alternatives considered · reason.

## Codebase study (stage 2, 2026-11-16)

Findings about Metrolist that shape the DJ design. Paths are under
`androidApp/src/main/kotlin/com/metrolist/android/` unless they start with `shared/`.

**Player and crossfade.** The brief's assumption is right, with a twist.
- `playback/ExoPlaybackPlayer.kt` (≈3.8k lines) implements `AndroidPlaybackPlayer` and owns **two**
  ExoPlayers: `exo` (primary) and `nextPlayer` (preloaded/crossfade). On a crossfade it swaps
  `exo = candidate`. It also owns the `MusicQueue`.
- Each ExoPlayer gets its audio chain from `SilenceSkippingSupport.renderersFactory`, which wraps
  `NormalizationAudioProcessor` (loudness) and silence skipping; `SystemAudioEffects` attaches the
  system equalizer. `EqualizerAudioProcessor.kt` is the in-app EQ processor pattern to copy.
- `playback/AndroidQueueTransitions.kt` drives crossfades (`PlaybackCrossfadeController`, settings
  `crossfadeEnabled`/`crossfadeDuration`/`crossfadeGapless`) through `AndroidPlaybackPlayer` hooks:
  `prepareNextTrack()`, `setCrossfadeVolumes()`, `startNextPlayback()`, `swapToPreloadedQueueNext()`.
  `setTempoPitch()` exists too.
- **Implication for stage 7:** two designs are possible. (a) The brief's: `DjMixEngine` owns two new
  ExoPlayers and takes over the session. (b) `DjMixEngine` drives Metrolist's existing two-player
  machinery through those hooks while Metrolist's own crossfade is switched off. (b) changes less
  core code but gives less control over processor chains and timing. Decide in `DJ_ENGINE_DESIGN.md`.

**Service and media session.** `playback/MusicService.kt` is a `MediaLibraryService` that builds a
`MediaLibrarySession` (`setupMediaSession()`) around `MediaSessionPlayer`, an adapter over
`AndroidPlaybackPlayer`. Android Auto goes through `AutomotiveMediaLibrary`. The UI talks to the
service through `playback/PlayerConnection.kt` (StateFlows; `playQueue(songs, finite = true, ...)`
starts a list). Swapping what `MediaSessionPlayer` adapts is the natural exclusive-control seam.

**Streams and audio files (affects analysis, stage 4/6).** Playback resolves a `PlaybackResource`
(`shared/.../youtube/playback/PlaybackResource.kt`) via `GlobalYouTubeRepository.streamResolver`.
Many streams are **SABR** (YouTube's server-driven protocol), which `MediaExtractor` cannot read from
a URL. Whole audio files exist only for cached or downloaded songs (`playback/AudioCacheManager.kt`:
`getCachedPlaybackResource()` gives a `file://`/`content://` URI; Opus/WebM or M4A). Getting a file
for an unplayed song uses the download path (`SongDownloadManager.resolvePlaybackResource()` →
`AudioCacheManager.downloadSong()` → `downloadSabrAudio()`/`downloadWithRangeFallback()`). These are
existing endpoints, not new ones.

**Search, radio, library (stage 3).** `shared/.../api/YouTubeRepository.kt`:
`searchFiltered(query, SearchFilter.SONG)` returns `SearchResultPage.items: List<YTItem>`;
`startRadio(videoId): List<SongItem>`. Access via `GlobalYouTubeRepository.instance`. Liked/library
songs are in `MusicDatabase` (`DatabaseDao`), read-only for us.

**Lyrics (stage 4 chorus signal).** `shared/.../repository/LyricsRepository.kt`
`getLyrics(videoId, title, artist, duration, ...)`; `LyricsUtils.parseLyrics()` gives
`List<LyricsEntry>` with `time` (ms) and `text`, so repeated lines are easy to find.

**Navigation.** Navigation 3. Keys are `shared/.../ui/navigation/Screen.kt` (`sealed class Screen :
NavKey`). Android bottom bar items come from `ui/Navigation.kt` `visibleNavigationItems()` (Home,
Search, Library, each toggled by `Settings.shared.showBottomNav*`). Screens are registered in
`ui/MetrolistApp.kt` `entryProvider { entry<Screen.X> { ... } }` (≈line 2394).

**Theming and strings.** `shared/.../ui/theme/CommonTheme.kt` `MetrolistTheme(...)`, Material 3
expressive. Strings: `shared/src/commonMain/composeResources/values/strings.xml`, public `Res` class
`com.metrolist.shared.generated.resources.Res`, so `dj-ui` can use it directly.

**DI.** Koin. `di/AppModule.kt` (`appModule`: `MusicDatabase`, `AudioCacheManager`,
`SongDownloadManager`, `PlayerConnection`, ...) and `di/ViewModelsModule.kt`. No Hilt.

**Rebrand leftovers found.** 67 English strings name "Metrolist" (onboarding etc.). The update
checker (`shared/.../update/LatestRelease.kt`, `Settings.checkForUpdates` default true) polls
Metrolist's GitHub releases, so abcd would offer Metrolist's APK as an "update". About links,
Discord button and lyrics-API referrers also point at Metrolist. See `docs/STAGES.md` backlog.

## 2026-11-16 · Android glue lives in `androidApp`, behind interfaces owned by the DJ modules

- **Decision:** `dj-*` modules define interfaces (`CandidateSource`, and later an audio-file
  provider and a playback host). Implementations that touch Metrolist internals (`PlayerConnection`,
  `AudioCacheManager`, `MusicService`, the stream resolver) live in `androidApp` under the package
  `com.aiqulabs.abcd.dj.host`, as new files.
- **Alternatives:** put that code in `dj-analysis`/`dj-engine` as the brief's §10 suggests.
- **Reason:** an Android library cannot depend on the application module, and most Metrolist
  playback/cache classes live in `androidApp` (several are `internal`). New files in a separate
  package keep upstream merges clean.

## 2026-11-16 · Analysis audio comes from a temporary full download, deleted after analysis

- **Decision (for stages 4/6):** analyse from the cached/downloaded file when one exists. Otherwise
  download the audio with Metrolist's existing download code into our own temp directory, decode,
  analyse, then delete the file. Only analysis results are stored (brief §2.5).
- **Alternatives:** `MediaExtractor` on the stream URL (brief §5.2).
- **Reason:** SABR streams cannot be opened by `MediaExtractor` from a URL. Cost: each new track is a
  full audio download (roughly 3–6 MB), so preparing an 18-track mix uses about 50–100 MB of data
  the first time. Re-using Metrolist's cache would count against the user's cache budget and show up
  in Storage, so we keep our own temp files.

## 2026-11-16 · Brief's module names mapped onto the KMP fork

- **Decision:** the brief's `app/` is `androidApp/`; the brief's `innertube/` is the external
  InnerTubeX dependency (JitPack), not a module in this repo. DJ code goes in new `dj-*` modules.
- **Alternatives:** restructure the repo to match the brief literally.
- **Reason:** this fork is the newer Kotlin Multiplatform Metrolist (`shared`, `androidApp`,
  `desktopApp`, `iosApp`), not the older single-app layout the brief assumes. Renaming upstream modules
  would make upstream merges painful.

## 2026-11-16 · Android-only scope; only `androidApp`, `shared`, `opusDecoder`, `listenTogetherProto` build for Android

- **Decision:** DJ work targets Android only. `desktopApp` and `iosApp` (and `shared`'s desktop/iOS
  source sets) are left untouched. `dj-core` stays free of Android imports so it can later become a
  KMP `commonMain` module for desktop/iOS.

## 2026-11-16 · DJ entry point is a fourth bottom-nav tab

- **Decision:** add a "DJ" tab to the Android bottom bar.
- **Alternatives:** a card on Home (brief §4.1 fallback).
- **Reason:** the Android bar shows only Home, Search and Library (each toggleable;
  `Navigation.kt` `visibleNavigationItems()`), so a fourth tab does not crowd it. An earlier note here
  wrongly counted five tabs; that list is the desktop sidebar (`desktopSidebarNavItems()`).

## 2026-11-16 · Rebrand keeps Kotlin namespaces

- **Decision:** change only `applicationId` (`in.aiqulabs.abcd`, `.debug` suffix for debug) and the
  visible app name. Kotlin packages (`com.metrolist.*`), `namespace` and internal intent action strings
  stay as upstream.
- **Reason:** renaming packages would touch thousands of files and break upstream merges. Provider
  authorities already derive from `${applicationId}`, so side-by-side install works.

## 2026-11-16 · DJ package is `com.aiqulabs.abcd.dj.*`

- **Decision:** Kotlin packages use `com.aiqulabs.abcd.dj.<module>`; the applicationId stays
  `in.aiqulabs.abcd`.
- **Reason:** `in` is a Kotlin keyword, so `in.aiqulabs...` packages would need backticks everywhere.

## 2026-11-16 · `dj-core` is Kotlin Multiplatform with only a `jvm()` target

- **Decision:** `dj-core` uses `commonMain`, with a single JVM target that Android consumes.
- **Alternatives:** a plain `kotlin("jvm")` module; or the Android KMP library plugin now.
- **Reason:** `commonMain` forbids Android/JVM-only APIs by construction, which enforces the "no
  Android imports" rule. Adding iOS/desktop later is one target line, no code move.

## 2026-11-16 · Language filtering rejects only on strong evidence

- **Decision:** a candidate is dropped for language only when its title/album is in another
  language's native script. Latin-script titles always pass.
- **Reason:** most Hindi/Punjabi film songs on YouTube Music have transliterated Latin titles, so a
  Latin title says nothing about language. Search queries ("hindi party songs") do most of the
  language targeting in v1; lyrics-based detection can refine it later.

## 2026-11-16 · DI is Koin

- **Decision:** DJ modules register Koin modules. The brief's "check whether it uses Hilt": it does not.
