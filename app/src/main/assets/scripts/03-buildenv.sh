#!/bin/bash
#codetool: 安装构建环境
#codetool-desc: 扫描脚本目录里的 JDK / Android SDK / NDK / build-tools / platform-tools / CMake / Flutter 压缩包；已安装的自动跳过，只装缺的。
#codetool-param: 解压目录（留空默认 /root/buildenv）
#
# 说明：
# - 压缩包后缀可能骗人（如 android-sdk.tar.gz 实为 XZ），一律读文件头 magic
#   判断真实格式，不按扩展名、也不依赖 file 命令（最小 rootfs 常常没装 file）。
# - 已安装检测：优先看 $TARGET/.codetool-installed 里的「压缩包名+大小」标记；
#   旧版脚本装过的目录没有标记，用关键文件（bin/java、toolchains、aapt2…）兜底
#   识别并补记标记，不会因为升级脚本而重解压一遍。
# - 只跳过「同一个压缩包」：换了新版本（文件名/大小不同）会重新解压。
# - 想全部强制重装：FORCE=1 sh 03-buildenv.sh
# - 目录层级按各包实际顶层结构组织到标准 Android SDK 布局，ANDROID_HOME 指向
#   含 build-tools/ platforms/ platform-tools/ 的那一级。

DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET="${1:-/root/buildenv}"
FORCE="${FORCE:-0}"
MARK_DIR="$TARGET/.codetool-installed"
mkdir -p "$TARGET" "$MARK_DIR"

# 文件头 6 字节的十六进制（coreutils od 一定有，不依赖 file）
magic() { od -An -tx1 -N6 "$1" 2>/dev/null | tr -d ' \n'; }

ensure_cmd() {  # $1=命令 $2=包名
  command -v "$1" >/dev/null 2>&1 && return 0
  echo "[提示] 缺 $1，尝试 apt 安装 $2"
  apt-get update -qq && apt-get install -y "$2" || { echo "[错误] 安装 $2 失败，请手动安装后重试"; return 1; }
}

# 按真实格式解压（$1=文件 $2=目标目录）
extract() {
  local f="$1" dest="$2" m
  mkdir -p "$dest" || return 1
  m="$(magic "$f")"
  case "$m" in
    fd377a585a00*)   # XZ
      ensure_cmd xz xz-utils || return 1
      tar -xJf "$f" -C "$dest" || { echo "[失败] XZ 解压失败: $f"; return 1; } ;;
    1f8b*)           # gzip
      tar -xzf "$f" -C "$dest" || { echo "[失败] gzip 解压失败: $f"; return 1; } ;;
    425a68*)         # bzip2
      tar -xjf "$f" -C "$dest" || { echo "[失败] bzip2 解压失败: $f"; return 1; } ;;
    504b0304*)       # zip
      ensure_cmd unzip unzip || return 1
      unzip -qo "$f" -d "$dest" || { echo "[失败] zip 解压失败: $f"; return 1; } ;;
    *)
      # 未压缩 tar：ustar magic 在偏移 257
      if head -c 512 "$f" | tail -c 6 | grep -q ustar; then
        tar -xf "$f" -C "$dest" || { echo "[失败] tar 解压失败: $f"; return 1; }
      else
        echo "[跳过] 未知格式: $f (magic=$m)"; return 1
      fi ;;
  esac
}

nonempty() { [ -d "$1" ] && [ -n "$(ls -A "$1" 2>/dev/null)" ]; }

# ---------------- 已安装检测 ----------------

fsize() { wc -c < "$1" 2>/dev/null | tr -d ' \n'; }

# 安装标记内容 = 压缩包文件名|字节数
mark_installed() {
  printf '%s|%s\n' "$(basename "$2")" "$(fsize "$2")" > "$MARK_DIR/$1" 2>/dev/null
}
is_installed() {
  [ -f "$MARK_DIR/$1" ] || return 1
  [ "$(cat "$MARK_DIR/$1" 2>/dev/null)" = "$(basename "$2")|$(fsize "$2")" ]
}

