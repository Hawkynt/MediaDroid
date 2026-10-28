#!/bin/bash
# Builds app/libs/lib-decoder-ffmpeg-release.aar from pinned upstreams — see README.md.
#
#   tools/ffmpeg/build.sh <workspace>            the LGPL build that is committed
#   FULL=1 tools/ffmpeg/build.sh <workspace>     GPL + nonfree, for your own device only
#
# Needs JAVA_HOME (JDK 21), ANDROID_HOME with NDK 28.2.13676358 and CMake 3.22.1, git, curl, make.
set -euo pipefail

W="$(realpath -m "${1:?workspace directory}")"
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="$ANDROID_HOME/ndk/28.2.13676358"
MEDIA_TAG=1.11.1
FFMPEG_VERSION=6.0
LIBYUV_COMMIT=2dd4257364d39c38d79465c4ddc4b93137fe729b

# The containers Media3 has no extractor for, and the parsers their raw streams need.
DEMUXERS="asf rm dv nut mxf wtv nuv mpegvideo h264 hevc yuv4mpegpipe ivf"
PARSERS="h264 hevc mpegvideo mpeg4video vc1 h263 rv30 rv40 ac3 dca aac mpegaudio vp9"
# Audio the devices often lack, and what the demuxed containers carry.
AUDIO="ac3 eac3 dca truehd mlp alac vorbis opus flac aac mp3 mp2 mp1 amrnb amrwb
  wmav1 wmav2 wmapro wmavoice wmalossless cook ra_144 ra_288 atrac3 sipr
  pcm_s16le pcm_s16be pcm_u8 pcm_s24le pcm_mulaw pcm_alaw adpcm_ima_wav adpcm_ms"
# Video for the software path and for codecs no device decodes.
VIDEO="h264 hevc mpeg2video mpeg1video mpeg4 vp8 vp9 av1 h263 flv
  wmv1 wmv2 wmv3 vc1 msmpeg4v1 msmpeg4v2 msmpeg4v3 rv10 rv20 rv30 rv40 dvvideo mjpeg theora rawvideo"

mkdir -p "$W"
cd "$W"
[ -d media ] || git clone -q --depth 1 --branch "$MEDIA_TAG" https://github.com/androidx/media.git
cd media
if ! git log -1 --format=%s | grep -q mediadroid; then
  git apply "$HERE/patches/0001-androidx-media-1591-ffmpeg-video-renderer.patch"
  git apply "$HERE/patches/0002-mediadroid-demuxer-and-codec-parameters.patch"
  git add -A
  git -c user.name=build -c user.email=build@localhost -c commit.gpgsign=false \
    commit -qm "mediadroid patches"
fi
M="$W/media/libraries/decoder_ffmpeg/src/main"
cp "$HERE"/mediadroid/jni/* "$M/jni/"
mkdir -p "$M/java/com/hawkynt/mediadroid/ffmpeg"
cp "$HERE"/mediadroid/java/com/hawkynt/mediadroid/ffmpeg/*.java "$M/java/com/hawkynt/mediadroid/ffmpeg/"

if [ ! -d "$M/jni/ffmpeg" ]; then
  curl -sL "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" | tar -xJ -C "$M/jni"
  mv "$M/jni/ffmpeg-$FFMPEG_VERSION" "$M/jni/ffmpeg"
fi
if [ ! -d "$M/jni/libyuv" ]; then
  git clone -q https://chromium.googlesource.com/libyuv/libyuv "$M/jni/libyuv"
  git -C "$M/jni/libyuv" checkout -q "$LIBYUV_COMMIT"
fi

bash "$M/jni/build_yuv.sh" "$M" "$NDK" 23
# The full build is for your own device only: GPL and nonfree, and every decoder, demuxer and parser.
EXTRA_CONFIGURE_FLAGS="$([ "${FULL:-}" = 1 ] && \
  echo "--enable-gpl --enable-nonfree --enable-decoders --enable-demuxers --enable-parsers" || true)" \
ENABLED_DEMUXERS="$DEMUXERS" ENABLED_PARSERS="$PARSERS" \
  bash "$HERE/build_ffmpeg_extension.sh" "$M" "$NDK" linux-x86_64 23 $AUDIO $VIDEO

grep -q abiFilters libraries/decoder_ffmpeg/build.gradle.kts || sed -i \
  's/^android {/android {\n  defaultConfig { externalNativeBuild { cmake { abiFilters("arm64-v8a", "x86_64") } } }/' \
  libraries/decoder_ffmpeg/build.gradle.kts
./gradlew -q :lib-decoder-ffmpeg:assembleRelease
OUT="$W/lib-decoder-ffmpeg-$([ "${FULL:-}" = 1 ] && echo full || echo release).aar"
cp "$(find . -name 'lib-decoder-ffmpeg-release.aar' -path '*outputs*' | head -1)" "$OUT"
ls -la "$OUT"
