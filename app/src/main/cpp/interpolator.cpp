// The JNI side of NativeInterpolation (NeuralInterpolator.kt): the networks of networks.h for the app, RIFE from
// its assets, DAIN and XVFI from their downloaded files.
#include <jni.h>

#include <android/asset_manager_jni.h>

#include <string>

#include "networks.h"
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
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    const std::string base = text(env, dir);
    return handle(mediadroid::openRife(
        [&](ncnn::Net& net, const char* name) {
            const std::string path = base + "/" + name;
            return net.load_param(manager, (path + ".param").c_str()) != 0 ||
                   net.load_model(manager, (path + ".bin").c_str()) != 0;
        },
        gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openDain(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    const std::string base = text(env, dir);
    return handle(mediadroid::openDain(
        [&](ncnn::Net& net, const char* name) {
            const std::string path = base + "/" + name;
            return net.load_param((path + ".param").c_str()) != 0 || net.load_model((path + ".bin").c_str()) != 0;
        },
        gpu));
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openXvfi(JNIEnv* env, jobject, jstring dir, jboolean gpu) {
    const std::string base = text(env, dir);
    return handle(mediadroid::openXvfi(
        [&](ncnn::Net& net, const char* name) {
            const std::string path = base + "/" + name;
            return net.load_param((path + ".param").c_str()) != 0 || net.load_model((path + ".bin").c_str()) != 0;
        },
        gpu));
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_between(
    JNIEnv* env, jobject, jlong handle, jobject earlier, jobject later, jint w, jint h, jfloat time, jobject out,
    jlong earlierId, jlong laterId) {
    auto* it = reinterpret_cast<mediadroid::Interpolator*>(handle);
    const auto* first = static_cast<const unsigned char*>(env->GetDirectBufferAddress(earlier));
    const auto* second = static_cast<const unsigned char*>(env->GetDirectBufferAddress(later));
    auto* rgb = static_cast<unsigned char*>(env->GetDirectBufferAddress(out));
    if (it == nullptr || first == nullptr || second == nullptr || rgb == nullptr) return -1;
    it->earlierId = earlierId;
    it->laterId = laterId;
    return it->between(first, second, w, h, time, rgb);
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<mediadroid::Interpolator*>(handle);
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_openEnlarger(
    JNIEnv* env, jobject, jobject assets, jstring dir, jboolean realCugan, jboolean gpu) {
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    const std::string base = text(env, dir);
    auto load = [&](ncnn::Net& net, const char* name) {
        const std::string path = base + "/" + name;
        return net.load_param(manager, (path + ".param").c_str()) != 0 ||
               net.load_model(manager, (path + ".bin").c_str()) != 0;
    };
    auto it = realCugan ? mediadroid::openRealCugan(load, gpu) : mediadroid::openWaifu2x(load, gpu);
    return reinterpret_cast<jlong>(it.release());
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_enlarge(
    JNIEnv* env, jobject, jlong handle, jobject picture, jint w, jint h, jobject out) {
    auto* it = reinterpret_cast<mediadroid::Enlarger*>(handle);
    const auto* rgba = static_cast<const unsigned char*>(env->GetDirectBufferAddress(picture));
    auto* rgb = static_cast<unsigned char*>(env->GetDirectBufferAddress(out));
    if (it == nullptr || rgba == nullptr || rgb == nullptr) return -1;
    return it->enlarge(rgba, w, h, rgb);
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_closeEnlarger(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<mediadroid::Enlarger*>(handle);
}

}  // extern "C"
