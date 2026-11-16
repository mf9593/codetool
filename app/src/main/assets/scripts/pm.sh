#!/usr/bin/env bash
# pm — Android package manager 转发器。
# 背景：容器 rootfs 是 Ubuntu，没有 Android 的 /system/bin/pm；
# 通过 codetool-shizuku 桥以 shell(uid 2000) 身份在宿主 Android 侧执行真实 pm。
# 用法与 Android pm 一致：pm grant <pkg> <perm> / pm list packages / pm install ...
# 依赖：Shizuku 运行中且 app 已授权（先 codetool-shizuku status 检查）。
set -u

if [ ! -x /usr/local/bin/codetool-shizuku ]; then
    echo "pm: 缺少 codetool-shizuku 桥（先在 app 终端启动一次容器让桥注入）" >&2
    exit 127
fi
if [ $# -eq 0 ]; then
    exec codetool-shizuku exec 'pm'
fi

# 逐参数安全引用：Android 侧经 /system/bin/sh -c 解释整条命令，
# 把每个参数里的 \ " $ ` 转义后用双引号包裹，含空格的参数保持为一个整体。
cmd="pm"
for a in "$@"; do
    esc=$(printf '%s' "$a" | sed 's/[\"$`]/\\&/g')
    cmd="$cmd \"$esc\""
done
exec codetool-shizuku exec "$cmd"
