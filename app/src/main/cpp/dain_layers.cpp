#include "dain_layers.h"

#include <algorithm>
#include <cmath>

#include <layer_type.h>

#include "shaders.h"

namespace mediadroid {

namespace {

// Bilinear sample of [plane] (w x h) at (sx, sy); 0 where the 2 x 2 neighbourhood leaves the picture.
float sampleOrZero(const ncnn::Mat& plane, int w, int h, float sx, float sy) {
    const int x0 = static_cast<int>(std::floor(sx));
    const int y0 = static_cast<int>(std::floor(sy));
    if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) return 0.f;
    const float alpha = sx - x0;
    const float beta = sy - y0;
    const float top = plane.row(y0)[x0] * (1 - alpha) + plane.row(y0)[x0 + 1] * alpha;
    const float bottom = plane.row(y0 + 1)[x0] * (1 - alpha) + plane.row(y0 + 1)[x0 + 1] * alpha;
    return top * (1 - beta) + bottom * beta;
}

}  // namespace

// --- Correlation ---------------------------------------------------------------------------------------------------

constexpr int kReach = 4;  // displacements -4..4 in each direction
constexpr int kSide = 2 * kReach + 1;

CorrelationLayer::CorrelationLayer() {
    one_blob_only = false;
    support_vulkan = true;
}

int CorrelationLayer::create_pipeline(const ncnn::Option& opt) {
    if (!vkdev) return 0;
    // The features bordered by zeros on the GPU through ncnn's own Padding layer.
    border = ncnn::create_layer_vulkan(ncnn::LayerType::Padding);
    border->vkdev = vkdev;
    ncnn::ParamDict pd;
    pd.set(0, kReach);
    pd.set(1, kReach);
    pd.set(2, kReach);
    pd.set(3, kReach);
    pd.set(4, 0);
    pd.set(5, 0.f);
    border->load_param(pd);
    border->create_pipeline(opt);
    pipeline = mediadroid::pipeline(vkdev, "dain-correlation", opt);
    return pipeline ? 0 : -1;
}

int CorrelationLayer::destroy_pipeline(const ncnn::Option& opt) {
    if (border) {
        border->destroy_pipeline(opt);
        delete border;
        border = nullptr;
    }
    delete pipeline;
    pipeline = nullptr;
    return 0;
}

int CorrelationLayer::upload_model(ncnn::VkTransfer& cmd, const ncnn::Option& opt) {
    return border ? border->upload_model(cmd, opt) : 0;
}

int CorrelationLayer::forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                              const ncnn::Option& opt) const {
    const ncnn::Mat& a = bottoms[0];
    const ncnn::Mat& b = bottoms[1];
    const int w = a.w;
    const int h = a.h;
    const int channels = a.c;
    ncnn::Mat& top = tops[0];
    top.create(w, h, kSide * kSide);
    if (top.empty()) return -100;
    ncnn::Mat aBordered;
    ncnn::Mat bBordered;
    ncnn::copy_make_border(a, aBordered, kReach, kReach, kReach, kReach, ncnn::BORDER_CONSTANT, 0.f);
    ncnn::copy_make_border(b, bBordered, kReach, kReach, kReach, kReach, ncnn::BORDER_CONSTANT, 0.f);
    if (aBordered.empty() || bBordered.empty()) return -100;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int t = 0; t < kSide * kSide; t++) {
        const int tj = t / kSide;
        const int ti = t % kSide;
        ncnn::Mat out = top.channel(t);
        out.fill(0.f);
        for (int q = 0; q < channels; q++) {
            const ncnn::Mat am = aBordered.channel(q);
            const ncnn::Mat bm = bBordered.channel(q);
            for (int y = 0; y < h; y++) {
                const float* ar = am.row(y + kReach);
                const float* br = bm.row(y + tj);
                float* o = out.row(y);
                for (int x = 0; x < w; x++) o[x] += ar[x + kReach] * br[x + ti];
            }
        }
        for (int i = 0; i < w * h; i++) static_cast<float*>(out.data)[i] /= channels;
    }
    return 0;
}

