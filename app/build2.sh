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

# 【2026-11-09】签名密码不再写死在这里。
# 源码要进版本库，密码跟着进去就等于把钥匙挂在门上；
# 统一从 local.properties 的 yd.storePassword 读（该文件已被 .gitignore 排除）。
read_prop() { grep -E "^$1=" "local.properties" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'; }
KS_PASS=$(read_prop yd.storePassword)
if [ -z "$KS_PASS" ]; then
  echo "✗ local.properties 里没有 yd.storePassword，无法签名"
  exit 1
fi

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
  -classpath "libs/mindspore-lite-java.jar" \
  -d $OUT/classes \
  @$OUT/sources.txt

echo "[4/6] 转 dex ..."
find $OUT/classes -name "*.class" > $OUT/classlist.txt
# MindSpore Lite 的 Java 接口也要一起转进 dex，否则运行期找不到 com.mindspore.Model
"$BT/d8.bat" --lib "$PLATFORM" --min-api 24 --output $OUT/dex \
  @$OUT/classlist.txt libs/mindspore-lite-java.jar

echo "[5/6] 打包 dex 与原生库进 APK ..."
OUT=$OUT PYTHONPATH= python - <<'PY'
import os, zipfile, shutil
out = os.environ['OUT']
shutil.copy(out + '/base.apk', out + '/app-unsigned.apk')
with zipfile.ZipFile(out + '/app-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(out + '/dex/classes.dex', 'classes.dex')
    # 端侧模型的原生库（MindSpore Lite）。必须放在 lib/<abi>/ 下，系统才知道按 ABI 解压。
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        d = os.path.join('jniLibs', abi)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            z.write(os.path.join(d, f), 'lib/%s/%s' % (abi, f))
            print('   打包 lib/%s/%s' % (abi, f))
print('   完成')
PY

"$BT/zipalign.exe" -f 4 $OUT/app-unsigned.apk $OUT/app-aligned.apk

echo "[6/6] 签名 ..."
"$BT/apksigner.bat" sign \
  --ks yindun.jks \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out Yindun.apk \
  $OUT/app-aligned.apk

echo ""
echo "成功！产物：$(pwd)/Yindun.apk"
ls -lh Yindun.apk | awk '{print " 大小：" $5}'
