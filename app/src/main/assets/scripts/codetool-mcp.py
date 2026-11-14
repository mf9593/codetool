#!/usr/bin/env python3
"""codetool-mcp — 让 mimo / opencode 通过 MCP 调用 app 的内置浏览器。

stdio 传输（每行一个 JSON-RPC 2.0 消息），只暴露 tools：
  browser_open / browser_list / browser_ws / browser_dom
  browser_screenshot / browser_eval / browser_help

零依赖是有意为之：MCP stdio 只是换行分隔 JSON，容器离线时不保证能
pip install SDK，所以只用 Python 标准库实现；CDP 是标准协议，
WebSocket 客户端仅实现 CDP 需要的单帧文本收发（非通用 WS 库）。

底层通道：
  /usr/share/tawc/browser/  →  Android 侧 BrowserBridge（拉起内置 Chrome）
  http://127.0.0.1:9222      →  Chrome DevTools Protocol（调试）
"""
import base64
import json
import os
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import uuid

BRIDGE = "/usr/share/tawc/browser"
SHIZUKU_BRIDGE = "/usr/share/tawc/shizuku"
CDP = "http://127.0.0.1:9222"
HEADLESS_PROFILE = "/tmp/codetool-headless"

CHROME_BINS = ("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
               "/usr/bin/chromium", "/usr/bin/chromium-browser",
               "/opt/google/chrome/chrome")
INSTALL_HINT = ("浏览器未安装：请在 app 的「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」，"
                "装好后回到终端点「显示」打开 GUI 合成器。不要自行 apt install。")

