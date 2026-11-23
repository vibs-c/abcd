# abcd v2 plan

v1 shipped as `1.0.0-alpha1` (2026-11-22, tag `v1.0.0-alpha1`). This file is where v2 is planned: first the
owner's list of what v2 must contain, then Claude breaks it into stages (one at a time, each with a model,
a test APK and a commit), like `docs/STAGES.md` did for v1.

Status: **waiting for the owner's list.** No v2 code is written until the stage plan below is agreed.

## 1. Owner's list

_To be filled in by the owner._

## 2. Candidates already known

From the brief (`docs/ABCD_rev2.md`, items tagged [v2]) and the owner's earlier notes. The owner picks
which of these go into v2; anything not picked stays for later.

| # | Item | Source | Hooks already in v1 |
|---|---|---|---|
| C1 | **Chorus editor**: drag chorus start/end on the waveform, snapped to bars/beats; add/delete segments; pick the main chorus; preview; saved as USER | Brief §4.4, stage 11 (moved) | `ChorusSegment.source`, re-analysis keeps USER segments (`DjDao.replaceAnalysis`), planner uses the main chorus |
| C2 | **DJ listening history**: a separate "DJ" tab inside the existing listening-history button | Owner, 2026-11-22 | `dj_session` rows (settings + tracks played) since stage 10 |
| C3 | **"More options" page**: genre filter, free-text vibe (`VibeParser`, Hindi/Hinglish keywords), energy arc, seed songs | Brief §4.2, §8 | `DjSessionSettings` fields `genres`, `vibeText`, `energyArc`, `seedVideoIds`; `DjGenre`, `EnergyArc` |
| C4 | **DJ details page** from the live screen: BPM/key/energy per deck, waveform with beat grid and mix point, countdown + technique name, "Mix now", "Skip next", "More/Less energy", "Lock", "Never play" | Brief §4.3 | `DjMixEngine.progress()`, timeline UI (stage 12) |
| C5 | **More transitions**: filter sweep, drop cut/slam, stand-alone bass swap; **Club** mix style; **Lock** a song's full length | Brief §9.2, §9.3 | `TransitionTechnique` enum values, bass-swap filter in `DeckGainProcessor` |
| C6 | **Sound pad**: air horn, siren, riser, rewind, scratch, drum roll, crowd cheer, drop, in time with the beat | Brief §9.4 | `DjEngineEvent.EffectTriggered` |
| C7 | **Runtime analysis / Endless sessions**: analyse just in time (next 2 tracks), start playback before the whole list is ready | Brief §5.1 | `DjSessionSettings.endless`, `SessionPreparer` |
| C8 | **Look-ahead planning** of the set (several transitions at once) | Brief §7.4 | `TransitionPlanner` interface |
| C9 | **Vocal presence** analysis to avoid two vocals overlapping in a blend | Brief §5.3 | — |
| C10 | **"Most replayed" heatmap** as a chorus prior, if Metrolist's existing responses carry it | Brief §5.5 | — (investigate first; no new endpoints without asking) |
| C11 | **Database export/import (JSON)**, tag-correction UI beyond the Lab, scoring weights tunable in the Lab | Brief §6.3 | `ScoringConfig` (one object), Lab song details (stage 12) |
| C12 | **Single-stream engine** (C++/Oboe) if two ExoPlayers drift on some phones | Brief §9.1 | `DjMixEngine` interface |
| C13 | **Charts as a song source** (Hindi/Punjabi/Any mixes) | Claude's suggestion, owner undecided | `CandidateSource` |
| C14 | ✅ **Merge upstream** (Metrolist): synced with 14.0.1 on 2026-11-23 → abcd 2.0.0-alpha1. Repeat before each v2 release | Owner discussion 2026-11-22 | `docs/UPSTREAM_CHANGES.md` lists every changed upstream file |
| C15 | **v1.0 final checks** carried over: 1-2 hour party test on a phone, update checker for abcd releases (new endpoint: ask), About/Discord links, Room migration policy, ktlint | `docs/STAGES.md` stage 12 "still open" | — |

v3 (not for v2): shared server database, on-device LLM for vibes, autonomous effects (brief §6.4, §8, §9.4).

## 3. Stage plan

_Written by Claude once section 1 is filled in: stages in order, each with its scope, model (Opus for
design-heavy or audio/engine work, Sonnet for UI and wiring), the brief sections it covers and how it is
checked._

## How v2 work runs

- Branch: `feat/v2` (from `main` at `v1.0.0-alpha1`). Each stage ends with a green build, an APK in
  `apk/abcd-v2-stage-<N>.apk`, an emulator test and a commit; the owner merges to `main` when happy.
- Same rules as v1 (`CLAUDE.md`): build directly, ask before touching Metrolist's player/service/database or
  adding a network endpoint, no telemetry, tell the owner every new dependency and its licence.
