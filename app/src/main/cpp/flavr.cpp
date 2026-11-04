// FLAVR (Kalluri et al., WACV 2023; Apache-2.0, see app/THIRD-PARTY-NOTICE.FLAVR.txt) on ncnn: its 3D part and its 2D
// part as ncnn networks (tools/flavr/export.py), joined as tools/flavr/flavr_net.py's forward does — four frames in,
// their mean taken away; the four times of the 3D part's output folded into channels for the 2D part; the mean put
// back. The 2x model: the in-between of the middle two.
#include <algorithm>
#include <cstring>

#include "networks.h"

namespace mediadroid {

namespace {

constexpr int kFrames = 4;
constexpr int kPadding = 8;  // sizes that halve cleanly three times

struct Flavr : Interpolator {
    ncnn::Net body;
    ncnn::Net head;

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float,
                unsigned char* rgb) override {
        const int pw = (w + kPadding - 1) / kPadding * kPadding;
        const int ph = (h + kPadding - 1) / kPadding * kPadding;
        const unsigned char* frames[kFrames] = {before ? before : first, first, second, after ? after : second};

        // The frames as one 4D blob (3 channels x 4 times x ph x pw) in 0..1, the edges repeated to the padded size;
        // each channel's mean over the frames, as FLAVR takes it, then taken away.
        ncnn::Mat x(pw, ph, kFrames, 3);
        float mean[3] = {0.f, 0.f, 0.f};
        for (int t = 0; t < kFrames; t++) {
            for (int y = 0; y < ph; y++) {
                const int sy = std::min(y, h - 1);
                for (int xx = 0; xx < pw; xx++) {
                    const int sx = std::min(xx, w - 1);
                    const unsigned char* p = frames[t] + (static_cast<size_t>(sy) * w + sx) * 4;
                    for (int c = 0; c < 3; c++) {
                        const float v = p[c] / 255.f;
                        x.channel(c).depth(t).row(y)[xx] = v;
                        if (y < h && xx < w) mean[c] += v;
                    }
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            mean[c] /= static_cast<float>(kFrames) * w * h;
            float* plane = x.channel(c);
            for (int i = 0; i < kFrames * pw * ph; i++) plane[i] -= mean[c];
        }

        ncnn::Mat features;
        {
            ncnn::Extractor ex = body.create_extractor();
            ex.input("in0", x);
            if (ex.extract("out", features) != 0) return -2;
        }
        // The four times folded into channels, time major: channel t * 64 + c.
        const int channels = features.c;
        ncnn::Mat folded(features.w, features.h, channels * kFrames);
        for (int t = 0; t < kFrames; t++) {
            for (int c = 0; c < channels; c++) {
                std::memcpy(folded.channel(t * channels + c), features.channel(c).depth(t),
                            sizeof(float) * features.w * features.h);
            }
        }
        ncnn::Mat out;
        {
            ncnn::Extractor ex = head.create_extractor();
            ex.input("in0", folded);
            if (ex.extract("out", out) != 0) return -2;
        }
        ncnn::Mat picture(w, h, 3);
        for (int c = 0; c < 3; c++) {
            const float* o = out.channel(c);
            float* p = picture.channel(c);
            for (int y = 0; y < h; y++) {
                for (int xx = 0; xx < w; xx++) {
                    p[y * w + xx] = std::clamp(o[y * out.w + xx] + mean[c], 0.f, 1.f) * 255.f + 0.5f;
                }
            }
        }
        picture.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
        return 0;
    }
};

}  // namespace

std::unique_ptr<Interpolator> openFlavr(const Loader& load, bool gpu) {
    // ncnn's 3D layers run on the CPU only; asked for the GPU, there is none to give.
    if (gpu) return nullptr;
    auto flavr = std::make_unique<Flavr>();
    for (ncnn::Net* net : {&flavr->body, &flavr->head}) {
        configure(*net, false);
    }
    if (load(flavr->body, "body") != 0 || load(flavr->head, "head") != 0) return nullptr;
    return flavr;
}

}  // namespace mediadroid
