# DJ mixing engine: design (stage 7)

Status: **approved by the owner on 2026-11-17** ("start making it directly"). Implemented from stage 8.
Brief: §9.1 (architecture), §9.2–9.4 (techniques, song length, effects), §4.3 (live screen).
Paths are under `androidApp/src/main/kotlin/com/metrolist/android/` unless stated otherwise.

---

## 1. In one paragraph

When DJ Mode starts, Metrolist's player is **stopped** (its queue is kept), and a separate engine,
`DjMixEngine`, plays the mix on **two decks of its own** (two ExoPlayers). The phone's media controls
(notification, lock screen, headphones, Bluetooth, Android Auto) are pointed at the DJ engine by
**swapping the player inside Metrolist's existing media session**, so the same foreground service keeps
running and nothing is created twice. The engine requests audio focus for itself and pauses for calls.
When DJ Mode ends, the engine fades out, releases both decks and its audio focus, and the media session is
pointed back at Metrolist's player, which is exactly where the user left it (paused). Metrolist's player,
crossfade, queue and database code are not modified; only a handful of small hooks are added.

---

## 2. What Metrolist actually does (checked in the code)

The brief's assumption is **right**: Metrolist manages its own queue and crossfades with two ExoPlayers.

| Piece | What it is | What matters for DJ |
|---|---|---|
| `playback/ExoPlaybackPlayer` (4k lines) | Implements `AndroidPlaybackPlayer` (~80 members). Owns `exo` + `nextPlayer`, the `MusicQueue`, SABR sources, normalization, silence skipping, system EQ. Only the PRIMARY player handles audio focus; on a crossfade, focus is handed to the promoted player. | Far too entangled to share. Its `stop()` already cancels the crossfade and preload, stops and releases the ExoPlayer, closes streams, abandons audio focus and **keeps the queue**. |
| `playback/AndroidQueueTransitions` | Crossfade controller, driven by `ExoPlaybackPlayer`'s position. | Does nothing while `ExoPlaybackPlayer` is stopped. No change needed. |
| `playback/MediaSessionPlayer` | A hand-written Media3 `Player` adapter over `AndroidPlaybackPlayer`, main looper. | The media session's player. Media3 lets a session switch players at runtime (`MediaSession.setPlayer`, same looper required). |
| `playback/MusicService` | `MediaLibraryService`; builds `ExoPlaybackPlayer` → `MediaSessionPlayer` → one `MediaLibrarySession`. `DefaultMediaNotificationProvider`. Android Auto via `AutomotiveSessionCallback`. | The notification and foreground state follow the session's player automatically. |
| `playback/PlayerConnection` (UI) | Binds to the service with a `Binder` and reads `service.player` (**not** through the media session). | The in-app mini player will **not** see a session swap. It needs its own small DJ hook (§6). |
| Sleep timer, pause-on-mute, history, scrobbling, widgets, Discord, cast, Listen Together, Device Connect | All observe or command `ExoPlaybackPlayer` directly. | Each needs a defined behaviour while DJ Mode is on (§8). |

---

## 3. Options considered

| Option | How | Verdict |
|---|---|---|
| **A. Own decks + session swap** (chosen) | `DjMixEngine` owns two new ExoPlayers with our own processor chains. Metrolist's player is stopped; the session's player is swapped. | Full control of gain envelopes, effects and timing. Metrolist's player code untouched. Clean "two separate worlds" ownership, exactly what §9.1 asks for. |
| B. Drive Metrolist's two players | Switch Metrolist's crossfade off and steer `exo`/`nextPlayer` through `prepareNextTrack()`, `setCrossfadeVolumes()`, etc. | Fewer new classes, but the DJ would share processor chains, focus hand-over, SABR caching and recovery logic with Metrolist. Every Metrolist update could break the DJ, and the brief's "never side by side" rule would be violated in spirit. |
| C. DJ engine implements `AndroidPlaybackPlayer` | Replace `player` everywhere while DJ is on. | ~80 members, many Metrolist-specific (video, SABR, preload tokens). Impractical and fragile. |

**Decision: A.** Recorded in `DECISIONS.md` once approved.

---

## 4. Architecture

```
dj-ui        Live screen, DJ mini bar ───────────────┐ (StateFlows, commands)
androidApp   dj/host/DjPlaybackHost  ◄── MusicService hooks (the only core edits, §9)
                 │  owns the hand-over; implements DjTrackSource with DjAudioProvider
dj-engine    DjMixEngine (interface) ◄── ExoDjMixEngine (v1)   |  OboeDjMixEngine (v2, same interface)
                 ├─ Deck A / Deck B: ExoPlayer + DeckAudioChain
                 ├─ DjSessionPlayer: Media3 SimpleBasePlayer the media session shows
                 ├─ DjAudioFocus: focus, calls, ducking, "becoming noisy"
                 ├─ TransitionRunner: executes one TransitionPlan (fades, echo, sync loop)
                 └─ EffectsChannel: reserved (v2), not built in v1
dj-core      TransitionPlanner → TransitionPlan (technique, exit/entry points, length, reason)
             TransitionTechnique strategies, song-length rules (§9.2, §9.3). Pure Kotlin, unit-tested.
dj-native    effect DSP (echo in v1; EQ/filter v2) called from DeckAudioChain through JNI
```

