// The desktop check of the app's neural frame interpolation (native/src/networks.cpp): the self-test's frames
// (as NeuralInterpolator.kt makes them) through RIFE, DAIN, XVFI and FLAVR on the CPU and, where there is one, the
// Vulkan GPU — the in-between against the frame truly halfway, the time it took, and the memory the process peaked
// at; and the upscalers on a drawing.
//
// neural_check <glsl/ncnn folder> <RIFE model folder> <DAIN model folder> <XVFI model folder> [size ...] FLAVR_DIR,
// CAIN_DIR, IFRNET_DIR, WAIFU2X_DIR, REALCUGAN_DIR and REALSR_DIR, where set, add those networks. NEURAL_CHECK_UNIT=CPU
// or GPU runs only that unit, NEURAL_CHECK_NETWORK=<its name> only that network. NEURAL_CHECK_DUMP=<folder> writes the
// upscalers' pictures there. NEURAL_CHECK_FRAMES=<folder> runs the interpolators on real footage instead: every
// <name>-a.ppm, -b.ppm and -c.ppm there, the in-between of a and c against b.
#include <sys/resource.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
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

// A binary PPM (P6, 8 bits) as RGBA; empty if it cannot be read.
std::vector<unsigned char> readPpm(const std::filesystem::path& path, int& w, int& h) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int max = 0;
    in >> magic >> w >> h >> max;
    in.get();
    if (magic != "P6" || max != 255 || w <= 0 || h <= 0) return {};
    std::vector<unsigned char> rgb(static_cast<size_t>(w) * h * 3);
    in.read(reinterpret_cast<char*>(rgb.data()), static_cast<std::streamsize>(rgb.size()));
    std::vector<unsigned char> rgba(static_cast<size_t>(w) * h * 4, 255);
    for (size_t i = 0; i < static_cast<size_t>(w) * h; i++) {
        for (int c = 0; c < 3; c++) rgba[i * 4 + c] = rgb[i * 3 + c];
    }
    return rgba;
}

// [rgba] (w x h) resized to [tw] x [th] bilinearly, as the stage draws a frame smaller for the network.
std::vector<unsigned char> resized(const std::vector<unsigned char>& rgba, int w, int h, int tw, int th) {
    if (tw == w && th == h) return rgba;
    std::vector<unsigned char> out(static_cast<size_t>(tw) * th * 4);
    ncnn::resize_bilinear_c4(rgba.data(), w, h, out.data(), tw, th);
    return out;
}

