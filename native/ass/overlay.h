// One ASS/SSA subtitle track drawn by libass, without any JNI: the script (a file's whole text, or a stream's header
// and then its events as they arrive), the fonts it may use, and the track drawn at a moment for a frame size as one
// picture over it. jni/subtitles.cpp is the JVM's surface onto it.
#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct ass_library;
struct ass_renderer;
struct ass_track;

namespace mediadroid::subtitles {

// The drawn track: the box (x, y, width, height) in the frame that holds anything, and its pixels in that box as
// 0xAARRGGBB, not premultiplied, rows from the top. Empty (width 0) where nothing shows.
struct Overlay {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
    std::vector<uint32_t> pixels;
};

// A track and the library and renderer it is drawn with. Every call is safe from any thread: the events fed on the
// player's thread and the frames drawn on the screen's are kept apart by a lock.
class Track {
public:
    Track();
    ~Track();
    Track(const Track&) = delete;
    Track& operator=(const Track&) = delete;

    // Whether libass came up.
    bool ready() const { return track_ != nullptr; }

    // A font the script may use (a file's attachment), by its file [name].
    void addFont(const std::string& name, const uint8_t* data, size_t size);

    // The font that draws what no other font has: a file's [path] and its [family], either empty for none; where
    // [system] the system's own fonts are asked too (DirectWrite, Fontconfig), where this build has a way to.
    void setDefaultFont(const std::string& path, const std::string& family, bool system);

    // A stream's header (its codec private: [Script Info], styles, the events' format).
    void header(const uint8_t* data, size_t size);

    // Script text as a file holds it — the whole file at once, or any part in order.
    void script(const uint8_t* data, size_t size);

    // One event as Matroska carries it ("ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"),
    // shown from [startMs] for [durationMs]. An event already fed (its ReadOrder) is not added again.
    void event(const uint8_t* data, size_t size, int64_t startMs, int64_t durationMs);

    // Every event dropped, the header kept (a stream switched or started again).
    void flush();

    // The track at [timeMs] drawn for a frame [width] × [height], the video's own pixels [storageWidth] ×
    // [storageHeight] (for the shapes the script's resolution implies; 0 for the frame's). Returns whether the picture
    // differs from the last one drawn; [out] is filled either way.
    bool render(int64_t timeMs, int width, int height, int storageWidth, int storageHeight, Overlay& out);

private:
    void fontsIfChanged();

    std::mutex lock_;
    ass_library* library_ = nullptr;
    ass_renderer* renderer_ = nullptr;
    ass_track* track_ = nullptr;
    std::string fontPath_;
    std::string fontFamily_;
    bool systemFonts_ = false;
    bool fontsChanged_ = true;
    bool first_ = true;
};

}  // namespace mediadroid::subtitles
