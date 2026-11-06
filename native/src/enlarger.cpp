// Neural upscalers on ncnn (Vulkan or the CPU), as nihui's ncnn ports run them (MIT; see
// app/THIRD-PARTY-NOTICE.waifu2x.txt, .Real-CUGAN.txt and .RealSR.txt) — two, three or four times the size: the frame
// in RGB 0..1, its edges extended by [padding] on every side, and at the bottom and right to a multiple of what the
// network halves and doubles by; the network trims what it added, so the output's top left is the picture enlarged.
// waifu2x repeats the edge pixels, Real-CUGAN mirrors them. As the ports do, a picture may go through in tiles, each
// with [padding] of its real neighbours around (mirrored only at the picture's edges), and each tile eight ways (TTA:
// mirrored and turned, every result turned back, the eight averaged).
#include <algorithm>
#include <cstring>

#include <gpu.h>

#include "networks.h"

namespace mediadroid {

namespace {

int roundUp(int value, int step) {
    return (value + step - 1) / step * step;
}

// [m] (three planes) turned the [way]-th of eight ways — bit 0 flips it upside down, bit 1 mirrors it, bit 2 swaps
// its rows and columns, in that order — or, [back], [m] turned that way taken back to how it was.
ncnn::Mat turned(const ncnn::Mat& m, int way, bool back) {
    const bool down = way & 1;
    const bool across = way & 2;
    const bool swap = way & 4;
    // The source's size: turning back a swapped picture starts from one whose sides are the other way round.
    const int outW = swap ? m.h : m.w;
    const int outH = swap ? m.w : m.h;
    ncnn::Mat out(outW, outH, m.c);
    for (int q = 0; q < m.c; q++) {
        const float* in = m.channel(q);
        float* to = out.channel(q);
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int sx = x;
                int sy = y;
                if (!back) {
                    // out = swap(across(down(m))): undo in reverse to find the source pixel.
                    if (swap) std::swap(sx, sy);
                    if (across) sx = m.w - 1 - sx;
                    if (down) sy = m.h - 1 - sy;
                } else {
                    // m = swap(across(down(out))): out's pixel (x, y) lands at the pixel found the same way forwards.
                    int tx = x;
                    int ty = y;
                    if (down) ty = outH - 1 - ty;
                    if (across) tx = outW - 1 - tx;
                    if (swap) std::swap(tx, ty);
                    sx = tx;
                    sy = ty;
                }
                to[y * outW + x] = in[sy * m.w + sx];
            }
        }
    }
    return out;
}

struct NcnnEnlarger : Enlarger {
    ncnn::Net net;
    const char* input = "in0";
    const char* output = "out0";
    int padding = 18;
    int border = ncnn::BORDER_REPLICATE;
    // What a tile's size is rounded up to: the network halves it (2) or more (Real-CUGAN at 3x, 4), or nothing (1).
    int multiple = 2;
    // Whether the network keeps what was added around (RealSR): the picture then starts padding x scale in.
    bool keepsPadding = false;
    // Whether the network gives only what is added to the input enlarged by repeating its pixels (Real-CUGAN at 4x).
    bool residual = false;
    // Whether each tile runs eight ways.
    bool tta = false;

    // The network over one padded tile: its output, eight ways averaged where asked, the input added where residual.
    int run(const ncnn::Mat& in, ncnn::Mat& result) {
        auto once = [&](const ncnn::Mat& x, ncnn::Mat& y) {
            ncnn::Extractor ex = net.create_extractor();
            ex.input(input, x);
            return ex.extract(output, y);
        };
        if (!tta) {
            if (once(in, result) != 0) return -2;
        } else {
            for (int way = 0; way < 8; way++) {
                ncnn::Mat y;
                if (once(way == 0 ? in : turned(in, way, false), y) != 0) return -2;
                ncnn::Mat back = way == 0 ? y.clone() : turned(y, way, true);
                if (way == 0) {
                    result = back;
                    continue;
                }
                if (back.w != result.w || back.h != result.h) return -2;
                for (int q = 0; q < result.c; q++) {
                    float* sum = result.channel(q);
                    const float* add = back.channel(q);
                    for (int i = 0; i < result.w * result.h; i++) sum[i] += add[i];
                }
            }
            for (int q = 0; q < result.c; q++) {
                float* sum = result.channel(q);
                for (int i = 0; i < result.w * result.h; i++) sum[i] *= 1.f / 8;
            }
        }
        if (residual) {
            // As realcugan-ncnn-vulkan's 4x postproc: the input pixel under each output pixel added back.
            for (int q = 0; q < result.c; q++) {
                float* to = result.channel(q);
                const float* from = in.channel(q);
                for (int y = 0; y < result.h; y++) {
                    const float* row = from + (padding + y / scale) * in.w + padding;
                    for (int x = 0; x < result.w; x++) to[y * result.w + x] += row[x / scale];
                }
            }
        }
        return 0;
    }

