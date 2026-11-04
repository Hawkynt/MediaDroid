// The desktop check of the app's own upscaling shaders (app/src/main/glsl) against third-party oracles: the shaders
// run headless on Mesa (EGL without a display, OpenGL ES 3.2 on llvmpipe) the way the filters' stage runs them, and
// their output is compared with what the original produces. See README.md.
//
//   shader_check picture <out.ppm> <width> <height> [gray]  the test picture
//   shader_check gsr <in.ppm> <out.ppm> <width> <height>    glsl/video/sgsr.frag to that size
//   shader_check gsr-qualcomm <shader> <in.ppm> <out.ppm> <width> <height>
//                                                           Qualcomm's sgsr1_shader_mobile.frag, unchanged but for
//                                                           its version line and what Mesa holds to the letter
//   shader_check plain <in.ppm> <out.ppm> <width> <height>  bilinear scaling, to show an upscaler changes anything
//   shader_check acnet <in.ppm> <out.ppm>                   glsl/acnet to twice the size, as the stage runs it
//   shader_check compare <a.ppm> <b.ppm> <max> <mean>       fails when they differ by more (in 8-bit steps)

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Picture {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgb;  // rows top to bottom
};

[[noreturn]] void fail(const std::string& what) {
    std::fprintf(stderr, "shader_check: %s\n", what.c_str());
    std::exit(2);
}

Picture readPpm(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::string magic;
    int maxValue = 0;
    Picture p;
    in >> magic >> p.width >> p.height >> maxValue;
    in.get();
    if (magic != "P6" || maxValue != 255) fail("not an 8-bit P6 picture: " + path);
    p.rgb.resize(size_t(p.width) * p.height * 3);
    in.read(reinterpret_cast<char*>(p.rgb.data()), std::streamsize(p.rgb.size()));
    if (!in) fail("short picture: " + path);
    return p;
}

void writePpm(const std::string& path, const Picture& p) {
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << p.width << " " << p.height << "\n255\n";
    out.write(reinterpret_cast<const char*>(p.rgb.data()), std::streamsize(p.rgb.size()));
}

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

std::string readFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) fail("cannot read " + path);
    std::stringstream s;
    s << in.rdbuf();
    return s.str();
}

