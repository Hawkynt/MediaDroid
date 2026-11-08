// The desktop side of platform.h: the models lie as files beside the app, so files are all there is.
#include "platform.h"

namespace mediadroyd::platform {

Loader loader(JNIEnv*, jobject, const std::string& base) {
    return fileLoader(base);
}

}  // namespace mediadroyd::platform
