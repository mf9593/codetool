package com.codetool.app.ai

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.util.Jsonc
import java.io.File

/**
 * Skill 管理：**扫描两个 CLI 实际会加载的所有全局 skill 目录**，并写回原位置。
 *
 * 扫描来源（容器内路径）：
 * - `~/.agents/skills`                   共享目录（agentskills.io 标准，两个 CLI 都认）
 * - `~/.config/mimocode/skills`          MiMo Code 专属
 * - `~/.config/opencode/skills`          OpenCode 专属
 * - `~/.claude/skills`                   OpenCode 默认兼容（MiMo 需显式开启）
 * - `~/.codex/skills`                    MiMo 显式开启的兼容目录
 * - 配置里的 `skills` 数组（绝对路径 / `~/`，HTTP 目录跳过）
 *
 * 目录式 `<root>/<id>/SKILL.md` 与单文件 `<root>/<id>.md` 都支持。
 * 编辑导入的 skill 时写回它原来的文件，不会在 `.agents` 里再造一份；
 * 新建的 skill 默认写共享目录 `~/.agents/skills`。
 *
 * 所有读取实时读盘、不缓存。
 */
object SkillManager {

    data class Skill(
        val name: String,
        val id: String,
        val description: String,
        val body: String,
        val file: File,
        /** 来源标签，如「共享目录」「MiMo Code」「OpenCode」。 */
        val source: String,
        /** 展示用容器路径（`/root` 显示为 `~`）。 */
        val pathText: String,
    )

    private val NAME_RE = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

    /** 共享目录：app 新建 skill 写这里，两个 CLI 都识别。 */
    const val SKILLS_REL = "root/.agents/skills"

    /**
     * 内置 skill 的 id 集合（必须与 [ensureBuiltin] 注册的完全一致）。
     * Skill 页据此显示「内置」徽标——之前只判断 codetool-browser，
     * 导致另外 4 个内置 skill 看起来像用户自建。
     */
    val BUILTIN_IDS: Set<String> = setOf(
        "codetool-browser", "codetool-shizuku", "codetool-system",
        "multi-search-engine", "dev-god", "app-debug-rules",
        "gradle-mirror",
    )

    /** 标准扫描根（相对容器根）。 */
    private val ROOTS = listOf(
        SKILLS_REL to "共享目录",
        "root/.config/mimocode/skills" to "MiMo Code",
        "root/.config/opencode/skills" to "OpenCode",
        "root/.claude/skills" to "Claude 兼容",
        "root/.codex/skills" to "Codex 兼容",
    )

    private fun root(ctx: Context) = RootfsManager.get(ctx.applicationContext).containerRoot
    private fun dir(ctx: Context) = File(root(ctx), SKILLS_REL)

    fun skillsDirPath(ctx: Context) = "/${SKILLS_REL.removePrefix("root/")}"

    fun listSkills(ctx: Context): List<Skill> = listSkills(ctx, root(ctx))

    private fun listSkills(ctx: Context, base: File): List<Skill> {
        val roots = buildList {
            ROOTS.forEach { (rel, label) -> add(File(base, rel) to label) }
            addAll(configSkillRoots(ctx, base))
        }
        val out = mutableListOf<Skill>()
        roots.distinctBy { it.first.absolutePath }.forEach { (d, label) ->
            if (!d.isDirectory) return@forEach
            // 目录式：<root>/<id>/SKILL.md
            d.listFiles { f -> f.isDirectory }?.forEach { sub ->
                val f = File(sub, "SKILL.md")
                if (f.isFile) out += readSkill(f, sub.name, label, base)
            }
            // 单文件式：<root>/<id>.md（OpenCode / MiMo 都支持）
            d.listFiles { f -> f.isFile && f.extension.equals("md", true) }?.forEach { f ->
                out += readSkill(f, f.nameWithoutExtension, label, base)
            }
        }
        return out.sortedWith(compareBy({ it.name.lowercase() }, { it.source }))
    }

    private fun readSkill(f: File, id: String, label: String, base: File): Skill {
        val txt = runCatching { f.readText() }.getOrDefault("")
        val (name, desc) = frontmatter(txt)
        val rel = f.absolutePath.removePrefix(base.absolutePath).trimStart('/')
        val pathText = ("/$rel").replaceFirst(Regex("^/root(?=/|$)"), "~")
        return Skill(
            name = name.ifEmpty { id },
            id = id,
            description = desc,
            body = stripFrontmatter(txt),
            file = f,
            source = label,
            pathText = pathText,
        )
    }

