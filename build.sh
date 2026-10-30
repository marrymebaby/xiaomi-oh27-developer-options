#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-35/android.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"

if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -f "$PLATFORM" ]; then
    echo "Android SDK not found. Set ANDROID_HOME or BUILD_TOOLS/PLATFORM." >&2
    exit 1
fi

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk" "$OUT/gen"
"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/apk/base.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 5 \
    --version-name 1.4 \
    --auto-add-overlay \
    -R "$OUT/res.zip"

javac -source 8 -target 8 \
    -classpath "$PLATFORM" \
    -d "$OUT/classes" \
    "$OUT"/gen/com/codex/settingsentry/R.java \
    "$ROOT"/src/com/codex/settingsentry/*.java

"$BUILD_TOOLS/d8" \
    --lib "$PLATFORM" \
    --min-api 26 \
    --output "$OUT/dex" \
    "$OUT"/classes/com/codex/settingsentry/*.class

cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/apk/unsigned.apk" classes.dex)

"$BUILD_TOOLS/zipalign" -f 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --min-sdk-version 26 \
    --out "$ROOT/SettingsEntryHelper.apk" \
    "$OUT/apk/aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose "$ROOT/SettingsEntryHelper.apk"
"$BUILD_TOOLS/aapt2" dump badging "$ROOT/SettingsEntryHelper.apk" | sed -n '1,12p'
echo "Built: $ROOT/SettingsEntryHelper.apk"
