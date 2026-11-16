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
        if (existing == null) {
            if (!NAME_RE.matches(trimmedName))
                return "名称只能小写字母/数字/连字符（如 my-skill），且与目录名一致"
        } else if (trimmedName.isEmpty()) {
            return "名称不能为空"
        }
        if (description.isBlank() || description.length > 1024)
            return "description 必填且不超过 1024 字符"
        if (body.isBlank()) return "指令正文不能为空"
        return runCatching {
            // 编辑导入的 skill：写回原文件（可能是别的 CLI 的目录）
            val f = existing?.file
                ?: File(File(dir(ctx), trimmedName).apply { mkdirs() }, "SKILL.md")
            f.parentFile?.mkdirs()
            f.writeText(
                "---\nname: $trimmedName\ndescription: ${description.replace("\n", " ").trim()}\n---\n\n" +
                    body.trim() + "\n"
            )
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

这个 app 自带浏览器能力（tawc 合成器里渲染的 Chrome），**不要打开外部浏览器**。
浏览器**不预装**，由用户在 app 里一键安装。

## 前置（每次先确认）
1. 调 `browser_status` 检查三件事：浏览器是否已装、CDP 9222 是否就绪、文件桥是否可用。
2. 若浏览器未安装，告诉用户：到 app 的「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」。
   **不要自行 apt install**，装好后让用户回到终端点「显示」打开 GUI 合成器。
3. CDP / 文件桥未就绪时同样提示用户先点「显示」打开 GUI 合成器。

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
        "用 Shizuku 以 shell 身份调试 Android App：截屏、导出界面控件树、跑 dumpsys/logcat/pm 等命令，比无线调试方便，无需连 adb。"

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
        "终端里跑的是 rootfs 里的 Ubuntu 系统（由 tawcroot 模拟 chroot 运行，无 root 权限、原理类似 PRoot 的单进程版本）：可直接跑 ping / curl / apt / adb 等系统命令安装组件、做网络诊断。装通用工具先确认包里有没有，没有就用 apt 装（容器内 root 是模拟映射，不代表 Android 已 root），装完必须验证。需要新能力可从 skill 商店搜索安装 skill，推荐优先用腾讯 SkillHub（skillhub.cn），其它商店备用；装前必须安全审查）。"

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
        n += ensureOne(ctx, "codetool-browser", BUILTIN_DESC, BUILTIN_BODY, "browser_status")
        n += ensureOne(ctx, "codetool-shizuku", SHIZUKU_DESC, SHIZUKU_BODY, "shizuku_exec")
        n += ensureOne(ctx, "codetool-system", SYSTEM_DESC, SYSTEM_BODY, "推荐首选")
        // 多文件内置包（assets/skills/，装前已按规范做安全审查：multi_search.py 的 base64
        // 仅解码 Bing 中转链接、请求目标全为公开搜索引擎；project_probe.py 纯文件探测无网络）
        n += ensureBuiltinPack(ctx, "multi-search-engine", "3.0.4")
        n += ensureBuiltinPack(ctx, "dev-god", "1.2.4")
        // dev-god（编程大神）与旧内置 codetool-dev 同为开发类 skill——冲突保留新的（用户定）：
        // 不再注册 codetool-dev，并清理已装旧包（幂等）。
        val oldDev = File(dir(ctx), "codetool-dev")
        if (oldDev.exists() && runCatching { oldDev.deleteRecursively() }.getOrDefault(false)) n++
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
