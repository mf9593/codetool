#!/usr/bin/env python3
"""codetool-mcp — 让 mimo / opencode 通过 MCP 调用 app 的内置浏览器。

stdio 传输（每行一个 JSON-RPC 2.0 消息），只暴露 tools：
  browser_open / browser_list / browser_ws / browser_dom
  browser_screenshot / browser_eval / browser_help
  shizuku_status / shizuku_exec / shizuku_screenshot / shizuku_ui_dump
  model_list / model_schedule_get / model_schedule_set

零依赖是有意为之：MCP stdio 只是换行分隔 JSON，容器离线时不保证能
pip install SDK，所以只用 Python 标准库实现；CDP 是标准协议，
WebSocket 客户端仅实现 CDP 需要的单帧文本收发（非通用 WS 库）。

底层通道：
  /usr/share/tawc/browser/  →  Android 侧 BrowserBridge（拉起内置 Chrome）
  http://127.0.0.1:9222      →  Chrome DevTools Protocol（调试）
"""
import base64
import datetime
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

BRIDGE = os.environ.get("CODETOOL_BROWSER_BRIDGE", "/usr/share/tawc/browser")
SHIZUKU_BRIDGE = "/usr/share/tawc/shizuku"
CDP = "http://127.0.0.1:9222"
MODELS_FILE = "/root/.config/codetool/ai-models.json"
SCHEDULE_FILE = "/usr/share/tawc/model-schedule.json"
HOLIDAYS_FILE = "/root/.config/codetool/holidays.json"
BEIJING = datetime.timezone(datetime.timedelta(hours=8))

CHROME_BINS = ("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
               "/usr/bin/chromium", "/usr/bin/chromium-browser",
               "/opt/google/chrome/chrome")
INSTALL_HINT = ("MCP 调试需要 Chrome / Chromium：请在 app 的「工具 → 包管理」点「安装 Chrome」。"
                "装好后即可 browser_open <url>（后台无头 Chrome，无需打开 GUI）。"
                "Firefox 可装但仅桌面使用，不支持 browser_* MCP。不要自行 apt install。")

