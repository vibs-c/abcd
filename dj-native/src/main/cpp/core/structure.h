// Song structure from per-bar features: repetition, chorus candidates, sections, phrases.
// Pure C++ (no Essentia), so it can be tested on its own and reused on any platform.
#ifndef ABCD_STRUCTURE_H
#define ABCD_STRUCTURE_H

#include <vector>

#include "analysis_result.h"

namespace abcd {

// One row per bar (a bar = 4 beats from the downbeat grid).
struct BarFeatures {
    std::vector<std::vector<float>> chroma;  // 12 values: which notes sound (harmony)
    std::vector<std::vector<float>> timbre;  // MFCCs: what the sound is like
    std::vector<float> rms;                  // loudness of the bar
    std::vector<float> midRatio;             // share of energy in 300-3400 Hz (vocal/lead range)
};

struct StructureResult {
    std::vector<Section> sections;
    std::vector<ChorusCandidate> chorusCandidates;
    std::vector<int> phraseStartBars;
    std::vector<double> energyCurve;  // per bar, 0..1
    int introMixableEndBar = 0;       // bars [0, n) are an easy-to-mix intro
    int outroMixableStartBar = 0;     // bars [n, end) are an easy-to-mix outro
};

// `barStartsMs` has one entry per bar; `durationMs` closes the last bar.
StructureResult analyzeStructure(const BarFeatures& features, const std::vector<double>& barStartsMs, double durationMs);

}  // namespace abcd

#endif  // ABCD_STRUCTURE_H
