// libplacebo itself, run on Mesa's OpenGL without a display: the oracle the app's passes written from it are held to —
// debanding (pl_shader_deband, sampling.c), HDR tone mapping (pl_shader_color_map_ex, colorspace.c, with the BT.2390
// curve of tone_mapping.c) and HDR peak detection (pl_shader_detect_peak). See README.md.
//
//   placebo_oracle deband <in.ppm> <out.ppm> <iterations> <threshold> <radius> <grain> <frame>
//   placebo_oracle tonemap <in16.ppm> <out.ppm> <pq|hlg> <peak nits>
//                     PQ or HLG BT.2020 RGB to BT.709 BT.1886 (libplacebo's SDR target): the BT.2390 curve, gamut by
//                     hard clip; <peak nits> the source's peak as HDR10 metadata gives it (0: none, the default)
//   placebo_oracle curve <peak nits>...
//                     prints the tone mapping's parameters for PQ to SDR at each peak, then pl_tone_map_sample's curve
//                     at 1001 points over the PQ range, and the colour matrices the mapping uses
//   placebo_oracle peak <pq|hlg> <in16.ppm>...
//                     the pictures as successive frames through libplacebo's peak detection (its defaults): per
//                     frame the detected peak and average, both in PQ
//
// The picture as one RGBA texture (8 or 16 bits), top row first, as libplacebo's own frames are.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <libplacebo/dispatch.h>
#include <libplacebo/opengl.h>
#include <libplacebo/shaders/colorspace.h>
#include <libplacebo/shaders/sampling.h>
#include <libplacebo/tone_mapping.h>

#include <cmath>
#include <cstdlib>

#include "picture.h"

using namespace shadercheck;

namespace {

pl_log logger;
pl_opengl gl;
pl_gpu gpu;

void startPlacebo(bool compute) {
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    EGLDisplay display = getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) fail("no surfaceless EGL display");
    eglBindAPI(EGL_OPENGL_API);
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &count) || count == 0) fail("no GL config");
    const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 5,
                                        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (context == EGL_NO_CONTEXT || !eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
        fail("no GL 4.5 context");
    }
    pl_log_params logParams{};
    logParams.log_cb = pl_log_simple;
    logParams.log_level = PL_LOG_WARN;
    logger = pl_log_create(PL_API_VER, &logParams);
    pl_opengl_params glParams{};
    glParams.get_proc_addr = eglGetProcAddress;
    glParams.allow_software = true;
    // Debanding as libplacebo runs it in a fragment shader; peak detection needs compute shaders.
    glParams.no_compute = !compute;
    glParams.egl_display = display;
    glParams.egl_context = context;
    gl = pl_opengl_create(logger, &glParams);
    if (!gl) fail("libplacebo cannot use the context");
    gpu = gl->gpu;
}

pl_tex texture(int w, int h, const char* format, const void* data, bool renderable, bool storable = false) {
    pl_tex_params params{};
    params.w = w;
    params.h = h;
    params.format = pl_find_named_fmt(gpu, format);
    params.sampleable = data != nullptr;
    params.host_writable = data != nullptr;
    params.initial_data = data;
    params.renderable = renderable;
    params.storable = storable;
    params.host_readable = renderable;
    pl_tex tex = pl_tex_create(gpu, &params);
    if (!tex) fail(std::string("cannot make a texture of ") + format);
    return tex;
}

Picture readBack(pl_tex tex, int w, int h) {
    std::vector<uint8_t> out(size_t(w) * h * 4);
    pl_tex_transfer_params download{};
    download.tex = tex;
    download.ptr = out.data();
    if (!pl_tex_download(gpu, &download)) fail("cannot read back");
    return fromRgba(out, w, h, true);
}

