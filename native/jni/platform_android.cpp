// The Android side of platform.h: models read through the AssetManager where one is handed over.
#include "platform.h"

#include <android/asset_manager_jni.h>

#include <cstdlib>

namespace {

// OpenMP (ncnn's threads on the CPU) without its own CPU affinity, set as the library loads, before OpenMP reads its
// settings: LLVM's runtime asserts and aborts setting it up when the app's allowed CPUs change between its start and
// the first parallel run, as Android's foreground and top-app CPU sets do in the app's own process (a network on the
// stage's thread, NeuralWorker; the self-test's fresh process never met it). ncnn does not rely on that affinity.
__attribute__((constructor)) void noOpenMpAffinity() {
    setenv("KMP_AFFINITY", "disabled", 0);
}

}  // namespace

namespace mediadroid::platform {

Loader loader(JNIEnv* env, jobject assets, const std::string& base) {
    AAssetManager* manager = assets ? AAssetManager_fromJava(env, assets) : nullptr;
    if (!manager) return fileLoader(base);
    return [manager, base](ncnn::Net& net, const char* name) {
        const std::string path = base + "/" + name;
        return net.load_param(manager, (path + ".param").c_str()) != 0 ||
               net.load_model(manager, (path + ".bin").c_str()) != 0;
    };
}

}  // namespace mediadroid::platform
