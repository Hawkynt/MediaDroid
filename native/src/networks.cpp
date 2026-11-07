#include "networks.h"

#include <mutex>
#include <type_traits>
#include <string>

#include <gpu.h>

#include "dain_layers.h"
#include "warp_layer.h"

namespace mediadroid {

namespace {

std::once_flag gpuOnce;

int roundUp(int value, int step) {
    return (value + step - 1) / step * step;
}


// [rgba] (w x h, rows as stored) as a three-plane picture in 0..1 in the [order] ncnn names, padded at the right and
// bottom to [paddedW] x [paddedH] by [border] (ncnn::BORDER_*).
ncnn::Mat planes(const unsigned char* rgba, int order, int w, int h, int paddedW, int paddedH, int border) {
    ncnn::Mat picture = ncnn::Mat::from_pixels(rgba, order, w, h);
    const float toUnit[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    picture.substract_mean_normalize(nullptr, toUnit);
    ncnn::Mat padded;
    ncnn::copy_make_border(picture, padded, 0, paddedH - h, 0, paddedW - w, border, 0.f);
    return padded;
}

// [result]'s top left w x h back to bytes into [rgb], rounded (to_pixels cuts off, so half a step goes in first).
void bytes(const ncnn::Mat& result, int order, int w, int h, unsigned char* rgb) {
    ncnn::Mat picture;
    ncnn::copy_cut_border(result, picture, 0, result.h - h, 0, result.w - w);
    const float half[3] = {-0.5f / 255.f, -0.5f / 255.f, -0.5f / 255.f};
    const float toBytes[3] = {255.f, 255.f, 255.f};
    picture.substract_mean_normalize(half, toBytes);
    picture.to_pixels(rgb, order);
}

struct Rife : Interpolator {
    ncnn::Net net;
    int padding = 128;

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                unsigned char* rgb) override {
        const int pw = roundUp(w, padding);
        const int ph = roundUp(h, padding);
        ncnn::Mat in0 = planes(first, ncnn::Mat::PIXEL_RGBA2RGB, w, h, pw, ph, ncnn::BORDER_CONSTANT);
        ncnn::Mat in1 = planes(second, ncnn::Mat::PIXEL_RGBA2RGB, w, h, pw, ph, ncnn::BORDER_CONSTANT);
        ncnn::Mat timestep(pw, ph, 1);
        timestep.fill(time);
        ncnn::Extractor ex = net.create_extractor();
        ex.input("in0", in0);
        ex.input("in1", in1);
        ex.input("in2", timestep);
        ncnn::Mat result;
        if (ex.extract("out0", result) != 0) return -2;
        bytes(result, ncnn::Mat::PIXEL_RGB, w, h, rgb);
        return 0;
    }

    // The graph read up to its last block's flow (blob "714": the flow so far plus the last block's change) and its
    // mask logits ("713"), before the warp and blend that make "out0" from them.
    int flow(const unsigned char* first, const unsigned char* second, int w, int h, float time, float* out) override {
        const int pw = roundUp(w, padding);
        const int ph = roundUp(h, padding);
        ncnn::Mat in0 = planes(first, ncnn::Mat::PIXEL_RGBA2RGB, w, h, pw, ph, ncnn::BORDER_CONSTANT);
        ncnn::Mat in1 = planes(second, ncnn::Mat::PIXEL_RGBA2RGB, w, h, pw, ph, ncnn::BORDER_CONSTANT);
        ncnn::Mat timestep(pw, ph, 1);
        timestep.fill(time);
        ncnn::Extractor ex = net.create_extractor();
        ex.input("in0", in0);
        ex.input("in1", in1);
        ex.input("in2", timestep);
        ncnn::Mat flows, mask;
        if (ex.extract("714", flows) != 0 || ex.extract("713", mask) != 0) return -2;
        if (flows.c < 4 || mask.c < 1 || flows.w != pw || flows.h != ph || mask.w != pw || mask.h != ph) return -2;
        float* masks = out + static_cast<size_t>(w) * h * 4;
        for (int y = 0; y < h; y++) {
            const float* fx0 = flows.channel(0).row(y);
            const float* fy0 = flows.channel(1).row(y);
            const float* fx1 = flows.channel(2).row(y);
            const float* fy1 = flows.channel(3).row(y);
            const float* m = mask.channel(0).row(y);
            float* row = out + static_cast<size_t>(y) * w * 4;
            for (int x = 0; x < w; x++) {
                row[x * 4] = fx0[x];
                row[x * 4 + 1] = fy0[x];
                row[x * 4 + 2] = fx1[x];
                row[x * 4 + 3] = fy1[x];
                masks[static_cast<size_t>(y) * w + x] = m[x];
            }
        }
        return 0;
    }
};

// Feeds and reads blobs of either kind: CPU Mats, or GPU VkMats recorded into [cmd].
void put(ncnn::Extractor& ex, const char* name, const ncnn::Mat& blob) { ex.input(name, blob); }
void put(ncnn::Extractor& ex, const char* name, const ncnn::VkMat& blob) { ex.input(name, blob); }
int take(ncnn::Extractor& ex, const char* name, ncnn::Mat& blob, ncnn::VkCompute*) { return ex.extract(name, blob); }
int take(ncnn::Extractor& ex, const char* name, ncnn::VkMat& blob, ncnn::VkCompute* cmd) {
    // Each network is submitted on its own: one command buffer for all of DAIN runs so long that a phone's GPU gives
    // up on it (the device is lost, for the rest of the process).
    if (ex.extract(name, blob, *cmd) != 0) return -1;
    const int submitted = cmd->submit_and_wait();
    cmd->reset();
    return submitted;
}

struct Dain : Interpolator {
    ncnn::Net depthnet;
    ncnn::Net flownet;
    ncnn::Net ctxnet;
    ncnn::Net interpolation;
    bool vulkan = false;

