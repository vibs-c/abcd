// The analysis output as plain C++ structs. Mirrors `AnalysisResult` in dj-core (Kotlin); the JSON
// writer in json_writer.cpp is the contract between the two.
#ifndef ABCD_ANALYSIS_RESULT_H
#define ABCD_ANALYSIS_RESULT_H

#include <string>
#include <vector>

namespace abcd {

enum class SectionType { Intro, Verse, PreChorus, Chorus, Bridge, Outro };

struct Section {
    SectionType type = SectionType::Verse;
    double startMs = 0;
    double endMs = 0;
    double confidence = 0;
};

// One candidate chorus occurrence. `signals` names the evidence ("REPETITION", "ENERGY").
struct ChorusCandidate {
    double startMs = 0;
    double endMs = 0;
    int startBar = 0;  // 0-based bar index, inclusive
    int endBar = 0;    // exclusive
    double score = 0;  // 0..1
    std::vector<std::string> signals;
    bool isMain = false;
};

struct KeyResult {
    std::string root;   // "A", "C#", ...
    std::string mode;   // "major" or "minor"
    std::string camelot;  // "8A"
    int pitchClass = 0;   // 0 = C
    double confidence = 0;  // 0..1
};

struct AnalysisResult {
    int analysisVersion = 1;
    double durationMs = 0;
    int analysisSampleRate = 44100;

    double bpm = 0;
    double bpmConfidence = 0;     // 0..1 (rubato and weak grids give low values)
    double bpmConfidenceRaw = 0;  // Essentia's own scale, 0..5.32
    std::vector<double> beatsMs;
    int beatsPerBar = 4;
    double downbeatConfidence = 0;
    std::vector<double> barStartsMs;  // downbeats
    std::vector<double> phraseStartsMs;

    KeyResult key;
    double lufs = 0;  // approximate integrated loudness of the mono downmix

    std::vector<double> energyCurve;  // per bar, 0..1
    std::vector<int> waveformPeaks;   // 0..255
    double introMixableEndMs = 0;
    double outroMixableStartMs = 0;

    std::vector<Section> sections;
    std::vector<ChorusCandidate> chorusCandidates;
};

}  // namespace abcd

#endif  // ABCD_ANALYSIS_RESULT_H
