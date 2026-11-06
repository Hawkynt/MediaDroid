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
//   placebo_oracle dither <in16.ppm> <out.ppm>
//                     the 16-bit picture dithered to 8 bits as libplacebo's renderer does it for an 8-bit screen
//                     (pl_shader_dither, its defaults: blue noise, a 64 × 64 matrix, not temporal)
//   placebo_oracle bluenoise
//                     prints that matrix as pl_generate_blue_noise makes it in a fresh process (its rand()
//                     unseeded): 64 rows of 64 ranks, 0 to 4095, each the value times 4096
//   placebo_oracle chroma <in.yuv> <width> <height> <spline36|shader file> <out.ppm> [rgb]
//                     a yuv420p frame (full range BT.709, chroma sited at the centre, as the stage's YUV pass reads
//                     it) through libplacebo's renderer, the chroma planes enlarged by spline36 (pl_render_params'
//                     plane_upscaler) or by an mpv user shader hooking CHROMA (KrigBilateral); out the YCbCr itself
//                     at the luma's size, 16 bits a value (Y, Cb, Cr in place of R, G, B) — or with [rgb] the
//                     picture in 8-bit RGB, as the stage's pass draws it
//   placebo_oracle hook <in.ppm> <shader.glsl> <out.ppm> <width> <height> [yuv]
//                     an mpv user shader run by libplacebo's renderer (pl_mpv_user_shader_parse) on the picture,
//                     shown at <width> x <height> (bilinear where the shader leaves scaling to do); with [yuv] the
//                     picture is handed over as full-range BT.709 YCbCr planes, so the shader's LUMA hooks run
//
// The picture as one RGBA texture (8 or 16 bits), top row first, as libplacebo's own frames are.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <libplacebo/dispatch.h>
#include <libplacebo/dither.h>
#include <libplacebo/opengl.h>
#include <libplacebo/renderer.h>
#include <libplacebo/shaders/custom.h>
#include <libplacebo/shaders/colorspace.h>
#include <libplacebo/shaders/dithering.h>
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

// The last pass of libplacebo's renderer for an 8-bit target (pass_output_target): the picture dithered to 8 bits
// with its default dither, the matrix made on first use and kept in the state object.
int dither(char** argv) {
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
    pl_shader_dither(sh, 8, &state, &pl_dither_default_params);
    pl_dispatch_params run{};
    run.shader = &sh;
    run.target = dst;
    if (!pl_dispatch_finish(dp, &run)) fail("the dither pass failed");
    writePpm(argv[1], readBack(dst, in.width, in.height));
    pl_shader_obj_destroy(&state);
    pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu, &src);
    pl_tex_destroy(gpu, &dst);
    return 0;
}

int blueNoise() {
    const int size = 1 << pl_dither_default_params.lut_size;
    std::vector<float> data(size_t(size) * size);
    pl_generate_blue_noise(data.data(), size);
    for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
            std::printf(x ? " %d" : "%d", int(std::lround(data[y * size + x] * size * size)));
        }
        std::printf("\n");
    }
    return 0;
}

// One plane of a yuv420p frame as an 8-bit single-channel texture.
pl_tex planeTexture(int w, int h, const uint8_t* data) {
    pl_tex_params params{};
    params.w = w;
    params.h = h;
    params.format = pl_find_named_fmt(gpu, "r8");
    params.sampleable = true;
    params.host_writable = true;
    params.initial_data = data;
    pl_tex tex = pl_tex_create(gpu, &params);
    if (!tex) fail("cannot make a plane texture");
    return tex;
}

// A 16-bit RGBA texture's first three values written as a 16-bit PPM.
void writePpm16(const char* path, pl_tex tex, int w, int h) {
    std::vector<uint16_t> data(size_t(w) * h * 4);
    pl_tex_transfer_params download{};
    download.tex = tex;
    download.ptr = data.data();
    if (!pl_tex_download(gpu, &download)) fail("cannot read back");
    FILE* file = std::fopen(path, "wb");
    if (!file) fail(std::string("cannot write ") + path);
    std::fprintf(file, "P6\n%d %d\n65535\n", w, h);
    for (size_t i = 0; i < size_t(w) * h; i++) {
        for (int c = 0; c < 3; c++) {
            const uint16_t v = data[i * 4 + c];
            std::fputc(v >> 8, file);
            std::fputc(v & 0xFF, file);
        }
    }
    std::fclose(file);
}

