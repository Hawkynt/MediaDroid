#!/bin/bash
# Builds the release APK and signs it: dist/MediaDroid.apk.
#
#   ./build.sh
#
# The release key is held in the repository's GitHub secrets, never in the repository. To sign with it,
# hand it over through the same variables those secrets use, from the environment or from a signing.env
# next to this script (ignored by git, as every *.env is):
#
#   KEYSTORE=/path/to/release.p12      the keystore file (PKCS12 or JKS), or instead
#   KEYSTORE_BASE64=…                  the keystore itself, base64-encoded, as in the repo secrets
#   KEYSTORE_PASSWORD=…                its password
#   KEY_ALIAS=…                        the key inside it
#   KEY_PASSWORD=…                     the key's password, when it differs from KEYSTORE_PASSWORD
#
# Without them the APK is signed with the Android debug key (~/.android/debug.keystore): it installs and
# runs on your own device, but cannot update an installed nightly or release, which carry the release key.
#
# ANDROID_HOME defaults to local.properties' sdk.dir, JAVA_HOME to a JDK 21 Gradle provisioned.
# FFMPEG_AAR and APK_NAME are for buildnonlgpl.sh; leave them unset.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [ -f signing.env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./signing.env
  set +a
fi

APK_NAME="${APK_NAME:-MediaDroid.apk}"

die() { echo "error: $*" >&2; exit 1; }

if [ -z "${ANDROID_HOME:-}" ] && [ -f local.properties ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || die "set ANDROID_HOME or sdk.dir in local.properties"
export ANDROID_HOME

if [ -z "${JAVA_HOME:-}" ]; then
  for jdks in "$HOME/.gradle/jdks" "/home/$(id -un)/.gradle/jdks"; do
    jdk="$(find "$jdks" -maxdepth 1 -mindepth 1 -type d -name '*21*' 2>/dev/null | sort -V | tail -1 || true)"
    if [ -n "$jdk" ] && [ -x "$jdk/bin/java" ]; then
      export JAVA_HOME="$jdk"
      break
    fi
  done
fi

if [ -z "${KEYSTORE:-}${KEYSTORE_BASE64:-}" ]; then
  for android_home in "${ANDROID_USER_HOME:-}" "$HOME/.android" "/home/$(id -un)/.android"; do
    if [ -n "$android_home" ] && [ -f "$android_home/debug.keystore" ]; then
      KEYSTORE="$android_home/debug.keystore"
      break
    fi
  done
  [ -n "${KEYSTORE:-}" ] || die "no release key set (see the top of $0) and no ~/.android/debug.keystore to fall back to"
  KEYSTORE_PASSWORD=android
  KEY_ALIAS=androiddebugkey
  echo "WARNING: no release key set; signing with the debug key $KEYSTORE" >&2
fi
[ -n "${KEYSTORE_PASSWORD:-}" ] || die "KEYSTORE_PASSWORD is not set"
[ -n "${KEY_ALIAS:-}" ] || die "KEY_ALIAS is not set"
export KEYSTORE_PASSWORD
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

build_tools="$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1 || true)"
apksigner="$build_tools/apksigner"
[ -x "$apksigner" ] || die "no apksigner under $ANDROID_HOME/build-tools"

gradle_args=(assembleRelease --no-daemon)
if [ -n "${FFMPEG_AAR:-}" ]; then
  [ -f "$FFMPEG_AAR" ] || die "FFMPEG_AAR $FFMPEG_AAR does not exist"
  gradle_args+=("-PffmpegAar=$(realpath "$FFMPEG_AAR")")
fi
./gradlew "${gradle_args[@]}"

keystore="${KEYSTORE:-}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -z "$keystore" ]; then
  keystore="$tmp/release.keystore"
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$keystore"
fi
[ -f "$keystore" ] || die "keystore $keystore does not exist"

mkdir -p dist
out="dist/$APK_NAME"
"$apksigner" sign --ks "$keystore" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --v4-signing-enabled false \
  --out "$out" app/build/outputs/apk/release/app-release-unsigned.apk
"$apksigner" verify --verbose "$out"
echo "signed: $ROOT/$out"
