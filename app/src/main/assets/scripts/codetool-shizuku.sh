#!/usr/bin/env bash
# codetool-shizuku — 容器内调用 app 的 Shizuku 调试桥（以 shell 身份调试 Android App）。
# 用法:
#   codetool-shizuku status            检查桥是否可用 / app 是否已授权
#   codetool-shizuku exec '<命令>'      以 shell(uid 2000) 身份执行命令，打印输出
#   codetool-shizuku screenshot [路径]  截屏到共享目录并拷到指定路径(默认 /tmp/ct-shot.png)
#   codetool-shizuku uidump [路径]      导出界面控件树 XML 到指定路径(默认 /tmp/ct-ui.xml)
# 底层: 写 /usr/share/tawc/shizuku/requests/<id>.json，轮询 responses/<id>.json。
set -u
BRIDGE=/usr/share/tawc/shizuku
REQ="$BRIDGE/requests"; RES="$BRIDGE/responses"

request() {  # request <action> <extra-json-fields>  → 输出响应 JSON
    local action="$1" extra="${2:-}" id i
    id="cli-$$-$(date +%s%N)"
    if [ ! -d "$REQ" ]; then
        echo '{"ok":false,"message":"桥目录不存在：请在 app 终端里启动过一次容器（桥随 App 常驻）"}'
        return 1
    fi
    mkdir -p "$RES" 2>/dev/null
    printf '{"id":"%s","action":"%s"%s}' "$id" "$action" "$extra" > "$REQ/$id.tmp" 2>/dev/null || {
        echo '{"ok":false,"message":"写入请求失败"}'; return 1; }
    mv "$REQ/$id.tmp" "$REQ/$id.json"      # 原子改名
    i=0
    while [ "$i" -lt 350 ]; do             # 35s × 0.1s
        if [ -f "$RES/$id.json" ]; then
            cat "$RES/$id.json"; rm -f "$RES/$id.json"; return 0
        fi
        sleep 0.1; i=$((i + 1))
    done
    rm -f "$REQ/$id.json"
    echo '{"ok":false,"message":"超时：app 侧未响应"}'
    return 1
}

# 从响应 JSON 里取 message 字段（仅做最小提取，避免引依赖）
json_message() {  # 读 stdin 的 message
    sed -n 's/.*"message":"\([^"]*\)".*/\1/p'
}

cmd="${1:-help}"
case "$cmd" in
    status)
        request "exec" ',"cmd":"id"' | { r=$(cat); msg=$(printf '%s' "$r" | json_message)
            case "$msg" in
                *uid=2000*) echo "Shizuku 调试桥可用：$msg" ;;
                *未授权*|*授权*) echo "未授权：请在 app 首页点「Shizuku」行申请授权。($msg)" ;;
                "") echo "$r" ;;
                *) echo "$msg" ;;
            esac; }
        ;;
    exec)
        c="${2:-}"
        if [ -z "$c" ]; then echo "用法: codetool-shizuku exec '<命令>'"; exit 1; fi
        esc=$(printf '%s' "$c" | sed 's/\\/\\\\/g; s/"/\\"/g')
        request "exec" ",\"cmd\":\"$esc\"" | { msg=$(json_message)
            # message 形如 "exit=N\n<输出>"，去掉前缀直接打印输出
            printf '%s' "$msg" | sed 's/^exit=[-0-9]*\\n//'
            printf '\n'; }
        ;;
    screenshot)
        out="${2:-/tmp/ct-shot.png}"
        request "exec" ',"cmd":"screencap -p /data/data/com.codetool.app/share/shizuku/shot.png"' >/dev/null
        if [ -f "$BRIDGE/shot.png" ]; then
            cp -f "$BRIDGE/shot.png" "$out" 2>/dev/null && echo "截图已保存：$out" || echo "拷贝失败：$BRIDGE/shot.png → $out"
        else
            echo "截屏失败：$BRIDGE/shot.png 未生成（是否已授权？）"
        fi
        ;;
    uidump)
        out="${2:-/tmp/ct-ui.xml}"
        request "exec" ',"cmd":"uiautomator dump /data/data/com.codetool.app/share/shizuku/ui.xml"' >/dev/null
        if [ -f "$BRIDGE/ui.xml" ]; then
            cp -f "$BRIDGE/ui.xml" "$out" 2>/dev/null && echo "控件树已导出：$out" || echo "拷贝失败：$BRIDGE/ui.xml → $out"
        else
            echo "UI dump 失败：$BRIDGE/ui.xml 未生成（界面是否 stable？是否已授权？）"
        fi
        ;;
    help|*)
        echo "用法: codetool-shizuku {status|exec '<命令>'|screenshot [路径]|uidump [路径]}"
        ;;
esac
