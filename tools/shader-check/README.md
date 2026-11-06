# Shader check on the desktop

The app's upscaling shaders (`stage/src/main/glsl`) against their originals, without a phone. `shader_check` runs the
app's shaders headless on Mesa — EGL without a display, OpenGL ES 3.2 — the way `VideoFilterStage` runs them (the
picture's top row where v is 1, 8-bit frames between passes, half-float textures between the networks' passes);
`shader_oracle` runs AMD's and NVIDIA's originals as compute shaders on desktop OpenGL 4.5, as their own samples run
them; Anime4K's and ACNet's mpv shaders run in libplacebo, mpv's renderer, through FFmpeg's `libplacebo` filter.
`oracle.sh` fetches each original at a fixed commit, builds both programs and compares what they draw from the same
test picture (flat discs in every colour, thin lines at several angles, a gradient and one-pixel detail; fixed, no clock
or randomness). Each upscaler is also compared with plain bilinear scaling, which it has to differ from: a match with an
oracle that bilinear scaling would pass as well proves nothing.

| Upscaler | Original | How it runs |
|----------|----------|-------------|
| GSR (`glsl/video/sgsr.frag`) | Qualcomm's `sgsr1_shader_mobile.frag`, quic/snapdragon-gsr `d926f07` (BSD-3-Clause) | on Mesa beside ours, unchanged but for its `#version` (`textureGather` is ES 3.1), a `highp` on a constructor and `mode` made `const` — Adreno takes those, Mesa holds them to the letter |
| FSR (`fsr-easu.frag`, `fsr-rcas.frag`) | AMD's `ffx_a.h` and `ffx_fsr1.h`, GPUOpen-Effects/FidelityFX-FSR `a21ffb8` (MIT) | as the Vulkan sample's full-float path (`FSR_Pass.glsl`, `SAMPLE_SLOW_FALLBACK`): `FsrEasuF` with `textureGather`, `FsrRcasF` with `texelFetch`, the constants from `FsrEasuCon` and `FsrRcasCon` on the processor; the frame between them 8-bit, as the app's is |
| NIS (`nis.frag`) | NVIDIA's `NIS_Scaler.h` and `NIS_Config.h`, NVIDIAGameWorks/NVIDIAImageScaling `35e13ba`, SDK v1.0.3 (MIT) | as `NIS_Main.glsl` runs `NVScaler` at sharpness 0.5, settings from `NVScalerUpdateConfig`; only the five texture macros written for OpenGL's combined samplers instead of Vulkan's separate ones. Ours gets its coefficients packed as `nisTable()` packs `Nis`'s, from `NIS_Config.h` itself (`NisTest` holds `Nis` to NVIDIA) |
| Anime4K (`glsl/anime4k`) | `Anime4K_Upscale_CNN_x2_S.glsl` v3.2, bloc97/Anime4K `7684e95` (MIT) | in libplacebo on RGB, where its MAIN hook sees the values the stage does |
| Lanczos, EWA (`lanczos.frag`, `ewa.frag`) | libplacebo's `lanczos` and `ewa_lanczossharp` | their kernels, antiringing off on both sides (ours pulls into the four nearest pixels' range, libplacebo's weighs a soft minimum and maximum — a different rule, so not compared) |
| xBRZ (`glsl/xbrz`) | the original's own output: core/imaging's reference pictures, which `Xbrz` is held to as well | the three passes as the stage runs them, every factor 2 to 6; the patterns read from the app's `Xbrz.kt` (`xbrz-table.py`) |
| YADIF, BWDIF (`temporal.frag`) | FFmpeg's `yadif` and `bwdif` on planar RGB | three interlaced frames (`fields.py`: half still, half moving), both field orders and both fields, PAL and NTSC sizes; once more with every read addressed 1/256 texel off, which nearest sampling has to absorb |
| Debanding (`deband-es3.frag`, `deband.frag`) | libplacebo's own `pl_shader_deband` (7.360) | run by libplacebo itself on the same Mesa's desktop OpenGL (`placebo.cpp`), on a picture of 8-bit steps (core/player's fixture) and the test picture, one to four iterations, with and without grain, frame indices 0 to 255; the frame held both ways up. The GLSL ES 1.00 variant draws other random numbers (no unsigned integers for pcg3d), so it is held only to moving no value further than the threshold lets it |
| HDR tone mapping (`tonemap.frag`, `hdr.glsl`) | libplacebo's own `pl_shader_color_map_ex` (7.360) with `pl_tone_map_bt2390` and `pl_gamut_map_clip` | run by libplacebo itself on the same Mesa (`placebo.cpp`) on core/player's HDR picture (16 bits: a grey ramp over the whole range, BT.2020's primaries and secondaries, mid colours, near-black, highlights), read as PQ without metadata and mastered at 400, 1000 and 4000 nits, and as HLG; ours from the stage's half-float HDR frame, its uniforms worked out as libplacebo works out its own |
| HDR peak detection (`peak.frag`) | libplacebo's own `pl_shader_detect_peak` (its defaults) | the same picture, a little darker, a good deal darker and nearly black, each as a scene's first frame (libplacebo's smoothing takes that as it is), PQ and HLG: our blocks read back as `PeakDetect` takes them, the peak and the average in PQ |
| Dithering (`dither-es3.frag`, `dither.frag`, `dither.glsl`) | libplacebo's own `pl_shader_dither` (7.360, its defaults: blue noise, 64 × 64) | run by libplacebo itself on the same Mesa (`placebo.cpp`) on 16-bit pictures (core/player's gradients, the HDR picture's coded values) into an 8-bit target; ours with the matrix libplacebo makes (`placebo_oracle bluenoise`), from a float frame and from half floats as the stages hold them, the frame both ways up, GLSL ES 3.00 and 1.00. With the matrix turned on its side it has to differ |
| YUV to RGB (`yuv.frag`) | FFmpeg's own conversion (swscale, `accurate_rnd`, bilinear chroma) | a yuv420p frame uploaded as `takeYuv` uploads it, BT.601 and BT.709 limited range, the matrix as `YuvMatrix.toRgb` computes it; full range (MJPEG's) squeezed first by the FFmpeg decoder's own `mediadroid_range.h`, which unsqueezed has to fail |
| ACNet (`glsl/acnet`) | `acnet_f8b4.glsl`, TianZerL/ACNetGLSL `c6e5940` (MIT) | in libplacebo on 16-bit YUV (BT.709, full range), grey and in colour, the chroma scaled bilinearly as the stage's doubling does — with another chroma scaler it has to differ, so that setting is known to take |

1. Mesa's EGL, GLES and OpenGL (`pkg-config egl glesv2 opengl`) and libplacebo's development files (`pkg-config
   libplacebo`); for Anime4K and ACNet, FFmpeg built with libplacebo and a Vulkan driver (FFmpeg's filter makes its own
   Vulkan device). Without a GPU Mesa can draw on the processor: `LIBGL_ALWAYS_SOFTWARE=1`, and where another vendor's
   EGL comes first, `__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json`.
2. `tools/shader-check/oracle.sh <work folder>` — fetches, builds, compares; fails when a comparison does not hold.

On an Intel HD Graphics 520 laptop (Mesa 26.2, libplacebo 7.360), per 8-bit value:

| Check | Max | Mean |
|-------|-----|------|
| GSR at 1.5×, 2× and 3.4× | 0 | 0 |
| FSR's EASU at 1.5×, 2× and 3.4× | 1 | ≤ 0.0002 |
| FSR's RCAS on the same picture | 0 | 0 |
| FSR, both passes (EASU's roundings in the 8-bit frame, sharpened by RCAS) | ≤ 4 | ≤ 0.0007 |
| NIS at 1.25×, 1.5×, 1.75× and 2× | 1 | ≤ 0.0063 |
| Anime4K at 2× | 1 | 0.056 |
| ACNet at 2×, grey / in colour | 1 | 0.040 / 0.021 |
| Lanczos and EWA kernels at 1.5× and 2× | ≤ 2 | ≤ 0.083 |
| xBRZ, three pictures at 2× to 6× | 0 | 0 |
| YADIF and BWDIF, every case, and read 1/256 texel off | 0 | 0 |
| YUV to RGB, grey / in colour | 0 / 1 | 0 / 0.005 |
| YUV to RGB from full range, grey / in colour (unsqueezed: 20 / 20) | 1 / 2 | 0.15 / 0.33 |

HDR, on Mesa 26.2's llvmpipe (libplacebo 7.360 on the same): tone mapping per 8-bit value, peak detection in 14-bit PQ
steps (1/16383):

| Check | Max | Mean |
|-------|-----|------|
| Tone mapping, PQ without metadata / at 400 / 1000 / 4000 nits | 1 | 0.033 / 0.027 / 0.033 / 0.028 |
| Tone mapping, HLG | 1 | 0.023 |
| Peak detection, peak / average (PQ and HLG) | 5 / 2.5 steps | — |

The tone mapping's ones are where libplacebo's curve, read from a 256-entry table of 16-bit floats, and ours, computed,
round a value to different sides. The peak's steps come from the stage's HDR frame of half floats, which hold a coded
value near the top to about four 14-bit steps (libplacebo reads 16 bits; the desktop stage does too); core/player's
`PeakDetect` agrees with libplacebo over a whole scene, smoothing and scene changes included, within two
(`PeakDetectTest`), its `ToneMap` within one 8-bit step (`ToneMapTest`).

Debanding, on Mesa 26.2's llvmpipe (libplacebo 7.360 on the same), per 8-bit value:

| Check | Max | Mean |
|-------|-----|------|
| Deband, ES 3.00, with grain (1 to 4 iterations) | 0 | 0 |
| Deband, ES 3.00, without grain | 1 | ≤ 0.0075 |
| Deband, ES 1.00 (other random numbers), without grain | 2 | ≤ 0.12 |

Where the ES 3.00 pass is not byte for byte, it is without grain, in one channel, where an average of four lands on a
half step and the shader compiler's float arithmetic rounds it the other way; the points it samples are libplacebo's
(checked against their exact positions: none lies near a texel's edge). Core/player's `Deband` agrees with libplacebo
byte for byte (`DebandTest`). What the check found and the pass now does: each texel requantised to its byte times the
float nearest 1/255, as libplacebo's output on Mesa rounds — read as the chip hands it over, about one value in eight
rounded the other way (mean 0.13); a true division by 255 still left 0.03.

Dithering, on Mesa 26.2's llvmpipe (libplacebo 7.360 on the same), per 8-bit value:

| Check | Max | Mean |
|-------|-----|------|
| Dither, ES 3.00 and ES 1.00, from a float frame, either way up | 0 | 0 |
| Dither from half floats (as the stages hold the picture) | 1 | ≤ 0.036 |

From half floats a value within half-float rounding of where its threshold tips it goes the other way; libplacebo's own
renderer holds its frames in half floats too. Core/player's `Dither` agrees with libplacebo byte for byte, the matrix
and the picture (`DitherTest`). What the check found and the pass now does: its samplers are `highp` — ES gives a
sampler `lowp` by default, and Mesa then read the float frame in half floats even from a full-float texture, one value
in thirty a level low, exact 8-bit levels among them.

Outermost pixels are left out where the originals read past the picture: AMD's RCAS fetches beyond the edge (one
pixel), NVIDIA's tile loader wraps an unsigned index at the top and left (eight pixels, as `NisTest` does).

What the check found and the app now does as the originals do: FFmpeg's frames converted with 16/255 and 128/255
taken off (not 16/256 and 0.5 — every grey a step towards blue), with exact matrices (BT.2020 had a 10-bit luma
scale), and, where a file names no colour space, BT.601 for SD rather than BT.709 — up to 35/255 off before; the passes that read exact pixels (YADIF, BWDIF,
Film's weave, the luma read back, xBRZ) sample them nearest — through linear filtering a texel addressed 1/256 off
moved YADIF by up to 139/255, which nearest sampling absorbs; Film's weave runs at high precision, as half floats
cannot address 1080 rows to the row; xBRZ finds its block half a pixel in, so a reciprocal landing a hair low cannot
pick the one before; FSR uses AMD's approximate reciprocals rather than
exact ones (`glsl/video/ffx-approx.glsl`, the same integer arithmetic on the float's two fields, since GLSL ES 1.00
cannot read its bits) — up to 23/255 apart before — and its RCAS lets a colour whose neighbours are all 0 or all 1
drop out of the lobe, as AMD's NaN does on GPUs; NIS gets its scale divided on the processor, as NVIDIA's
`kScaleX`/`kScaleY` are — divided on the GPU, 240 / 480 landed a hair off 0.5 and picked the filter phase below, up to
10/255 apart.
