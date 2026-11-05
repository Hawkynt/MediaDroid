// libplacebo's own debanding (pl_shader_deband, sampling.c), run by libplacebo on Mesa's OpenGL without a display: the
// oracle the app's deband pass (glsl/video/deband.glsl) and core/player's Deband are held to. See README.md.
//
//   placebo_deband <in.ppm> <out.ppm> <iterations> <threshold> <radius> <grain> <frame>
//
// The picture as one RGBA8 texture, as the stage holds it; <frame> is the dispatch's frame index, which seeds
// libplacebo's random numbers (sh_prng) beside the pixel's position.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <libplacebo/dispatch.h>
#include <libplacebo/opengl.h>
#include <libplacebo/shaders/sampling.h>

#include <cstdlib>

#include "picture.h"

using namespace shadercheck;

int main(int argc, char** argv) {
    if (argc != 8) fail("usage: placebo_deband <in.ppm> <out.ppm> <iterations> <threshold> <radius> <grain> <frame>");
    Picture in = readPpm(argv[1]);
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
    pl_log log = pl_log_create(PL_API_VER, &logParams);
    pl_opengl_params glParams{};
    glParams.get_proc_addr = eglGetProcAddress;
    glParams.allow_software = true;
    glParams.no_compute = true;
    glParams.egl_display = display;
    glParams.egl_context = context;
    pl_opengl gl = pl_opengl_create(log, &glParams);
    if (!gl) fail("libplacebo cannot use the context");
    pl_gpu gpu = gl->gpu;
    pl_fmt fmt = pl_find_named_fmt(gpu, "rgba8");
    std::vector<uint8_t> rgbaIn = rgba(in, true);
    pl_tex_params srcParams{};
    srcParams.w = in.width;
    srcParams.h = in.height;
    srcParams.format = fmt;
    srcParams.sampleable = true;
    srcParams.host_writable = true;
    srcParams.initial_data = rgbaIn.data();
    pl_tex src = pl_tex_create(gpu, &srcParams);
    pl_tex_params dstParams{};
    dstParams.w = in.width;
    dstParams.h = in.height;
    dstParams.format = fmt;
    dstParams.renderable = true;
    dstParams.host_readable = true;
    pl_tex dst = pl_tex_create(gpu, &dstParams);
    if (!src || !dst) fail("cannot make the textures");
    pl_dispatch dp = pl_dispatch_create(log, gpu);
    // The frame's index seeds libplacebo's random numbers (sh_prng); the dispatch counts frames from 0.
    for (int i = 0; i < std::atoi(argv[7]); i++) pl_dispatch_reset_frame(dp);
    pl_shader sh = pl_dispatch_begin(dp);
    pl_deband_params params{};
    params.iterations = std::atoi(argv[3]);
    params.threshold = float(std::atof(argv[4]));
    params.radius = float(std::atof(argv[5]));
    params.grain = float(std::atof(argv[6]));
    pl_sample_src sample{};
    sample.tex = src;
    pl_shader_deband(sh, &sample, &params);
    pl_dispatch_params run{};
    run.shader = &sh;
    run.target = dst;
    if (!pl_dispatch_finish(dp, &run)) fail("the deband pass failed");
    std::vector<uint8_t> out(size_t(in.width) * in.height * 4);
    pl_tex_transfer_params download{};
    download.tex = dst;
    download.ptr = out.data();
    if (!pl_tex_download(gpu, &download)) fail("cannot read back");
    writePpm(argv[2], fromRgba(out, in.width, in.height, true));
    pl_dispatch_destroy(&dp);
    pl_tex_destroy(gpu, &src);
    pl_tex_destroy(gpu, &dst);
    pl_opengl_destroy(&gl);
    pl_log_destroy(&log);
    return 0;
}