    // An extractor of [net] that keeps its blobs in [blobs] (on the GPU, so they outlive it for the next network).
    static ncnn::Extractor extractor(ncnn::Net& net, ncnn::VkAllocator* blobs, ncnn::VkAllocator* staging) {
        ncnn::Extractor ex = net.create_extractor();
        if (blobs) {
            ex.set_blob_vkallocator(blobs);
            ex.set_workspace_vkallocator(blobs);
            ex.set_staging_vkallocator(staging);
        }
        return ex;
    }

    // On the CPU, a frame and what is worked out from it alone (its depth and context), kept for the next pair. On
    // the GPU nothing is kept: ncnn hands the memory of a call's blobs to the next call.
    struct Frame {
        long long id = 0;
        ncnn::Mat depth, ctx;
    };
    Frame kept[2];

    // The four networks in turn, as dain-ncnn-vulkan's process_notile runs them; depth and context of a frame
    // already kept (on the CPU, by its id) are not worked out again.
    template <typename M>
    int run(const M& in0, const M& in1, float time, M& out, ncnn::VkAllocator* blobs, ncnn::VkAllocator* staging,
            ncnn::VkCompute* cmd) {
        M depth0, depth1, flow0, flow1, ctx0, ctx1;
        int failed = 0;
        auto one = [&](ncnn::Net& net, const M& in, const char* outName, M& result) {
            ncnn::Extractor ex = extractor(net, blobs, staging);
            put(ex, "input", in);
            failed |= take(ex, outName, result, cmd);
        };
        auto pair = [&](const M& first, const M& second, M& result) {
            ncnn::Extractor ex = extractor(flownet, blobs, staging);
            put(ex, "input0", first);
            put(ex, "input1", second);
            failed |= take(ex, "flow", result, cmd);
        };
        auto perFrame = [&](const M& in, long long id, const Frame* other, M& depth, M& ctx) {
            if constexpr (std::is_same_v<M, ncnn::Mat>) {
                for (Frame& f : kept) {
                    if (id != 0 && f.id == id && f.depth.w == in.w && f.depth.h == in.h) {
                        depth = f.depth;
                        ctx = f.ctx;
                        return &f;
                    }
                }
                one(depthnet, in, "depth", depth);
                one(ctxnet, in, "ctx", ctx);
                Frame& slot = &kept[0] == other ? kept[1] : kept[0];
                slot = Frame{id, depth, ctx};
                return &slot;
            } else {
                one(depthnet, in, "depth", depth);
                one(ctxnet, in, "ctx", ctx);
                return static_cast<Frame*>(nullptr);
            }
        };
        const Frame* first = perFrame(in0, earlierId, nullptr, depth0, ctx0);
        perFrame(in1, laterId, first, depth1, ctx1);
        pair(in0, in1, flow0);
        pair(in1, in0, flow1);
        if (failed) return -2;
        ncnn::Mat flow0Weight(1);
        ncnn::Mat flow1Weight(1);
        flow0Weight[0] = time;
        flow1Weight[0] = 1.f - time;
        ncnn::Extractor ex = extractor(interpolation, blobs, staging);
        put(ex, "input0", in0);
        put(ex, "input1", in1);
        put(ex, "depth0", depth0);
        put(ex, "depth1", depth1);
        put(ex, "flow0", flow0);
        put(ex, "flow1", flow1);
        ex.input("flow0_w", flow0Weight);
        ex.input("flow1_w", flow1Weight);
        put(ex, "ctx0", ctx0);
        put(ex, "ctx1", ctx1);
        return take(ex, "output_rectified", out, cmd) != 0 ? -2 : 0;
    }