// The stage's warp (glsl/video/rife-warp-es3.frag) in C++: the flow and mask of [fw] x [fh] (Interpolator::flow's
// layout) read bilinearly at each pixel's centre of the [w] x [h] frames, the flow grown by the sizes' ratios, each
// frame sampled along its flow as the warp layer samples (warp_layer.cpp), the two blended by the mask's sigmoid and
// the residual added. Into [rgb].
void warped(const std::vector<unsigned char>& a, const std::vector<unsigned char>& c, int w, int h,
            const std::vector<float>& flow, int fw, int fh, unsigned char* rgb) {
    const float* masks = flow.data() + static_cast<size_t>(fw) * fh * 4;
    const float sx = static_cast<float>(w) / fw;
    const float sy = static_cast<float>(h) / fh;
    auto lerp = [&](int channel, float u, float v) {
        // GL's linear filtering with clamping to the edge: texel centres at half pixels.
        const float x = std::clamp(u * fw - 0.5f, 0.f, fw - 1.f);
        const float y = std::clamp(v * fh - 0.5f, 0.f, fh - 1.f);
        const int x0 = static_cast<int>(x), y0 = static_cast<int>(y);
        const int x1 = std::min(x0 + 1, fw - 1), y1 = std::min(y0 + 1, fh - 1);
        const float ax = x - x0, ay = y - y0;
        auto at = [&](int xx, int yy) {
            const size_t i = static_cast<size_t>(yy) * fw + xx;
            return channel < 4 ? flow[i * 4 + channel] : masks[i * 4 + channel - 4];
        };
        return (at(x0, y0) * (1 - ax) + at(x1, y0) * ax) * (1 - ay) + (at(x0, y1) * (1 - ax) + at(x1, y1) * ax) * ay;
    };
    auto sample = [&](const std::vector<unsigned char>& frame, float px, float py, int k) {
        const int fx = static_cast<int>(std::floor(px)), fy = static_cast<int>(std::floor(py));
        const int x0 = std::clamp(fx, 0, w - 1), y0 = std::clamp(fy, 0, h - 1);
        const int x1 = std::clamp(fx + 1, 0, w - 1), y1 = std::clamp(fy + 1, 0, h - 1);
        const float alpha = px - x0, beta = py - y0;
        auto v = [&](int xx, int yy) { return frame[(static_cast<size_t>(yy) * w + xx) * 4 + k] / 255.f; };
        const float top = v(x0, y0) * (1 - alpha) + v(x1, y0) * alpha;
        const float bottom = v(x0, y1) * (1 - alpha) + v(x1, y1) * alpha;
        return top * (1 - beta) + bottom * beta;
    };
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const float u = (x + 0.5f) / w, v = (y + 0.5f) / h;
            const float ex = lerp(0, u, v) * sx, ey = lerp(1, u, v) * sy;
            const float lx = lerp(2, u, v) * sx, ly = lerp(3, u, v) * sy;
            const float share = 1.f / (1.f + std::exp(-lerp(4, u, v)));
            for (int k = 0; k < 3; k++) {
                const float value = sample(a, x + ex, y + ey, k) * share +
                    sample(c, x + lx, y + ly, k) * (1 - share) + lerp(5 + k, u, v);
                rgb[(static_cast<size_t>(y) * w + x) * 3 + k] =
                    static_cast<unsigned char>(std::clamp(value * 255.f + 0.5f, 0.f, 255.f));
            }
        }
    }
}

