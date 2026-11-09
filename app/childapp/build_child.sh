#!/bin/bash
# ============================================================================
#  编译子女端 App（包名 com.yindun.child）
#  和老人端、假银行、微信壳同一套手工工具链，同一把签名钥匙 —— 四台设备可以互装。
#
#  构建前会做一件重要的事：把 local.properties 里的 yd.server 注入成 ServerGen.java，
#  这样真实服务器 IP 只存在于本机，不会跟着源码进仓库。
# ============================================================================
set -e
cd "$(dirname "$0")"

SDK="D:/Android"
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-34/android.jar"
JDK="D:/Java/bin"
OUT="build_$(date +%m%d_%H%M%S)"
LP="../local.properties"

# ---------- 1. 读取本机私有配置 ----------
read_prop() { grep -E "^$1=" "$LP" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '\r'; }

KS_PASS=$(read_prop yd.storePassword)
SERVER=$(read_prop yd.server)

if [ -z "$KS_PASS" ]; then
  echo "✗ local.properties 里没有 yd.storePassword，无法签名"
  exit 1
fi
if [ -z "$SERVER" ]; then
  SERVER="http://YOUR_SERVER:8080"
  echo "! 未配置 yd.server，打出来的包装好之后要在 App 里手动填服务器地址"
fi

# ---------- 2. 注入服务器地址 ----------
mkdir -p src/com/yindun/child
cat > src/com/yindun/child/ServerGen.java <<EOF
package com.yindun.child;

/**
 * 构建时自动生成，不进版本库（见 .gitignore）。
 * 内容取自 local.properties 的 yd.server —— 真实地址永远不落进源码。
 */
final class ServerGen {
    static final String BASE = "$SERVER";
    private ServerGen() { }
}
EOF
echo "[0/6] 已注入服务器地址：$SERVER"

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
  --out ChildDemo.apk \
  $OUT/app-aligned.apk

echo ""
echo "成功！产物：$(pwd)/ChildDemo.apk"
ls -lh ChildDemo.apk | awk '{print " 大小：" $5}'
