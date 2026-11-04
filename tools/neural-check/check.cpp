// The desktop check of the app's neural frame interpolation (app/src/main/cpp/networks.cpp): the self-test's frames
// (as NeuralInterpolator.kt makes them) through RIFE, DAIN, XVFI and FLAVR on the CPU and, where there is one, the
// Vulkan GPU — the in-between against the frame truly halfway, the time it took, and the memory the process peaked
// at; and the upscalers on a drawing.
//
// neural_check <glsl/ncnn folder> <RIFE model folder> <DAIN model folder> <XVFI model folder> [size ...]
// FLAVR_DIR, WAIFU2X_DIR and REALCUGAN_DIR, where set, add those networks. NEURAL_CHECK_UNIT=CPU or GPU runs only
// that unit, NEURAL_CHECK_NETWORK=RIFE, DAIN, XVFI, FLAVR, waifu2x or Real-CUGAN only that network.
// NEURAL_CHECK_DUMP=<folder> writes the upscalers' pictures there.
#include <sys/resource.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

#include "networks.h"
#include "shaders.h"

namespace {

// The self-test's value noise: a level per 8-pixel cell corner, bilinear between (NeuralInterpolator.noise).
int level(int cx, int cy, int channel) {
    int v = cx * 73856093 ^ cy * 19349663 ^ channel * 83492791;
    v = static_cast<int>((v ^ static_cast<int>(static_cast<unsigned>(v) >> 13)) * 0x5bd1e995);
    return (v ^ static_cast<int>(static_cast<unsigned>(v) >> 15)) & 0xFF;
}

int noise(int x, int y, int channel) {
    const int cell = 8;
    const int cx = static_cast<int>(std::floor(static_cast<double>(x) / cell));
    const int cy = static_cast<int>(std::floor(static_cast<double>(y) / cell));
    const float fx = static_cast<float>(x - cx * cell) / cell;
    const float fy = static_cast<float>(y - cy * cell) / cell;
    const float top = level(cx, cy, channel) * (1 - fx) + level(cx + 1, cy, channel) * fx;
    const float bottom = level(cx, cy + 1, channel) * (1 - fx) + level(cx + 1, cy + 1, channel) * fx;
    return static_cast<int>(top * (1 - fy) + bottom * fy);
}

std::vector<unsigned char> frame(int w, int h, int shift) {
    std::vector<unsigned char> rgba(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            unsigned char* p = &rgba[(static_cast<size_t>(y) * w + x) * 4];
            for (int c = 0; c < 3; c++) p[c] = static_cast<unsigned char>(noise(x + shift * 5, y + shift * 3, c));
            p[3] = 255;
        }
    }
    return rgba;
}

long peakMb() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    return usage.ru_maxrss / 1024;
}

void check(const char* name, mediadroid::Interpolator& net, int w, int h) {
    const auto before = frame(w, h, -2);
    const auto first = frame(w, h, 0);
    const auto middle = frame(w, h, 1);
    const auto last = frame(w, h, 2);
    const auto after = frame(w, h, 4);
    std::vector<unsigned char> rgb(static_cast<size_t>(w) * h * 3);
    // The frames either side of the pair, for a network that looks at four (FLAVR).
    net.before = before.data();
    net.after = after.data();
    const auto start = std::chrono::steady_clock::now();
    const int ran = net.between(first.data(), last.data(), w, h, 0.5f, rgb.data());
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    net.before = net.after = nullptr;
    if (ran != 0) {
        std::printf("%s %dx%d: failed (%d)\n", name, w, h, ran);
        return;
    }
    const int edge = 24;
    double network = 0;
    double blend = 0;
    long count = 0;
    for (int y = edge; y < h - edge; y++) {
        for (int x = edge; x < w - edge; x++) {
            for (int c = 0; c < 3; c++) {
                const size_t i = (static_cast<size_t>(y) * w + x) * 4 + c;
                const int truth = middle[i];
                network += std::abs(rgb[(static_cast<size_t>(y) * w + x) * 3 + c] - truth);
                blend += std::abs((first[i] + last[i]) / 2 - truth);
                count++;
            }
        }
    }
    std::printf("%s %dx%d: in-between off by %.2f, a blend by %.2f; %.0f ms; peak %ld MB\n", name, w, h,
                network / count, blend / count, ms, peakMb());
}

