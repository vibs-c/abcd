// JNI glue for Android: one function that forwards to the portable C API in core/.
// Kept deliberately thin; all logic lives in core/ so other platforms can reuse it.
#include <jni.h>

#include "../core/abcd_analysis.h"

namespace {

void throwJava(JNIEnv* env, const char* className, const char* message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

}  // namespace

extern "C" {

// Kotlin: object NativeAnalyzer { external fun analyzeJson(pcm: FloatArray, sampleRate: Int): String }
JNIEXPORT jstring JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeAnalyzer_analyzeJson(JNIEnv* env, jobject /*thiz*/, jfloatArray pcm,
                                                                                         jint sampleRate) {
    if (pcm == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "pcm is null");
        return nullptr;
    }
    const jsize frames = env->GetArrayLength(pcm);
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        throwJava(env, "java/lang/OutOfMemoryError", "Could not read PCM");
        return nullptr;
    }
    char* error = nullptr;
    char* json = abcd_analyze_json(samples, static_cast<size_t>(frames), sampleRate, &error);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);  // read-only: discard without copying back

    if (json == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", error != nullptr ? error : "Analysis failed");
        abcd_free(error);
        return nullptr;
    }
    jstring result = env->NewStringUTF(json);
    abcd_free(json);
    return result;
}

JNIEXPORT jint JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeAnalyzer_analysisVersion(JNIEnv* /*env*/, jobject /*thiz*/) {
    return abcd_analysis_version();
}

}  // extern "C"
