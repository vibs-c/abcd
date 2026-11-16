# abcd (Anybody Can DJ) — Build Brief for the AI Developer

You are the lead Android engineer on **abcd ("Anybody Can DJ")**. The app is built on a fork of **Metrolist** (https://github.com/MetrolistGroup/Metrolist), an open-source YouTube Music client for Android written in Kotlin with Jetpack Compose and Material 3. Your job is to add a **DJ Mode**. In DJ Mode a user picks languages, a mood, a mix style and a session length. An automatic DJ then builds a playlist and plays it as one continuous mix, with beat-matched transitions and jumps to chorus sections. Every song is analysed and stored in a database, so the DJ gets better the more it is used.

Read this whole brief before writing any code.

---

## 0. Versions — what to build now

Every feature in this brief is tagged **[v1]**, **[v2]** or **[v3]**.

| Version | Goal | What it means for you |
|---|---|---|
| **v1 — Stable app** | A DJ Mode that works reliably end to end with a small, simple feature set | **Build only v1 now.** |
| **v2 — Polished app** | More controls, more techniques, runtime analysis, sound effects, performance limits | Don't build these yet. Where a v2 feature needs a hook (an interface, an enum value, a DB column), add the hook in v1 so v2 doesn't need a rewrite. |
| **v3 — Major features** | A shared server database, on-device AI, fully autonomous effects | Don't build. Keep the v1 design compatible with these. |

When a v1 choice would make a v2/v3 feature hard later, mention it to me rather than deciding silently.

---

## 1. Product goals

- **Primary use cases:** house parties with no budget for a DJ, and car trips where the music should keep a continuous vibe.
- **Target users:** non-technical people. They should start a good mix in a few taps.
- **Market focus:** India first. Language selection matters as much as genre, and many users will mix Hindi, Punjabi, Marathi, English and South Indian film music.
- **v1 success criteria:** a full session plays with no dead air and no crashes, transitions between steady-tempo songs are beat-aligned, and chorus jumps land on the actual chorus.

---

## 2. Base project and constraints

1. **Fork Metrolist** and keep it buildable at every step. Metrolist is in maintenance mode upstream, so treat it as a stable base. Still, isolate our code so upstream bug fixes can be merged later.
2. **License:** abcd is open source. Metrolist is **GPL-3.0**, and **AGPL-3.0** dependencies (such as Essentia) are acceptable. If you link an AGPL library, the combined app is distributed under AGPL-3.0. Update the LICENSE/README to say so and keep all upstream credits. Avoid proprietary or non-open-source dependencies.
3. **Isolation rule:** put all new code in new Gradle modules or packages (see §10). Touch existing Metrolist files only where an integration point requires it. Keep a running list of every upstream file you modified in `docs/UPSTREAM_CHANGES.md`, with the reason for each change.
4. **Rebrand:** change the app name to "abcd" and use a new `applicationId` (for example `in.aiqulabs.abcd`) so it installs side by side with Metrolist.
5. **Audio source:** songs stream through Metrolist's existing YouTube Music (innertube) layer. Don't build a new music source. Store only **analysis results**, never audio, in our database.
6. **Min/target SDK:** keep Metrolist's existing values.
7. **No resource limits in v1.** Don't optimise for storage, RAM, battery or processing time yet. Make it correct and stable first. Limits and tuning come in v2/v3. Still keep heavy work off the main thread so the UI doesn't freeze.

---

## 3. Getting started

1. Fork and build the stock app (`./gradlew assembleDebug`) and confirm the APK installs.
2. Rename to abcd with the new applicationId.
3. Explore the codebase *efficiently*: player/service, media session, crossfade implementation, queue handling, innertube APIs, lyrics, navigation, theming, DI, Room usage.
4. Write a concise `CLAUDE.md` (under 150 lines) with build commands, the module map, key classes and conventions. Add deny rules for `build/`, `.gradle/`, native build output and generated files so you don't waste tokens reading them.
5. Then build the v1 features in this order: setup screen and playlist → analysis engine and database → pre-analysis step → mixing engine → live screen → chorus editor. Each step should leave the app buildable.

---

## 4. DJ Mode — user experience

### 4.1 Entry point [v1]
- Add a **"DJ" destination** to the main navigation, following Metrolist's existing nav pattern and theme. If a bottom-nav item would crowd the bar, add a prominent card on the Home screen instead and tell me which option you chose.

### 4.2 Setup screen ("Start a mix")

**v1 shows only these four inputs, all required:**

| Input | Type | Notes |
|---|---|---|
| Languages | Multi-select chips (≥1) | Hindi, English, Punjabi, Marathi, Tamil, Telugu, Kannada, Malayalam, Bengali, Gujarati, plus "Any". Remember the last selection. |
| Mood | Single-select cards with icons | Party, Chill, Romantic, Road Trip, Workout, Sad/Emotional, Retro/Nostalgic, Devotional, Wedding/Sangeet |
| Mix style | Segmented control (default Smooth) | v1 styles: **Smooth** (long blends), **Radio** (quick fades, full songs), **Chorus Hits** (mostly choruses, fast pace). **Club** (EQ/filter-heavy) is v2. |
| Session length | Slider/picker | 15 min to 4 h. Required in v1 because the DJ uses it to decide how many tracks to prepare and pre-analyse (§5.1). "Endless" is v2, since it needs runtime analysis. |

Then a large **"Start DJ"** button (see §5.1 for what happens next).

**[v2] "More options" page.** Hidden in v1: no UI and no entry point. In v2 it becomes a secondary page opened from a "More options" link on the setup screen. It holds:
- **Genre** (multi-select chips): Bollywood, Punjabi Pop, Hip-Hop/Rap, EDM, House, Pop, Rock, Indie, Lo-fi, Sufi, Classical fusion, Regional folk.
- **Free-text vibe**, for example "90s Bollywood wedding dance", parsed into the other fields (§8).
- **Energy arc**: Steady, Build-up, Peak-and-cooldown, Wave.
- **Seed songs**: anchor songs the DJ must include.

In v1, add these as fields in the session-settings data model with sensible defaults (no genre filter, an energy arc chosen from the mood, no seeds), so v2 only adds the UI.

### 4.3 Live DJ screen

**v1 main screen — keep it simple. Show only:**
- **Now playing:** artwork, title, artist.
- **Up next:** artwork, title, artist.
- **Thumbs up / thumbs down** on the transition that just happened (stored as feedback, §6).
- **Queue view:** the list of upcoming tracks. In v1 the user can remove a track or reorder tracks, and the DJ re-plans the affected transitions.
- Standard play/pause, plus an "End session" button.

The screen must keep working with the screen off, using the existing foreground media service, notification controls and lock screen. Android Auto should keep working wherever Metrolist already supports it.

**[v2] "DJ details" page.** Everything else lives here, opened from a small icon on the live screen:
- BPM, key (Camelot) and energy for each deck.
- A waveform strip with the beat grid, section markers and the planned transition point.
- A transition countdown and the technique name ("Echo out · 8 beats").
- Controls: "Mix now", "Skip next", "More energy" / "Less energy", "Lock" (keep this song's full length), "Never play".
- The sound-effects pad (§9.4).

### 4.4 Chorus editor [v1]
Users can fix a chorus the analysis got wrong. Make the editor reachable from a track's long-press menu ("Adjust chorus") and from the Lab screen.
- Show the track's waveform with section markers and each detected chorus segment as a highlighted region.
- The user can **drag the start and end handles** of a chorus segment, **add** a segment, **delete** one, and **choose the main chorus** (the one Chorus Hits and chorus jumps use).
- Handles **snap to the nearest bar/beat** from the beat grid, with a toggle to turn snapping off for fine adjustment.
- A **preview** button plays from 4 bars before the segment through its end, so the user can hear whether the entry point is right.
- Saving writes the segments with `source = USER` (§6). User-edited segments always win and are **never overwritten** by re-analysis.

### 4.5 Lab screen (developer-only, behind a settings toggle) [v1]
- Shows the full analysis of any track: BPM and confidence, beat grid, key and confidence, LUFS, sections, chorus candidates with scores and sources.
- Lets me trigger re-analysis of a track, open the chorus editor, and audition a transition between any two tracks with the technique picked by hand.
- Shows pre-analysis status and database contents.

---

## 5. Track analysis

### 5.1 When analysis happens

**[v1] Ahead of time, never during playback:**
1. **Session preparation.** When the user taps "Start DJ", the DJ builds a playlist long enough to fill the chosen session length plus a few spare tracks, then analyses **every track that isn't already in the database** before playback starts. Show a "Preparing your mix…" screen with a progress bar ("Analysing 7 of 18"). Tracks already analysed are skipped, so repeat sessions start quickly. If a track can't be analysed (decode error, unavailable stream), drop it and pull in a replacement. Playback starts only once the whole prepared playlist has been analysed.
2. **Library pre-analysis.** Add a button ("Analyse my library") in DJ settings and the Lab screen. It runs a batch analysis of liked songs, library songs and downloaded songs in the background, with a progress notification, so the database fills up ahead of time.
3. **Reuse cache:** if Metrolist has already cached or downloaded the audio, analyse from that file instead of fetching again.
4. Store an `analysisVersion` with each analysis so tracks can be re-analysed when the algorithms change. A user's manual chorus edits survive re-analysis.

If the user edits the queue and adds a track that isn't analysed yet, analyse it immediately. If it isn't ready in time, use the classic fade for that transition.

**[v2] Runtime analysis.** Analyse just in time during playback (always keeping the next 2 tracks analysed). This enables "Endless" sessions and starting playback before the whole playlist is ready. Design the v1 analysis service so v2 can call it from a runtime scheduler without changes.

### 5.2 Decoding [v1]
- Decode with `MediaExtractor` and `MediaCodec` to PCM in Kotlin, then pass the PCM to the native analyser (§5.4). Downmix to mono, and resample to the rate the native library expects (for example 44,100 Hz for Essentia).

### 5.3 Features to extract [v1 unless tagged]
| Feature | Notes |
|---|---|
| **BPM** + confidence | 70–180 BPM range, resolving half/double-tempo ambiguity. Many Indian film songs change tempo or use rubato, so low confidence must be detected and stored, not hidden. |
| **Beat grid** | Beat timestamps |
| **Downbeats / bars** | Assume 4/4, with a confidence value. Needed for phrase-aligned mixing and snapping in the chorus editor. |
| **Phrases** | Group bars into 8/16/32-bar phrases using energy/novelty changes. Transitions start on phrase boundaries. |
| **Key** + confidence | Store both the musical key and the Camelot code. |
| **Loudness** | Integrated LUFS (EBU R128), for gain matching between decks |
| **Energy curve** | Per-bar energy, normalised 0–1 |
| **Sections** | intro / verse / pre-chorus / chorus / bridge / outro, from a self-similarity/novelty analysis |
| **Chorus segments** | See §5.5. Stored as their own table (§6). |
| **Intro/outro mixability** | Length of low-vocal, steady-beat regions at the start and end |
| Waveform overview | A downsampled peak array for the chorus editor and the v2 details page |
| Vocal presence **[v2]** | Avoid two vocals overlapping during a blend. |

### 5.4 Native implementation (C++ via the NDK) [v1]
- Build the analysis **hot paths in C++ from the start**: STFT/FFT, onset detection, tempo and beat tracking, key detection, loudness, self-similarity and segmentation.
- **Recommended:** use **Essentia** (AGPL-3.0, C++), cross-compiled for Android (arm64-v8a first, then armeabi-v7a and x86_64 for the emulator), with KissFFT or its built-in FFT so FFTW isn't needed. Its ready-made algorithms (rhythm extractor, beat tracker, key extractor, EBU R128 loudness) save a lot of DSP work. If Essentia proves too painful to cross-compile, tell me and propose **aubio** plus our own C++ code for key, loudness and segmentation.
- Expose one JNI entry point, roughly `analyze(pcm: FloatArray, sampleRate: Int): AnalysisResult`, behind a Kotlin `AudioAnalyzer` interface, so the native implementation can be swapped later.
- Write the per-buffer DSP for the mixing effects (EQ, filters, echo; §9.2) in C++ as well, called from the Media3 `AudioProcessor` through JNI.
- Use CMake through the Android Gradle plugin. Keep native code in its own module (§10).

### 5.5 Chorus detection [v1]
Combine several signals, each with a weight:
1. **Repetition:** the most-repeated high-energy segment in the self-similarity matrix.
2. **Synced lyrics:** Metrolist already fetches time-synced lyrics. Lines that repeat 2 or more times are very likely the chorus. Use this whenever lyrics exist.
3. **Energy:** choruses are usually louder and brighter than verses.
4. **[v2] "Most replayed" data:** if the innertube responses Metrolist already handles expose YouTube's most-replayed heatmap, use its peak as a strong prior. Investigate in v2. Don't add new scraping.

The output is every candidate chorus segment, with start, end, score and the signals that contributed, plus one marked as the **main chorus**. A user's manual edits (§4.4) override all of this.

---

## 6. Music-intelligence database

### 6.1 [v1] Local database
Use a **separate Room database** (`abcd_dj.db`) owned by the DJ module, so Metrolist's own schema and migrations stay untouched. Link records by YouTube `videoId`.

Entities (adjust names to match project conventions):
- **TrackAnalysis:** `videoId` (PK), `analysisVersion`, `durationMs`, `bpm`, `bpmConfidence`, `beatGrid` (blob), `downbeats` (blob), `phraseStartsMs` (blob), `keyRoot`, `keyMode`, `camelot`, `keyConfidence`, `lufs`, `energyCurve` (blob, per bar), `waveformPeaks` (blob), `introMixableEndMs`, `outroMixableStartMs`, `analyzedAt`
- **TrackSection:** `id`, `videoId`, `type` (enum: INTRO / VERSE / PRE_CHORUS / CHORUS / BRIDGE / OUTRO), `startMs`, `endMs`, `confidence`
- **ChorusSegment:** `id`, `videoId`, `startMs`, `endMs`, `startBar`, `endBar`, `isMainChorus` (bool), `score`, `signals` (e.g. REPETITION, LYRICS, ENERGY), `source` (AUTO / USER), `editedAt`
  - Re-analysis replaces only `source = AUTO` segments, and only when the track has no `USER` segments.
  - Whenever a `USER` segment marked as the main chorus exists, the DJ uses it for chorus jumps and Chorus Hits.
- **TrackTag:** `videoId`, `tagType` (LANGUAGE / MOOD / GENRE / ERA / ENERGY_CLASS), `value`, `source` (METADATA / ANALYSIS / LLM / USER), `confidence`
- **TransitionFeedback:** `fromVideoId`, `toVideoId`, `technique`, `bpmDelta`, `keyCompat`, `outcome` (THUMBS_UP / THUMBS_DOWN / SKIPPED / COMPLETED), `timestamp`
- **TrackFeedback:** `videoId`, `playCount`, `skipCount`, `djPlayCount`, `banned`, `lastPlayedAt`
- **DjSession:** id, settings JSON (including the v2 fields with defaults), start/end, tracks played

### 6.2 Categorisation [v1]
- **Language:** from metadata (artist, album, and the script of the title, detected with a Unicode range check for Devanagari, Gurmukhi, Tamil and so on), then lyrics language if lyrics are available.
- **Mood/energy class:** a rule-based mapping from BPM, energy, mode (major/minor) and tags.
- **Genre:** from YouTube Music metadata and artist history.
- Every tag records its `source` and `confidence`. User corrections (v2 UI) always win.

### 6.3 Learning from feedback
- **[v1]** Record thumbs up/down, skips, removals from the queue and completed plays. Apply a simple, explainable adjustment: banned and often-removed tracks sink, and transition pairs and techniques with thumbs up are preferred.
- **[v2]** Export/import the database as JSON, a UI for correcting tags, and tuning the scoring weights from the Lab screen.

### 6.4 [v3] Shared database on a server
- Pool the analysis data (BPM, beat grid, key, sections, chorus segments, tags) for all users on a server, so a song analysed once by anyone is ready instantly for everyone.
- Before analysing locally, the app asks the server for the track's analysis. After analysing locally, it uploads the result.
- **User-edited chorus segments** are the most valuable data. The server should aggregate edits from many users (for example by taking the median of agreeing edits) to converge on the "true" chorus, and track how much each contributor is trusted.
- Transition feedback can be aggregated to learn which song pairs and techniques work well together.
- Upload only analysis results and anonymous feedback, never audio or personal data. Make sharing opt-in.
- **v1 design implication:** keep the schema keyed by `videoId` and versioned, with every value carrying `source`/`confidence`, so it can sync to a server later without migration pain. Don't build any server code in v1 or v2.

---

## 7. Playlist generation (the "DJ brain") [v1 unless tagged]

### 7.1 Candidate sourcing
Use Metrolist's existing innertube APIs: search by language + mood keywords, YouTube Music radio/"up next" from chosen tracks, charts and mood/genre browse pages, and the user's library and liked songs. Filter out long talk segments, poor-quality live audio, very long tracks and banned tracks.

### 7.2 Building the playlist
- Estimate how many tracks fill the session length for the chosen mix style (Chorus Hits uses many short segments, Radio uses fewer full songs), then add a few spare tracks.
- Order the tracks after analysis, when BPM, key and energy are known, so the order flows well.

### 7.3 Scoring a candidate as the next track
`score = w1·vibeMatch + w2·bpmCompat + w3·keyCompat + w4·energyFit + w5·freshness + w6·feedback − penalties(same artist within 3 tracks, repeated song, language drift)`
- **bpmCompat:** within ±6% after time-stretching is ideal. Consider half/double-time matches. Large gaps are allowed only with techniques that don't need beat-matching.
- **keyCompat:** Camelot rules (same key, ±1 on the wheel, or relative major/minor counts as compatible).
- **energyFit:** in v1, follow a default energy arc picked from the mood. User-chosen arcs come in v2.
- Keep all weights in one config object.

### 7.4 [v2] Look-ahead planning
Plan 3–5 tracks ahead with a small beam search so the DJ doesn't paint itself into a corner, and re-plan on "More energy", skips and similar events.

---

## 8. On-device AI

- **[v2] `VibeParser`:** a rule/keyword-based parser (including Hindi/Hinglish keywords) for the free-text vibe field on the "More options" page.
- **[v3] On-device LLM:** replace or augment the parser with a small local model (MediaPipe LLM Inference / LiteRT, or llama.cpp via JNI with a ~1B 4-bit model), downloaded after install rather than bundled in the APK. Also use it in a **`TrackTagger`** that tags mood and genre from metadata and lyrics, optionally alongside a small audio classifier.
- All AI runs **on-device**. Don't add cloud LLM calls.
- **v1 design implication:** define the `VibeParser` and `TrackTagger` interfaces now with simple implementations (v1 tags come from metadata rules, §6.2), so v2 and v3 just plug in.

---

## 9. Mixing engine

### 9.1 Architecture [v1]
- **Exclusive-ownership rule (read this first):** ExoPlayer has no native crossfade, so Metrolist's existing crossfade most likely manages its own queue and runs two ExoPlayer instances. Metrolist's crossfade/queue logic and our `DjMixEngine` would therefore both want to own the same thing: which player is live, and what's in the audio-processor chain. **Do not run them side by side.**
  - When DJ Mode is off, Metrolist's player, crossfade and queue work exactly as today, untouched.
  - When DJ Mode is on, `DjMixEngine` takes full, exclusive ownership of the two-deck player and the media session, and Metrolist's own crossfade and queue logic is bypassed entirely.
  - When DJ Mode ends, control hands back cleanly to the normal player, with no second deck left half-alive and no stale processor left in the chain.
- **Two decks (A/B):** two Media3 `ExoPlayer` instances owned by `DjMixEngine`, each with a custom `AudioProcessor` chain (gain, plus the native EQ/filter/echo DSP) inserted through a custom `RenderersFactory`/`DefaultAudioSink`. Use `PlaybackParameters` for tempo changes with pitch preserved.
- First study how Metrolist's `MusicService`/player, media session and crossfade are actually built, and confirm or correct the assumption above. Then propose in `docs/DJ_ENGINE_DESIGN.md` exactly how `DjMixEngine` acquires exclusive control when DJ Mode starts and releases it when DJ Mode ends: which classes or services are paused or torn down, what happens to the media session, and what happens if the user backgrounds the app or takes a call mid-handoff. **Wait for my approval of that design before changing the core player.**
- **Sync:** schedule the incoming deck so its chosen downbeat lands on the outgoing deck's phrase boundary. Use audio-output timestamps (`AudioTrack.getTimestamp`, or the player's position plus output latency), not wall-clock guesses. Run a drift-correction loop during the overlap with tiny playback-speed nudges. Aim for blends with no audible flamming (roughly under 20 ms of beat phase error).
- **Gain matching:** use LUFS from the analysis so both decks sit at the same perceived loudness, and put a safety limiter on the output.
- **[v2] Escalation path:** if two ExoPlayers can't hold sync reliably, build an engine that decodes both tracks to PCM and mixes them in one **Oboe** (C++) output stream with sample-accurate scheduling. Design the `DjMixEngine` interface so this swap doesn't affect the UI or the DJ brain.

### 9.2 Transition techniques (each a strategy class implementing `TransitionTechnique`)
| Technique | Version | When to use | How |
|---|---|---|---|
| **Beatmatched blend** | v1 | BPM within ±6%, both beat grids confident | Tempo-match the incoming deck, align downbeats, and do an equal-power crossfade over 16/32 beats on a phrase boundary |
| **Echo out** | v1 | Large BPM gap or low grid confidence (common with Bollywood) | Add an echo tail on the outgoing deck's last phrase, then start the incoming deck cleanly |
| **Chorus jump** | v1 | Chorus Hits mode, or when the incoming track has a long, slow intro | Start the incoming track 4–8 bars before its main chorus instead of at 0:00 |
| **Classic fade** | v1 | Fallback when analysis fails or confidence is low | An equal-power crossfade of 4–8 s |
| **Bass swap** | v2 | Club style, with both tracks bass-heavy | Cut the outgoing deck's lows and bring in the incoming deck's lows on one downbeat, so two basslines never play together |
| **Filter sweep** | v2 | Moderate key clash or energy change | High-pass the outgoing deck over 8–16 beats while the incoming deck fades in |
| **Drop cut / slam** | v2 | High-energy moments | A hard cut on a downbeat straight into the incoming deck's chorus or drop |

Technique selection depends on BPM delta, beat-grid and key confidence, key compatibility, energy delta, mix style and transition feedback. Log the reason for each choice (shown on the v2 details page).

### 9.3 Song-length logic per mix style
- **Radio [v1]:** play most of the song and exit in the outro.
- **Smooth [v1]:** exit at the start of the outro or after the last chorus, with long blends.
- **Chorus Hits [v1]:** play pre-chorus → main chorus (optionally followed by a second chorus), so each track lasts about 60–90 s. Use the main chorus from §6.1, preferring a user-edited one.
- **Club [v2]:** exit after the second chorus or after 2–3 minutes, on a phrase boundary.
- **"Lock" [v2]** keeps one track's full length.

### 9.4 Transition sound effects
- **[v2] User-triggered.** A sound pad on the DJ details page with buttons such as air horn, siren, riser/build-up, rewind, scratch, drum roll, crowd cheer and "drop" impact. A tap plays the effect **quantised to the next beat or bar** from the beat grid, on a third effects channel mixed above both decks with its own volume control. Allow at most one effect at a time, with a short cooldown, so spamming doesn't wreck the mix. Use sounds we create ourselves or that are CC0/public-domain, and record each sound's source in `docs/SOUNDS.md`.
- **[v3] Autonomous.** The DJ fires effects on its own: a riser in the bars before a drop, an air horn on a high-energy chorus entry, a rewind at a hard cut. This is driven by the transition plan, the mix style and the mood, with a per-session intensity setting, and it learns from thumbs feedback.
- **v1 design implication:** reserve an `EffectsChannel` in `DjMixEngine`'s design and an `EFFECT_TRIGGERED` event type, but don't implement either in v1.

---

## 10. Code organisation

```
app/                 (Metrolist, minimal edits)
innertube/           (Metrolist, untouched if possible)
dj-core/             pure Kotlin: models, DJ brain, scoring, technique selection
dj-native/           C++ (CMake/NDK): Essentia or aubio build, analysis, effect DSP, JNI bindings
dj-analysis/         decoding, AudioAnalyzer interface + native implementation, pre-analysis service
dj-data/             Room DB abcd_dj.db, DAOs, repositories
dj-engine/           DjMixEngine, decks, AudioProcessors, sync loop
dj-ui/               Compose screens: Setup, Preparing, Live, Chorus editor, Lab (+ v2 pages)
docs/                DJ_ENGINE_DESIGN.md, UPSTREAM_CHANGES.md, DECISIONS.md, (v2) SOUNDS.md
```
- Use dependency injection the way Metrolist already does (check whether it uses Hilt).
- Use Kotlin coroutines and Flow, and keep analysis and other heavy work off the main thread.

---

## 11. Working rules for you (the AI)

1. **Build v1 only.** v2/v3 items are there so you design compatible hooks, not to be implemented.
2. **Build after every meaningful change** with `./gradlew assembleDebug`. Read the errors, fix them, and rebuild until the build is green. Never hand over a non-building state. Native build errors count too.
3. **Commit** in small steps with clear messages.
4. **Ask me before:** changing Metrolist's core player or service, changing Metrolist's database, or adding a network endpoint. When you add a dependency, tell me its name and license; open-source licenses, including AGPL, are fine.
5. **Save tokens:** rely on `CLAUDE.md` and targeted searches instead of reading whole directories. Don't re-read files you have just edited. Keep the logs you paste short.
6. Keep code readable for manual review: small files, clear names, and KDoc on public interfaces. Add a short comment explaining the idea behind each native DSP function.
7. Record significant design decisions in `docs/DECISIONS.md` (date, decision, alternatives, reason).
8. When a feature is finished, tell me the APK path (`app/build/outputs/apk/debug/…`), what to try on my phone step by step, and any known issues.
9. If something in this brief turns out to be impractical (an API that doesn't exist, a library that won't cross-compile), say so and propose an alternative. Don't work around it silently.
10. Don't hard-code secrets, and don't add analytics or telemetry.

---

## 12. Out of scope (all versions, for now)
iOS, user accounts, social/sharing features, AI voice-overs, DJ hardware (MIDI) support and a Play Store listing. The app is distributed as an APK.

**Start with §3, Getting started.**