// The next pair of a stream — its earlier frame the later one of the pair before — against the same pair worked out
// afresh: they must agree to the byte, and the time saved is printed.
void reuse(const char* name, mediadroid::Interpolator& net, int w, int h) {
    const auto f0 = frame(w, h, 0);
    const auto f2 = frame(w, h, 2);
    const auto f4 = frame(w, h, 4);
    std::vector<unsigned char> fresh(static_cast<size_t>(w) * h * 3);
    std::vector<unsigned char> kept(fresh.size());
    auto timed = [&](const std::vector<unsigned char>& a, const std::vector<unsigned char>& b, long long ia,
                     long long ib, std::vector<unsigned char>& out) {
        net.earlierId = ia;
        net.laterId = ib;
        const auto start = std::chrono::steady_clock::now();
        net.between(a.data(), b.data(), w, h, 0.5f, out.data());
        return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    };
    const double afresh = timed(f2, f4, 0, 0, fresh);
    timed(f0, f2, 1, 2, kept);
    const double again = timed(f2, f4, 2, 3, kept);
    net.earlierId = net.laterId = 0;
    std::printf("%s %dx%d: the next pair %s with the earlier frame's work kept; %.0f ms afresh, %.0f ms kept\n",
                name, w, h, fresh == kept ? "agrees" : "DIFFERS", afresh, again);
}

// The upscalers' test picture (NeuralEnlarger.drawing): flat colours with round edges, as drawings are — discs of
// 7 to 20 pixels' radius on a 24-pixel grid, each its own colour, on a light ground.
std::vector<unsigned char> drawing(int w, int h) {
    std::vector<unsigned char> rgba(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const int cx = x / 24;
            const int cy = y / 24;
            const int dx = x - (cx * 24 + 12);
            const int dy = y - (cy * 24 + 12);
            const int radius = 7 + level(cx, cy, 3) % 14;
            unsigned char* p = &rgba[(static_cast<size_t>(y) * w + x) * 4];
            const bool inside = dx * dx + dy * dy <= radius * radius;
            for (int c = 0; c < 3; c++) p[c] = static_cast<unsigned char>(inside ? level(cx, cy, c) : 230);
            p[3] = 255;
        }
    }
    return rgba;
}

// An upscaler on a drawing: the picture box-filtered down to half and enlarged again, against the original — and,
// for scale, the half picture's pixels just doubled.
void enlarge(const char* name, mediadroid::Enlarger& net, int w, int h) {
    const auto full = drawing(w * 2, h * 2);
    std::vector<unsigned char> half(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 4; c++) {
                int sum = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        sum += full[((static_cast<size_t>(y) * 2 + dy) * w * 2 + x * 2 + dx) * 4 + c];
                    }
                }
                half[(static_cast<size_t>(y) * w + x) * 4 + c] = static_cast<unsigned char>((sum + 2) / 4);
            }
        }
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(w) * h * 4 * 3);
    const auto start = std::chrono::steady_clock::now();
    const int ran = net.enlarge(half.data(), w, h, rgb.data());
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    if (ran != 0) {
        std::printf("%s %dx%d: failed (%d)\n", name, w, h, ran);
        return;
    }
    if (const char* dump = std::getenv("NEURAL_CHECK_DUMP")) {
        auto ppm = [&](const std::string& file, const unsigned char* data, int pw, int ph, int stride) {
            std::FILE* f = std::fopen((std::string(dump) + "/" + file).c_str(), "wb");
            std::fprintf(f, "P6\n%d %d\n255\n", pw, ph);
            for (int i = 0; i < pw * ph; i++) std::fwrite(data + static_cast<size_t>(i) * stride, 1, 3, f);
            std::fclose(f);
        };
        const std::string base = std::string(name).substr(0, std::string(name).find(' '));
        ppm(base + "-full.ppm", full.data(), w * 2, h * 2, 4);
        ppm(base + "-half.ppm", half.data(), w, h, 4);
        ppm(base + "-out.ppm", rgb.data(), w * 2, h * 2, 3);
    }
    double network = 0;
    double doubled = 0;
    long count = 0;
    for (int y = 8; y < h * 2 - 8; y++) {
        for (int x = 8; x < w * 2 - 8; x++) {
            for (int c = 0; c < 3; c++) {
                const int truth = full[(static_cast<size_t>(y) * w * 2 + x) * 4 + c];
                network += std::abs(rgb[(static_cast<size_t>(y) * w * 2 + x) * 3 + c] - truth);
                doubled += std::abs(half[(static_cast<size_t>(y / 2) * w + x / 2) * 4 + c] - truth);
                count++;
            }
        }
    }
    std::printf("%s %dx%d -> %dx%d: off by %.2f, pixels doubled by %.2f; %.0f ms; peak %ld MB\n", name, w, h, w * 2,
                h * 2, network / count, doubled / count, ms, peakMb());
}

