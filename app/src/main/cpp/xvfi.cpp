// XVFI-Net (Sim, Oh, Kim, ICCV 2021; see app/THIRD-PARTY-NOTICE.XVFI.txt) on ncnn: its convolution blocks as ncnn
// networks (tools/xvfi/export.py) joined as tools/xvfi/xvfi_net.py's forward does — the Vimeo-trained model, a module
// scale of 2 and one coarser level (S_tst = 1). The blocks run on the GPU or the CPU; what joins them (warping, the
// complementary flow reversal, the blending) on the CPU.
#include <algorithm>
#include <cmath>
#include <initializer_list>
#include <vector>

#include "networks.h"

namespace mediadroid {

namespace {

constexpr int kScale = 2;     // the module scale: features at half size
constexpr int kPadding = 32;  // sizes that halve cleanly through every level and hourglass

ncnn::Mat run(ncnn::Net& net, const ncnn::Mat& in) {
    ncnn::Extractor ex = net.create_extractor();
    ex.input("in0", in);
    ncnn::Mat out;
    ex.extract("out", out);
    return out;
}

ncnn::Mat cat(std::initializer_list<const ncnn::Mat*> parts) {
    int channels = 0;
    for (const ncnn::Mat* p : parts) channels += p->c;
    const ncnn::Mat& first = **parts.begin();
    ncnn::Mat out(first.w, first.h, channels);
    int c = 0;
    for (const ncnn::Mat* p : parts) {
        for (int q = 0; q < p->c; q++, c++) std::copy_n(p->channel(q).row(0), p->w * p->h, out.channel(c).row(0));
    }
    return out;
}

// Channels [from, to) of [m].
ncnn::Mat slice(const ncnn::Mat& m, int from, int to) {
    ncnn::Mat out(m.w, m.h, to - from);
    for (int q = from; q < to; q++) std::copy_n(m.channel(q).row(0), m.w * m.h, out.channel(q - from).row(0));
    return out;
}

float sigmoid(float v) { return 1.f / (1.f + std::exp(-v)); }

// [x] sampled where [flow] (two planes, pixels) points: bilinear with align_corners, zeros outside, and 0 where less
// than 0.999 of the sample's weight falls inside the picture (xvfi_net.bwarp).
ncnn::Mat bwarp(const ncnn::Mat& x, const ncnn::Mat& flow, int flowAt = 0) {
    const int w = x.w;
    const int h = x.h;
    ncnn::Mat out(w, h, x.c);
    const float* fx = flow.channel(flowAt);
    const float* fy = flow.channel(flowAt + 1);
    for (int i = 0; i < w * h; i++) {
        const float sx = i % w + fx[i];
        const float sy = i / w + fy[i];
        const int x0 = static_cast<int>(std::floor(sx));
        const int y0 = static_cast<int>(std::floor(sy));
        const float ax = sx - x0;
        const float ay = sy - y0;
        const int xs[2] = {x0, x0 + 1};
        const int ys[2] = {y0, y0 + 1};
        const float wx[2] = {1 - ax, ax};
        const float wy[2] = {1 - ay, ay};
        float cover = 0.f;
        for (int a = 0; a < 2; a++) {
            for (int b = 0; b < 2; b++) {
                if (xs[b] >= 0 && xs[b] < w && ys[a] >= 0 && ys[a] < h) cover += wy[a] * wx[b];
            }
        }
        for (int q = 0; q < x.c; q++) {
            float v = 0.f;
            if (cover >= 0.999f) {
                const float* plane = x.channel(q);
                for (int a = 0; a < 2; a++) {
                    for (int b = 0; b < 2; b++) {
                        if (xs[b] >= 0 && xs[b] < w && ys[a] >= 0 && ys[a] < h) {
                            v += wy[a] * wx[b] * plane[ys[a] * w + xs[b]];
                        }
                    }
                }
            }
            out.channel(q)[i] = v;
        }
    }
    return out;
}

// [m] doubled, bilinear without align_corners (PyTorch's: sources below 0 clamped), values times [gain].
ncnn::Mat up2(const ncnn::Mat& m, float gain) {
    const int w = m.w * 2;
    const int h = m.h * 2;
    ncnn::Mat out(w, h, m.c);
    auto source = [](int d, int size, int& i0, int& i1, float& f) {
        float s = std::max((d + 0.5f) / 2.f - 0.5f, 0.f);
        i0 = std::min(static_cast<int>(s), size - 1);
        i1 = std::min(i0 + 1, size - 1);
        f = s - i0;
    };
    for (int q = 0; q < m.c; q++) {
        const ncnn::Mat in = m.channel(q);
        float* o = out.channel(q);
        for (int y = 0; y < h; y++) {
            int y0, y1;
            float fy;
            source(y, m.h, y0, y1, fy);
            for (int x = 0; x < w; x++) {
                int x0, x1;
                float fx;
                source(x, m.w, x0, x1, fx);
                const float top = in.row(y0)[x0] * (1 - fx) + in.row(y0)[x1] * fx;
                const float bottom = in.row(y1)[x0] * (1 - fx) + in.row(y1)[x1] * fx;
                o[y * w + x] = gain * (top * (1 - fy) + bottom * fy);
            }
        }
    }
    return out;
}

// Forward warp (xvfi_net.z_fwarp): each pixel of [values] (2 planes) added, times [scale], to the four pixels
// around where [flow] (the same planes, times [scale]) takes it, weighted by (z + 1e-5) * exp(-distance^2); the sums
// into [sums], the weights into [weights].
void zFwarp(const ncnn::Mat& flow, float scale, const ncnn::Mat& z, int zAt, ncnn::Mat& sums, ncnn::Mat& weights) {
    const int w = flow.w;
    const int h = flow.h;
    sums.create(w, h, 2);
    sums.fill(0.f);
    weights.create(w, h, 1);
    weights.fill(0.f);
    const float* f0 = flow.channel(0);
    const float* f1 = flow.channel(1);
    const float* zz = z.channel(zAt);
    float* s0 = sums.channel(0);
    float* s1 = sums.channel(1);
    float* ws = weights.channel(0);
    for (int i = 0; i < w * h; i++) {
        const float fx = scale * f0[i];
        const float fy = scale * f1[i];
        const float x1 = std::floor(fx);
        const float y1 = std::floor(fy);
        const float confidence = sigmoid(zz[i]) + 1e-5f;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                const int r = i / w + static_cast<int>(y1) + dy;
                const int c = i % w + static_cast<int>(x1) + dx;
                if (r < 0 || r >= h || c < 0 || c >= w) continue;
                const float ex = fx - (x1 + dx);
                const float ey = fy - (y1 + dy);
                const float weight = confidence * std::exp(-(ey * ey + ex * ex));
                s0[r * w + c] += f0[i] * weight;
                s1[r * w + c] += f1[i] * weight;
                ws[r * w + c] += weight;
            }
        }
    }
}

