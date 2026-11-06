#include "overlay.h"

#include <algorithm>
#include <fstream>
#include <iterator>

extern "C" {
#include <ass.h>
}

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H

namespace mediadroid::subtitles {

namespace {

// libass's messages are for its own tools; the player has nowhere to show them.
void quiet(int, const char*, va_list, void*) {}

// A name of the font's name table (UTF-16, big-endian) as UTF-8.
std::string utf8(const FT_Byte* text, FT_UInt length) {
    std::string made;
    for (FT_UInt i = 0; i + 1 < length; i += 2) {
        uint32_t code = static_cast<uint32_t>(text[i]) << 8 | text[i + 1];
        if (code >= 0xD800 && code < 0xDC00 && i + 3 < length) {
            const uint32_t low = static_cast<uint32_t>(text[i + 2]) << 8 | text[i + 3];
            if (low >= 0xDC00 && low < 0xE000) {
                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                i += 2;
            }
        }
        if (code < 0x80) {
            made += static_cast<char>(code);
        } else if (code < 0x800) {
            made += static_cast<char>(0xC0 | code >> 6);
            made += static_cast<char>(0x80 | (code & 0x3F));
        } else if (code < 0x10000) {
            made += static_cast<char>(0xE0 | code >> 12);
            made += static_cast<char>(0x80 | (code >> 6 & 0x3F));
            made += static_cast<char>(0x80 | (code & 0x3F));
        } else {
            made += static_cast<char>(0xF0 | code >> 18);
            made += static_cast<char>(0x80 | (code >> 12 & 0x3F));
            made += static_cast<char>(0x80 | (code >> 6 & 0x3F));
            made += static_cast<char>(0x80 | (code & 0x3F));
        }
    }
    return made;
}

// The family name libass knows the face [index] of the font file at [path] by: one of its Windows family names (the
// ones libass reads, ass_fontselect.c's get_font_info), US English where there is one, else FreeType's; empty where
// the file does not open.
std::string familyOf(const std::string& path, int index) {
    FT_Library freetype = nullptr;
    if (FT_Init_FreeType(&freetype)) return {};
    std::string family;
    FT_Face face = nullptr;
    if (!FT_New_Face(freetype, path.c_str(), index, &face)) {
        const FT_UInt names = FT_Get_Sfnt_Name_Count(face);
        for (FT_UInt i = 0; i < names; i++) {
            FT_SfntName name;
            if (FT_Get_Sfnt_Name(face, i, &name) || name.platform_id != TT_PLATFORM_MICROSOFT ||
                name.name_id != TT_NAME_ID_FONT_FAMILY) continue;
            if (family.empty() || name.language_id == TT_MS_LANGID_ENGLISH_UNITED_STATES) {
                family = utf8(name.string, name.string_len);
            }
            if (name.language_id == TT_MS_LANGID_ENGLISH_UNITED_STATES) break;
        }
        if (family.empty() && face->family_name) family = face->family_name;
        FT_Done_Face(face);
    }
    FT_Done_FreeType(freetype);
    return family;
}

}  // namespace

Track::Track() {
    library_ = ass_library_init();
    if (!library_) return;
    ass_set_message_cb(library_, quiet, nullptr);
    // Fonts given as attachments are what the script was made with: they are looked at first.
    ass_set_extract_fonts(library_, 1);
    renderer_ = ass_renderer_init(library_);
    if (!renderer_) return;
    track_ = ass_new_track(library_);
}

Track::~Track() {
    if (track_) ass_free_track(track_);
    if (renderer_) ass_renderer_done(renderer_);
    if (library_) ass_library_done(library_);
}

void Track::addFont(const std::string& name, const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!library_) return;
    ass_add_font(library_, name.c_str(), reinterpret_cast<const char*>(data), static_cast<int>(size));
    fontsChanged_ = true;
}

void Track::setDefaultFont(const std::string& path, const std::string& family, bool system) {
    std::lock_guard<std::mutex> guard(lock_);
    fontPath_ = path;
    fontFamily_ = family;
    systemFonts_ = system;
    fontsChanged_ = true;
}

std::string Track::fallbackFont(const std::string& path, int index) {
    const std::string family = familyOf(path, index);
    if (family.empty()) return {};
    // Read outside the lock: a system's CJK font is large, and frames go on being drawn meanwhile.
    std::vector<char> data;
    bool known;
    {
        std::lock_guard<std::mutex> guard(lock_);
        known = std::find(fallbackFiles_.begin(), fallbackFiles_.end(), path) != fallbackFiles_.end();
    }
    if (!known) {
        std::ifstream file(path, std::ios::binary);
        data.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
        if (data.empty()) return {};
    }
    std::lock_guard<std::mutex> guard(lock_);
    if (!library_) return {};
    if (!known && std::find(fallbackFiles_.begin(), fallbackFiles_.end(), path) == fallbackFiles_.end()) {
        const size_t slash = path.find_last_of("/\\");
        const std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
        ass_add_font(library_, name.c_str(), data.data(), static_cast<int>(data.size()));
        fallbackFiles_.push_back(path);
    }
    fontFamily_ = family;
    fontsChanged_ = true;
    return family;
}

void Track::header(const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!track_) return;
    ass_process_codec_private(track_, const_cast<char*>(reinterpret_cast<const char*>(data)), static_cast<int>(size));
}

