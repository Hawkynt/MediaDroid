// The app's own upscaling shaders (app/src/main/glsl) run headless on Mesa — EGL without a display, OpenGL ES 3.2 —
// the way the filters' stage (VideoFilterStage) runs them, for oracle.sh to compare with the originals. See README.md.
//
//   shader_check picture <out.ppm> <width> <height> [gray]  the test picture
//   shader_check plain <in.ppm> <out.ppm> <width> <height>  bilinear scaling, which an upscaler has to differ from
//   shader_check gsr <in.ppm> <out.ppm> <width> <height>    glsl/video/sgsr.frag to that size
//   shader_check gsr-qualcomm <shader> <in.ppm> <out.ppm> <width> <height>
//                                                           Qualcomm's sgsr1_shader_mobile.frag, unchanged but for
//                                                           its version line and what Mesa holds to the letter
//   shader_check fsr <in.ppm> <out.ppm> <width> <height>    glsl/video/fsr-easu.frag to that size, then fsr-rcas.frag
//   shader_check fsr-easu <in.ppm> <out.ppm> <width> <height>
//                                                           its EASU alone
//   shader_check fsr-rcas <in.ppm> <out.ppm>                its RCAS alone, at the picture's own size
//   shader_check ewa <in.ppm> <out.ppm> <width> <height> <antiring>
//                                                           glsl/video/ewa.frag to that size (at most twice)
//   shader_check lanczos <in.ppm> <out.ppm> <width> <height> <antiring>
//                                                           glsl/video/lanczos.frag to that size
//   shader_check nis <in.ppm> <out.ppm> <width> <height>    glsl/video/nis.frag to that size (at most twice)
//   shader_check temporal <prev.ppm> <cur.ppm> <next.ppm> <out.ppm> <yadif|bwdif> <tff 0|1> <second 0|1>
//                                                           glsl/video/temporal.frag: one field of cur rebuilt
//   shader_check xbrz <in.ppm> <out.ppm> <factor> <table.bin>
//                                                           glsl/xbrz to factor (2..6) times the size
//   shader_check yuv <in.yuv> <width> <height> <601|709|2020> <out.ppm>
//                                                           glsl/video/yuv.frag: a yuv420p frame to RGB
//   shader_check deinterlace <in.ppm> <out.ppm> <mode> <tff 0|1>
//                                                           glsl/video/deinterlace.frag (1 Auto, 2 Blend, 3 Bob)
//   shader_check anime4k <in.ppm> <out.ppm>                 glsl/anime4k to twice the size
//   shader_check acnet <in.ppm> <out.ppm>                   glsl/acnet to twice the size
//   shader_check compare <a.ppm> <b.ppm> <max> <mean> [border]
//                                                           fails when they differ by more (in 8-bit steps), the
//                                                           border's pixels on every side left out

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <algorithm>
#include <cmath>

#include "NIS_Config.h"
#include "picture.h"

using namespace shadercheck;

namespace {

// The test picture: what upscalers are judged on — flat discs with round edges in every colour, thin lines at
// several angles, a smooth gradient and one-pixel detail, all fixed (no clock, no randomness).
Picture testPicture(int w, int h, bool gray) {
    Picture p{w, h, std::vector<uint8_t>(size_t(w) * h * 3)};
    auto level = [](int cx, int cy, int c) {
        uint32_t v = uint32_t(cx * 73856093) ^ uint32_t(cy * 19349663) ^ uint32_t(c * 83492791);
        v = (v ^ (v >> 13)) * 0x5bd1e995u;
        return int((v ^ (v >> 15)) & 0xFF);
    };
    const int cell = 24;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int rgb[3];
            int cx = x / cell, cy = y / cell;
            int dx = x - (cx * cell + cell / 2), dy = y - (cy * cell + cell / 2);
            int radius = (7 + level(cx, cy, 3) % 14) * cell / 24;
            bool inside = dx * dx + dy * dy <= radius * radius;
            for (int c = 0; c < 3; c++) rgb[c] = inside ? level(cx, cy, c) : 40 + 150 * x / w;
            if (y > h * 3 / 4) {  // the lower quarter: a gradient with lines and one-pixel detail across it
                for (int c = 0; c < 3; c++) rgb[c] = 255 * x / w;
                if ((x + 2 * y) % 37 < 2 || (3 * x - y) % 53 < 2) rgb[0] = rgb[1] = rgb[2] = 250;
                if (x % 7 == 0 && y % 5 == 0) rgb[0] = rgb[1] = rgb[2] = 10;
            }
            if (gray) {
                int l = (rgb[0] * 2126 + rgb[1] * 7152 + rgb[2] * 722 + 5000) / 10000;
                rgb[0] = rgb[1] = rgb[2] = l;
            }
            for (int c = 0; c < 3; c++) p.rgb[(size_t(y) * w + x) * 3 + c] = uint8_t(rgb[c]);
        }
    }
    return p;
}

