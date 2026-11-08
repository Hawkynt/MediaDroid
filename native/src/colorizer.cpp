// DDColor on ncnn (Vulkan or the CPU): its paper-tiny model as tools/ddcolor/export.py and pnnx write it out
// (piddnad/DDColor, Apache-2.0; see engine/desktop/THIRD-PARTY-NOTICE.DDColor.txt) — the grey picture at 256 x 256 as
// three planes 0..1 in ("in0"), the a and b of Lab at 256 x 256 out ("out0"). What goes in and what is done with the
// colour is Colorizing.kt's.
#include <cstring>

#include "networks.h"

namespace mediadroyd {

namespace {

struct DdColor : Colorizer {
    ncnn::Net net;

    int colorize(const float* grey, float* ab) override {
        ncnn::Mat in(SIZE, SIZE, 3);
        for (int q = 0; q < 3; q++) std::memcpy(in.channel(q), grey + q * SIZE * SIZE, sizeof(float) * SIZE * SIZE);
        ncnn::Extractor ex = net.create_extractor();
        ex.input("in0", in);
        ncnn::Mat out;
        if (ex.extract("out0", out) != 0 || out.w != SIZE || out.h != SIZE || out.c != 2) return -2;
        for (int q = 0; q < 2; q++) std::memcpy(ab + q * SIZE * SIZE, out.channel(q), sizeof(float) * SIZE * SIZE);
        return 0;
    }
};

}  // namespace

std::unique_ptr<Colorizer> openDdcolor(const Loader& load, bool gpu) {
    auto it = std::make_unique<DdColor>();
    configure(it->net, gpu && gpuReady());
    if (load(it->net, "ddcolor") != 0) return nullptr;
    return it;
}

}  // namespace mediadroyd