    int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                unsigned char* rgb) override {
        const int pw = roundUp(w, 32);
        const int ph = roundUp(h, 32);
        ncnn::Mat in0 = planes(first, ncnn::Mat::PIXEL_RGBA2BGR, w, h, pw, ph, ncnn::BORDER_REPLICATE);
        ncnn::Mat in1 = planes(second, ncnn::Mat::PIXEL_RGBA2BGR, w, h, pw, ph, ncnn::BORDER_REPLICATE);
        ncnn::Mat result;
        if (!vulkan) {
            const int ran = run(in0, in1, time, result, nullptr, nullptr, nullptr);
            if (ran != 0) return ran;
        } else {
            ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device();
            ncnn::VkAllocator* blobs = vkdev->acquire_blob_allocator();
            ncnn::VkAllocator* staging = vkdev->acquire_staging_allocator();
            int ran = 0;
            {
                ncnn::Option opt = depthnet.opt;
                opt.blob_vkallocator = blobs;
                opt.workspace_vkallocator = blobs;
                opt.staging_vkallocator = staging;
                ncnn::VkCompute cmd(vkdev);
                ncnn::VkMat gpu0, gpu1, out;
                // Upload and download convert, as the networks keep their blobs: half floats, packed by four where
                // the channels allow (a clone would copy the words as they are).
                cmd.record_upload(in0, gpu0, opt);
                cmd.record_upload(in1, gpu1, opt);
                ran = run(gpu0, gpu1, time, out, blobs, staging, &cmd);
                if (ran == 0) {
                    cmd.record_download(out, result, opt);
                    ran = cmd.submit_and_wait();
                }
            }
            vkdev->reclaim_blob_allocator(blobs);
            vkdev->reclaim_staging_allocator(staging);
            if (ran != 0) return -2;
        }
        bytes(result, ncnn::Mat::PIXEL_BGR2RGB, w, h, rgb);
        return 0;
    }
};

}  // namespace

// In half floats the sampling positions of a large frame lose too much: on the GPU they only store.
void configure(ncnn::Net& net, bool vulkan) {
    ncnn::Option& opt = net.opt;
    opt.use_vulkan_compute = vulkan;
    opt.use_fp16_packed = vulkan;
    opt.use_fp16_storage = vulkan;
    opt.use_fp16_arithmetic = false;
    opt.use_bf16_storage = false;
    opt.num_threads = vulkan ? 1 : 4;
    if (vulkan) net.set_vulkan_device(ncnn::get_gpu_device());
}

Loader fileLoader(const std::string& base) {
    return [base](ncnn::Net& net, const char* name) {
        const std::string path = base + "/" + name;
        return net.load_param((path + ".param").c_str()) != 0 || net.load_model((path + ".bin").c_str()) != 0;
    };
}

bool gpuReady() {
    std::call_once(gpuOnce, [] { ncnn::create_gpu_instance(); });
    return ncnn::get_gpu_count() > 0;
}

std::unique_ptr<Interpolator> openRife(const Loader& load, bool gpu) {
    auto rife = std::make_unique<Rife>();
    configure(rife->net, gpu && gpuReady());
    rife->net.register_custom_layer("rife.Warp", createWarpLayer);
    if (load(rife->net, "flownet") != 0) return nullptr;
    return rife;
}

std::unique_ptr<Interpolator> openDain(const Loader& load, bool gpu) {
    auto dain = std::make_unique<Dain>();
    dain->vulkan = gpu && gpuReady();
    dain->flownet.register_custom_layer("dain.Correlation", createCorrelationLayer);
    dain->flownet.register_custom_layer("dain.OpticalFlowWarp", createFlowWarpLayer);
    dain->interpolation.register_custom_layer("dain.DepthFlowProjection", createProjectionLayer);
    dain->interpolation.register_custom_layer("dain.FilterInterpolation", createFilterInterpolationLayer);
    ncnn::Net* nets[] = {&dain->depthnet, &dain->flownet, &dain->ctxnet, &dain->interpolation};
    const char* names[] = {"depthnet", "flownet", "ctxnet", "interpolation"};
    for (int i = 0; i < 4; i++) {
        configure(*nets[i], dain->vulkan);
        // On the CPU ncnn's Winograd and im2col (sgemm) convolutions prepare copies of DAIN's weights and work buffers
        // that took gigabytes (a phone's limit ended the app); the direct ones take a few hundred megabytes, and
        // are as fast here.
        if (!dain->vulkan) {
            ncnn::Option& opt = nets[i]->opt;
            opt.use_winograd_convolution = false;
            opt.use_winograd23_convolution = false;
            opt.use_winograd43_convolution = false;
            opt.use_winograd63_convolution = false;
            opt.use_sgemm_convolution = false;
        }
        if (load(*nets[i], names[i]) != 0) return nullptr;
    }
    return dain;
}

}  // namespace mediadroid