// The renderer on a yuv420p frame, the luma at its own size and the chroma enlarged to it; the target holds the
// result as YCbCr again (the same full-range BT.709 both ways, so libplacebo's decode and encode cancel out).
int chroma(char** argv) {
    const int w = std::atoi(argv[1]);
    const int h = std::atoi(argv[2]);
    const int cw = (w + 1) / 2;
    const int ch = (h + 1) / 2;
    const std::string data = readFile(argv[0]);
    if (data.size() != size_t(w) * h + 2 * size_t(cw) * ch) fail("not a yuv420p frame of that size");
    const auto* bytes = reinterpret_cast<const uint8_t*>(data.data());
    pl_tex planes[3] = {
        planeTexture(w, h, bytes),
        planeTexture(cw, ch, bytes + size_t(w) * h),
        planeTexture(cw, ch, bytes + size_t(w) * h + size_t(cw) * ch),
    };
    pl_frame image{};
    image.num_planes = 3;
    for (int i = 0; i < 3; i++) {
        image.planes[i].texture = planes[i];
        image.planes[i].components = 1;
        image.planes[i].component_mapping[0] = i;
    }
    image.repr.sys = PL_COLOR_SYSTEM_BT_709;
    image.repr.levels = PL_COLOR_LEVELS_FULL;
    image.color = pl_color_space_bt709;
    pl_frame_set_chroma_location(&image, PL_CHROMA_CENTER);
    const bool rgb = argv[5] != nullptr;
    pl_tex out = texture(w, h, rgb ? "rgba8" : "rgba16", nullptr, true);
    pl_frame target{};
    target.num_planes = 1;
    target.planes[0].texture = out;
    target.planes[0].components = 3;
    for (int i = 0; i < 3; i++) target.planes[0].component_mapping[i] = i;
    target.repr = rgb ? pl_color_repr_rgb : image.repr;
    // Encoded with the 8-bit offsets it was decoded with (the chroma's middle at 128/255), not a 16-bit target's 0.5.
    if (!rgb) {
        target.repr.bits.sample_depth = 8;
        target.repr.bits.color_depth = 8;
    }
    target.color = image.color;
    const std::string filter = argv[3];
    const pl_hook* hook = nullptr;
    pl_render_params params{};
    params.color_map_params = &pl_color_map_default_params;
    params.color_adjustment = &pl_color_adjustment_neutral;
    params.plane_upscaler = filter == "spline36" ? &pl_filter_spline36 : &pl_filter_bilinear;
    if (filter != "bilinear" && filter != "spline36") {
        const std::string shader = readFile(filter);
        hook = pl_mpv_user_shader_parse(gpu, shader.data(), shader.size());
        if (!hook) fail("libplacebo cannot read " + filter);
        params.hooks = &hook;
        params.num_hooks = 1;
    }
    pl_renderer renderer = pl_renderer_create(logger, gpu);
    if (!pl_render_image(renderer, &image, &target, &params)) fail("the render failed");
    if (rgb) writePpm(argv[4], readBack(out, w, h));
    else writePpm16(argv[4], out, w, h);
    if (hook) pl_mpv_user_shader_destroy(&hook);
    pl_renderer_destroy(&renderer);
    pl_tex_destroy(gpu, &out);
    for (pl_tex& t : planes) pl_tex_destroy(gpu, &t);
    return 0;
}

