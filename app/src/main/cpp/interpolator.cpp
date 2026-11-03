// Neural frame interpolation on ncnn (Vulkan or the CPU), for NativeInterpolation.kt: a network that takes two
// frames and the in-between's time as a plane and gives the in-between (RIFE 4.x as rife-ncnn-vulkan runs it, MIT,
// see app/THIRD-PARTY-NOTICE.RIFE.txt). Frames come as RGBA bytes, the in-between goes back as RGB bytes.
#include <jni.h>

#include <android/asset_manager_jni.h>

#include <mutex>
#include <string>

#include <gpu.h>
#include <net.h>

#include "warp_layer.h"

namespace {

std::once_flag gpuOnce;

// The Vulkan instance, made once for the process; true where the device has a GPU ncnn can use.
bool gpuReady() {
    std::call_once(gpuOnce, [] { ncnn::create_gpu_instance(); });
    return ncnn::get_gpu_count() > 0;
}

struct Interpolator {
    ncnn::Net net;
    int padding = 1;
};

std::string text(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// [rgba] (w x h, rows as stored) as a three-plane picture in 0..1, the right and bottom filled with zeros up to the
// padded size, as the reference pads.
ncnn::Mat planes(const unsigned char* rgba, int w, int h, int paddedW, int paddedH) {
    ncnn::Mat rgb = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    rgb.substract_mean_normalize(nullptr, toUnit);
    ncnn::Mat padded;
    ncnn::copy_make_border(rgb, padded, 0, paddedH - h, 0, paddedW - w, ncnn::BORDER_CONSTANT, 0.f);
    return padded;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_hasGpu(JNIEnv*, jobject) {
    return gpuReady();
}

JNIEXPORT jlong JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_open(
    JNIEnv* env, jobject, jobject assets, jstring param, jstring model, jboolean gpu, jint padding, jstring warp) {
    mediadroid::warpShader = text(env, warp);
    auto* it = new Interpolator();
    it->padding = padding;
    ncnn::Option& opt = it->net.opt;
    const bool vulkan = gpu && gpuReady();
    opt.use_vulkan_compute = vulkan;
    // Half floats to store on the GPU, full floats to compute with: in half floats the sampling positions of a
    // large frame lose too much.
    opt.use_fp16_packed = vulkan;
    opt.use_fp16_storage = vulkan;
    opt.use_fp16_arithmetic = false;
    opt.use_bf16_storage = false;
    opt.num_threads = vulkan ? 1 : 4;
    if (vulkan) it->net.set_vulkan_device(ncnn::get_gpu_device());
    it->net.register_custom_layer("rife.Warp", mediadroid::createWarpLayer);
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (it->net.load_param(manager, text(env, param).c_str()) != 0 ||
        it->net.load_model(manager, text(env, model).c_str()) != 0) {
        delete it;
        return 0;
    }
    return reinterpret_cast<jlong>(it);
}

JNIEXPORT jint JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_between(
    JNIEnv* env, jobject, jlong handle, jobject earlier, jobject later, jint w, jint h, jfloat time, jobject out) {
    auto* it = reinterpret_cast<Interpolator*>(handle);
    const auto* first = static_cast<const unsigned char*>(env->GetDirectBufferAddress(earlier));
    const auto* second = static_cast<const unsigned char*>(env->GetDirectBufferAddress(later));
    auto* rgb = static_cast<unsigned char*>(env->GetDirectBufferAddress(out));
    if (it == nullptr || first == nullptr || second == nullptr || rgb == nullptr) return -1;
    const int paddedW = (w + it->padding - 1) / it->padding * it->padding;
    const int paddedH = (h + it->padding - 1) / it->padding * it->padding;
    ncnn::Mat in0 = planes(first, w, h, paddedW, paddedH);
    ncnn::Mat in1 = planes(second, w, h, paddedW, paddedH);
    ncnn::Mat timestep(paddedW, paddedH, 1);
    timestep.fill(time);

    ncnn::Extractor ex = it->net.create_extractor();
    ex.input("in0", in0);
    ex.input("in1", in1);
    ex.input("in2", timestep);
    ncnn::Mat result;
    if (ex.extract("out0", result) != 0) return -2;

    ncnn::Mat picture;
    ncnn::copy_cut_border(result, picture, 0, paddedH - h, 0, paddedW - w);
    // Back to bytes, rounded: to_pixels cuts off, so half a step is added first, as the reference does.
    const float half[3] = {-0.5f / 255.f, -0.5f / 255.f, -0.5f / 255.f};
    const float toBytes[3] = {255.f, 255.f, 255.f};
    picture.substract_mean_normalize(half, toBytes);
    picture.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_hawkynt_mediadroid_player_NativeInterpolation_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Interpolator*>(handle);
}

}  // extern "C"
