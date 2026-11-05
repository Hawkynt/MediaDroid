// The originals of FSR and NIS, run headless on Mesa — EGL without a display, desktop OpenGL 4.5 compute shaders —
// as their own samples run them, for oracle.sh to compare the app's shaders with. See README.md.
//
//   shader_oracle fsr-amd <ffx dir> <in.ppm> <out.ppm> <width> <height>
//       AMD's ffx_a.h and ffx_fsr1.h as FidelityFX-FSR's Vulkan sample (FSR_Pass.glsl) runs them on full floats:
//       FsrEasuF to that size, FsrRcasF on it at 0.2 stops, the constants from FsrEasuCon and FsrRcasCon on the
//       processor; the frame between the two 8-bit, as the app's is
//   shader_oracle fsr-amd-easu <ffx dir> <in.ppm> <out.ppm> <width> <height>
//       its EASU alone
//   shader_oracle fsr-amd-rcas <ffx dir> <in.ppm> <out.ppm> <width> <height>
//       its RCAS alone, at the picture's own size (the size given is not used)
//   shader_oracle nis-nvidia <nis dir> <in.ppm> <out.ppm> <width> <height>
//       NVIDIA's NIS_Scaler.h as NIS_Main.glsl runs it (NVScaler, sharpness 0.5), its constants from
//       NVScalerUpdateConfig and its coefficients from NIS_Config.h; only its five texture macros are written for
//       OpenGL's combined samplers instead of Vulkan's separate ones

#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES 1
#include <GL/glcorearb.h>

#include <algorithm>
#include <cmath>

#define A_CPU 1
#include "ffx_a.h"
#include "ffx_fsr1.h"
#include "NIS_Config.h"
#include "picture.h"

using namespace shadercheck;

namespace {

void startGl() {
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (!getDisplay) fail("no eglGetPlatformDisplayEXT");
    EGLDisplay display = getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) fail("no surfaceless EGL display");
    eglBindAPI(EGL_OPENGL_API);
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                       EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &count) || count == 0) fail("no OpenGL config");
    const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 5,
                                        EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                                        EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (context == EGL_NO_CONTEXT) fail("no OpenGL 4.5 context");
    if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) fail("cannot make the context current");
}

GLuint compute(const std::string& source) {
    GLuint s = glCreateShader(GL_COMPUTE_SHADER);
    const char* text = source.c_str();
    glShaderSource(s, 1, &text, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        std::vector<char> log(65536);
        glGetShaderInfoLog(s, GLsizei(log.size()), nullptr, log.data());
        fail(std::string("shader does not compile: ") + log.data());
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, s);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) fail("program does not link");
    return p;
}

// A texture: RGBA8 (filled from [data] when given) or RGBA32F, linear filtering clamped to the edge — what the
// samples' samplerLinearClamp and InputSampler are.
GLuint texture(int w, int h, GLenum format, const void* data = nullptr) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
    if (data) {
        const GLenum type = format == GL_RGBA32F ? GL_FLOAT : GL_UNSIGNED_BYTE;
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, type, data);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

Picture download(GLuint t, int w, int h) {
    std::vector<uint8_t> data(size_t(w) * h * 4);
    glBindTexture(GL_TEXTURE_2D, t);
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
    return fromRgba(data, w, h, true);
}

// One FSR pass of AMD's sample (CurrFilter, SAMPLE_SLOW_FALLBACK): [body] defines the callbacks and the filter call.
std::string fsrShader(const std::string& dir, const std::string& callbacks, const std::string& call) {
    return "#version 450\n#define A_GPU 1\n#define A_GLSL 1\n" + readFile(dir + "/ffx_a.h") +
           "\nlayout(binding = 0) uniform sampler2D InputTexture;\n"
           "layout(binding = 0, rgba8) uniform writeonly image2D OutputTexture;\n"
           "uniform uvec4 Const0;\nuniform uvec4 Const1;\nuniform uvec4 Const2;\nuniform uvec4 Const3;\n" +
           callbacks + readFile(dir + "/ffx_fsr1.h") +
           "\nlayout(local_size_x = 8, local_size_y = 8) in;\n"
           "void main() {\n  AU2 pos = AU2(gl_GlobalInvocationID.xy);\n"
           "  if (pos.x >= uint(imageSize(OutputTexture).x) || pos.y >= uint(imageSize(OutputTexture).y)) return;\n"
           "  AF3 c;\n  " + call + "\n  imageStore(OutputTexture, ASU2(pos), AF4(c, 1));\n}\n";
}

void setConst(GLuint prog, const char* name, const AU1* v) {
    glUniform4ui(glGetUniformLocation(prog, name), v[0], v[1], v[2], v[3]);
}

// AMD's EASU from [source] (w × h of it) into a new w × h 8-bit texture.
GLuint easuAmd(const std::string& dir, GLuint source, int inWidth, int inHeight, int w, int h) {
    GLuint easu = compute(fsrShader(dir,
        "#define FSR_EASU_F 1\n"
        "AF4 FsrEasuRF(AF2 p) { return textureGather(InputTexture, p, 0); }\n"
        "AF4 FsrEasuGF(AF2 p) { return textureGather(InputTexture, p, 1); }\n"
        "AF4 FsrEasuBF(AF2 p) { return textureGather(InputTexture, p, 2); }\n",
        "FsrEasuF(c, pos, Const0, Const1, Const2, Const3);"));
    GLuint out = texture(w, h, GL_RGBA8);
    varAU4(con0);
    varAU4(con1);
    varAU4(con2);
    varAU4(con3);
    FsrEasuCon(con0, con1, con2, con3, AF1(inWidth), AF1(inHeight), AF1(inWidth), AF1(inHeight), AF1(w), AF1(h));
    glUseProgram(easu);
    setConst(easu, "Const0", con0);
    setConst(easu, "Const1", con1);
    setConst(easu, "Const2", con2);
    setConst(easu, "Const3", con3);
    glBindTextureUnit(0, source);
    glBindImageTexture(0, out, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);
    return out;
}

