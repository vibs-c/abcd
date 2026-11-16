# abcd design decisions

Format: date · decision · alternatives considered · reason.

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

## 2026-11-16 · DJ entry point is a Home screen card, not a bottom-nav tab

- **Decision:** add a prominent "DJ" card on Home.
- **Alternatives:** a sixth bottom-nav destination (`NavigationId`).
- **Reason:** the bottom bar already holds up to five user-configurable tabs (Home, Library, History,
  Stats, Listen Together). A sixth would crowd it (brief §4.1 fallback).

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
