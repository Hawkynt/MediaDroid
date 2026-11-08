// What the JNI surface (interpolator.cpp) needs from the system it runs on: on Android (platform_android.cpp) the
// models bundled as the app's assets, on the desktop (platform_desktop.cpp) files alone.
#pragma once

#include <jni.h>

#include <string>

#include "networks.h"

namespace mediadroyd::platform {

// A loader for the models under [base]: from the app's bundled assets where [assets] (an Android AssetManager) is
// given, else from the files there. The desktop has no assets; it reads files whatever [assets] is.
Loader loader(JNIEnv* env, jobject assets, const std::string& base);

}  // namespace mediadroyd::platform