int CorrelationLayer::forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops,
                              ncnn::VkCompute& cmd, const ncnn::Option& opt) const {
    const ncnn::VkMat& a = bottoms[0];
    const ncnn::VkMat& b = bottoms[1];
    ncnn::VkMat aBordered;
    ncnn::VkMat bBordered;
    ncnn::Option padOpt = opt;
    padOpt.blob_vkallocator = opt.workspace_vkallocator;
    border->forward(a, aBordered, cmd, padOpt);
    border->forward(b, bBordered, cmd, padOpt);
    if (aBordered.empty() || bBordered.empty()) return -100;
    // Padding hands its result back packed four channels a word where they divide by four; the shader reads one.
    for (ncnn::VkMat* bordered : {&aBordered, &bBordered}) {
        if (bordered->elempack != 1) {
            ncnn::VkMat unpacked;
            vkdev->convert_packing(*bordered, unpacked, 1, cmd, padOpt);
            *bordered = unpacked;
        }
    }
    if (aBordered.empty() || bBordered.empty()) return -100;

    ncnn::VkMat& top = tops[0];
    top.create(a.w, a.h, kSide * kSide, opt.use_fp16_storage ? 2u : 4u, 1, opt.blob_vkallocator);
    if (top.empty()) return -100;
    std::vector<ncnn::VkMat> bindings = {aBordered, bBordered, top};
    std::vector<ncnn::vk_constant_type> constants(8);
    constants[0].i = aBordered.w;
    constants[1].i = aBordered.h;
    constants[2].i = aBordered.c;
    constants[3].i = aBordered.cstep;
    constants[4].i = top.w;
    constants[5].i = top.h;
    constants[6].i = top.c;
    constants[7].i = top.cstep;
    cmd.record_pipeline(pipeline, bindings, constants, top);
    return 0;
}

// --- Optical-flow warp ---------------------------------------------------------------------------------------------

FlowWarpLayer::FlowWarpLayer() {
    one_blob_only = false;
    support_vulkan = true;
}

int FlowWarpLayer::create_pipeline(const ncnn::Option& opt) {
    if (!vkdev) return 0;
    pipeline = mediadroid::pipeline(vkdev, "dain-flow-warp", opt);
    return pipeline ? 0 : -1;
}

int FlowWarpLayer::destroy_pipeline(const ncnn::Option&) {
    delete pipeline;
    pipeline = nullptr;
    return 0;
}

int FlowWarpLayer::forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
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
        const float* fx = flow.channel(0);
        const float* fy = flow.channel(1);
        float* out = top.channel(q);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                const int i = y * w + x;
                out[i] = sampleOrZero(plane, w, h, x + fx[i], y + fy[i]);
            }
        }
    }
    return 0;
}

int FlowWarpLayer::forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops,
                           ncnn::VkCompute& cmd, const ncnn::Option& opt) const {
    const ncnn::VkMat& image = bottoms[0];
    ncnn::VkMat& top = tops[0];
    top.create(image.w, image.h, image.c, image.elemsize, image.elempack, opt.blob_vkallocator);
    if (top.empty()) return -100;
    std::vector<ncnn::VkMat> bindings = {image, bottoms[1], top};
    std::vector<ncnn::vk_constant_type> constants(4);
    constants[0].i = top.w;
    constants[1].i = top.h;
    constants[2].i = top.c;
    constants[3].i = top.cstep;
    cmd.record_pipeline(pipeline, bindings, constants, top);
    return 0;
}

// --- Depth-aware flow projection -----------------------------------------------------------------------------------

ProjectionLayer::ProjectionLayer() {
    one_blob_only = false;
    support_vulkan = true;
}

int ProjectionLayer::create_pipeline(const ncnn::Option& opt) {
    if (!vkdev) return 0;
    zero = mediadroid::pipeline(vkdev, "dain-projection-zero", opt);
    project = mediadroid::pipeline(vkdev, "dain-projection-project", opt);
    average = mediadroid::pipeline(vkdev, "dain-projection-average", opt);
    fillHole = mediadroid::pipeline(vkdev, "dain-projection-fill-hole", opt);
    return zero && project && average && fillHole ? 0 : -1;
}

int ProjectionLayer::destroy_pipeline(const ncnn::Option&) {
    for (ncnn::Pipeline** p : {&zero, &project, &average, &fillHole}) {
        delete *p;
        *p = nullptr;
    }
    return 0;
}