### 4.1 Public interface (dj-engine)

```kotlin
interface DjMixEngine {
    val state: StateFlow<DjEngineState>      // Idle, Starting, Playing(now, next, transition?), Paused(reason), Ending
    val events: SharedFlow<DjEngineEvent>     // TrackStarted, TransitionStarted(plan), TransitionFinished,
                                              // TrackSkipped(reason), FocusLost, Error; EffectTriggered reserved (v2)
    val sessionPlayer: Player                 // what the media session shows while DJ Mode is on

    fun start(session: DjMixSession)          // ordered prepared tracks + settings + planner
    fun play(); fun pause()
    fun skipNext()                            // "next" button: a quick transition now
    fun updateQueue(upcoming: List<DjTrack>)  // remove/reorder (stage 10): re-plans affected transitions
    suspend fun end(fadeOutMs: Long = 1_500)  // fade, release decks and focus; returns when silent
}
```
The UI and the DJ brain only see this interface, so the v2 Oboe engine (§9.1 escalation) is a drop-in.

### 4.2 Decks

Each deck is an `ExoPlayer` built by the engine with:
- a custom `RenderersFactory` whose `DefaultAudioSink` gets **`DeckAudioChain`**: `DeckGainProcessor`
  (loudness trim × transition envelope) → `DeckFxProcessor` (native echo in v1) → `DeckLimiter`;
- `setAudioAttributes(music, handleAudioFocus = false)`: the **engine** owns focus for the whole session,
  so it never has to be handed between decks mid-blend;
- `setWakeMode(WAKE_MODE_LOCAL)` (decks play local files, §7); one shared audio session id for both decks,
  announced with `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` so system EQ apps still apply.
- Tempo matching uses `PlaybackParameters(speed, pitch = 1)` (pitch preserved), as the brief says.

Deck roles alternate: the incoming deck becomes the main deck when its transition finishes; the old deck
is stopped and **kept** (not released) for the next track, so a session uses exactly two ExoPlayers.

### 4.3 DjSessionPlayer (what the phone's controls see)

A Media3 `SimpleBasePlayer` on the main looper, built from the engine's state:
- current item = the track that is the main deck (title, artist, artwork); playlist = the remaining mix,
  so the lock screen and Android Auto show "Up next";
- commands: play/pause, next (→ `skipNext()`), stop (→ end session). **Seek and previous are disabled**:
  inside a planned mix they have no clear meaning (v2 can add "restart track");
- position/duration = the main deck's track, so the notification's progress bar is sensible.

---

## 5. The hand-over, step by step

All steps run on the main thread inside `DjPlaybackHost` (host glue, in the service process).

States: `NORMAL → ENTERING → DJ (playing | paused | waiting-for-focus) → LEAVING → NORMAL`.
Only `DjPlaybackHost` changes state; the guards in §5.3 make re-entry impossible.

### 5.1 Entering DJ Mode (after "Preparing your mix" finished)
1. Refuse with a clear message if casting, a Listen Together session or Device Connect remote playback
   is active (v1). Video mode is switched off first.
2. Save Metrolist's playback state (`savePlaybackState()`), then call Metrolist's own
   `player.stop()`: crossfade and preload cancelled, ExoPlayer released, streams closed, focus abandoned,
   **queue kept**. Cancel any pending queue request.
3. Create the engine (decks are built lazily) and `mediaSession.player = djSessionPlayer`. The
   notification now shows the DJ mix.
4. Engine requests audio focus (`AUDIOFOCUS_GAIN`, delayed focus accepted). Granted → play deck A.
   Delayed (e.g. a call is ringing) → state `waiting-for-focus`, starts by itself when granted.

### 5.2 Leaving DJ Mode
Triggers: "End session", notification stop, the mix runs out, the user starts normal playback anywhere
(app, Android Auto, a widget), cast/Listen Together connects, sleep timer ends, service destroyed.
1. `engine.end()`: both decks fade to silence (1.5 s, or immediately if paused), stop, **release**;
   processors and native FX state are freed with them; audio focus abandoned; noisy-receiver unregistered.
