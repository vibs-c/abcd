# abcd v1 build stages

The working plan for building v1 of DJ Mode from `docs/ABCD_rev2.md`. Each stage ends with a green
build and a test APK at `apk/abcd-stage-<N>.apk`, plus a step-by-step phone check. Claude then stops
and asks for commit confirmation; it commits only after the owner says yes.

**How to run a stage**

1. Switch to the stage's model before starting: `/model sonnet` or `/model opus`, with effort set to
   **high** for every stage.
2. Paste the stage prompt (or say "start stage N").
3. Never use Plan mode. Stage 7 is design-only: Claude writes the document and stops; no code.
4. `/clear` between stages. CLAUDE.md and this file carry the context forward.

Status key: ✅ done · 🟡 partly done · ⬜ not started.

---

## 1. Rebrand to abcd · Sonnet · ✅ (brief §2.4)

- **Done:** app name "abcd" (debug: "abcd Debug"), applicationId `in.aiqulabs.abcd` (debug adds
  `.debug`), installs beside Metrolist. Placeholder icon (white record on saffron) as the default
  launcher icon. README gets an abcd section; credits and license notes in `NOTICE.md`. `LICENSE`
  stays verbatim GPL-3.0 (its text may not be modified); the AGPL note is added in stage 4 if
  Essentia is linked.
- **Not changed:** Metrolist's alternative icons in the icon picker, the in-app Metrolist logo, the
  TV banner. Replace them when there is a final abcd logo.
- **Check:** an app called abcd, with its own icon, installs next to Metrolist and plays music as before.

## 2. Study the code and create the empty modules · Opus · ✅ (brief §3, §10)

- **Done:** study notes in `docs/DECISIONS.md` ("Codebase study" plus two new decisions: Android glue
  lives in `androidApp` under `com.aiqulabs.abcd.dj.host`, and analysis audio comes from a temporary
  download). CLAUDE.md updated. Empty Android library modules `dj-native`, `dj-analysis`, `dj-data`,
  `dj-engine`, `dj-ui` created and wired into `androidApp`.
- **Check:** the app builds and looks the same. Skim the new notes in `docs/DECISIONS.md`.

## 3. DJ tab, setup screen and playlist · Sonnet · 🟡 (brief §4.1, §4.2, §7)

- **Done** (`2bbc270`): `dj-core` session settings (v2 fields defaulted), languages, moods, mix styles,
  playlist sizing, candidate filtering, script-based language tags, playlist builder. Unit-tested.
- **Remaining:** a fourth bottom-nav tab "DJ" (the Android bar has Home, Search, Library), the v1 setup
  screen (languages, mood, mix style, session length; all required; last selection remembered), an
  Android `CandidateSource` over Metrolist's existing search/radio (no new endpoints), and "Start DJ"
  playing the playlist through Metrolist's normal player.
- **Check:** pick Hindi + Punjabi, Party, Smooth, 30 min, tap Start DJ. The songs fit the choice.

## 4. C++ analysis engine · Opus · ⬜ (brief §5.2–5.5)

- Build `dj-native` with the NDK and CMake. Cross-compile Essentia for Android (arm64-v8a first). If
  that is too painful on Windows, stop and propose the aubio fallback (§5.4) before switching. Expose
  `analyze()` through JNI behind the `AudioAnalyzer` interface. Analyse one downloaded song and show
  its BPM, key and chorus times. Add the AGPL-3.0 note to README/LICENSE if Essentia is linked.
- **Check:** BPM, key and chorus times are shown. The BPM is close to the published one (or exactly
  half/double).

## 5. Database and Lab screen · Sonnet · ⬜ (brief §6.1, §6.2, §4.5)

- Separate Room database `abcd_dj.db` with every v1 entity in §6.1, including `ChorusSegment`. Save
  analysis results. Developer Lab screen (§4.5) behind a settings toggle.
- **Check:** turn on the Lab toggle, open a song in Lab, see its BPM, key, sections and chorus.

## 6. Pre-analysis before playback · Sonnet · ⬜ (brief §5.1)

- "Preparing your mix" screen with progress, skip already-analysed tracks, replace tracks that fail,
  "Analyse my library" button.
- **Check:** the first mix shows "Analysing 7 of 18"; a second mix with the same choices starts much
  faster.

## 7. Mixing-engine design · Opus · ⬜ (brief §9.1) — design only, no code

- Write `docs/DJ_ENGINE_DESIGN.md`: exactly how `DjMixEngine` takes exclusive control of playback and
  the media session when DJ Mode starts, and hands it back cleanly when it ends, including
  backgrounding and phone calls. Then explain it in plain words and list what could go wrong.
- **Check:** you understand the summary. Reply **Approved** to unlock stage 8.

## 8. Two-deck engine with fades · Opus · ⬜ (brief §9.1)

- Implement the approved design: two decks, the exclusive handoff, LUFS gain matching, the limiter,
  the classic fade. DJ sessions play through `DjMixEngine`.
- **Check:** a DJ session plays continuously with smooth fades. Then exit DJ Mode and play a normal
  song: normal Metrolist playback must still work. This is the most important check in the plan.

## 9. Beat-matching and chorus jumps · Opus · ⬜ (brief §9.2, §9.3)

- v1 techniques (beatmatched blend, echo out, chorus jump; classic fade as fallback), the technique
  selector with logged reasons, song-length rules for Smooth, Radio and Chorus Hits.
- **Check:** two similar-tempo party songs blend with no "double drum". Chorus Hits jumps into choruses.

## 10. Simple live screen and feedback · Sonnet · ⬜ (brief §4.3, §6.3)

- Now playing, up next, thumbs up/down, queue with remove and reorder, play/pause, end session.
  Record feedback as §6.3 describes.
- **Check:** thumbs work, and removing a song from the queue skips it.

## 11. Chorus editor · Sonnet · ⬜ (brief §4.4)

- Waveform, draggable handles that snap to beats, add/delete, choose the main chorus, preview, save
  as `source = USER`. Re-analysis never overwrites user edits.
- **Check:** fix a chorus, save, re-analyse in Lab: the fix survives, and Chorus Hits uses it.

## Backlog (found during testing; not scheduled yet)

- **Debug build feels laggy** (owner, stage 1). Expected for debug builds (no R8, Compose debug).
  Judge on a release build in stage 12; profile only if the release build is also slow.
- **Setup/onboarding pages still say "Metrolist"** (owner, stage 1). 67 English strings name it.
  Fix: replace the brand in `values/strings.xml` (translations keep "Metrolist" until a decision on
  touching Weblate-managed locale files).
- **Update checker points at Metrolist's GitHub** (`shared/.../update/LatestRelease.kt`, on by
  default). abcd would offer Metrolist's APK as an "update". Fix: turn it off for abcd, or point it at
  abcd's own releases (a network endpoint change, so ask first).
- **About/Discord links and lyrics-API referrer headers name Metrolist's repo.** Decide what abcd shows.
- **Sentry in release builds** sends opt-in crash reports to Metrolist's project. Handled in stage 12.

## 12. Make v1 stable and build a release · Opus · ⬜ (brief §1)

- A checklist for a full party session (screen off, other apps, a phone call, Bluetooth speaker,
  car); fix reported bugs one by one. Disable or redirect upstream Sentry crash reporting in release
  builds (it points at Metrolist's project). Signed release build with the keystore outside the repo.
- **Check:** a real 1–2 hour session. Back up the keystore and its password (password manager plus a
  USB drive). Losing it means the installed app can never be updated.
