// Neural frame interpolation networks on ncnn (Vulkan or the CPU), without Android: the app's JNI part
// (interpolator.cpp) and the desktop check (tools/neural-check) both run them.
//  - RIFE 4.x as rife-ncnn-vulkan runs it (MIT, see app/THIRD-PARTY-NOTICE.RIFE.txt): one network, the frames in
//    0..1 padded with zeros to a multiple of 128, the time as a plane.
//  - DAIN as dain-ncnn-vulkan runs it (MIT, see app/THIRD-PARTY-NOTICE.DAIN.txt): depth, flow and context networks
//    for both frames, then the interpolation network; the frames in BGR order, 0..1, the edges repeated to a
//    multiple of 32.
//  - XVFI (research and education only, used with its authors' permission; see app/THIRD-PARTY-NOTICE.XVFI.txt):
//    convolution blocks joined by warping on the CPU (xvfi.cpp).
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
    // The frames the next between() gets, as ids (0: not known): a frame met again — the later of one pair is the
    // earlier of the next — keeps what was worked out from it alone (features, depth) instead of working it out again.
    long long earlierId = 0;
    long long laterId = 0;
    virtual int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                        unsigned char* rgb) = 0;
};

// Loads the structure (.param) and weights (.bin) named [name] into [net]: 0 when both loaded.
using Loader = std::function<int(ncnn::Net& net, const char* name)>;

// RIFE (its "flownet"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openRife(const Loader& load, bool gpu);

// DAIN (its "depthnet", "flownet", "ctxnet" and "interpolation"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openDain(const Loader& load, bool gpu);

// XVFI (its blocks "extract", "context", "bottom", "flow1", "flow2", "flow3", "refine"; see xvfi.cpp), on the GPU or
// the CPU; null where it does not load.
std::unique_ptr<Interpolator> openXvfi(const Loader& load, bool gpu);

// [net]'s options for the GPU or the CPU: on the GPU half floats to store, full floats to compute with.
void configure(ncnn::Net& net, bool vulkan);

}  // namespace mediadroid
