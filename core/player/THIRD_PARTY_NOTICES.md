# Third-party notices — `:core:player`

## Converted code

| Source | Licence | Used for |
| --- | --- | --- |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_yadif.c` (`filter_line_c`, `filter_edges`, `filter_slice`) — © 2006-2011 Michael Niedermayer, 2010 James Darnley | LGPL-2.1-or-later | `Deinterlacer` YADIF, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_bwdif.c` (`filter_line_c`, `filter_edge`, `filter_slice`) — © 2016 Thomas Mundt; YADIF as above; the Weston 3-field coefficients © 2012 British Broadcasting Corporation (Jim Easterbrook, after Martin Weston, BBC R&D) | LGPL-2.1-or-later | `Deinterlacer` BWDIF, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_idet.c` (`filter`, `ff_idet_filter_line_c`) — © 2012 Michael Niedermayer | LGPL-2.1-or-later | `FieldOrder` — telling the field order from the pictures |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_fieldmatch.c` and `vf_decimate.c` — © 2012 Fredrik Mellbin, 2013 Clément Bœsch, after VapourSynth's VFM and VDecimate | LGPL-2.1-or-later | `Telecine` — 3:2 pulldown removal, and the app's Film mode written from it |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) `src/filters.c` (`ewa_lanczossharp`, `jinc`, `pl_filter_sample`) — © Niklas Haas and the libplacebo authors; blur after Nicolas Robidoux | LGPL-2.1-or-later | `Ewa` — the EWA Lanczos-sharp weights, and the app's GL pass reading them |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/sampling.c` (`pl_shader_deband`, `PL_DEBAND_DEFAULTS`) and `src/shaders.c` (`sh_prng`, after the pcg3d hash of Mark Jarzynski and Marc Olano, JCGT 9(3), 2020) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `Deband`, and the app's GL pass written from it (`stage/src/main/glsl/video/deband*.{glsl,frag}`; the GLSL ES 1.00 variant draws its random numbers with Dave Hoskins' hash without sine, see `app/THIRD-PARTY-NOTICE.hash-without-sine.txt`) |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/colorspace.c` (`pl_shader_color_map_ex`, `pl_shader_linearize`, `pl_shader_delinearize`, `pl_shader_detect_peak`, `update_peak_buf`, `PL_PEAK_DETECT_DEFAULTS`), `src/tone_mapping.c` (`pl_tone_map_bt2390`, `pl_tone_map_params_infer`, `pl_tone_map_sample`) and `src/colorspace.c` (`pl_color_space_nominal_luma_ex`, `pl_color_space_infer_map`, `pl_hdr_rescale`, `pl_get_rgb2xyz_matrix`, `pl_ipt_rgb2lms`, the IPT matrices after Ebner and Fairchild, 1998) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `ToneMap` and `PeakDetect`, and the app's GL passes written from them (`stage/src/main/glsl/video/{hdr.glsl,tonemap.frag,peak.frag}`); the constants of SMPTE ST 2084 and ITU-R BT.2100 as libplacebo holds them |
| [Media3](https://github.com/androidx/media) `libraries/effect` (`DefaultShaderProgram`'s BT.2020 limited-range matrix and `fragment_shader_transformation_external_yuv_es3.glsl`'s YUV offsets) — © The Android Open Source Project | Apache-2.0 | how `stage/src/main/glsl/video/copy-y2y-es3.frag` reads a decoder's 10-bit HDR frame through GL_EXT_YUV_target; `YuvMatrix.toRgbTenBit` is held to Media3's matrix |
| [NVIDIA Image Scaling SDK](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling) v1.0.3 `NIS_Scaler.h` (NVScaler) and `NIS_Config.h` (settings, coefficient tables) — © 2022 NVIDIA Corporation | MIT | `Nis`, and the app's GL pass written from it (licence text: `app/THIRD-PARTY-NOTICE.NVIDIA-Image-Scaling.txt`) |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_minterpolate.c` and `motion_estimation.c` (bilateral EPZS search, OBMC, `obmc_linear32`, scene change) — © 2014-2015 Michael Niedermayer, 2016 Davinder Singh | LGPL-2.1-or-later | `MotionInterpolation`, and the app's GL pass written from it |

The Bessel function J1 in `Ewa` follows Abramowitz & Stegun, *Handbook of Mathematical Functions*, 9.4.4 and 9.4.6.

## Test fixtures

`src/test/resources/deinterlace/` — the `input*.gray` files are synthetic, written by `make-input.py` beside
them; the `yadif-*.gray` and `bwdif-*.gray` files and the `.idet.txt` verdicts are FFmpeg 6.0's output for them,
made with the commands in that script's header. `src/test/resources/telecine/` likewise: `telecined*.gray.gz` written by
`make-input.py` there, `fieldmatch*.txt` and `decimate.txt` FFmpeg 6.0's debug lines for them.
`src/test/resources/ewa/weights.txt` — printed by `oracle.c` beside it: libplacebo's `ewa_lanczossharp` evaluated with the C
library's `j1`.
`src/test/resources/nis/` — `in.gray` synthetic; `expected.txt.gz` NVIDIA's own NIS_Scaler.h run on the processor by
`harness.cpp`, made by `make-expected.sh`, which fetches the header.
`src/test/resources/deband/` — `input.ppm` written by `make-input.py` there; the other `.ppm` files libplacebo 7.360's own
`pl_shader_deband` for it, run by libplacebo on Mesa 26.2's OpenGL (llvmpipe) through `tools/shader-check/placebo.cpp`,
made by `make-expected.sh`.
`src/test/resources/tonemap/` — `input.ppm`, `dimmer.ppm`, `darker.ppm` and `dark.ppm` (16-bit) written by `make-input.py`
there; `pq-*.ppm`, `hlg.ppm`, `curve.txt`, `peak.txt` and `peak-hlg.txt` libplacebo 7.360's own colour mapping (BT.2390,
gamut by hard clip), `pl_tone_map_sample` and peak detection for them, run by libplacebo on Mesa 26.2's OpenGL (llvmpipe)
through `tools/shader-check/placebo.cpp`, made by `make-expected.sh`.
`src/test/resources/minterpolate/` — `input.gray.gz` written by `make-input.py` there, `expected.gray.gz` FFmpeg 6.0's
`minterpolate` output for it, made with the command in that script's header.
