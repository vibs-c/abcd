# Changelog

All notable changes to abcd. Versions follow [semantic versioning](https://semver.org/).

## 1.0.0-alpha2 · 2026-11-23

### Fixed
- **"Could not build the mix … SABR stream protection requires attestation"**: since about 20 November YouTube
  stops serving a song through its web player client after the first minute (HTTP 403), so every song failed to
  download for analysis. Downloads now fetch the way playback does, report a refused client, and retry through
  one that still works (as normal playback does). Mixes prepare again: 48 of 48 songs on the test device.
- Updated the YouTube library (InnerTubeX v0.11.7 → v0.13.0): it stops picking two web stream types YouTube now
  refuses on signed-in Android phones, plays re-released songs under their new IDs, and fixes some ciphered
  streams. Normal playback can fall back to a lower-quality stream as a last resort.

## 1.0.0-alpha1 · 2026-11-22

The first alpha of abcd's DJ Mode.

### DJ Mode
- **Start a mix** from the DJ tab: choose languages, a mood, a mix style (Smooth, Radio, Chorus Hits) and a
  session length.
- **Song picking** from YouTube Music search, "up next" radio, the Home mood chips and your liked and
  library songs, kept to the languages you chose, with the song's lyrics checked too.
- **On-device analysis** of every song before it plays: tempo, beat grid, key, loudness, sections and
  chorus. Each song is analysed once and the result is kept.
- **Ordering** by tempo, key and an energy arc that suits the mood.
- **Two-deck mixing**: beat-matched blends with tempo matching and a bass swap, echo outs, and classic
  fades as a fallback.
- **Live screen**: artwork, now playing and up next, a timeline with the waveform and the coming mix, thumbs
  up/down on each transition, and a queue you can reorder or trim ("never play" too).
- **Mini bar** on every other tab, plus notification and lock-screen controls.
- **Learns from you**: banned songs never return, removed or skipped songs sink, and liked pairs and
  transition styles are chosen more often. Everything stays on the phone.
- **Lab** (developer tools, hidden: tap the "Start a mix" title 7 times): analyse any song, inspect and
  correct its language, type, mood and vibe, see everything saved about it, and audition transitions.

### Privacy
- No analytics, telemetry or crash reporting.

### Known limits
- Android only, distributed as an APK.
- The first mix takes a few minutes while its songs are analysed; later mixes reuse the analyses.
- Language detection knows Indian languages and English; other languages in an English-only mix are not
  always caught. Correct a song in the Lab and the DJ remembers.
