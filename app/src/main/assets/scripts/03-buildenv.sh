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

# 选最高版本的 NDK（多版本并存时 env / SDK 软链都用最高版）
pick_highest_ndk() {
  for d in "$TARGET"/ndk/*/; do
    [ -f "$d/source.properties" ] || continue
    [ -d "$d/toolchains" ] || continue
    rev="$(grep -E '^Pkg\.Revision' "$d/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')"
    [ -z "$rev" ] && rev="0"
    echo "$rev|${d%/}"
  done | sort -V | tail -1 | cut -d'|' -f2
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

DATA_DIR="${CODETOOL_DATA_DIR:-/storage/emulated/0/Download/codetool/data}"
echo "==== 扫描构建包 ===="
echo "  脚本目录: $DIR"
if [ -d "$DATA_DIR" ]; then
  echo "  数据目录: $DATA_DIR"
else
  echo "  数据目录: $DATA_DIR（不存在，跳过）"
fi
LIST="/tmp/codetool-buildenv-list.$$"
DEDUP="/tmp/codetool-buildenv-dedup.$$"
: > "$LIST"
# data/ 优先（下载页下载到这里）；脚本目录作为旧版兼容
if [ -d "$DATA_DIR" ]; then
  for f in "$DATA_DIR"/*; do [ -f "$f" ] && echo "$f" >> "$LIST"; done
fi
for f in "$DIR"/*; do [ -f "$f" ] && echo "$f" >> "$LIST"; done
# 按文件名去重（同名包优先用 data/ 的，脚本目录里的旧副本跳过）
awk -F/ '!seen[$NF]++' "$LIST" > "$DEDUP"
while IFS= read -r f; do
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
    *r[0-9][0-9][a-z]*|*ndk*)
      echo "[NDK] $b"
      # 多版本并存：每个压缩包独立标记，装过就跳过；env 稍后选最高版本
      mark_ndk="ndk_${b}"
      if [ "$FORCE" != "1" ] && is_installed "$mark_ndk" "$f"; then
        echo "  已安装（压缩包未变），跳过"
        SKIPPED=$((SKIPPED + 1))
      else
        # 旧版已解压但无 per-version 标记：按 rXX 目录名补记，避免重复解压
        tok="$(echo "$b" | grep -oE 'r[0-9][0-9]' | head -1)"
        old=""
        [ -n "$tok" ] && old="$(find "$TARGET/ndk" -maxdepth 1 -mindepth 1 -type d -name "*${tok}*" 2>/dev/null | head -1)"
        if [ -n "$old" ] && [ -f "$old/source.properties" ] && [ -d "$old/toolchains" ]; then
          mark_installed "$mark_ndk" "$f"
          echo "  检测到已解压目录 $(basename "$old")（旧版无标记），跳过并补记标记"
          SKIPPED=$((SKIPPED + 1))
        else
          echo "  解压 NDK（多版本保留）"
          extract "$f" "$TARGET/ndk" && mark_installed "$mark_ndk" "$f"
        fi
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
done < "$DEDUP"
rm -f "$LIST" "$DEDUP"

# ---- 定位各级目录（find 兜底，兼容顶层带一层目录名的包）----
JAVA_BIN="$(find "$TARGET/jdk" -type f -name java -path '*/bin/*' 2>/dev/null | head -1)"
JAVA_HOME_DIR="${JAVA_BIN%/bin/java}"
NDK_DIR="$(pick_highest_ndk)"
if [ -n "$NDK_DIR" ]; then NDK_TOOLCHAINS="$NDK_DIR/toolchains"; else NDK_TOOLCHAINS=""; fi
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

# ---- NDK 主机架构修正（关键：arm64 容器绝不能落到 x86_64 host 工具，否则 Error 127）----
# 背景：ndk-build 靠 HOST_OS/HOST_ARCH 算 host-tag，arm64 容器算出 linux-aarch64，
# 去 NDK 的 toolchains/llvm/prebuilt/linux-aarch64/ 找 host 工具（clang 等）。
# 但官方/社区 NDK 的 prebuilt 目录命名混乱：
#   - 官方 x86_64 包：只有 prebuilt/linux-x86_64/（arm64 容器跑不动 → Error 127）
#   - 社区 aarch64 包：prebuilt/linux-arm64/ + linux-x86_64→arm64 软链，但常缺 linux-aarch64
#     → ndk-build 找不到 linux-aarch64 → 回落 linux-x86_64（可能指向 arm64，也可能真 x86_64）
# 修法两层：
#   fix_ndk_prebuilt_symlink：给每个 NDK 的 prebuilt 补 linux-aarch64 软链（指向 arm64），幂等。
#   fix_ndk_host_arch：SDK 内 NDK 副本若是 x86_64-only（无任何 arm64 prebuilt），整目录替换为软链 → 预装 aarch64 NDK。
fix_ndk_prebuilt_symlink() {
  local ndk pre_dir
  for ndk in "$TARGET"/ndk/*/ "$SDK_DIR"/ndk/*/; do
    [ -d "$ndk/toolchains/llvm/prebuilt" ] || continue
    pre_dir="$ndk/toolchains/llvm/prebuilt"
    [ -e "${pre_dir%/}/linux-aarch64" ] && continue   # 已有（软链或目录）→ 跳过
    if [ -d "${pre_dir%/}/linux-arm64" ]; then
      ln -s linux-arm64 "${pre_dir%/}/linux-aarch64" && \
        echo "[NDK-HOST] $(basename "$ndk") prebuilt 补软链 linux-aarch64 → linux-arm64"
    elif [ -d "${pre_dir%/}/linux-x86_64" ] && [ -L "${pre_dir%/}/linux-x86_64" ]; then
      # x86_64 是软链（社区包指向 arm64）→ 让 aarch64 也指同一目标
      ln -s linux-arm64 "${pre_dir%/}/linux-aarch64" && \
        echo "[NDK-HOST] $(basename "$ndk") prebuilt 补软链 linux-aarch64 → linux-arm64"
    fi
    # 真 x86_64 目录（非软链）这里不处理，交给 fix_ndk_host_arch 整目录替换
  done
}

fix_ndk_host_arch() {
  local sdk_ndk pre
  [ -d "$SDK_DIR/ndk" ] || return 0
  for sdk_ndk in "$SDK_DIR"/ndk/*/; do
    [ -d "$sdk_ndk" ] || continue
    # 已是软链 → 上次已修，跳过
    [ -L "${sdk_ndk%/}" ] && continue
    # 若该副本已有可用的 arm64 prebuilt（真目录 linux-arm64 或任意 arm64 软链）→ 软链修正即可，不替换
    if [ -d "$sdk_ndk/toolchains/llvm/prebuilt/linux-arm64" ] || \
       [ -L "$sdk_ndk/toolchains/llvm/prebuilt/linux-aarch64" ]; then
      continue
    fi
    # 找预装的 aarch64 NDK（按 NDK 版本号匹配，取第一个匹配的）
    local rel
    rel="$(grep -E '^Pkg\.Revision' "$sdk_ndk/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')"
    pre=""
    for cand in "$TARGET"/ndk/*/; do
      [ -d "$cand" ] || continue
      [ -d "$cand/toolchains/llvm/prebuilt/linux-arm64" ] || \
        [ -d "$cand/toolchains/llvm/prebuilt/linux-aarch64" ] || continue   # 只要 arm64 host 的
      if [ -n "$rel" ]; then
        [ "$(grep -E '^Pkg\.Revision' "$cand/source.properties" 2>/dev/null | cut -d= -f2 | tr -d ' ')" = "$rel" ] && { pre="${cand%/}"; break; }
      fi
    done
    if [ -z "$pre" ]; then
      local hi; hi="$(pick_highest_ndk)"
      if [ -n "$hi" ] && { [ -d "$hi/toolchains/llvm/prebuilt/linux-arm64" ] || [ -d "$hi/toolchains/llvm/prebuilt/linux-aarch64" ]; }; then
        pre="$hi"
      fi
    fi
    [ -z "$pre" ] && continue   # 没有可用的 aarch64 NDK，放弃
    echo "[NDK-HOST] SDK 内 NDK 副本 $(basename "$sdk_ndk") 非 arm64 host → 替换为软链 → $pre"
    rm -rf "${sdk_ndk%/}"
    ln -s "$pre" "${sdk_ndk%/}" && echo "[NDK-HOST] 已软链 $(basename "${sdk_ndk%/}") → $pre"
  done
}
fix_ndk_prebuilt_symlink
fix_ndk_host_arch

# ---- ndk-build 前置系统依赖：make（第六坑：最小 rootfs 常没预装 make）----
# 背景：terminal-emulator 的 libtermux.so 走 ndk-build（Android.mk），ndk-build 本体就是
# 一堆 make 脚本，靠系统 make 驱动。最小 Ubuntu rootfs 往往没预装 make → ndk-build 直接
#   make: *** setup-app.mk:105: clean-installed-binaries] Error 127   （127=command not found）
# gcc/clang 不用装（NDK 自带 clang），但 make 必须有。复用 ensure_cmd（幂等：有就跳过）。
echo "==== 检查 ndk-build 前置系统依赖 ===="
ensure_cmd make make || echo "[警告] make 未就绪，ndk-build（libtermux.so）会失败"

# ---- 第八坑：NDK 自带 prebuilt make 在本容器无法执行 recipe（表象也是 Error 127）----
# 背景：ndk-build 默认**优先**用 NDK 自带的 prebuilt make
#   （$ANDROID_NDK_ROOT/prebuilt/<host-tag>/bin/make，静态链接 musl 二进制），系统 make
#   存在也被忽略。本容器里这个 prebuilt make fork 出的子进程执行**任何** recipe 都静默
#   失败：退出码 127、连 shell 都起不来、无任何报错输出（dry-run `-n` 打印的命令全正常、
#   手动跑 clang 也正常、`ndk-build --version` 也正常，极具迷惑性）。系统 make（动态
#   glibc）跑同一个 makefile 完全正常，可作判别。
# 修法：ndk-build 原生支持 GNUMAKE 环境变量覆盖 make，把它指到系统 make 绕开坏的
#   prebuilt make。系统 make 不存在时不写（正常机器上 prebuilt make 是好的，可回落）。
GNUMAKE_BIN="$(command -v make 2>/dev/null)"
[ -n "$GNUMAKE_BIN" ] && echo "[GNUMAKE] 将强制 ndk-build 使用系统 make: $GNUMAKE_BIN（绕开 NDK 自带 prebuilt make）"

# ---- 第七坑：容器 rootfs 缺 /tmp → ndk-build Error 127（表象是 127，真因是输出目录建不了）----
# 背景：ndk-build 默认把编译产物写 /tmp/<...>（NDK_OUT 默认落 /tmp）。最小 Ubuntu rootfs
#   可能没有 /tmp 目录 → mkdir -p /tmp/... 因父目录不存在而失败 → clang 写 .o 报
#   "No such file or directory" → make 包装成 build-binary.mk:418 Error 127。
#   手动 clang 直编能成功（NDK_OUT 指向已存在的 jniLibs），极具迷惑性——dry-run 也看不出
#   （-n 只打印不执行，mkdir 那步不会真跑）。
# 修法：确保 /tmp 存在且可写（幂等）。这是容器环境问题，不是构建源码问题，归本脚本管。
if [ ! -d /tmp ]; then
  mkdir -p /tmp 2>/dev/null && chmod 1777 /tmp 2>/dev/null
  echo "[TMP] 已创建缺失的 /tmp（ndk-build 输出目录依赖它）"
fi
if [ ! -w /tmp ]; then
  echo "[警告] /tmp 不可写，ndk-build 编译产物可能写不进去（可设 NDK_OUT 到可写目录绕过）"
fi

# ---- 写环境（profile.d + bashrc，幂等；目录为空说明解压失败，不写变量）----
ENVFILE=/etc/profile.d/codetool-buildenv.sh
{
  echo '# codetool 构建环境（由 03-buildenv.sh 生成）'
  [ -n "$JAVA_HOME_DIR" ] && nonempty "$JAVA_HOME_DIR" && \
    echo "export JAVA_HOME=\"$JAVA_HOME_DIR\"" && echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\""
  [ -n "$NDK_DIR" ] && nonempty "$NDK_DIR" && \
    echo "export ANDROID_NDK_HOME=\"$NDK_DIR\"" && echo "export ANDROID_NDK_ROOT=\"$NDK_DIR\""
  # 关键：容器是 aarch64，ndk-build 靠 uname -s 探测 HOST_OS，但 Gradle 子进程 PATH
  # 可能不含 /usr/bin → uname 返回空 → 报「Unable to determine HOST_OS from uname -s:」。
  # 显式 export HOST_OS/HOST_ARCH/HOST_TAG，ndk-build 跳过 uname 探测、且 host 工具解析到
  # prebuilt/linux-aarch64（arm64 容器绝不能落到 linux-x86_64，否则 Error 127——x86_64 clang 跑不动）。
  # 三个都要 export：缺 HOST_ARCH 时 ndk-build 内部重算 host-tag 可能回落 x86_64（实测踩过）。
  echo "export HOST_OS=linux"
  echo "export HOST_ARCH=aarch64"
  echo "export HOST_TAG=linux-aarch64"
  # 第八坑：NDK 自带 prebuilt make（静态 musl）在本容器执行 recipe 静默失败（Error 127），
  # ndk-build 却默认优先用它。GNUMAKE 指到系统 make 绕开（见脚本上方第八坑注释）。
  [ -n "$GNUMAKE_BIN" ] && echo "export GNUMAKE=\"$GNUMAKE_BIN\""
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

# ---- AAPT2 override（第七坑：AGP 自带的 aapt2 是 x86_64，arm64 容器跑不动）----
# 背景：AGP 默认不用 build-tools 里的 aapt2，而是自己从 Google Maven 下载一份 bundle 的
# aapt2（aapt2-<ver>-linux），而 Google 只发布 x86_64 → arm64 容器里 daemon 起不来，报
#   AAPT2 aapt2-8.7.2-...-linux Daemon #N: Daemon startup failed
# 修法：设 android.aapt2FromMavenOverride 指向 build-tools 里的 arm64 aapt2，强制 AGP 用它。
# 写进用户级 ~/.gradle/gradle.properties（配一次永久生效，重下 gradle/清缓存都不用重配；
# 只有重置整个 rootfs 才会丢，而那时会重跑本脚本重新写）。幂等：已配置就更新路径。
AAPT2_BIN=""
[ -n "$SDK_DIR" ] && [ -n "$BT_VER" ] && [ -f "$SDK_DIR/build-tools/$BT_VER/aapt2" ] && \
  AAPT2_BIN="$SDK_DIR/build-tools/$BT_VER/aapt2"
if [ -n "$AAPT2_BIN" ]; then
  GP_DIR="$HOME/.gradle"
  GP="$GP_DIR/gradle.properties"
  mkdir -p "$GP_DIR"
  if [ -f "$GP" ] && grep -q '^android.aapt2FromMavenOverride=' "$GP"; then
    sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$AAPT2_BIN|" "$GP"
    echo "[AAPT2] 已更新 android.aapt2FromMavenOverride → $AAPT2_BIN"
  else
    echo "android.aapt2FromMavenOverride=$AAPT2_BIN" >> "$GP"
    echo "[AAPT2] 已写入 android.aapt2FromMavenOverride → $AAPT2_BIN"
  fi
else
  echo "[AAPT2] 警告：未找到 build-tools 的 aapt2，AGP 会用自带的 x86_64 版（arm64 会失败）"
fi

echo "==== 环境配置完成 ===="
[ "$SKIPPED" -gt 0 ] && echo "本次跳过 $SKIPPED 个已安装组件（FORCE=1 可强制重装）"
echo "JAVA_HOME     = ${JAVA_HOME_DIR:-未找到}${JAVA_VER:+（$JAVA_VER）}"
echo "ANDROID_HOME  = ${SDK_DIR:-未找到}"
echo "ANDROID_NDK   = ${NDK_DIR:-未找到}${NDK_VER:+（$NDK_VER）}"
echo "BUILD-TOOLS   = ${BT_VER:-未找到}"
echo "CMAKE_HOME    = ${CMAKE_HOME_DIR:-未找到}${CMAKE_VER:+（$CMAKE_VER）}"
echo "FLUTTER_HOME  = ${FLUTTER_HOME_DIR:-未找到}${FLUTTER_VER:+（$FLUTTER_VER）}"
echo "--- 生效变量 ---"
env | grep -E 'JAVA_HOME|ANDROID|NDK|CMAKE_HOME|FLUTTER_HOME|GNUMAKE' || true
command -v aapt2 >/dev/null 2>&1 && echo "aapt2: $(command -v aapt2) ($(file -b "$(command -v aapt2)" 2>/dev/null | cut -d, -f1))"
