// abcd audio analysis: the portable C API.
//
// This is the only header a host platform needs. Android reaches it through jni/abcd_jni.cpp; iOS or
// desktop could call it directly (for example through Kotlin/Native cinterop). Nothing in core/ may
// include JNI or Android headers.
//
// The result is returned as JSON text ("wire format"), so the boundary is one function and one
// string, and the Kotlin side parses it into `AnalysisResult` (dj-core).
#ifndef ABCD_ANALYSIS_H
#define ABCD_ANALYSIS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// Bumped whenever the algorithms or the JSON layout change. Stored with every analysis so tracks can
// be re-analysed (brief §5.1).
#define ABCD_ANALYSIS_VERSION 1

int abcd_analysis_version(void);

// Analyses one track.
//   mono        mono PCM, float samples in [-1, 1], at `sample_rate` Hz (any rate; resampled inside)
//   frames      number of samples
//   error       optional; on failure receives a malloc'd message (free with abcd_free)
// Returns a malloc'd UTF-8 JSON string (free with abcd_free), or NULL on failure.
char* abcd_analyze_json(const float* mono, size_t frames, int sample_rate, char** error);

void abcd_free(void* pointer);

#ifdef __cplusplus
}
#endif

#endif  // ABCD_ANALYSIS_H
