#!/usr/bin/env bash
# GhostBlade Task — manual APK build (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
SDK="${ANDROID_SDK:-$HOME/workspace/tools/android-sdk}"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile + link"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -R "$OUT/res.zip" \
  -A assets --java "$OUT/gen" --auto-add-overlay

echo "[2/6] javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -cp "$AJAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/6] d8"
(cd "$OUT/classes" && jar cf ../classes.jar .)
"$BT/d8" --release --lib "$AJAR" --output "$OUT/dex" "$OUT/classes.jar"

echo "[4/6] add dex to apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=GhostBlade Task,O=GhostBlade,C=BD" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out GhostBladeTask.apk "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs GhostBladeTask.apk | head -3
ls -la GhostBladeTask.apk
echo "DONE -> GhostBladeTask.apk"