# ELF aarch64 校验：防止把 SDK 里自带的 x86 adb / aapt2 当成“已安装”
elf_arm64() {
  [ -f "$1" ] || return 1
  [ "$(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' \n')" = "7f454c46" ] || return 1
  [ "$(od -An -tx1 -j18 -N2 "$1" 2>/dev/null | tr -d ' \n')" = "b700" ]
}

have_jdk17() {
  local j; j="$(find "$TARGET/jdk" -type f -path '*/bin/java' 2>/dev/null | head -1)"
  [ -n "$j" ] && [ -f "$(dirname "$(dirname "$j")")/lib/server/libjvm.so" ]
}
have_jdk21() {
  local j; j="$(find "$TARGET/jdk21" -type f -path '*/bin/java' 2>/dev/null | head -1)"
  [ -n "$j" ] && [ -f "$(dirname "$(dirname "$j")")/lib/server/libjvm.so" ]
}
have_sdk() {
  [ -n "$(find "$TARGET/sdk" -maxdepth 3 -type d -name build-tools 2>/dev/null | head -1)" ] && \
    [ -n "$(find "$TARGET/sdk" -maxdepth 3 -type d -name platforms 2>/dev/null | head -1)" ]
}
have_ndk() {
  local p; p="$(find "$TARGET/ndk" -maxdepth 3 -type f -name source.properties 2>/dev/null | head -1)"
  [ -n "$p" ] && [ -d "$(dirname "$p")/toolchains" ]
}
have_buildtools() {
  local v="${BTVER:-36.1.0}"
  elf_arm64 "$(find "$TARGET/sdk" -type f -path "*/build-tools/$v/aapt2" 2>/dev/null | head -1)"
}
have_platformtools() {
  elf_arm64 "$(find "$TARGET/sdk" -maxdepth 6 -type f -path '*/platform-tools/adb' 2>/dev/null | head -1)"
}
have_cmake() {
  local c; c="$(find "$TARGET/cmake" -type f -path '*/bin/cmake' 2>/dev/null | head -1)"
  [ -n "$c" ] && [ -d "$(dirname "$(dirname "$c")")/share" ]
}
have_flutter() {
  [ -n "$(find "$TARGET/flutter" -type f -path '*/bin/flutter' 2>/dev/null | head -1)" ]
}

SKIPPED=0
# $1=组件标记名 $2=压缩包 $3=结构检查函数（旧版安装无标记时兜底）
need_install() {
  if [ "$FORCE" = "1" ]; then
    echo "  FORCE=1：强制重装"
    return 0
  fi
  if [ -f "$MARK_DIR/$1" ]; then
    if is_installed "$1" "$2" && "$3"; then
      echo "  已安装（压缩包未变），跳过"
      SKIPPED=$((SKIPPED + 1))
      return 1
    fi
    echo "  检测到新版本或文件缺失，重新解压"
    return 0
  fi
  if "$3"; then
    mark_installed "$1" "$2"
    echo "  检测到已安装目录（旧版无标记），跳过并补记标记"
    SKIPPED=$((SKIPPED + 1))
    return 1
  fi
  return 0
}

