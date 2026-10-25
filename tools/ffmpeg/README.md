# FFmpeg decoder extension

`app/libs/lib-decoder-ffmpeg-release.aar` adds FFmpeg audio decoders to the
player, so AC3, E-AC3, DTS, TrueHD and MLP tracks play on devices whose own
decoders refuse them. It is built from two upstreams and nothing else:

- the `decoder_ffmpeg` extension module of [androidx/media 1.11.1](https://github.com/androidx/media/tree/1.11.1) (Apache-2.0), and
- [FFmpeg 6.0](https://ffmpeg.org) (LGPL-3.0-or-later), statically linked, configured `--disable-everything`
  with only these decoders enabled:
  `h264 hevc mpeg2video vp8 vp9 av1 ac3 eac3 dts truehd mlp alac vorbis opus flac`.

No GPL component (x264, x265, `--enable-gpl`) is configured or linked, which is
what keeps the result compatible with this repository's LGPL-3.0-or-later.
Video decoding normally stays on the device's own MediaCodec decoders; the
FFmpeg decoders matter for the audio tracks.

## Rebuilding the AAR

```bash
# 1. Unpack FFmpeg next to the extension module's native sources.
curl -L https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz | tar -xJ
mv ffmpeg-6.0 jni/ffmpeg          # inside libraries/decoder_ffmpeg/src/main/

# 2. Build the static FFmpeg libs for arm64-v8a and x86_64.
FFMPEG_MODULE_PATH=.../libraries/decoder_ffmpeg \
  ./build_ffmpeg_extension.sh "$FFMPEG_MODULE_PATH" "$ANDROID_HOME/ndk/28.2.13676358" linux x86_64 \
  h264 hevc mpeg2video vp8 vp9 av1 ac3 eac3 dts truehd mlp alac vorbis opus flac

# 3. Build the extension module. Its build.gradle.kts pins
#    defaultConfig.externalNativeBuild.cmake.abiFilters("arm64-v8a", "x86_64")
#    so only the two shipped ABIs are configured.
./gradlew :lib-decoder-ffmpeg:assembleRelease
# → libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar
```

`build_ffmpeg_extension.sh` is the upstream `build_ffmpeg.sh` from androidx/media
trimmed to the two ABIs we ship; its Apache-2.0 header is kept intact.