// A shader of the app's as the shaderSources task assembles it: each `#include "name.glsl"` replaced by that file,
// beside the including one.
std::string appShader(const std::string& relative) {
    const std::string path = std::string(GLSL_DIR) + "/" + relative;
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

struct Target {
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int width = 0;
    int height = 0;
};

// A texture to draw into: 8-bit as the stage's frames, or half floats as its Anime4K and ACNet textures.
Target target(int w, int h, bool half) {
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
    Target t = target(p.width, p.height, false);
    std::vector<uint8_t> rgba(size_t(p.width) * p.height * 4);
    for (int y = 0; y < p.height; y++) {
        for (int x = 0; x < p.width; x++) {
            const uint8_t* from = &p.rgb[(size_t(p.height - 1 - y) * p.width + x) * 3];
            uint8_t* to = &rgba[(size_t(y) * p.width + x) * 4];
            to[0] = from[0];
            to[1] = from[1];
            to[2] = from[2];
            to[3] = 255;
        }
    }
    glBindTexture(GL_TEXTURE_2D, t.texture);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.width, p.height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    return t;
}

Picture download(const Target& t) {
    glBindFramebuffer(GL_FRAMEBUFFER, t.framebuffer);
    std::vector<uint8_t> rgba(size_t(t.width) * t.height * 4);
    glReadPixels(0, 0, t.width, t.height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    Picture p{t.width, t.height, std::vector<uint8_t>(size_t(t.width) * t.height * 3)};
    for (int y = 0; y < t.height; y++) {
        for (int x = 0; x < t.width; x++) {
            for (int c = 0; c < 3; c++) {
                p.rgb[(size_t(t.height - 1 - y) * t.width + x) * 3 + c] = rgba[(size_t(y) * t.width + x) * 4 + c];
            }
        }
    }
    return p;
}

// One pass over the whole of [into], as the stage's run(): the input on unit 0 as uTex, the identity matrix.
void draw(GLuint prog, const Target& into, GLuint input) {
    static const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
    static const float quadTex[] = {0, 0, 1, 0, 0, 1, 1, 1};
    static const float identity[] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glBindFramebuffer(GL_FRAMEBUFFER, into.framebuffer);
    glViewport(0, 0, into.width, into.height);
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, input);
    glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
    glUniformMatrix4fv(glGetUniformLocation(prog, "uTexMatrix"), 1, GL_FALSE, identity);
    glUniform1f(glGetUniformLocation(prog, "uGrain"), 0.0f);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 0, quadTex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

Picture gsr(const Picture& in, int w, int h) {
    GLuint prog = program(appShader("video/vertex.vert"), appShader("video/sgsr.frag"));
    Target source = upload(in);
    Target out = target(w, h, false);
    glUseProgram(prog);
    glUniform2f(glGetUniformLocation(prog, "uInSize"), float(in.width), float(in.height));
    draw(prog, out, source.texture);
    return download(out);
}

// Bilinear scaling, the screen's own: what an upscaler has to differ from for a match with its oracle to mean much.
Picture plain(const Picture& in, int w, int h) {
    GLuint prog = program(appShader("video/vertex.vert"),
                          "precision mediump float;\nuniform sampler2D uTex;\nvarying vec2 vTex;\n"
                          "void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }\n");
    Target source = upload(in);
    Target out = target(w, h, false);
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
    Target out = target(w, h, false);
    glUseProgram(prog);
    glUniform4f(glGetUniformLocation(prog, "ViewportInfo"), 1.0f / in.width, 1.0f / in.height, float(in.width),
                float(in.height));
    glUniform1i(glGetUniformLocation(prog, "ps0"), 0);
    draw(prog, out, source.texture);
    return download(out);
}

// ACNet as VideoFilterStage runs it: eleven passes over two pairs of half-float textures, then the pixel shuffle.
Picture acnet(const Picture& in) {
    const std::string vertex = appShader("video/vertex.vert");
    const int w = in.width, h = in.height;
    Target source = upload(in);
    Target pairs[2][2] = {{target(w, h, true), target(w, h, true)}, {target(w, h, true), target(w, h, true)}};
    for (int i = 0; i < 11; i++) {
        GLuint prog = program(vertex, appShader("acnet/conv-" + std::to_string(i + 1) + ".frag"));
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
    GLuint shuffle = program(vertex, appShader("acnet/pixel-shuffle.frag"));
    Target out = target(w * 2, h * 2, false);
    glUseProgram(shuffle);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, pairs[1][0].texture);
    glUniform1i(glGetUniformLocation(shuffle, "uLast"), 1);
    glUniform2f(glGetUniformLocation(shuffle, "uLastSize"), float(w), float(h));
    draw(shuffle, out, source.texture);
    return download(out);
}

int compare(const Picture& a, const Picture& b, int maxAllowed, double meanAllowed) {
    if (a.width != b.width || a.height != b.height) fail("the pictures differ in size");
    int worst = 0;
    long long sum = 0;
    for (size_t i = 0; i < a.rgb.size(); i++) {
        int d = std::abs(int(a.rgb[i]) - int(b.rgb[i]));
        worst = std::max(worst, d);
        sum += d;
    }
    double mean = double(sum) / double(a.rgb.size());
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
    if (a.size() == 5 && a[0] == "compare") {
        return compare(readPpm(a[1]), readPpm(a[2]), std::stoi(a[3]), std::stod(a[4]));
    }
    startGl();
    if (a.size() == 5 && a[0] == "gsr") {
        writePpm(a[2], gsr(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    } else if (a.size() == 5 && a[0] == "plain") {
        writePpm(a[2], plain(readPpm(a[1]), std::stoi(a[3]), std::stoi(a[4])));
    } else if (a.size() == 6 && a[0] == "gsr-qualcomm") {
        writePpm(a[3], gsrQualcomm(a[1], readPpm(a[2]), std::stoi(a[4]), std::stoi(a[5])));
    } else if (a.size() == 3 && a[0] == "acnet") {
        writePpm(a[2], acnet(readPpm(a[1])));
    } else {
        fail("usage: see the head of tools/shader-check/check.cpp");
    }
    if (glGetError() != GL_NO_ERROR) fail("GL error");
    return 0;
}