std::string read(const std::filesystem::path& path) {
    std::ifstream in(path);
    std::stringstream text;
    text << in.rdbuf();
    return text.str();
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "neural_check <glsl/ncnn folder> <RIFE model folder> <DAIN model folder> "
                             "<XVFI model folder> [size ...]\n");
        return 2;
    }
    for (const auto& entry : std::filesystem::directory_iterator(argv[1])) {
        if (entry.path().extension() == ".comp") mediadroid::shaders[entry.path().stem().string()] = read(entry.path());
    }
    std::vector<int> sizes;
    for (int i = 5; i < argc; i++) sizes.push_back(std::atoi(argv[i]));
    if (sizes.empty()) sizes = {256};
    auto files = [](const std::string& dir) {
        return [dir](ncnn::Net& net, const char* name) {
            const std::string path = dir + "/" + name;
            return net.load_param((path + ".param").c_str()) != 0 || net.load_model((path + ".bin").c_str()) != 0;
        };
    };
    const bool gpu = mediadroid::gpuReady();
    std::printf("Vulkan GPU: %s\n", gpu ? "yes" : "no");
    for (bool onGpu : {false, true}) {
        if (onGpu && !gpu) continue;
        const char* unit = onGpu ? "GPU" : "CPU";
        const char* only = std::getenv("NEURAL_CHECK_UNIT");
        if (only != nullptr && std::string(only) != unit) continue;
        for (const auto& [network, open] : {
                 std::make_pair(std::string("RIFE"), &mediadroid::openRife),
                 std::make_pair(std::string("DAIN"), &mediadroid::openDain),
                 std::make_pair(std::string("XVFI"), &mediadroid::openXvfi),
                 std::make_pair(std::string("FLAVR"), &mediadroid::openFlavr)}) {
            const char* only = std::getenv("NEURAL_CHECK_NETWORK");
            if (only != nullptr && network != only) continue;
            const char* flavrDir = std::getenv("FLAVR_DIR");
            if (network == "FLAVR" && flavrDir == nullptr) continue;
            const std::string dir = network == "RIFE" ? argv[2]
                : network == "DAIN"                     ? argv[3]
                : network == "XVFI"                     ? argv[4]
                                                        : flavrDir;
            auto net = (*open)(files(dir), onGpu);
            if (!net) {
                std::printf("%s on the %s: does not load\n", network.c_str(), unit);
                continue;
            }
            for (int side : sizes) {
                check((network + " " + unit).c_str(), *net, side, side);
                reuse((network + " " + unit).c_str(), *net, side, side);
            }
        }
    }
    for (bool onGpu : {false, true}) {
        if (onGpu && !gpu) continue;
        const char* unit = onGpu ? "GPU" : "CPU";
        const char* onlyUnit = std::getenv("NEURAL_CHECK_UNIT");
        if (onlyUnit != nullptr && std::string(onlyUnit) != unit) continue;
        for (const auto& [network, open, env] : {
                 std::make_tuple(std::string("waifu2x"), &mediadroid::openWaifu2x, "WAIFU2X_DIR"),
                 std::make_tuple(std::string("Real-CUGAN"), &mediadroid::openRealCugan, "REALCUGAN_DIR")}) {
            const char* dir = std::getenv(env);
            const char* only = std::getenv("NEURAL_CHECK_NETWORK");
            if (dir == nullptr || (only != nullptr && network != only)) continue;
            auto net = (*open)(files(dir), onGpu);
            if (!net) {
                std::printf("%s on the %s: does not load\n", network.c_str(), unit);
                continue;
            }
            for (int side : sizes) enlarge((network + " " + unit).c_str(), *net, side, side);
        }
    }
    return 0;
}
