# Upstream (Metrolist) files changed by abcd

Every Metrolist file that abcd modifies, with the reason. Keep this list current so upstream fixes can
be merged later. New DJ code lives in the `dj-*` modules and is not listed here.

| File | Change | Reason |
|---|---|---|
| `androidApp/build.gradle.kts` | `applicationId` → `in.aiqulabs.abcd`; app labels → `abcd` / `abcd Debug` | Rebrand so abcd installs beside Metrolist (brief §2.4) |
| `androidApp/build.gradle.kts` | `implementation(project(...))` for `dj-core`, `dj-analysis`, `dj-data`, `dj-engine`, `dj-ui` | App consumes the DJ modules |
| `settings.gradle.kts` | `include(...)` for the six `dj-*` modules | Register DJ modules |
| `shared/src/commonMain/composeResources/values/strings.xml` | `app_name` → `abcd` | Rebrand (brief §2.4) |
| `androidApp/src/main/res/mipmap-anydpi{,-v26}/ic_launcher.xml` | Point the default icon at new `abcd_launcher_*` drawables | Placeholder abcd icon; Metrolist's alternative icons in the picker are unchanged |
| `shared/src/commonMain/composeResources/values*/strings.xml` (58 files) | "Metrolist" → "abcd" on 1,514 lines; five credit/licence/migration/Discord strings kept | Rebrand (owner request) |
| `androidApp/.../update/AppUpdateManager.kt` | `UPDATES_ENABLED = false` guard in `check()` | Stops offering Metrolist's APK as an update |
| `shared/.../settings/Settings.kt` | `checkForUpdates` default `true` → `false` | Same |
| `shared/.../youtube/playback/PlaybackEventLog.kt`, `shared/.../api/lyrics/OpenRouterAuth.kt` | Visible name literal → "abcd" | Rebrand |
| `shared/.../ui/navigation/Screen.kt`, `NavigationState.kt` | Add `Screen.Dj` and register its serializer | DJ tab route |
| `androidApp/.../ui/Navigation.kt` | DJ tab label, icons; added after the hidden-tab check in both bottom-bar lists, and to the large-screen sidebar list | DJ tab (always shown); Metrolist's "mysterious fourth tab" behaviour unchanged |
| `androidApp/.../ui/MetrolistApp.kt` | `Screen.Dj` in the top-level route sets, top-bar title, `isVisibleNavigationScreen`, and `entry<Screen.Dj> { DjTab() }` | DJ tab content |
| `shared/.../composeResources/values/strings.xml` | 107 `dj_*` English strings appended (setup screen and Lab) | DJ screens (translations via Weblate later) |
| `androidApp/.../playback/AudioCacheManager.kt` | New public `downloadToFile(resource, target, control, onProgress)`: about 55 added lines after `downloadSong`; nothing existing changed. Transfer failures are wrapped in `DownloadTransferException` like `storeAudioFile` does, so a refused URL is resolved again (SABR first) | Pre-analysis downloads a song's audio into abcd's own folder without touching Metrolist's cache, library or downloads (stage 6, owner chose this over scraping the cache) |
| `androidApp/src/main/AndroidManifest.xml` | New `<service>` entry `com.aiqulabs.abcd.dj.host.DjAnalysisService` (`dataSync`, same type Metrolist's `DownloadService` uses; the permission was already declared) | "Analyse my library" runs in a foreground service with a progress notification (brief §5.1) |
| `shared/.../composeResources/values/strings.xml` | ~40 more `dj_*` strings (preparing screen, library analysis, DJ audio files) | Stage 6 screens |
| `androidApp/.../playback/MusicService.kt` | DJ Mode hooks (stage 8, design approved): `djHost` field + `setupDjPlayback()` (creates `DjPlaybackHost` with existing calls: `savePlaybackState`, `player.stop`, `retryPendingQueuePlayback`, `mediaSession.player = ...`; one guard on `player.wantsPlaybackState` that ends DJ Mode before Metrolist plays); sleep timer and pause-on-mute act on the DJ mix while it runs (8d); DJ Mode is refused while casting, in Listen Together or following via Device Connect, and ends when cast or Listen Together connects (8d); `onTaskRemoved` ends a paused DJ session; `onDestroy` ends DJ Mode before releasing the session | The exclusive hand-over in `docs/DJ_ENGINE_DESIGN.md` §5, §9. `ExoPlaybackPlayer`, `AndroidQueueTransitions`, `MediaSessionPlayer` unchanged |
| `androidApp/.../ui/MetrolistApp.kt` | Stage 10: `showDjMiniBar` / `miniPlayerSlotUsed` (the mini player's space is kept while a DJ mix runs and Metrolist's player is stopped; six inset lines use it instead of `shouldRenderMiniPlayer`) and a `DjMiniBarHost` drawn in the mini player's place, before the `SnackbarHost` | DJ mini bar on the other tabs (brief §4.3, design §6); Metrolist's own mini player is unchanged |
| `shared/.../composeResources/values/strings.xml` | 16 more `dj_*` strings (live screen, thumbs, queue) | Stage 10 |
| `shared/.../utils/CrashReporting.kt` (+ its test) | `isCrashReportingAvailable()` always false | Stage 12: no crash reports to upstream's Sentry; hides the onboarding step and the Settings toggle |
| `androidApp/.../App.kt` | In release builds (no Timber tree), plant `DjLog.plantReleaseTree()`: DJ tags only, INFO and up, local log | Stage 12: the Lab problem report works in release builds; nothing is sent anywhere |
| `androidApp/build.gradle.kts`, `gradle.properties` | `abcdVersion` property used as `versionName` (`1.0.0-alpha1`); `appVersion` unchanged | Stage 12 alpha release; Listen Together still sends upstream's version |
| `shared/.../composeResources/values/strings.xml` | More `dj_*` strings (Lab song details, timeline, developer switch) | Stage 12 |
| `.github/` | Upstream workflows, composite actions, CI scripts/tests, actionlint config, FUNDING.yml and issue templates removed; one abcd `ci.yml` (dj-core unit tests on ubuntu-latest, `--no-scan`), abcd bug/idea templates and PR template added | Owner request 2026-11-22: upstream CI needs Metrolist's paid Blacksmith runners and builds iOS/desktop releases; GitHub-only files, the app build is unaffected. On an upstream merge, keep them deleted |
| `androidApp/.../playback/AudioCacheManager.kt` | Hotfix alpha2: `downloadToFile` first reads direct streams with `HttpRangeInputStream` (new private `downloadLikePlayer`), then the old range path; one line of upstream `702d5245` merged by hand (`"cached-audio"` literal instead of `CACHED_AUDIO_PROFILE_ID`) | YouTube 403s parallel/whole-file ranges past the first minute (2026-11-23) |
| `gradle.properties` | `innerTubeXVersion` v0.11.7 → v0.13.0 (as upstream) | Hotfix alpha2 |
| `opusDecoder/build.gradle.kts` | `opusAbis` adds `x86_64` only when `-Pabcd.opusX86=true` is passed; default ABIs unchanged | Test in-process Opus decoding for DJ analysis on the x86_64 emulator (stage 6c) |
| `gradle/libs.versions.toml` | New alias `media3-extractor` (same Media3 version, already in the app through ExoPlayer) | `dj-analysis` reads WebM with Media3's Matroska extractor (stage 6c) |
| `.gitmodules` | Two new submodules: `dj-native/third_party/essentia`, `dj-native/third_party/eigen` | Audio analysis libraries (stage 4) |
| `README.md` | Rewritten for abcd (what it does, how it works with a block diagram, modules, build, privacy); Metrolist credited under "License and credits" | Owner request after stage 10; credit kept (brief §2.2, GPL) |
| `CLAUDE.md` | Replaced with the abcd agent guide | Brief §3.4 |
| `.gitignore` | Ignore `graphify-out/` | Local code graph is not committed |
