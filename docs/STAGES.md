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

## 3. DJ tab, setup screen and playlist · Sonnet · ✅ built, awaiting phone test (brief §4.1, §4.2, §7)

- **Done** (`2bbc270`): `dj-core` session settings (v2 fields defaulted), languages, moods, mix styles,
  playlist sizing, candidate filtering, script-based language tags, playlist builder. Unit-tested.
- **Also done:** a fourth bottom-nav tab "DJ" (the Android bar has Home, Search, Library), the v1 setup
  screen (languages, mood, mix style, session length; all required; last selection remembered), an
  Android `CandidateSource` over Metrolist's existing search/radio (no new endpoints), and "Start DJ"
  playing the playlist through Metrolist's normal player.
- **Check:** pick Hindi + Punjabi, Party, Smooth, 30 min, tap Start DJ. The songs fit the choice.

## 4. C++ analysis engine · Opus · ✅ built, awaiting phone test (brief §5.2–5.5)

- Build `dj-native` with the NDK and CMake. Cross-compile Essentia for Android (arm64-v8a first). If
  that is too painful on Windows, stop and propose the aubio fallback (§5.4) before switching. Expose
  `analyze()` through JNI behind the `AudioAnalyzer` interface. Analyse one downloaded song and show
  its BPM, key and chorus times. Add the AGPL-3.0 note to README/LICENSE if Essentia is linked.
- **Done:** Essentia cross-compiled with the NDK (no aubio needed); portable `core/` + thin JNI;
  `AudioAnalyzer`, `PcmDecoder`, `AnalysisResult` and `ChorusRanker` (lyrics) in `dj-core`;
  `MediaCodecPcmDecoder` and `NativeAudioAnalyzer` in `dj-analysis`; arm64-v8a, armeabi-v7a, x86_64.
  Verified on the emulator: 138.02 BPM on a real track with a published 138 BPM. Details and limits in
  `docs/DECISIONS.md` ("Stage 4").
- **Check:** BPM, key and chorus times are shown. The BPM is close to the published one (or exactly
  half/double). The phone has no analysis screen until stage 5 (Lab); stage 4's APK only proves the
  native library installs and the app still works.

## Review of stages 3-4 (Opus, 2026-11-17)

Fixes and known gaps are in `docs/DECISIONS.md` ("Review of stages 3 and 4"). Carry-overs: add
charts, mood/genre pages and library/liked songs as candidate sources (§7.1) once tracks have
language tags (stages 5-6); wire lyrics into `ChorusRanker` (stage 6).

## 5. Database and Lab screen · Sonnet · ✅ built, awaiting phone test (brief §6.1, §6.2, §4.5)

- Separate Room database `abcd_dj.db` with every v1 entity in §6.1, including `ChorusSegment`. Save
  analysis results. Developer Lab screen (§4.5) behind a settings toggle.
- **Check:** turn on the Lab toggle, open a song in Lab, see its BPM, key, sections and chorus.

## 6. Pre-analysis before playback · Sonnet · ✅ built, awaiting phone test (brief §5.1)

- "Preparing your mix" screen with progress, skip already-analysed tracks, replace tracks that fail,
  "Analyse my library" button. Also downloads each track's audio file into a session folder, kept
  until the track has played, then deleted (see DECISIONS.md); a storage check before starting.
- **Done:** `SessionPreparer` + `PlaylistOrderer` + `ScoringConfig` in `dj-core` (unit-tested);
  `DjAudioProvider` / `DjTrackPreparer` / `DjLibraryAnalyzer` / `DjAnalysisService` in the host glue;
  Preparing card; "Analyse my library" card (DJ tab and Lab) with a foreground-service notification; Lab
  shows and clears the DJ audio files; new public `AudioCacheManager.downloadToFile` (owner-approved,
  logged in UPSTREAM_CHANGES.md). Tracks are ordered by tempo, key and energy arc after analysis.
  Details in `docs/DECISIONS.md` ("Stage 6").
- **Check:** the first mix shows "Analysing 7 of 18"; a second mix with the same choices starts much
  faster.
- **6b** (`1dede06`, APK `abcd-stage-6b.apk`): fixed out-of-memory while preparing, and refused (HTTP 403)
  downloads now retry with a fresh SABR stream, as Metrolist's own downloads do.
- **6c** (`f964e7f`, `93c06a2`, APK `abcd-stage-6c.apk`): progress card scrolls into view and counts every
  track; rolling 2-track preparation; WebM/Opus decoded in-process with libopus. 12 new tracks prepare in
  ~45 s on the emulator (was ~245 s). Details in DECISIONS.md.

## 7. Mixing-engine design · Opus · ✅ approved 2026-11-17 (brief §9.1) — `docs/DJ_ENGINE_DESIGN.md`

