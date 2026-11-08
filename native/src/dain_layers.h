// DAIN's own layers for ncnn (after dain-ncnn-vulkan's, MIT, see app/THIRD-PARTY-NOTICE.DAIN.txt), on the CPU and
// on Vulkan (shaders: glsl/ncnn/dain-*.comp).
#pragma once

#include <vector>

#include <layer.h>
#include <pipeline.h>

namespace mediadroyd {

// dain.Correlation (PWC-Net's cost volume): for each of the 9 x 9 displacements up to 4 pixels, the first feature
// blob times the second there, averaged over the channels — 81 planes.
class CorrelationLayer : public ncnn::Layer {
public:
    CorrelationLayer();
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int upload_model(ncnn::VkTransfer& cmd, const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Layer* border = nullptr;
    ncnn::Pipeline* pipeline = nullptr;
};

// dain.OpticalFlowWarp: a blob sampled where a flow points, bilinear; samples leaving the picture are 0.
class FlowWarpLayer : public ncnn::Layer {
public:
    FlowWarpLayer();
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Pipeline* pipeline = nullptr;
};

// dain.DepthFlowProjection: the flow from a frame to the in-between, each pixel's flow reversed and splatted where it
// lands, nearer pixels (by the depth) weighing more; holes take the mean of their nearest filled neighbours.
class ProjectionLayer : public ncnn::Layer {
public:
    ProjectionLayer();
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Pipeline* zero = nullptr;
    ncnn::Pipeline* project = nullptr;
    ncnn::Pipeline* average = nullptr;
    ncnn::Pipeline* fillHole = nullptr;
};

// dain.FilterInterpolation: a blob sampled where a flow points through a per-pixel 4 x 4 filter (16 planes).
class FilterInterpolationLayer : public ncnn::Layer {
public:
    FilterInterpolationLayer();
    int create_pipeline(const ncnn::Option& opt) override;
    int destroy_pipeline(const ncnn::Option& opt) override;
    int forward(const std::vector<ncnn::Mat>& bottoms, std::vector<ncnn::Mat>& tops,
                const ncnn::Option& opt) const override;
    int forward(const std::vector<ncnn::VkMat>& bottoms, std::vector<ncnn::VkMat>& tops, ncnn::VkCompute& cmd,
                const ncnn::Option& opt) const override;

private:
    ncnn::Pipeline* pipeline = nullptr;
    ncnn::Pipeline* pipelinePack4 = nullptr;
};

ncnn::Layer* createCorrelationLayer(void* userdata);
ncnn::Layer* createFlowWarpLayer(void* userdata);
ncnn::Layer* createProjectionLayer(void* userdata);
ncnn::Layer* createFilterInterpolationLayer(void* userdata);

}  // namespace mediadroyd