// Real footage instead of the self-test's frames: every <name>-a.ppm, -b.ppm, -c.ppm in [folder] (three frames in a
// row, of one size), the in-between of a and c against b — mean difference per value and PSNR, the network's and a
// blend's, averaged over the triplets, and the time each in-between took.
void footage(const char* name, mediadroid::Interpolator& net, const std::string& folder, int divisor = 0) {
    std::vector<std::filesystem::path> firsts;
    for (const auto& entry : std::filesystem::directory_iterator(folder)) {
        const std::string file = entry.path().filename().string();
        if (file.size() > 6 && file.substr(file.size() - 6) == "-a.ppm") firsts.push_back(entry.path());
    }
    std::sort(firsts.begin(), firsts.end());
    double network = 0, blend = 0, networkPsnr = 0, blendPsnr = 0, ms = 0;
    int triplets = 0, w = 0, h = 0;
    for (const auto& path : firsts) {
        const std::string stem = path.string().substr(0, path.string().size() - 6);
        int wb = 0, hb = 0, wc = 0, hc = 0;
        const auto a = readPpm(path, w, h);
        const auto b = readPpm(stem + "-b.ppm", wb, hb);
        const auto c = readPpm(stem + "-c.ppm", wc, hc);
        if (a.empty() || b.empty() || c.empty() || wb != w || wc != w || hb != h || hc != h) continue;
        std::vector<unsigned char> rgb(static_cast<size_t>(w) * h * 3);
        const auto start = std::chrono::steady_clock::now();
        int ran;
        if (divisor > 0) {
            // As the stage does it: the frames drawn smaller, the flow worked out there, the frames warped whole.
            const int fw = (w / divisor + 1) / 2 * 2, fh = (h / divisor + 1) / 2 * 2;
            const auto sa = resized(a, w, h, fw, fh);
            const auto sc = resized(c, w, h, fw, fh);
            std::vector<float> flow(static_cast<size_t>(fw) * fh * 8);
            ran = net.flow(sa.data(), sc.data(), fw, fh, 0.5f, flow.data());
            if (ran == 0) warped(a, c, w, h, flow, fw, fh, rgb.data());
        } else if (divisor < 0) {
            // The whole picture made smaller, the in-between made there and enlarged: what a slow device got before.
            const int fw = (w / -divisor + 1) / 2 * 2, fh = (h / -divisor + 1) / 2 * 2;
            const auto sa = resized(a, w, h, fw, fh);
            const auto sc = resized(c, w, h, fw, fh);
            std::vector<unsigned char> small(static_cast<size_t>(fw) * fh * 3);
            ran = net.between(sa.data(), sc.data(), fw, fh, 0.5f, small.data());
            if (ran == 0) ncnn::resize_bilinear_c3(small.data(), fw, fh, rgb.data(), w, h);
        } else {
            ran = net.between(a.data(), c.data(), w, h, 0.5f, rgb.data());
        }
        if (ran != 0) {
            std::printf("%s %s: failed\n", name, path.filename().string().c_str());
            continue;
        }
        const auto took = std::chrono::steady_clock::now() - start;
        if (triplets > 0) ms += std::chrono::duration<double, std::milli>(took).count();
        double absNet = 0, absBlend = 0, sqNet = 0, sqBlend = 0;
        const size_t pixels = static_cast<size_t>(w) * h;
        for (size_t i = 0; i < pixels; i++) {
            for (int k = 0; k < 3; k++) {
                const int truth = b[i * 4 + k];
                const int dn = rgb[i * 3 + k] - truth;
                const int db = (a[i * 4 + k] + c[i * 4 + k] + 1) / 2 - truth;
                absNet += std::abs(dn);
                absBlend += std::abs(db);
                sqNet += dn * dn;
                sqBlend += db * db;
            }
        }
        const double n = static_cast<double>(pixels) * 3;
        auto psnr = [&](double sq) { return sq <= 0 ? 99.0 : 10 * std::log10(255.0 * 255.0 * n / sq); };
        if (std::getenv("NEURAL_CHECK_EACH")) {
            std::printf("  %s: off by %.2f (%.2f dB), a blend by %.2f (%.2f dB)\n", path.filename().string().c_str(),
                        absNet / n, psnr(sqNet), absBlend / n, psnr(sqBlend));
        }
        network += absNet / n;
        blend += absBlend / n;
        networkPsnr += psnr(sqNet);
        blendPsnr += psnr(sqBlend);
        triplets++;
    }
    if (triplets == 0) {
        std::printf("%s: no triplets in %s\n", name, folder.c_str());
        return;
    }
    const std::string how = divisor > 0 ? " flow at 1/" + std::to_string(divisor)
        : divisor < 0               ? " picture at 1/" + std::to_string(-divisor)
                                    : "";
    std::printf("%s footage%s %dx%d, %d triplets: off by %.2f (%.2f dB), a blend by %.2f (%.2f dB); %.0f ms\n", name,
                how.c_str(), w, h, triplets, network / triplets, networkPsnr / triplets, blend / triplets,
                blendPsnr / triplets,
                triplets > 1 ? ms / (triplets - 1) : 0.0);
}

// The flow handed back and warped as the stage warps it, at the network's own size, against the network's own
// in-between: the largest difference per value away from the edges (where the network's padding is black and the
// stage's edge the picture's own), which must be at most 1.
void flowAgrees(const char* name, mediadroid::Interpolator& net, int w, int h) {
    const auto first = frame(w, h, 0);
    const auto last = frame(w, h, 2);
    std::vector<unsigned char> own(static_cast<size_t>(w) * h * 3), stage(own.size());
    std::vector<float> flow(static_cast<size_t>(w) * h * 8);
    if (net.between(first.data(), last.data(), w, h, 0.5f, own.data()) != 0 ||
        net.flow(first.data(), last.data(), w, h, 0.5f, flow.data()) != 0) {
        std::printf("%s %dx%d: the flow FAILED\n", name, w, h);
        return;
    }
    warped(first, last, w, h, flow, w, h, stage.data());
    const int edge = 24;
    int most = 0;
    for (int y = edge; y < h - edge; y++) {
        for (int x = edge; x < w - edge; x++) {
            for (int c = 0; c < 3; c++) {
                const size_t i = (static_cast<size_t>(y) * w + x) * 3 + c;
                most = std::max(most, std::abs(own[i] - stage[i]));
            }
        }
    }
    std::printf("%s %dx%d: the flow warped as the stage does %s the network's in-between (at most %d apart)\n", name,
                w, h, most <= 1 ? "agrees with" : "DIFFERS from", most);
}

