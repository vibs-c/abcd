#include "json_writer.h"

#include <iomanip>
#include <locale>
#include <sstream>

namespace abcd {

namespace {

const char* typeName(SectionType type) {
    switch (type) {
        case SectionType::Intro: return "INTRO";
        case SectionType::Verse: return "VERSE";
        case SectionType::PreChorus: return "PRE_CHORUS";
        case SectionType::Chorus: return "CHORUS";
        case SectionType::Bridge: return "BRIDGE";
        case SectionType::Outro: return "OUTRO";
    }
    return "VERSE";
}

// Writes numbers with a dot decimal separator whatever the process locale is.
class Json {
public:
    Json() {
        out_.imbue(std::locale::classic());
        out_ << std::fixed << std::setprecision(3);
    }
    std::string str() const { return out_.str(); }

    void raw(const char* text) { out_ << text; }
    void key(const char* name) { comma(); out_ << '"' << name << "\":"; first_ = true; }
    void number(double v) { out_ << v; first_ = false; }
    void integer(long v) { out_ << v; first_ = false; }
    void text(const std::string& v) { out_ << '"' << v << '"'; first_ = false; }  // names are plain ASCII
    void boolean(bool v) { out_ << (v ? "true" : "false"); first_ = false; }
    void open(char c) { comma(); out_ << c; first_ = true; }
    void beginArray(const char* name) { key(name); out_ << '['; first_ = true; }
    void endArray() { out_ << ']'; first_ = false; }
    void stringElement(const std::string& v) { comma(); out_ << '"' << v << '"'; first_ = false; }
    void close(char c) { out_ << c; first_ = false; }
    void field(const char* name, double v) { key(name); number(v); }
    void field(const char* name, long v) { key(name); integer(v); }
    void field(const char* name, int v) { key(name); integer(v); }
    void field(const char* name, const std::string& v) { key(name); text(v); }
    void field(const char* name, bool v) { key(name); boolean(v); }

    template <typename T>
    void numberArray(const char* name, const std::vector<T>& values) {
        key(name);
        out_ << '[';
        for (size_t i = 0; i < values.size(); ++i) {
            if (i) out_ << ',';
            out_ << values[i];
        }
        out_ << ']';
        first_ = false;
    }

private:
    void comma() { if (!first_) out_ << ','; }
    std::ostringstream out_;
    bool first_ = true;
};

}  // namespace

std::string toJson(const AnalysisResult& r) {
    Json j;
    j.open('{');
    j.field("analysisVersion", r.analysisVersion);
    j.field("durationMs", r.durationMs);
    j.field("analysisSampleRate", r.analysisSampleRate);
    j.field("bpm", r.bpm);
    j.field("bpmConfidence", r.bpmConfidence);
    j.field("bpmConfidenceRaw", r.bpmConfidenceRaw);
    j.numberArray("beatsMs", r.beatsMs);
    j.field("beatsPerBar", r.beatsPerBar);
    j.field("downbeatConfidence", r.downbeatConfidence);
    j.numberArray("barStartsMs", r.barStartsMs);
    j.numberArray("phraseStartsMs", r.phraseStartsMs);

    j.key("key");
    j.open('{');
    j.field("root", r.key.root);
    j.field("mode", r.key.mode);
    j.field("camelot", r.key.camelot);
    j.field("pitchClass", r.key.pitchClass);
    j.field("confidence", r.key.confidence);
    j.close('}');

    j.field("lufs", r.lufs);
    j.numberArray("energyCurve", r.energyCurve);
    j.numberArray("waveformPeaks", r.waveformPeaks);
    j.field("introMixableEndMs", r.introMixableEndMs);
    j.field("outroMixableStartMs", r.outroMixableStartMs);

    j.beginArray("sections");
    for (size_t i = 0; i < r.sections.size(); ++i) {
        j.open('{');
        j.field("type", std::string(typeName(r.sections[i].type)));
        j.field("startMs", r.sections[i].startMs);
        j.field("endMs", r.sections[i].endMs);
        j.field("confidence", r.sections[i].confidence);
        j.close('}');
    }
    j.endArray();

    j.beginArray("chorusCandidates");
    for (size_t i = 0; i < r.chorusCandidates.size(); ++i) {
        const ChorusCandidate& c = r.chorusCandidates[i];
        j.open('{');
        j.field("startMs", c.startMs);
        j.field("endMs", c.endMs);
        j.field("startBar", c.startBar);
        j.field("endBar", c.endBar);
        j.field("score", c.score);
        j.beginArray("signals");
        for (const std::string& signal : c.signals) j.stringElement(signal);
        j.endArray();
        j.field("isMain", c.isMain);
        j.close('}');
    }
    j.endArray();
    j.close('}');
    return j.str();
}

}  // namespace abcd
