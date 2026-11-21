#!/usr/bin/env python3
"""codetool-android-mcp — 结构化 Android UI 自动化（Shizuku，shell 身份）。

只提供比原始 `shizuku_exec` 更安全、可复用的 UI 动作：
查看设备/前台、启动 App、打开 URL、导出控件树、点击/滑动/输入、
按文字找控件并点击、截图、通知摘要、分享文本、剪贴板（best-effort）。

底层仍走容器内 `/usr/local/bin/codetool-shizuku`（app 注入的 Shizuku 桥），
不重复实现文件桥；需要 dumpsys / logcat / pm 等任意命令时继续用
`shizuku_exec`。零第三方依赖，只用 Python 标准库。
"""
import json
import os
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
from urllib.parse import urlparse

CLI = os.environ.get("CODETOOL_SHIZUKU_CLI", "/usr/local/bin/codetool-shizuku")
UI_PATH = os.environ.get("CODETOOL_UI_PATH", "/tmp/codetool-ui.xml")
SHOT_PATH = os.environ.get("CODETOOL_SHOT_PATH", "/tmp/codetool-screen.png")
MAX_TEXT = 60000

PKG_RE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$")
ACTIVITY_RE = re.compile(r"^[A-Za-z0-9_.$]+$")
KEY_RE = re.compile(r"^KEYCODE_[A-Z0-9_]+$")
BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
EXIT_RE = re.compile(r"\[exit=(-?\d+)\]")

KEYMAP = {
    "home": "KEYCODE_HOME",
    "back": "KEYCODE_BACK",
    "enter": "KEYCODE_ENTER",
    "delete": "KEYCODE_DEL",
    "recents": "KEYCODE_APP_SWITCH",
    "app_switch": "KEYCODE_APP_SWITCH",
    "power": "KEYCODE_POWER",
    "menu": "KEYCODE_MENU",
    "search": "KEYCODE_SEARCH",
    "tab": "KEYCODE_TAB",
    "escape": "KEYCODE_ESCAPE",
    "volume_up": "KEYCODE_VOLUME_UP",
    "volume_down": "KEYCODE_VOLUME_DOWN",
    "dpad_up": "KEYCODE_DPAD_UP",
    "dpad_down": "KEYCODE_DPAD_DOWN",
    "dpad_left": "KEYCODE_DPAD_LEFT",
    "dpad_right": "KEYCODE_DPAD_RIGHT",
    "dpad_center": "KEYCODE_DPAD_CENTER",
}


def clip(text, limit=MAX_TEXT):
    s = str(text or "")
    if len(s) <= limit:
        return s
    return s[:limit] + "\n...（已截断，原文共 %d 字符）" % len(s)


