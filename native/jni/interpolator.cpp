// The JNI side of NativeInterpolation (NeuralInterpolator.kt): the networks of networks.h for the app, RIFE from
// its assets, DAIN, XVFI and FLAVR from their downloaded files. Nothing here is Android's own: the assets are read
// through platform.h.
#include <jni.h>

#include <string>

#include "networks.h"
#include "platform.h"
#include "shaders.h"

namespace {

std::string text(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jlong handle(std::unique_ptr<mediadroid::Interpolator> it) {
    return reinterpret_cast<jlong>(it.release());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_hasGpu(JNIEnv*, jobject) {
    return mediadroid::gpuReady();
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_shaders(
    JNIEnv* env, jobject, jobjectArray names, jobjectArray sources) {
    const jsize count = env->GetArrayLength(names);
    for (jsize i = 0; i < count; i++) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto source = static_cast<jstring>(env->GetObjectArrayElement(sources, i));
        mediadroid::shaders[text(env, name)] = text(env, source);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(source);
    }
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openRife(
    JNIEnv* env, jobject, jobject assets, jstring dir, jboolean gpu) {
    return handle(mediadroid::openRife(mediadroid::platform::loader(env, assets, text(env, dir)), gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openDain(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    return handle(mediadroid::openDain(mediadroid::fileLoader(text(env, dir)), gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openXvfi(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    return handle(mediadroid::openXvfi(mediadroid::fileLoader(text(env, dir)), gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openFlavr(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    return handle(mediadroid::openFlavr(mediadroid::fileLoader(text(env, dir)), gpu));
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_between(
    JNIEnv* env, jobject, jlong handle, jobject earlier, jobject later, jint w, jint h, jfloat time, jobject out,
    jlong earlierId, jlong laterId, jobject before, jobject after) {
    auto* it = reinterpret_cast<mediadroid::Interpolator*>(handle);
    const auto* first = static_cast<const unsigned char*>(env->GetDirectBufferAddress(earlier));
    const auto* second = static_cast<const unsigned char*>(env->GetDirectBufferAddress(later));
    auto* rgb = static_cast<unsigned char*>(env->GetDirectBufferAddress(out));
    if (it == nullptr || first == nullptr || second == nullptr || rgb == nullptr) return -1;
    it->earlierId = earlierId;
    it->laterId = laterId;
    it->before = before ? static_cast<const unsigned char*>(env->GetDirectBufferAddress(before)) : nullptr;
    it->after = after ? static_cast<const unsigned char*>(env->GetDirectBufferAddress(after)) : nullptr;
    const int ran = it->between(first, second, w, h, time, rgb);
    it->before = it->after = nullptr;
    return ran;
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<mediadroid::Interpolator*>(handle);
}

// [kind]: 0 waifu2x, 1 Real-CUGAN, 2 RealSR, 3 Real-ESRGAN (the desktop's; Android runs it on TFLite); [model] the
// structure and weights' name in [dir], [scale] how much larger it makes a picture, [tta] whether it runs eight ways.
JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openEnlarger(
    JNIEnv* env, jobject, jobject assets, jstring dir, jint kind, jstring model, jint scale, jboolean tta,
    jboolean gpu) {
    auto load = mediadroid::platform::loader(env, assets, text(env, dir));
    const std::string name = text(env, model);
    auto it = kind == 3   ? mediadroid::openRealEsrgan(load, gpu, name.c_str(), tta)
              : kind == 2 ? mediadroid::openRealSr(load, gpu, tta)
              : kind == 1 ? mediadroid::openRealCugan(load, gpu, name.c_str(), scale, tta)
                          : mediadroid::openWaifu2x(load, gpu, name.c_str(), tta);
    return reinterpret_cast<jlong>(it.release());
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_gpuHeapBudget(JNIEnv*, jobject) {
    return mediadroid::gpuHeapBudget();
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openCain(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    return handle(mediadroid::openCain(mediadroid::fileLoader(text(env, dir)), gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openIfrnet(
    JNIEnv* env, jobject, jobject assets, jstring dir, jboolean gpu) {
    return handle(mediadroid::openIfrnet(mediadroid::platform::loader(env, assets, text(env, dir)), gpu));
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_enlarge(
    JNIEnv* env, jobject, jlong handle, jobject picture, jint w, jint h, jobject out, jint tile) {
    auto* it = reinterpret_cast<mediadroid::Enlarger*>(handle);
    const auto* rgba = static_cast<const unsigned char*>(env->GetDirectBufferAddress(picture));
    auto* rgb = static_cast<unsigned char*>(env->GetDirectBufferAddress(out));
    if (it == nullptr || rgba == nullptr || rgb == nullptr) return -1;
    return it->enlarge(rgba, w, h, rgb, tile);
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_closeEnlarger(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<mediadroid::Enlarger*>(handle);
}

}  // extern "C"
