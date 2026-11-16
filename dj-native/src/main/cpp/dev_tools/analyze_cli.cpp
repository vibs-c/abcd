// Development tool: analyse a file or run the synthetic-song self test, on any machine or on an
// Android device/emulator (push the executable with adb). Not shipped in the app.
//
//   abcd_analyze_cli --selftest          analyse a generated song with known answers and check them
//   abcd_analyze_cli --wav file.wav      analyse a 16-bit PCM or 32-bit float WAV file
//   abcd_analyze_cli --raw file.f32 RATE analyse raw mono 32-bit float samples at RATE Hz
//   abcd_analyze_cli --write-synth-wav out.wav   save the synthetic song as a 16-bit mono WAV
//   add --json to print the full JSON instead of the summary
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "abcd_analysis.h"
#include "analyzer.h"
#include "json_writer.h"
#include "synth_song.h"

namespace {

bool readFile(const char* path, std::vector<unsigned char>& bytes) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    bytes.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

uint32_t le32(const unsigned char* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
uint16_t le16(const unsigned char* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

// Minimal WAV reader: PCM16 or float32, any channel count (downmixed to mono).
bool readWav(const char* path, std::vector<float>& mono, int& rate) {
    std::vector<unsigned char> b;
    if (!readFile(path, b) || b.size() < 44 || std::memcmp(b.data(), "RIFF", 4) != 0) return false;
    int channels = 1, bits = 16, format = 1;
    size_t pos = 12, dataStart = 0, dataSize = 0;
    while (pos + 8 <= b.size()) {
        const uint32_t size = le32(&b[pos + 4]);
        if (std::memcmp(&b[pos], "fmt ", 4) == 0) {
            format = le16(&b[pos + 8]);
            channels = le16(&b[pos + 10]);
            rate = static_cast<int>(le32(&b[pos + 12]));
            bits = le16(&b[pos + 22]);
        } else if (std::memcmp(&b[pos], "data", 4) == 0) {
            dataStart = pos + 8;
            dataSize = std::min<size_t>(size, b.size() - dataStart);
            break;
        }
        pos += 8 + size + (size & 1);
    }
    if (dataStart == 0 || channels < 1) return false;
    const size_t bytesPer = bits / 8;
    const size_t frames = dataSize / (bytesPer * channels);
    mono.resize(frames);
    for (size_t i = 0; i < frames; ++i) {
        double sum = 0;
        for (int c = 0; c < channels; ++c) {
            const unsigned char* s = &b[dataStart + (i * channels + c) * bytesPer];
            if (format == 3 && bits == 32) {
                float f;
                std::memcpy(&f, s, 4);
                sum += f;
            } else if (bits == 16) {
                sum += static_cast<int16_t>(le16(s)) / 32768.0;
            } else {
                return false;
            }
        }
        mono[i] = static_cast<float>(sum / channels);
    }
    return true;
}

bool writeWav16(const char* path, const std::vector<float>& mono, int rate) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    const uint32_t dataBytes = static_cast<uint32_t>(mono.size() * 2);
    auto w32 = [&](uint32_t v) { out.write(reinterpret_cast<const char*>(&v), 4); };
    auto w16 = [&](uint16_t v) { out.write(reinterpret_cast<const char*>(&v), 2); };
    out.write("RIFF", 4); w32(36 + dataBytes); out.write("WAVEfmt ", 8);
    w32(16); w16(1); w16(1); w32(static_cast<uint32_t>(rate)); w32(static_cast<uint32_t>(rate) * 2); w16(2); w16(16);
    out.write("data", 4); w32(dataBytes);
    for (float f : mono) {
        const int16_t s = static_cast<int16_t>(std::lround(std::max(-1.0f, std::min(1.0f, f)) * 32767.0f));
        out.write(reinterpret_cast<const char*>(&s), 2);
    }
    return static_cast<bool>(out);
}

void printSummary(const abcd::AnalysisResult& r) {
    std::printf("duration       %.1f s\n", r.durationMs / 1000.0);
    std::printf("bpm            %.2f  (confidence %.2f, raw %.2f)\n", r.bpm, r.bpmConfidence, r.bpmConfidenceRaw);
    std::printf("beats / bars   %zu / %zu  (downbeat confidence %.2f)\n", r.beatsMs.size(), r.barStartsMs.size(), r.downbeatConfidence);
    std::printf("key            %s %s = Camelot %s  (confidence %.2f)\n", r.key.root.c_str(), r.key.mode.c_str(), r.key.camelot.c_str(),
                r.key.confidence);
    std::printf("loudness       %.1f LUFS (mono downmix)\n", r.lufs);
    std::printf("mixable intro  until %.1f s   outro from %.1f s\n", r.introMixableEndMs / 1000.0, r.outroMixableStartMs / 1000.0);
    static const char* names[] = {"INTRO", "VERSE", "PRE_CHORUS", "CHORUS", "BRIDGE", "OUTRO"};
    for (const auto& s : r.sections) {
        std::printf("section        %-10s %6.1f - %6.1f s  (%.2f)\n", names[static_cast<int>(s.type)], s.startMs / 1000.0, s.endMs / 1000.0,
                    s.confidence);
    }
    for (const auto& c : r.chorusCandidates) {
        std::printf("chorus         %6.1f - %6.1f s  bars %d-%d  score %.2f%s\n", c.startMs / 1000.0, c.endMs / 1000.0, c.startBar, c.endBar,
                    c.score, c.isMain ? "  <- main" : "");
    }
}

int check(bool ok, const char* what) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    return ok ? 0 : 1;
}

int selfTest(bool printJson) {
    const abcd_test::SynthSong song = abcd_test::makeSynthSong();
    std::printf("synthetic song: %.0f BPM, A minor, %d bars, choruses at bars 12-19, 28-35, 40-47\n", song.bpm, song.bars);
    const auto start = std::chrono::steady_clock::now();
    abcd::AnalysisResult r;
    try {
        r = abcd::analyzeTrack(song.pcm, song.sampleRate);
    } catch (const std::exception& e) {
        std::printf("analysis failed: %s\n", e.what());
        return 2;
    }
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::printf("analysis took %.1f s\n\n", seconds);
    if (printJson) std::printf("%s\n", abcd::toJson(r).c_str());
    printSummary(r);
    std::printf("\nchecks:\n");

    int failures = 0;
    failures += check(std::fabs(r.bpm - song.bpm) < 1.5, "BPM within 1.5 of 126");
    failures += check(r.key.pitchClass == song.expectedPitchClass || r.key.camelot == "8B", "key is A minor (or relative C major)");

    // The first downbeat must sit on a true bar line (bar length 1.905 s) within 80 ms.
    const double barMs = 4 * 60000.0 / song.bpm;
    bool aligned = !r.barStartsMs.empty();
    if (aligned) {
        const double phase = std::fmod(r.barStartsMs[0], barMs);
        aligned = std::min(phase, barMs - phase) < 80.0;
    }
    failures += check(aligned, "first downbeat on a true bar line");

    failures += check(r.chorusCandidates.size() >= 2, "at least two chorus occurrences found");
    // Compare in milliseconds against the true chorus times (our bar numbering starts at the first
    // detected downbeat, so bar indices are not comparable with the generator's).
    bool mainOk = false;
    int matching = 0;
    for (const auto& c : r.chorusCandidates) {
        bool matches = false;
        for (const auto& truth : song.chorusBars) {
            const double truthStart = truth.first * barMs, truthEnd = truth.second * barMs;
            const double overlap = std::min(c.endMs, truthEnd) - std::max(c.startMs, truthStart);
            const bool startsClose = std::fabs(c.startMs - truthStart) < 0.5 * barMs;
            if (overlap >= 0.75 * (c.endMs - c.startMs) && startsClose) matches = true;
        }
        if (matches) ++matching;
        if (c.isMain && matches) mainOk = true;
    }
    failures += check(mainOk, "main chorus starts within half a bar of a true chorus and stays inside it");
    failures += check(matching == 3, "all three true choruses found");
    failures += check(!r.sections.empty() && !r.waveformPeaks.empty() && !r.energyCurve.empty(), "sections, waveform and energy present");
    std::printf("\n%s\n", failures == 0 ? "SELF TEST PASSED" : "SELF TEST FAILED");
    return failures == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    bool printJson = false;
    for (int i = 1; i < argc; ++i) if (std::strcmp(argv[i], "--json") == 0) printJson = true;
    if (argc >= 2 && std::strcmp(argv[1], "--selftest") == 0) return selfTest(printJson);
    if (argc >= 3 && std::strcmp(argv[1], "--write-synth-wav") == 0) {
        const abcd_test::SynthSong song = abcd_test::makeSynthSong();
        if (!writeWav16(argv[2], song.pcm, song.sampleRate)) { std::printf("cannot write %s\n", argv[2]); return 2; }
        std::printf("wrote %s (%.0f s)\n", argv[2], song.pcm.size() / static_cast<double>(song.sampleRate));
        return 0;
    }

    std::vector<float> mono;
    int rate = 0;
    if (argc >= 3 && std::strcmp(argv[1], "--wav") == 0) {
        if (!readWav(argv[2], mono, rate)) { std::printf("cannot read WAV %s\n", argv[2]); return 2; }
    } else if (argc >= 4 && std::strcmp(argv[1], "--raw") == 0) {
        std::vector<unsigned char> bytes;
        if (!readFile(argv[2], bytes)) { std::printf("cannot read %s\n", argv[2]); return 2; }
        mono.resize(bytes.size() / 4);
        std::memcpy(mono.data(), bytes.data(), mono.size() * 4);
        rate = std::atoi(argv[3]);
    } else {
        std::printf("usage: abcd_analyze_cli --selftest | --wav file.wav | --raw file.f32 RATE   [--json]\n");
        return 2;
    }
    try {
        const abcd::AnalysisResult r = abcd::analyzeTrack(mono, rate);
        if (printJson) std::printf("%s\n", abcd::toJson(r).c_str()); else printSummary(r);
    } catch (const std::exception& e) {
        std::printf("analysis failed: %s\n", e.what());
        return 2;
    }
    return 0;
}
