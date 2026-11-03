#include "warp_layer.h"

#include <algorithm>
#include <cmath>

#include <gpu.h>

namespace mediadroid {

std::string warpShader;

WarpLayer::WarpLayer() {
    one_blob_only = false;
    support_inplace = false;
    support_vulkan = true;
}

int WarpLayer::create_pipeline(const ncnn::Option& opt) {
    if (!vkdev) return 0;
    std::vector<uint32_t> spirv;
    if (ncnn::compile_spirv_module(warpShader.c_str(), opt, spirv) != 0) return -1;
    pipeline = new ncnn::Pipeline(vkdev);
    pipeline->set_optimal_local_size_xyz();
    return pipeline->create(spirv.data(), spirv.size() * 4, std::vector<ncnn::vk_specialization_type>());
}

int WarpLayer::destroy_pipeline(const ncnn::Option&) {
    delete pipeline;
    pipeline = nullptr;
    return 0;
}

int WarpLayer::forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                       const ncnn::Option& opt) const {
    const ncnn::Mat& image = bottoms[0];
    const ncnn::Mat& flow = bottoms[1];
    const int w = image.w;
    const int h = image.h;
    ncnn::Mat& top = tops[0];
    top.create(w, h, image.c);
    if (top.empty()) return -100;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int q = 0; q < image.c; q++) {
        const ncnn::Mat plane = image.channel(q);
        const float* flowX = flow.channel(0);
        const float* flowY = flow.channel(1);
        float* out = top.channel(q);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                const int i = y * w + x;
                const float sampleX = x + flowX[i];
                const float sampleY = y + flowY[i];
                // The weights from the corner after clamping, exactly as the reference computes them: the
                // network was converted against this sampling.
                const int x0 = std::clamp(static_cast<int>(std::floor(sampleX)), 0, w - 1);
                const int y0 = std::clamp(static_cast<int>(std::floor(sampleY)), 0, h - 1);
                const int x1 = std::clamp(static_cast<int>(std::floor(sampleX)) + 1, 0, w - 1);
                const int y1 = std::clamp(static_cast<int>(std::floor(sampleY)) + 1, 0, h - 1);
                const float alpha = sampleX - x0;
                const float beta = sampleY - y0;
                const float top0 = plane.row(y0)[x0] * (1 - alpha) + plane.row(y0)[x1] * alpha;
                const float bottom0 = plane.row(y1)[x0] * (1 - alpha) + plane.row(y1)[x1] * alpha;
                out[i] = top0 * (1 - beta) + bottom0 * beta;
            }
        }
    }
    return 0;
}

int WarpLayer::forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops,
                       ncnn::VkCompute& cmd, const ncnn::Option& opt) const {
    const ncnn::VkMat& image = bottoms[0];
    const ncnn::VkMat& flow = bottoms[1];
    ncnn::VkMat& top = tops[0];
    top.create(image.w, image.h, image.c, image.elemsize, image.elempack, opt.blob_vkallocator);
    if (top.empty()) return -100;

    std::vector<ncnn::VkMat> bindings = {image, flow, top};
    std::vector<ncnn::vk_constant_type> constants(4);
    constants[0].i = top.w;
    constants[1].i = top.h;
    constants[2].i = top.c;
    constants[3].i = top.cstep;
    cmd.record_pipeline(pipeline, bindings, constants, top);
    return 0;
}

ncnn::Layer* createWarpLayer(void*) {
    return new WarpLayer();
}

}  // namespace mediadroid
