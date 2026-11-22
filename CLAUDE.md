# abcd (Anybody Can DJ): agent guide

abcd is a fork of Metrolist (KMP YouTube Music client) that adds a DJ Mode.
The product brief is `docs/ABCD_rev2.md`. Build **v1 only**. v2/v3 items get hooks
(interfaces, enum values, DB columns, settings fields), never implementations.
Work follows `docs/STAGES.md` (12 stages, model per stage, high effort). Never use Plan mode. The owner
wants work built directly, without plan/approval round-trips. Engine design (approved): `docs/DJ_ENGINE_DESIGN.md`.

## Owner rules (these override AGENTS.md where they conflict)

- **Android only for now.** Do not build, test or change iOS (`iosApp`, `shared/src/iosMain`) or
  desktop (`desktopApp`, `shared/src/desktopMain`) for DJ work. Leave room for cross-platform later.
- **`dj-core` has no Android imports.** It is pure Kotlin (models, DJ brain, scoring, technique
  selection). Android-specific code (screens, player, storage, services) goes in the Android DJ
  modules or `androidApp`.
- **Green build loop.** After every meaningful change run the Android build, read the errors, fix,
  rebuild until green. Never hand over a broken build (native errors count).
- **Small stages; ask before every commit.** At the end of each stage: build a debug APK, copy it to
  `apk/abcd-stage-<N>.apk` (git-ignored), report its path and a step-by-step phone test, then stop
  and ask the owner to confirm the commit. Commit (Conventional Commits) only after a yes. Never push,
  force-push or rewrite history unasked.
- **Ask first before:** changing Metrolist's core player/service (`MusicService`, `ExoPlaybackPlayer`,
  `AndroidQueueTransitions`, `MediaSessionPlayer`, `PlayerConnection`), changing Metrolist's database
  (`MusicDatabase`), or adding any network endpoint.
- **Dependencies:** when adding one, tell the owner its name and license. Open source only; AGPL is fine.
- **No analytics or telemetry.** Do not add any. (Upstream Sentry crash reporting already exists, is
  disabled for debug builds, and must not be extended into DJ code.)
- **Never commit** secrets, keystores, `local.properties`, or `androidApp/src/main/assets/po_token.html`
  or anything like it.
- **Windows paths.** The repo lives at `C:\ABCD\abcd` (the owner's notes say `C:\Projects\abcd`;
  confirm before relying on either). `git core.longpaths` is on. Use `.\gradlew.bat` in PowerShell.
- **Impractical brief items:** say so and propose an alternative; never silently work around them.
- Keep this file under 200 lines (brief target: 150) and accurate. Update it when modules change.

## Graphify (code only, offline)

- Set `GRAPHIFY_BACKEND=none`. Never use an AI backend, `--strict`, or any cloud/hosted service.
  Never send code or docs anywhere.
- Before reading many files: `graphify query "<q>" --budget 1500`, `graphify explain "<Symbol>"`,
  `graphify path "<A>" "<B>"`. Read source only for lines the graph located.
- After code changes: `graphify update .` (AST only).
- Do not read `build/`, `.gradle/`, `.cxx/`, `graphify-out/` directly.

## Build

```powershell
.\gradlew.bat :androidApp:assembleDebug          # debug APK
.\gradlew.bat :androidApp:installDebug           # install on a connected phone
.\gradlew.bat :dj-core:jvmTest :dj-analysis:testDebugUnitTest   # DJ unit tests
git submodule update --init                      # needed once: libopus, metroproto, Essentia, Eigen
.\gradlew.bat :shared:kspAndroidMain --rerun     # if MusicDatabaseConstructor has "no actual" (stale Room KSP state)
```

- Native analysis is tested on the emulator AVD `test` (x86_64): see `dj-native/README.md`. Use `adb` +
  `am instrument`; `connectedDebugAndroidTest` wipes pushed test files. Never commit test audio.
- `build/` reads are denied for me in `.claude/settings.json`; use PowerShell/adb output for results.

- Debug APK: `androidApp/build/outputs/apk/debug/` (brief says `app/...`; here it is `androidApp/`).
- Toolchain: JDK 25 (Adoptium, auto-provisioned by Gradle toolchains), Android SDK at
  `%LOCALAPPDATA%\Android\Sdk`, NDK `30.0.16248370`, CMake `4.1.2`, compileSdk 37.1, minSdk 24.
- Debug build appends `.debug` to the applicationId.

## Module map

