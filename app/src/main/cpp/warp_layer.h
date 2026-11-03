// RIFE's warp as an ncnn layer (after rife-ncnn-vulkan's rife.Warp, MIT, see app/THIRD-PARTY-NOTICE.RIFE.txt).
#pragma once

#include <string>
#include <vector>

#include <layer.h>
#include <pipeline.h>

namespace mediadroid {

// The warp's compute shader source (glsl/ncnn/warp.comp, handed over from the app before a network loads).
extern std::string warpShader;

// Samples the first blob, an image of any channels, where the second blob's flow (two planes, across and down, in
// pixels) points from each pixel: bilinear, coordinates clamped to the picture.
class WarpLayer : public ncnn::Layer {
public:
    WarpLayer();
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Pipeline* pipeline = nullptr;
};

ncnn::Layer* createWarpLayer(void* userdata);

}  // namespace mediadroid
