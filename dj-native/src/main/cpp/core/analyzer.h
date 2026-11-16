// Full track analysis: tempo, beat grid, bars, key, loudness, energy, structure, chorus.
#ifndef ABCD_ANALYZER_H
#define ABCD_ANALYZER_H

#include <string>
#include <vector>

#include "analysis_result.h"

namespace abcd {

// Analyses mono PCM at `sampleRate`. Throws std::runtime_error for audio that cannot be analysed
// (too short, silent). Thread-safe, but runs one analysis at a time.
// Memory: the song is copied once (into a 44.1 kHz buffer); Essentia reads that buffer in place.
AnalysisResult analyzeTrack(const float* mono, size_t frames, int sampleRate);
inline AnalysisResult analyzeTrack(const std::vector<float>& mono, int sampleRate) {
    return analyzeTrack(mono.data(), mono.size(), sampleRate);
}

// Camelot wheel code for a key, for example (pitchClass 9, minor) = "8A". 0 = C.
std::string camelotCode(int pitchClass, bool minor);

}  // namespace abcd

#endif  // ABCD_ANALYZER_H