    int enlarge(const unsigned char* rgba, int w, int h, unsigned char* rgb, int tile) override {
        ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
        const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        picture.substract_mean_normalize(nullptr, toUnit);
        ncnn::Mat padded;
        const int extraW = roundUp(w, multiple) - w;
        const int extraH = roundUp(h, multiple) - h;
        ncnn::copy_make_border(picture, padded, padding, padding + extraH, padding, padding + extraW, border, 0.f);
        // Tiles of a size every network divides evenly, so only the last in a row or column is rounded up — by as
        // much as the whole picture is.
        const int tileW = tile > 0 ? std::min(roundUp(tile, 4), w) : w;
        const int tileH = tile > 0 ? std::min(roundUp(tile, 4), h) : h;
        const int at = keepsPadding ? padding * scale : 0;
        // Back to bytes, rounded: to_pixels cuts off, so half a step is added first, as the ports do.
        const float half[3] = {-0.5f / 255.f, -0.5f / 255.f, -0.5f / 255.f};
        const float toBytes[3] = {255.f, 255.f, 255.f};
        for (int y0 = 0; y0 < h; y0 += tileH) {
            const int th = std::min(tileH, h - y0);
            const int inH = roundUp(th, multiple) + 2 * padding;
            for (int x0 = 0; x0 < w; x0 += tileW) {
                const int tw = std::min(tileW, w - x0);
                const int inW = roundUp(tw, multiple) + 2 * padding;
                ncnn::Mat in;
                if (tileW == w && tileH == h) {
                    in = padded;
                } else {
                    ncnn::copy_cut_border(padded, in, y0, padded.h - y0 - inH, x0, padded.w - x0 - inW);
                }
                ncnn::Mat result;
                if (run(in, result) != 0) return -2;
                if (result.w < at + scale * tw || result.h < at + scale * th) return -2;
                ncnn::Mat enlarged;
                ncnn::copy_cut_border(result, enlarged, at, result.h - at - scale * th, at, result.w - at - scale * tw);
                enlarged.substract_mean_normalize(half, toBytes);
                unsigned char* to = rgb + (static_cast<size_t>(y0) * scale * w * scale + x0 * scale) * 3;
                enlarged.to_pixels(to, ncnn::Mat::PIXEL_RGB, w * scale * 3);
            }
        }
        return 0;
    }
};

std::unique_ptr<Enlarger> open(const Loader& load, bool gpu, const char* name, const char* input, const char* output,
                               int border, int scale, int padding, int multiple, bool tta, bool keepsPadding = false,
                               bool residual = false) {
    auto it = std::make_unique<NcnnEnlarger>();
    configure(it->net, gpu && gpuReady());
    it->input = input;
    it->output = output;
    it->border = border;
    it->scale = scale;
    it->padding = padding;
    it->multiple = multiple;
    it->keepsPadding = keepsPadding;
    it->residual = residual;
    it->tta = tta;
    if (load(it->net, name) != 0) return nullptr;
    return it;
}

}  // namespace

int gpuHeapBudget() {
    if (!gpuReady()) return 0;
    return static_cast<int>(ncnn::get_gpu_device()->get_heap_budget());
}

std::unique_ptr<Enlarger> openWaifu2x(const Loader& load, bool gpu, const char* model, bool tta) {
    // CUnet, twice the size (models-cunet/<model>, a noise level each): 18 pixels around, edges repeated.
    return open(load, gpu, model, "Input1", "Eltwise4", ncnn::BORDER_REPLICATE, 2, 18, 2, tta);
}

std::unique_ptr<Enlarger> openRealCugan(const Loader& load, bool gpu, const char* model, int scale, bool tta) {
    // The SE or pro models (models-se, models-pro), mirrored, as realcugan-ncnn-vulkan's main.cpp sets them up: at 2x
    // 18 pixels around and even sizes; at 3x 14 around and sizes a multiple of four; at 4x 19 around, even sizes, and
    // the network gives what is added to the picture's pixels repeated four times.
    if (scale == 3) return open(load, gpu, model, "in0", "out0", ncnn::BORDER_REFLECT, 3, 14, 4, tta);
    if (scale == 4) return open(load, gpu, model, "in0", "out0", ncnn::BORDER_REFLECT, 4, 19, 2, tta, false, true);
    return open(load, gpu, model, "in0", "out0", ncnn::BORDER_REFLECT, 2, 18, 2, tta);
}

std::unique_ptr<Enlarger> openRealSr(const Loader& load, bool gpu, bool tta) {
    // DF2K_JPEG, four times the size (models-DF2K_JPEG/x4): 10 pixels around, mirrored, kept in its output.
    return open(load, gpu, "x4", "data", "output", ncnn::BORDER_REFLECT, 4, 10, 1, tta, true);
}

std::unique_ptr<Enlarger> openRealEsrgan(const Loader& load, bool gpu, const char* model, bool tta) {
    // Four times the size (Real-ESRGAN-ncnn-vulkan's models/, MIT; see
    // engine/desktop/THIRD-PARTY-NOTICE.Real-ESRGAN-ncnn.txt): its prepadding of 10, mirrored as its preproc shader
    // mirrors, kept in its output — as RealSR.
    return open(load, gpu, model, "data", "output", ncnn::BORDER_REFLECT, 4, 10, 1, tta, true);
}

}  // namespace mediadroid
