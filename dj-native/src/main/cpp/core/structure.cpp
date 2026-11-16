#include "structure.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace abcd {

namespace {

constexpr int kSmoothBars = 4;     // diagonal smoothing window of the similarity matrix
constexpr int kMinRepeatBars = 8;  // a repeat must last at least this long to count
constexpr int kChorusBars = 8;     // core length searched for; extended afterwards
constexpr int kMaxChorusBars = 16;
constexpr int kMinBarsForStructure = 16;

using Matrix = std::vector<std::vector<float>>;

float dot(const std::vector<float>& a, const std::vector<float>& b) {
    float sum = 0;
    for (size_t i = 0; i < a.size(); ++i) sum += a[i] * b[i];
    return sum;
}

void l2Normalize(std::vector<float>& v) {
    const float norm = std::sqrt(dot(v, v));
    if (norm > 1e-9f) for (float& x : v) x /= norm;
}

double percentile(std::vector<double> values, double p) {
    if (values.empty()) return 0;
    std::sort(values.begin(), values.end());
    const double index = p * (values.size() - 1);
    const size_t lo = static_cast<size_t>(index);
    const size_t hi = std::min(values.size() - 1, lo + 1);
    return values[lo] + (values[hi] - values[lo]) * (index - lo);
}

// Idea: a bar-by-bar self-similarity matrix. Cell (i, j) says how alike bar i and bar j sound, from
// three views: harmony (chroma), timbre (MFCC) and loudness. Repeated parts of a song, such as the
// choruses, show up as bright stripes parallel to the main diagonal.
Matrix similarityMatrix(const BarFeatures& f, const std::vector<double>& energy01) {
    const size_t bars = f.chroma.size();
    std::vector<std::vector<float>> chroma = f.chroma;
    for (auto& row : chroma) l2Normalize(row);

    // Timbre: remove each coefficient's mean and spread across the song so no single one dominates.
    std::vector<std::vector<float>> timbre = f.timbre;
    const size_t dims = timbre.empty() ? 0 : timbre[0].size();
    for (size_t d = 0; d < dims; ++d) {
        double mean = 0, var = 0;
        for (size_t b = 0; b < bars; ++b) mean += timbre[b][d];
        mean /= bars;
        for (size_t b = 0; b < bars; ++b) var += (timbre[b][d] - mean) * (timbre[b][d] - mean);
        const double sd = std::sqrt(var / bars) + 1e-9;
        for (size_t b = 0; b < bars; ++b) timbre[b][d] = static_cast<float>((timbre[b][d] - mean) / sd);
    }
    for (auto& row : timbre) l2Normalize(row);

    Matrix s(bars, std::vector<float>(bars, 0.f));
    for (size_t i = 0; i < bars; ++i) {
        for (size_t j = i; j < bars; ++j) {
            const float harmony = std::max(0.f, dot(chroma[i], chroma[j]));
            const float timbreSim = 0.5f * (dot(timbre[i], timbre[j]) + 1.f);
            const float loud = 1.f - static_cast<float>(std::fabs(energy01[i] - energy01[j]));
            const float v = 0.45f * harmony + 0.40f * timbreSim + 0.15f * loud;
            s[i][j] = s[j][i] = v;
        }
    }
    return s;
}

// Average of `len` consecutive cells along a diagonal, starting at (i, j).
float diagonalMean(const Matrix& s, int i, int j, int len) {
    float sum = 0;
    for (int k = 0; k < len; ++k) sum += s[i + k][j + k];
    return sum / len;
}

// Idea: a boundary is where the music before it resembles itself, the music after it resembles
// itself, but the two sides do not resemble each other. This "checkerboard" score over the
// similarity matrix peaks at section changes (for example, where the verse starts after an intro).
std::vector<double> noveltyCurve(const Matrix& s, int half) {
    const int bars = static_cast<int>(s.size());
    std::vector<double> novelty(bars, 0.0);
    for (int b = 2; b + 2 <= bars; ++b) {
        const int h = std::min({half, b, bars - b});  // narrower window near the edges of the song
        double within = 0, across = 0;
        for (int i = 0; i < h; ++i) {
            for (int j = 0; j < h; ++j) {
                within += s[b - h + i][b - h + j] + s[b + i][b + j];
                across += s[b - h + i][b + j];
            }
        }
        novelty[b] = within / (2.0 * h * h) - across / (static_cast<double>(h) * h);
    }
    return novelty;
}

}  // namespace

