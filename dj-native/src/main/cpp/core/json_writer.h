// Serialises an AnalysisResult to the JSON "wire format" that dj-core's AnalysisResult.fromWireJson reads.
#ifndef ABCD_JSON_WRITER_H
#define ABCD_JSON_WRITER_H

#include <string>

#include "analysis_result.h"

namespace abcd {

std::string toJson(const AnalysisResult& result);

}  // namespace abcd

#endif  // ABCD_JSON_WRITER_H