// AMD's RCAS on [source] (w × h) at 0.2 stops, into a new 8-bit texture.
GLuint rcasAmd(const std::string& dir, GLuint source, int w, int h) {
    GLuint rcas = compute(fsrShader(dir,
        "#define FSR_RCAS_F 1\n"
        "AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(InputTexture, ASU2(p), 0); }\n"
        "void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}\n",
        "FsrRcasF(c.r, c.g, c.b, pos, Const0);"));
    GLuint out = texture(w, h, GL_RGBA8);
    varAU4(con);
    FsrRcasCon(con, 0.2f);
    glUseProgram(rcas);
    setConst(rcas, "Const0", con);
    glBindTextureUnit(0, source);
    glBindImageTexture(0, out, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);
    return out;
}

GLuint upload(const Picture& in) { return texture(in.width, in.height, GL_RGBA8, rgba(in, true).data()); }

Picture nisNvidia(const std::string& dir, const Picture& in, int w, int h) {
    std::string scaler = readFile(dir + "/NIS_Scaler.h");
    // Vulkan GLSL samples a texture through a sampler2D made of it and a separate sampler; OpenGL's sampler2D is both.
    scaler = std::regex_replace(scaler, std::regex(R"(sampler2D\((\w+), \w+\))"), "$1");
    const std::string source =
        "#version 450\n#define NIS_GLSL 1\n#define NIS_SCALER 1\n"
        "layout(std140, binding = 0) uniform const_buffer {\n"
        "  float kDetectRatio; float kDetectThres; float kMinContrastRatio; float kRatioNorm;\n"
        "  float kContrastBoost; float kEps; float kSharpStartY; float kSharpScaleY;\n"
        "  float kSharpStrengthMin; float kSharpStrengthScale; float kSharpLimitMin; float kSharpLimitScale;\n"
        "  float kScaleX; float kScaleY; float kDstNormX; float kDstNormY; float kSrcNormX; float kSrcNormY;\n"
        "  uint kInputViewportOriginX; uint kInputViewportOriginY; uint kInputViewportWidth;\n"
        "  uint kInputViewportHeight; uint kOutputViewportOriginX; uint kOutputViewportOriginY;\n"
        "  uint kOutputViewportWidth; uint kOutputViewportHeight; float reserved0; float reserved1;\n"
        "};\n"
        "layout(binding = 0) uniform sampler2D in_texture;\n"
        "layout(binding = 0, rgba8) uniform writeonly image2D out_texture;\n"
        "layout(binding = 1) uniform sampler2D coef_scaler;\n"
        "layout(binding = 2) uniform sampler2D coef_usm;\n" +
        scaler +
        "\nlayout(local_size_x = NIS_THREAD_GROUP_SIZE) in;\n"
        "void main() { NVScaler(gl_WorkGroupID.xy, gl_LocalInvocationID.x); }\n";
    GLuint prog = compute(source);
    NISConfig config{};
    NVScalerUpdateConfig(config, 0.5f, 0, 0, in.width, in.height, in.width, in.height, 0, 0, w, h, w, h);
    GLuint ubo;
    glGenBuffers(1, &ubo);
    glBindBuffer(GL_UNIFORM_BUFFER, ubo);
    glBufferData(GL_UNIFORM_BUFFER, sizeof config, &config, GL_STATIC_DRAW);
    glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
    const int coefWidth = int(::kFilterSize / 4);
    GLuint scale = texture(coefWidth, ::kPhaseCount, GL_RGBA32F, ::coef_scale);
    GLuint usm = texture(coefWidth, ::kPhaseCount, GL_RGBA32F, ::coef_usm);
    GLuint input = upload(in);
    GLuint out = texture(w, h, GL_RGBA8);
    glUseProgram(prog);
    glBindTextureUnit(0, input);
    glBindTextureUnit(1, scale);
    glBindTextureUnit(2, usm);
    glBindImageTexture(0, out, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    // NIS_Scaler.h's default block: 32 × 24 output pixels a work group.
    glDispatchCompute((w + 31) / 32, (h + 23) / 24, 1);
    glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
    return download(out, w, h);
}

}  // namespace

int main(int argc, char** argv) {
    const std::vector<std::string> a(argv + 1, argv + argc);
    if (a.size() != 6) fail("usage: see the head of tools/shader-check/oracle.cpp");
    startGl();
    const Picture in = readPpm(a[2]);
    const int w = std::stoi(a[4]), h = std::stoi(a[5]);
    if (a[0] == "fsr-amd") {
        writePpm(a[3], download(rcasAmd(a[1], easuAmd(a[1], upload(in), in.width, in.height, w, h), w, h), w, h));
    } else if (a[0] == "fsr-amd-easu") {
        writePpm(a[3], download(easuAmd(a[1], upload(in), in.width, in.height, w, h), w, h));
    } else if (a[0] == "fsr-amd-rcas") {
        writePpm(a[3], download(rcasAmd(a[1], upload(in), in.width, in.height), in.width, in.height));
    } else if (a[0] == "nis-nvidia") {
        writePpm(a[3], nisNvidia(a[1], in, w, h));
    } else {
        fail("usage: see the head of tools/shader-check/oracle.cpp");
    }
    if (glGetError() != GL_NO_ERROR) fail("GL error");
    return 0;
}