// PyTorch's pixel_shuffle by [r]: channel c * r * r + i * r + j becomes the pixel (y * r + i, x * r + j) of channel c.
ncnn::Mat pixelShuffle(const ncnn::Mat& m, int r) {
    const int c = m.c / (r * r);
    ncnn::Mat out(m.w * r, m.h * r, c);
    for (int q = 0; q < c; q++) {
        float* o = out.channel(q);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
                const float* in = m.channel(q * r * r + i * r + j);
                for (int y = 0; y < m.h; y++) {
                    for (int x = 0; x < m.w; x++) o[(y * r + i) * out.w + x * r + j] = in[y * m.w + x];
                }
            }
        }
    }
    return out;
}

struct Xvfi : Interpolator {
    ncnn::Net extract, context, bottom, flow1, flow2, flow3, refine;

    // A frame and what is worked out from it alone: its features and the coarser level's.
    struct Frame {
        long long id = 0;
        ncnn::Mat picture, features, coarser;
    };
    Frame kept[2];

    // [rgba]'s frame with the id [id]: the one kept where it is there (and the id known), else worked out and kept in
    // place of the other than [keep].
    const Frame& frame(const unsigned char* rgba, long long id, int w, int h, int pw, int ph, const Frame* keep) {
        for (Frame& f : kept) {
            if (id != 0 && f.id == id && f.picture.w == pw && f.picture.h == ph) return f;
        }
        Frame& slot = &kept[0] == keep ? kept[1] : kept[0];
        ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
        const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        picture.substract_mean_normalize(nullptr, toUnit);
        ncnn::copy_make_border(picture, slot.picture, 0, ph - h, 0, pw - w, ncnn::BORDER_REPLICATE, 0.f);
        slot.features = run(extract, slot.picture);
        slot.coarser = slot.features.empty() ? ncnn::Mat() : run(context, slot.features);
        slot.id = id;
        return slot;
    }

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float t,
                unsigned char* rgb) override {
        const int pw = (w + kPadding - 1) / kPadding * kPadding;
        const int ph = (h + kPadding - 1) / kPadding * kPadding;
        // The frames with their features at half size and the next coarser level's — the earlier one usually kept
        // from the pair before.
        const Frame& earlier = frame(first, earlierId, w, h, pw, ph, nullptr);
        const Frame& later = frame(second, laterId, w, h, pw, ph, &earlier);
        const ncnn::Mat a = earlier.picture;
        const ncnn::Mat b = later.picture;
        const ncnn::Mat fa = earlier.features;
        const ncnn::Mat fb = later.features;
        const ncnn::Mat ca = earlier.coarser;
        const ncnn::Mat cb = later.coarser;
        if (fa.empty() || ca.empty() || fb.empty() || cb.empty()) return -2;

        // The coarsest level: the flows both ways.
        const ncnn::Mat coarse = slice(run(bottom, cat({&ca, &cb})), 0, 4);
        // The full level: the flows from the coarser ones, refined.
        const ncnn::Mat up = up2(coarse, 2.f);
        const ncnn::Mat warpedB = bwarp(fb, up, 0);
        const ncnn::Mat warpedA = bwarp(fa, up, 2);
        const ncnn::Mat flowA = run(flow1, cat({&fa, &warpedB}));
        const ncnn::Mat flowB = run(flow1, cat({&fb, &warpedA}));
        const ncnn::Mat tmp = run(flow2, cat({&flowA, &flowB, &up}));
        if (tmp.empty()) return -2;
        ncnn::Mat flow = slice(tmp, 0, 4);
        for (int q = 0; q < 4; q++) {
            float* f = flow.channel(q);
            const float* u = up.channel(q);
            for (int i = 0; i < flow.w * flow.h; i++) f[i] += u[i];
        }
        const ncnn::Mat f01 = slice(flow, 0, 2);
        const ncnn::Mat f10 = slice(flow, 2, 4);

        // Complementary flow reversal (the paper's Eq. 1 and 2): the flows from t back to each frame.
        ncnn::Mat forward, norm0, backward, norm1;
        zFwarp(f01, t, tmp, 4, forward, norm0);
        zFwarp(f10, 1 - t, tmp, 5, backward, norm1);
        const int n = flow.w * flow.h;
        ncnn::Mat ft0(flow.w, flow.h, 2);
        ncnn::Mat ft1(flow.w, flow.h, 2);
        for (int q = 0; q < 2; q++) {
            const float* fw = forward.channel(q);
            const float* bw = backward.channel(q);
            float* o0 = ft0.channel(q);
            float* o1 = ft1.channel(q);
            for (int i = 0; i < n; i++) {
                float v0 = -(1 - t) * (t * fw[i]) + t * (t * bw[i]);
                float v1 = (1 - t) * ((1 - t) * fw[i]) - t * ((1 - t) * bw[i]);
                const float norm = (1 - t) * norm0.channel(0)[i] + t * norm1.channel(0)[i];
                if (norm > 0.f) {
                    v0 /= norm;
                    v1 /= norm;
                }
                o0[i] = v0;
                o1[i] = v1;
            }
        }
        ncnn::Mat wa = bwarp(fa, ft0);
        ncnn::Mat wb = bwarp(fb, ft1);
        ncnn::Mat refined = run(flow3, cat({&fa, &wa, &wb, &fb, &ft0, &ft1}));
        if (refined.empty()) return -2;
        for (int q = 0; q < 4; q++) {
            float* r = refined.channel(q);
            const float* add = (q < 2 ? ft0 : ft1).channel(q % 2);
            for (int i = 0; i < n; i++) r[i] += add[i];
        }
        ft0 = slice(refined, 0, 2);
        ft1 = slice(refined, 2, 4);
        wa = bwarp(fa, ft0);
        wb = bwarp(fb, ft1);

        // To full size: the frames warped, then occlusion and residual from the refinement network.
        const ncnn::Mat full0 = up2(ft0, static_cast<float>(kScale));
        const ncnn::Mat full1 = up2(ft1, static_cast<float>(kScale));
        const ncnn::Mat wi0 = bwarp(a, full0);
        const ncnn::Mat wi1 = bwarp(b, full1);
        const ncnn::Mat features = cat({&fa, &fb, &wa, &wb});
        const ncnn::Mat shuffled = pixelShuffle(features, kScale);
        const ncnn::Mat out = run(refine, cat({&shuffled, &a, &b, &wi0, &wi1, &full0, &full1}));
        if (out.empty()) return -2;

        ncnn::Mat picture(w, h, 3);
        const float* occ = out.channel(0);
        for (int q = 0; q < 3; q++) {
            const float* i0 = wi0.channel(q);
            const float* i1 = wi1.channel(q);
            const float* residual = out.channel(q + 1);
            float* o = picture.channel(q);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    const int i = y * pw + x;
                    const float o0 = sigmoid(occ[i]);
                    const float o1 = 1 - o0;
                    const float v = ((1 - t) * o0 * i0[i] + t * o1 * i1[i]) / ((1 - t) * o0 + t * o1) + residual[i];
                    o[y * w + x] = std::clamp(v, 0.f, 1.f) * 255.f + 0.5f;
                }
            }
        }
        picture.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
        return 0;
    }
};

}  // namespace

std::unique_ptr<Interpolator> openXvfi(const Loader& load, bool gpu) {
    auto xvfi = std::make_unique<Xvfi>();
    const bool vulkan = gpu && gpuReady();
    ncnn::Net* nets[] = {&xvfi->extract, &xvfi->context, &xvfi->bottom, &xvfi->flow1,
                         &xvfi->flow2, &xvfi->flow3, &xvfi->refine};
    const char* names[] = {"extract", "context", "bottom", "flow1", "flow2", "flow3", "refine"};
    for (int i = 0; i < 7; i++) {
        configure(*nets[i], vulkan);
        if (load(*nets[i], names[i]) != 0) return nullptr;
    }
    return xvfi;
}

}  // namespace mediadroid