def run_cli(args, timeout=45):
    """调用 codetool-shizuku，返回 (ok, output)。

    codetool-shizuku exec 会把非 0 退出码写成 stderr 的 [exit=N]，但脚本自身
    仍返回 0，所以这里同时检查 [exit=N]。
    """
    try:
        p = subprocess.run(
            [CLI] + args,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except FileNotFoundError:
        return False, ("codetool-shizuku 未注入：请先在 app 终端启动一次容器，"
                       "或到「工具 → MCP」点「一键修复环境」。")
    except subprocess.TimeoutExpired:
        return False, "执行超时（%d 秒）" % timeout
    out = (p.stdout or "").strip()
    err = (p.stderr or "").strip()
    if err:
        out = (out + "\n" + err).strip()
    m = EXIT_RE.search(out)
    if p.returncode != 0 or (m and int(m.group(1)) != 0):
        return False, out or ("codetool-shizuku 退出码 %d" % p.returncode)
    return True, out


def exec_cmd(cmd, timeout=45):
    ok, out = run_cli(["exec", cmd], timeout=timeout)
    return ok, clip(out)


def _int_arg(a, name, default=None, lo=0, hi=10000):
    raw = a.get(name, default)
    if raw is None:
        raise ValueError("缺少 %s" % name)
    try:
        n = int(raw)
    except (TypeError, ValueError):
        raise ValueError("%s 必须是整数" % name)
    if n < lo or n > hi:
        raise ValueError("%s 超出范围（%d..%d）" % (name, lo, hi))
    return n


def _dump_ui():
    # 清掉旧文件：CLI 超时/失败时不能把上一次的控件树当成本次结果
    try:
        os.remove(UI_PATH)
    except OSError:
        pass
    ok, msg = run_cli(["uidump", UI_PATH])
    if not ok:
        return None, msg
    if not os.path.isfile(UI_PATH):
        return None, msg + "\n未找到导出的控件树文件：%s" % UI_PATH
    try:
        with open(UI_PATH, "r", encoding="utf-8", errors="replace") as f:
            data = f.read()
    except Exception as e:
        return None, "读取控件树失败：%s" % e
    return data, msg


def _nodes(xml_text):
    root = ET.fromstring(xml_text)
    out = []
    for n in root.iter("node"):
        m = BOUNDS_RE.match(n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        out.append({
            "x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "text": n.get("text", ""),
            "desc": n.get("content-desc", ""),
            "rid": n.get("resource-id", ""),
            "cls": n.get("class", ""),
            "clickable": n.get("clickable", ""),
        })
    return out


def _match_nodes(nodes, text, rid, desc, contains):
    found = []
    for n in nodes:
        if text is not None:
            value = n["text"]
            if (text not in value) if contains else (value != text):
                continue
        if rid is not None and n["rid"] != rid:
            continue
        if desc is not None:
            value = n["desc"]
            if (desc not in value) if contains else (value != desc):
                continue
        found.append(n)
    # 同一段文字可能同时出现在父/子节点；优先点面积最小的可见节点。
    found.sort(key=lambda n: ((n["x2"] - n["x1"]) * (n["y2"] - n["y1"]),
                              n["y1"], n["x1"]))
    return found


def _candidate_text(nodes, limit=20):
    rows = []
    for n in nodes:
        label = n["text"] or n["desc"] or n["rid"]
        if not label:
            continue
        rows.append("%s (%d,%d)-(%d,%d)%s" % (
            label, n["x1"], n["y1"], n["x2"], n["y2"],
            " clickable" if n["clickable"] == "true" else ""))
        if len(rows) >= limit:
            break
    return "\n".join(rows)


def tool_status(a):
    ok, st = run_cli(["status"])
    if not ok:
        return st
    ok2, info = exec_cmd(
        "getprop ro.build.version.release; getprop ro.product.model; "
        "wm size; wm density")
    if ok2 and info:
        return st + "\n\n--- 设备 ---\n" + info
    return st


def tool_current_app(a):
    cmd = ("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true; "
           "echo '---'; "
           "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true")
    ok, out = exec_cmd(cmd)
    return out if out else "未找到前台信息（Shizuku 未授权或界面未就绪）"


def tool_launch(a):
    pkg = str(a.get("package", "")).strip()
    if not PKG_RE.match(pkg):
        return "package 不合法：只允许字母、数字、下划线和点，例如 com.example.app"
    activity = str(a.get("activity", "")).strip()
    if activity:
        if not ACTIVITY_RE.match(activity):
            return "activity 不合法：只允许字母、数字、下划线、点和 $"
        cmd = "am start -W -n " + shlex.quote(pkg + "/" + activity)
    else:
        cmd = "monkey -p %s -c android.intent.category.LAUNCHER 1" % shlex.quote(pkg)
    ok, out = exec_cmd(cmd)
    return out


def tool_open_url(a):
    url = str(a.get("url", "")).strip()
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.netloc:
        return "url 只支持 http(s)，且必须包含主机名"
    ok, out = exec_cmd("am start -W -a android.intent.action.VIEW -d " + shlex.quote(url))
    return out


def tool_ui_dump(a):
    data, msg = _dump_ui()
    if data is None:
        return msg
    if bool(a.get("include_xml", False)):
        return msg + "\n\n--- UI XML ---\n" + clip(data)
    return (msg + "\n提示：默认只返回路径；需要 XML 传 include_xml=true；"
            "要按文字点击用 android_tap_text。")


def tool_tap(a):
    x = _int_arg(a, "x")
    y = _int_arg(a, "y")
    ok, out = exec_cmd("input tap %d %d" % (x, y))
    return out if out else ("已点击 (%d,%d)" % (x, y))


def tool_swipe(a):
    x1 = _int_arg(a, "x1")
    y1 = _int_arg(a, "y1")
    x2 = _int_arg(a, "x2")
    y2 = _int_arg(a, "y2")
    duration = _int_arg(a, "duration_ms", default=300, lo=1, hi=10000)
    ok, out = exec_cmd("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, duration))
    return out if out else ("已滑动 (%d,%d) -> (%d,%d)，%dms" % (x1, y1, x2, y2, duration))


def tool_type(a):
    text = str(a.get("text", ""))
    if text == "":
        return "text 不能为空"
    if "\n" in text or "\r" in text:
        return "暂不支持换行输入；请拆成多次调用，或让用户手动输入"
    if any(ord(ch) > 127 for ch in text):
        return ("当前 Android input text 只稳定支持 ASCII；中文/emoji 请让用户手动输入。"
                "如需自动输入中文，需要另装 ADBKeyboard（本版未集成）。")
    escaped = text.replace(" ", "%s")
    ok, out = exec_cmd("input text " + shlex.quote(escaped))
    return out if out else ("已输入：%s" % text)


def tool_key(a):
    raw = str(a.get("key", "")).strip()
    if not raw:
        return "key 不能为空，例如 back / home / enter / KEYCODE_BACK / 4"
    if raw.isdigit():
        code = raw
    else:
        code = KEYMAP.get(raw.lower())
        if not code:
            candidate = raw.upper()
            if not candidate.startswith("KEYCODE_"):
                candidate = "KEYCODE_" + candidate
            if not KEY_RE.match(candidate):
                return "无法识别的 key：%s" % raw
            code = candidate
    ok, out = exec_cmd("input keyevent " + code)
    return out if out else ("已发送按键：%s" % code)


def tool_tap_text(a):
    text = a.get("text")
    rid = a.get("resource_id")
    desc = a.get("content_desc")
    if text is None and rid is None and desc is None:
        return "至少提供 text / resource_id / content_desc 之一"
    contains = bool(a.get("contains", False))
    index = _int_arg(a, "index", default=0, lo=0, hi=1000)

    data, msg = _dump_ui()
    if data is None:
        return msg
    try:
        nodes = _nodes(data)
    except ET.ParseError as e:
        return "解析控件树失败：%s" % e
    found = _match_nodes(nodes, text, rid, desc, contains)
    if not found:
        extra = _candidate_text(nodes)
        return "未找到匹配控件。" + ("\n当前有文字的节点：\n" + extra if extra else "")
    if index >= len(found):
        return "匹配到 %d 个控件，index=%d 越界" % (len(found), index)
    n = found[index]
    cx = (n["x1"] + n["x2"]) // 2
    cy = (n["y1"] + n["y2"]) // 2
    ok, out = exec_cmd("input tap %d %d" % (cx, cy))
    label = n["text"] or n["desc"] or n["rid"]
    if not ok:
        return "找到控件但点击失败：%s\n%s" % (out, label)
    return ("已点击「%s」：(%d,%d)，bounds=(%d,%d)-(%d,%d)，匹配 %d 个%s" % (
        label, cx, cy, n["x1"], n["y1"], n["x2"], n["y2"], len(found),
        "" if index == 0 else "，使用 index=%d" % index))


def tool_screenshot(a):
    try:
        os.remove(SHOT_PATH)
    except OSError:
        pass
    ok, msg = run_cli(["screenshot", SHOT_PATH])
    if not ok:
        return msg
    if not os.path.isfile(SHOT_PATH):
        return msg + "\n未找到截图文件：%s" % SHOT_PATH
    return msg


def tool_clipboard(a):
    action = str(a.get("action", "get")).strip().lower()
    if action not in ("get", "set"):
        return "action 必须是 get 或 set"
    if action == "set":
        text = str(a.get("text", ""))
        if not text:
            return "action=set 时 text 不能为空"
        if len(text) > 10000:
            return "text 太长（最多 10000 字符）"
        cmd = "cmd clipboard set-primary-clip --text " + shlex.quote(text)
    else:
        cmd = "cmd clipboard get-primary-clip"
    ok, out = exec_cmd(cmd)
    bad = (not ok) or (not out) or any(x in out for x in (
        "Unknown command", "Can't find service", "Error:", "Exception"))
    if bad:
        return ("当前 Android 版本不支持 shell 直接%s剪贴板（Android 10+ 常见限制）。\n"
                "请让用户手动复制/粘贴；自动输入请用 android_type（仅 ASCII）。\n"
                "原始输出：%s" % ("写入" if action == "set" else "读取", clip(out, 1000)))
    if action == "set":
        return "已写入剪贴板（best-effort）：%s" % clip(text, 200)
    return "剪贴板内容：\n" + clip(out)


def tool_notifications(a):
    include = bool(a.get("include_content", False))
    limit = _int_arg(a, "limit", default=20, lo=1, hi=200)
    ok, out = exec_cmd("dumpsys notification --noredact", timeout=60)
    if not ok:
        return out
    if include:
        return ("⚠️ 以下包含通知正文，可能含隐私；仅在用户明确要求时使用。\n\n"
                + clip(out))
    pkgs = []
    for m in re.finditer(r"NotificationRecord\([^\n]*?pkg=([A-Za-z0-9_.]+)", out):
        if m.group(1) not in pkgs:
            pkgs.append(m.group(1))
    if not pkgs:
        for m in re.finditer(r"\bpkg=([A-Za-z0-9_.]+)", out):
            if m.group(1) not in pkgs:
                pkgs.append(m.group(1))
    if not pkgs:
        return ("未解析到活动通知包名。可传 include_content=true 查看原始 dumpsys，"
                "但其中可能包含隐私内容。")
    shown = pkgs[:limit]
    return "活动通知包名（不含正文，共 %d 个）：\n%s" % (
        len(pkgs), "\n".join("- " + p for p in shown))


def tool_share_text(a):
    text = str(a.get("text", ""))
    if not text:
        return "text 不能为空"
    cmd = ("am start -a android.intent.action.SEND -t text/plain "
           "--es android.intent.extra.TEXT " + shlex.quote(text))
    ok, out = exec_cmd(cmd)
    return out if out else "已调起分享面板"


def tool_help(a):
    return (
        "codetool-android：结构化 Android UI 自动化（Shizuku，shell 身份）。\n"
        "先 android_status 确认授权，再用 android_current_app / android_launch / "
        "android_ui_dump。\n"
        "点击优先用 android_tap_text（按文字/资源 id 找控件并点中心），"
        "找不到再 android_tap 坐标；\n"
        "输入用 android_type（仅 ASCII），按键用 android_key，滑动用 android_swipe；\n"
        "android_notifications 默认只返回包名，include_content=true 才返回正文；\n"
        "android_share_text 会打开系统分享面板；android_clipboard 仅在系统支持 cmd clipboard 时可用，\n"
        "Android 10+ 常见限制会明确报错，不要假设读写成功。任意 shell 命令仍用 shizuku_exec。"
    )


TOOLS = [
    {
        "name": "android_status",
        "description": "检查 Shizuku 桥 / 授权状态，并返回 Android 版本、型号、屏幕尺寸与密度。做任何 UI 操作前先调用。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "android_current_app",
        "description": "查看当前前台窗口与 Activity（dumpsys window / activity），用于确认操作对象。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "android_launch",
        "description": "启动 Android App。传 package 即可走 Launcher；也可再传 activity（如 .MainActivity）精确启动。",
        "inputSchema": {"type": "object", "properties": {
            "package": {"type": "string", "description": "包名，如 com.example.app"},
            "activity": {"type": "string", "description": "可选 Activity，如 .MainActivity"},
        }, "required": ["package"]},
    },
    {
        "name": "android_open_url",
        "description": "用系统默认浏览器 / 应用打开 http(s) URL（am start VIEW）。",
        "inputSchema": {"type": "object", "properties": {
            "url": {"type": "string", "description": "http:// 或 https:// URL"},
        }, "required": ["url"]},
    },
    {
        "name": "android_ui_dump",
        "description": "导出当前界面控件树 XML。默认只返回容器内路径；include_xml=true 返回 XML（可能较大）。",
        "inputSchema": {"type": "object", "properties": {
            "include_xml": {"type": "boolean", "description": "是否直接返回 XML，默认 false"},
        }},
    },
    {
        "name": "android_tap",
        "description": "按屏幕坐标点击（input tap）。优先用 android_tap_text，坐标点击只在找不到控件时使用。",
        "inputSchema": {"type": "object", "properties": {
            "x": {"type": "integer"}, "y": {"type": "integer"},
        }, "required": ["x", "y"]},
    },
    {
        "name": "android_swipe",
        "description": "按坐标滑动（input swipe），用于滚动列表 / 切换页面。",
        "inputSchema": {"type": "object", "properties": {
            "x1": {"type": "integer"}, "y1": {"type": "integer"},
            "x2": {"type": "integer"}, "y2": {"type": "integer"},
            "duration_ms": {"type": "integer", "description": "默认 300ms"},
        }, "required": ["x1", "y1", "x2", "y2"]},
    },
    {
        "name": "android_type",
        "description": "向当前焦点输入框输入 ASCII 文本（input text）。中文/emoji 会明确拒绝，避免静默输入错误。",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string"},
        }, "required": ["text"]},
    },
    {
        "name": "android_key",
        "description": "发送按键：back / home / enter / recents / power / volume_up / KEYCODE_* / 数字 keycode。",
        "inputSchema": {"type": "object", "properties": {
            "key": {"type": "string"},
        }, "required": ["key"]},
    },
    {
        "name": "android_tap_text",
        "description": "先导出控件树，再按文字 / content-desc / resource-id 找控件并点击其中心。优先用这个，避免猜坐标。",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string", "description": "控件文字（默认精确匹配）"},
            "resource_id": {"type": "string", "description": "如 com.example.app:id/button"},
            "content_desc": {"type": "string", "description": "无障碍描述"},
            "contains": {"type": "boolean", "description": "true 时用包含匹配，默认 false"},
            "index": {"type": "integer", "description": "多个匹配时取第几个，默认 0"},
        }},
    },
    {
        "name": "android_screenshot",
        "description": "截取当前 Android 屏幕，保存到 /tmp/codetool-screen.png，返回容器内路径。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "android_clipboard",
        "description": "best-effort 读写剪贴板。Android 10+ 通常限制 shell 访问，只有系统提供 cmd clipboard 时可用；失败会明确返回限制，不要假设成功。",
        "inputSchema": {"type": "object", "properties": {
            "action": {"type": "string", "description": "get 或 set，默认 get"},
            "text": {"type": "string", "description": "action=set 时要写入的文字"},
        }},
    },
    {
        "name": "android_notifications",
        "description": "读取活动通知摘要。默认只返回包名，不返回正文；include_content=true 才返回完整 dumpsys（可能含隐私）。",
        "inputSchema": {"type": "object", "properties": {
            "include_content": {"type": "boolean", "description": "默认 false"},
            "limit": {"type": "integer", "description": "最多返回多少个包名，默认 20"},
        }},
    },
    {
        "name": "android_share_text",
        "description": "调起系统分享面板分享一段文字（am start SEND text/plain）。",
        "inputSchema": {"type": "object", "properties": {
            "text": {"type": "string"},
        }, "required": ["text"]},
    },
    {
        "name": "android_help",
        "description": "查看 codetool-android 的工具列表、推荐顺序与限制。",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def call_tool(name, a):
    try:
        if name == "android_status":
            return tool_status(a)
        if name == "android_current_app":
            return tool_current_app(a)
        if name == "android_launch":
            return tool_launch(a)
        if name == "android_open_url":
            return tool_open_url(a)
        if name == "android_ui_dump":
            return tool_ui_dump(a)
        if name == "android_tap":
            return tool_tap(a)
        if name == "android_swipe":
            return tool_swipe(a)
        if name == "android_type":
            return tool_type(a)
        if name == "android_key":
            return tool_key(a)
        if name == "android_tap_text":
            return tool_tap_text(a)
        if name == "android_screenshot":
            return tool_screenshot(a)
        if name == "android_clipboard":
            return tool_clipboard(a)
        if name == "android_notifications":
            return tool_notifications(a)
        if name == "android_share_text":
            return tool_share_text(a)
        if name == "android_help":
            return tool_help(a)
        return "未知工具：%s" % name
    except ValueError as e:
        return "参数错误：%s" % e
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
        if "id" not in req:
            continue
        rid, method = req["id"], req.get("method", "")
        params = req.get("params") or {}
        if method == "initialize":
            result = {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "codetool-android", "version": "1.0.0"},
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
                "error": {"code": -32601, "message": "Method not found: " + method},
            }) + "\n")
            sys.stdout.flush()
            continue
        sys.stdout.write(json.dumps(
            {"jsonrpc": "2.0", "id": rid, "result": result},
            ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
