#!/bin/bash
# 编译演示用「微信壳」App（包名 com.tencent.mm）
# 与主 App 同一套工具链。比假银行多一步：要先编译 res（要一个图标）。
set -e
cd "$(dirname "$0")"

SDK="D:/Android"
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-34/android.jar"
JDK="D:/Java/bin"
OUT="build_$(date +%m%d_%H%M%S)"

# 【2026-11-09】签名密码不再写死在这里。
# 源码要进版本库，密码跟着进去就等于把钥匙挂在门上；
# 统一从 local.properties 的 yd.storePassword 读（该文件已被 .gitignore 排除）。
read_prop() { grep -E "^$1=" "../local.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'; }
KS_PASS=$(read_prop yd.storePassword)
if [ -z "$KS_PASS" ]; then
  echo "✗ local.properties 里没有 yd.storePassword，无法签名"
  exit 1
fi

mkdir -p $OUT/classes $OUT/gen $OUT/dex $OUT/resc

echo "[1/6] 编译资源 ..."
"$BT/aapt2.exe" compile --dir res -o $OUT/resc/res.zip

echo "[2/6] 链接清单 + 资源 ..."
"$BT/aapt2.exe" link \
  -o $OUT/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R $OUT/resc/res.zip \
  --java $OUT/gen \
  --min-sdk-version 24 \
  --target-sdk-version 34

echo "[3/6] 编译 Java ..."
find src $OUT/gen -name "*.java" > $OUT/sources.txt
"$JDK/javac.exe" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -d $OUT/classes \
  @$OUT/sources.txt 2>&1 | grep -v "警告" || true

echo "[4/6] 转 dex ..."
find $OUT/classes -name "*.class" > $OUT/classlist.txt
"$BT/d8.bat" --lib "$PLATFORM" --min-api 24 --output $OUT/dex @$OUT/classlist.txt

echo "[5/6] 打包 + 对齐 ..."
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
  --ks ../yindun.jks \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out WechatDemo.apk \
  $OUT/app-aligned.apk

echo ""
echo "成功！产物：$(pwd)/WechatDemo.apk"
ls -lh WechatDemo.apk | awk '{print " 大小：" $5}'
