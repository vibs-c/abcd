// JNI glue for Android: one function that forwards to the portable C API in core/.
// Kept deliberately thin; all logic lives in core/ so other platforms can reuse it.
#include <jni.h>

#include <algorithm>

#include "../core/abcd_analysis.h"
#include "../core/echo.h"

namespace {

void throwJava(JNIEnv* env, const char* className, const char* message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) env->ThrowNew(cls, message);
}

}  // namespace

extern "C" {

// Kotlin: object NativeAnalyzer { external fun analyzeJson(pcm: FloatArray, frames: Int, sampleRate: Int): String }
// Only the first `frames` samples are audio: the decoder passes its working buffer, which may have
// spare room at the end, so a whole song is never copied into a second array.
JNIEXPORT jstring JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeAnalyzer_analyzeJson(JNIEnv* env, jobject /*thiz*/, jfloatArray pcm,
                                                                                         jint frameCount, jint sampleRate) {
    if (pcm == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "pcm is null");
        return nullptr;
    }
    const jsize length = env->GetArrayLength(pcm);
    if (frameCount < 0 || frameCount > length) {
        throwJava(env, "java/lang/IllegalArgumentException", "frames outside the array");
        return nullptr;
    }
    const jsize frames = frameCount;
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

// ---- Echo-out DSP for the DJ decks (brief §9.2). Kotlin: object NativeEcho in dj-native. ----------------
extern "C" {

JNIEXPORT jlong JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeEcho_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate,
                                                                                    jint channels) {
    return reinterpret_cast<jlong>(new abcd::EchoLine(sampleRate, channels));
}

// Processes `frames` interleaved frames of a direct FloatBuffer in place.
JNIEXPORT void JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeEcho_nativeProcess(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                                                                   jobject buffer, jint frames, jint delayFrames,
                                                                                   jfloat send, jfloat feedback, jfloat dry,
                                                                                   jfloat wet) {
    auto* echo = reinterpret_cast<abcd::EchoLine*>(handle);
    if (echo == nullptr || buffer == nullptr || frames <= 0) return;
    auto* samples = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (samples == nullptr || capacity < static_cast<jlong>(frames) * echo->channels()) return;
    echo->process(samples, static_cast<size_t>(frames), static_cast<size_t>(std::max(1, static_cast<int>(delayFrames))), send,
                  feedback, dry, wet);
}

JNIEXPORT void JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeEcho_nativeClear(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto* echo = reinterpret_cast<abcd::EchoLine*>(handle)) echo->clear();
}

JNIEXPORT void JNICALL Java_com_aiqulabs_abcd_dj_nativelib_NativeEcho_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<abcd::EchoLine*>(handle);
}

}  // extern "C"

