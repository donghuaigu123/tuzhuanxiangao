#!/usr/bin/env bash
# 桌面端算法自测：生成示例图片、编译纯 Java 核心、跑出线稿与对比预览图
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/build/coretest" "$ROOT/docs"

echo "生成示例资源 ..."
python3 "$ROOT/tools/make_assets.py"

echo "编译核心算法（Java SE，无需 Android SDK）..."
javac -encoding UTF-8 -d "$ROOT/build/coretest" \
  "$ROOT/src/com/inkline/lineart/LineArtCore.java" \
  "$ROOT/src/com/inkline/lineart/ColorCard.java" \
  "$ROOT/tools/CoreTest.java"

echo "运行自测 ..."
java -Djava.awt.headless=true -cp "$ROOT/build/coretest" CoreTest "$ROOT/tools/sample.png" "$ROOT/docs"
