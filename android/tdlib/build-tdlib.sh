#!/usr/bin/env bash
# Builds TDLib (Java interface) for Android and installs it into android/tdlib/out:
#   out/java/org/drinkless/tdlib/{Client,TdApi}.java   generated sources
#   out/libs/<abi>/libtdjni.so                         native library (OpenSSL linked statically)
#
# Runs on Linux (or WSL). Needs: git, a host C++ compiler, cmake, make, gperf, php, perl, JDK 17
# and the Android SDK command-line tools prerequisites listed by TDLib's check-environment.sh.
# No root is required if those tools come from a user-level package manager (e.g. micromamba).
#
# Usage: android/tdlib/build-tdlib.sh [work-dir]
set -euo pipefail

TDLIB_COMMIT="f10611ee1f03f91ddae8185483a1ccfd3d664668"   # TDLib 1.8.71, 2026-11-22
OPENSSL_TAG="openssl-3.5.9"                               # OpenSSL 3.5 LTS (Apache-2.0)
NDK_VERSION="28.2.13676358"                               # r28: 16 KB page-aligned output by default
MIN_API=24
ABIS="arm64-v8a x86_64"

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$HOME/tg}"
OUT="$HERE/out"
mkdir -p "$WORK"

# Conda-style toolchains export host CFLAGS (e.g. -march=nocona) that break the NDK cross-builds.
unset CFLAGS CXXFLAGS CPPFLAGS LDFLAGS DEBUG_CFLAGS DEBUG_CXXFLAGS DEBUG_CPPFLAGS
HOST_CC="${HOST_CC:-${CC:-cc}}"
HOST_CXX="${HOST_CXX:-${CXX:-c++}}"
unset CC CXX

cd "$WORK"
[ -d td ] || git clone https://github.com/tdlib/td.git
cd td
git fetch --quiet origin
git checkout --quiet "$TDLIB_COMMIT"
cd example/android

./check-environment.sh
SDK="$WORK/android-sdk"
[ -d "$SDK/ndk/$NDK_VERSION" ] || { rm -rf "$SDK"; ./fetch-sdk.sh "$SDK" "$NDK_VERSION"; }
[ -f third-party/openssl/x86_64/lib/libssl.a ] || {
  rm -rf third-party/openssl
  ./build-openssl.sh "$SDK" "$NDK_VERSION" third-party/openssl "$OPENSSL_TAG"
}
CC="$HOST_CC" CXX="$HOST_CXX" ./build-tdlib.sh "$SDK" "$NDK_VERSION" third-party/openssl c++_static Java "$MIN_API" "$ABIS"

rm -rf "$OUT"
mkdir -p "$OUT"
cp -r tdlib/java "$OUT/java"
cp -r tdlib/libs "$OUT/libs"
echo "TDLib $TDLIB_COMMIT installed in $OUT"