echo "==== 扫描脚本目录: $DIR ===="
# 逐个精确归类（按文件名，避免 r27d/buildtools/platformtools 被关键字漏掉）
for f in "$DIR"/*; do
  [ -f "$f" ] || continue
  b="$(basename "$f")"; l="$(echo "$b" | tr 'A-Z' 'a-z')"
  case "$l" in
    *.tar.gz|*.tgz|*.tar.xz|*.tar.bz2|*.zip|*.tar) ;;
    *) continue ;;
  esac
  case "$l" in
    *jdk17*|*jdk-17*|*jdk_aarch64*17*)
      echo "[JDK17] $b"
      if need_install jdk17 "$f" have_jdk17; then
        extract "$f" "$TARGET/jdk" && mark_installed jdk17 "$f"
      fi ;;
    *jdk21*|*jdk-21*)
      echo "[JDK21] $b（单独存放，不覆盖 JDK17）"
      if need_install jdk21 "$f" have_jdk21; then
        extract "$f" "$TARGET/jdk21" && mark_installed jdk21 "$f"
      fi ;;
    *android-sdk*|*commandlinetools*)
      echo "[SDK] $b"
      if need_install sdk "$f" have_sdk; then
        extract "$f" "$TARGET/sdk" && mark_installed sdk "$f"
      fi ;;
    *r27d*|*ndk*)
      echo "[NDK] $b"
      if need_install ndk "$f" have_ndk; then
        extract "$f" "$TARGET/ndk" && mark_installed ndk "$f"
      fi ;;
    *buildtools*|*build-tools*)
      # 版本号从文件名取（buildtools_36.1.0_... → 36.1.0），不再写死
      BTVER="$(echo "$b" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
      [ -z "$BTVER" ] && BTVER="36.1.0"
      echo "[BUILD-TOOLS] $b → build-tools/$BTVER"
      if need_install buildtools "$f" have_buildtools; then
        extract "$f" "$TARGET/sdk/android-sdk/build-tools/$BTVER" && mark_installed buildtools "$f"
      fi ;;
    *platformtools*|*platform-tools*)
      echo "[PLATFORM-TOOLS] $b"
      if need_install platformtools "$f" have_platformtools; then
        extract "$f" "$TARGET/sdk/android-sdk/platform-tools" && mark_installed platformtools "$f"
      fi ;;
    *4.3.0*|*cmake*)
      echo "[CMAKE] $b"
      if need_install cmake "$f" have_cmake; then
        extract "$f" "$TARGET/cmake" && mark_installed cmake "$f"
      fi ;;
    *flutsdk*|*flutter*)
      echo "[FLUTTER] $b"
      if need_install flutter "$f" have_flutter; then
        extract "$f" "$TARGET/flutter" && mark_installed flutter "$f"
      fi ;;
    *) echo "[跳过] 未归类的包: $b" ;;
  esac
done

# ---- 定位各级目录（find 兜底，兼容顶层带一层目录名的包）----
JAVA_BIN="$(find "$TARGET/jdk" -type f -name java -path '*/bin/*' 2>/dev/null | head -1)"
JAVA_HOME_DIR="${JAVA_BIN%/bin/java}"
NDK_TOOLCHAINS="$(find "$TARGET/ndk" -maxdepth 4 -type d -name toolchains 2>/dev/null | head -1)"
NDK_DIR="${NDK_TOOLCHAINS%/toolchains}"
SDK_DIR="$(find "$TARGET/sdk" -maxdepth 3 -type d -name build-tools 2>/dev/null | head -1 | xargs dirname 2>/dev/null)"
[ -z "$SDK_DIR" ] && SDK_DIR="$TARGET/sdk/android-sdk"
CMAKE_BIN="$(find "$TARGET/cmake" -type f -name cmake -path '*/bin/*' 2>/dev/null | head -1)"
CMAKE_HOME_DIR="${CMAKE_BIN%/bin/cmake}"
FLUTTER_BIN="$(find "$TARGET/flutter" -type f -name flutter -path '*/bin/*' 2>/dev/null | head -1)"
FLUTTER_HOME_DIR="${FLUTTER_BIN%/bin/flutter}"
# 优先选 arm64 的 build-tools（SDK 里可能混着 x86_64 的 aapt2，进容器跑不了）
BT_VER=""
for v in $(ls -1 "$SDK_DIR/build-tools" 2>/dev/null | sort -Vr); do
  if elf_arm64 "$SDK_DIR/build-tools/$v/aapt2"; then BT_VER="$v"; break; fi
done
[ -z "$BT_VER" ] && BT_VER="$(ls -1 "$SDK_DIR/build-tools" 2>/dev/null | sort -V | tail -1)"

# ---- 真实版本号（拿不到就留空，不阻塞）----
JAVA_VER=""
[ -n "$JAVA_HOME_DIR" ] && [ -x "$JAVA_HOME_DIR/bin/java" ] && \
  JAVA_VER="$("$JAVA_HOME_DIR/bin/java" -version 2>&1 | head -1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
NDK_VER=""
[ -n "$NDK_DIR" ] && [ -f "$NDK_DIR/source.properties" ] && \
  NDK_VER="$(grep -E '^Pkg\.Revision' "$NDK_DIR/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')"
CMAKE_VER=""
[ -n "$CMAKE_HOME_DIR" ] && [ -x "$CMAKE_HOME_DIR/bin/cmake" ] && \
  CMAKE_VER="$("$CMAKE_HOME_DIR/bin/cmake" --version 2>/dev/null | head -1 | awk '{print $3}')"
FLUTTER_VER=""
[ -n "$FLUTTER_HOME_DIR" ] && [ -f "$FLUTTER_HOME_DIR/version" ] && \
  FLUTTER_VER="$(head -1 "$FLUTTER_HOME_DIR/version" 2>/dev/null | tr -d ' \r\n')"

# ---- 写环境（profile.d + bashrc，幂等；目录为空说明解压失败，不写变量）----
ENVFILE=/etc/profile.d/codetool-buildenv.sh
{
  echo '# codetool 构建环境（由 03-buildenv.sh 生成）'
  [ -n "$JAVA_HOME_DIR" ] && nonempty "$JAVA_HOME_DIR" && \
    echo "export JAVA_HOME=\"$JAVA_HOME_DIR\"" && echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\""
  [ -n "$NDK_DIR" ] && nonempty "$NDK_DIR" && \
    echo "export ANDROID_NDK_HOME=\"$NDK_DIR\"" && echo "export ANDROID_NDK_ROOT=\"$NDK_DIR\""
  nonempty "$SDK_DIR" && {
    echo "export ANDROID_HOME=\"$SDK_DIR\""
    echo "export ANDROID_SDK_ROOT=\"$SDK_DIR\""
    echo "export PATH=\"\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
    [ -n "$BT_VER" ] && echo "export PATH=\"\$ANDROID_HOME/build-tools/$BT_VER:\$PATH\""
  }
  [ -n "$CMAKE_HOME_DIR" ] && nonempty "$CMAKE_HOME_DIR" && \
    echo "export CMAKE_HOME=\"$CMAKE_HOME_DIR\"" && echo "export PATH=\"\$CMAKE_HOME/bin:\$PATH\""
  [ -n "$FLUTTER_HOME_DIR" ] && nonempty "$FLUTTER_HOME_DIR" && \
    echo "export FLUTTER_HOME=\"$FLUTTER_HOME_DIR\"" && echo "export PATH=\"\$FLUTTER_HOME/bin:\$PATH\""
} > "$ENVFILE"
grep -q codetool-buildenv ~/.bashrc 2>/dev/null || \
  echo '[ -f /etc/profile.d/codetool-buildenv.sh ] && . /etc/profile.d/codetool-buildenv.sh' >> ~/.bashrc
. "$ENVFILE" 2>/dev/null

echo "==== 环境配置完成 ===="
[ "$SKIPPED" -gt 0 ] && echo "本次跳过 $SKIPPED 个已安装组件（FORCE=1 可强制重装）"
echo "JAVA_HOME     = ${JAVA_HOME_DIR:-未找到}${JAVA_VER:+（$JAVA_VER）}"
echo "ANDROID_HOME  = ${SDK_DIR:-未找到}"
echo "ANDROID_NDK   = ${NDK_DIR:-未找到}${NDK_VER:+（$NDK_VER）}"
echo "BUILD-TOOLS   = ${BT_VER:-未找到}"
echo "CMAKE_HOME    = ${CMAKE_HOME_DIR:-未找到}${CMAKE_VER:+（$CMAKE_VER）}"
echo "FLUTTER_HOME  = ${FLUTTER_HOME_DIR:-未找到}${FLUTTER_VER:+（$FLUTTER_VER）}"
echo "--- 生效变量 ---"
env | grep -E 'JAVA_HOME|ANDROID|NDK|CMAKE_HOME|FLUTTER_HOME' || true
command -v aapt2 >/dev/null 2>&1 && echo "aapt2: $(command -v aapt2) ($(file -b "$(command -v aapt2)" 2>/dev/null | cut -d, -f1))"