void Track::script(const uint8_t* data, size_t size) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!track_) return;
    ass_process_data(track_, const_cast<char*>(reinterpret_cast<const char*>(data)), static_cast<int>(size));
}

void Track::event(const uint8_t* data, size_t size, int64_t startMs, int64_t durationMs) {
    std::lock_guard<std::mutex> guard(lock_);
    if (!track_) return;
    ass_process_chunk(track_, const_cast<char*>(reinterpret_cast<const char*>(data)), static_cast<int>(size),
        startMs, durationMs);
}

void Track::flush() {
    std::lock_guard<std::mutex> guard(lock_);
    if (track_) ass_flush_events(track_);
}

void Track::setLinePosition(double percent) {
    std::lock_guard<std::mutex> guard(lock_);
    linePosition_ = std::clamp(percent, 0.0, 100.0);
}

void Track::fontsIfChanged() {
    if (!fontsChanged_) return;
    fontsChanged_ = false;
    ass_set_fonts(renderer_, fontPath_.empty() ? nullptr : fontPath_.c_str(),
        fontFamily_.empty() ? "sans-serif" : fontFamily_.c_str(),
        systemFonts_ ? ASS_FONTPROVIDER_AUTODETECT : ASS_FONTPROVIDER_NONE, nullptr, 1);
}

bool Track::render(int64_t timeMs, int width, int height, int storageWidth, int storageHeight, Overlay& out,
    int marginTop, int marginBottom, int marginLeft, int marginRight) {
    std::lock_guard<std::mutex> guard(lock_);
    out = Overlay();
    if (!track_ || width <= 0 || height <= 0) return false;
    fontsIfChanged();
    ass_set_frame_size(renderer_, width, height);
    ass_set_margins(renderer_, marginTop, marginBottom, marginLeft, marginRight);
    ass_set_use_margins(renderer_, marginTop != 0 || marginBottom != 0 || marginLeft != 0 || marginRight != 0);
    ass_set_line_position(renderer_, linePosition_);
    ass_set_storage_size(renderer_, storageWidth > 0 ? storageWidth : width, storageHeight > 0 ? storageHeight : height);
    int change = 0;
    const ASS_Image* images = ass_render_frame(renderer_, track_, timeMs, &change);
    const bool changed = change != 0 || first_;
    first_ = false;

    // The box that holds every piece, within the frame.
    int left = width, top = height, right = 0, bottom = 0;
    for (const ASS_Image* image = images; image; image = image->next) {
        if (image->w <= 0 || image->h <= 0) continue;
        left = std::min(left, std::max(image->dst_x, 0));
        top = std::min(top, std::max(image->dst_y, 0));
        right = std::max(right, std::min(image->dst_x + image->w, width));
        bottom = std::max(bottom, std::min(image->dst_y + image->h, height));
    }
    if (right <= left || bottom <= top) return changed;
    const int w = right - left;
    const int h = bottom - top;

    // Each piece is one colour through its coverage, laid over those before it (premultiplied while adding up).
    std::vector<float> premultiplied(static_cast<size_t>(w) * h * 4, 0.0f);
    for (const ASS_Image* image = images; image; image = image->next) {
        const float r = static_cast<float>((image->color >> 24) & 0xFF) / 255.0f;
        const float g = static_cast<float>((image->color >> 16) & 0xFF) / 255.0f;
        const float b = static_cast<float>((image->color >> 8) & 0xFF) / 255.0f;
        // The colour's last byte is its transparency: 0 opaque.
        const float opacity = static_cast<float>(255 - (image->color & 0xFF)) / 255.0f;
        const int x0 = std::max(image->dst_x, left), x1 = std::min(image->dst_x + image->w, right);
        const int y0 = std::max(image->dst_y, top), y1 = std::min(image->dst_y + image->h, bottom);
        for (int y = y0; y < y1; y++) {
            const unsigned char* coverage = image->bitmap + static_cast<ptrdiff_t>(y - image->dst_y) * image->stride;
            float* row = premultiplied.data() + (static_cast<size_t>(y - top) * w) * 4;
            for (int x = x0; x < x1; x++) {
                const float a = opacity * static_cast<float>(coverage[x - image->dst_x]) / 255.0f;
                if (a <= 0.0f) continue;
                float* p = row + static_cast<size_t>(x - left) * 4;
                p[0] = a + p[0] * (1.0f - a);
                p[1] = r * a + p[1] * (1.0f - a);
                p[2] = g * a + p[2] * (1.0f - a);
                p[3] = b * a + p[3] * (1.0f - a);
            }
        }
    }

    out.x = left;
    out.y = top;
    out.width = w;
    out.height = h;
    out.pixels.resize(static_cast<size_t>(w) * h);
    auto byte = [](float v) { return static_cast<uint32_t>(std::clamp(v * 255.0f + 0.5f, 0.0f, 255.0f)); };
    for (size_t i = 0; i < out.pixels.size(); i++) {
        const float* p = premultiplied.data() + i * 4;
        if (p[0] <= 0.0f) {
            out.pixels[i] = 0;
            continue;
        }
        out.pixels[i] = byte(p[0]) << 24 | byte(p[1] / p[0]) << 16 | byte(p[2] / p[0]) << 8 | byte(p[3] / p[0]);
    }
    return changed;
}

}  // namespace mediadroid::subtitles
