// Neural frame interpolation networks on ncnn (Vulkan or the CPU), without Android: the app's JNI part
// (interpolator.cpp) and the desktop check (tools/neural-check) both run them.
//  - RIFE 4.x as rife-ncnn-vulkan runs it (MIT, see app/THIRD-PARTY-NOTICE.RIFE.txt): one network, the frames in
//    0..1 padded with zeros to a multiple of 128, the time as a plane.
//  - DAIN as dain-ncnn-vulkan runs it (MIT, see app/THIRD-PARTY-NOTICE.DAIN.txt): depth, flow and context networks
//    for both frames, then the interpolation network; the frames in BGR order, 0..1, the edges repeated to a
//    multiple of 32.
#pragma once

#include <functional>
#include <memory>

#include <net.h>

namespace mediadroid {

// Whether the device has a GPU ncnn can use; the Vulkan instance is made on the first call.
bool gpuReady();

// A network that takes two frames as RGBA bytes (w x h, rows as stored) and gives the in-between at [time] as RGB.
struct Interpolator {
    virtual ~Interpolator() = default;
    virtual int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                        unsigned char* rgb) = 0;
};

// Loads the structure (.param) and weights (.bin) named [name] into [net]: 0 when both loaded.
using Loader = std::function<int(ncnn::Net& net, const char* name)>;

// RIFE (its "flownet"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openRife(const Loader& load, bool gpu);

// DAIN (its "depthnet", "flownet", "ctxnet" and "interpolation"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openDain(const Loader& load, bool gpu);

}  // namespace mediadroid
