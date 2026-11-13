#!/bin/bash
# codetool-browser —— 让容器内 CLI / AI 模型调用 app 的「内置浏览器」
# （tawc 合成器里渲染的 Chrome），不需要外部浏览器。
#
# 通道：/usr/share/tawc/browser/{requests,responses}
#   app 把 dataDir/share bind 到 /usr/share/tawc；这里原子写请求 JSON，
#   Android 侧 BrowserBridge(FileObserver) 处理后写响应 JSON，脚本轮询读取。
# 调试：Chrome DevTools Protocol http://127.0.0.1:9222（标准协议，任意 CDP 客户端可连）
#
# 浏览器不预装：由 app 的「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」安装。
#
# 用法：
#   codetool-browser status       检查浏览器是否已安装 / CDP / 文件桥状态
#   codetool-browser open <url>   在内置浏览器渲染（未启动则拉起 Chrome，并开 CDP）
#   codetool-browser new  <url>   新标签打开（Chrome 已在跑时走 CDP）
#   codetool-browser list         CDP 页面列表：URL / title / webSocketDebuggerUrl
#   codetool-browser ws           第一个页面的 webSocketDebuggerUrl（供模型连 CDP）
#   codetool-browser port         CDP 端点与浏览器版本
#   codetool-browser help         本说明
set -u

BRIDGE=/usr/share/tawc/browser
REQ="$BRIDGE/requests"
RES="$BRIDGE/responses"
CDP=http://127.0.0.1:9222

usage() {
    cat <<'EOF'
codetool-browser — 用 app 内置浏览器渲染 / 调试网页
（浏览器不预装：先到 app「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」）

  codetool-browser status         浏览器是否已装 / CDP / 文件桥状态
  codetool-browser open <url>     渲染 URL（必要时拉起 Chrome）
  codetool-browser new  <url>     新标签
  codetool-browser list           列出页面（CDP /json/list）
  codetool-browser ws             打印首个页面的 CDP WebSocket 端点
  codetool-browser port           CDP 端点 + 版本

调试（标准 Chrome DevTools Protocol，不需要额外浏览器）：
  WS=$(codetool-browser ws | sed 's/.*"webSocketDebuggerUrl": *"\([^"]*\)".*/\1/')
  python3 - "$WS" <<'PY'
import sys, json, websocket            # pip install websocket-client
ws = websocket.create_connection(sys.argv[1])
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                    "params": {"expression": "document.title", "returnByValue": True}}))
print(ws.recv())                       # console / 截图用 Log.enable、Page.captureScreenshot
PY

提示：若 open 超时，请先在 app 终端点「显示」打开 GUI 合成器（桥随合成器启动）。
EOF
}

# JSON 字符串转义（只处理脚本会用到的反斜杠 / 双引号 / 换行）
jstr() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' '; }

# 浏览器是否已装（不预装，由「工具 → 包管理」安装）
browser_bin() {
    for p in /usr/bin/google-chrome /usr/bin/google-chrome-stable \
             /usr/bin/chromium /usr/bin/chromium-browser /opt/google/chrome/chrome; do
        [ -f "$p" ] && { echo "$p"; return 0; }
    done
    return 1
}
INSTALL_HINT='浏览器未安装：请在 app 的「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」，装好后回到终端点「显示」打开 GUI 合成器。'

# Chrome 是否已带 CDP 在跑
cdp_up() { curl -s --max-time 1 "$CDP/json/version" 2>/dev/null | grep -q webSocketDebuggerUrl; }

# 发请求给 Android 侧并等待响应（最多 20s）
request() {
    local payload="$1" id i
    id="r$$-$(date +%s%N)"
    if [ ! -d "$REQ" ]; then
        echo '{"ok":false,"message":"桥目录不存在：请先在 app 终端点「显示」打开 GUI 合成器"}'
        return 1
    fi
    mkdir -p "$RES" 2>/dev/null
    printf '{"id":"%s",%s}' "$id" "$payload" > "$REQ/$id.tmp" 2>/dev/null || {
        echo '{"ok":false,"message":"请求写入失败（共享目录不可写）"}'; return 1
    }
    mv "$REQ/$id.tmp" "$REQ/$id.json"      # 原子改名，避免 FileObserver 读到半截
    i=0
    while [ "$i" -lt 200 ]; do
        if [ -f "$RES/$id.json" ]; then
            cat "$RES/$id.json"
            rm -f "$RES/$id.json"
            return 0
        fi
        sleep 0.1
        i=$((i + 1))
    done
    rm -f "$REQ/$id.json"
    echo '{"ok":false,"message":"超时：app 侧未响应（请先打开 GUI 合成器）"}'
    return 1
}

cmd="${1:-help}"
shift 2>/dev/null || true

case "$cmd" in
    status)
        b=$(browser_bin) || b=""
        if [ -n "$b" ]; then echo "内置浏览器：已安装（$b）"
        else echo "内置浏览器：未安装"; echo "$INSTALL_HINT"; fi
        if cdp_up; then echo "CDP 端口 9222：就绪"
        else echo "CDP 端口 9222：未就绪（先在 app 终端点「显示」打开 GUI 合成器）"; fi
        if [ -d "$REQ" ]; then echo "文件桥：可用"
        else echo "文件桥：不可用（需先打开 GUI 合成器）"; fi
        ;;
    open|new)
        if ! browser_bin >/dev/null 2>&1; then
            printf '{"ok":false,"message":"%s"}\n' "$INSTALL_HINT"
            exit 1
        fi
        url="${1:-about:blank}"
        if cdp_up; then
            # Chrome 已在跑：用 CDP 直接开新标签，绝不再起第二个 Chrome
            # （同一 user-data-dir 重复启动会静默失败）
            qurl=$(printf '%s' "$url" | sed -e 's/ /%20/g' -e 's/&/%26/g')
            curl -s -X PUT "$CDP/json/new?$qurl"
            echo
            exit 0
        fi
        request "\"action\":\"open\",\"url\":\"$(jstr "$url")\""
        ;;
    list) curl -s "$CDP/json/list" ;;
    ws)   curl -s "$CDP/json/list" | grep -o '"webSocketDebuggerUrl": *"[^"]*"' | head -1 ;;
    port) echo "$CDP"; curl -s "$CDP/json/version" ;;
    help|*) usage ;;
esac
