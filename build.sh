#!/usr/bin/env bash
# 「图转线稿」一键构建脚本
# 不依赖 Gradle / Android Studio / AndroidX / 任何第三方库，直接用 Android SDK 官方命令行工具构建。
# 用法: ./build.sh [输出APK路径]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"

if [ ! -f "$AJAR" ] || [ ! -x "$BT/aapt2" ]; then
  echo "缺少 Android SDK，请先安装："
  echo "  sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\""
  echo "当前 SDK 路径: $SDK （可用 ANDROID_HOME 覆盖）"
  exit 1
fi

B="$ROOT/build"
OUT="${1:-$ROOT/app/TuZhuanXianGao-v1.1.0.apk}"
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$(dirname "$OUT")"

echo "[1/8] 编译资源 ..."
"$BT/aapt2" compile --dir "$ROOT/res" -o "$B/res.zip"

echo "[2/8] 链接资源、生成 R.java 与基础 APK ..."
# -0 arsc 保证 resources.arsc 未压缩且可 4 字节对齐（Android 11+ 强制要求）
"$BT/aapt2" link -o "$B/base.apk" \
  -I "$AJAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -R "$B/res.zip" \
  --java "$B/gen" \
  -0 arsc \
  --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 2 --version-name "1.1.0"

echo "[3/8] 编译 Java 源码 ..."
find "$ROOT/src" "$B/gen" -name '*.java' > "$B/sources.txt"
javac --release 11 -encoding UTF-8 -nowarn \
  -classpath "$AJAR" -d "$B/classes" @"$B/sources.txt"

echo "[4/8] 转换为 DEX ..."
(cd "$B/classes" && jar cf "$B/classes.jar" .)
"$BT/d8" --lib "$AJAR" --min-api 24 --output "$B/dex" "$B/classes.jar"

echo "[5/8] 合并 classes.dex ..."
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -X "$B/unsigned.apk" classes.dex)

echo "[6/8] zipalign 对齐 ..."
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[7/8] 生成签名密钥并签名 ..."
KS="$B/inkline.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -alias inkline -keyalg RSA -keysize 2048 -validity 10000 \
    -storetype PKCS12 -keystore "$KS" \
    -storepass inkline2026 -keypass inkline2026 \
    -dname "CN=TuZhuanXianGao, OU=OpenSource, O=InkLine, L=CN, ST=CN, C=CN" \
    > /dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:inkline2026 --key-pass pass:inkline2026 \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" "$B/aligned.apk"

echo "[8/8] 校验签名 ..."
"$BT/apksigner" verify --verbose "$OUT" | sed -n '1,6p'
echo ""
echo "构建完成: $OUT"
echo "大小: $(du -h "$OUT" | cut -f1)"
