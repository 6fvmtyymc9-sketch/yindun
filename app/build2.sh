#!/bin/bash
# 无删除版构建：不 rm 任何目录，每次编译到一个新的时间戳目录。
# 原 build.sh 第一步是 rm -rf build，会触发删除守卫，所以这里单独开一份。
set -e
cd "$(dirname "$0")"

SDK="D:/Android"
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-34/android.jar"
JDK="D:/Java/bin"
OUT="build_$(date +%m%d_%H%M%S)"

mkdir -p $OUT/compiled $OUT/classes $OUT/gen $OUT/dex

echo "[1/6] 编译资源 ..."
"$BT/aapt2.exe" compile --dir res -o $OUT/compiled

echo "[2/6] 链接资源 + 打包 assets ..."
"$BT/aapt2.exe" link \
  -o $OUT/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  --java $OUT/gen \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  $OUT/compiled/*.flat

echo "[3/6] 编译 Java ..."
find src $OUT/gen -name "*.java" > $OUT/sources.txt
"$JDK/javac.exe" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "[4/6] 转 dex ..."
find $OUT/classes -name "*.class" > $OUT/classlist.txt
"$BT/d8.bat" --lib "$PLATFORM" --min-api 24 --output $OUT/dex @$OUT/classlist.txt

echo "[5/6] 打包 dex 进 APK ..."
OUT=$OUT PYTHONPATH= python - <<'PY'
import os, zipfile, shutil
out = os.environ['OUT']
shutil.copy(out + '/base.apk', out + '/app-unsigned.apk')
with zipfile.ZipFile(out + '/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(out + '/dex/classes.dex', 'classes.dex')
print('    dex 已写入')
PY

"$BT/zipalign.exe" -f 4 $OUT/app-unsigned.apk $OUT/app-aligned.apk

echo "[6/6] 签名 ..."
"$BT/apksigner.bat" sign \
  --ks yindun.jks \
  --ks-pass pass:yindun123 \
  --key-pass pass:yindun123 \
  --out Yindun.apk \
  $OUT/app-aligned.apk

echo ""
echo "成功！产物：$(pwd)/Yindun.apk"
ls -lh Yindun.apk | awk '{print " 大小：" $5}'
