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
| `.gitmodules` | Two new submodules: `dj-native/third_party/essentia`, `dj-native/third_party/eigen` | Audio analysis libraries (stage 4) |
| `README.md` | abcd section added at the top; Metrolist's README kept below | Credits (brief §2.2) |
| `CLAUDE.md` | Replaced with the abcd agent guide | Brief §3.4 |
| `.gitignore` | Ignore `graphify-out/` | Local code graph is not committed |