TOOLS = [
    {
        "name": "browser_status",
        "description": "检查 app 内置浏览器是否已安装、CDP 调试端口与文件桥是否就绪。调用 browser_open 之前应先用它确认；未安装时返回安装指引。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "browser_open",
        "description": "在 app 内置浏览器打开 URL。默认带窗口显示进 GUI 合成器——前提是合成器已开、且合成器桌面上的可见 Chrome 已在运行（app 会把 URL 转发给它新开一个前台标签，点开合成器即可看到实时画面）。可见 Chrome 没在跑时无法带窗口（冷启动会崩溃，app 会拒绝并引导先点桌面 Chrome 图标）。传 headless:true 则在后台无头 Chrome 打开（CDP 9222，用于 eval/截图等自动化，不显示窗口）。不需要外部浏览器。",
        "inputSchema": {"type": "object", "properties": {
            "url": {"type": "string", "description": "http(s) URL 或 file:// 路径"},
            "headless": {"type": "boolean", "description": "true=后台无头（不显示窗口）；默认 false=带窗口显示进 GUI 合成器"}
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
        "name": "browser_console",
        "description": "捕获内置浏览器页面的 console 输出（log/info/warn/error）+ 未捕获异常 + 资源加载失败。原理：注入拦截器后刷新页面，回收从加载起的全部日志。调试 UI 没画出来 / JS 报错 / 资源 404 的第一入口。前提：页面已用 browser_open 打开（走后台无头实例 CDP 9222）。",
        "inputSchema": {"type": "object", "properties": {
            "tab": {"type": "integer", "description": "页面序号，默认 0"},
            "settle_ms": {"type": "integer", "description": "刷新后再等异步日志的毫秒数，默认 1500"}
        }},
    },
    {
        "name": "browser_layout",
        "description": "布局/UI 绘制探针：返回某元素的盒模型（x/y/w/h）、可见性（display/visibility/opacity/是否在视口）、命中测试（中心点被谁遮挡）、关键计算样式（color/bg/fontSize/z-index/overflow/position）。调试「UI 为什么没画出来 / 被谁挡住 / 位置不对」。前提：页面已 browser_open（无头实例 CDP 9222）。",
        "inputSchema": {"type": "object", "properties": {
            "selector": {"type": "string", "description": "CSS 选择器，默认 body"},
            "tab": {"type": "integer", "description": "页面序号，默认 0"}
        }, "required": ["selector"]},
    },
    {
        "name": "browser_help",
        "description": "查看内置浏览器 MCP 的能力与使用前提（需在「工具 → 包管理」装 Chrome；后台无头运行，无需打开 GUI）。",
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
    {
        "name": "model_list",
        "description": "列出用户在 app「AI 模型」里配置的全部模型（名称 / 服务商 / 模型 ID / 目标 CLI / 是否当前 / 是否停用）。配置定时切换前先用它确认模型名。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "model_schedule_get",
        "description": "读取 AI 模型定时切换规则：是否启用、高峰时段、高峰/闲时各自绑定的模型、当前北京时间与当前处于高峰还是闲时。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "model_schedule_set",
        "description": "修改 AI 模型定时切换规则（按北京时间 UTC+8，默认 DeepSeek 口径：工作日 09:00–12:00、14:00–18:00 高峰，其余时段含周末/法定节假日为闲时）。只需传要改的字段：enabled / peak1Start / peak1End / peak2Start / peak2End / peakModel / peakTarget / offModel / offTarget / weekendOff / holidayOff。模型名必须存在于 model_list 中。保存后需让用户回 app 一次，系统闹钟才会按新边界重排。",
        "inputSchema": {"type": "object", "properties": {
            "enabled": {"type": "boolean"},
            "peak1Start": {"type": "string", "description": "第一段高峰开始 HH:mm（北京时间，默认 09:00）"},
            "peak1End": {"type": "string", "description": "第一段高峰结束 HH:mm（默认 12:00）"},
            "peak2Start": {"type": "string", "description": "第二段高峰开始 HH:mm（默认 14:00）"},
            "peak2End": {"type": "string", "description": "第二段高峰结束 HH:mm（默认 18:00；与开始相同表示不启用该段）"},
            "peakModel": {"type": "string", "description": "高峰时段使用的模型名称（model_list 里的 name）"},
            "peakTarget": {"type": "string", "description": "高峰模型写入哪个 CLI：mimo / opencode"},
            "offModel": {"type": "string", "description": "闲时使用的模型名称"},
            "offTarget": {"type": "string", "description": "闲时模型写入哪个 CLI：mimo / opencode"},
            "weekendOff": {"type": "boolean", "description": "周六 / 周日是否全天按闲时处理（默认 true）"},
            "holidayOff": {"type": "boolean", "description": "中国法定节假日是否全天按闲时处理（默认 true）"},
        }},
    },
]


# ---------- 文件桥：请求 Android 侧拉起 Chrome ----------

def bridge_request(action, timeout=20, bridge=BRIDGE, **kw):
    req_dir, res_dir = bridge + "/requests", bridge + "/responses"
    if not os.path.isdir(req_dir):
        return {"ok": False, "message": "桥目录不存在：请重启 app（浏览器桥前台服务未就绪）"}
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
    exe = browser_installed()
    if not exe:
        return INSTALL_HINT
    # 每次独立 profile：并发 browser_dom / browser_screenshot 不会抢同一把锁；
    # 用完立即删，避免 /tmp 里堆几十个 Chrome profile。
    profile = tempfile.mkdtemp(prefix="codetool-headless-")
    try:
        cmd = [exe, "--headless", "--no-sandbox", "--disable-gpu",
               "--disable-dev-shm-usage", "--no-first-run", "--no-default-browser-check",
               "--user-data-dir=" + profile] + args
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return ((r.stdout or "") + (r.stderr or "")).strip()
    except subprocess.TimeoutExpired:
        return "无头 Chrome 超时（%d 秒）" % timeout
    except Exception as e:
        return "无头 Chrome 启动失败：%s" % e
    finally:
        shutil.rmtree(profile, ignore_errors=True)


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


def bridge_alive(timeout=2):
    """真正 ping 一下 app 侧 BrowserBridge，而不是只看目录是否存在。

    桥目录由 TawcrootRuntime 在容器启动时就创建，GUI 合成器没开时目录仍在，
    只看目录会把「无人监听」误报成可用。
    """
    if not os.path.isdir(BRIDGE + "/requests"):
        return False
    try:
        return bool(bridge_request("ping", timeout=timeout).get("ok"))
    except Exception:
        return False


def tool_status():
    exe = browser_installed()
    lines = ["内置浏览器：" + ("已安装（%s）" % exe if exe else "未安装")]
    if not exe:
        lines.append(INSTALL_HINT)
    lines.append("CDP 端口 9222：" + ("就绪" if cdp_up() else "未就绪（先 browser_open <url> 拉起后台无头 Chrome）"))
    lines.append("文件桥：" + ("可用" if bridge_alive() else "不可用（请重启 app；"
                "或桥进程没有响应）"))
    return "\n".join(lines)


def create_target(url):
    """用浏览器级 CDP Target.createTarget 开标签，避免 /json/new? 的 URL 编码坑。"""
    ver = cdp_get("/json/version", timeout=4)
    browser_ws = ver.get("webSocketDebuggerUrl")
    if not browser_ws:
        raise RuntimeError("CDP /json/version 没有 webSocketDebuggerUrl")
    r = ws_call(browser_ws, "Target.createTarget", {"url": url})
    if "error" in r:
        raise RuntimeError(str(r["error"]))
    # CDP 建好 target ≠ 页面已渲染：等 document.title 非空再返回，否则下游第一次
    # browser_eval("document.title") 会拿到空串（Chrome 导航 / target 就绪有时序差）。
    # about:blank 等本就无标题的页面等满超时，返回值不受影响。
    wait_title_settled(5)
    return json.dumps(r.get("result", r), ensure_ascii=False, indent=2)


def wait_cdp(seconds=10):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if cdp_up():
            return True
        time.sleep(0.2)
    return False


def _page_title(tab=0):
    """取第 tab 个页面的 document.title；失败返回空串（调用方据其是否为空重试）。"""
    try:
        pages = [p for p in cdp_get("/json/list") if p.get("type") == "page"]
        if not pages:
            return ""
        ws = pages[min(tab, len(pages) - 1)].get("webSocketDebuggerUrl")
        if not ws:
            return ""
        r = ws_call(ws, "Runtime.evaluate",
                    {"expression": "document.title", "returnByValue": True})
        return (r.get("result", {}).get("result", {}).get("value") or "").strip()
    except Exception:
        return ""


def wait_title_settled(seconds=5, tab=0):
    """browser_open 返回后立刻 browser_eval 常拿到空标题（Chrome 导航 / CDP target
    就绪存在时序差）。这里在 open 内部等标题非空再返回，省掉下游第一次 eval 扑空。
    about:blank 等本就无标题的页面会等到超时，用空串区分「真无标题」与「没就绪」。"""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if _page_title(tab):
            return True
        time.sleep(0.25)
    return False


def tool_open(a):
    if not browser_installed():
        return INSTALL_HINT
    url = a.get("url") or "about:blank"
    headless = bool(a.get("headless", False))
    # 默认带窗口：走文件桥让 app 在 GUI 合成器里开带窗口 Chrome（用户能看实时画面）。
    # 不复用后台无头实例——那不会显示窗口。
    if not headless:
        # 注意：service 端只读 headless 字段决定路由（不读 gui）。这里发 gui=True
        # 纯文档性——不带 headless（默认 false）就已让 service 走带窗口分支。
        res = bridge_request("open", url=url, gui=True)
        if res.get("ok"):
            # 成功 = 已转发到 GUI 合成器的可见 Chrome。文案以 service 返回为准
            # （service 已说明是转发到可见实例、点开合成器看画面），不在这里硬编码误导。
            return res.get("message") or "已在 GUI 合成器（带窗口）打开：%s" % url
        # 带窗口失败（合成器没开 / 可见 Chrome 没跑 / 未装浏览器）：service 已给出
        # 具体引导，这里只补一句「可退回无头」。
        return ("%s\n若只想后台自动化（不显示窗口），改用 browser_open 并传 headless:true。"
                % (res.get("message") or "带窗口打开失败"))
    # headless 模式：复用后台无头 Chrome
    if cdp_up():
        try:
            return create_target(url)
        except Exception as e:
            # 极老 Chrome / 浏览器级 WS 不可用时退回 HTTP /json/new；编码保留 :/?&=，
            # 至少不会再把 query 里的 & 错误转义。
            try:
                q = urllib.parse.quote(url, safe=":/?&=%")
                req = urllib.request.Request(CDP + "/json/new?" + q, method="PUT")
                with urllib.request.urlopen(req, timeout=4) as r:
                    return r.read().decode()
            except Exception as e2:
                return "CDP 新标签失败：%s（HTTP 回退也失败：%s）" % (e, e2)
    # headless 分支必须显式带 headless=true：service 端按 headless 字段判定，
    # 只发无标志 open 会被当成带窗口请求（合成器开着时走 GUI 分支返回引导文案）。
    res = bridge_request("open", url=url, headless=True)
    if not res.get("ok"):
        return res.get("message") or "打开失败：app 侧未响应"
    # 拉 Chrome 是异步的：等 CDP 起来再返回，模型紧接着 browser_eval 才不会扑空。
    if wait_cdp(10):
        # CDP 就绪 ≠ 页面已渲染：再等 document.title 非空，避免下游第一次
        # browser_eval("document.title") 拿到空串（实测约需 1~2 秒）。
        wait_title_settled(5)
        return ("已在后台无头浏览器打开：%s\nCDP 端口 9222：就绪" % url)
    return ("已发起打开：%s\nCDP 端口 9222：10 秒内未就绪，"
            "后台无头 Chrome 可能仍在启动；稍后可用 browser_status 重试。" % url)


def _get_page_ws(tab=0):
    """取第 tab 个页面的 CDP WebSocket URL。无页面返回 None。"""
    pages = [p for p in cdp_get("/json/list") if p.get("type") == "page"]
    if not pages:
        return None
    return pages[min(tab, len(pages) - 1)]["webSocketDebuggerUrl"]


def _eval_raw(expr, tab=0):
    """Runtime.evaluate 执行 expr，返回 (ok, data)。
    ok=True 时 data 是 JS 返回值（已取 value/description）；ok=False 时 data 是错误文本。
    供 tool_eval / browser_console / browser_layout 复用。"""
    try:
        ws = _get_page_ws(tab)
        if ws is None:
            return False, "没有已打开的页面，先调用 browser_open"
        r = ws_call(ws, "Runtime.evaluate",
                    {"expression": expr, "returnByValue": True, "awaitPromise": True})
        if "error" in r:
            return False, "CDP 错误：%s" % r["error"]
        payload = r.get("result", {})
        if "exceptionDetails" in payload:
            exc = payload["exceptionDetails"]
            detail = exc.get("exception", {}).get("description") or exc.get("text") or exc
            return False, "JS 异常：%s" % json.dumps(detail, ensure_ascii=False, indent=2)
        res = payload.get("result", {})
        return True, res.get("value", res.get("description", res))
    except Exception as e:
        return False, "执行失败：%s（浏览器是否已打开？CDP 端口 9222 是否就绪？）" % e


def tool_eval(a):
    ok, data = _eval_raw(a.get("expression", ""), tab=int(a.get("tab", 0)))
    if not ok:
        return data if isinstance(data, str) else json.dumps(data, ensure_ascii=False, indent=2)
    return json.dumps(data, ensure_ascii=False, indent=2)


# ---------- 浏览器深度调试：console 捕获 + 布局探针 ----------

# 注入到页面顶层的 console/异常拦截器：把 log/info/warn/error + 未捕获异常 + 资源加载失败
# 存进 window.__ctlogs，供 browser_console 取回。必须在页面脚本前执行（addScriptToEvaluateOnNewDocument）。
_CONSOLE_HOOK = (
    "(function(){if(window.__ctlogs)return;window.__ctlogs=[];"
    "var MAX=300;function rec(lv,args){try{"
    "window.__ctlogs.push({level:lv,text:Array.prototype.map.call(args,function(x){"
    "try{return (typeof x==='string')?x:JSON.stringify(x)}catch(e){return String(x)}}).join(' ')});"
    "if(window.__ctlogs.length>MAX)window.__ctlogs.splice(0,window.__ctlogs.length-MAX);}catch(e){}}"
    "['log','info','warn','error'].forEach(function(k){var o=console[k];console[k]=function(){"
    "rec(k,arguments);try{o.apply(console,arguments)}catch(e){}};});"
    "window.addEventListener('error',function(e){rec('pageerror',[(e.message||'error')+(e.lineno?(' @line '+e.lineno):'')])},true);"
    "window.addEventListener('unhandledrejection',function(e){rec('unhandledrejection',[String(e.reason)])},true);})();"
)


def tool_console(a):
    """捕获页面 console 输出 + 未捕获异常 + 资源加载失败。
    原理：Page.addScriptToEvaluateOnNewDocument 注入拦截器（先于页面脚本执行）→ Page.reload
    重放页面捕获从加载起的全部日志 → Runtime.evaluate 取回 window.__ctlogs。"""
    tab = int(a.get("tab", 0))
    settle = int(a.get("settle_ms", 1500))
    try:
        ws = _get_page_ws(tab)
        if ws is None:
            return "没有已打开的页面，先调用 browser_open（无头实例 CDP 9222）"
        # 1. 注入拦截器（每次新文档加载时先执行）+ 开 Page 域
        ws_call(ws, "Page.enable", {}, timeout=8)
        ws_call(ws, "Page.addScriptToEvaluateOnNewDocument",
                {"source": _CONSOLE_HOOK}, timeout=8)
        # 2. 刷新页面，让拦截器从加载起点捕获（否则只能捕获注入之后的日志）
        ws_call(ws, "Page.reload", {"ignoreCache": False}, timeout=12)
        # 3. 等页面渲染稳定（复用 browser_open 后的等待逻辑），再留 settle_ms 给异步日志
        wait_title_settled(seconds=5, tab=tab)
        time.sleep(settle / 1000.0)
        # 4. 取回日志（_eval_raw 内部会按 tab 重新取刷新后的 ws，不会用到旧连接）
        ok, data = _eval_raw("JSON.stringify(window.__ctlogs||[])", tab=tab)
        if not ok:
            # 兜底：拦截器没生效（可能 addScriptToEvaluateOnNewDocument 晚于页面加载），至少报错
            return "console 捕获失败：%s" % data
        try:
            logs = json.loads(data) if isinstance(data, str) else (data or [])
        except Exception:
            logs = []
        if not logs:
            return "（console 无输出——页面加载期间没有 log/warn/error，也没未捕获异常）"
        lines = []
        for e in logs:
            lv = str(e.get("level", "log")).upper()
            lines.append("[%s] %s" % (lv, e.get("text", "")))
        # 错误类排前方便快速定位（但保留时间顺序做次级排序）
        errs = [l for l in lines if l.startswith("[ERROR]") or l.startswith("[PAGEERROR]")
                or l.startswith("[UNHANDLEDREJECTION]")]
        header = "console 共 %d 条（error/异常 %d 条）：" % (len(lines), len(errs))
        return header + "\n" + "\n".join(lines)
    except Exception as e:
        return "console 捕获异常：%s（CDP 9222 是否就绪？先 browser_open）" % e


def tool_layout(a):
    """布局/UI 绘制探针：返回元素的盒模型、可见性、关键计算样式、命中测试。
    用于调试「UI 为什么没画出来 / 被谁挡住 / 位置不对」。走无头实例 CDP 9222。"""
    selector = (a.get("selector") or "body").strip()
    tab = int(a.get("tab", 0))
    # 转义 selector 里的单引号，避免拼进 JS 字符串时注入
    sel_js = selector.replace("\\", "\\\\").replace("'", "\\'")
    probe = (
        "(function(){var s='%s';var el=document.querySelector(s);"
        "if(!el)return JSON.stringify({found:false,selector:s,"
        "hint:'选择器没匹配到元素（DOM 里不存在或拼写错）'});"
        "var r=el.getBoundingClientRect();var cs=getComputedStyle(el);"
        "var cx=r.left+r.width/2, cy=r.top+r.height/2;"
        "var hit=null;try{hit=document.elementFromPoint(cx,cy)}catch(e){}"
        "var hitDesc=null;"
        "if(hit){hitDesc=hit.tagName.toLowerCase()+(hit.id?('#'+hit.id):'')"
        "+(hit.className&&typeof hit.className==='string'?('.'+hit.className.trim().split(/\\s+/).join('.')):'');}"
        "var o={found:true,selector:s,tag:el.tagName.toLowerCase(),"
        "rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)},"
        "visible:{display:cs.display,visibility:cs.visibility,opacity:cs.opacity,"
        "inViewport:(r.bottom>0&&r.right>0&&r.top<=(window.innerHeight||0)&&r.left<=(window.innerWidth||0))},"
        "hitTest:{centerPoint:[Math.round(cx),Math.round(cy)],topElement:hitDesc,"
        "occluded:(hit&&hit!==el)?true:false},"
        "styles:{color:cs.color,background:cs.backgroundColor,fontSize:cs.fontSize,"
        "zIndex:cs.zIndex,overflow:cs.overflow,position:cs.position}};"
        "return JSON.stringify(o);})()" % sel_js
    )
    ok, data = _eval_raw(probe, tab=tab)
    if not ok:
        return "layout 探针失败：%s" % data
    try:
        obj = json.loads(data) if isinstance(data, str) else data
    except Exception:
        return "layout 返回解析失败：%s" % data
    if not obj.get("found"):
        return "元素未找到：%s（%s）" % (obj.get("selector"), obj.get("hint", ""))
    r = obj.get("rect", {})
    v = obj.get("visible", {})
    h = obj.get("hitTest", {})
    st = obj.get("styles", {})
    occ = "；⚠️ 中心点被遮挡 by %s" % h.get("topElement") if h.get("occluded") else "；中心点命中自身（未被遮挡）"
    return (
        "元素 <%s> 「%s」布局：\n"
        "  盒模型：x=%s y=%s w=%s h=%s\n"
        "  可见性：display=%s visibility=%s opacity=%s 在视口内=%s\n"
        "  命中测试：中心点 %s%s\n"
        "  样式：color=%s bg=%s fontSize=%s z=%s overflow=%s position=%s"
        % (obj.get("tag"), obj.get("selector"),
           r.get("x"), r.get("y"), r.get("w"), r.get("h"),
           v.get("display"), v.get("visibility"), v.get("opacity"), v.get("inViewport"),
           h.get("centerPoint"), occ,
           st.get("color"), st.get("background"), st.get("fontSize"),
           st.get("zIndex"), st.get("overflow"), st.get("position"))
    )


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


# ---------- AI 模型定时切换（与 app 侧 ModelScheduleManager 共用同一份规则文件） ----------
# 只读写规则，不直接改 CLI 配置：真正切换模型由 app 负责（要同时写 mimo/opencode 配置）。
# 北京时间固定 UTC+8，不依赖容器 tzdata。

def _read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _parse_hm(v):
    try:
        h, m = str(v).strip().split(":")
        h, m = int(h), int(m)
        if 0 <= h <= 23 and 0 <= m <= 59:
            return h * 60 + m
    except Exception:
        pass
    return None


_HOLIDAY_CACHE = {"stamp": object(), "dates": set()}


def _holiday_set():
    """中国法定节假日晚期覆盖文件（app 从内置数据注入，ISO 日期数组）。"""
    try:
        stamp = os.path.getmtime(HOLIDAYS_FILE)
    except OSError:
        stamp = None
    if stamp != _HOLIDAY_CACHE["stamp"]:
        data = _read_json(HOLIDAYS_FILE, None)
        _HOLIDAY_CACHE["dates"] = set(str(x) for x in data) if isinstance(data, list) else set()
        _HOLIDAY_CACHE["stamp"] = stamp
    return _HOLIDAY_CACHE["dates"]


def _peak_ranges(s):
    """有效高峰段 [(start, end)]；时间非法或 start == end 视为停用该段。"""
    out = []
    for idx, dft_s, dft_e in (("1", "09:00", "12:00"), ("2", "14:00", "18:00")):
        ps = _parse_hm(s.get("peak%sStart" % idx, dft_s))
        pe = _parse_hm(s.get("peak%sEnd" % idx, dft_e))
        if ps is None or pe is None or ps == pe:
            continue
        out.append((ps, pe))
    return out


def _slot_of(s, now):
    """返回「高峰」/「周末闲时」/「法定节假日闲时」/「闲时」；规则无效返回说明文本。"""
    if not s.get("enabled"):
        return "未启用"
    ranges = _peak_ranges(s)
    if not ranges:
        return "时段无效（没有启用任何高峰段）"
    if s.get("weekendOff", True) and now.weekday() >= 5:
        return "周末闲时"
    if s.get("holidayOff", True) and now.strftime("%Y-%m-%d") in _holiday_set():
        return "法定节假日闲时"
    m = now.hour * 60 + now.minute

    def hit(r):
        ps, pe = r
        return (m >= ps or m < pe) if ps > pe else (ps <= m < pe)

    return "高峰" if any(hit(r) for r in ranges) else "闲时"


def model_list():
    models = _read_json(MODELS_FILE, None)
    if not isinstance(models, list):
        return "模型清单不存在或格式异常：%s（先在 app「AI 模型」页新增模型）" % MODELS_FILE
    if not models:
        return "还没有配置任何模型：请在 app「AI 模型」页新增。"
    lines = ["共 %d 个模型（* = 当前使用）：" % len(models)]
    for m in models:
        if not isinstance(m, dict):
            continue
        flags = []
        if m.get("current"):
            flags.append("当前")
        if not m.get("enabled", True):
            flags.append("已停用")
        lines.append("- %s%s | 服务商 %s | 模型 %s | 目标 %s" % (
            m.get("name", "?"),
            ("（" + "，".join(flags) + "）") if flags else "",
            m.get("provider", "?"), m.get("modelId", "?"), m.get("target", "?")))
    return "\n".join(lines)


def model_schedule_get():
    s = _read_json(SCHEDULE_FILE, None)
    if not isinstance(s, dict):
        return ("尚未配置定时切换（规则文件 %s 不存在）。\n"
                "可用 model_schedule_set 创建，例如：enabled=true, peak1Start=09:00, peak1End=12:00, "
                "peak2Start=14:00, peak2End=18:00, peakModel=..., offModel=... "
                "配置前先调 model_list 看模型名。" % SCHEDULE_FILE)
    now = datetime.datetime.now(BEIJING)
    p1s, p1e = s.get("peak1Start", "09:00"), s.get("peak1End", "12:00")
    p2s, p2e = s.get("peak2Start", "14:00"), s.get("peak2End", "18:00")
    slot = _slot_of(s, now)
    return "\n".join([
        "定时切换：%s" % ("已启用" if s.get("enabled") else "未启用"),
        "当前北京时间：%s（当前时段：%s）" % (now.strftime("%Y-%m-%d %H:%M"), slot),
        "高峰 1：%s — %s（可跨零点）" % (p1s, p1e),
        "高峰 2：%s — %s（开始=结束表示不启用）" % (p2s, p2e),
        "  高峰模型：%s（写入 %s）" % (s.get("peakModel") or "未设置", s.get("peakTarget", "opencode")),
        "闲时：其余所有时段（含午休、夜间、周末与法定节假日，自动）",
        "  闲时模型：%s（写入 %s）" % (s.get("offModel") or "未设置", s.get("offTarget", "opencode")),
        "周末规则：%s" % ("周六 / 周日全天算闲时" if s.get("weekendOff", True) else "周末与工作日相同"),
        "法定节假日规则：%s（内置 2025–2027，可在 app 关闭）"
        % ("全天算闲时" if s.get("holidayOff", True) else "与工作日相同"),
        "行为：到点由 app 的后台闹钟自动切换模型，并通知用户重新打开终端（运行中的会话不会热更新）。",
        "规则文件：%s（app 与 MCP 共用）" % SCHEDULE_FILE,
    ])


def model_schedule_set(a):
    cur = _read_json(SCHEDULE_FILE, None)
    if not isinstance(cur, dict):
        cur = {"enabled": False, "peak1Start": "09:00", "peak1End": "12:00",
               "peak2Start": "14:00", "peak2End": "18:00",
               "peakModel": "", "peakTarget": "opencode",
               "offModel": "", "offTarget": "opencode",
               "weekendOff": True, "holidayOff": True}
    for key in ("peak1Start", "peak1End", "peak2Start", "peak2End"):
        if a.get(key) is not None:
            v = str(a[key]).strip()
            if _parse_hm(v) is None:
                return "时间格式错误：%s 必须是 HH:mm（00:00–23:59）" % v
            cur[key] = v
    models = _read_json(MODELS_FILE, None)
    if not isinstance(models, list):
        models = []

    def find_model(name, target):
        for m in models:
            if not isinstance(m, dict):
                continue
            if m.get("name") == name and m.get("target", "opencode") == target \
                    and m.get("enabled", True):
                return m
        return None

    for side in ("peak", "off"):
        nk, tk = side + "Model", side + "Target"
        if a.get(nk) is not None:
            name = str(a[nk]).strip()
            target = str(a.get(tk) or cur.get(tk) or "opencode").strip()
            if name and find_model(name, target) is None:
                return ("模型「%s」（目标 %s）不存在或已停用，先用 model_list 查看可用模型。"
                        % (name, target))
            cur[nk], cur[tk] = name, target
        elif a.get(tk) is not None:
            cur[tk] = str(a[tk]).strip()
    if a.get("enabled") is not None:
        cur["enabled"] = bool(a["enabled"])
    if a.get("weekendOff") is not None:
        cur["weekendOff"] = bool(a["weekendOff"])
    if a.get("holidayOff") is not None:
        cur["holidayOff"] = bool(a["holidayOff"])
    if cur.get("enabled") and (not cur.get("peakModel") or not cur.get("offModel")):
        return "启用前必须同时设置 peakModel 与 offModel（先调 model_list 选模型）。"
    os.makedirs(os.path.dirname(SCHEDULE_FILE), exist_ok=True)
    tmp = SCHEDULE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cur, f, ensure_ascii=False, indent=2)
    os.replace(tmp, SCHEDULE_FILE)
    return ("已保存定时规则：%s\n\n注意：系统闹钟由 app 维护，"
            "请让用户回 app「AI 模型」页一次（或重开 app），新边界时间才会排进闹钟；"
            "已过边界会立即补切一次。\n\n" % SCHEDULE_FILE) + model_schedule_get()


def call_tool(name, a):
    try:
        if name == "model_list":
            return model_list()
        if name == "model_schedule_get":
            return model_schedule_get()
        if name == "model_schedule_set":
            return model_schedule_set(a)
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
            # 清掉旧文件，避免 headless 失败时把上一次的截图误报成本次成功
            try:
                os.remove(path)
            except OSError:
                pass
            out = headless(["--window-size=1280,720", "--screenshot=" + path,
                            a.get("url", "about:blank")])
            if not os.path.isfile(path):
                return "截图失败：%s" % (out or "无头 Chrome 未生成文件")
            return "截图已保存：%s（存在）" % path
        if name == "browser_eval":
            return tool_eval(a)
        if name == "browser_console":
            return tool_console(a)
        if name == "browser_layout":
            return tool_layout(a)
        if name == "browser_help":
            return ("内置浏览器 MCP（浏览器不预装，由「工具 → 包管理」安装）：\n"
                    "先调 browser_status 确认浏览器已装 + 文件桥可用；\n"
                    "browser_open 在后台无头 Chrome 打开 URL（无需 GUI）；"
                    "browser_list / browser_ws 看页面与 CDP 端点；\n"
                    "browser_dom / browser_screenshot 用无头 Chrome 检查 DOM 与截图；\n"
                    "browser_eval 在已打开页面执行 JS。\n"
                    "深度调试：browser_console 捕获 console + 未捕获异常 + 资源加载失败（UI 报错第一入口）；"
                    "browser_layout 探针返回元素盒模型/可见性/命中测试/计算样式（查 UI 为什么没画出来或被遮挡）。"
                    "CDP 端点 %s。\n"
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
