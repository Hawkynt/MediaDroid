// RIFE's warp as an ncnn layer (after rife-ncnn-vulkan's rife.Warp, MIT, see app/THIRD-PARTY-NOTICE.RIFE.txt).
#pragma once

#include <vector>

#include <layer.h>
#include <pipeline.h>

namespace mediadroyd {

// Samples the first blob, an image of any channels, where the second blob's flow (two planes, across and down, in
// pixels) points from each pixel: bilinear, coordinates clamped to the picture (shader: glsl/ncnn/warp.comp).
class WarpLayer : public ncnn::Layer {
public:
    // [flowFirst]: the flow is the first blob and the image the second, as IFRNet's ifrnet.Warp takes them.
    explicit WarpLayer(bool flowFirst = false);
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    bool flowFirst;
    ncnn::Pipeline* pipeline = nullptr;
};

ncnn::Layer* createWarpLayer(void* userdata);
ncnn::Layer* createFlowFirstWarpLayer(void* userdata);

}  // namespace mediadroyd
