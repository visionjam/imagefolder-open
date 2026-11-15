#!/usr/bin/env bash
# Rebuild app/libs/libarchive-android-1.1.6-libarchive-3.8.8.aar.
#
# Why: libarchive-android 1.1.6's Java binding is compatible with compileSdk 35,
# but its native library bundles libarchive 3.8.1, which predates the 2026 RAR5
# security fixes. 1.1.7 bundles libarchive 3.8.8 but declares minCompileSdk 37.2,
# which would force an AGP/Gradle upgrade. The Java API and all JNI native method
# signatures are identical between 1.1.6 and 1.1.7, so we combine the two.
#
# Requires: curl, unzip, zip. If Maven Central needs a proxy:
#   HTTPS_PROXY=http://proxy.example:8080 scripts/repack-libarchive-aar.sh
set -euo pipefail

JAVA_VERSION=1.1.6
NATIVE_VERSION=1.1.7
REPO=https://repo1.maven.org/maven2/me/zhanghai/android/libarchive/library
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/app/libs/libarchive-android-${JAVA_VERSION}-libarchive-3.8.8.aar"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

curl -fsSL -o "$WORK/java.aar" "$REPO/$JAVA_VERSION/library-$JAVA_VERSION.aar"
curl -fsSL -o "$WORK/native.aar" "$REPO/$NATIVE_VERSION/library-$NATIVE_VERSION.aar"

mkdir -p "$WORK/java" "$WORK/native"
unzip -q "$WORK/java.aar" -d "$WORK/java"
unzip -q "$WORK/native.aar" 'jni/*/*' -d "$WORK/native"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    cp "$WORK/native/jni/$abi/libarchive-jni.so" "$WORK/java/jni/$abi/libarchive-jni.so"
done

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$WORK/java" && zip -qr "$OUT" .)

unzip -p "$OUT" META-INF/com/android/build/gradle/aar-metadata.properties \
    | grep -q 'minCompileSdk=1'
unzip -p "$OUT" jni/arm64-v8a/libarchive-jni.so \
    | grep -aq 'libarchive 3\.8\.8'

echo "Wrote $OUT"
