// The desktop's own JNI entries (NativeNetworks.kt in :engine:desktop): a network of one input and one output run on
// the CPU from its files — what shows the desktop library loads, finds ncnn and reads a model.
#include <jni.h>

#include <string>
#include <vector>

#include "networks.h"

extern "C" {

// The network [name] in [dir] (blobs "in0" and "out0") run over [input], [c] planes of [w] x [h] floats; its output
// the same way, plane after plane, or null where the network does not load or run.
JNIEXPORT jfloatArray JNICALL
Java_com_hawkynt_mediadroid_engine_desktop_NativeNetworks_run(
    JNIEnv* env, jobject, jstring dir, jstring name, jfloatArray input, jint w, jint h, jint c) {
    const char* chars = env->GetStringUTFChars(dir, nullptr);
    const std::string base(chars);
    env->ReleaseStringUTFChars(dir, chars);
    chars = env->GetStringUTFChars(name, nullptr);
    const std::string network(chars);
    env->ReleaseStringUTFChars(name, chars);
    if (env->GetArrayLength(input) != w * h * c) return nullptr;

    ncnn::Net net;
    mediadroid::configure(net, false);
    if (mediadroid::fileLoader(base)(net, network.c_str()) != 0) return nullptr;

    ncnn::Mat in(w, h, c);
    std::vector<float> values(static_cast<size_t>(w) * h * c);
    env->GetFloatArrayRegion(input, 0, static_cast<jsize>(values.size()), values.data());
    for (int q = 0; q < c; q++) {
        float* plane = in.channel(q);
        for (int i = 0; i < w * h; i++) plane[i] = values[static_cast<size_t>(q) * w * h + i];
    }
    ncnn::Extractor ex = net.create_extractor();
    ncnn::Mat out;
    if (ex.input("in0", in) != 0 || ex.extract("out0", out) != 0) return nullptr;

    const int size = out.w * out.h * out.d;
    std::vector<float> result(static_cast<size_t>(size) * out.c);
    for (int q = 0; q < out.c; q++) {
        const float* plane = out.channel(q);
        for (int i = 0; i < size; i++) result[static_cast<size_t>(q) * size + i] = plane[i];
    }
    jfloatArray made = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (made) env->SetFloatArrayRegion(made, 0, static_cast<jsize>(result.size()), result.data());
    return made;
}

}  // extern "C"
