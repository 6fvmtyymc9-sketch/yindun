#!/bin/bash
# Mac 版打包（build2.sh 是 Windows 版）：编出可调试 APK，用本机 debug 签名。
# 用法：bash app/build_mac.sh            产物 app/build_mac/Yindun-debug.apk
set -e
APP="$(cd "$(dirname "$0")" && pwd)"; OUT="$APP/build_mac"
SDK=~/Library/Android/sdk
BT=$SDK/build-tools/35.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
rm -rf "$OUT"; mkdir -p "$OUT"/{compiled,classes,gen,dex}
$BT/aapt2 compile --dir "$APP/res" -o "$OUT/compiled"
$BT/aapt2 link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 --debug-mode "$OUT"/compiled/*.flat
find "$APP/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$PLATFORM" -nowarn -Xlint:none -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v "^warning\|^注\|^Note\|bootstrap" || true
[ -n "$(find "$OUT/classes" -name '*.class' | head -1)" ] || { echo "javac failed"; exit 1; }
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"
$BT/d8 --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" @"$OUT/classlist.txt"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
$BT/zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=~/.android/debug.keystore
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass android -keypass android -alias androiddebugkey -keyalg RSA -validity 10000 -dname "CN=Android Debug" >/dev/null 2>&1
$BT/apksigner sign --ks $KS --ks-pass pass:android --key-pass pass:android --out "$OUT/Yindun-debug.apk" "$OUT/aligned.apk"
ls -lh "$OUT/Yindun-debug.apk"