// A shader of the app's as the shaderSources task assembles it: each `#include "name.glsl"` replaced by that file,
// beside the including one.
std::string appShader(const std::string& relative) {
    // SHADER_CHECK_GLSL: another shader folder, to try a changed copy without touching the app's.
    const char* other = std::getenv("SHADER_CHECK_GLSL");
    const std::string path = std::string(other ? other : GLSL_DIR) + "/" + relative;
    const std::string dir = path.substr(0, path.rfind('/'));
    std::istringstream in(readFile(path));
    std::string line, out;
    const std::regex include(R"re(^\s*#include\s+"([^"]+)"\s*$)re");
    std::smatch m;
    while (std::getline(in, line)) {
        if (std::regex_match(line, m, include)) out += readFile(dir + "/" + m[1].str());
        else out += line + "\n";
    }
    return out;
}

void startGl() {
    auto getDisplay = reinterpret_cast<PFNEGLGETPLATFORMDISPLAYEXTPROC>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (!getDisplay) fail("no eglGetPlatformDisplayEXT");
    EGLDisplay display = getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) fail("no surfaceless EGL display");
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                       EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(display, configAttributes, &config, 1, &count) || count == 0) fail("no ES 3 config");
    const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (context == EGL_NO_CONTEXT) fail("no ES 3.2 context");
    if (!eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) fail("cannot make the context current");
}

GLuint compile(GLenum kind, const std::string& source) {
    GLuint s = glCreateShader(kind);
    const char* text = source.c_str();
    glShaderSource(s, 1, &text, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        fail(std::string("shader does not compile: ") + log);
    }
    return s;
}

GLuint program(const std::string& vertex, const std::string& fragment) {
    GLuint p = glCreateProgram();
    glAttachShader(p, compile(GL_VERTEX_SHADER, vertex));
    glAttachShader(p, compile(GL_FRAGMENT_SHADER, fragment));
    glBindAttribLocation(p, 0, "aPosition");
    glBindAttribLocation(p, 1, "aTexCoord");
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) fail("program does not link");
    return p;
}

GLuint appProgram(const std::string& fragment) { return program(appShader("video/vertex.vert"), appShader(fragment)); }

struct Target {
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int width = 0;
    int height = 0;
};

// A texture to draw into: 8-bit as the stage's frames, or half floats as its Anime4K and ACNet textures.
Target target(int w, int h, bool half = false) {
    Target t{0, 0, w, h};
    glGenTextures(1, &t.texture);
    glBindTexture(GL_TEXTURE_2D, t.texture);
    if (half) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
    else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &t.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, t.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) fail("cannot draw into a texture");
    return t;
}

// The picture as the stage holds its frames: rows counted upwards, so the picture's top row is where v is 1.
Target upload(const Picture& p) {
    Target t = target(p.width, p.height);
    glBindTexture(GL_TEXTURE_2D, t.texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.width, p.height, GL_RGBA, GL_UNSIGNED_BYTE, rgba(p, false).data());
    return t;
}

