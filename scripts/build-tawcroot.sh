#!/bin/bash
# build-tawcroot.sh — 在设备（或开发机）上编译 libtawcroot.so 并放进 jniLibs。
#
# 用法（在 ACSIDE 终端或任意有 NDK 的环境）：
#   cd /path/to/codetool
#   bash scripts/build-tawcroot.sh
#
# 前置：ANDROID_NDK_HOME 已设置（ACSIDE 的 NDK 路径通常在
#   $ANDROID_HOME/ndk/<版本>），或本脚本会自动探测。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TAWC_DIR="$PROJECT_DIR/tawcroot-src"
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"

echo "==> CodeTool: 构建 libtawcroot.so"

# 1. 探测 NDK
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
    if [ -d "$ANDROID_HOME/ndk" ]; then
        ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls -1 "$ANDROID_HOME/ndk" | sort -V | tail -1)"
    fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: 找不到 Android NDK。请设置 ANDROID_NDK_HOME。" >&2
    echo "       ACSIDE 通常在: \$ANDROID_HOME/ndk/<版本>" >&2
    exit 1
fi
echo "    NDK: $ANDROID_NDK_HOME"
export ANDROID_NDK_HOME

# 2. clone tawc（若没有）
if [ ! -d "$TAWC_DIR" ]; then
    echo "==> clone tawc 仓库..."
    git clone --depth 1 https://github.com/taowen/tawc.git "$TAWC_DIR"
fi

# 3. 编译 aarch64
echo "==> 编译 tawcroot (aarch64)..."
bash "$TAWC_DIR/tawcroot/build.sh" --abi=aarch64 --clean

# 4. build.sh 会输出到 tawc 仓库的 app/src/main/jniLibs，
#    但我们用自己的项目布局，手动拷贝
SRC="$TAWC_DIR/build/tawcroot-aarch64/libtawcroot.so"
if [ ! -f "$SRC" ]; then
    # build.sh 直接 stage 到 tawc 项目的 jniLibs，兜底从那里找
    SRC="$TAWC_DIR/app/src/main/jniLibs/arm64-v8a/libtawcroot.so"
fi
if [ ! -f "$SRC" ]; then
    echo "ERROR: 编译产物 libtawcroot.so 未找到" >&2
    exit 1
fi

mkdir -p "$JNILIBS"
cp -f "$SRC" "$JNILIBS/libtawcroot.so"
echo "==> 已放到 $JNILIBS/libtawcroot.so"
ls -la "$JNILIBS/libtawcroot.so"

echo "==> 完成。重新构建 APK 即可打包。"
