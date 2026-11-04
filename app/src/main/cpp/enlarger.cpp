// Neural upscalers on ncnn (Vulkan or the CPU), twice the picture's size, as nihui's ncnn ports run them (MIT; see
// app/THIRD-PARTY-NOTICE.waifu2x.txt and .Real-CUGAN.txt): the frame in RGB 0..1, its edges extended by [padding] on
// every side and to an even size; the network trims what it added, so the output's top left is the picture twice
// as large. waifu2x repeats the edge pixels, Real-CUGAN mirrors them.
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

    int enlarge(const unsigned char* rgba, int w, int h, unsigned char* rgb) override {
        ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, ncnn::Mat::PIXEL_RGBA2RGB, w, h);
        const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        picture.substract_mean_normalize(nullptr, toUnit);
        ncnn::Mat padded;
        ncnn::copy_make_border(picture, padded, padding, padding + h % 2, padding, padding + w % 2, border, 0.f);
        ncnn::Extractor ex = net.create_extractor();
        ex.input(input, padded);
        ncnn::Mat result;
        if (ex.extract(output, result) != 0 || result.w < 2 * w || result.h < 2 * h) return -2;
        ncnn::Mat enlarged;
        ncnn::copy_cut_border(result, enlarged, 0, result.h - 2 * h, 0, result.w - 2 * w);
        // Back to bytes, rounded: to_pixels cuts off, so half a step is added first, as the ports do.
        const float half[3] = {-0.5f / 255.f, -0.5f / 255.f, -0.5f / 255.f};
        const float toBytes[3] = {255.f, 255.f, 255.f};
        enlarged.substract_mean_normalize(half, toBytes);
        enlarged.to_pixels(rgb, ncnn::Mat::PIXEL_RGB);
        return 0;
    }
};

std::unique_ptr<Enlarger> open(const Loader& load, bool gpu, const char* name, const char* input, const char* output,
                               int border) {
    auto it = std::make_unique<NcnnEnlarger>();
    configure(it->net, gpu && gpuReady());
    it->input = input;
    it->output = output;
    it->border = border;
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

}  // namespace mediadroid
