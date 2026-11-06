// Neural frame interpolation networks on ncnn (Vulkan or the CPU), without Android or JNI: the JNI surface
// (native/jni) and the desktop check (tools/neural-check) both run them.
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
#include <string>

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
    // The frames before the earlier one and after the later one, for a network that looks at four (FLAVR); null
    // where not known — the pair's own frames stand in.
    const unsigned char* before = nullptr;
    const unsigned char* after = nullptr;
    virtual int between(const unsigned char* first, const unsigned char* second, int w, int h, float time,
                        unsigned char* rgb) = 0;
};

// Loads the structure (.param) and weights (.bin) named [name] into [net]: 0 when both loaded.
using Loader = std::function<int(ncnn::Net& net, const char* name)>;

// A loader reading [base]/<name>.param and [base]/<name>.bin from files.
Loader fileLoader(const std::string& base);

// RIFE (its "flownet"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openRife(const Loader& load, bool gpu);

// DAIN (its "depthnet", "flownet", "ctxnet" and "interpolation"), on the GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openDain(const Loader& load, bool gpu);

// XVFI (its blocks "extract", "context", "bottom", "flow1", "flow2", "flow3", "refine"; see xvfi.cpp), on the GPU or
// the CPU; null where it does not load.
std::unique_ptr<Interpolator> openXvfi(const Loader& load, bool gpu);

// A network that enlarges an RGBA picture (w x h, rows as stored) to [scale] times its size as RGB bytes: waifu2x,
// Real-CUGAN, RealSR or Real-ESRGAN (see enlarger.cpp).
struct Enlarger {
    virtual ~Enlarger() = default;
    // How much larger it makes a picture.
    int scale = 2;
    // The picture through the network whole ([tile] 0) or in tiles of about [tile] pixels a side.
    virtual int enlarge(const unsigned char* rgba, int w, int h, unsigned char* rgb, int tile) = 0;
};

// What ncnn lets the GPU's networks take, in MB (its heap budget); 0 where there is no GPU.
int gpuHeapBudget();

// waifu2x's CUnet [model] (waifu2x-ncnn-vulkan's models-cunet: "noise1_scale2.0x_model" and the other noise levels,
// twice the size), on the GPU or the CPU, once or eight ways ([tta]); null where it does not load.
std::unique_ptr<Enlarger> openWaifu2x(const Loader& load, bool gpu, const char* model = "noise1_scale2.0x_model",
                                      bool tta = false);

// Real-CUGAN's SE or pro [model] (realcugan-ncnn-vulkan's "up2x-conservative" and the others) at [scale] 2, 3 or 4,
// on the GPU or the CPU, once or eight ways; null where it does not load.
std::unique_ptr<Enlarger> openRealCugan(const Loader& load, bool gpu, const char* model = "up2x-conservative",
                                        int scale = 2, bool tta = false);

// RealSR's DF2K_JPEG model (its "x4"), four times the size, on the GPU or the CPU; null where it does not load.
std::unique_ptr<Enlarger> openRealSr(const Loader& load, bool gpu, bool tta = false);

// Real-ESRGAN's [model] as Real-ESRGAN-ncnn-vulkan ships it (its compact video model "realesr-animevideov3-x4"), four
// times the size, on the GPU or the CPU; null where it does not load.
std::unique_ptr<Enlarger> openRealEsrgan(const Loader& load, bool gpu, const char* model = "realesr-animevideov3-x4",
                                         bool tta = false);

// CAIN (its "cain") and IFRNet (its "ifrnet"), two frames to the in-between, on the GPU or the CPU; null where they
// do not load.
std::unique_ptr<Interpolator> openCain(const Loader& load, bool gpu);
std::unique_ptr<Interpolator> openIfrnet(const Loader& load, bool gpu);

// FLAVR (its "body", the 3D part written as 2D layers over the four frames, and its "head"; see flavr.cpp), on the
// GPU or the CPU; null where it does not load.
std::unique_ptr<Interpolator> openFlavr(const Loader& load, bool gpu);

// [net]'s options for the GPU or the CPU: on the GPU half floats to store, full floats to compute with.
void configure(ncnn::Net& net, bool vulkan);

}  // namespace mediadroid
