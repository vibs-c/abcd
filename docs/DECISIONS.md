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

## 2026-11-16 · Session audio is downloaded once, kept on device until the track has played, then deleted

- **Decision (stages 4, 6, 8):** for every track in a prepared mix, get a full audio file (reuse
  Metrolist's cached/downloaded file if one exists, otherwise download it with Metrolist's existing
  download code into our own session folder). Analyse it from that file, and **also play it from that
  file** during the session. Delete our copy when the track has finished playing (or was removed from
  the queue), and sweep leftovers when a session ends or the app starts. Only analysis results are
  kept long term (brief §2.5). Files Metrolist already had are never deleted by us.
- **Alternatives:** `MediaExtractor` on the stream URL (brief §5.2); analyse then delete, then
  stream again at play time.
- **Reason (owner's idea):** SABR streams cannot be opened by `MediaExtractor` from a URL. Playing
  from local files also removes network stalls and dropouts mid-mix (the main risk for "no dead air"
  on car trips), and gives the engine seekable files, which beat-aligned entry points and chorus
  jumps need. Costs: each new track is a 3–6 MB download, and a 4-hour session holds up to
  roughly 60 tracks (about 300 MB) at once until they play. Mitigation: a session folder with
  delete-after-play, a visible "Preparing your mix" download step, and a storage check before
  starting. This is a v1 simplification; brief §2.7 says not to optimise storage yet.
- **Effect on later stages:** stage 6 prepares files as well as analysis; stage 7 designs decks that
  play local files (affects the Media3 data-source choice); stage 12 tests the cleanup paths.

## 2026-11-16 · The app is branded abcd everywhere users see it

- **Decision:** replaced "Metrolist" with "abcd" in all 58 locale string catalogs (1,514 lines) and
  two visible code literals. Kept "Metrolist" in: the credits and licence lines
  (`credits_creator`, `credits_license_desc`, `account_menu_license`), the standalone-Metrolist
  migration text, and the Discord "Visit Metrolist" button (it links to Metrolist's repository, so
  the label stays honest).
- **Left unchanged on purpose:** third-party identifiers (`User-Agent` for the Paxsenix lyrics
  provider, ListenBrainz `submission_client`, OpenRouter `X-Title`/`HTTP-Referer`), because services may
  allow-list them; the About screen links to Metrolist's repository as upstream credit; external
  folder names `Metrolist/Downloads`; internal style and class names; non-Latin transliterations of
  the name in translations, if any.

## 2026-11-16 · In-app update check is off

- **Decision:** `AppUpdateManager.UPDATES_ENABLED = false` (guards the only network call) and the
  `checkForUpdates` default is `false`. The Updates screen still opens and says "up to date".
- **Reason:** the checker polls Metrolist's GitHub releases and abcd shares Metrolist's version
  number, so it would offer Metrolist's APK as an "update". Turn it back on after pointing
  `RELEASES_URL` at abcd's own releases (a network endpoint change; ask first).

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
- **Reason:** adding iOS/desktop later is one target line, no code move. Correction (stage 3/4 review):
  with only a JVM target the compiler does *not* reject `java.*`/`android.*` in `commonMain`, so a
  build check (`verifyPlatformFreeCommonMain` in `dj-core/build.gradle.kts`) enforces the "no Android
  imports" rule on every compile until a second target exists.

## 2026-11-16 · Language filtering rejects only on strong evidence

- **Decision:** a candidate is dropped for language only when its title/album is in another
  language's native script. Latin-script titles always pass.
- **Reason:** most Hindi/Punjabi film songs on YouTube Music have transliterated Latin titles, so a
  Latin title says nothing about language. Search queries ("hindi party songs") do most of the
  language targeting in v1; lyrics-based detection can refine it later.

## 2026-11-16 · Stage 3 design choices

- **Setup form is a `DjSetupDraft`, separate from `DjSessionSettings`.** First run has nothing
  selected, so Start stays disabled until a language and a mood are chosen (all four inputs are
  required, brief §4.2). `toSettings()` fills the v2 fields with their defaults.
- **Remembered selection** lives in a private SharedPreferences file (`DjSetupStore` in `dj-data`),
  not in Metrolist's `Settings` (no upstream edits) and not yet in `abcd_dj.db` (stage 5).
- **`DjController` is an application-wide object** in `androidApp/.../dj/host`. A build keeps
  running if the user switches tabs, and progress shows again on return. Cancelling returns to idle.
- **Stage 3 hand-off:** the playlist plays through `PlayerConnection.playQueue(songs, finite = true,
  shuffleModeEnabled = false)`, so Metrolist's own player, crossfade and queue are untouched. A mix
  needs at least 5 playable songs, otherwise the screen says so instead of playing a tiny queue.
- **Search is online-only for DJ:** `searchFiltered(..., allowOfflineFallback = false)`, so being
  offline shows an error instead of a mix of whatever library songs match. Results respect the
  user's blocked-content list.
- **Known limits until stage 4:** the order is search order interleaved across queries (no BPM, key
  or energy yet), and the mood only changes the search words. The mix "fits" as well as YouTube
  Music's own search does.

## 2026-11-17 · Stage 4: audio analysis in C++ with Essentia (no aubio fallback needed)

- **Decision:** use Essentia (AGPL-3.0) for tempo/beat tracking (`RhythmExtractor2013`, multifeature)
  and key (`KeyExtractor`), plus our own portable C++ for resampling, loudness, bars, structure and
  chorus. 33 Essentia algorithms are compiled (of 283) with the bundled KissFFT.
- **Build:** Essentia's own build (`waf`, an old Android toolchain recipe) was not used. A generator
  script (`dj-native/scripts/generate_essentia_registry.py`) follows `AlgorithmFactory::create(...)`
  references from our roots and writes the registry once; the output is committed, so building needs
  only CMake and the NDK, no Python. The cross-compile worked first time with NDK r30. This also
  answers the brief's "if Essentia is too painful, propose aubio": it was not.
- **Dependencies added (tell the owner):** Essentia 2.1-beta6-dev, AGPL-3.0 (submodule, pinned
  `23ae7ba`); Eigen 3.4.0, MPL-2.0, header-only (submodule, pinned `3147391`), which Essentia's
  current core headers require; KissFFT, BSD-3-Clause, bundled in Essentia. The combined app is now
  AGPL-3.0 (NOTICE.md, `LICENSES/AGPL-3.0.txt`).
- **Portability (owner's request):** `core/` has no JNI or Android includes and exposes one C function
  (`abcd_analyze_json`). Only `jni/abcd_jni.cpp` and the Gradle module are Android-specific. The
  analyzer interface, result models and lyrics logic are in `dj-core` (platform-free Kotlin). Another
  platform needs a `PcmDecoder` and to compile `core/` + the Essentia subset.
- **Wire format = JSON string** (not packed arrays or Java object construction): one tiny JNI
  function, debuggable by eye, and iOS/desktop can use the same C function. Cost is parsing about
  50-100 KB of text per song, which is negligible next to the 7 s analysis.
- **Analysis works at 44.1 kHz mono:** our own windowed-sinc resampler (any input rate), so
  decoders on any platform can hand over their native rate.
- **Loudness is an approximation:** BS.1770 K-weighted, gated, from the mono downmix (not a certified
  stereo meter). Fine for matching two tracks the same way; absolute values can differ by 1-3 dB.
- **Bars assume 4/4.** The tracker starts a few beats late, so the grid is extended back to 0:00 at
  the same tempo. The downbeat is the beat phase with the most bass energy (kick/bass on beat 1);
  confidence is the margin. It was 0.25 on the real song, so v1 should treat bar alignment as
  unconfirmed when `downbeatConfidence` is low.
- **Structure/chorus:** bar-level self-similarity (harmony + timbre + loudness), repeated stripes,
  loud-and-repeated 8-bar core, occurrences found across the song, growth to 16 bars until a
  structural boundary, at most 6 non-overlapping candidates. Lyrics are blended in later by
  `ChorusRanker` (dj-core, tested); the host still has to fetch lyrics and call it (stage 6).
  Confidence is lowered when the "chorus" covers over 60% of the song (loop-based music).
- **ABIs:** arm64-v8a, armeabi-v7a (older 32-bit phones are common in the target market) and x86_64
  (emulator). 64-bit libraries are 16 KB page aligned for new Android versions. Native code adds
  about 0.7 MB to the APK (libraries are 1.1-1.8 MB each).
- **How it was verified without a phone:** the Android emulator (x86_64, Android 17, 16 KB pages)
  with a command-line build of the same code, and instrumentation tests for the full chain. See
  `dj-native/README.md`.

### Stage 4 results

| Input | Truth | Result |
|---|---|---|
| Synthetic song (126 BPM, A minor, 3 choruses) | 126 BPM, A minor / 8A, choruses at 22.9, 53.3, 76.2 s (15.2 s long) | 126.00 BPM, A minor / 8A, choruses at exactly those times, intro/verse/bridge/outro correct, 2 s analysis |
| "Shiny Tech", Kevin MacLeod, 3:42 MP3 (CC BY 3.0), decoded by MediaCodec | published tempo 138 BPM | 138.02 BPM (confidence 0.58), C minor (5A), 7.3 s analysis on the emulator; 5 chorus candidates of 16 bars, main at 1:41-2:09; structure confidence 0.35 because the track is one long loop |

- **Limits, honestly:** two tracks are not a benchmark. Real-world accuracy on Bollywood and regional
  songs (tempo changes, rubato) is untested; the owner will see it in the stage 5 Lab screen. Key
  detection of minor vs relative major is a known weak spot. Chorus detection is weakest on
  loop-based dance tracks, where the user's chorus editor (stage 11) is the fix.
- **Speed:** 7 s for a 3.7-minute song on an emulator; a mid-range phone may be 2-4x slower, so
  pre-analysing an 18-track mix could take a few minutes (stage 6 shows progress).

## 2026-11-17 · Review of stages 3 and 4 (Opus)

Checked every change against brief §4.1, §4.2, §5.2-5.5 and §7. Metrolist's player, service, queue,
crossfade and `MusicDatabase` were **not** touched; the only upstream code edits are navigation
(`Screen.kt`, `NavigationState.kt`, `Navigation.kt`, `MetrolistApp.kt`) and the string catalog.

Fixed in the review:
- **Hidden-tab regression:** the DJ tab was added before Metrolist's "if no tab is visible, show the
  mysterious fourth tab" check, so that tab could never appear. DJ is now added after the check.
- **Large-screen layout:** the DJ tab was missing from the sidebar list used on large screens.
- **DJ screen insets:** content was drawn under the top bar and under the mini player (the Start
  button could be hidden once music played). It now uses the same insets as Home and Library.
- **dj-core portability:** the "no Android imports" rule was not actually enforced (see the dj-core
  decision above); `verifyPlatformFreeCommonMain` now fails the build on any violation (tested).
- **Native memory:** the song was copied about four times on its way into Essentia (about 64 MB per
  copy for a 6-minute song). Now one copy; Essentia reads it in place. Same results, verified on the
  emulator.
- **Plurals:** "1 songs" in the status messages; now proper plural strings.

Verified on the emulator after the fixes: all 4 instrumentation tests pass (138.02 BPM on the real
track again); DJ tab, setup screen and "Start DJ" with Hindi + Punjabi, Party, Smooth, 30 min built
a 14-song mix (11 + 3 spares) that started with a Hindi party song.

Known gaps against the brief, not fixed now (planned):
- §7.1 candidate sources: search and radio are used; **charts, mood/genre browse pages and the
  user's library/liked songs are not yet**. Library songs need language tags first (a Latin-script
  title says nothing about language), so they come with the database in stages 5-6.
- §5.2 says resample in Kotlin; we resample in C++ so every platform's decoder can hand over its
  native rate (documented above).
- §5.5 lyrics signal: `ChorusRanker` exists and is tested but nothing fetches lyrics yet (stage 6).

## 2026-11-16 · DI is Koin

- **Decision:** DJ modules register Koin modules. The brief's "check whether it uses Hilt": it does not.
