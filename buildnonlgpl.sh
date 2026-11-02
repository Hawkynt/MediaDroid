#!/bin/bash
# Builds and signs the release APK with the GPL + nonfree FFmpeg: dist/MediaDroid-nonlgpl.apk.
#
#   ./buildnonlgpl.sh [workspace]
#
# FOR YOUR OWN DEVICE ONLY. FFmpeg configured with --enable-gpl --enable-nonfree cannot be distributed
# under this repository's LGPL-3.0-or-later (tools/ffmpeg/README.md, "the non-LGPL switch"): never
# publish this APK and never commit its AAR over app/libs/lib-decoder-ffmpeg-release.aar. The AAR is
# swapped in through -PffmpegAar, so the committed one is left untouched.
#
# The full AAR is built once with `FULL=1 tools/ffmpeg/build.sh` in the workspace (default
# stage/ffmpeg-full, ignored by git; needs NDK 28.2.13676358 and CMake 3.22.1) and reused afterwards; delete
# <workspace>/lib-decoder-ffmpeg-full.aar to rebuild it. Signing is build.sh's: same variables, same
# signing.env.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

WORKSPACE="$(realpath -m "${1:-${FFMPEG_WORKSPACE:-$ROOT/stage/ffmpeg-full}}")"
AAR="$WORKSPACE/lib-decoder-ffmpeg-full.aar"

if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties)"
  export ANDROID_HOME
fi
if [ -z "${JAVA_HOME:-}" ]; then
  for jdks in "$HOME/.gradle/jdks" "/home/$(id -un)/.gradle/jdks"; do
    jdk="$(find "$jdks" -maxdepth 1 -mindepth 1 -type d -name '*21*' 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$jdk" ] && [ -x "$jdk/bin/java" ]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
fi

if [ ! -f "$AAR" ]; then
  echo "building the GPL + nonfree FFmpeg extension in $WORKSPACE (this takes a while)"
  FULL=1 bash tools/ffmpeg/build.sh "$WORKSPACE"
fi

echo "NOTE: GPL + nonfree build: for your own device only, never distribute it"
FFMPEG_AAR="$AAR" APK_NAME="MediaDroid-nonlgpl.apk" "$ROOT/build.sh"
