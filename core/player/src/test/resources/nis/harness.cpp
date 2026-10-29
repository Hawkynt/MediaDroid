// Runs NVIDIA's NIS_Scaler.h (NVScaler, SDR, fp32) on the CPU: every thread of a block three times, so
// the shared tile and edge map are whole before the last run writes the output. Input: a raw 8-bit grey
// picture (as RGB with r = g = b), output: the scaled picture's luma as floats, one per line.
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>
#include <algorithm>
#include "NIS_Config.h"
struct float3 { float x, y, z; float3(float a = 0, float b = 0, float c = 0) : x(a), y(b), z(c) {} };
struct float4 {
    float x, y, z, w;
    float4(float a = 0, float b = 0, float c = 0, float d = 0) : x(a), y(b), z(c), w(d) {}
    float3 xyz() const { return float3(x, y, z); }
    float4 operator*(float s) const { return float4(x * s, y * s, z * s, w * s); }
};
struct int2 { int x, y; int2(int a = 0, int b = 0) : x(a), y(b) {} };
struct uint2 { unsigned x, y; uint2(unsigned a = 0, unsigned b = 0) : x(a), y(b) {} };
static float lerp(float a, float b, float t) { return a + (b - a) * t; }
static float4 lerp(float4 a, float4 b, float t) {
    return float4(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t), lerp(a.w, b.w, t));
}
static float saturate(float v) { return std::min(1.0f, std::max(0.0f, v)); }
using std::min; using std::max; using std::abs; using std::floor; using std::ceil;
#define NVF float
#define NVF2 float2
#define NVF3 float3
#define NVF4 float4
#define NVI int
#define NVI2 int2
#define NVU unsigned
#define NVU2 uint2
#define NVB bool
#define NVH NVF
#define NVH4 NVF4
#define NVSHARED static
#define NIS_UNROLL
#define GroupMemoryBarrierWithGroupSync()
struct float2 { float x, y; float2(float a = 0, float b = 0) : x(a), y(b) {} };
static int W, H, OW, OH;
static std::vector<float> img;
static std::vector<float> out;
static float4 sample(float2 p) {
    float fx = p.x * W - 0.5f, fy = p.y * H - 0.5f;
    int x0 = (int) std::floor(fx), y0 = (int) std::floor(fy);
    float ax = fx - x0, ay = fy - y0;
    auto at = [](int x, int y) { x = std::clamp(x, 0, W - 1); y = std::clamp(y, 0, H - 1); return img[y * W + x]; };
    float v = (at(x0, y0) * (1 - ax) + at(x0 + 1, y0) * ax) * (1 - ay) + (at(x0, y0 + 1) * (1 - ax) + at(x0 + 1, y0 + 1) * ax) * ay;
    return float4(v, v, v, 1);
}
static float4 coefLoad(const float (*c)[8], int2 p) {
    return float4(c[p.y][p.x * 4], c[p.y][p.x * 4 + 1], c[p.y][p.x * 4 + 2], c[p.y][p.x * 4 + 3]);
}
#define NVTEX_SAMPLE(x, s, pos) sample(pos)
#define NVTEX_LOAD(x, pos) coefLoad(x, pos)
static bool writing = false;
#define NVTEX_STORE(tex_, at_, val_) do { if (writing && (int) at_.x < OW && (int) at_.y < OH) out[(int) at_.y * OW + (int) at_.x] = val_.x; } while (0)
static float kDetectRatio, kDetectThres, kMinContrastRatio, kRatioNorm, kContrastBoost, kEps, kSharpStartY,
    kSharpScaleY, kSharpStrengthMin, kSharpStrengthScale, kSharpLimitMin, kSharpLimitScale, kScaleX, kScaleY,
    kSrcNormX, kSrcNormY;
static const float (*coef_scaler)[8] = coef_scale;
static const float (*coef_usm_t)[8] = coef_usm;
#define coef_usm coef_usm_t
static int in_texture, out_texture, samplerLinearClamp;
#include "NIS_Scaler_cpu.h"
#undef coef_usm
int main(int argc, char** argv) {
    W = atoi(argv[1]); H = atoi(argv[2]); OW = atoi(argv[3]); OH = atoi(argv[4]);
    float sharpness = atof(argv[5]);
    std::vector<uint8_t> raw(W * H);
    fread(raw.data(), 1, raw.size(), stdin);
    img.resize(W * H);
    for (int i = 0; i < W * H; i++) img[i] = raw[i] / 255.0f;
    out.assign(OW * OH, 0.0f);
    NISConfig c;
    NVScalerUpdateConfig(c, sharpness, 0, 0, W, H, W, H, 0, 0, OW, OH, OW, OH);
    kDetectRatio = c.kDetectRatio; kDetectThres = c.kDetectThres; kMinContrastRatio = c.kMinContrastRatio;
    kRatioNorm = c.kRatioNorm; kContrastBoost = c.kContrastBoost; kEps = c.kEps; kSharpStartY = c.kSharpStartY;
    kSharpScaleY = c.kSharpScaleY; kSharpStrengthMin = c.kSharpStrengthMin; kSharpStrengthScale = c.kSharpStrengthScale;
    kSharpLimitMin = c.kSharpLimitMin; kSharpLimitScale = c.kSharpLimitScale; kScaleX = c.kScaleX; kScaleY = c.kScaleY;
    kSrcNormX = c.kSrcNormX; kSrcNormY = c.kSrcNormY;
    for (unsigned by = 0; by * NIS_BLOCK_HEIGHT < (unsigned) OH; by++)
        for (unsigned bx = 0; bx * NIS_BLOCK_WIDTH < (unsigned) OW; bx++)
            for (int pass = 0; pass < 3; pass++) {
                writing = pass == 2;
                for (unsigned t = 0; t < NIS_THREAD_GROUP_SIZE; t++) NVScaler(uint2(bx, by), t);
            }
    for (float v : out) printf("%.6f\n", v);
}
