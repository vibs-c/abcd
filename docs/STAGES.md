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

## 8d. Stage 1-8 discrepancy fixes · Opus · ✅ built, awaiting phone test

Owner feedback on 8c (phone): every style mixes only at the very end, the fade dips in volume, transitions
must be unnoticeable. Cause: stage 8's placeholder `ClassicFadePlanner`; fixed by stage 9 below. The owner
asked to fix stage 1-8 gaps first and plan 9-12 before building stage 9. Fixed in 8d (DECISIONS.md "8d"):
synced lyrics re-rank choruses (§5.5, stage 6 carry-over); played session files are deleted after playing
(stage 6 promise); the next two tracks' files are prefetched (design §7); pause-on-mute acts on the mix,
Device Connect blocks DJ Mode, cast/Listen Together end it (design §8); the decks' audio session is announced
to system EQ apps (design §4.2); design doc and CLAUDE.md synced with what was built. APK
`apk/abcd-stage-8d.apk`. A stage-9 draft (`MixPlanner`, `CrossfadeCurve`) is parked in `git stash@{0}`.

## 9. Mix points, beat-matching, echo out · Opus · ✅ built, awaiting phone test (brief §9.2, §9.3, §4.5) — answers the 8c feedback

- **9a Mix points by style** (§9.3; restore the parked `MixPlanner`, finish and unit-test it). Leave the
  outgoing song on a bar line while it is still full: Chorus Hits after the main chorus (or the chorus right
  after it), so each song lasts ~45-90 s; Smooth after the last chorus, else at the mixable outro; Radio at
  the mixable outro. Enter Chorus Hits 8 bars before the main chorus (chorus jump, user-edited chorus
  first); other styles on the first downbeat (skip leading silence). Never less than 25 s per song.
- **9b No-dip crossfade** (`CrossfadeCurve`): both gains lifted so the summed loudness stays level or
  rises ~1 dB mid-blend instead of sagging; decks keep 2 dB headroom + limiter. Applies to every technique.
- **9c Beatmatched blend** (§9.1 sync): when both beat grids are trusted and tempos are within ±6% (half or
  double time allowed), the incoming deck plays tempo-matched (`PlaybackParameters`, pitch kept), is cued two
  beats early and silent, and a phase-lock loop (both decks' positions every 40 ms, speed nudges ≤ 3%) lines
  its bar starts up with the outgoing deck's before it becomes audible. Target < 20 ms; if not locked within
  4 beats, fall back. Blend over 8 bars (16 in Smooth). The tempo glides back to 1.0 over ~25 s afterwards.
  Each transition logs its phase error for the Lab.
- **9d Echo out** (§9.2): a native echo/delay DSP (C++ in `dj-native`, called from a deck `AudioProcessor`)
  on the outgoing deck's last phrase, then the incoming song starts cleanly on its downbeat. Used for large
  tempo gaps and untrusted grids (common in film music).
- **9e Technique selector** (§9.2 table, §7.3): beatmatched blend / echo out / chorus jump / classic fade from
  BPM delta, grid and key confidence, Camelot compatibility, energy delta and mix style; the reason is
  logged (and kept for the v2 details page). Weights in `ScoringConfig`.
- **9f Lab "audition transition"** (§4.5): pick two analysed songs and a technique, hear the blend.
- **Check:** each style sounds different (Chorus Hits jumps chorus to chorus every ~1 min; Radio plays most
  of each song; Smooth leaves after the last chorus); two similar-tempo party songs blend with no "double
  drum" and no volume dip; a big tempo gap uses echo out; the Lab audition works.
- **Risks:** speed nudges may be audible (fallback: our own native resampler for the nudge); rubato film
  songs with weak grids (they get echo out/classic); position jitter on some phones (logged per transition).

## 10. Live screen, feedback, tags, more song sources · Sonnet · ⬜ (brief §4.3, §6.2, §6.3, §7.1, §5.1)

- **Live screen** (§4.3): artwork, now playing / up next, thumbs up/down on the last transition, queue with
  remove and reorder (`DjMixEngine.updateQueue` exists), play/pause, End session. **DJ mini bar** on the other
  tabs (deferred from stage 8) from `DjPlayback.state`.
- **Feedback** (§6.3): write `TransitionFeedback` (thumbs, skips, completed), `TrackFeedback` (plays, skips,
  removals, ban) and `DjSession` rows; explainable adjustments: banned and often-removed tracks sink,
  thumbs-up pairs and techniques are preferred.
- **Tags** (§6.2): write `TrackTag` rows: LANGUAGE (metadata + lyrics script), MOOD and ENERGY_CLASS (rules
  from BPM, energy, mode), with source and confidence.
- **More candidate sources** (§7.1, carry-over from stage 3): liked and library songs (language from tags),
  charts and mood/genre browse pages through Metrolist's existing browse calls (ask first if any new
  endpoint would be needed).
- **Queue additions** (§5.1): a song added to the queue that is not analysed yet is analysed at once; if not
  ready in time, its transition is a classic fade.
- **Decide with the owner:** should DJ plays appear in Metrolist's listening history / scrobbles (design §8)?
- **Check:** thumbs work; removing a song skips it; the mini bar shows on Home; liked songs appear in mixes.

## 11. Chorus editor · Sonnet · ⬜ (brief §4.4)

- Waveform with sections and chorus regions; drag start/end handles that snap to bars/beats (snap toggle);
  add/delete segments; choose the main chorus; preview from 4 bars before the segment to its end; save as
  `source = USER`. Reachable from a song's long-press menu ("Adjust chorus") and from the Lab. Re-analysis
  never overwrites user edits (already enforced in `DjDao.replaceAnalysis`); stage 9's chorus jump and
  Chorus Hits use the user's main chorus first.
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

- **Party checklist** on the phone: 1-2 h session with screen off, other apps, a phone call, Bluetooth
  speaker, Android Auto/car, low battery; memory and battery over the session; fix reported bugs one by one.
- **Release build:** R8/ProGuard rules for the JNI analyser, Media3's reflective libopus renderer and
  kotlinx serialization; judge speed on release, not debug (backlog: "debug build feels laggy"); APK size
  (three native ABIs; consider per-ABI splits).
- **Upstream leftovers:** disable or redirect Metrolist's Sentry in release builds; update checker pointed at
  abcd's own releases (network endpoint: ask first); About/Discord links; GitHub CI decision (Metrolist's
  `blacksmith-*` runners; CI only runs on `main`).
- **Data safety:** a Room migration policy for `abcd_dj.db` before any schema change (user chorus edits must
  survive); `ktlint --format` pass over DJ code.
- Signed release build with the keystore outside the repo.
- **Check:** a real 1-2 hour session. Back up the keystore and its password (password manager plus a
  USB drive). Losing it means the installed app can never be updated.