Picture download(const Target& t) {
    glBindFramebuffer(GL_FRAMEBUFFER, t.framebuffer);
    std::vector<uint8_t> data(size_t(t.width) * t.height * 4);
    glReadPixels(0, 0, t.width, t.height, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
    return fromRgba(data, t.width, t.height, false);
}

// One pass over the whole of [into], as the stage's run(): the input on unit 0 as uTex, the identity matrix.
const float kIdentity[] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

void draw(GLuint prog, const Target& into, GLuint input, const float* matrix = kIdentity) {
    static const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    static const float quadTex[] = {0, 0, 1, 0, 0, 1, 1, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, into.framebuffer);
    glViewport(0, 0, into.width, into.height);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, input);
    glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
    glUniformMatrix4fv(glGetUniformLocation(prog, "uTexMatrix"), 1, GL_FALSE, matrix);
    glUniform1f(glGetUniformLocation(prog, "uGrain"), 0.0f);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, quadTex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// YADIF or BWDIF as VideoFilterStage.rebuildField runs it: [cur] with one field rebuilt from [prev], [cur], [next];
// the field as Deinterlacer.rebuiltParity picks it.
Picture temporal(const Picture& prev, const Picture& cur, const Picture& next, bool bwdif, bool tff, bool second) {
    GLuint prog = appProgram("video/temporal.frag");
    Target p = upload(prev), c = upload(cur), n = upload(next);
    // The stage reads these exact pixels nearest (VideoFilterStage.exact).
    for (GLuint t : {p.texture, c.texture, n.texture}) {
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    Target out = target(cur.width, cur.height);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, p.texture);
    glUniform1i(glGetUniformLocation(prog, "uPrev"), 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, n.texture);
    glUniform1i(glGetUniformLocation(prog, "uNext"), 2);
    glUniform2f(glGetUniformLocation(prog, "uSize"), float(cur.width), float(cur.height));
    glUniform1i(glGetUniformLocation(prog, "uSecond"), second ? 1 : 0);
    glUniform1f(glGetUniformLocation(prog, "uParity"), float((tff ? 1 : 0) ^ (second ? 0 : 1)));
    glUniform1i(glGetUniformLocation(prog, "uBwdif"), bwdif ? 1 : 0);
    draw(prog, out, c.texture);
    return download(out);
}

// A yuv420p frame (raw planes, rows from the top) to RGB as VideoFilterStage.takeYuv/copyInput do it: the planes as
// luminance textures, rows from the top flipped by the texture matrix, the matrix as YuvMatrix.toRgb computes it.
Picture yuv(const std::string& path, int w, int h, double kr, double kb) {
    const std::string data = readFile(path);
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    if (data.size() != size_t(w) * h + 2 * size_t(cw) * ch) fail("not a yuv420p frame of that size: " + path);
    const uint8_t* planes[3] = {reinterpret_cast<const uint8_t*>(data.data()),
                                reinterpret_cast<const uint8_t*>(data.data()) + size_t(w) * h,
                                reinterpret_cast<const uint8_t*>(data.data()) + size_t(w) * h + size_t(cw) * ch};
    GLuint tex[3];
    glGenTextures(3, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, tex[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, i ? cw : w, i ? ch : h, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                     planes[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    const double kg = 1 - kr - kb, ys = 255.0 / 219, cs = 255.0 / 224;
    const float matrix[9] = {
        float(ys), float(ys), float(ys),
        0, float(-cs * 2 * (1 - kb) * kb / kg), float(cs * 2 * (1 - kb)),
        float(cs * 2 * (1 - kr)), float(-cs * 2 * (1 - kr) * kr / kg), 0,
    };
    // Rows from the top in the planes, from the bottom in texture coordinates (takeYuv's texMatrix).
    const float flip[16] = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};
    GLuint prog = appProgram("video/yuv.frag");
    Target out = target(w, h);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex[1]);
    glUniform1i(glGetUniformLocation(prog, "uU"), 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, tex[2]);
    glUniform1i(glGetUniformLocation(prog, "uV"), 2);
    glUniformMatrix3fv(glGetUniformLocation(prog, "uColor"), 1, GL_FALSE, matrix);
    glUniform1f(glGetUniformLocation(prog, "uChroma"), 1.0f);
    draw(prog, out, tex[0], flip);
    return download(out);
}

// Auto, Blend or Bob (Deinterlace's ordinal [mode]) as the stage runs it, the first field kept for [tff].
Picture deinterlace(const Picture& in, int mode, bool tff) {
    GLuint prog = appProgram("video/deinterlace.frag");
    Target source = upload(in);
    Target out = target(in.width, in.height);
    glUseProgram(prog);
    glUniform2f(glGetUniformLocation(prog, "uTexel"), 1.0f / in.width, 1.0f / in.height);
    glUniform1i(glGetUniformLocation(prog, "uMode"), mode);
    glUniform1f(glGetUniformLocation(prog, "uHeight"), float(in.height));
    glUniform1f(glGetUniformLocation(prog, "uParity"), float(tff ? 0 : 1));
    draw(prog, out, source.texture);
    return download(out);
}

// Bilinear scaling, the screen's own: what an upscaler has to differ from for a match with its oracle to mean much.
Picture plain(const Picture& in, int w, int h) {
    GLuint prog = program(appShader("video/vertex.vert"),
                          "precision mediump float;\nuniform sampler2D uTex;\nvarying vec2 vTex;\n"
                          "void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }\n");
    Target source = upload(in);
    Target out = target(w, h);
    draw(prog, out, source.texture);
    return download(out);
}

Picture gsr(const Picture& in, int w, int h) {
    GLuint prog = appProgram("video/sgsr.frag");
    Target source = upload(in);
    Target out = target(w, h);
    glUseProgram(prog);
    glUniform2f(glGetUniformLocation(prog, "uInSize"), float(in.width), float(in.height));
    draw(prog, out, source.texture);
    return download(out);
}

Picture gsrQualcomm(const std::string& shaderPath, const Picture& in, int w, int h) {
    std::string fragment = readFile(shaderPath);
    // textureGather is OpenGL ES 3.1; the shader says 300 es, which Mesa holds it to.
    fragment = std::regex_replace(fragment, std::regex("#version 300 es"), "#version 310 es");
    // `coord + highp vec2(…)`: a precision qualifier on a constructor is no GLSL, though Adreno's compiler takes it.
    fragment = std::regex_replace(fragment, std::regex(R"(\+\s*highp vec2\()"), "+ vec2(");
    // textureGather's component has to be a constant expression; `mode` is one in all but name.
    fragment = std::regex_replace(fragment, std::regex(R"(\bint mode = OperationMode;)"),
                                  "const int mode = OperationMode;");
    const std::string vertex =
        "#version 310 es\n"
        "layout(location = 0) in vec2 aPosition;\n"
        "layout(location = 1) in vec2 aTexCoord;\n"
        "layout(location = 0) out highp vec4 in_TEXCOORD0;\n"
        "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); in_TEXCOORD0 = vec4(aTexCoord, 0.0, 0.0); }\n";
    GLuint prog = program(vertex, fragment);
    Target source = upload(in);
    Target out = target(w, h);
    glUseProgram(prog);
    glUniform4f(glGetUniformLocation(prog, "ViewportInfo"), 1.0f / in.width, 1.0f / in.height, float(in.width),
                float(in.height));
    glUniform1i(glGetUniformLocation(prog, "ps0"), 0);
    draw(prog, out, source.texture);
    return download(out);
}

// RCAS alone on [in], at the stage's sharpness: to compare the two halves of FSR apart.
Picture rcasOnly(const Picture& in) {
    GLuint rcas = appProgram("video/fsr-rcas.frag");
    Target source = upload(in);
    Target out = target(in.width, in.height);
    glUseProgram(rcas);
    glUniform2f(glGetUniformLocation(rcas, "uTexel"), 1.0f / in.width, 1.0f / in.height);
    glUniform1f(glGetUniformLocation(rcas, "uSharp"), std::exp2(-0.2f));
    draw(rcas, out, source.texture);
    return download(out);
}

// FSR 1 as the stage runs it: EASU to the output's size into an 8-bit frame, RCAS on it at
// VideoFilterStage.RCAS_SHARPNESS — 0.2 stops below full on AMD's scale.
Picture fsr(const Picture& in, int w, int h, bool easuOnly = false) {
    GLuint easu = appProgram("video/fsr-easu.frag");
    GLuint rcas = appProgram("video/fsr-rcas.frag");
    Target source = upload(in);
    Target enlarged = target(w, h);
    glUseProgram(easu);
    glUniform2f(glGetUniformLocation(easu, "uInSize"), float(in.width), float(in.height));
    draw(easu, enlarged, source.texture);
    if (easuOnly) return download(enlarged);
    Target out = target(w, h);
    glUseProgram(rcas);
    glUniform2f(glGetUniformLocation(rcas, "uTexel"), 1.0f / w, 1.0f / h);
    glUniform1f(glGetUniformLocation(rcas, "uSharp"), std::exp2(-0.2f));
    draw(rcas, out, enlarged.texture);
    return download(out);
}

// NIS as the stage runs it: its coefficients packed as VideoFilterStage.nisTable() packs Nis's — two bytes a tap,
// (c + 1) / 2.5 — and its settings at sharpness 0.5. Nis's tables and settings are NVIDIA's (NisTest holds them to
// NIS_Scaler.h), so they are taken here from NIS_Config.h itself.
Picture nis(const Picture& in, int w, int h) {
    std::vector<uint8_t> table(6 * ::kPhaseCount * 4);
    auto put = [](uint8_t* at, float c) {
        int v = std::clamp(int((c + 1.0f) / 2.5f * 65535.0f + 0.5f), 0, 65535);
        at[0] = uint8_t(v >> 8);
        at[1] = uint8_t(v & 0xFF);
    };
    for (size_t phase = 0; phase < ::kPhaseCount; phase++) {
        for (int tap = 0; tap < 6; tap++) {
            uint8_t* texel = &table[(phase * 6 + tap) * 4];
            put(texel, ::coef_scale[phase][tap]);
            put(texel + 2, ::coef_usm[phase][tap]);
        }
    }
    GLuint coef;
    glGenTextures(1, &coef);
    glBindTexture(GL_TEXTURE_2D, coef);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 6, ::kPhaseCount, 0, GL_RGBA, GL_UNSIGNED_BYTE, table.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    NISConfig c{};
    NVScalerUpdateConfig(c, 0.5f, 0, 0, in.width, in.height, in.width, in.height, 0, 0, w, h, w, h);
    GLuint prog = appProgram("video/nis.frag");
    Target source = upload(in);
    Target out = target(w, h);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, coef);
    glUniform1i(glGetUniformLocation(prog, "uCoef"), 1);
    auto set = [&](const char* name, float v) { glUniform1f(glGetUniformLocation(prog, name), v); };
    glUniform2f(glGetUniformLocation(prog, "uInSize"), float(in.width), float(in.height));
    glUniform2f(glGetUniformLocation(prog, "uOutSize"), float(w), float(h));
    glUniform2f(glGetUniformLocation(prog, "uScale"), float(in.width) / float(w), float(in.height) / float(h));
    set("uDetectRatio", c.kDetectRatio);
    set("uDetectThres", c.kDetectThres);
    set("uMinContrastRatio", c.kMinContrastRatio);
    set("uRatioNorm", c.kRatioNorm);
    set("uSharpStartY", c.kSharpStartY);
    set("uSharpScaleY", c.kSharpScaleY);
    set("uSharpStrengthMin", c.kSharpStrengthMin);
    set("uSharpStrengthScale", c.kSharpStrengthScale);
    set("uSharpLimitMin", c.kSharpLimitMin);
    set("uSharpLimitScale", c.kSharpLimitScale);
    draw(prog, out, source.texture);
    return download(out);
}

// EWA Lanczos-sharp as the stage runs it: Ewa's weights in a 512-texel table packed as VideoFilterStage.ewaTable()
// packs them, two bytes each, (w + 0.25) / 1.25; the weights as Ewa defines them (libplacebo's ewa_lanczossharp), J1
// taken exactly here where Ewa uses Abramowitz & Stegun's polynomial (under 1e-7 apart, below the table's steps).
Picture ewa(const Picture& in, int w, int h, float antiring) {
    const double jincZero3 = 3.2383154841662362076499, blur = 0.98125058372237073562493;
    const double jincZero1 = 1.2196698912665045, pi = 3.14159265358979323846;
    const double radius = double(float(jincZero3 * blur));
    auto jinc = [&](double x) { return x < 1e-8 ? 1.0 : 2.0 * std::cyl_bessel_j(1.0, x * pi) / (x * pi); };
    const int size = 512;
    std::vector<uint8_t> table(size * 4, 0);
    for (int i = 0; i < size; i++) {
        const double x = radius * i / (size - 1);
        const float weight = float(x > radius ? 0.0 : jinc(x / blur) * jinc(x / radius * jincZero1));
        const int v = std::clamp(int((weight + 0.25f) / 1.25f * 65535.0f), 0, 65535);
        table[i * 4] = uint8_t(v >> 8);
        table[i * 4 + 1] = uint8_t(v & 0xFF);
    }
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, table.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    GLuint prog = appProgram("video/ewa.frag");
    Target source = upload(in);
    Target out = target(w, h);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(glGetUniformLocation(prog, "uTable"), 1);
    glUniform2f(glGetUniformLocation(prog, "uInSize"), float(in.width), float(in.height));
    glUniform1f(glGetUniformLocation(prog, "uRadius"), float(radius));
    glUniform1f(glGetUniformLocation(prog, "uAntiring"), antiring);
    draw(prog, out, source.texture);
    return download(out);
}

// Lanczos-3 as the stage runs it, one pass to the size given, with [antiring].
Picture lanczos(const Picture& in, int w, int h, float antiring) {
    GLuint prog = appProgram("video/lanczos.frag");
    Target source = upload(in);
    Target out = target(w, h);
    glUseProgram(prog);
    glUniform2f(glGetUniformLocation(prog, "uInSize"), float(in.width), float(in.height));
    glUniform1f(glGetUniformLocation(prog, "uAntiring"), antiring);
    draw(prog, out, source.texture);
    return download(out);
}

// xBRZ as the stage runs it: corners at (w + 1) × (h + 1), the decisions at the picture's size, then [factor] times
// the size, every texture read nearest; the patterns from [tablePath] (xbrz-table.py, from the app's own Xbrz.kt).
Picture xbrz(const Picture& in, int factor, const std::string& tablePath) {
    const std::string bytes = readFile(tablePath);
    if (bytes.size() != 36 * 25 * 4) fail("the xBRZ table is not 36 × 25 texels");
    GLuint table;
    glGenTextures(1, &table);
    glBindTexture(GL_TEXTURE_2D, table);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 36, 25, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes.data());
    const int w = in.width, h = in.height;
    Target source = upload(in);
    Target corners = target(w + 1, h + 1);
    Target decisions = target(w, h);
    Target out = target(w * factor, h * factor);
    for (GLuint t : {table, source.texture, corners.texture, decisions.texture}) {
        glBindTexture(GL_TEXTURE_2D, t);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    auto size = [&](GLuint p) { glUniform2f(glGetUniformLocation(p, "uSize"), float(w), float(h)); };
    GLuint first = appProgram("xbrz/corners.frag");
    glUseProgram(first);
    size(first);
    draw(first, corners, source.texture);
    GLuint second = appProgram("xbrz/decisions.frag");
    glUseProgram(second);
    size(second);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, corners.texture);
    glUniform1i(glGetUniformLocation(second, "uCorners"), 1);
    draw(second, decisions, source.texture);
    GLuint third = appProgram("xbrz/scale.frag");
    glUseProgram(third);
    size(third);
    glUniform1f(glGetUniformLocation(third, "uFactor"), float(factor));
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, decisions.texture);
    glUniform1i(glGetUniformLocation(third, "uDecisions"), 1);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, table);
    glUniform1i(glGetUniformLocation(third, "uTable"), 2);
    draw(third, out, source.texture);
    return download(out);
}

