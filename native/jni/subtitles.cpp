// The JNI entries of LibAss.kt (:core:player): an ASS/SSA track drawn by libass (ass/overlay.h), the same on Android and
// the desktop. A track is a handle (a pointer as a long) from open until close.
#include <jni.h>

#include <ass.h>

#include <string>
#include <vector>

#include "overlay.h"

using mediadroid::subtitles::Overlay;
using mediadroid::subtitles::Track;

namespace {

Track* track(jlong handle) { return reinterpret_cast<Track*>(handle); }

std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string made(chars);
    env->ReleaseStringUTFChars(value, chars);
    return made;
}

std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray value) {
    std::vector<uint8_t> made(static_cast<size_t>(env->GetArrayLength(value)));
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(made.size()), reinterpret_cast<jbyte*>(made.data()));
    return made;
}

}  // namespace

extern "C" {

// libass's version, as ass_library_version gives it (0x01705000 for 0.17.5).
JNIEXPORT jint JNICALL Java_com_hawkynt_mediadroid_player_LibAss_version(JNIEnv*, jobject) {
    return ass_library_version();
}

// A new, empty track; 0 where libass does not come up.
JNIEXPORT jlong JNICALL Java_com_hawkynt_mediadroid_player_LibAss_open(JNIEnv*, jobject) {
    auto* made = new Track();
    if (!made->ready()) {
        delete made;
        return 0;
    }
    return reinterpret_cast<jlong>(made);
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_close(JNIEnv*, jobject, jlong handle) {
    delete track(handle);
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_addFont(
    JNIEnv* env, jobject, jlong handle, jstring name, jbyteArray data) {
    const auto font = bytes(env, data);
    track(handle)->addFont(text(env, name), font.data(), font.size());
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_defaultFont(
    JNIEnv* env, jobject, jlong handle, jstring path, jstring family, jboolean system) {
    track(handle)->setDefaultFont(text(env, path), text(env, family), system == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_header(
    JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    const auto header = bytes(env, data);
    track(handle)->header(header.data(), header.size());
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_script(
    JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    const auto script = bytes(env, data);
    track(handle)->script(script.data(), script.size());
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_event(
    JNIEnv* env, jobject, jlong handle, jbyteArray data, jlong startMs, jlong durationMs) {
    const auto event = bytes(env, data);
    track(handle)->event(event.data(), event.size(), startMs, durationMs);
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_player_LibAss_flush(JNIEnv*, jobject, jlong handle) {
    track(handle)->flush();
}

// The track at [timeMs] for a frame [width] × [height] (the video's own pixels [storageWidth] × [storageHeight]): null
// where the picture is the one drawn last, else x, y, width and height of the box that holds it followed by its pixels
// as 0xAARRGGBB, rows from the top — width 0 where nothing shows.
JNIEXPORT jintArray JNICALL Java_com_hawkynt_mediadroid_player_LibAss_render(
    JNIEnv* env, jobject, jlong handle, jlong timeMs, jint width, jint height, jint storageWidth, jint storageHeight) {
    Overlay overlay;
    if (!track(handle)->render(timeMs, width, height, storageWidth, storageHeight, overlay)) return nullptr;
    const jsize size = static_cast<jsize>(4 + overlay.pixels.size());
    jintArray made = env->NewIntArray(size);
    if (!made) return nullptr;
    const jint box[4] = {overlay.x, overlay.y, overlay.width, overlay.height};
    env->SetIntArrayRegion(made, 0, 4, box);
    if (!overlay.pixels.empty()) {
        env->SetIntArrayRegion(made, 4, static_cast<jsize>(overlay.pixels.size()),
            reinterpret_cast<const jint*>(overlay.pixels.data()));
    }
    return made;
}

}  // extern "C"
