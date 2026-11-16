// A synthetic "song" with known tempo, key and chorus positions, used to test the analyzer without
// any audio file. Not part of the app: only the development tool includes this header.
//
//   126 BPM, 4/4, A minor, 52 bars (99 s)
//   bars:  0-3 intro | 4-11 verse | 12-19 CHORUS | 20-27 verse | 28-35 CHORUS | 36-39 bridge | 40-47 CHORUS | 48-51 outro
//
// Choruses are louder and play the same lead melody each time; verses use different chords and are
// quieter; beat 1 of every bar carries an extra bass note, as in most dance music.
#ifndef ABCD_SYNTH_SONG_H
#define ABCD_SYNTH_SONG_H

#include <cmath>
#include <cstdint>
#include <utility>
#include <vector>

namespace abcd_test {

struct SynthSong {
    int sampleRate = 44100;
    double bpm = 126.0;
    int bars = 52;
    std::vector<float> pcm;
    std::vector<std::pair<int, int>> chorusBars = {{12, 20}, {28, 36}, {40, 48}};  // [start, end)
    int expectedPitchClass = 9;  // A
    bool expectedMinor = true;
};

namespace detail {

constexpr double kPi = 3.14159265358979323846;

inline double midiToHz(double midi) { return 440.0 * std::pow(2.0, (midi - 69.0) / 12.0); }

struct Noise {
    uint32_t state = 12345;
    double next() {  // white noise in [-1, 1]
        state = state * 1664525u + 1013904223u;
        return (static_cast<double>(state >> 8) / 8388608.0) - 1.0;
    }
};

enum class Part { Intro, Verse, Chorus, Bridge, Outro };

struct BarPlan {
    Part part;
    int barInPart;
};

inline std::vector<BarPlan> plan() {
    std::vector<BarPlan> bars;
    auto add = [&](Part part, int count) {
        for (int i = 0; i < count; ++i) bars.push_back({part, i});
    };
    add(Part::Intro, 4);
    add(Part::Verse, 8);
    add(Part::Chorus, 8);
    add(Part::Verse, 8);
    add(Part::Chorus, 8);
    add(Part::Bridge, 4);
    add(Part::Chorus, 8);
    add(Part::Outro, 4);
    return bars;
}

// Chord tones (MIDI) and bass root per chord. All chords belong to A minor (E major is the dominant).
struct Chord {
    int tones[3];
    int bass;
};
constexpr Chord Am{{57, 60, 64}, 33};
constexpr Chord Dm{{50, 53, 57}, 38};
constexpr Chord F{{53, 57, 60}, 29};
constexpr Chord C{{55, 60, 64}, 36};
constexpr Chord G{{55, 59, 62}, 31};
constexpr Chord E{{52, 56, 59}, 28};

inline const Chord& chordFor(Part part, int barInPart) {
    static const Chord verse[4] = {Am, Dm, Am, E};
    static const Chord chorus[4] = {F, C, G, Am};
    static const Chord bridge[4] = {Dm, G, C, E};
    switch (part) {
        case Part::Verse: return verse[barInPart % 4];
        case Part::Chorus: return chorus[barInPart % 4];
        case Part::Bridge: return bridge[barInPart % 4];
        default: return Am;
    }
}

}  // namespace detail

inline SynthSong makeSynthSong() {
    using namespace detail;
    SynthSong song;
    const int sr = song.sampleRate;
    const int beatSamples = static_cast<int>(std::lround(sr * 60.0 / song.bpm));  // 21000 at 126 BPM
    const int barSamples = 4 * beatSamples;
    const std::vector<BarPlan> bars = plan();
    song.pcm.assign(static_cast<size_t>(bars.size()) * barSamples, 0.f);
    Noise noise;

    auto add = [&](size_t index, double value) {
        if (index < song.pcm.size()) song.pcm[index] += static_cast<float>(value);
    };

    for (size_t bar = 0; bar < bars.size(); ++bar) {
        const Part part = bars[bar].part;
        const int inPart = bars[bar].barInPart;
        const Chord& chord = chordFor(part, inPart);
        const size_t barStart = bar * static_cast<size_t>(barSamples);

        const double kickAmp = part == Part::Intro ? 0.5 : part == Part::Outro ? 0.4 : 0.7;
        const double snareAmp = (part == Part::Verse || part == Part::Chorus) ? 0.25 : 0.0;
        const double hatAmp = part == Part::Chorus ? 0.06 : part == Part::Verse ? 0.03 : 0.0;
        const double padAmp = part == Part::Intro ? 0.0 : part == Part::Verse ? 0.12 : part == Part::Chorus ? 0.30 : part == Part::Bridge ? 0.18 : 0.10;
        const double leadAmp = part == Part::Chorus ? 0.22 : part == Part::Verse ? 0.05 : part == Part::Bridge ? 0.10 : 0.0;

        // Pad: chord tones with a gentle fade at the bar edges.
        if (padAmp > 0) {
            for (int n = 0; n < barSamples; ++n) {
                const double t = static_cast<double>(n) / sr;
                const double fade = std::min({1.0, n / (0.02 * sr), (barSamples - n) / (0.02 * sr)});
                double v = 0;
                for (int tone : chord.tones) {
                    const double hz = midiToHz(tone);
                    v += std::sin(2 * kPi * hz * t) + 0.3 * std::sin(4 * kPi * hz * t);
                }
                add(barStart + n, padAmp * fade * v / 3.0);
            }
        }

        for (int beat = 0; beat < 4; ++beat) {
            const size_t beatStart = barStart + static_cast<size_t>(beat) * beatSamples;

            // Kick: a sine that sweeps down from ~135 Hz to 45 Hz, every beat.
            double phase = 0;
            for (int n = 0; n < sr / 4; ++n) {
                const double t = static_cast<double>(n) / sr;
                phase += 2 * kPi * (45.0 + 90.0 * std::exp(-t * 35.0)) / sr;
                add(beatStart + n, kickAmp * std::exp(-t * 12.0) * std::sin(phase));
            }
            // Bass note on beat 1 only: makes the downbeat the strongest bass moment of the bar.
            if (beat == 0) {
                const double hz = midiToHz(chord.bass);
                for (int n = 0; n < static_cast<int>(1.5 * beatSamples); ++n) {
                    const double t = static_cast<double>(n) / sr;
                    add(beatStart + n, 0.5 * std::exp(-t * 2.5) * (std::sin(2 * kPi * hz * t) + 0.4 * std::sin(4 * kPi * hz * t)));
                }
            }
            // Snare on beats 2 and 4: a noise burst plus a short tone.
            if (snareAmp > 0 && (beat == 1 || beat == 3)) {
                for (int n = 0; n < sr / 6; ++n) {
                    const double t = static_cast<double>(n) / sr;
                    add(beatStart + n, snareAmp * std::exp(-t * 25.0) * (0.8 * noise.next() + 0.4 * std::sin(2 * kPi * 190.0 * t)));
                }
            }
            // Hi-hats on eighth notes: differenced noise = bright, short.
            if (hatAmp > 0) {
                for (int eighth = 0; eighth < 2; ++eighth) {
                    double previous = 0;
                    for (int n = 0; n < sr / 20; ++n) {
                        const double t = static_cast<double>(n) / sr;
                        const double x = noise.next();
                        add(beatStart + eighth * beatSamples / 2 + n, hatAmp * std::exp(-t * 80.0) * (x - previous));
                        previous = x;
                    }
                }
            }
            // Lead: one note per beat, a fixed motif so every chorus plays the same melody.
            if (leadAmp > 0) {
                static const int chorusMotif[8] = {69, 72, 76, 72, 74, 72, 69, 67};
                static const int verseMotif[4] = {57, 60, 64, 60};
                const int note = part == Part::Verse ? verseMotif[beat] : chorusMotif[(inPart * 4 + beat) % 8];
                const double hz = midiToHz(note);
                for (int n = 0; n < beatSamples; ++n) {
                    const double t = static_cast<double>(n) / sr;
                    add(beatStart + n, leadAmp * std::exp(-t * 3.0) * std::sin(2 * kPi * hz * t));
                }
            }
        }
    }

    // Tiny noise floor, then normalise to 0.8 peak.
    float peak = 1e-9f;
    for (auto& v : song.pcm) {
        v += static_cast<float>(0.002 * noise.next());
        peak = std::max(peak, std::fabs(v));
    }
    for (auto& v : song.pcm) v *= 0.8f / peak;
    return song;
}

}  // namespace abcd_test

#endif  // ABCD_SYNTH_SONG_H