// The deband pass on an RGBA8 texture; <frame> is the dispatch's frame index, which seeds libplacebo's random numbers
// (sh_prng) beside the pixel's position.
int deband(char** argv) {
    Picture in = readPpm(argv[0]);
    std::vector<uint8_t> rgbaIn = rgba(in, true);
    pl_tex src = texture(in.width, in.height, "rgba8", rgbaIn.data(), false);
    pl_tex dst = texture(in.width, in.height, "rgba8", nullptr, true);
    pl_dispatch dp = pl_dispatch_create(logger, gpu);
    // The frame's index seeds libplacebo's random numbers (sh_prng); the dispatch counts frames from 0.
    for (int i = 0; i < std::atoi(argv[6]); i++) pl_dispatch_reset_frame(dp);
    pl_shader sh = pl_dispatch_begin(dp);
    pl_deband_params params{};
    params.iterations = std::atoi(argv[2]);
    params.threshold = float(std::atof(argv[3]));
    params.radius = float(std::atof(argv[4]));
    params.grain = float(std::atof(argv[5]));
    pl_sample_src sample{};
    sample.tex = src;
    pl_shader_deband(sh, &sample, &params);
    pl_dispatch_params run{};
    run.shader = &sh;
    run.target = dst;
    if (!pl_dispatch_finish(dp, &run)) fail("the deband pass failed");
    writePpm(argv[1], readBack(dst, in.width, in.height));
    pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu, &src);
    pl_tex_destroy(gpu, &dst);
    return 0;
}

// The colour mapping the app's tone mapping is converted from: libplacebo's defaults but for the BT.2390 curve and the
// hard clip into the target's gamut (no 3D LUT), with a state object as the renderer passes one (without, libplacebo
// falls back to a linear curve).
pl_color_map_params mapParams() {
    pl_color_map_params params = pl_color_map_default_params;
    params.tone_mapping_function = &pl_tone_map_bt2390;
    params.gamut_mapping = &pl_gamut_map_clip;
    return params;
}

pl_color_space source(const std::string& transfer, float peakNits) {
    pl_color_space csp{};
    csp.primaries = PL_COLOR_PRIM_BT_2020;
    csp.transfer = transfer == "hlg" ? PL_COLOR_TRC_HLG : PL_COLOR_TRC_PQ;
    csp.hdr.max_luma = peakNits;
    return csp;
}

int tonemap(char** argv) {
    Picture16 in = readPpm16(argv[0]);
    std::vector<uint16_t> rgbaIn = rgba16(in, true);
    pl_tex src = texture(in.width, in.height, "rgba16", rgbaIn.data(), false);
    pl_tex dst = texture(in.width, in.height, "rgba8", nullptr, true);
    pl_dispatch dp = pl_dispatch_create(logger, gpu);
    pl_shader sh = pl_dispatch_begin(dp);
    pl_sample_src sample{};
    sample.tex = src;
    pl_shader_sample_direct(sh, &sample);
    pl_shader_obj state = nullptr;
    pl_color_map_params params = mapParams();
    pl_color_map_args args{};
    args.src = source(argv[2], float(std::atof(argv[3])));
    args.dst = pl_color_space_bt709;
    args.state = &state;
    pl_shader_color_map_ex(sh, &params, &args);
    pl_dispatch_params run{};
    run.shader = &sh;
    run.target = dst;
    if (!pl_dispatch_finish(dp, &run)) fail("the tone mapping pass failed");
    writePpm(argv[1], readBack(dst, in.width, in.height));
    pl_shader_obj_destroy(&state);
    pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu, &src);
    pl_tex_destroy(gpu, &dst);
    return 0;
}

void printMatrix(const char* name, const pl_matrix3x3& m) {
    std::printf("%s", name);
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) std::printf(" %.9g", m.m[r][c]);
    }
    std::printf("\n");
}

