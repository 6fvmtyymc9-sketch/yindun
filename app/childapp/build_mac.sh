#!/bin/bash
# Mac 版子女端打包（build_child.sh 是 Windows 版）：可调试包，本机 debug 签名。
# 用法：bash app/childapp/build_mac.sh [服务器地址]   默认 http://10.0.2.2:8080（模拟器访问本机）
# 产物：app/childapp/build_mac/ChildDemo-debug.apk
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"; OUT="$DIR/build_mac"
SERVER="${1:-http://10.0.2.2:8080}"
SDK=~/Library/Android/sdk; BT=$SDK/build-tools/35.0.0; PLATFORM=$SDK/platforms/android-34/android.jar
rm -rf "$OUT"; mkdir -p "$OUT"/{classes,gen,dex}
mkdir -p "$OUT/gen/com/yindun/child"
cat > "$OUT/gen/com/yindun/child/ServerGen.java" <<J
package com.yindun.child;
final class ServerGen { static final String BASE = "$SERVER"; private ServerGen() { } }
J
$BT/aapt2 compile --dir "$DIR/res" -o "$OUT/res.zip"
$BT/aapt2 link -o "$OUT/base.apk" -I "$PLATFORM" --manifest "$DIR/AndroidManifest.xml" -R "$OUT/res.zip" \
  --java "$OUT/gen" --min-sdk-version 24 --target-sdk-version 34 --debug-mode
find "$DIR/src" "$OUT/gen" -name "*.java" ! -name ServerGen.java -path "*src*" > "$OUT/sources.txt"
echo "$OUT/gen/com/yindun/child/ServerGen.java" >> "$OUT/sources.txt"
find "$OUT/gen" -name "R.java" >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v "^warning\|^注\|bootstrap" || true
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"
$BT/d8 --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" @"$OUT/classlist.txt"
cp "$OUT/base.apk" "$OUT/unsigned.apk"; (cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
$BT/zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android --out "$OUT/ChildDemo-debug.apk" "$OUT/aligned.apk"
ls -lh "$OUT/ChildDemo-debug.apk"