// The last step of Anime4K and ACNet: [last]'s four channels as the four pixels of each picture pixel, with the
// picture's bilinear doubling.
Picture toTwice(GLuint prog, const Target& source, const Target& last) {
    Target out = target(source.width * 2, source.height * 2);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, last.texture);
    glUniform1i(glGetUniformLocation(prog, "uLast"), 1);
    glUniform2f(glGetUniformLocation(prog, "uLastSize"), float(last.width), float(last.height));
    draw(prog, out, source.texture);
    return download(out);
}

// Anime4K as the stage runs it: four passes into half-float textures taking turns, then Depth-to-Space.
Picture anime4k(const Picture& in) {
    const int w = in.width, h = in.height;
    Target source = upload(in);
    Target turns[2] = {target(w, h, true), target(w, h, true)};
    GLuint input = source.texture;
    for (int i = 0; i < 4; i++) {
        GLuint prog = appProgram("anime4k/conv-" + std::to_string(i + 1) + ".frag");
        glUseProgram(prog);
        glUniform2f(glGetUniformLocation(prog, "uTexel"), 1.0f / w, 1.0f / h);
        draw(prog, turns[i % 2], input);
        input = turns[i % 2].texture;
    }
    return toTwice(appProgram("anime4k/depth-to-space.frag"), source, turns[1]);
}

