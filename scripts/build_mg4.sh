#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    SDK="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then
    SDK="$HOME/Android/Sdk"
  fi
fi

if [ -n "${ANDROID_BUILD_TOOLS:-}" ]; then
  BT="$ANDROID_BUILD_TOOLS"
elif [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | tail -n1)
else
  echo "Error: Android SDK or build-tools not found. Set ANDROID_HOME or ANDROID_BUILD_TOOLS." >&2
  exit 1
fi

# Gradle reads ANDROID_HOME or local.properties, not the lookup above.
if [ -n "$SDK" ] && [ ! -f "$ROOT/local.properties" ]; then
  export ANDROID_HOME="$SDK"
fi

KEYS="${MG4_PLATFORM_KEYS_DIR:-$ROOT/tools}"
if [ ! -f "$KEYS/platform.pk8" ]; then
  echo "Error: platform.pk8 not found in $KEYS. Set MG4_PLATFORM_KEYS_DIR to folder containing platform.pk8 & platform.x509.pem" >&2
  exit 1
fi
OUT="$ROOT/mobile/build/outputs/apk/debug/mobile-debug.apk"
VERSION="0.2.9-mg4.85"
ALIGNED="$ROOT/mobile/build/outputs/apk/debug/MG4CPlay-v$VERSION-aligned.apk"
SIGNED="$ROOT/mobile/build/outputs/apk/debug/MG4CPlay-v$VERSION.apk"
DELIVERY_DIR="$ROOT/dist"
DELIVERY_APK="$DELIVERY_DIR/MG4CPlay-v$VERSION.apk"

: "${DIPLAY_AUTH_ASSETS_DIR:?Set DIPLAY_AUTH_ASSETS_DIR to the directory containing offline-mfi/}"

cd "$ROOT"
# iCloud Drive (Desktop & Documents sync) leaves "name 2.ext" copies in build output, which
# fail Android resource validation ("Failed file name validation ... ic_launcher 2.xml").
# Remove them from build folders only; sources are never touched.
for dir in build */build vendor/*/*/lib/build .gradle; do
  [ -d "$dir" ] && find "$dir" -name '* [0-9].*' -type f -delete 2>/dev/null || true
done
if [ -n "${DIPLAY_PREBUILT_JNI_DIR:-}" ]; then
  DIPLAY_AUTH_ASSETS_DIR="$DIPLAY_AUTH_ASSETS_DIR" \
  DIPLAY_PREBUILT_JNI_DIR="$DIPLAY_PREBUILT_JNI_DIR" \
  ./gradlew :mobile:assembleStandaloneDebug
else
  DIPLAY_AUTH_ASSETS_DIR="$DIPLAY_AUTH_ASSETS_DIR" ./gradlew :mobile:assembleStandaloneDebug
fi
"$BT/zipalign" -f 4 "$OUT" "$ALIGNED"
"$BT/apksigner" sign \
  --key "$KEYS/platform.pk8" \
  --cert "$KEYS/platform.x509.pem" \
  --out "$SIGNED" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$SIGNED"
mkdir -p "$DELIVERY_DIR"
cp "$SIGNED" "$DELIVERY_APK"
echo "$DELIVERY_APK"
