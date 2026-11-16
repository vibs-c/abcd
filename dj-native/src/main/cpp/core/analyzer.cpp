#include "analyzer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

#include <essentia/algorithmfactory.h>
#include <essentia/essentia.h>

#include "dsp.h"
#include "structure.h"

namespace abcd {

namespace {

constexpr int kAnalysisRate = 44100;  // Essentia's rhythm and key algorithms are tuned for 44.1 kHz
constexpr int kFrameSize = 4096;      // 93 ms: long enough to resolve notes (chroma)
constexpr int kHopSize = 2048;        // 46 ms between frames
constexpr int kBeatsPerBar = 4;       // assumption from the brief: 4/4
constexpr int kMinSeconds = 10;
constexpr int kWaveformPeaks = 1000;
constexpr int kChromaBins = 12;
constexpr int kMfccCoefficients = 13;

std::mutex gAnalysisMutex;  // Essentia's factory is not documented as thread-safe
std::once_flag gEssentiaInit;

void ensureEssentia() {
    std::call_once(gEssentiaInit, [] { essentia::init(); });
}

using essentia::Real;
using essentia::standard::Algorithm;
using essentia::standard::AlgorithmFactory;

// Owns an Essentia algorithm instance.
struct Algo {
    std::unique_ptr<Algorithm> ptr;
    template <typename... Args>
    explicit Algo(const std::string& name, Args... args)
        : ptr(AlgorithmFactory::create(name, args...)) {}
    Algorithm* operator->() { return ptr.get(); }
};

// Features for every analysis frame (46 ms), from one shared spectrum per frame.
struct FrameFeatures {
    std::vector<double> centerSec;
    std::vector<std::array<float, kChromaBins>> chroma;      // which notes sound
    std::vector<std::array<float, kMfccCoefficients>> mfcc;  // timbre
    std::vector<float> rms;
    std::vector<float> lowEnergy;  // 20-150 Hz: kick and bass, strongest on the downbeat
    std::vector<float> midEnergy;  // 300-3400 Hz: vocals and lead instruments
    std::vector<float> totalEnergy;
};

// Idea: slide a window over the song and, for each position, compute a spectrum once and derive
// everything from it: chroma (energy per musical note), MFCCs (overall sound colour), and energy in
// the bass and vocal bands.
FrameFeatures extractFrames(const std::vector<float>& signal) {
    FrameFeatures out;
    Algo window("Windowing", "type", "hann", "size", kFrameSize);
    Algo spectrum("Spectrum", "size", kFrameSize);
    Algo peaks("SpectralPeaks", "sampleRate", static_cast<Real>(kAnalysisRate), "minFrequency", 40.f, "maxFrequency", 5000.f,
               "maxPeaks", 100, "magnitudeThreshold", 1e-5f);
    Algo hpcp("HPCP", "size", kChromaBins, "sampleRate", static_cast<Real>(kAnalysisRate), "minFrequency", 40.f, "maxFrequency", 5000.f);
    Algo mfcc("MFCC", "inputSize", kFrameSize / 2 + 1, "sampleRate", static_cast<Real>(kAnalysisRate), "numberCoefficients",
              kMfccCoefficients, "numberBands", 40);

    std::vector<Real> frame(kFrameSize), windowed, spec, freqs, mags, chromaVec, bands, coeffs;
    window->input("frame").set(frame);
    window->output("frame").set(windowed);
    spectrum->input("frame").set(windowed);
    spectrum->output("spectrum").set(spec);
    peaks->input("spectrum").set(spec);
    peaks->output("frequencies").set(freqs);
    peaks->output("magnitudes").set(mags);
    hpcp->input("frequencies").set(freqs);
    hpcp->input("magnitudes").set(mags);
    hpcp->output("hpcp").set(chromaVec);
    mfcc->input("spectrum").set(spec);
    mfcc->output("bands").set(bands);
    mfcc->output("mfcc").set(coeffs);

    const double binHz = static_cast<double>(kAnalysisRate) / kFrameSize;
    auto bin = [&](double hz) { return static_cast<size_t>(hz / binHz); };
    const size_t lowFrom = bin(20), lowTo = bin(150), midFrom = bin(300), midTo = bin(3400);

    for (size_t start = 0; start + kFrameSize <= signal.size(); start += kHopSize) {
        double sumSquares = 0;
        for (int i = 0; i < kFrameSize; ++i) {
            frame[i] = signal[start + i];
            sumSquares += static_cast<double>(frame[i]) * frame[i];
        }
        window->compute();
        spectrum->compute();
        peaks->compute();
        hpcp->compute();
        mfcc->compute();

        float low = 0, mid = 0, total = 0;
        for (size_t k = 0; k < spec.size(); ++k) {
            const float p = spec[k] * spec[k];
            total += p;
            if (k >= lowFrom && k <= lowTo) low += p;
            if (k >= midFrom && k <= midTo) mid += p;
        }
        std::array<float, kChromaBins> chromaRow{};
        for (int i = 0; i < kChromaBins && i < static_cast<int>(chromaVec.size()); ++i) chromaRow[i] = chromaVec[i];
        std::array<float, kMfccCoefficients> mfccRow{};
        for (int i = 0; i < kMfccCoefficients && i < static_cast<int>(coeffs.size()); ++i) mfccRow[i] = coeffs[i];

        out.centerSec.push_back((static_cast<double>(start) + kFrameSize / 2.0) / kAnalysisRate);
        out.chroma.push_back(chromaRow);
        out.mfcc.push_back(mfccRow);
        out.rms.push_back(static_cast<float>(std::sqrt(sumSquares / kFrameSize)));
        out.lowEnergy.push_back(low);
        out.midEnergy.push_back(mid);
        out.totalEnergy.push_back(total);
    }
    return out;
}

// Pitch class (0 = C) from an Essentia key name such as "A", "C#" or "Bb".
int pitchClassOf(const std::string& name) {
    static const int natural[7] = {9, 11, 0, 2, 4, 5, 7};  // A B C D E F G
    if (name.empty() || name[0] < 'A' || name[0] > 'G') return 0;
    int pc = natural[name[0] - 'A'];
    if (name.size() > 1 && name[1] == '#') pc += 1;
    if (name.size() > 1 && (name[1] == 'b')) pc -= 1;
    return (pc + 12) % 12;
}

// Mean of `values` over frames whose centre lies in [fromSec, toSec).
struct FrameRange {
    size_t first = 0, last = 0;  // [first, last)
};
FrameRange framesBetween(const FrameFeatures& f, double fromSec, double toSec) {
    FrameRange range;
    range.first = static_cast<size_t>(std::lower_bound(f.centerSec.begin(), f.centerSec.end(), fromSec) - f.centerSec.begin());
    range.last = static_cast<size_t>(std::lower_bound(f.centerSec.begin(), f.centerSec.end(), toSec) - f.centerSec.begin());
    return range;
}

// Idea: in most dance and film music the kick and bass land hardest on beat 1 of the bar. We try the
// four possible phases for "which beat is beat 1", add up the bass energy at each, and the phase
// with the most bass wins. The margin over the others tells how sure we are.
int downbeatPhase(const std::vector<double>& beatsSec, const FrameFeatures& f, double& confidence) {
    std::array<double, kBeatsPerBar> sum{};
    std::array<int, kBeatsPerBar> count{};
    for (size_t i = 0; i < beatsSec.size(); ++i) {
        const FrameRange r = framesBetween(f, beatsSec[i] - 0.02, beatsSec[i] + 0.07);
        double bass = 0;
        for (size_t k = r.first; k < r.last; ++k) bass += f.lowEnergy[k];
        sum[i % kBeatsPerBar] += bass;
        count[i % kBeatsPerBar] += 1;
    }
    std::array<double, kBeatsPerBar> mean{};
    int best = 0;
    for (int p = 0; p < kBeatsPerBar; ++p) {
        mean[p] = count[p] > 0 ? sum[p] / count[p] : 0;
        if (mean[p] > mean[best]) best = p;
    }
    double others = 0;
    for (int p = 0; p < kBeatsPerBar; ++p) if (p != best) others += mean[p];
    others /= (kBeatsPerBar - 1);
    confidence = mean[best] > 0 ? std::clamp((mean[best] - others) / mean[best], 0.0, 1.0) : 0.0;
    return best;
}

std::vector<int> waveformPeaks(const std::vector<float>& signal) {
    std::vector<int> peaks(kWaveformPeaks, 0);
    float global = 1e-9f;
    for (float v : signal) global = std::max(global, std::fabs(v));
    const size_t perBucket = std::max<size_t>(1, signal.size() / kWaveformPeaks);
    for (int b = 0; b < kWaveformPeaks; ++b) {
        float peak = 0;
        const size_t from = static_cast<size_t>(b) * perBucket;
        const size_t to = std::min(signal.size(), from + perBucket);
        for (size_t i = from; i < to; ++i) peak = std::max(peak, std::fabs(signal[i]));
        peaks[b] = static_cast<int>(std::lround(255.0f * peak / global));
    }
    return peaks;
}

}  // namespace

std::string camelotCode(int pitchClass, bool minor) {
    const int majorRoot = minor ? (pitchClass + 3) % 12 : pitchClass;  // relative major shares the number
    const int fifths = (majorRoot * 7) % 12;                           // steps around the circle of fifths from C
    const int number = (fifths + 7) % 12 + 1;                          // C major = 8B
    return std::to_string(number) + (minor ? "A" : "B");
}

AnalysisResult analyzeTrack(const float* monoInput, size_t sampleCount, int sampleRate) {
    if (sampleRate <= 0 || monoInput == nullptr || sampleCount == 0) throw std::runtime_error("No audio");
    if (sampleCount < static_cast<size_t>(kMinSeconds) * sampleRate) throw std::runtime_error("Audio too short to analyse");

    std::lock_guard<std::mutex> lock(gAnalysisMutex);
    ensureEssentia();

    AnalysisResult result;
    result.analysisVersion = 1;
    result.analysisSampleRate = kAnalysisRate;
    result.durationMs = 1000.0 * static_cast<double>(sampleCount) / sampleRate;

    // Everything below works at 44.1 kHz.
    const std::vector<Real> signal = resample(monoInput, sampleCount, sampleRate, kAnalysisRate);
    double peak = 0;
    for (float v : signal) peak = std::max(peak, static_cast<double>(std::fabs(v)));
    if (peak < 1e-4) throw std::runtime_error("Audio is silent");

    result.lufs = integratedLoudness(signal, kAnalysisRate);
    result.waveformPeaks = waveformPeaks(signal);

    // Tempo and beats (multi-feature method: slower, more accurate; reports a confidence).
    {
        Algo rhythm("RhythmExtractor2013", "method", "multifeature", "minTempo", 70, "maxTempo", 180);
        std::vector<Real> ticks, estimates, intervals;
        Real bpm = 0, confidence = 0;
        rhythm->input("signal").set(signal);  // read in place, no copy
        rhythm->output("bpm").set(bpm);
        rhythm->output("ticks").set(ticks);
        rhythm->output("confidence").set(confidence);
        rhythm->output("estimates").set(estimates);
        rhythm->output("bpmIntervals").set(intervals);
        rhythm->compute();
        result.bpm = bpm;
        result.bpmConfidenceRaw = confidence;
        result.bpmConfidence = std::clamp(static_cast<double>(confidence) / 3.5, 0.0, 1.0);  // >3.5 is "excellent" in Essentia's scale
        // The tracker needs a few seconds to lock on, so its first tick can be several beats after the
        // start. Extend the grid backwards at the same tempo so bar 0 is the real start of the song.
        if (ticks.size() >= 8) {
            const double period = (ticks[7] - ticks[0]) / 7.0;
            std::vector<double> head;
            for (double t = ticks[0] - period; t > -0.03 && period > 0.2; t -= period) head.push_back(std::max(0.0, t));
            for (auto it = head.rbegin(); it != head.rend(); ++it) result.beatsMs.push_back(1000.0 * *it);
        }
        for (Real t : ticks) result.beatsMs.push_back(1000.0 * t);
    }

    // Key and mode.
    {
        Algo keyExtractor("KeyExtractor", "sampleRate", static_cast<Real>(kAnalysisRate));

        std::string key, scale;
        Real strength = 0;
        keyExtractor->input("audio").set(signal);
        keyExtractor->output("key").set(key);
        keyExtractor->output("scale").set(scale);
        keyExtractor->output("strength").set(strength);
        keyExtractor->compute();
        result.key.root = key;
        result.key.mode = scale;
        result.key.pitchClass = pitchClassOf(key);
        result.key.camelot = camelotCode(result.key.pitchClass, scale == "minor");
        result.key.confidence = std::clamp(static_cast<double>(strength), 0.0, 1.0);
    }

    const double durationSec = static_cast<double>(signal.size()) / kAnalysisRate;
    const std::vector<double> beatsSec = [&] {
        std::vector<double> v;
        for (double ms : result.beatsMs) v.push_back(ms / 1000.0);
        return v;
    }();

    // Bars: group beats in fours starting from the most likely downbeat.
    if (beatsSec.size() < static_cast<size_t>(2 * kBeatsPerBar)) return result;  // not enough beats for a bar grid

    const FrameFeatures frames = extractFrames(signal);
    if (frames.centerSec.empty()) return result;
    const int phase = downbeatPhase(beatsSec, frames, result.downbeatConfidence);
    result.beatsPerBar = kBeatsPerBar;

    std::vector<double> barStartsSec;
    for (size_t i = phase; i + kBeatsPerBar <= beatsSec.size(); i += kBeatsPerBar) barStartsSec.push_back(beatsSec[i]);
    if (barStartsSec.size() < 2) return result;
    const double barSec = (barStartsSec.back() - barStartsSec.front()) / (barStartsSec.size() - 1);
    for (double t : barStartsSec) result.barStartsMs.push_back(1000.0 * t);

    // Per-bar features from the frames inside each bar.
    BarFeatures bars;
    for (size_t b = 0; b < barStartsSec.size(); ++b) {
        const double t0 = barStartsSec[b];
        const double t1 = (b + 1 < barStartsSec.size()) ? barStartsSec[b + 1] : std::min(durationSec, t0 + barSec);
        const FrameRange r = framesBetween(frames, t0, t1);
        std::vector<float> chroma(kChromaBins, 0.f), timbre(kMfccCoefficients - 1, 0.f);
        double sumSquares = 0, mid = 0, total = 0;
        const size_t n = std::max<size_t>(1, r.last - r.first);
        for (size_t k = r.first; k < r.last; ++k) {
            for (int c = 0; c < kChromaBins; ++c) chroma[c] += frames.chroma[k][c] / n;
            for (int m = 1; m < kMfccCoefficients; ++m) timbre[m - 1] += frames.mfcc[k][m] / n;  // skip c0 (overall level)
            sumSquares += static_cast<double>(frames.rms[k]) * frames.rms[k];
            mid += frames.midEnergy[k];
            total += frames.totalEnergy[k];
        }
        bars.chroma.push_back(chroma);
        bars.timbre.push_back(timbre);
        bars.rms.push_back(static_cast<float>(std::sqrt(sumSquares / n)));
        bars.midRatio.push_back(total > 0 ? static_cast<float>(mid / total) : 0.f);
    }

    const StructureResult structure = analyzeStructure(bars, result.barStartsMs, result.durationMs);
    result.energyCurve = structure.energyCurve;
    result.sections = structure.sections;
    result.chorusCandidates = structure.chorusCandidates;
    for (int bar : structure.phraseStartBars) result.phraseStartsMs.push_back(result.barStartsMs[bar]);
    result.introMixableEndMs = structure.introMixableEndBar > 0 ? result.barStartsMs[structure.introMixableEndBar - 1] + 1000.0 * barSec : 0.0;
    result.outroMixableStartMs = structure.outroMixableStartBar < static_cast<int>(result.barStartsMs.size())
                                     ? result.barStartsMs[structure.outroMixableStartBar]
                                     : result.durationMs;
    return result;
}

}  // namespace abcd
