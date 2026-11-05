// Neural upscalers on ncnn (Vulkan or the CPU), as nihui's ncnn ports run them (MIT; see
// app/THIRD-PARTY-NOTICE.waifu2x.txt, .Real-CUGAN.txt and .RealSR.txt) — twice the size, RealSR four times: the frame
// in RGB 0..1, its edges extended by [padding] on every side and to an even size; the network trims what it added, so
// the output's top left is the picture twice as large. waifu2x repeats the edge pixels, Real-CUGAN mirrors them.
#include <algorithm>

#include "networks.h"

namespace mediadroid {

namespace {

struct NcnnEnlarger : Enlarger {
    ncnn::Net net;
    const char* input = "in0";
    const char* output = "out0";
    int padding = 18;
    int border = ncnn::BORDER_REPLICATE;
    // Whether the network keeps what was added around (RealSR): the picture then starts padding x scale in.
    bool keepsPadding = false;

    int enlarge(const unsigned char* rgba, int w, int h, unsigned char* rgb) override {
        ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
        const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        picture.substract_mean_normalize(nullptr, toUnit);
        ncnn::Mat padded;
        const int evenW = scale == 2 ? w % 2 : 0;
        const int evenH = scale == 2 ? h % 2 : 0;
        ncnn::copy_make_border(picture, padded, padding, padding + evenH, padding, padding + evenW, border, 0.f);
        ncnn::Extractor ex = net.create_extractor();
        ex.input(input, padded);
        ncnn::Mat result;
        const int at = keepsPadding ? padding * scale : 0;
        if (ex.extract(output, result) != 0 || result.w < at + scale * w || result.h < at + scale * h) return -2;
        ncnn::Mat enlarged;
        ncnn::copy_cut_border(result, enlarged, at, result.h - at - scale * h, at, result.w - at - scale * w);
        // Back to bytes, rounded: to_pixels cuts off, so half a step is added first, as the ports do.
        const float half[3] = {-0.5f / 255.f, -0.5f / 255.f, -0.5f / 255.f};
        const float toBytes[3] = {255.f, 255.f, 255.f};
        enlarged.substract_mean_normalize(half, toBytes);
        enlarged.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
        return 0;
    }
};

std::unique_ptr<Enlarger> open(const Loader& load, bool gpu, const char* name, const char* input, const char* output,
                               int border, int scale = 2, int padding = 18, bool keepsPadding = false) {
    auto it = std::make_unique<NcnnEnlarger>();
    configure(it->net, gpu && gpuReady());
    it->input = input;
    it->output = output;
    it->border = border;
    it->scale = scale;
    it->padding = padding;
    it->keepsPadding = keepsPadding;
    if (load(it->net, name) != 0) return nullptr;
    return it;
}

}  // namespace

std::unique_ptr<Enlarger> openWaifu2x(const Loader& load, bool gpu) {
    // CUnet, light denoising, twice the size (models-cunet/noise1_scale2.0x_model): 18 pixels around.
    return open(load, gpu, "noise1_scale2.0x_model", "Input1", "Eltwise4", ncnn::BORDER_REPLICATE);
}

std::unique_ptr<Enlarger> openRealCugan(const Loader& load, bool gpu) {
    // The SE model, conservative, twice the size (models-se/up2x-conservative): 18 pixels around, mirrored.
    return open(load, gpu, "up2x-conservative", "in0", "out0", ncnn::BORDER_REFLECT);
}

std::unique_ptr<Enlarger> openRealSr(const Loader& load, bool gpu) {
    // DF2K_JPEG, four times the size (models-DF2K_JPEG/x4): 10 pixels around, mirrored, kept in its output.
    return open(load, gpu, "x4", "data", "output", ncnn::BORDER_REFLECT, 4, 10, true);
}

}  // namespace mediadroid