// An mpv user shader through libplacebo's renderer, the picture as RGB or as full-range BT.709 YCbCr planes.
int hook(char** argv, bool yuv) {
    Picture in = readPpm(argv[0]);
    const std::string shader = readFile(argv[1]);
    const int w = std::atoi(argv[3]);
    const int h = std::atoi(argv[4]);
    const pl_hook* hook = pl_mpv_user_shader_parse(gpu, shader.data(), shader.size());
    if (!hook) fail(std::string("libplacebo cannot read ") + argv[1]);
    pl_frame image{};
    std::vector<pl_tex> textures;
    std::vector<uint8_t> rgbaIn;
    std::vector<std::vector<uint8_t>> planes(3, std::vector<uint8_t>(size_t(in.width) * in.height));
    if (yuv) {
        for (size_t i = 0; i < size_t(in.width) * in.height; i++) {
            const double r = in.rgb[i * 3], g = in.rgb[i * 3 + 1], b = in.rgb[i * 3 + 2];
            const double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            const double values[3] = {y, (b - y) / 1.8556 + 128, (r - y) / 1.5748 + 128};
            for (int c = 0; c < 3; c++) {
                planes[c][i] = uint8_t(std::lround(std::fmin(255.0, std::fmax(0.0, values[c]))));
            }
        }
        image.num_planes = 3;
        for (int c = 0; c < 3; c++) {
            textures.push_back(planeTexture(in.width, in.height, planes[c].data()));
            image.planes[c].texture = textures.back();
            image.planes[c].components = 1;
            image.planes[c].component_mapping[0] = c;
        }
        image.repr.sys = PL_COLOR_SYSTEM_BT_709;
        image.repr.levels = PL_COLOR_LEVELS_FULL;
    } else {
        rgbaIn = rgba(in, true);
        textures.push_back(texture(in.width, in.height, "rgba8", rgbaIn.data(), false));
        image.num_planes = 1;
        image.planes[0].texture = textures.back();
        image.planes[0].components = 3;
        for (int c = 0; c < 3; c++) image.planes[0].component_mapping[c] = c;
        image.repr = pl_color_repr_rgb;
    }
    image.color = pl_color_space_bt709;
    pl_tex out = texture(w, h, "rgba8", nullptr, true);
    pl_frame target{};
    target.num_planes = 1;
    target.planes[0].texture = out;
    target.planes[0].components = 3;
    for (int c = 0; c < 3; c++) target.planes[0].component_mapping[c] = c;
    target.repr = pl_color_repr_rgb;
    target.color = pl_color_space_bt709;
    pl_render_params params{};
    params.color_map_params = &pl_color_map_default_params;
    params.color_adjustment = &pl_color_adjustment_neutral;
    params.upscaler = &pl_filter_bilinear;
    params.downscaler = &pl_filter_bilinear;
    params.plane_upscaler = &pl_filter_bilinear;
    params.disable_linear_scaling = true;
    params.hooks = &hook;
    params.num_hooks = 1;
    pl_renderer renderer = pl_renderer_create(logger, gpu);
    if (!pl_render_image(renderer, &image, &target, &params)) fail("the render failed");
    writePpm(argv[2], readBack(out, w, h));
    pl_mpv_user_shader_destroy(&hook);
    pl_renderer_destroy(&renderer);
    pl_tex_destroy(gpu, &out);
    for (pl_tex& t : textures) pl_tex_destroy(gpu, &t);
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "curve" && argc > 2) return curve(argc - 2, argv + 2);
    if (mode == "bluenoise" && argc == 2) return blueNoise();
    startPlacebo(mode == "peak");
    int result = 0;
    if (mode == "deband" && argc == 9) result = deband(argv + 2);
    else if (mode == "tonemap" && argc == 6) result = tonemap(argv + 2);
    else if (mode == "dither" && argc == 4) result = dither(argv + 2);
    else if (mode == "peak" && argc > 3) result = peak(argv[2], argc - 3, argv + 3);
    else if (mode == "chroma" && (argc == 7 || (argc == 8 && std::string(argv[7]) == "rgb")))
        result = chroma(argv + 2);
    else if (mode == "hook" && (argc == 7 || (argc == 8 && std::string(argv[7]) == "yuv")))
        result = hook(argv + 2, argc == 8);
    else fail("usage: see the head of tools/shader-check/placebo.cpp");
    pl_opengl_destroy(&gl);
    pl_log_destroy(&logger);
    return result;
}
