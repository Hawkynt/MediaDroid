// Two more networks that make the in-between of a pair, as nihui's ncnn ports run them (MIT; see
// app/THIRD-PARTY-NOTICE.CAIN.txt and .IFRNet.txt):
//  - CAIN: each frame's own mean (per channel) taken away, its edges repeated to a multiple of 32; the in-between
//    halfway, the two means' average put back.
//  - IFRNet: the frames shifted to -0.5..0.5, zeros to a multiple of 32, the time as a plane; 0.5 put back.
#include <algorithm>

#include "networks.h"
#include "warp_layer.h"

namespace mediadroyd {

namespace {

int roundUp32(int value) {
    return (value + 31) / 32 * 32;
}

// [rgba] as three planes in 0..1 minus [offset] (per channel), padded at the right and bottom by [border].
ncnn::Mat planes(const unsigned char* rgba, int w, int h, const float offset[3], int border) {
    ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
    const float mean[3] = {offset[0] * 255.f, offset[1] * 255.f, offset[2] * 255.f};
    const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    picture.substract_mean_normalize(mean, toUnit);
    ncnn::Mat padded;
    ncnn::copy_make_border(picture, padded, 0, roundUp32(h) - h, 0, roundUp32(w) - w, border, 0.f);
    return padded;
}

// [result]'s top left w x h plus [offset] (per channel), as RGB bytes into [rgb], rounded.
void bytes(const ncnn::Mat& result, const float offset[3], int w, int h, unsigned char* rgb) {
    ncnn::Mat picture;
    ncnn::copy_cut_border(result, picture, 0, result.h - h, 0, result.w - w);
    // (v + offset) * 255 + 0.5: to_pixels cuts off, so half a step goes in first.
    const float mean[3] = {-offset[0] - 0.5f / 255.f, -offset[1] - 0.5f / 255.f, -offset[2] - 0.5f / 255.f};
    const float toBytes[3] = {255.f, 255.f, 255.f};
    picture.substract_mean_normalize(mean, toBytes);
    picture.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
}

// Each channel's mean of [rgba], in 0..1.
void channelMeans(const unsigned char* rgba, int w, int h, float mean[3]) {
    double sum[3] = {0, 0, 0};
    for (int i = 0; i < w * h; i++) {
        for (int c = 0; c < 3; c++) sum[c] += rgba[i * 4 + c];
    }
    for (int c = 0; c < 3; c++) mean[c] = static_cast<float>(sum[c] / (static_cast<double>(w) * h) / 255.0);
}

struct Cain : Interpolator {
    ncnn::Net net;

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float,
                unsigned char* rgb) override {
        float mean0[3];
        float mean1[3];
        channelMeans(first, w, h, mean0);
        channelMeans(second, w, h, mean1);
        ncnn::Extractor ex = net.create_extractor();
        ex.input("x.1", planes(first, w, h, mean0, ncnn::BORDER_REPLICATE));
        ex.input("x.3", planes(second, w, h, mean1, ncnn::BORDER_REPLICATE));
        ncnn::Mat result;
        if (ex.extract("4070", result) != 0) return -2;
        const float mean[3] = {(mean0[0] + mean1[0]) / 2, (mean0[1] + mean1[1]) / 2, (mean0[2] + mean1[2]) / 2};
        bytes(result, mean, w, h, rgb);
        return 0;
    }
};

struct Ifrnet : Interpolator {
    ncnn::Net net;

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                unsigned char* rgb) override {
        const float half[3] = {0.5f, 0.5f, 0.5f};
        ncnn::Mat result;
        {
            // At the network's coarsest level, a sixteenth of the padded size each way, as ifrnet-ncnn-vulkan makes
            // it: the network joins it there with its features (cat_0), and a whole-size plane overran that join.
            ncnn::Mat timestep(roundUp32(w) / 16, roundUp32(h) / 16, 1);
            timestep.fill(time);
            ncnn::Extractor ex = net.create_extractor();
            ex.input("in0", planes(first, w, h, half, ncnn::BORDER_CONSTANT));
            ex.input("in1", planes(second, w, h, half, ncnn::BORDER_CONSTANT));
            ex.input("in2", timestep);
            ncnn::Mat out;
            if (ex.extract("out0", out) != 0) return -2;
            // A copy of its own: what the extractor hands out may live in its pool, which goes with it.
            result = out.clone();
        }
        bytes(result, half, w, h, rgb);
        return 0;
    }

    // The graph read up to its last flows ("193" towards the earlier frame, "195" the later, at the padded size),
    // its mask logits ("189") and the residual it adds to the blend ("190"), before the warp and blend that make
    // "out0" — the frames go in less a half, which the blend of two leaves off and the residual does not carry.
    int flow(const unsigned char* first, const unsigned char* second, int w, int h, float time, float* out) override {
        const float half[3] = {0.5f, 0.5f, 0.5f};
        ncnn::Mat timestep(roundUp32(w) / 16, roundUp32(h) / 16, 1);
        timestep.fill(time);
        ncnn::Extractor ex = net.create_extractor();
        ex.input("in0", planes(first, w, h, half, ncnn::BORDER_CONSTANT));
        ex.input("in1", planes(second, w, h, half, ncnn::BORDER_CONSTANT));
        ex.input("in2", timestep);
        ncnn::Mat earlier, later, mask, residual;
        if (ex.extract("193", earlier) != 0 || ex.extract("195", later) != 0 || ex.extract("189", mask) != 0 ||
            ex.extract("190", residual) != 0) {
            return -2;
        }
        for (const ncnn::Mat* m : {&earlier, &later, &mask, &residual}) {
            if (m->w < w || m->h < h) return -2;
        }
        if (earlier.c < 2 || later.c < 2 || mask.c < 1 || residual.c < 3) return -2;
        float* rest = out + static_cast<size_t>(w) * h * 4;
        for (int y = 0; y < h; y++) {
            float* flows = out + static_cast<size_t>(y) * w * 4;
            float* more = rest + static_cast<size_t>(y) * w * 4;
            for (int x = 0; x < w; x++) {
                flows[x * 4] = earlier.channel(0).row(y)[x];
                flows[x * 4 + 1] = earlier.channel(1).row(y)[x];
                flows[x * 4 + 2] = later.channel(0).row(y)[x];
                flows[x * 4 + 3] = later.channel(1).row(y)[x];
                more[x * 4] = mask.channel(0).row(y)[x];
                for (int c = 0; c < 3; c++) more[x * 4 + 1 + c] = residual.channel(c).row(y)[x];
            }
        }
        return 0;
    }
};

}  // namespace

std::unique_ptr<Interpolator> openCain(const Loader& load, bool gpu) {
    auto cain = std::make_unique<Cain>();
    configure(cain->net, gpu && gpuReady());
    if (load(cain->net, "cain") != 0) return nullptr;
    return cain;
}

std::unique_ptr<Interpolator> openIfrnet(const Loader& load, bool gpu) {
    auto ifrnet = std::make_unique<Ifrnet>();
    configure(ifrnet->net, gpu && gpuReady(), true);
    // On the CPU every blob one channel a plane: ncnn's own Concat overruns its output when it joins the warp's
    // unpacked blobs with packed ones (AddressSanitizer: heap-buffer-overflow in Concat_x86_avx), as IFRNet does.
    if (!ifrnet->net.opt.use_vulkan_compute) ifrnet->net.opt.use_packing_layout = false;
    ifrnet->net.register_custom_layer("ifrnet.Warp", createFlowFirstWarpLayer);
    if (load(ifrnet->net, "ifrnet") != 0) return nullptr;
    return ifrnet;
}

}  // namespace mediadroyd