    /** 配置里 `"skills": [...]` 指向的额外本地目录（只认绝对路径与 ~/）。 */
    private fun configSkillRoots(ctx: Context, base: File): List<Pair<File, String>> {
        val cfgs = listOf(
            "root/.config/mimocode/mimocode.jsonc" to "MiMo Code",
            "root/.config/mimocode/mimocode.json" to "MiMo Code",
            "root/.config/opencode/opencode.json" to "OpenCode",
            "root/.config/opencode/opencode.jsonc" to "OpenCode",
        )
        val out = mutableListOf<Pair<File, String>>()
        for ((rel, label) in cfgs) {
            val f = File(base, rel)
            if (!f.isFile) continue
            val obj = runCatching { Jsonc.parse(f.readText())?.obj }.getOrNull() ?: continue
            val arr = obj.optJSONArray("skills") ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isEmpty() || v.startsWith("http://") || v.startsWith("https://")) continue
                val d = when {
                    v == "~" -> File(base, "root")
                    v.startsWith("~/") -> File(base, "root/" + v.removePrefix("~/"))
                    v.startsWith("/") -> File(base, v.removePrefix("/"))
                    else -> null      // 相对路径取决于 CLI 的工作目录，无法静态解析
                } ?: continue
                if (d.isDirectory) out += d to "$label 自定义"
            }
        }
        return out
    }

    /** 返回 null=保存成功；否则是可展示给用户的错误原因。 */
    fun upsert(
        ctx: Context,
        existing: Skill?,
        name: String,
        description: String,
        body: String,
    ): String? {
        val trimmedName = name.trim()
        // 低危：编辑也校验 NAME_RE（原名可能被改；空串不匹配 NAME_RE，覆盖「不能为空」）。
        if (!NAME_RE.matches(trimmedName))
            return "名称只能小写字母/数字/连字符（如 my-skill），且与目录名一致"
        if (description.isBlank() || description.length > 1024)
            return "description 必填且不超过 1024 字符"
        if (body.isBlank()) return "指令正文不能为空"
        return runCatching {
            // 编辑导入的 skill：写回原文件（可能是别的 CLI 的目录）
            val f = existing?.file
                ?: File(File(dir(ctx), trimmedName).apply { mkdirs() }, "SKILL.md")
            f.parentFile?.mkdirs()
            // 低危：原子写（tmp→rename），避免写一半失败损坏 SKILL.md。
            val content = "---\nname: $trimmedName\ndescription: ${description.replace("\n", " ").trim()}\n---\n\n" +
                body.trim() + "\n"
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(f)) { tmp.delete(); f.writeText(content) }
            null
        }.getOrElse { it.message ?: "保存失败" }
    }

    /** 删除 skill 本身（目录式删整个目录，单文件式只删文件）。 */
    fun remove(skill: Skill): Boolean = runCatching {
        if (skill.file.name == "SKILL.md") skill.file.parentFile?.deleteRecursively() == true
        else skill.file.delete()
    }.getOrDefault(false)

    private const val BUILTIN_DESC =
        "用 app 内置浏览器渲染网页并调试前端；写完 HTML/JS 后打开页面、执行 JS、截图、查 DOM，不依赖外部浏览器。"

    private val BUILTIN_BODY = """
# 用 app 内置浏览器渲染与调试网页

这个 app 自带浏览器能力：MCP / CLI 用**后台无头 Chrome**（CDP 9222），**不需要打开 GUI 合成器**。
只有用户想手动看页面时，才需要到终端点「显示」打开 GUI。
浏览器**不预装**，由用户在 app 里一键安装。

## 前置（每次先确认）
1. 调 `browser_status` 检查三件事：浏览器是否已装、CDP 9222 是否就绪、文件桥是否可用。
2. 若浏览器未安装，告诉用户：到 app 的「工具 → 包管理」点「安装 Chrome」。
   **不要自行 apt install**。装好后 MCP 会后台无头启动，不需要点「显示」。
   （Firefox 可以装在桌面手动使用，但 browser_* MCP 只支持 Chrome / Chromium 的 CDP。）
3. CDP / 文件桥未就绪时，先等 `browser_status` 自愈；仍失败再让用户检查 Chrome 是否装好，
   **不要**默认让用户打开 GUI 合成器。

## 通过 MCP 工具（推荐）
- `browser_status()`：上面的前置检查。
- `browser_open(url)`：打开并渲染 URL / 本地 HTML 文件（file://）。
- `browser_list()` / `browser_ws()`：列出页面、拿 CDP WebSocket 端点。
- `browser_dom(url)`：无头渲染取回 DOM，检查结构与 JS 生成内容。
- `browser_screenshot(url, path)`：截图到容器内 PNG。
- `browser_eval(expression)`：在已打开页面执行 JS，读运行结果 / 变量 / console。

## 通过终端命令（等价）
`codetool-browser status` / `open <url>` / `list` / `port`。
CDP 端点固定 `http://127.0.0.1:9222`，可用任意 CDP 客户端连入做深度调试。

## 典型流程
browser_status → 写前端代码 → browser_open(file:///path/index.html) →
browser_dom 或 browser_eval 检查渲染结果 → browser_screenshot 看视觉 →
发现问题改代码 → 重开验证。
""".trimIndent()

    private const val SHIZUKU_DESC =
        "用 Shizuku 以 shell 身份调试 Android App：结构化 UI 自动化（启动、控件树、按文字点击、滑动、输入、按键、通知、分享）以及截屏、跑 dumpsys/logcat/pm 等命令，比无线调试方便，无需连 adb。"

    private val SHIZUKU_BODY = """
# 用 Shizuku 调试 Android App（截屏 / 界面树 / shell 命令）

这个 app 内置了 Shizuku 桥，能让你以 **shell（adb，uid 2000）** 身份在 Android 设备上执行命令、
**截屏**、**导出界面控件树**，用来调试正在运行的 App。**比无线调试方便**——不需要用户连 adb、
不需要 `adb connect`，直接调工具即可。

## 前置（每次先确认）
1. 调 `shizuku_status()` 检查桥是否可用、app 是否已授权 Shizuku。
2. 若未授权，告诉用户：到 app **首页点「Shizuku」那一行**申请授权（以 shell 身份运行），
   授权后重试。**不要**让用户去连无线调试。
3. 桥目录不存在时，提示用户在 app 终端里启动过一次容器（桥随 App 常驻，不用开 GUI）。

## 通过 MCP 工具（推荐）
- `shizuku_status()`：前置检查——桥目录 + 授权状态。
- `shizuku_exec(cmd)`：以 shell 身份执行任意命令并返回输出。常用：
  - `dumpsys activity top` — 当前前台 Activity / 栈顶
  - `dumpsys window | grep mCurrentFocus` — 当前焦点窗口（判断哪个界面在前台）
  - `dumpsys package <pkg>` — 查某应用的版本、权限、组件
  - `logcat -d -t 200` — 最近 200 行日志（排查崩溃 / 报错）
  - `pm list packages | grep <关键字>` — 查包名
  - `am start -n <pkg>/<activity>` — 启动某界面（调试用）
  - `settings get global <key>` / `settings put ...` — 查/改系统设置
- `shizuku_screenshot()`：截取当前屏幕，返回容器内 PNG 路径（直接看界面长什么样）。
- `shizuku_ui_dump()`：导出当前界面控件树 XML，返回容器内路径（精确拿每个控件的
  文字 / 坐标 / resource-id，定位 UI 元素比看截图准）。

## 结构化 UI 自动化（`codetool-android` MCP，优先）
`codetool-android` 在同一个 Shizuku 授权之上提供更安全的固定动作，不需要自己拼 shell 命令；
`shizuku_exec()` 仍保留给 dumpsys / logcat / pm 等任意命令。

- `android_status()` / `android_current_app()`：先确认授权与当前前台。
- `android_launch(package, activity?)`：启动 App；`android_open_url(url)`：用系统应用打开 http(s)。
- `android_ui_dump(include_xml?)`：导出控件树；默认只回路径，避免一次塞入巨大 XML。
- `android_tap_text(text/resource_id/content_desc, contains?, index?)`：**优先用它**，
  内部先 dump 再找控件中心点击，避免猜坐标。
- `android_tap(x,y)` / `android_swipe(x1,y1,x2,y2,duration_ms?)`：找不到控件时才用坐标。
- `android_type(text)`：向焦点输入 ASCII；中文 / emoji 会明确拒绝，需让用户手动输入。
- `android_key(key)`：back / home / enter / recents / KEYCODE_* / 数字 keycode。
- `android_screenshot()`：截屏到容器 `/tmp/codetool-screen.png`，返回路径。
- `android_notifications(include_content?, limit?)`：默认只回包名；只有用户明确要求时才传
  `include_content=true`，因为通知正文可能含隐私。
- `android_share_text(text)`：调起系统分享面板。
- `android_clipboard(action=get/set, text?)`：**best-effort**。Android 10+ 通常限制 shell
  访问剪贴板，只有系统提供 `cmd clipboard` 时可用；失败会明确报错，不要假设读写成功。
  需要可靠粘贴时让用户手动操作，或让用户在前台输入框里输入。

推荐顺序：`android_status` → `android_current_app` → `android_launch` → `android_ui_dump`
→ `android_tap_text` → `android_type` / `android_key` → 再次 `android_ui_dump` 确认结果。

## 典型调试流程
shizuku_status → `shizuku_screenshot()` 看界面 → `shizuku_ui_dump()` 拿控件树定位元素 →
`shizuku_exec("dumpsys activity top")` 确认前台 → `shizuku_exec("logcat -d -t 200")` 看日志 →
定位问题。看截图用截图路径，查控件用 ui_dump 路径，都能在容器里直接 cat / 打开。

## 注意
- 命令以 **shell（uid 2000）** 身份跑，权限等同 adb；**不能**访问需要 root 的东西。
- 命令经 `sh -c` 执行，支持管道 / 重定向；内部 30s 超时，长命令会被掐断。
- 截屏 / ui_dump 的输出文件写在共享目录，返回的容器路径可直接读。
""".trimIndent()

    private const val SYSTEM_DESC =
        "终端里跑的是 rootfs 里的 Ubuntu 系统（由 tawcroot 模拟 chroot 运行，无 root 权限、原理类似 PRoot 的单进程版本）：可直接跑 ping / curl / apt / adb 等系统命令安装组件、做网络诊断。装通用工具先确认包里有没有，没有就用 apt 装（容器内 root 是模拟映射，不代表 Android 已 root），装完必须验证。需要新能力可从 skill 商店搜索安装 skill，推荐优先用腾讯 SkillHub（skillhub.cn），其它商店备用；装前必须安全审查。"

    private val SYSTEM_BODY = """
# rootfs Ubuntu 系统环境（可直接调系统命令）

**你在终端 / 容器里跑的是 Ubuntu（aarch64）的 rootfs**，不是模拟器、不是玩具 shell——
能直接执行系统命令，装组件、跑诊断、做网络排查都行。这页告诉你怎么正确用它。

## 环境（tawcroot 模拟 chroot，**无 root**）
- 系统：Ubuntu（aarch64）rootfs，由 **tawcroot** 运行——它**模拟 chroot**（ptrace 拦截
  系统调用、把路径重写到 rootfs），**原理类似 PRoot 但用单进程实现、更快**；**不是**真正的
  `chroot(2)`（那需要 root，Android app 没有），**更不是 proot 程序**。
- 用户：容器内是 **root（模拟映射的 uid 0）**，包管理走 `apt`——但这**不代表 Android 已
  root**，也不代表能访问需要真 root 的设备资源（内核模块、真实 mount 等）。
- 限制：共享宿主 Android 内核，无 systemd（容器内直接 /bin/bash 流程）；网络与 app 共享
  （tawcroot 不隔离 netns），loopback 与宿主机互通。

## 可以直接执行的系统命令（无需额外桥接）
- `ping <ip|域名>` — ICMP 可达性（网络诊断第一查）
- `curl -v <url>` / `wget` — HTTP 请求、下载
- `ss -tulpn` / `netstat` — 端口 / 连接状态
- `apt list --installed` / `apt-cache search <包>` — 查包
- `adb devices` — 本机 Android 调试（容器里装了 platform-tools 时）
- `nslookup` / `dig` / `getent hosts` — DNS 解析
- `lsblk` / `df -h` / `free -m` — 磁盘 / 内存

## 装通用工具（按需，装完必须验证）
1. 先判断已装：`command -v ping` / `ping --version`（真实自检，别假定装好了）。
2. 没装且是通用工具（ping/curl/vim/jq/ffmpeg 等），**先确认包名能否装**，
   安装用 `apt-get update && apt-get install -y <包>`（容器内是模拟映射的 root）。
3. **装完必须验证**：`command -v <bin>` + 跑一次 `--version` / 实际功能，
   不能只看到 apt 输出 install 成功就当装好（可能依赖缺失跑不起来）。
4. 装不动（缺仓库 / 缺依赖）就提示用户，别反复重试浪费时间。

## 脚本运行（Permission denied / exit 126）
- 脚本报 `Permission denied`，退出码 **126** = 没有可执行权限（缺 shebang 或文件系统无 exec），
  **脚本根本没启动**，不是环境故障。
- 解决：**用解释器显式跑**——`bash xxx.sh` / `sh xxx.sh` / `python3 xxx.py`，
  比 `chmod +x` 可靠（跨挂载盘 chmod 可能无效）。`chmod +x` 无效时也改走解释器。
- **先在前台跑一遍确认输出正常，再放后台**（`nohup ... &` / 任务管理器），
  别直接后台启动没验证过的命令。

## Skill 商店（按需扩展能力）

想增强能力时，可以从 skill 商店搜索/安装现成的 skill，装到容器内
`~/.agents/skills/<名字>/SKILL.md`（MiMo Code 与 OpenCode 都默认扫描该目录）。
**推荐优先用腾讯 SkillHub 安装**；以下其它商店只作备用（腾讯 SkillHub 搜不到/装不了时再用），
自行搜索比较后判断哪个满足需求；**装任何 skill 前必须先做安全审查**（见下）。

1. **腾讯 SkillHub（推荐首选）**：按官方文档安装——
   `curl -s https://skillhub.cn/install/skillhub.md` 读文档后按其说明安装 CLI 并使用。
2. **SkillStore**（备用）— https://skillstore.io/zh-hans
3. **SkillsMP**（备用）— https://skillsmp.com/zh
4. **人人 Skill**（备用）：根据 https://rrskill.cn/install/rrskill.md 安装 rrskill CLI。
5. **Skills.sh**（备用）— https://www.skills.sh/
6. **LobeHub Skills**（备用）：阅读 https://lobehub.com/skills/skill.md，
   并按照说明设置 LobeHub Skills Marketplace。
7. **讯飞 Astron SkillHub**（备用）— https://skill.xfyun.cn/

**安全审查（装任何 skill 前，必须逐条做）：**
1. 先解压/下载到临时目录，**通读 SKILL.md 与全部脚本代码**，不要只看描述。
2. 危险信号：`rm -rf`、`curl ... | bash` 盲执行、base64 混淆内容、向陌生域名外传数据
   （文件/剪贴板/环境变量）、索要 API key 或账号密码、要求关闭安全设置。
3. 有疑点 → **不装**，向用户说明原因；用户明确确认后才可继续。
4. 审查通过再移入 `~/.agents/skills/`，装完验证 CLI 能识别（重新进 skill 列表确认）。

## 注意（浏览器等特殊软件）
- 依赖 app 前置能力的（如内置浏览器调试）走对应 skill（`codetool-browser`），
  它要求检测浏览器是否已装、未装时引导 app 包管理安装——**不要自行 apt install 浏览器**。

## 读取 CodeTool 自身日志（同容器直接读，不需要 MCP）
- 运行日志镜像在 `/usr/share/tawc/logs/codetool.log`（超过 2MB 轮转为 `.1`），
  崩溃日志在 `/usr/share/tawc/logs/crash/crash-*.txt`。
- 直接用 `tail -n 200 /usr/share/tawc/logs/codetool.log`、`grep -n "关键字" ...`
  或读崩溃文件；这些是 app 应用日志，不是容器系统日志（dmesg/journalctl），
  Android logcat 用 `shizuku_exec("logcat -d -t 200")`。
- 日志 MCP 已移除；直接读上面两个路径即可，不需要再调 `codetool_log_*` 工具。
- 日志可能含文件路径、命令输出等敏感信息，不要原样复述 token / 密钥。

## 在容器内自建 APK（codetool 项目，进阶）

**什么时候用**：改了 codetool 的 Kotlin/Java/XML/assets，要在容器里自己编译出 APK
（而不是靠 app host 侧构建）。原生库 `libtawcroot.so`/`libtawc_compositor.so` 是**预编译**
的（直接在 `jniLibs/`，不需要 NDK）；只有 `terminal-emulator` 模块的 `libtermux.so` 是
**源码编译**（`src/main/jni/termux.c` + Android.mk），走 ndk-build——arm64 容器的坑全在这条链上，
但**都已由 03-buildenv.sh 自动配好**（HOST_ARCH / NDK host 软链 / GNUMAKE / aapt2 override），照下面流程走即可。

### 前置：装构建环境
1. 先跑「工具 → 包管理」或脚本库里的 **03-buildenv.sh**（扫 JDK/SDK/NDK/build-tools
   压缩包，缺的才装）。装完它会把环境写进 `/etc/profile.d/codetool-buildenv.sh`。
   **路径机制**：脚本扫描**自己所在目录**（`dirname "${'$'}0"`）里的 `*.tar.gz`/`*.xz` 等压缩包，
   解压到**目标目录** `TARGET`（默认 `/root/buildenv`，可用第一个参数改）；已装的记在
   `/root/buildenv/.codetool-installed`（按「包名|大小」判重复，跳过），换版本会重装。
   所以压缩包得和 03-buildenv.sh 在同一目录，或把该目录作为参数传进去。
2. **每个新终端先 source**（否则 `ndk-build`/`aapt2` 不在 PATH）：
   ```sh
   . /etc/profile.d/codetool-buildenv.sh
   ```
   它会 export `ANDROID_HOME`/`ANDROID_NDK_HOME`/`JAVA_HOME`/`HOST_OS=linux`/
   `HOST_TAG=linux-aarch64` 等。

### 七个必踩的坑（按报错对号入座）

1. **`Unable to determine HOST_OS from uname -s:`（uname 输出空）**
   ndk-build 靠 `uname -s` 探测主机 OS，但 **Gradle 子进程的 PATH 可能不含
   `/usr/bin`**（容器 uname 在 `/usr/bin/uname`）→ uname 返回空 → 直接 exit 1。
   手动跑 `uname -s` 正常、Gradle 跑就空，就是这个。
   **修**：确保 source 了 buildenv（它 export `HOST_OS=linux HOST_TAG=linux-aarch64`），
   ndk-build 认这两个变量、直接跳过 uname。没生效就构建前手动：
   ```sh
   export HOST_OS=linux HOST_TAG=linux-aarch64
   ```

2. **`Unknown host CPU architecture: aarch64`**
   AGP 首次构建会把 NDK 自动下载/复制到 `ANDROID_HOME/ndk/<ver>`，这个**副本常是
   x86_64 host**，容器 aarch64 跑不了。而 03-buildenv.sh 装的预装 NDK
   （`/root/buildenv/ndk/android-ndk-r27d`）是 aarch64 host、能用。
   **修**：新版 03-buildenv.sh 会在装完时自动把 SDK 内非 aarch64 的 NDK 副本
   **替换成软链 → 预装 aarch64 r27d**（看输出 `[NDK-HOST]` 行）。旧装没修的话手动
   （`NDKVER` 取 terminal-emulator 的 `ndkVersion`，当前 27.3.13750724）：
   ```sh
   SDK_NDK=/root/buildenv/sdk/android-sdk/ndk/27.3.13750724
   rm -rf "${'$'}SDK_NDK" && ln -s /root/buildenv/ndk/android-ndk-r27d "${'$'}SDK_NDK"
   ndk-build --version                          # 应显示 Built for aarch64
   ```

3. **local.properties 的 sdk.dir 指向不存在的路径**
   `local.properties` 里的 sdk.dir/ndk.dir **必须指向当前构建环境真实存在的 SDK/NDK**：
   - **容器内构建** → 用 buildenv 装的路径（先 `. /etc/profile.d/codetool-buildenv.sh`，
     再 `echo "${'$'}ANDROID_HOME"` / `echo "${'$'}ANDROID_NDK_HOME"` 看实际值）：
     ```
     sdk.dir=<ANDROID_HOME 的值，如 /root/buildenv/sdk/android-sdk>
     ndk.dir=<ANDROID_NDK_HOME 的值，如 /root/buildenv/ndk/android-ndk-r27d>
     ```
   - **host 侧（ACSIDE）构建** → 用 host 路径（如 `/data/data/com.acside/files/home/android-sdk`）。
   ⚠️ 两个环境共用一份项目目录时，改了 local.properties 会让另一边构建报
   `sdk.dir ... Directory does not exist`——切环境构建前记得改回对应路径
   （宿主原值备份在 local.properties.bak）。报这个错就是这个原因。

4. **gradlew 无执行位（FUSE 不认 chmod）**
   项目在 `/storage/emulated/0`（FUSE 挂载），`chmod +x gradlew` 改了不生效。
   **修**：别依赖执行位，用解释器跑 `sh gradlew ...`。

5. **AGP 的 SdkHandler 与 CXX 插件解析出两套路径**（`-P` / 环境变量 / 清缓存都压不过）
   CXX 插件日志里 ndk 解析正确，但 ndk-build 任务实际用的 sdkFolder 仍是旧路径。
   **修**：别硬压，直接建软链把旧路径指到新 SDK 兜底：
   ```sh
   mkdir -p /data/data/com.acside/files/home
   ln -sfn /root/buildenv/sdk/android-sdk /data/data/com.acside/files/home/android-sdk
   ```
   （软链是兜底，正解是问题 3 把 local.properties 改对。）

6. **ndk-build 报 `Error 127`（编 `libtermux.so` 挂，dry-run / 手动 clang 都正常）**
   现象：Gradle 跑 `:terminal-emulator:buildNdkBuildDebug` 挂
   `make: *** .../build-binary.mk:418: .../objs-debug/termux] Error 127`，但
   `ndk-build -n`（dry-run）打印的命令全正常、手动跑 clang 也正常、`ndk-build --version`
   也正常——没有任何「command not found」输出，极具迷惑性。
   **根因（本容器实测）**：ndk-build **默认优先用 NDK 自带的 prebuilt make**
   （`${'$'}ANDROID_NDK_ROOT/prebuilt/<host-tag>/bin/make`，静态 musl 二进制），系统 make
   存在也被忽略；这个 prebuilt make 在本容器 fork 出的子进程执行**任何** recipe 都静默
   失败（退出码 127、连 shell 都起不来）。判别：用 NDK prebuilt make 跑一个只含
   `echo hi` 的 makefile → 127；系统 make 跑同一文件 → 正常。
   **修**：`export GNUMAKE=/usr/bin/make`（ndk-build 原生支持该变量，指到系统 make）。
   **新版 03-buildenv.sh 会自动写进 buildenv**（装环境时检测系统 make 并 export，source
   后即生效）。手动验证：`GNUMAKE=/usr/bin/make ndk-build ...` 能编出 libtermux.so。
   另：prebuilt 目录缺 `linux-aarch64` 软链、`make` 没装、缺 `/tmp` 也会表现为 127
   （脚本已自动修，见坑 1 与脚本内注释）。

7. **`AAPT2 ... Daemon startup failed`（arm64 容器资源编译必踩）**
   资源编译时 AAPT2 daemon 起不来。根因：AGP **不用 build-tools 里的 aapt2**，而是自己从
   Google Maven 下载一份 bundle 的 aapt2（`aapt2-<ver>-linux`），而 **Google 只发 x86_64**
   → arm64 容器跑不动（`file` 它是 `x86-64`，直接跑报 `Exec format error`）。你 build-tools
   里的 aapt2 是 arm64（能跑），但 AGP 默认不理它。
   **修**：设 `android.aapt2FromMavenOverride` 指向 build-tools 的 arm64 aapt2，强制 AGP 用它：
   ```sh
   mkdir -p ~/.gradle
   echo "android.aapt2FromMavenOverride=/root/buildenv/sdk/android-sdk/build-tools/<版本>/aapt2" \
     >> ~/.gradle/gradle.properties
   sh gradlew --stop    # 让 daemon 重读属性
   ```
   **新版 03-buildenv.sh 会自动写这条**（装环境时检测 build-tools 的 aapt2 路径写进去），
   配一次永久生效——重下 gradle/清缓存都不用重配，只有重置整个 rootfs 才丢（那时重跑脚本）。

8. **`mergeDebugNativeLibs` 报「2 files found with path 'lib/arm64-v8a/libtermux.so'」**
   现象：NDK 构建修好后，`:terminal-emulator:mergeDebugNativeLibs` 挂：
   `2 files found with path 'lib/arm64-v8a/libtermux.so' from inputs:`，列出
   `merged_jni_libs/.../libtermux.so` 和 `cxx/Debug/.../obj/local/arm64-v8a/libtermux.so`
   两个输入。之前能构建成功是因为 merge 任务命中 up-to-date 缓存没重跑，`gradlew clean`
   后必然暴露。
   **根因**：`libtermux.so` 有**两个来源**：`terminal-emulator/src/main/jniLibs/arm64-v8a/`
   里的**预编译副本**（走 mergeDebugJniLibFolders）+ NDK **源码编译**产物（走 cxx obj
   目录），AGP 拒绝同路径重复输入。规范来源是 NDK 源码编译（build.gradle 的
   `externalNativeBuild.ndkBuild` + `src/main/jni/`）。
   **修**：删掉冗余的预编译副本（已从源码树删除；若从旧备份恢复出来再遇到此错，照此删）：
   ```sh
   rm -f terminal-emulator/src/main/jniLibs/arm64-v8a/libtermux.so
   rmdir terminal-emulator/src/main/jniLibs/arm64-v8a terminal-emulator/src/main/jniLibs 2>/dev/null
   ```

### 构建命令
```sh
. /etc/profile.d/codetool-buildenv.sh          # 每个新终端先 source（含 HOST_* / GNUMAKE / aapt2 override）
cd /storage/emulated/0/Download/code/codetool  # 项目根（含 settings.gradle.kts）
sh gradlew :app:assembleDebug                  # Debug 包；release 加签名另说
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 自查清单（构建失败先过一遍）
- [ ] source 了 buildenv？`echo "${'$'}HOST_OS"` 应为 `linux`、`echo "${'$'}HOST_ARCH"` 应为 `aarch64`
- [ ] `echo "${'$'}GNUMAKE"` 应为系统 make 路径（如 /usr/bin/make，NDK prebuilt make 坏，坑 6）
- [ ] `make` 装了没？`command -v make`（arm64 容器最小 rootfs 常缺，坑 3）
- [ ] `local.properties` 的 sdk.dir/ndk.dir 指向容器内真实路径？（否→坑 3）
- [ ] 用 `sh gradlew` 而非 `./gradlew`？（FUSE 执行位，坑 4）
- [ ] `uname -s` 在交互 shell 正常、Gradle 报空 → source buildenv（坑 1）
- [ ] native 报 Error 127 → 先确认 `GNUMAKE` 指向系统 make（坑 6）；再查 NDK prebuilt 有无 `linux-aarch64` 软链 + `HOST_ARCH`
- [ ] `mergeDebugNativeLibs` 报 2 files found with path libtermux.so → 删 jniLibs 预编译副本（坑 8）
- [ ] AAPT2 Daemon startup failed → `grep aapt2FromMavenOverride ~/.gradle/gradle.properties`（坑 7）

## Git 凭据（app 已代管，不要向用户索要）
- GitHub / Gitee 的凭据分别存在 `~/.git-credentials-github`、`~/.git-credentials-gitee`（各 0600），
  SSH 私钥在 `~/.ssh/id_ed25519`，均由 app 的「工具 → Git」页面写入；
  `git clone` / `git push` 会自动使用，**不要向用户索要 GitHub/Gitee token 或私钥**，
  也不要 `cat` / 打印这些文件，或把凭据写进代码、日志、提交信息。
- 连接失败或未配置时，引导用户到 app「工具 → Git」配置（页面自带「测试连接」），
  不要让用户把 token 粘贴到对话里。
""".trimIndent()

    private const val GRADLE_MIRROR_DESC =
        "Gradle wrapper 下载超时/太慢时，把 distributionUrl 换成国内镜像（腾讯云 / 华为云 / 阿里云），" +
        "自动从 gradle-wrapper.properties 读取版本并验证；触发词：gradle 下载超时、换 gradle 镜像、gradle 镜像源、gradle 下载不了。"

    private val GRADLE_MIRROR_BODY = """
# Gradle 镜像源（换掉 services.gradle.org）

国内直连 services.gradle.org 经常超时或极慢。把 wrapper 下载地址换成国内镜像即可。

## 何时用
- `sh gradlew` / `./gradlew` 报下载超时、`SocketTimeoutException`、连接被拒
- Gradle wrapper 下载极慢
- 用户说「换 gradle 镜像」「gradle 镜像源」「gradle 下载不了」

## 镜像（按速度选一个）
| 镜像 | 地址 |
|---|---|
| 腾讯云 | `https://mirrors.cloud.tencent.com/gradle/gradle-{VERSION}-bin.zip` |
| 华为云 | `https://mirrors.huaweicloud.com/gradle/gradle-{VERSION}-bin.zip` |
| 阿里云 | `https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-{VERSION}-bin.zip` |

`{VERSION}` 从 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 读取
（如 `gradle-9.3.1-bin.zip` → `9.3.1`）；原地址是 `-all` 就换成 `-all`。

## 步骤
1. 读版本：
   ```bash
   grep distributionUrl gradle/wrapper/gradle-wrapper.properties
   ```
2. 测镜像（可选，推荐）：
   ```bash
   curl -sI "https://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip" | head -1
   ```
   返回 `200` 即可用，选第一个可用的。
3. 改 `distributionUrl`（保留 `distributionSha256Sum` 不动：镜像文件相同、校验一致）：
   ```properties
   distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-9.3.1-bin.zip
   ```
4. 验证（FUSE 盘不要依赖执行位，用 `sh` 跑）：
   ```bash
   sh gradlew --version
   ```
   能打印 `Gradle 9.x.x` 即成功。

## 还原 / 注意
- 还原官方源：
  ```bash
  sed -i 's#mirrors.cloud.tencent.com/gradle#services.gradle.org/distributions#' gradle/wrapper/gradle-wrapper.properties
  ```
- 只改 properties，不改 `gradlew`。
- 之前下了一半：删 `~/.gradle/wrapper/dists/gradle-{VERSION}-bin/*/` 里的 `.part` / `.lck` 再重试。
- 镜像可能滞后于最新 Gradle；没有该版本就换一个镜像或暂时保留官方地址。
""".trimIndent()

    /**
     * 内置**多文件** skill 包（assets/skills/<name>/ → 容器 ~/.agents/skills/<name>/）。
     * 与单文件 ensureOne 不同：包带 references/ scripts/ 目录，走 assets 递归复制。
     * 幂等：`.codetool-pack-version` 记录版本，一致跳过；版本升级整包重拷。
     */
    private fun ensureBuiltinPack(ctx: Context, name: String, version: String): Int {
        val dir = File(dir(ctx), name)
        val marker = File(dir, ".codetool-pack-version")
        if (dir.exists() && marker.exists() && runCatching { marker.readText() }.getOrNull() == version) {
            return 0
        }
        runCatching { dir.deleteRecursively() }
        if (!dir.exists() && !dir.mkdirs()) return 0
        if (!copyAssetsTree(ctx, "skills/$name", dir)) return 0
        marker.writeText(version)
        return 1
    }

    /** 递归复制 assets 目录树到文件系统（保持相对结构）。返回是否成功。 */
    private fun copyAssetsTree(ctx: Context, assetPath: String, outDir: File): Boolean {
        val entries = runCatching { ctx.assets.list(assetPath) }.getOrNull() ?: return false
        if (entries.isEmpty()) return false
        for (e in entries) {
            val childAsset = "$assetPath/$e"
            val childOut = File(outDir, e)
            val sub = runCatching { ctx.assets.list(childAsset) }.getOrNull() ?: emptyArray()
            if (sub.isNotEmpty()) {
                if (!childOut.exists() && !childOut.mkdirs()) return false
                if (!copyAssetsTree(ctx, childAsset, childOut)) return false
            } else {
                runCatching {
                    ctx.assets.open(childAsset).use { input ->
                        childOut.outputStream().use { input.copyTo(it) }
                    }
                }.onFailure { return false }
            }
        }
        return true
    }

    fun ensureBuiltin(ctx: Context): Int {
        var n = 0
        n += ensureOne(ctx, "codetool-browser", BUILTIN_DESC, BUILTIN_BODY, "后台无头 Chrome")
        n += ensureOne(ctx, "codetool-shizuku", SHIZUKU_DESC, SHIZUKU_BODY, "android_tap_text")
        n += ensureOne(ctx, "codetool-system", SYSTEM_DESC, SYSTEM_BODY, "读取 CodeTool 自身日志")
        n += ensureOne(ctx, "gradle-mirror", GRADLE_MIRROR_DESC, GRADLE_MIRROR_BODY, "gradle 镜像")
        // 多文件内置包（assets/skills/，装前已按规范做安全审查：multi_search.py 的 base64
        // 仅解码 Bing 中转链接、请求目标全为公开搜索引擎；project_probe.py 纯文件探测无网络）
        n += ensureBuiltinPack(ctx, "multi-search-engine", "3.0.4")
        n += ensureBuiltinPack(ctx, "dev-god", "1.2.4")
        // dev-god（编程大神）与旧内置 codetool-dev 同为开发类 skill——冲突保留新的（用户定）：
        // 不再注册 codetool-dev，并清理已装旧包（幂等）。
        val oldDev = File(dir(ctx), "codetool-dev")
        if (oldDev.exists() && runCatching { oldDev.deleteRecursively() }.getOrDefault(false)) n++
        // 启动时同步外部 skill 源码目录 → 容器：源码放在 /storage/.../codetool/skill/<name>/，
        // 改完重启 APP 即生效（无需重新编译 APK）。同名覆盖，源码永不丢（在 git/外部存储里）。
        n += syncExternalSkills(ctx)
        return n
    }

    /**
     * 把外部 skill 源码目录同步进容器 ~/.agents/skills/。
     * 源目录：/storage/emulated/0/Download/codetool/skill/<skill-id>/SKILL.md（可含 references/ 等子目录）。
     * 规则：
     *  - 源目录里每个含 SKILL.md 的子目录，整体递归复制进容器，**同名覆盖**（源码为准）。
     *  - 只增/改，不删除容器里源目录已没有的 skill（避免误删用户在容器内自建的）。
     *  - 目录不存在 / 无存储权限时静默跳过（返回 0），不影响内置 skill 播种。
     * 返回本次实际复制/更新的 skill 个数。
     */
    private fun syncExternalSkills(ctx: Context): Int {
        // 源码 skill 根目录（外部存储，APP 有 READ/WRITE_EXTERNAL_STORAGE 即可读）。
        val srcRoot = File("/storage/emulated/0/Download/codetool/skill")
        if (!srcRoot.isDirectory) return 0
        val dstRoot = dir(ctx)
        if (!dstRoot.isDirectory && !dstRoot.mkdirs()) return 0
        var n = 0
        val dirs = srcRoot.listFiles { f -> f.isDirectory } ?: return 0
        for (src in dirs) {
            // 只同步含 SKILL.md 的目录（合法 skill），跳过临时/隐藏/备份（.bak .tmp 以 . 开头）。
            val id = src.name
            if (id.startsWith(".") || id.endsWith(".bak") || id.endsWith(".tmp")) continue
            if (!File(src, "SKILL.md").isFile) continue
            val dst = File(dstRoot, id)
            // 内容一致则跳过（按 SKILL.md 长度 + 目录文件数粗判，避免每次启动都重写时间戳）。
            if (dst.isDirectory && File(dst, "SKILL.md").isFile &&
                File(dst, "SKILL.md").length() == File(src, "SKILL.md").length() &&
                (dst.walkTopDown().count { it.isFile }) == (src.walkTopDown().count { it.isFile })
            ) continue
            runCatching {
                dst.deleteRecursively()
                if (!dst.exists() && !dst.mkdirs()) return@runCatching
                src.copyRecursively(dst, overwrite = true)
                n++
            }
        }
        return n
    }

    /** 首次写入内置单文件 skill；旧版内容自愈升级，写回原位置、不新增副本。
     *  每个 skill 用一个「特征串」判断是否已是当前版本（marker 出现即跳过；
     *  **marker 必须选旧版文件里绝对不存在的词**——旧版含此串会被误判已更新）。 */
    private fun ensureOne(ctx: Context, id: String, desc: String, body: String, marker: String): Int {
        val existing = listSkills(ctx).firstOrNull { it.id == id }
        if (existing != null) {
            if (existing.body.contains(marker)) return 0
            return if (upsert(ctx, existing, existing.name, desc, body) == null) 1 else 0
        }
        return if (upsert(ctx, null, id, desc, body) == null) 1 else 0
    }

    private fun frontmatter(txt: String): Pair<String, String> {
        if (!txt.startsWith("---")) return "" to ""
        val end = txt.indexOf("\n---", 4)
        if (end < 0) return "" to ""
        var name = ""
        var desc = ""
        // description 支持 YAML 块标量（|- / | / >- / >，值在后续缩进行里）：
        // 踩坑记录——裸逐行解析只能读到字面量「|-」，技能页说明列显示成「|-」，
        // 真实描述全丢（multi-search-engine 3.0.4 实测）。| 按换行拼接，> 折叠为空格。
        val lines = txt.substring(3, end).lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("name:")) {
                name = t.removePrefix("name:").trim().trim('"', '\'')
            } else if (t.startsWith("description:")) {
                val value = t.removePrefix("description:").trim()
                if (value in setOf("|", "|-", "|+", ">", ">-", ">+")) {
                    val fold = value.startsWith(">")
                    val parts = mutableListOf<String>()
                    var j = i + 1
                    while (j < lines.size) {
                        val l = lines[j]
                        if (l.isBlank()) {
                            // 块内空行：后面还有缩进行则属于内容（跳过），否则块结束
                            val nxt = lines.getOrNull(j + 1) ?: break
                            if (nxt.isNotBlank() && !nxt[0].isWhitespace()) break
                            j++
                            continue
                        }
                        if (!l[0].isWhitespace()) break
                        parts.add(l.trim())
                        j++
                    }
                    desc = parts.joinToString(if (fold) " " else "\n").trim()
                    i = j - 1
                } else {
                    desc = value.trim('"', '\'')
                }
            }
            i++
        }
        return name to desc
    }

    private fun stripFrontmatter(txt: String): String {
        if (!txt.startsWith("---")) return txt
        val end = txt.indexOf("\n---", 4)
        return if (end >= 0) txt.substring(end + 4).trimStart('\n', '\r') else txt
    }
}
