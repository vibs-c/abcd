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

## 2026-11-17 · Stage 5: DJ database and Lab screen

- **Separate database `abcd_dj.db`** (Room 3, version 1) in `dj-data`; Metrolist's `song.db` and
  `MusicDatabase` are untouched. It holds every v1 entity from brief §6.1 from the start, so no early
  migration is needed: `track_analysis`, `track_section`, `chorus_segment`, `track_tag`,
  `transition_feedback`, `track_feedback`, `dj_session`, plus one addition, `track_meta` (title and
  artists, so the Lab can name songs that are not in Metrolist's library; never meant for sharing).
  Extra columns beyond the brief: `bpmConfidenceRaw`, `beatsPerBar`, `downbeatConfidence`,
  `keyPitchClass` (needed to rebuild the full analysis) and `removeCount` on `track_feedback` (§6.3).
- **`dj-data` is Kotlin Multiplatform with an Android target only**, set up like `:shared`, because
  Room 3 generates its database constructor for KMP. Everything except `DjData` (Android builder) and
  `DjSetupStore` is in `commonMain`, so iOS/desktop later need only a driver and a builder.
- **No foreign keys** between tables: `chorus_segment` rows must survive when an analysis row is
  deleted and recreated. Arrays are BLOBs written by portable code (doubles as 8 little-endian bytes,
  peaks as bytes); enums are stored as text so a new value never breaks an old row.
- **The rule that matters, tested on the emulator:** re-analysis replaces only `AUTO` chorus segments,
  and only when the track has no `USER` segments (`DjDao.replaceAnalysis`, one transaction). If the user
  has a main chorus, it is the only main one when loading. Deleting an analysis keeps the user's edits.
- **Exported schema** is committed in `dj-data/schemas/` for later migration tests. Never use
  destructive migration here: the user's chorus edits live in this database.
- **Lab** is switched on with a "Lab (developer tools)" switch at the bottom of the DJ tab (stored in
  the app's own preferences; off by default, no change to Metrolist's settings screens). It shows the
  analysed songs, songs on the phone, database row counts, and a detail page with a waveform strip
  (sections coloured, chorus candidates outlined, main one thicker), tempo, key, loudness, beat grid,
  mix points, sections and chorus candidates with scores and sources. Buttons: analyse again, delete.
- **Where Lab gets audio (stage 5 scope):** files Metrolist already holds (downloaded or cached songs),
  or **any music file the owner picks with the system file picker** (added because it works with the
  owner's own files and needs no download). Picked files get ids `local-<hash>` and live only in the
  Lab. Fetching audio for songs that are not on the phone is stage 6; Metrolist's download helpers are
  `private` in `AudioCacheManager`, so stage 6 needs a small, approved addition there.
- **Not in the Lab yet (later stages):** "open the chorus editor" (stage 11), "audition a transition"
  (stage 9) and "pre-analysis status" (stage 6). The brief lists them under §4.5; they have nothing to
  show until those stages exist.
- **Emulator finding:** YouTube downloads did not complete on the emulator, so "songs on this phone"
  stayed empty there; the file picker route was used instead. On the emulator decoding a 3:42 MP3 took
  about 25 s (software codec on an emulated CPU) plus 7.5 s to analyse; expect far faster decoding on a
  phone. Worth checking on the real phone because stage 6 analyses 20+ songs in a row.

## 2026-11-17 · Stage 6: pre-analysis before playback

- **Flow of "Start DJ":** build the playlist (stage 3) → drop blocked songs → check free space →
  **prepare** (analyse every track not already analysed with the current algorithm version) → **order**
  the analysed tracks → hand them to Metrolist's player. Playback starts only once the whole prepared
  playlist is analysed (brief §5.1). The Preparing card shows "Analysing 7 of 18", a progress bar, the
  song being worked on, how many were already analysed or replaced, and Cancel.
- **`SessionPreparer` (dj-core, pure Kotlin, unit-tested)** works through the plan in order, up to two
  tracks at once (one's download overlaps another's analysis). A failed track is dropped and the next
  spare takes its place until the session has its core count or the spares run out. Batches never
  exceed the number of tracks still needed, so the result is the same as doing it one by one.
- **Only one native analysis runs at a time** (a lock inside `NativeAudioAnalyzer`). Essentia's one-time
  setup is not guaranteed thread-safe, and two analyses would double the memory use. Download and decode
  of the next track still overlap with the current analysis.
- **Audio comes from `DjAudioProvider`:** a file Metrolist already holds (downloaded/cached) is used
  as it is; otherwise the audio is downloaded into `cache/dj-audio/` by the new public
  `AudioCacheManager.downloadToFile` (the owner chose this "proper" option; logged in
  UPSTREAM_CHANGES.md). It reuses Metrolist's resolver, range/SABR download code and storage checks,
  and writes nothing to Metrolist's cache database, library or download list. Downloads go to a
  `.part` file and are renamed only when complete, so a crash cannot leave a half file that looks
  usable. If a file Metrolist holds cannot be decoded, the song is downloaded fresh once.
- **Session files are kept, not deleted straight away:** the files of a session's tracks stay in
  `cache/dj-audio/` so the mixing engine (stage 8) can play them; the next session deletes everything
  not in its own playlist, and the Lab shows the size with a "Delete files" button. Android may also
  reclaim `cacheDir` when storage is low, so the engine must treat a missing file as "download again".
  Tracks that were already analysed are not downloaded in stage 6; stage 8 fetches missing files then.
  Library batches delete each file right after analysis.
- **Storage check:** before preparing, the app needs 10 MB per track still to analyse plus 64 MB spare;
  otherwise the card says there is not enough space. The download itself also refuses below 16 MB free
  (Metrolist's own rule).
- **Ordering after analysis (`PlaylistOrderer`, dj-core):** a greedy pass: first the track that fits the
  start of the energy arc, then repeatedly the remaining track with the best score
  `bpmWeight·bpmCompat + keyWeight·keyCompat + energyWeight·energyFit − same-artist penalty`.
  bpmCompat is 1.0 within ±6% (half/double time allowed) falling to 0 at 25%; keyCompat uses the Camelot
  wheel (same 1.0, relative or ±1 0.9, ±2 0.5); untrusted tempo/key score neutral (0.5). Energy is each
  track's mean bar loudness stretched to 0..1 across the playlist, compared with the arc's target
  (steady, build-up, peak-and-cooldown, wave). All weights are in `ScoringConfig`; vibe, freshness and
  feedback weights exist at 0 for later. v2's look-ahead search replaces the greedy pass behind the same
  function (§7.4). The chosen order is logged (tag `DjController`: BPM, key, energy per track).
- **Analyse my library:** a card in the DJ tab and in the Lab. It runs in a foreground service
  (`DjAnalysisService`, type `dataSync`, with a progress notification and a Stop button) so it keeps
  going in the background. It takes downloaded songs first, then liked, then the rest of the library,
  skips ones already analysed, and does at most 200 songs per run (the finished card says how many are
  left; tap again to continue). Songs over 10 minutes, podcasts, uploads and blocked songs are skipped.
- **Not done / for later:** lyrics are still not wired into `ChorusRanker` (needs the lyrics source and
  language tags; stage 11 uses it for the chorus editor). Candidate sources still lack charts and mood
  pages (§7.1).
- **Emulator caveat:** YouTube downloads did not work on the emulator in stage 5, so the real download
  path (resolver → `downloadToFile` → decode → analyse) is exercised by an instrumented test that skips
  itself without network, and must be confirmed on the phone.

## 2026-11-17 · Fix: out of memory while preparing a DJ session

- **Problem:** Start DJ crashed with `OutOfMemoryError` in `MonoSink.ensureCapacity` (emulator, 192 MB
  heap limit). Session preparation runs two tracks at once and only the native analysis was serialised,
  so several whole-song float arrays (about 46 MB each for 4 min at 48 kHz) lived together, and the 2x
  growth plus the final exact-size copy briefly needed two to three times a song's size.
- **Decision:** `AnalysisGate` (dj-analysis) lets one decode + analyse run at a time across the app
  (session prep, library analysis, Lab); downloads still overlap. `MonoSink` is sized from the track's
  duration (+2%, capped at 30 min), grows by 1.5x if short, and hands its buffer over without copying:
  `PcmAudio` gained `sampleCount`, and `NativeAnalyzer.analyzeJson` takes the frame count.
- Analysis results are unchanged (same samples, same rate), so `ABCD_ANALYSIS_VERSION` stays.

## 2026-11-17 · Fix: every pre-analysis download failed with HTTP 403

- **Problem:** after stage 6, Start DJ ended with "Prepared 0 of 12 tracks". YouTube refused the first
  (direct, non-SABR) stream URL with 403. `downloadWithResourceRefresh` retries with a fresh, SABR-first
  stream only for a `DownloadTransferException`; Metrolist's `storeAudioFile` wraps transfer errors in
  one, but stage 6's `AudioCacheManager.downloadToFile` threw the raw error, so the retry never ran.
  Stage 6's tests skip on "YouTube did not serve the audio", which hid it.
- **Decision:** `downloadToFile` wraps transfer errors exactly like `storeAudioFile`. Verified on the
  emulator: 12 of 12 tracks prepared and playing; the real-download instrumented test now passes.

## 2026-11-17 · Preparation speed: where the time goes, and what was changed

- **Measured** on the x86_64 emulator, one 5-minute Opus track: download 2-5 s, decode ~19 s, native
  analysis ~3 s (resample 0.3, RhythmExtractor2013 multifeature 2.5, key 0.1, frames 0.2, structure ~0).
  The C++ side is already built with -O2. Decoding dominated: ~0.6 ms per 20 ms Opus packet, split between
  `MediaExtractor` (~5 s) and binder round trips to the platform Opus codec (~8 s), plus the per-sample
  `ShortBuffer.get()` loop in `MonoSink`.
- **Changed:** `MonoSink` copies each decoder buffer in one bulk call (append time ~0.1 s per song);
  the decode loop feeds all free input buffers and drains all ready output before waiting. Decode is now
  ~13 s per 5-min track; results are bit-identical on stereo input. `SessionPreparer` keeps a rolling window
  of 2 tracks and reports progress after each one (it used to wait for the slower track of each pair), and
  the setup screen scrolls to the "Preparing your mix" card when a mix starts: it sat above the fold, so
  preparation looked frozen.
- **Result:** 12 new tracks in ~190-200 s on the emulator (was 245 s), counter moving every ~15 s.
- **Not done (needs approval):** decoding Opus in-process (Media3 Matroska extractor + the vendored libopus)
  would remove the per-packet IPC, probably several times faster; starting playback before the whole
  playlist is analysed is brief §5.1 v2 (runtime analysis).

## 2026-11-16 · DI is Koin

- **Decision:** DJ modules register Koin modules. The brief's "check whether it uses Hilt": it does not.
