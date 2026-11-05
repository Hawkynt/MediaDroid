// The Android side of platform.h: models read through the AssetManager where one is handed over.
#include "platform.h"

#include <android/asset_manager_jni.h>

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
