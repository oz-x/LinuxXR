// JNI bridge for offline dictation (whisper.cpp). Used by com.linuxxr.ui.Dictation.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <thread>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LinuxXR-Whisper", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL Java_com_linuxxr_ui_Dictation_nativeInit(JNIEnv* env, jclass, jstring model) {
    const char* path = env->GetStringUTFChars(model, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(model, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL Java_com_linuxxr_ui_Dictation_nativeFree(JNIEnv*, jclass, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context*>(ctx));
}

// pcm: 16 kHz mono float samples. language: ISO code ("es") or "auto".
// Returns UTF-8 bytes (NewStringUTF only accepts modified UTF-8).
JNIEXPORT jbyteArray JNICALL Java_com_linuxxr_ui_Dictation_nativeTranscribe(JNIEnv* env, jclass, jlong handle,
                                                                       jfloatArray pcm, jstring language) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return env->NewByteArray(0);
    jsize n = env->GetArrayLength(pcm);
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    const char* lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(2u, std::min(6u, std::thread::hardware_concurrency()));
    p.language = lang;
    p.translate = false;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.suppress_blank = true;
    // Whisper pads every input to 30 s (1500 encoder frames). Dictation utterances are a few
    // seconds, so shrink the encoder context to the clip (+ margin): several times faster.
    p.audio_ctx = std::min(1500, (int)(n / 16000.0 * 50) + 128);

    std::string text;
    if (whisper_full(ctx, p, samples, n) == 0) {
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    // Trim leading/trailing whitespace whisper adds.
    size_t a = text.find_first_not_of(" \n\t"), b = text.find_last_not_of(" \n\t");
    text = a == std::string::npos ? "" : text.substr(a, b - a + 1);
    jbyteArray out = env->NewByteArray((jsize)text.size());
    env->SetByteArrayRegion(out, 0, (jsize)text.size(), reinterpret_cast<const jbyte*>(text.data()));
    return out;
}

}  // extern "C"
