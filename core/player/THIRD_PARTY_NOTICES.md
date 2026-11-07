# Third-party notices — `:core:player`

## Converted code

| Source | Licence | Used for |
| --- | --- | --- |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_yadif.c` (`filter_line_c`, `filter_edges`, `filter_slice`) — © 2006-2011 Michael Niedermayer, 2010 James Darnley | LGPL-2.1-or-later | `Deinterlacer` YADIF, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_bwdif.c` (`filter_line_c`, `filter_edge`, `filter_slice`) — © 2016 Thomas Mundt; YADIF as above; the Weston 3-field coefficients © 2012 British Broadcasting Corporation (Jim Easterbrook, after Martin Weston, BBC R&D) | LGPL-2.1-or-later | `Deinterlacer` BWDIF, and the app's GL pass written from it |
| [GStreamer](https://gstreamer.freedesktop.org) gst-plugins-good 1.28 `gst/deinterlace/tvtime/greedyh.c` (`greedyh_scanline_C_planar_y`, `deinterlace_frame_di_greedyh_plane`, the defaults max-comb 5, motion-threshold 25, motion-sense 30) — © 2004 Billy Biggs, 2008, 2010 Sebastian Dröge; tvtime's Greedy High Motion by Tom Barry after DScaler, relicensed LGPL for GStreamer with the authors' permission | LGPL-2.0-or-later | `Deinterlacer` Greedy High Motion, and the app's GL pass written from it |
| [GStreamer](https://gstreamer.freedesktop.org) gst-plugins-good 1.28 `gst/deinterlace/tvtime/tomsmocomp.c` and `tomsmocomp/*.inc` (`TomsMoCompAll.inc`, `WierdBob.inc`, `SearchLoopTop.inc`, `SearchLoopOddA2.inc`, `SearchLoopOddAH2.inc`, `SearchLoop0A.inc`, `SearchLoopBottom.inc`: the MMXEXT search at effort 5) — © 2002 Tom Barry, relicensed LGPL for GStreamer with his permission | LGPL-2.0-or-later | `Deinterlacer` TomsMoComp, and the app's GL pass written from it |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_idet.c` (`filter`, `ff_idet_filter_line_c`) — © 2012 Michael Niedermayer | LGPL-2.1-or-later | `FieldOrder` — telling the field order from the pictures |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_fieldmatch.c` and `vf_decimate.c` — © 2012 Fredrik Mellbin, 2013 Clément Bœsch, after VapourSynth's VFM and VDecimate | LGPL-2.1-or-later | `Telecine` — 3:2 pulldown removal, and the app's Film mode written from it |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) `src/filters.c` (`ewa_lanczossharp`, `jinc`, `pl_filter_sample`) — © Niklas Haas and the libplacebo authors; blur after Nicolas Robidoux | LGPL-2.1-or-later | `Ewa` — the EWA Lanczos-sharp weights, and the app's GL pass reading them |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/sampling.c` (`pl_shader_deband`, `PL_DEBAND_DEFAULTS`) and `src/shaders.c` (`sh_prng`, after the pcg3d hash of Mark Jarzynski and Marc Olano, JCGT 9(3), 2020) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `Deband`, and the app's GL pass written from it (`stage/src/main/glsl/video/deband*.{glsl,frag}`; the GLSL ES 1.00 variant draws its random numbers with Dave Hoskins' hash without sine, see `app/THIRD-PARTY-NOTICE.hash-without-sine.txt`) |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/colorspace.c` (`pl_shader_color_map_ex`, `pl_shader_linearize`, `pl_shader_delinearize`, `pl_shader_detect_peak`, `update_peak_buf`, `PL_PEAK_DETECT_DEFAULTS`), `src/tone_mapping.c` (`pl_tone_map_bt2390`, `pl_tone_map_params_infer`, `pl_tone_map_sample`) and `src/colorspace.c` (`pl_color_space_nominal_luma_ex`, `pl_color_space_infer_map`, `pl_hdr_rescale`, `pl_get_rgb2xyz_matrix`, `pl_ipt_rgb2lms`, the IPT matrices after Ebner and Fairchild, 1998) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `ToneMap` and `PeakDetect`, and the app's GL passes written from them (`stage/src/main/glsl/video/{hdr.glsl,tonemap.frag,peak.frag}`); the constants of SMPTE ST 2084 and ITU-R BT.2100 as libplacebo holds them |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/dithering.c` (`pl_shader_dither`, `PL_DITHER_DEFAULTS`) — © Niklas Haas and the libplacebo authors — and `src/dither.c` (`pl_generate_blue_noise`) — © 2013 Wessel Dankers, from mpv | LGPL-2.1-or-later | `Dither`, and the app's GL pass written from it (`stage/src/main/glsl/video/dither*.{glsl,frag}`) |
| [glibc](https://www.gnu.org/software/libc/) `stdlib/random_r.c` (`__srandom_r`, `__random_r`, the default `TYPE_3` state) — © the Free Software Foundation | LGPL-2.1-or-later | `Dither.GlibcRandom` — the `rand()` that breaks the blue-noise matrix's ties as libplacebo makes it on Linux |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/filters.c` (`spline36`) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `ChromaUpscaling.spline36`, and the app's GL pass written from it (`stage/src/main/glsl/video/yuv-spline.frag`) |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) 7.360 `src/shaders/custom_mpv.c` (`parse_tex`, `parse_buf`, `parse_hook`'s `COMPUTE`, `lookup_var`, `hook_hook`'s binding of embedded textures, buffers and compute dispatch), `src/dispatch.c` (`compute_vertex_attribs`, the buffer blocks' declarations), `src/gpu.c` (`pl_std140_layout`, `pl_std430_layout`, `pl_var_glsl_types`), `src/renderer.c` (`pass_read_image`'s order of the plane hooks) and `src/opengl/formats.c` (the format names and their host types) — © Niklas Haas and the libplacebo authors | LGPL-2.1-or-later | `MpvShader`'s embedded textures (`Texture`, `Format`, `Border`), buffers (`Buffer`, `Variable`) and compute passes (`Compute`, their GLSL), and the stages' hook runner that uploads, binds and dispatches them (`:stage`'s `MpvHooks`) |
| [KrigBilateral](https://gist.github.com/igv/a015fc885d5c22e6891820ad89555637) (revision `038064821c5f`, the mpv user shader) — © Shiandow | LGPL-3.0-or-later | `ChromaUpscaling.krig` and `lowResolutionLuma`, and the app's GL passes written from them (`stage/src/main/glsl/video/chroma-krig-{1,2,3}.frag`) |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_deblock.c` (the weak and strong edge filters, `filter_frame`'s order, the default thresholds) — © 2018 Paul B Mahol, after "A Simple and Efficient Deblocking Algorithm for Low Bit-Rate Video Coding" | LGPL-2.1-or-later | `Deblock`, and the app's GL pass written from it (`stage/src/main/glsl/video/deblock-es3.frag`); every row of a vertical edge judged, where FFmpeg's stop at the first they leave alone |
| [dav1d](https://code.videolan.org/videolan/dav1d) 1.5.0 `src/cdef_tmpl.c` (`cdef_find_dir_c`, `cdef_filter_block_c`, `constrain`), `src/cdef_apply_tmpl.c` (`adjust_strength`) and `src/tables.c` (`dav1d_cdef_directions`) — © 2018 VideoLAN and dav1d authors, Two Orioles, LLC | BSD-2-Clause | `Cdef`, and the app's GL passes written from it (`stage/src/main/glsl/video/cdef-direction-es3.frag`, `cdef-es3.frag`); licence text: `app/THIRD-PARTY-NOTICE.dav1d.txt` |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_atadenoise.c` (`FILTER_ROW`, the parallel unweighted filter, and `config_input`'s thresholds) — © 2015 Paul B Mahol, after Bartovčak and Vrankić, "Video Denoising Based on Adaptive Temporal Averaging" | LGPL-2.1-or-later | `TemporalDenoise`, over three frames, and the app's GL pass written from it (`stage/src/main/glsl/video/temporal-denoise.frag`); the frames either side moved along the motion with `MotionInterpolation`'s search and overlapped blocks (FFmpeg's minterpolate, below) |
| [Media3](https://github.com/androidx/media) `libraries/effect` (`DefaultShaderProgram`'s BT.2020 limited-range matrix and `fragment_shader_transformation_external_yuv_es3.glsl`'s YUV offsets) — © The Android Open Source Project | Apache-2.0 | how `stage/src/main/glsl/video/copy-y2y-es3.frag` reads a decoder's 10-bit HDR frame through GL_EXT_YUV_target; `YuvMatrix.toRgbTenBit` is held to Media3's matrix |
| [NVIDIA Image Scaling SDK](https://github.com/NVIDIAGameWorks/NVIDIAImageScaling) v1.0.3 `NIS_Scaler.h` (NVScaler) and `NIS_Config.h` (settings, coefficient tables) — © 2022 NVIDIA Corporation | MIT | `Nis`, and the app's GL pass written from it (licence text: `app/THIRD-PARTY-NOTICE.NVIDIA-Image-Scaling.txt`) |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_minterpolate.c` and `motion_estimation.c` (bilateral EPZS search, OBMC, `obmc_linear32`, scene change) — © 2014-2015 Michael Niedermayer, 2016 Davinder Singh | LGPL-2.1-or-later | `MotionInterpolation`, and the app's GL pass written from it |
| [libebur128](https://github.com/jiixyj/libebur128) v1.2.6 `ebur128/ebur128.c` (`ebur128_init_filter`, the filter, `ebur128_calc_gating_block`, `ebur128_gated_loudness`) — © 2011 Jan Kokemüller | MIT | `LoudnessMeter` — EBU R128 integrated loudness (licence text: `app/THIRD-PARTY-NOTICE.libebur128.txt`) |

The Bessel function J1 in `Ewa` follows Abramowitz & Stegun, *Handbook of Mathematical Functions*, 9.4.4 and 9.4.6.
`Histogram` turns RGB into Y'CbCr by ITU-R BT.709-6's luma weights and colour-difference scales (items 3.2 to 3.4), at
full range.
`TestPattern`'s levels and layouts follow ITU-R BT.814 (PLUGE), ITU-R BT.471 (EBU 100/0/75/0 colour bars) and SMPTE RP
219 / ARIB STD-B28 (HD colour bars), given there in percent and drawn here in full-range RGB; which patterns to offer
follows DScaler's `Help/Calibration.htm` (GPL-2.0, read only for its kinds of pattern — greys, colours — and the order
they are set in: brightness, contrast, colour).
`TemporalDenoise.noiseSigma` follows J. Immerkær, "Fast Noise Variance Estimation", *Computer Vision and Image
Understanding* 64(2), 1996, pp. 300-302: the difference-of-Laplacians mask and the √(π/2) / 6 scale.
`Keystone.squareToQuad` follows Paul Heckbert, *Fundamentals of Texture Mapping and Image Warping* (UC Berkeley, 1989),
§2.2.3, the projective map of the unit square onto a quadrilateral.

## Behaviour followed, no code taken

These are GPL, which this LGPL project cannot take code from; their behaviour was written down and implemented anew,
and the first is held to its own output.

| Source | Licence | Used for |
| --- | --- | --- |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_cropdetect.c`, black mode (after MPlayer's `vf_cropdetect`) — © 2002 A'rpi | GPL-2.0-or-later | `BlackBars`: which lines are bar (mean luma at most `limit`, 24/255 by default), tolerated bright lines, the picture growing over frames, the fresh start every `reset` frames, the crop rounded to even corners and a multiple of `round` |
| DScaler's AutoAspect, as described in issue #252 | GPL-2.0 | `AutoCrop`'s idea of cropping in steps: few detections to widen the picture, many to narrow it |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/AspectRatio.htm` and `Help/Aspect.htm` (the source formats, "Aspect Ratio of Display Screen", the custom screen aspect for a Panamorph lens) | GPL-2.0 | `PictureShape` and `ScreenShape`: a frame's shape set over its flag, a screen seen wider than its pixels and pictures drawn narrower to match |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/SourceSpecific.htm` (settings kept per source) | GPL-2.0 | the idea behind `FolderDefaults` (in `:core:library`): pictures kept per folder or share, the nearest one winning |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/Aspect.htm` (the "orbit" and "bounce" settings: the picture moved a few pixels every so often, within the overscan) | GPL-2.0 | `BurnInDrift`'s idea; its path — one pixel at a time, row by row over the square and back — is its own |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/Histogram.htm` (luma white, U light blue, V pink, a log scale, a dotted yellow line under the 256 levels, values outside CCIR 601's range dimmed) | GPL-2.0 | `Histogram.heights` and the overlays drawing it; the counting is BT.709's (below) |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/Mirror.htm` (rear projection, with the vertical flip for a ceiling mount) and `Help/LinearCorrection.htm` (the trapezoid and two-axis keystone modes) | GPL-2.0 | `ScreenSetup`'s mirror, flip and keystones; the map itself is `ScreenWarp`'s own, after Heckbert (below) |
| [DScaler](https://github.com/JohnAdders/DScaler) `Help/LinearCorrection.htm` and `Plugins/FLT_LinearCorrection` (the "Stretch" mode: 4:3 to 16:9, a non-linear zoom) | GPL-2.0 | `Panorama`: the idea of a 4:3 picture widened to a wide screen with the stretch growing towards the sides; DScaler's own curve was not taken — ours keeps the middle exactly in shape and is solved back by Cardano's formula |
| [DScaler](https://github.com/JohnAdders/DScaler) `Plugins/DI_Greedy2Frame` and its help's "Greedy 2 Frame Method" — © 2000 John Adcock, Tom Barry, Steve Grimm — held to xine-lib's port of it (`src/post/deinterlace/plugins/greedy2frame_template.c`, © 2003 Miguel Freitas), run as an oracle only | GPL-2.0 | `Deinterlacer` Greedy 2-Frame, and the app's GL pass: the four fields it weighs, the threshold 4 on halved differences two pixels at a time, weaving the rounded average where the line and one of its neighbours stood still, the rounded bob otherwise |
| [DScaler](https://github.com/JohnAdders/DScaler) `Plugins/DI_OldGame` and its help's "Old Game" — © 2001 Lindsey Dubb and the DScaler authors | GPL-2.0 | `OldGame`: each field shown as a half-height progressive picture, averaged with the field before (the newest value's lowest bit dropped, rounded up) while their comb factor stays at most 300; the stretch to full height is this project's bilinear one |
| QTGMC (Vit, Didée and -Vit-, for AviSynth) as its documentation and havsfunc's VapourSynth port (Unlicense, but resting on MVTools and NNEDI3, GPL) describe it | GPL-2.0 (MVTools) | `TemporalSmoothing`: the method — a per-field base, the neighbouring fields' bases averaged along the motion where they match, contra-sharpening, a repair to the base's range, the source lines kept; the motion search is this project's own (a local per-pixel search, not MVTools' blocks), held to ground truth |
| [DScaler](https://github.com/JohnAdders/DScaler) `Plugins/DI_Adaptive/DI_Adaptive.c` and its help's "Adaptive" — © 2000 John Adcock and the DScaler authors | GPL-2.0 | `MotionAdaptive`: still fields (a field difference under 15), 100 of them before weaving, the first moving field back to the temporal deinterlacer |
| [DScaler](https://github.com/JohnAdders/DScaler) `DScaler/FD_50Hz.cpp` (`UpdatePALPulldownMode`) and `FD_Common.cpp` (`CalcCombFactor`, `CalculateTotalCombFactor`, `CalcDiffFactor`), its help's "2:2 Pulldown Settings" and "Shared Pulldown Settings" — © 2000 John Adcock and the DScaler authors | GPL-2.0 | `Pulldown22`: the comb factor (a pixel between the other field's lines sticking out of both beyond their own difference, threshold 73 on halved values; lines counted only beside combed lines), the field difference (squares shifted down by 12), and the detection's thresholds — a drop under 40 %, three in a row on the same field, movement over 100, a comb factor over 150; leaving film on a bad pair is this project's own rule, after the help's 10 % rise |
| [FFmpeg](https://ffmpeg.org) `libavfilter/vf_delogo.c` — © 2002 Jindrich Makovicka, 2011 Stefano Sabatini, 2013, 2015 Jean Delvare | GPL-2.0-or-later | `LogoMask`'s Blur: the rectangle filled from the ring around it, each side's three nearest ring pixels weighed by nearness — held to FFmpeg's own output, byte for byte |
| DScaler's logo killer, as described in issue #264 | GPL-2.0 | `LogoMask`'s Grey and Dim: greying the logo, holding its brightness down |
| DScaler's comb filtering of composite captures, as described in issue #263 | GPL-2.0 | `DotCrawl`'s idea only: still areas averaged over the frames where the composite residue alternates; written from NTSC's subcarrier relation (SMPTE 170M: 227.5 cycles a line, its phase turning over each frame) and PAL's (ITU-R BT.1700: 283.75 cycles a line plus 25 Hz, 625 lines, the V switch every line — back in phase after four frames) |

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
`src/test/resources/dither/` — `input.ppm` (16-bit) written by `make-input.py` there; `expected.ppm` libplacebo 7.360's own
`pl_shader_dither` for it (its defaults) and `bluenoise.txt` its `pl_generate_blue_noise` matrix, run by libplacebo on Mesa
26.2's OpenGL (llvmpipe) through `tools/shader-check/placebo.cpp`, made by `make-expected.sh`.
`src/test/resources/minterpolate/` — `input.gray.gz` written by `make-input.py` there, `expected.gray.gz` FFmpeg 6.0's
`minterpolate` output for it, made with the command in that script's header.
`src/test/resources/loudness/expected.txt` — the integrated loudness of the `LoudnessSignals` test signals, measured by
`measure.sh` beside it with libebur128 1.2.6 (through `oracle.c`) and FFmpeg's `ebur128` filter.
`src/test/resources/deinterlace/tomsmocomp*.gray` — GStreamer 1.28.8's TomsMoComp, its MMXEXT search at effort 5 built
from its sources through `tomsmocomp-harness.c`, for `input.gray` and `input-bff.gray`; made by `make-tomsmocomp.sh`
there, which fetches the sources.
`src/test/resources/oldgame/` — `console.gray.gz` written by `make-input.py` there, a woven 240p clip; `top.gray.gz` and
`bottom.gray.gz` FFmpeg n9.0.2's `field` and bilinear `scale` output for it, made with the commands in that script's
header.
`src/test/resources/pulldown/` — `pal.gray.gz`, `pal-film.gray.gz` and `pal.txt` written by `make-input.py` there: a
PAL clip whose every frame's make-up is known by construction, the answer `Pulldown22` is held to; FFmpeg n9.0.2's idet
calls it top field first throughout.
`src/test/resources/deinterlace/greedyh*.gray` — GStreamer 1.28.8's own `deinterlace method=greedyh` output for
`input.gray` and `input-bff.gray`, `greedy2frame*.gray` xine-lib's port of DScaler's Greedy 2-Frame for them through
`greedy2frame-harness.c`; both made by `make-greedy.sh` there, which fetches the port.
`src/test/resources/cropdetect/` — the `.gray.gz` clips synthetic (FFmpeg's lavfi sources), the `.txt` files FFmpeg
n9.0.2's own `cropdetect` lines for them, run as an oracle over the same bytes; both made by `make-expected.sh`.
`src/test/resources/chroma/` — `input.yuv` written by `make-input.py` there; `spline36.ppm` and `krig.ppm` libplacebo
7.360's own renderer on it (its `spline36` plane scaler, and KrigBilateral run as the mpv user shader it is), on Mesa
26.2's OpenGL (llvmpipe) through `tools/shader-check/placebo.cpp`, made by `make-expected.sh`.
`src/test/resources/delogo/` — `input.yuv` FFmpeg's testsrc2 with a half-transparent box drawn into a corner, the other
`.yuv` files FFmpeg n9.0.2's own delogo filter on it (an oracle only), both made by `make-expected.sh`.
`src/test/resources/deblock/` — `input.ppm` FFmpeg's testsrc2 coded as MPEG-2 and decoded; the other `.ppm` files FFmpeg's
own deblock filters (n8.0's `vf_deblock.c`, every row of a vertical edge judged) run on it by `harness.c`, whose build
from the unchanged filters `make-expected.sh` first holds to FFmpeg n9.0.2's command-line output, byte for byte.
`src/test/resources/cdef/` — `input.ppm` likewise; the other `.ppm` files dav1d 1.5.0's own CDEF run on it by `harness.c`,
built against dav1d's files by `make-expected.sh`.
`engine/desktop/src/test/resources/mpv/` (the desktop stage's tests) — mpv user shaders as test fixtures, not carried
by the apps: bloc97's `Anime4K_Upscale_CNN_x2_S.glsl` (Anime4K, MIT, © 2019-2021 bloc97), igv's
`FSRCNNX_x2_8-0-4-1.glsl` (LGPL-3.0-or-later, © 2017-2021 igv), bjin's `ravu-lite-ar-r2.hook` and its compute variant
`ravu-lite-ar-r2-compute.hook` (mpv-prescalers at `b3f0a59`, LGPL-3.0-or-later, © bjin) and Shiandow's
`KrigBilateral.glsl` (LGPL-3.0-or-later, the revision in `tools/shader-check/krig-url.txt`), each with its licence in its
header, and natural-harmonia-gropius's `st2094-10.glsl` (hdr-toys at `db16b9f`, MIT, © 2023 natural-harmonia-gropius;
its licence beside it as `LICENSE.hdr-toys.txt`); `lookup-1d.glsl` is the app's own; the pictures written by `make-input.py` there,
`chroma.yuv` by `src/test/resources/chroma/make-input.py`; `anime4k.ppm`, `fsrcnnx.ppm`, `fsrcnnx-planes.ppm`,
`ravu.ppm`, `ravu-compute.ppm`, `krig.ppm`, `lookup-1d.ppm` and `st2094-10.ppm` libplacebo 7.360's own renderer running
them, on Mesa's OpenGL through `tools/shader-check/placebo.cpp`, made by `make-expected.sh`.
`src/test/resources/temporal-denoise/` — `frame0..4.ppm` written by `make-input.py` there; the other `.ppm` files the middle
frame denoised against its neighbours by FFmpeg's own atadenoise filter (n8.0's `vf_atadenoise.c`) run by `harness.c`,
whose output over five frames `make-expected.sh` first holds to FFmpeg n9.0.2's command line, byte for byte.
