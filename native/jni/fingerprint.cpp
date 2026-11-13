// The JNI side of NativeFingerprint (core/player): Chromaprint's raw fingerprint of PCM samples, as its fpcalc tool
// prints with -raw — the default algorithm (Chromaprint's ALGORITHM_DEFAULT, test2), its own resampling to 11025 Hz.
#include <jni.h>

#include <vector>

#include <chromaprint.h>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_hawkynt_mediadroyd_player_NativeFingerprint_fingerprint(
    JNIEnv* env, jobject, jshortArray samples, jint sampleRate, jint channels) {
    ChromaprintContext* context = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (context == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(samples);
    jshort* pcm = env->GetShortArrayElements(samples, nullptr);
    uint32_t* items = nullptr;
    int size = 0;
    const bool done = chromaprint_start(context, sampleRate, channels) &&
                      chromaprint_feed(context, pcm, count) &&
                      chromaprint_finish(context) &&
                      chromaprint_get_raw_fingerprint(context, &items, &size);
    env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
    jintArray result = nullptr;
    if (done) {
        result = env->NewIntArray(size);
        if (result != nullptr) env->SetIntArrayRegion(result, 0, size, reinterpret_cast<const jint*>(items));
    }
    if (items != nullptr) chromaprint_dealloc(items);
    chromaprint_free(context);
    return result;
}
