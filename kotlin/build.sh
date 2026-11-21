#!/usr/bin/env bash
# GhostBlade Task — KOTLIN edition build pipeline (no Gradle).
# aapt2 (resources+manifest) -> javac (R.java) -> kotlinc (app) ->
# d8 (app classes + kotlin-stdlib) -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$HOME/workspace/tools/android-sdk"
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/tools/kotlinc/bin/kotlinc"
STDLIB="$HOME/workspace/tools/kotlinc/lib/kotlin-stdlib.jar"
KEYSTORE="$HOME/workspace/projects/ghostblade-task-android/debug.keystore"

B=build
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "== aapt2: compile + link resources =="
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$AJAR" \
  --manifest AndroidManifest.xml -R "$B/res.zip" \
  --java "$B/gen" --auto-add-overlay

echo "== javac: R.java =="
javac --release 8 -cp "$AJAR" -d "$B/classes" $(find "$B/gen" -name '*.java')

echo "== kotlinc: app sources =="
"$KOTLINC" src/com/ghostblade/task/*.kt -cp "$AJAR:$B/classes" -d "$B/classes"

echo "== d8: dex (app + kotlin-stdlib) =="
(cd "$B/classes" && jar cf ../app.jar .)
"$BT/d8" --release --min-api 24 --lib "$AJAR" \
  --output "$B/dex" "$B/app.jar" "$STDLIB"

echo "== package + align + sign =="
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
# Versioned output name (GhostBladeTask-Kotlin-v2.7.apk) so downloads never
# collide into "(1)", "(2)" duplicates on the phone.
VER=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | cut -d'"' -f2)
OUT="GhostBladeTask-Kotlin-v${VER%-kotlin}.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$B/aligned.apk"
cp "$OUT" GhostBladeTask-Kotlin.apk

"$BT/apksigner" verify "$OUT"
# Badging goes to a file first: piping it into `head` made aapt2 die of
# SIGPIPE and, under pipefail, the whole script exited 141 on success.
"$BT/aapt2" dump badging "$OUT" > "$B/badging.txt"
head -8 "$B/badging.txt"
ls -la "$OUT"
sha256sum "$OUT"