StructureResult analyzeStructure(const BarFeatures& features, const std::vector<double>& barStartsMs, double durationMs) {
    StructureResult result;
    const int bars = static_cast<int>(features.chroma.size());
    if (bars == 0) return result;

    // Per-bar loudness scaled so the loud bars sit near 1 (95th percentile) and quiet ones near 0.
    std::vector<double> rms(features.rms.begin(), features.rms.end());
    const double top = std::max(1e-9, percentile(rms, 0.95));
    const double bottom = percentile(rms, 0.05);
    result.energyCurve.resize(bars);
    std::vector<double> energy01(bars);
    for (int b = 0; b < bars; ++b) {
        energy01[b] = std::clamp((rms[b] - bottom) / std::max(1e-9, top - bottom), 0.0, 1.0);
        result.energyCurve[b] = std::clamp(rms[b] / top, 0.0, 1.0);
    }

    auto barStart = [&](int b) { return b >= bars ? durationMs : barStartsMs[b]; };
    auto section = [&](SectionType type, int from, int to, double confidence) {
        Section s;
        s.type = type;
        s.startMs = barStart(from);
        s.endMs = barStart(to);
        s.confidence = confidence;
        result.sections.push_back(s);
    };

    // Mixable intro/outro: leading/trailing bars whose mid-range (vocal/lead) share stays low.
    {
        std::vector<double> mid(features.midRatio.begin(), features.midRatio.end());
        const double limit = percentile(mid, 0.4);
        int lead = 0;
        while (lead < bars && lead < 16 && mid[lead] <= limit) ++lead;
        int trail = 0;
        while (trail < bars && trail < 16 && mid[bars - 1 - trail] <= limit) ++trail;
        result.introMixableEndBar = lead;
        result.outroMixableStartBar = bars - trail;
    }

    if (bars < kMinBarsForStructure) {
        section(SectionType::Verse, 0, bars, 0.2);
        return result;
    }

    // 1. Repetition: find stripes of high similarity at every lag and count how often each bar repeats.
    const Matrix s = similarityMatrix(features, energy01);
    const std::vector<double> novelty = noveltyCurve(s, 4);
    double maxNovelty = 1e-9;
    for (double n : novelty) maxNovelty = std::max(maxNovelty, n);
    // A bar is a structural boundary when the novelty curve peaks there clearly.
    std::vector<char> isBoundary(bars, 0);
    for (int b = 1; b + 1 < bars; ++b) {
        if (novelty[b] >= 0.5 * maxNovelty && novelty[b] >= novelty[b - 1] && novelty[b] >= novelty[b + 1]) isBoundary[b] = 1;
    }
    std::vector<double> stripeValues;
    for (int lag = kMinRepeatBars; lag + kSmoothBars <= bars; ++lag) {
        for (int i = 0; i + lag + kSmoothBars <= bars; ++i) stripeValues.push_back(diagonalMean(s, i, i + lag, kSmoothBars));
    }
    double mean = 0, var = 0;
    for (double v : stripeValues) mean += v;
    mean /= std::max<size_t>(1, stripeValues.size());
    for (double v : stripeValues) var += (v - mean) * (v - mean);
    const double sd = std::sqrt(var / std::max<size_t>(1, stripeValues.size()));
    const double threshold = std::clamp(mean + 0.75 * sd, 0.55, 0.95);

    std::vector<double> repeats(bars, 0.0);
    for (int lag = kMinRepeatBars; lag + kSmoothBars <= bars; ++lag) {
        int runStart = -1;
        for (int i = 0; i + lag + kSmoothBars <= bars + 1; ++i) {
            const bool hit = i + lag + kSmoothBars <= bars && diagonalMean(s, i, i + lag, kSmoothBars) >= threshold;
            if (hit && runStart < 0) runStart = i;
            if ((!hit || i + lag + kSmoothBars == bars) && runStart >= 0) {
                const int runEnd = hit ? i : i - 1;  // last hit start index
                const int covered = runEnd - runStart + kSmoothBars;
                if (covered >= kMinRepeatBars) {
                    for (int k = 0; k < covered; ++k) {
                        repeats[runStart + k] += 1.0;
                        repeats[runStart + lag + k] += 1.0;
                    }
                }
                runStart = -1;
            }
        }
    }
    const double maxRepeat = std::max(1.0, *std::max_element(repeats.begin(), repeats.end()));

    // 2. Chorusness = repeated AND loud. The best 8-bar window is the core of the main chorus.
    std::vector<double> chorusness(bars);
    for (int b = 0; b < bars; ++b) chorusness[b] = (repeats[b] / maxRepeat) * energy01[b];
    int coreStart = 0;
    double bestWindow = -1;
    for (int b = 0; b + kChorusBars <= bars; ++b) {
        double sum = 0;
        for (int k = 0; k < kChorusBars; ++k) sum += chorusness[b + k];
        if (sum > bestWindow + 1e-9) { bestWindow = sum; coreStart = b; }
    }
    const bool foundRepeats = bestWindow > 0.5;

    // 3. All occurrences of that core: places whose next 8 bars look like it.
    struct Occurrence { int start; double similarity; };
    std::vector<Occurrence> occurrences;
    int length = kChorusBars;
    if (foundRepeats) {
        std::vector<Occurrence> all;
        const double occurrenceThreshold = std::max(0.6, threshold - 0.05);
        for (int j = 0; j + kChorusBars <= bars; ++j) {
            const double sim = diagonalMean(s, coreStart, j, kChorusBars);
            if (sim >= occurrenceThreshold) all.push_back({j, sim});
        }
        std::sort(all.begin(), all.end(), [](const Occurrence& a, const Occurrence& b) { return a.similarity > b.similarity; });
        for (const auto& candidate : all) {
            bool free = true;
            for (const auto& taken : occurrences) {
                if (std::abs(taken.start - candidate.start) < kChorusBars) { free = false; break; }
            }
            if (free) occurrences.push_back(candidate);
        }
        std::sort(occurrences.begin(), occurrences.end(), [](const Occurrence& a, const Occurrence& b) { return a.start < b.start; });

        // Grow the chorus forward while the following bars keep matching in most occurrences.
        while (length < kMaxChorusBars) {
            if (coreStart + length < bars && isBoundary[coreStart + length]) break;  // the section ends here
            int agree = 0;
            for (const auto& o : occurrences) {
                if (o.start + length >= bars || coreStart + length >= bars) continue;
                if (s[coreStart + length][o.start + length] >= threshold - 0.1) ++agree;
            }
            if (agree * 10 >= static_cast<int>(occurrences.size()) * 7 && !occurrences.empty()) ++length; else break;
        }
        for (auto& o : occurrences) o.start = std::min(o.start, bars - length);

        // Now that the real length is known, keep the best occurrences that do not overlap each
        // other (a loop-based track matches everywhere), at most six.
        std::sort(occurrences.begin(), occurrences.end(), [](const Occurrence& a, const Occurrence& b) { return a.similarity > b.similarity; });
        std::vector<Occurrence> kept;
        for (const auto& o : occurrences) {
            bool overlaps = false;
            for (const auto& k : kept) if (std::abs(k.start - o.start) < length) { overlaps = true; break; }
            if (!overlaps && kept.size() < 6) kept.push_back(o);
        }
        occurrences = kept;
        std::sort(occurrences.begin(), occurrences.end(), [](const Occurrence& a, const Occurrence& b) { return a.start < b.start; });
    }

    // 4. Chorus candidates with scores; the loudest occurrence (latest on ties) is the main one.
    if (foundRepeats && occurrences.size() >= 2) {
        std::vector<double> meanEnergy;
        double maxEnergy = 1e-9;
        for (const auto& o : occurrences) {
            double sum = 0;
            for (int k = 0; k < length; ++k) sum += energy01[o.start + k];
            meanEnergy.push_back(sum / length);
            maxEnergy = std::max(maxEnergy, meanEnergy.back());
        }
        size_t main = 0;
        for (size_t i = 0; i < occurrences.size(); ++i) if (meanEnergy[i] >= meanEnergy[main] - 1e-9) main = i;
        for (size_t i = 0; i < occurrences.size(); ++i) {
            ChorusCandidate c;
            c.startBar = occurrences[i].start;
            c.endBar = occurrences[i].start + length;
            c.startMs = barStart(c.startBar);
            c.endMs = barStart(c.endBar);
            c.score = std::clamp(0.6 * occurrences[i].similarity + 0.4 * (meanEnergy[i] / maxEnergy), 0.0, 1.0);
            c.signals = {"REPETITION", "ENERGY"};
            c.isMain = i == main;
            result.chorusCandidates.push_back(c);
        }
    } else {
        // No repeat found: offer the loudest window as a weak, energy-only guess.
        int loudest = 0;
        double best = -1;
        for (int b = 0; b + kChorusBars <= bars; ++b) {
            double sum = 0;
            for (int k = 0; k < kChorusBars; ++k) sum += energy01[b + k];
            if (sum > best) { best = sum; loudest = b; }
        }
        ChorusCandidate c;
        c.startBar = loudest;
        c.endBar = loudest + kChorusBars;
        c.startMs = barStart(c.startBar);
        c.endMs = barStart(c.endBar);
        c.score = 0.25;
        c.signals = {"ENERGY"};
        c.isMain = true;
        result.chorusCandidates.push_back(c);
    }

    // 5. Sections: chorus where chorus candidates are, labels for the gaps between them.
    std::vector<int> isChorus(bars, 0);
    const bool strong = result.chorusCandidates.size() >= 2;
    for (const auto& c : result.chorusCandidates) for (int b = c.startBar; b < c.endBar && b < bars; ++b) isChorus[b] = 1;
    int firstChorus = bars;
    for (int b = 0; b < bars; ++b) if (isChorus[b]) { firstChorus = b; break; }
    int chorusBars = 0;
    for (int flag : isChorus) chorusBars += flag;
    const bool coversMostOfSong = chorusBars * 10 > bars * 6;  // over 60%: likely a loop-based track
    const double conf = strong && !coversMostOfSong ? 0.7 : 0.35;

    int b = 0;
    int chorusSeen = 0;
    while (b < bars) {
        int e = b;
        while (e < bars && isChorus[e] == isChorus[b]) ++e;
        if (isChorus[b]) {
            ++chorusSeen;
            section(SectionType::Chorus, b, e, conf);
        } else {
            const int gap = e - b;
            if (b == 0) {
                // Before the first chorus: the intro ends at the first clear change in the sound
                // (a novelty peak within the first 8 bars); the bars before the chorus build up to it.
                int introEnd = 0;
                for (int k = 2; k <= std::min(8, gap - 1); ++k) {
                    const bool peak = novelty[k] >= novelty[k - 1] && novelty[k] >= novelty[k + 1 < bars ? k + 1 : k];
                    if (peak && novelty[k] >= 0.4 * maxNovelty) { introEnd = k; break; }
                }
                if (introEnd >= 2) section(SectionType::Intro, 0, introEnd, conf);
                const int rest = gap - introEnd;
                if (rest > 0 && e == firstChorus && rest <= 4 && introEnd > 0) {
                    section(SectionType::PreChorus, introEnd, e, conf);
                } else if (rest > 0) {
                    const int pre = (e == firstChorus && rest > 8) ? 4 : 0;
                    section(SectionType::Verse, introEnd, e - pre, conf);
                    if (pre > 0) section(SectionType::PreChorus, e - pre, e, conf);
                }
            } else if (e == bars) {
                section(gap >= 2 ? SectionType::Outro : SectionType::Verse, b, e, conf);
            } else if (chorusSeen >= 2 && gap <= 6) {
                section(SectionType::Bridge, b, e, conf);
            } else if (gap > 8 && chorusSeen == 1) {
                section(SectionType::Verse, b, e - 4, conf);
                section(SectionType::PreChorus, e - 4, e, conf);
            } else {
                section(SectionType::Verse, b, e, conf);
            }
        }
        b = e;
    }

    // 6. Phrases: 8-bar groups, placed to line up with as many section boundaries as possible.
    std::vector<int> boundaries;
    for (const auto& sec : result.sections) {
        const int bar = static_cast<int>(std::lower_bound(barStartsMs.begin(), barStartsMs.end(), sec.startMs - 1.0) - barStartsMs.begin());
        boundaries.push_back(bar);
    }
    int bestOffset = 0, bestHits = -1;
    for (int offset = 0; offset < 8; ++offset) {
        int hits = 0;
        for (int boundary : boundaries) if (((boundary - offset) % 8 + 8) % 8 == 0) ++hits;
        if (hits > bestHits) { bestHits = hits; bestOffset = offset; }
    }
    for (int bar = bestOffset; bar < bars; bar += 8) result.phraseStartBars.push_back(bar);
    return result;
}

}  // namespace abcd