// The tone curve as pl_shader_color_map_ex sets it up for PQ BT.2020 to libplacebo's SDR target, step by step.
int curve(int count, char** peaks) {
    for (int k = 0; k < count; k++) {
        pl_color_space src = source("pq", float(std::atof(peaks[k])));
        pl_color_space dst = pl_color_space_bt709;
        pl_color_space_infer_map(&src, &dst);
        pl_tone_map_params tone{};
        tone.function = &pl_tone_map_bt2390;
        tone.constants = pl_color_map_default_params.tone_constants;
        tone.input_scaling = PL_HDR_PQ;
        tone.output_scaling = PL_HDR_PQ;
        tone.lut_size = 256;
        tone.hdr = src.hdr;
        pl_nominal_luma_params in{};
        in.color = &src;
        in.metadata = PL_HDR_METADATA_ANY;
        in.scaling = PL_HDR_PQ;
        in.out_min = &tone.input_min;
        in.out_max = &tone.input_max;
        in.out_avg = &tone.input_avg;
        pl_color_space_nominal_luma_ex(&in);
        pl_nominal_luma_params out{};
        out.color = &dst;
        out.metadata = PL_HDR_METADATA_HDR10;
        out.scaling = PL_HDR_PQ;
        out.out_min = &tone.output_min;
        out.out_max = &tone.output_max;
        pl_color_space_nominal_luma_ex(&out);
        pl_tone_map_params_infer(&tone);
        if (std::fabs(tone.input_max - tone.output_max) < 1e-6) tone.output_max = tone.input_max;
        if (std::fabs(tone.input_min - tone.output_min) < 1e-6) tone.output_min = tone.input_min;
        tone.output_max = std::fmin(tone.output_max, tone.input_max);
        std::printf("peak %s: input %.9g %.9g output %.9g %.9g\n", peaks[k], tone.input_min, tone.input_max,
                    tone.output_min, tone.output_max);
        std::printf("curve");
        for (int i = 0; i <= 1000; i++) std::printf(" %.9g", pl_tone_map_sample(i / 1000.0f, &tone));
        std::printf("\n");
    }
    printMatrix("rgb2lms-bt2020", pl_ipt_rgb2lms(pl_raw_primaries_get(PL_COLOR_PRIM_BT_2020)));
    printMatrix("lms2rgb-bt709", pl_ipt_lms2rgb(pl_raw_primaries_get(PL_COLOR_PRIM_BT_709)));
    printMatrix("rgb2xyz-bt2020", pl_get_rgb2xyz_matrix(pl_raw_primaries_get(PL_COLOR_PRIM_BT_2020)));
    return 0;
}

// Each picture one frame through pl_shader_detect_peak (its defaults: no delay, so each frame's measurement is read
// back before the next), then what pl_get_detected_hdr_metadata holds after it.
int peak(const std::string& transfer, int count, char** files) {
    pl_dispatch dp = pl_dispatch_create(logger, gpu);
    pl_shader_obj state = nullptr;
    pl_color_space csp = source(transfer, 0);
    for (int k = 0; k < count; k++) {
        Picture16 in = readPpm16(files[k]);
        std::vector<uint16_t> rgbaIn = rgba16(in, true);
        pl_tex src = texture(in.width, in.height, "rgba16", rgbaIn.data(), false);
        pl_tex dst = texture(in.width, in.height, "rgba8", nullptr, true, true);
        pl_shader sh = pl_dispatch_begin(dp);
        pl_sample_src sample{};
        sample.tex = src;
        pl_shader_sample_direct(sh, &sample);
        if (!pl_shader_detect_peak(sh, csp, &state, &pl_peak_detect_default_params)) fail("no peak detection");
        pl_dispatch_params run{};
        run.shader = &sh;
        run.target = dst;
        if (!pl_dispatch_finish(dp, &run)) fail("the peak detection pass failed");
        pl_hdr_metadata hdr{};
        if (!pl_get_detected_hdr_metadata(state, &hdr)) fail("no peak detected");
        std::printf("%.9g %.9g\n", hdr.max_pq_y, hdr.avg_pq_y);
        pl_dispatch_reset_frame(dp);
        pl_tex_destroy(gpu, &src);
        pl_tex_destroy(gpu, &dst);
    }
    pl_shader_obj_destroy(&state);
    pl_dispatch_destroy(&dp);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "curve" && argc > 2) return curve(argc - 2, argv + 2);
    startPlacebo(mode == "peak");
    int result = 0;
    if (mode == "deband" && argc == 9) result = deband(argv + 2);
    else if (mode == "tonemap" && argc == 6) result = tonemap(argv + 2);
    else if (mode == "peak" && argc > 3) result = peak(argv[2], argc - 3, argv + 3);
    else fail("usage: see the head of tools/shader-check/placebo.cpp");
    pl_opengl_destroy(&gl);
    pl_log_destroy(&logger);
    return result;
}