int ProjectionLayer::forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                             const ncnn::Option&) const {
    const ncnn::Mat& flow = bottoms[0];
    const ncnn::Mat& depth = bottoms[1];
    const int w = flow.w;
    const int h = flow.h;
    ncnn::Mat& top = tops[0];
    top.create(w, h, flow.c);
    if (top.empty()) return -100;
    top.fill(0.f);
    ncnn::Mat weight(w, h);
    weight.fill(0.f);
    float* sumX = top.channel(0);
    float* sumY = top.channel(1);
    float* weights = weight;
    const float* fx = flow.channel(0);
    const float* fy = flow.channel(1);
    const float* d = depth.channel(0);

    // Splat: every pixel's reversed flow, weighted by its depth, onto the four pixels around where it lands.
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            const int i = y * w + x;
            const int x0 = static_cast<int>(std::floor(x + fx[i]));
            const int y0 = static_cast<int>(std::floor(y + fy[i]));
            if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) continue;
            for (int at : {y0 * w + x0, y0 * w + x0 + 1, (y0 + 1) * w + x0, (y0 + 1) * w + x0 + 1}) {
                sumX[at] -= fx[i] * d[i];
                sumY[at] -= fy[i] * d[i];
                weights[at] += d[i];
            }
        }
    }
    for (int i = 0; i < w * h; i++) {
        if (weights[i] > 0.f) {
            sumX[i] /= weights[i];
            sumY[i] /= weights[i];
        }
    }

    // Holes: the mean of the nearest filled pixel left, right, up and down — as the Vulkan path does, which is the
    // path dain-ncnn-vulkan runs (its CPU path reads one pixel past the nearest).
    ncnn::Mat filledX = top.channel(0).clone();
    ncnn::Mat filledY = top.channel(1).clone();
    auto landed = [&](int x, int y) { return weights[y * w + x] > 0.f; };
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            if (landed(x, y)) continue;
            float mx = 0.f;
            float my = 0.f;
            int found = 0;
            auto take = [&](int px, int py) {
                mx += sumX[py * w + px];
                my += sumY[py * w + px];
                found++;
            };
            for (int px = x - 1; px >= 0; px--) if (landed(px, y)) { take(px, y); break; }
            for (int px = x + 1; px < w; px++) if (landed(px, y)) { take(px, y); break; }
            for (int py = y - 1; py >= 0; py--) if (landed(x, py)) { take(x, py); break; }
            for (int py = y + 1; py < h; py++) if (landed(x, py)) { take(x, py); break; }
            if (found > 0) {
                static_cast<float*>(filledX.data)[y * w + x] = mx / found;
                static_cast<float*>(filledY.data)[y * w + x] = my / found;
            }
        }
    }
    std::copy_n(static_cast<const float*>(filledX.data), w * h, sumX);
    std::copy_n(static_cast<const float*>(filledY.data), w * h, sumY);
    return 0;
}

int ProjectionLayer::forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops,
                             ncnn::VkCompute& cmd, const ncnn::Option& opt) const {
    const ncnn::VkMat& flow = bottoms[0];
    const ncnn::VkMat& depth = bottoms[1];
    const int w = flow.w;
    const int h = flow.h;
    ncnn::VkMat& top = tops[0];
    top.create(w, h, flow.c, flow.elemsize, flow.elempack, opt.blob_vkallocator);
    if (top.empty()) return -100;
    ncnn::VkMat sums(w, h, 1, 4u, 1, opt.workspace_vkallocator);
    ncnn::VkMat weights(w, h, 1, 4u, 1, opt.workspace_vkallocator);
    if (sums.empty() || weights.empty()) return -100;

    ncnn::VkMat line;
    line.w = w * h;
    line.h = 1;
    line.c = 1;
    {
        std::vector<ncnn::VkMat> bindings = {sums, weights};
        std::vector<ncnn::vk_constant_type> constants(1);
        constants[0].i = w * h;
        cmd.record_pipeline(zero, bindings, constants, line);
    }
    ncnn::VkMat picture;
    picture.w = w;
    picture.h = h;
    picture.c = 1;
    {
        std::vector<ncnn::VkMat> bindings = {flow, depth, sums, weights};
        std::vector<ncnn::vk_constant_type> constants(3);
        constants[0].i = w;
        constants[1].i = h;
        constants[2].i = flow.cstep;
        cmd.record_pipeline(project, bindings, constants, picture);
    }
    {
        std::vector<ncnn::VkMat> bindings = {sums, weights, top};
        std::vector<ncnn::vk_constant_type> constants(2);
        constants[0].i = w * h;
        constants[1].i = top.cstep;
        cmd.record_pipeline(average, bindings, constants, line);
    }
    {
        std::vector<ncnn::VkMat> bindings = {weights, top};
        std::vector<ncnn::vk_constant_type> constants(4);
        constants[0].i = top.w;
        constants[1].i = top.h;
        constants[2].i = top.c;
        constants[3].i = top.cstep;
        cmd.record_pipeline(fillHole, bindings, constants, picture);
    }
    return 0;
}

// --- Filter interpolation ------------------------------------------------------------------------------------------

FilterInterpolationLayer::FilterInterpolationLayer() {
    one_blob_only = false;
    support_vulkan = true;
    // The shaders read the 16 filter planes four to a word, and the image four channels to a word where it has
    // them: ncnn hands over packed blobs only to a layer that says it takes them.
    support_vulkan_packing = true;
}