// The upscalers' test picture (NeuralEnlarger.drawing): flat colours with round edges, as drawings are — a disc in
// each cell of a [cell]-pixel grid, its radius from 7/24 to 20/24 of the cell, each its own colour, on a light ground.
std::vector<unsigned char> drawing(int w, int h, int cell = 24) {
    std::vector<unsigned char> rgba(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const int cx = x / cell;
            const int cy = y / cell;
            const int dx = x - (cx * cell + cell / 2);
            const int dy = y - (cy * cell + cell / 2);
            const int radius = (7 + level(cx, cy, 3) % 14) * cell / 24;
            unsigned char* p = &rgba[(static_cast<size_t>(y) * w + x) * 4];
            const bool inside = dx * dx + dy * dy <= radius * radius;
            for (int c = 0; c < 3; c++) p[c] = static_cast<unsigned char>(inside ? level(cx, cy, c) : 230);
            p[3] = 255;
        }
    }
    return rgba;
}

// An upscaler on a drawing: the picture box-filtered down by the upscaler's scale and enlarged again, against the
// original — and, for scale, the small picture's pixels just repeated.
void enlarge(const char* name, mediadroid::Enlarger& net, int w, int h) {
    const int k = net.scale;
    // The grid grows with the scale, so the small picture always has 12-pixel cells.
    const auto full = drawing(w * k, h * k, 12 * k);
    std::vector<unsigned char> small(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 4; c++) {
                int sum = 0;
                for (int dy = 0; dy < k; dy++) {
                    for (int dx = 0; dx < k; dx++) {
                        sum += full[((static_cast<size_t>(y) * k + dy) * w * k + x * k + dx) * 4 + c];
                    }
                }
                const int mean = (sum + k * k / 2) / (k * k);
                small[(static_cast<size_t>(y) * w + x) * 4 + c] = static_cast<unsigned char>(mean);
            }
        }
    }
    std::vector<unsigned char> rgb(static_cast<size_t>(w) * h * k * k * 3);
    const auto start = std::chrono::steady_clock::now();
    const int ran = net.enlarge(small.data(), w, h, rgb.data(), 0);
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
        ppm(base + "-full.ppm", full.data(), w * k, h * k, 4);
        ppm(base + "-small.ppm", small.data(), w, h, 4);
        ppm(base + "-out.ppm", rgb.data(), w * k, h * k, 3);
    }
    double network = 0;
    double repeated = 0;
    double back = 0;
    long count = 0;
    long countBack = 0;
    const int W = w * k;
    for (int y = 8; y < h * k - 8; y++) {
        for (int x = 8; x < W - 8; x++) {
            for (int c = 0; c < 3; c++) {
                const int truth = full[(static_cast<size_t>(y) * W + x) * 4 + c];
                network += std::abs(rgb[(static_cast<size_t>(y) * W + x) * 3 + c] - truth);
                repeated += std::abs(small[(static_cast<size_t>(y / k) * w + x / k) * 4 + c] - truth);
                count++;
            }
        }
    }
    // The output shrunk back against its input: the app's correctness check (NeuralEnlarger).
    for (int y = 4; y < h - 4; y++) {
        for (int x = 4; x < w - 4; x++) {
            for (int c = 0; c < 3; c++) {
                int sum = 0;
                for (int dy = 0; dy < k; dy++) {
                    for (int dx = 0; dx < k; dx++) {
                        sum += rgb[((static_cast<size_t>(y) * k + dy) * W + x * k + dx) * 3 + c];
                    }
                }
                back += std::abs((sum + k * k / 2) / (k * k) - small[(static_cast<size_t>(y) * w + x) * 4 + c]);
                countBack++;
            }
        }
    }
    std::printf("%s %dx%d -> %dx%d: off by %.2f, pixels repeated by %.2f; shrunk back %.2f; %.0f ms; peak %ld MB\n",
                name, w, h, W, h * k, network / count, repeated / count, back / countBack, ms, peakMb());
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
                 std::make_pair(std::string("FLAVR"), &mediadroid::openFlavr),
                 std::make_pair(std::string("CAIN"), &mediadroid::openCain),
                 std::make_pair(std::string("IFRNet"), &mediadroid::openIfrnet)}) {
            const char* only = std::getenv("NEURAL_CHECK_NETWORK");
            if (only != nullptr && network != only) continue;
            const char* extra = network == "FLAVR" ? std::getenv("FLAVR_DIR")
                : network == "CAIN"                  ? std::getenv("CAIN_DIR")
                : network == "IFRNet"                ? std::getenv("IFRNET_DIR")
                                                     : "";
            if (extra == nullptr) continue;
            const std::string dir = network == "RIFE" ? argv[2]
                : network == "DAIN"                     ? argv[3]
                : network == "XVFI"                     ? argv[4]
                                                        : extra;
            auto net = open(mediadroid::fileLoader(dir), onGpu);
            if (!net) {
                std::printf("%s on the %s: does not load\n", network.c_str(), unit);
                continue;
            }
            const bool flows = network == "RIFE" || network == "IFRNet";
            if (const char* frames = std::getenv("NEURAL_CHECK_FRAMES")) {
                footage((network + " " + unit).c_str(), *net, frames);
                if (flows) {
                    for (int divisor : {2, 4, -2, -4}) footage((network + " " + unit).c_str(), *net, frames, divisor);
                }
                continue;
            }
            for (int side : sizes) {
                check((network + " " + unit).c_str(), *net, side, side);
                reuse((network + " " + unit).c_str(), *net, side, side);
                if (flows) flowAgrees((network + " " + unit).c_str(), *net, side, side);
            }
        }
    }
    for (bool onGpu : {false, true}) {
        if (onGpu && !gpu) continue;
        const char* unit = onGpu ? "GPU" : "CPU";
        const char* onlyUnit = std::getenv("NEURAL_CHECK_UNIT");
        if (onlyUnit != nullptr && std::string(onlyUnit) != unit) continue;
        using Open = std::function<std::unique_ptr<mediadroid::Enlarger>(const mediadroid::Loader&, bool)>;
        const Open waifu2x = [](const mediadroid::Loader& l, bool g) { return mediadroid::openWaifu2x(l, g); };
        const Open cugan = [](const mediadroid::Loader& l, bool g) { return mediadroid::openRealCugan(l, g); };
        const Open realsr = [](const mediadroid::Loader& l, bool g) { return mediadroid::openRealSr(l, g); };
        for (const auto& [network, open, env] : {
                 std::make_tuple(std::string("waifu2x"), waifu2x, "WAIFU2X_DIR"),
                 std::make_tuple(std::string("Real-CUGAN"), cugan, "REALCUGAN_DIR"),
                 std::make_tuple(std::string("RealSR"), realsr, "REALSR_DIR")}) {
            const char* dir = std::getenv(env);
            const char* only = std::getenv("NEURAL_CHECK_NETWORK");
            if (dir == nullptr || (only != nullptr && network != only)) continue;
            auto net = open(mediadroid::fileLoader(dir), onGpu);
            if (!net) {
                std::printf("%s on the %s: does not load\n", network.c_str(), unit);
                continue;
            }
            for (int side : sizes) enlarge((network + " " + unit).c_str(), *net, side, side);
        }
    }
    return 0;
}
