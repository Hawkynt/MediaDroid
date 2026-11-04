# Shader check on the desktop

The app's upscaling shaders (`app/src/main/glsl`) against third-party oracles, without a phone: `shader_check` runs
them headless on Mesa — EGL without a display, OpenGL ES 3.2 — the way `VideoFilterStage` runs them (the picture's top
row where v is 1, half-float textures between ACNet's passes), and `oracle.sh` compares what they draw with what the
originals draw from the same test picture (flat discs in every colour, thin lines at several angles, a gradient and
one-pixel detail; fixed, no clock or randomness). Each upscaler is also compared with plain bilinear scaling, which it
has to differ from: a match with an oracle that bilinear scaling would pass as well proves nothing.

| Upscaler | Oracle | How the oracle runs |
|----------|--------|---------------------|
| GSR (`glsl/video/sgsr.frag`) | Qualcomm's `sgsr1_shader_mobile.frag` at quic/snapdragon-gsr `d926f07` (BSD-3-Clause), fetched | on Mesa beside ours, unchanged but for its `#version` (`textureGather` is ES 3.1), a `highp` on a constructor and `mode` made `const` — Adreno takes those, Mesa holds them to the letter |
| ACNet (`glsl/acnet`) | ACNetGLSL's `acnet_f8b4.glsl` at TianZerL/ACNetGLSL `c6e5940` (MIT) | in libplacebo, mpv's renderer, through FFmpeg's `libplacebo` filter on Vulkan; the picture grey, so mpv's LUMA plane and the stage's luma are the same value |

1. Mesa's EGL and GLES (`pkg-config egl glesv2`), and for ACNet FFmpeg built with libplacebo and a Vulkan driver.
2. `cmake -S tools/shader-check -B build/shader-check` and `cmake --build build/shader-check`.
3. `tools/shader-check/oracle.sh build/shader-check <work folder> [acnet_f8b4.glsl]` — ACNet's original from
   <https://github.com/TianZerL/ACNetGLSL/blob/c6e5940cef3d2c15bebbe407da6b4e0c68ce50ad/glsl/acnet/acnet_f8b4.glsl>;
   without it only GSR is checked.

On an Intel HD Graphics 520 laptop (Mesa 26.2, libplacebo 7.360, FFmpeg's filter making its own Vulkan device), per
8-bit value:

| Check | Max | Mean |
|-------|-----|------|
| GSR at 1.5×, 2× and 3.4× against Qualcomm's | 0 | 0 |
| GSR against bilinear | 24 | 2.19 |
| ACNet at 2× against libplacebo | 1 | 0.01 |
| ACNet against bilinear | 115 | 4.27 |

ACNet's check covers the network on the luma; how the stage puts that luma back into a coloured picture (its
bilinear doubling, BT.709 luma) is the stage's own and has no counterpart in mpv, which scales the chroma itself.