2. `mediaSession.player = media3Player` (Metrolist's adapter): notification shows Metrolist's paused song.
3. Metrolist's player stays **paused** on the user's previous queue. If the exit was "the user started a
   song", that request now runs normally (step 1 completes first, so the two never overlap).
4. Session audio files are pruned (stage 6 rule: keep only what the next mix still needs).

### 5.3 Guards (how "never side by side" is enforced)
- `MusicService` has one check, `djHost.ensureNormalPlayback()`, at the entry points that start Metrolist
  playback (list in §9). While DJ is on, it runs §5.2 first and returns; while `LEAVING`, the request
  waits for it to finish (a few ms; the fade is skipped for a user-initiated switch).
- The engine never touches `ExoPlaybackPlayer`, `AndroidQueueTransitions` or `MediaSessionPlayer`, and
  Metrolist never sees the decks. Shared state is limited to the media session's player slot.

---

## 6. Screens and controls during DJ Mode

| Surface | During DJ Mode |
|---|---|
| Notification, lock screen, headset/Bluetooth buttons, Wear | Via `DjSessionPlayer` (session swap). Next = mix to the next track now. |
| Android Auto | Shows the DJ mix and its queue. Picking any other item ends DJ Mode, then plays it (§5.2). |
| In-app mini player | **Deferred to stage 10** (as built in stage 8): Metrolist's mini player lives in its player sheet, which hides while its player is stopped. Stage 8 shows a "Now mixing" card on the DJ tab; the notification and lock screen show the mix everywhere. Stage 10 adds the DJ mini bar via `DjPlayback.state` (no `PlayerConnection` change needed). |
| Metrolist's full player screen | Not opened while DJ is on; the DJ tab shows the live screen (stage 10). |
| Starting any song in the app | Ends DJ Mode first (guard), then plays normally. |

---

## 7. Where the decks' audio comes from

Decks play the **local files prepared in stage 6** (`DjAudioProvider`, `keepAudio = true`): no network,
no SABR, exact seeking (needed for chorus jumps and beat alignment), and no interference with Metrolist's
stream cache. `DjPlaybackHost` implements `DjTrackSource.open(track): Uri?` for the engine and keeps the
**next two** tracks' files ready; a file deleted by the system is downloaded again ahead of time. If a
file still is not ready when its transition starts, the engine skips that track (event `TrackSkipped`)
rather than stalling the mix. Played files are deleted as the mix goes.

---

## 8. Interruptions and other Metrolist features

| Situation | Behaviour |
|---|---|
| **Phone call** (ringing or in call) | Android sends a transient focus loss. Both decks pause **together** (same main-thread call), so a blend is frozen in place. After the call, both resume; the sync loop re-checks alignment and, if a beatmatched blend is off by more than ~40 ms, finishes it as a quick classic fade instead of flamming. |
| Other app starts music | Permanent focus loss: pause, no auto-resume (Media3 convention). |
| Navigation prompt / notification sound | "Can duck": both decks drop to 20 % and come back. |
| Headphones unplugged / Bluetooth drops | "Becoming noisy": pause. |
| App in background / screen off | Unaffected: the engine lives in the foreground media service, not in the Activity; wake mode keeps the CPU on. |
| App swiped away (task removed) | Playing: the mix continues (like Metrolist). Paused: DJ Mode ends and the service may stop. |
| Process killed | The DJ session is not restored in v1; Metrolist's saved state (from §5.1 step 2) is. |
| Call or backgrounding **during the hand-over** | Hand-over steps are synchronous on the main thread, so focus and lifecycle callbacks arrive after it completes and are handled in the new state. Starting during a call → `waiting-for-focus`. |
| Sleep timer | While DJ is on, the timer **pauses the mix** (as built: same as Metrolist's own sleep timer, which pauses). |
| Pause on mute | Routed to the engine's pause/resume while DJ is on. |
| Listening history, scrobbling, Discord, widgets | v1: not updated by DJ plays (they observe Metrolist's player). Hook point reserved; decide in stage 10. |
| Cast, Listen Together, Device Connect | Refused at DJ start; connecting while DJ is on ends DJ Mode first. |
| Metrolist settings: normalization, crossfade, silence skip, tempo/pitch | Not applied to decks. The DJ has its own loudness matching and transitions. Unchanged for normal playback. |

---

## 9. Changes to Metrolist code (need the owner's OK, logged in UPSTREAM_CHANGES.md)

No changes to `ExoPlaybackPlayer`, `AndroidQueueTransitions`, `MediaSessionPlayer`, `MusicDatabase`.

| File | Change (small, additive) |
|---|---|
| `playback/MusicService.kt` | Create `DjPlaybackHost` with access to `player`, `mediaSession`, `media3Player`, `savePlaybackState`. One guard instead of per-entry-point calls (as built): an observer on `player.wantsPlaybackState` ends DJ Mode whenever Metrolist's player is asked to play; Android Auto requests reach `DjSessionPlayer.handleSetMediaItems`, which ends DJ Mode and forwards them; cast/Listen Together connecting ends DJ Mode. End DJ in `onDestroy`/`onTaskRemoved` (paused). Sleep timer and pause-on-mute routed to the engine while DJ is on. |
| `playback/PlayerConnection.kt` | Expose `djSession` and the DJ commands to the UI; normal play calls go through the guard. |
| `ui/MetrolistApp.kt` (mini-player slot, ~line 1813) | Show the DJ mini bar while `djSession` is non-null. |
| `dj/host/DjController.kt` (ours) | Hand the prepared mix to `DjPlaybackHost` instead of `playerConnection.playQueue`. |

---

## 10. Sound: loudness, limiter, transitions

- **Loudness match:** each deck gets a fixed trim `target − track LUFS` (target −14 LUFS, clamp ±12 dB)
  from the stored analysis, minus 2 dB headroom.
- **Transition envelopes** (equal-power fades, echo send) are applied inside `DeckGainProcessor` against
  the deck's **media time** (sample position), not wall-clock time, so they are sample-accurate per deck
  and freeze correctly when paused.
- **Limiter — brief item to adjust (rule 9):** the brief asks for "a safety limiter on the output". With
  two ExoPlayers there is no shared output stage in the app: Android mixes the two AudioTracks after
  us. v1 therefore puts a limiter (−1 dBFS ceiling) on **each deck** and keeps 2 dB headroom so the
  equal-power sum stays clean. A true master limiter needs the single-stream v2 Oboe engine.
- **Effects channel:** `EffectsChannel` and the `EffectTriggered` event exist in the interface only (v2).

## 11. Timing and beat sync (built in stage 9, designed now)

- **Clock:** `ExoPlayer.currentPosition` is derived from the AudioTrack's output timestamp
  (`AudioTrack.getTimestamp`, via Media3's position tracker), i.e. "what is audible now", including
  output latency. ExoPlayer does not expose `getTimestamp` itself (brief wording adjusted); reading both
  decks' positions back-to-back on the main thread gives their phase difference within a few ms.
- **Start:** the incoming deck is prepared and paused at its cue, started **two beats early at zero gain**
  with the tempo-matched speed, then pulled into phase with small speed nudges (≤ ±1 %) before its gain
  envelope opens on the phrase boundary.
- **Drift loop:** every 50 ms during the overlap, phase error in ms → proportional speed nudge. Target
  < 20 ms. If lock is not reached within 4 beats, the runner switches the plan to echo-out/classic fade.
- **Fallbacks:** low grid confidence or BPM gap > 6 % → echo out; analysis missing → classic fade.

## 12. What could go wrong (and the answer)

| Risk | Mitigation / how we find out |
|---|---|
| Swapping the session's player confuses a controller (Auto, Wear) for a moment | Media3 supports `setPlayer` officially; tested in stage 8 with notification, lock screen, a Bluetooth headset and the Android Auto desktop head unit. |
| A leftover deck or processor after exit ("half-alive" deck) | `end()` releases both decks and is idempotent; stage 8 check: exit mid-blend, then play a normal song and confirm one AudioTrack (`dumpsys media.audio_flinger`). |
| Metrolist starts playing while a deck still sounds | Single guard (§5.3) runs the exit first; Metrolist's player is stopped, not just paused, while DJ is on. |
| Speed nudges via `PlaybackParameters` cause audible glitches | Measured in stage 9 with the Lab's transition audition. Fallback: do the nudge in our own processor (tiny native resampler), keeping `PlaybackParameters` for the coarse tempo match. |
| Two decks cannot hold < 20 ms on some phones | Logged phase error per transition; automatic fall back to echo out; v2 Oboe engine if it is common. |
| Clipping when two loud tracks overlap | Headroom + per-deck limiters (§10). |
| Mini player shows Metrolist's paused song during DJ | The `djSession` hook replaces the mini-player slot (§6). |
| Session file deleted by Android while mixing | Next two files kept ready and re-downloaded if missing (§7). |
| History/scrobbles miss DJ plays | Known v1 gap, decided in stage 10. |
| Upstream Metrolist changes break the hooks | Hooks are few, additive and listed in UPSTREAM_CHANGES.md. |

## 13. Stage 8 checks (from STAGES.md, made concrete)

1. A 15-minute DJ session plays continuously with classic fades, screen off, through a phone call
   (pauses and resumes), with headphones unplugged (pauses).
2. Notification and lock screen show the DJ track; next mixes to the next track; stop ends DJ Mode.
3. End the session mid-fade, then play a normal song: normal Metrolist playback, crossfade, queue and
   the previous queue all work. Only one audio stream is active.
4. Start a normal song from Home while DJ is on: DJ ends cleanly first.