int FilterInterpolationLayer::create_pipeline(const ncnn::Option& opt) {
    if (!vkdev) return 0;
    pipeline = mediadroid::pipeline(vkdev, "dain-filter-interpolation", opt);
    pipelinePack4 = mediadroid::pipeline(vkdev, "dain-filter-interpolation-pack4", opt);
    return pipeline && pipelinePack4 ? 0 : -1;
}

int FilterInterpolationLayer::destroy_pipeline(const ncnn::Option&) {
    delete pipeline;
    pipeline = nullptr;
    delete pipelinePack4;
    pipelinePack4 = nullptr;
    return 0;
}

int FilterInterpolationLayer::forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                                      const ncnn::Option& opt) const {
    const ncnn::Mat& image = bottoms[0];
    const ncnn::Mat& flow = bottoms[1];
    const ncnn::Mat& filter = bottoms[2];
    const int w = image.w;
    const int h = image.h;
    ncnn::Mat& top = tops[0];
    top.create(w, h, image.c);
    if (top.empty()) return -100;

    #pragma omp parallel for num_threads(opt.num_threads)
    for (int q = 0; q < image.c; q++) {
        const ncnn::Mat plane = image.channel(q);
        const float* fx = flow.channel(0);
        const float* fy = flow.channel(1);
        float* out = top.channel(q);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                const int i = y * w + x;
                const float sx = x + fx[i];
                const float sy = y + fy[i];
                if (sx < 0.f || sy < 0.f || sx >= w - 1 || sy >= h - 1 || std::fabs(fx[i]) > w / 2.f ||
                    std::fabs(fy[i]) > h / 2.f) {
                    out[i] = plane.row(y)[x];
                    continue;
                }
                const int x1 = static_cast<int>(std::floor(sx));
                const int y1 = static_cast<int>(std::floor(sy));
                const float alpha = sx - x1;
                const float beta = sy - y1;
                const int xs[4] = {std::max(x1 - 1, 0), std::min(x1, w - 1), std::min(x1 + 1, w - 1),
                                   std::min(x1 + 2, w - 1)};
                const int ys[4] = {std::max(y1 - 1, 0), std::min(y1, h - 1), std::min(y1 + 1, h - 1),
                                   std::min(y1 + 2, h - 1)};
                // Each 2 x 2 corner of the 4 x 4 neighbourhood through its filter weights (plane 4 * row + col).
                float corner[4];
                for (int k = 0; k < 4; k++) {
                    const int r = (k / 2) * 2;
                    const int c = (k % 2) * 2;
                    float sum = 0.f;
                    for (int dr = 0; dr < 2; dr++) {
                        for (int dc = 0; dc < 2; dc++) {
                            sum += plane.row(ys[r + dr])[xs[c + dc]] * filter.channel(4 * (r + dr) + c + dc)[i];
                        }
                    }
                    corner[k] = sum;
                }
                const float topRow = corner[0] * (1 - alpha) + corner[1] * alpha;
                const float bottomRow = corner[2] * (1 - alpha) + corner[3] * alpha;
                out[i] = topRow * (1 - beta) + bottomRow * beta;
            }
        }
    }
    return 0;
}

int FilterInterpolationLayer::forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops,
                                      ncnn::VkCompute& cmd, const ncnn::Option& opt) const {
    const ncnn::VkMat& image = bottoms[0];
    const ncnn::VkMat& filter = bottoms[2];
    ncnn::VkMat& top = tops[0];
    top.create(image.w, image.h, image.c, image.elemsize, image.elempack, opt.blob_vkallocator);
    if (top.empty()) return -100;
    std::vector<ncnn::VkMat> bindings = {image, bottoms[1], filter, top};
    std::vector<ncnn::vk_constant_type> constants(5);
    constants[0].i = top.w;
    constants[1].i = top.h;
    constants[2].i = top.c;
    constants[3].i = top.cstep;
    constants[4].i = filter.cstep;
    cmd.record_pipeline(image.elempack == 4 ? pipelinePack4 : pipeline, bindings, constants, top);
    return 0;
}

ncnn::Layer* createCorrelationLayer(void*) { return new CorrelationLayer(); }
ncnn::Layer* createFlowWarpLayer(void*) { return new FlowWarpLayer(); }
ncnn::Layer* createProjectionLayer(void*) { return new ProjectionLayer(); }
ncnn::Layer* createFilterInterpolationLayer(void*) { return new FilterInterpolationLayer(); }

}  // namespace mediadroid