// ACNet as the stage runs it: eleven passes over two pairs of half-float textures, then the pixel shuffle.
Picture acnet(const Picture& in) {
    const int w = in.width, h = in.height;
    Target source = upload(in);
    Target pairs[2][2] = {{target(w, h, true), target(w, h, true)}, {target(w, h, true), target(w, h, true)}};
    for (int i = 0; i < 11; i++) {
        GLuint prog = appProgram("acnet/conv-" + std::to_string(i + 1) + ".frag");
        int layer = i < 2 ? 0 : (i - 2) / 2 + 1;
        Target* into = pairs[layer % 2];
        Target* from = pairs[1 - layer % 2];
        glUseProgram(prog);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, from[1].texture);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        glUniform2f(glGetUniformLocation(prog, "uTexel"), 1.0f / w, 1.0f / h);
        glUniform1i(glGetUniformLocation(prog, "uTex1"), 1);
        glUniform1i(glGetUniformLocation(prog, "uPicture"), 2);
        draw(prog, into[i % 2], layer == 0 ? source.texture : from[0].texture);
    }
    return toTwice(appProgram("acnet/pixel-shuffle.frag"), source, pairs[1][0]);
}

int compare(const Picture& a, const Picture& b, int maxAllowed, double meanAllowed, int border) {
    if (a.width != b.width || a.height != b.height) fail("the pictures differ in size");
    int worst = 0;
    long long sum = 0, count = 0;
    for (int y = border; y < a.height - border; y++) {
        for (int x = border; x < a.width - border; x++) {
            for (int c = 0; c < 3; c++) {
                size_t i = (size_t(y) * a.width + x) * 3 + c;
                int d = std::abs(int(a.rgb[i]) - int(b.rgb[i]));
                worst = std::max(worst, d);
                sum += d;
                count++;
            }
        }
    }
    double mean = double(sum) / double(count);
    std::printf("max %d, mean %.4f (allowed %d, %.4f)\n", worst, mean, maxAllowed, meanAllowed);
    return worst <= maxAllowed && mean <= meanAllowed ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    const std::vector<std::string> a(argv + 1, argv + argc);
    if (a.size() >= 4 && a[0] == "picture") {
        writePpm(a[1], testPicture(std::stoi(a[2]), std::stoi(a[3]), a.size() > 4 && a[4] == "gray"));
        return 0;
    }
    if ((a.size() == 5 || a.size() == 6) && a[0] == "compare") {
        return compare(readPpm(a[1]), readPpm(a[2]), std::stoi(a[3]), std::stod(a[4]),
                       a.size() == 6 ? std::stoi(a[5]) : 0);
    }
    startGl();
    auto sized = [&](const char* mode) { return a.size() == 5 && a[0] == mode; };
    if (sized("plain")) writePpm(a[2], plain(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    else if (sized("gsr")) writePpm(a[2], gsr(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    else if (sized("fsr")) writePpm(a[2], fsr(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    else if (sized("fsr-easu")) writePpm(a[2], fsr(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4]), true));
    else if (a.size() == 6 && a[0] == "ewa")
        writePpm(a[2], ewa(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4]), std::stof(a[5])));
    else if (a.size() == 6 && a[0] == "lanczos")
        writePpm(a[2], lanczos(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4]), std::stof(a[5])));
    else if (sized("nis")) writePpm(a[2], nis(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    else if (a.size() == 6 && a[0] == "gsr-qualcomm")
        writePpm(a[3], gsrQualcomm(a[1], readPpm(a[2]), std::stoi(a[4]), std::stoi(a[5])));
    else if (a.size() == 3 && a[0] == "fsr-rcas") writePpm(a[2], rcasOnly(readPpm(a[1])));
    else if (a.size() == 8 && a[0] == "temporal")
        writePpm(a[4], temporal(readPpm(a[1]), readPpm(a[2]), readPpm(a[3]), a[5] == "bwdif", a[6] == "1",
                                a[7] == "1"));
    else if (a.size() == 5 && a[0] == "xbrz") writePpm(a[2], xbrz(readPpm(a[1]), std::stoi(a[3]), a[4]));
    else if (a.size() == 6 && a[0] == "yuv") {
        const double kr = a[4] == "601" ? 0.299 : a[4] == "709" ? 0.2126 : 0.2627;
        const double kb = a[4] == "601" ? 0.114 : a[4] == "709" ? 0.0722 : 0.0593;
        writePpm(a[5], yuv(a[1], std::stoi(a[2]), std::stoi(a[3]), kr, kb));
    } else if (a.size() == 5 && a[0] == "deinterlace")
        writePpm(a[2], deinterlace(readPpm(a[1]), std::stoi(a[3]), a[4] == "1"));
    else if (a.size() == 3 && a[0] == "anime4k") writePpm(a[2], anime4k(readPpm(a[1])));
    else if (a.size() == 3 && a[0] == "acnet") writePpm(a[2], acnet(readPpm(a[1])));
    else fail("usage: see the head of tools/shader-check/check.cpp");
    if (glGetError() != GL_NO_ERROR) fail("GL error");
    return 0;
}
