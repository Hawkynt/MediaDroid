// What both programs of the shader check share: 8-bit RGB pictures (binary PPM), the test picture, files.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

namespace shadercheck {

struct Picture {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgb;  // rows top to bottom
};

[[noreturn]] inline void fail(const std::string& what) {
    std::fprintf(stderr, "shader_check: %s\n", what.c_str());
    std::exit(2);
}

inline Picture readPpm(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int maxValue = 0;
    Picture p;
    in >> magic >> p.width >> p.height >> maxValue;
    in.get();
    if (magic != "P6" || maxValue != 255) fail("not an 8-bit P6 picture: " + path);
    p.rgb.resize(size_t(p.width) * p.height * 3);
    in.read(reinterpret_cast<char*>(p.rgb.data()), std::streamsize(p.rgb.size()));
    if (!in) fail("short picture: " + path);
    return p;
}

inline void writePpm(const std::string& path, const Picture& p) {
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << p.width << " " << p.height << "\n255\n";
    out.write(reinterpret_cast<const char*>(p.rgb.data()), std::streamsize(p.rgb.size()));
}

// A 16-bit RGB picture (binary PPM with a maximum of 65535, big-endian samples): HDR video's coded values.
struct Picture16 {
    int width = 0;
    int height = 0;
    std::vector<uint16_t> rgb;  // rows top to bottom
};

inline Picture16 readPpm16(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int maxValue = 0;
    Picture16 p;
    in >> magic >> p.width >> p.height >> maxValue;
    in.get();
    if (magic != "P6" || maxValue != 65535) fail("not a 16-bit P6 picture: " + path);
    std::vector<uint8_t> bytes(size_t(p.width) * p.height * 6);
    in.read(reinterpret_cast<char*>(bytes.data()), std::streamsize(bytes.size()));
    if (!in) fail("short picture: " + path);
    p.rgb.resize(bytes.size() / 2);
    for (size_t i = 0; i < p.rgb.size(); i++) p.rgb[i] = uint16_t(bytes[2 * i] << 8 | bytes[2 * i + 1]);
    return p;
}

// The 16-bit picture as RGBA for a texture, rows as rgba() orders them.
inline std::vector<uint16_t> rgba16(const Picture16& p, bool topFirst) {
    std::vector<uint16_t> out(size_t(p.width) * p.height * 4);
    for (int y = 0; y < p.height; y++) {
        const int row = topFirst ? y : p.height - 1 - y;
        for (int x = 0; x < p.width; x++) {
            for (int c = 0; c < 3; c++) {
                out[(size_t(y) * p.width + x) * 4 + c] = p.rgb[(size_t(row) * p.width + x) * 3 + c];
            }
            out[(size_t(y) * p.width + x) * 4 + 3] = 65535;
        }
    }
    return out;
}

inline std::string readFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) fail("cannot read " + path);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

// The picture as RGBA bytes for a texture: rows top to bottom when [topFirst] (as Vulkan, Direct3D and the originals
// count them), else bottom to top (as the filters' stage holds its frames: the picture's top row where v is 1).
inline std::vector<uint8_t> rgba(const Picture& p, bool topFirst) {
    std::vector<uint8_t> out(size_t(p.width) * p.height * 4);
    for (int y = 0; y < p.height; y++) {
        const int row = topFirst ? y : p.height - 1 - y;
        for (int x = 0; x < p.width; x++) {
            for (int c = 0; c < 3; c++) {
                out[(size_t(y) * p.width + x) * 4 + c] = p.rgb[(size_t(row) * p.width + x) * 3 + c];
            }
            out[(size_t(y) * p.width + x) * 4 + 3] = 255;
        }
    }
    return out;
}

// RGBA bytes read back from a texture as a picture, rows as [rgba] stores them.
inline Picture fromRgba(const std::vector<uint8_t>& data, int w, int h, bool topFirst) {
    Picture p{w, h, std::vector<uint8_t>(size_t(w) * h * 3)};
    for (int y = 0; y < h; y++) {
        const int row = topFirst ? y : h - 1 - y;
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 3; c++) p.rgb[(size_t(row) * w + x) * 3 + c] = data[(size_t(y) * w + x) * 4 + c];
        }
    }
    return p;
}

}  // namespace shadercheck