| Module | Role | DJ rule |
|---|---|---|
| `androidApp` | Metrolist Android app: UI, `MusicService`, ExoPlayer, nav, Koin DI | Minimal edits only; log each in `docs/UPSTREAM_CHANGES.md` |
| `shared` | KMP: commonMain UI/logic, Room `MusicDatabase`, settings, YouTube repository | Do not change schema; DJ uses its own DB |
| `opusDecoder` | Vendored Media3 libopus JNI (CMake, ARM only) | Code untouched; `dj-analysis` uses it. `-Pabcd.opusX86=true` adds x86_64 for emulator tests |
| `listenTogetherProto`, `metroproto` | Protobuf | Untouched |
| `desktopApp`, `iosApp` | Other platforms | Out of scope for now |
| InnerTubeX | External JitPack dependency (the brief's `innertube/`) | Not in this repo |

DJ modules (brief §10; package `com.aiqulabs.abcd.dj.<module>`):

| Module | Type | Contents | Status |
|---|---|---|---|
| `dj-core` | KMP, `jvm()` only | Session settings, models, playlist builder, tagging, `VibeParser`/`TrackTagger`, `SessionPreparer`, `PlaylistOrderer` + `ScoringConfig`; later technique selection | Has code + tests; `mix/` transition planning (stage 8, classic fades) |
| `dj-native` | Android lib | C++ via CMake/NDK: Essentia-based analysis, JNI; `core/` is portable C++ (no JNI/Android). See `dj-native/README.md` | Analysis done (effect DSP, e.g. echo out, comes in stage 9) |
| `dj-analysis` | Android lib | `MediaCodecPcmDecoder` (tries `OpusPcmDecoder`: in-process libopus for WebM/Opus, ~15x faster), `NativeAudioAnalyzer`, `AnalysisGate` (one decode+analysis at a time) | Done (pre-analysis lives in the host glue, stage 6) |
| `dj-data` | KMP, Android target only | Separate Room DB `abcd_dj.db` (all v1 entities), DAO, `DjAnalysisRepository`, `DjData`, `DjSetupStore`; exported schema in `schemas/` | Done (stage 5) |
| `dj-engine` | Android lib | `DjMixEngine` / `ExoDjMixEngine` (two decks, `DeckGainProcessor` trim+limiter, `DjAudioFocus`, `DjSessionPlayer` for the media session) | Classic fades (stage 8); beatmatch/echo/chorus jump in stage 9 |
| `dj-ui` | Android lib | Compose screens: Setup, Preparing, Live, Chorus editor, Lab | Setup (3), Lab (5), Preparing + library cards (6), "Now mixing" card (8); live screen + mini bar in stage 10 |

**Host glue rule:** a library cannot depend on `androidApp`. Code that touches Metrolist internals
(`PlayerConnection`, `AudioCacheManager`, `MusicService`, stream resolver) goes in new files under
`androidApp/.../com/aiqulabs/abcd/dj/host/`, implementing interfaces defined in the `dj-*` modules.
Study notes with the details: `docs/DECISIONS.md` → "Codebase study".
Host files so far: `DjController` (Start DJ), `DjPlaybackHost`/`DjPlayback` (DJ Mode hand-over, created in `MusicService`), `DjTrackPreparer`/`DjAudioProvider` (analyse + fetch audio into
`cache/dj-audio`), `DjLibraryAnalyzer`+`DjAnalysisService` (library batch), `DjLabController`, `DjTab`, `DjLyrics` (synced lyrics → chorus ranking), `DjLog` (Lab problem report).

## Key Metrolist classes (Android)

Path prefix `androidApp/src/main/kotlin/com/metrolist/android/`.

- `playback/MusicService.kt`: `MediaLibraryService`; `MediaLibrarySession` around `MediaSessionPlayer`.
- `playback/ExoPlaybackPlayer.kt`: implements `AndroidPlaybackPlayer`; owns two ExoPlayers (`exo`,
  `nextPlayer`) and the `MusicQueue`; audio chain via `SilenceSkippingSupport.renderersFactory`.
- `playback/AndroidQueueTransitions.kt`: crossfade (`PlaybackCrossfadeController`), next-track preload.
- `playback/MediaSessionPlayer.kt`: session-facing adapter over `AndroidPlaybackPlayer`.
- `playback/PlayerConnection.kt`: UI-side connection; `playQueue(songs, finite = true)` plays a list.
- `playback/AudioCacheManager.kt`, `SongDownloadManager.kt`: cached/downloaded audio files, SABR download.
- `playback/EqualizerAudioProcessor.kt`: existing AudioProcessor pattern.
- `ui/MetrolistApp.kt`: `entryProvider` registers screens; `ui/Navigation.kt`: bottom bar items.
- `di/AppModule.kt`, `di/ViewModelsModule.kt`: Koin.
- Shared: `shared/.../api/YouTubeRepository.kt` (`searchFiltered`, `startRadio`; `GlobalYouTubeRepository.instance`),
  `shared/.../repository/LyricsRepository.kt`, `shared/.../db/MusicDatabase.kt` (Room 3),
  `shared/.../ui/navigation/Screen.kt`, `shared/.../ui/theme/CommonTheme.kt` (`MetrolistTheme`),
  `SongItem`/`YTItem` models, `Settings.shared` + `PreferenceKeys.kt`.

## Conventions (from AGENTS.md, still apply)

- DI is **Koin**, not Hilt. Coroutines + Flow; heavy work off the main thread.
- User-facing strings go only in `shared/src/commonMain/composeResources/values/strings.xml` (English);
  use `stringResource(Res.string.x)`. No hardcoded locale/language defaults.
- Preference keys in `PreferenceKeys.kt`, accessed via `Settings.shared.<name>`.
- Imports, not inline FQNs. Small files, KDoc on public interfaces, comment each native DSP function.
- Format changed Kotlin with `ktlint --format`.
- Record design decisions in `docs/DECISIONS.md`; upstream file edits in `docs/UPSTREAM_CHANGES.md`.
- Mixing engine: write `docs/DJ_ENGINE_DESIGN.md` and wait for approval before touching the core player.
