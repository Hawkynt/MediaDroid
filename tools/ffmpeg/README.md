# FFmpeg extension

`app/libs/lib-decoder-ffmpeg-release.aar` brings FFmpeg into the player for what Android's own
decoders and Media3's own extractors cannot do:

- **Audio decoders** — AC3, E-AC3, DTS, TrueHD, MLP and the rest — for devices whose decoders
  refuse them.
- **Video decoders** in software — the *Software first* choice in Settings, and the fallback for
  codecs no device decodes (WMV, VC-1 in ASF, RealVideo, DV, MS-MPEG4, …). This is the
  `ExperimentalFfmpegVideoRenderer` of androidx/media pull request #1591, which Media3 1.11.1 ships
  only as an empty shell.
- **Demuxers** for the containers Media3 has no extractor for: ASF/WMV/WMA, RealMedia, DV, NUT,
  MXF, WTV, NuppelVideo, raw MPEG video, H.264 and HEVC streams, Y4M and IVF. The app's
  `FfmpegExtractor` sits last in Media3's extractor list and takes a file only when none of Media3's
  own recognised it.

It is built from these and nothing else (attribution: `app/THIRD-PARTY-NOTICE.ffmpeg.txt`):

- the `decoder_ffmpeg` module of [androidx/media 1.11.1](https://github.com/androidx/media/tree/1.11.1)
  (Apache-2.0);
- [pull request #1591](https://github.com/androidx/media/pull/1591) applied unchanged —
  `patches/0001-*.patch` (Apache-2.0);
- MediaDroid's own part (LGPL-3.0-or-later): `mediadroid/jni/mediadroid_demux.cc` (libavformat over a
  custom AVIOContext, after FFmpeg's `doc/examples/avio_read_callback.c`),
  `mediadroid/jni/mediadroid_codecpar.h` (how a demuxed stream's codec parameters reach the decoders),
  `mediadroid/java/…/FfmpegDemuxer.java`, and `patches/0002-*.patch` — FFmpeg linked statically into
  one library, libyuv static, our two ABIs, and decoders found by name for `audio/x-ffmpeg-<decoder>` /
  `video/x-ffmpeg-<decoder>` tracks;
- [libyuv](https://chromium.googlesource.com/libyuv/libyuv) at a pinned commit (BSD-3-Clause);
- [FFmpeg 6.0](https://ffmpeg.org) (LGPL-2.1-or-later, as its configure reports), configured `--disable-everything` with only the
  decoders, demuxers and parsers listed in `build.sh`, no protocols.

No GPL component (x264, x265, `--enable-gpl`) is configured or linked, which is what keeps the result
compatible with this repository's LGPL-3.0-or-later.

## Rebuilding the AAR

```bash
JAVA_HOME=…/jdk21 ANDROID_HOME=…/android-sdk tools/ffmpeg/build.sh ~/build/ffmpeg
cp ~/build/ffmpeg/lib-decoder-ffmpeg-release.aar app/libs/
```

`build.sh` clones the pinned upstreams, applies the two patches, copies `mediadroid/` into the module,
builds libyuv and FFmpeg for arm64-v8a and x86_64 with NDK 28.2.13676358 and assembles the module.
`build_ffmpeg_extension.sh` is the upstream `build_ffmpeg.sh` from androidx/media trimmed to the two
ABIs we ship, with avformat, swscale and the demuxer and parser lists switched on; its Apache-2.0
header is kept intact.

## Checking it against FFmpeg itself

The demuxer and the codec-parameter handover are native, so the unit tests cannot reach them;
`test/check.sh` checks them on the desktop instead, against a host build of the same FFmpeg 6.0
(configured `--disable-x86asm --disable-inline-asm`, since FFmpeg 6.0's x86 inline assembly no longer
assembles with current binutils):

```bash
JAVA_HOME=…/jdk21 tools/ffmpeg/test/check.sh <host-ffmpeg-prefix> <out-dir>
```

It writes a two-second sample of every container the app demuxes with FFmpeg (WMV, ASF, RealMedia with
RealVideo 1/2 and RealAudio 1.0, DV, MXF, NUT, WTV, raw MPEG-2, Y4M), runs `FfmpegDemuxer.java` over
JNI on them, records ffprobe's packet counts as the oracle, and decodes every stream twice — from
FFmpeg's own parameters and from MediaDroid's codec-parameter blob alone. `test/expected.txt` is the
reviewed result: packet counts equal ffprobe's and both decodes give the same frames. The script fails
on any difference.

## Optional: the non-LGPL switch

If you want decoders that live in FFmpeg's GPL or nonfree sections, set the
compile switch before step 2:

```bash
FULL=1 tools/ffmpeg/build.sh ~/build/ffmpeg   # → lib-decoder-ffmpeg-full.aar
```

Understand what that costs: a binary configured with `--enable-gpl` (and even
more with `--enable-nonfree`) cannot be distributed under this repository's
LGPL-3.0-or-later — the GPL components would relicense the whole work. Such a
build is for your own device only; never commit its AAR over
`app/libs/lib-decoder-ffmpeg-release.aar`, and never ship it in a release. The
committed AAR stays the LGPL-only configuration above.