- Write `docs/DJ_ENGINE_DESIGN.md`: exactly how `DjMixEngine` takes exclusive control of playback and
  the media session when DJ Mode starts, and hands it back cleanly when it ends, including
  backgrounding and phone calls. Then explain it in plain words and list what could go wrong.
- **Check:** you understand the summary. Reply **Approved** to unlock stage 8.

## 8. Two-deck engine with fades · Opus · ✅ built, awaiting phone test (brief §9.1)

- Implement the approved design: two decks, the exclusive handoff, LUFS gain matching, the limiter,
  the classic fade. DJ sessions play through `DjMixEngine`.
- **Check:** a DJ session plays continuously with smooth fades. Then exit DJ Mode and play a normal
  song: normal Metrolist playback must still work. This is the most important check in the plan.

- **8b/8c:** decoder safety net and Lab "Copy DJ log" (8b); code review of stages 1-8 with fixes (8c, see
  DECISIONS.md). APK `apk/abcd-stage-8c.apk`.

## ⏸ Where we stopped (2026-11-17, end of day) — read this first next session

- **Phone status:** 8c works on the owner's phone. Owner feedback: (1) every style mixes only at the very
  end of the song, (2) the fade has an audible volume dip, (3) transitions must be subtle (unnoticeable).
  Cause: stage 8's placeholder `ClassicFadePlanner` (end-of-song fades for all styles, plain equal-power
  curve, no beat sync). These are stage 9's job; the owner asked NOT to build stage 9 yet.
- **Parked stage-9 draft:** `git stash list` → `stash@{0}` "stage-9 draft (MixPlanner, CrossfadeCurve,
  TransitionPlan speed/preRoll)". Per-style exit points, chorus-jump entry, tempo match, no-dip curve.
  Not built or tested. Restore with `git stash apply stash@{0}` when stage 9 starts.
- **In progress: ironing out stage 1-8 discrepancies** (owner's request). Audit result:
  - DONE (this commit): synced lyrics now re-rank chorus candidates (`DjLyrics` + `ChorusRanker`,
    brief §5.5; stage 6 carry-over) in session preparation and the Lab. Compiles; not yet tested.
  - TODO: delete each session file once its track has played (stage 6 promise; 8c pinning keeps them
    until the session ends): add `DjAudioProvider.unpin(id)` and call it on `TrackFinished`.
  - TODO (design §7): keep the next TWO tracks' files ready (prefetch on `TrackStarted`).
  - TODO (design §8): route pause-on-mute to the DJ engine; refuse DJ Mode during Device Connect; end DJ
    Mode when cast or Listen Together connects; broadcast `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`
    for the decks' shared audio session (system EQ apps).
  - TODO (docs): design doc §8 says the sleep timer "ends DJ Mode", code pauses the mix (keep pause, fix the
    doc); design §6 mini bar is deferred to stage 10 (say so in the design doc); CLAUDE.md module statuses.
  - NOT a discrepancy (planned later): TrackTag rows (§6.2, "written from stage 10"); charts and mood/genre
    browse pages as candidate sources (§7.1; needs YouTube Music browse ids, plan for stage 9/10).
- **Still to do after that:** write the plan for stages 9-12 (owner asked for analysis + plan, no code),
  then rebuild, emulator-test, APK `apk/abcd-stage-8d.apk`, ask the owner before committing.

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
- ✅ **Setup/onboarding pages said "Metrolist"** — replaced in all locales (see DECISIONS.md).
- ✅ **Update checker pointed at Metrolist's GitHub** — switched off (see DECISIONS.md). Re-enable
  later by pointing it at abcd's own releases (network endpoint change; ask first).
- **About screen and Discord button link to Metrolist's repo** — kept as upstream credit. Add abcd's
  own repo link once it is public.
- **GitHub CI** (`.github/workflows`) targets Metrolist's paid runners (`blacksmith-*`) and Sentry
  release steps; it would not run usefully in abcd's repo. Decide before the first push.
- **Repo git hooks are not active** (`core.hooksPath` unset). Enable with
  `git config core.hooksPath .githooks` if wanted; all current commit messages already pass them.
- **Sentry in release builds** sends opt-in crash reports to Metrolist's project. Handled in stage 12.

## 12. Make v1 stable and build a release · Opus · ⬜ (brief §1)

- A checklist for a full party session (screen off, other apps, a phone call, Bluetooth speaker,
  car); fix reported bugs one by one. Disable or redirect upstream Sentry crash reporting in release
  builds (it points at Metrolist's project). Signed release build with the keystore outside the repo.
- **Check:** a real 1–2 hour session. Back up the keystore and its password (password manager plus a
  USB drive). Losing it means the installed app can never be updated.