TOOLS = [
    {
        "name": "browser_status",
        "description": "检查 app 内置浏览器是否已安装、CDP 调试端口与文件桥是否就绪。调用 browser_open 之前应先用它确认；未安装时返回安装指引。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_open",
        "description": "在 app 的内置浏览器（tawc 合成器里的 Chrome）打开并渲染 URL。写好的 HTML/前端页面用它看实际效果，不需要外部浏览器。",
        "inputSchema": {"type": "object", "properties": {
            "url": {"type": "string", "description": "http(s) URL 或 file:// 路径"}
        }, "required": ["url"]},
    },
    {
        "name": "browser_list",
        "description": "列出内置浏览器当前所有页面（URL / 标题 / CDP WebSocket 端点）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_ws",
        "description": "返回第一个页面的 CDP WebSocket 端点，供任意 CDP 客户端连入做深度调试。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_dom",
        "description": "用无头 Chrome 渲染 URL 并返回渲染后的 DOM（检查页面结构 / JS 生成的内容）。不占用可见窗口。",
        "inputSchema": {"type": "object", "properties": {
            "url": {"type": "string"},
            "timeout": {"type": "integer", "description": "秒，默认 40"}
        }, "required": ["url"]},
    },
    {
        "name": "browser_screenshot",
        "description": "用无头 Chrome 给 URL 截图，返回容器内 PNG 路径。",
        "inputSchema": {"type": "object", "properties": {
            "url": {"type": "string"},
            "path": {"type": "string", "description": "输出 PNG 路径，默认 /tmp/codetool-shot.png"}
        }, "required": ["url"]},
    },
    {
        "name": "browser_eval",
        "description": "在内置浏览器已打开的页面上执行 JavaScript 并返回结果（CDP Runtime.evaluate）。",
        "inputSchema": {"type": "object", "properties": {
            "expression": {"type": "string"},
            "tab": {"type": "integer", "description": "页面序号，默认 0"}
        }, "required": ["expression"]},
    },
    {
        "name": "browser_help",
        "description": "查看内置浏览器 MCP 的能力与使用前提（需要先在 app 终端点「显示」打开 GUI 合成器）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "shizuku_status",
        "description": "检查 Shizuku 调试桥是否可用：桥目录是否存在、app 是否已授权 Shizuku（shell 身份）。调用其它 shizuku_* 工具前先用它确认；未授权时返回申请指引。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "shizuku_exec",
        "description": "通过 Shizuku 以 shell（adb，uid 2000）身份在 Android 设备上执行命令并返回输出。用于调试 App：dumpsys 查状态、dumpsys window 查前台、pm/am 管理应用组件、settings 查系统设置、logcat 看日志等。无需 root，无需无线调试。命令经 sh -c 执行，内部 30s 超时。",
        "inputSchema": {"type": "object", "properties": {
            "cmd": {"type": "string", "description": "要执行的 shell 命令，如 'dumpsys activity top'、'dumpsys window | grep mCurrentFocus'、'logcat -d -t 100'"}
        }, "required": ["cmd"]},
    },
    {
        "name": "shizuku_screenshot",
        "description": "通过 Shizuku 截取当前 Android 屏幕（screencap），PNG 保存到共享目录，返回容器内可读的路径。用于看 App 实际界面——比无线调试截图方便，不需要连 adb。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "shizuku_ui_dump",
        "description": "通过 Shizuku 用 uiautomator dump 导出当前界面的控件树（XML），返回容器内可读的路径。用于精确获取 App 界面上每个控件的文字、坐标、resource-id，定位 UI 元素比看截图更准。",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


# ---------- 文件桥：请求 Android 侧拉起 Chrome ----------

def bridge_request(action, timeout=20, bridge=BRIDGE, **kw):
    req_dir, res_dir = bridge + "/requests", bridge + "/responses"
    if not os.path.isdir(req_dir):
        return {"ok": False, "message": "桥目录不存在：请先在 app 终端点「显示」打开 GUI 合成器"}
    os.makedirs(res_dir, exist_ok=True)
    rid = "mcp-" + uuid.uuid4().hex
    payload = dict(id=rid, action=action, **kw)
    tmp = os.path.join(req_dir, rid + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)
    os.rename(tmp, os.path.join(req_dir, rid + ".json"))   # 原子写
    deadline = time.time() + timeout
    out = os.path.join(res_dir, rid + ".json")
    while time.time() < deadline:
        if os.path.isfile(out):
            with open(out, "r", encoding="utf-8") as f:
                data = json.load(f)
            os.remove(out)
            return data
        time.sleep(0.1)
    return {"ok": False, "message": "超时：app 侧未响应"}


# ---------- CDP（标准 HTTP 端点） ----------

def cdp_get(path, timeout=4):
    with urllib.request.urlopen(CDP + path, timeout=timeout) as r:
        return json.load(r)


def cdp_up():
    try:
        cdp_get("/json/version", timeout=1)
        return True
    except Exception:
        return False


def headless(args, timeout=60):
    cmd = ["/usr/bin/google-chrome", "--headless", "--no-sandbox", "--disable-gpu",
           "--disable-dev-shm-usage", "--no-first-run", "--no-default-browser-check",
           "--user-data-dir=" + HEADLESS_PROFILE] + args
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


# ---------- 最小 CDP WebSocket 客户端（只做单帧文本收发） ----------

def ws_call(ws_url, method, params=None, timeout=12):
    rest = ws_url.split("://", 1)[-1]
    host_port, path = rest.split("/", 1)
    host, port = host_port.split(":")
    s = socket.create_connection((host, int(port)), timeout=timeout)
    s.settimeout(timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(("GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\n"
               "Sec-WebSocket-Version: 13\r\n\r\n" % (path, host_port, key)).encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        buf += s.recv(4096)
    if b" 101 " not in buf.split(b"\r\n", 1)[0]:
        s.close()
        raise RuntimeError("CDP WebSocket 握手失败")
    msg = json.dumps({"id": 1, "method": method, "params": params or {}}).encode()
    mask = os.urandom(4)
    n = len(msg)
    head = b"\x81"
    if n < 126:
        head += bytes([0x80 | n])
    elif n < 65536:
        head += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        head += bytes([0x80 | 127]) + struct.pack(">Q", n)
    s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))

    def exact(k):
        out = b""
        while len(out) < k:
            chunk = s.recv(k - len(out))
            if not chunk:
                raise RuntimeError("CDP 连接提前关闭")
            out += chunk
        return out

    # CDP 会先推事件帧，跳过直到拿到 id=1 的响应
    for _ in range(30):
        h = exact(2)
        ln = h[1] & 0x7F
        if ln == 126:
            ln = struct.unpack(">H", exact(2))[0]
        elif ln == 127:
            ln = struct.unpack(">Q", exact(8))[0]
        payload = exact(ln)
        try:
            obj = json.loads(payload)
        except Exception:
            continue
        if obj.get("id") == 1:
            s.close()
            return obj
    s.close()
    raise RuntimeError("未收到 CDP 响应")


# ---------- 工具实现 ----------

def browser_installed():
    """浏览器不预装，由「工具 → 包管理」安装；这里检测 rootfs 里的可执行文件。"""
    return next((p for p in CHROME_BINS if os.path.isfile(p)), None)


def tool_status():
    exe = browser_installed()
    lines = ["内置浏览器：" + ("已安装（%s）" % exe if exe else "未安装")]
    if not exe:
        lines.append(INSTALL_HINT)
    lines.append("CDP 端口 9222：" + ("就绪" if cdp_up() else "未就绪（先在 app 终端点「显示」打开 GUI 合成器）"))
    lines.append("文件桥：" + ("可用" if os.path.isdir(BRIDGE + "/requests") else "不可用（同样需要先打开 GUI 合成器）"))
    return "\n".join(lines)


def tool_open(a):
    if not browser_installed():
        return INSTALL_HINT
    url = a.get("url") or "about:blank"
    if cdp_up():
        try:
            req = urllib.request.Request(CDP + "/json/new?" + url.replace(" ", "%20").replace("&", "%26"),
                                         method="PUT")
            with urllib.request.urlopen(req, timeout=4) as r:
                return r.read().decode()
        except Exception as e:
            return "CDP 新标签失败：%s" % e
    return json.dumps(bridge_request("open", url=url), ensure_ascii=False)


def tool_eval(a):
    expr = a.get("expression", "")
    tab = int(a.get("tab", 0))
    try:
        pages = [p for p in cdp_get("/json/list") if p.get("type") == "page"]
        if not pages:
            return "没有已打开的页面，先调用 browser_open"
        ws = pages[min(tab, len(pages) - 1)]["webSocketDebuggerUrl"]
        r = ws_call(ws, "Runtime.evaluate",
                    {"expression": expr, "returnByValue": True, "awaitPromise": True})
        if "error" in r:
            return "CDP 错误：%s" % r["error"]
        res = r.get("result", {}).get("result", {})
        return json.dumps(res.get("value", res.get("description", res)), ensure_ascii=False, indent=2)
    except Exception as e:
        return "执行失败：%s（浏览器是否已打开？CDP 端口 9222 是否就绪？）" % e


# ---------- Shizuku 调试工具（以 shell 身份调试 App，无需 root / 无线调试） ----------

def shizuku_status():
    if not os.path.isdir(SHIZUKU_BRIDGE + "/requests"):
        return ("Shizuku 桥不可用：共享目录 %s 不存在。\n"
                "请先在 app 终端里启动过一次容器（桥随 App 常驻，不用开 GUI）。" % SHIZUKU_BRIDGE)
    # 授权状态由 app 侧在执行时校验，这里只确认桥目录就绪
    r = shizuku_exec_raw("id")
    if not r.get("ok"):
        return "Shizuku 桥目录就绪，但执行失败：%s\n提示：%s" % (
            r.get("message", "未知错误"),
            "请在 app 首页点「Shizuku」行申请授权（以 shell 身份运行）。")
    last = r.get("message", "").splitlines()[-1] if r.get("message") else ""
    return "Shizuku 调试桥可用。%s" % last


def shizuku_exec_raw(cmd, timeout=35):
    """底层：通过 Shizuku 桥执行 cmd，返回 {ok, message}。message 含 exit=N + 输出。"""
    return bridge_request("exec", timeout=timeout, bridge=SHIZUKU_BRIDGE, cmd=cmd)


def shizuku_exec(a):
    cmd = (a.get("cmd") or "").strip()
    if not cmd:
        return "缺少 cmd"
    r = shizuku_exec_raw(cmd)
    if not r.get("ok"):
        return "Shizuku 执行失败：%s" % r.get("message", "未知错误")
    return r.get("message", "")


def shizuku_screenshot():
    # 走专用 screenshot action：shell 截屏到 /data/local/tmp，app 拷进 share 供容器读
    r = bridge_request("screenshot", timeout=35, bridge=SHIZUKU_BRIDGE)
    if not r.get("ok"):
        return "截屏失败：%s" % r.get("message", "未知错误")
    return "截图已保存：%s（PNG，容器内可直接读）。" % r.get("message", "").split("：")[-1]


def shizuku_ui_dump():
    # 走专用 ui_dump action：uiautomator dump 到 /data/local/tmp，app 拷进 share
    r = bridge_request("ui_dump", timeout=35, bridge=SHIZUKU_BRIDGE)
    if not r.get("ok"):
        return "UI dump 失败：%s" % r.get("message", "未知错误")
    return "控件树已导出：%s（XML，容器内可直接读）。" % r.get("message", "").split("：")[-1]


def call_tool(name, a):
    try:
        if name == "shizuku_status":
            return shizuku_status()
        if name == "shizuku_exec":
            return shizuku_exec(a)
        if name == "shizuku_screenshot":
            return shizuku_screenshot()
        if name == "shizuku_ui_dump":
            return shizuku_ui_dump()
        if name == "browser_status":
            return tool_status()
        if name == "browser_open":
            return tool_open(a)
        if name == "browser_list":
            return json.dumps(cdp_get("/json/list"), ensure_ascii=False, indent=2)
        if name == "browser_ws":
            pages = [p for p in cdp_get("/json/list") if p.get("type") == "page"]
            return pages[0]["webSocketDebuggerUrl"] if pages else "无页面，先 browser_open"
        if name == "browser_dom":
            out = headless(["--dump-dom", a.get("url", "about:blank")],
                           timeout=int(a.get("timeout", 40)))
            return "\n".join(l for l in out.splitlines() if "ERROR:" not in l)[:60000]
        if name == "browser_screenshot":
            path = a.get("path") or "/tmp/codetool-shot.png"
            headless(["--window-size=1280,720", "--screenshot=" + path, a.get("url", "about:blank")])
            return "截图已保存：%s（%s）" % (path, "存在" if os.path.isfile(path) else "未生成")
        if name == "browser_eval":
            return tool_eval(a)
        if name == "browser_help":
            return ("内置浏览器 MCP（浏览器不预装，由「工具 → 包管理」安装）：\n"
                    "先调 browser_status 确认浏览器已装 + GUI 合成器已打开；\n"
                    "browser_open 渲染 URL；browser_list / browser_ws 看页面与 CDP 端点；\n"
                    "browser_dom / browser_screenshot 用无头 Chrome 检查 DOM 与截图；\n"
                    "browser_eval 在已打开页面执行 JS。CDP 端点 %s。\n"
                    "终端等价命令：codetool-browser status / open / list。" % CDP)
        return "未知工具：%s" % name
    except Exception as e:
        return "工具执行失败：%s" % e


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception:
            continue
        if "id" not in req:           # notification（如 initialized），无需响应
            continue
        rid, method = req["id"], req.get("method", "")
        params = req.get("params") or {}
        if method == "initialize":
            result = {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "codetool-browser", "version": "1.0.0"},
            }
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            text = call_tool(params.get("name", ""), params.get("arguments") or {})
            result = {"content": [{"type": "text", "text": str(text)}]}
        elif method == "ping":
            result = {}
        else:
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0", "id": rid,
                "error": {"code": -32601, "message": "Method not found: " + method}
            }) + "\n")
            sys.stdout.flush()
            continue
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": rid, "result": result},
                                   ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
