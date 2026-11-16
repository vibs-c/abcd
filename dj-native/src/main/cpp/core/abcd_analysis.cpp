#include "abcd_analysis.h"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <stdexcept>
#include <string>
#include <vector>

#include "analyzer.h"
#include "json_writer.h"

namespace {

char* duplicate(const std::string& text) {
    char* copy = static_cast<char*>(std::malloc(text.size() + 1));
    if (copy != nullptr) std::memcpy(copy, text.c_str(), text.size() + 1);
    return copy;
}

}  // namespace

extern "C" {

int abcd_analysis_version(void) { return ABCD_ANALYSIS_VERSION; }

char* abcd_analyze_json(const float* mono, size_t frames, int sample_rate, char** error) {
    if (error != nullptr) *error = nullptr;
    try {
        if (mono == nullptr || frames == 0) throw std::runtime_error("No audio");
        return duplicate(abcd::toJson(abcd::analyzeTrack(mono, frames, sample_rate)));
    } catch (const std::exception& e) {
        if (error != nullptr) *error = duplicate(e.what());
    } catch (...) {
        if (error != nullptr) *error = duplicate("Unknown analysis error");
    }
    return nullptr;
}

void abcd_free(void* pointer) { std::free(pointer); }

}  // extern "C"
