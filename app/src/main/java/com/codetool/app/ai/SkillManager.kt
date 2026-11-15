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

    private const val DEV_DESC =
        "通用开发约定与约束：改代码后写开发记录、原生库 16KB 页对齐、工具链下载必须与本机同架构、" +
        "脚本真实自检、UI 布局规范、颜色跟随主题、不自造轮子。任何 Android / 软件项目干活前先读。"

    private val DEV_BODY = """
# 通用开发约定与约束（本机记忆）

> 跨项目通用的开发习惯和硬约束。改任何代码前先对照，避免同类问题反复返工。marker: codetool-dev-conventions

## 开发记录（强制）
- 每次改代码（源码 / 资源 / 脚本 / 构建配置）后，**立即**追加当天的开发记录（devlog），当天文件不存在就新建，不攒到最后补写。
- 每节写清：背景/问题、改动文件（相对路径）、关键实现、验证方式与结果、产物位置、待办。
- 只追加不改历史；更正用「补充」小节。没实机验证的写「待验证」，不默认生效。

## 构建与验证
- 编译通过 ≠ 完成。改完要装到实机 / 实际环境跑一遍验证，优先用日志和命令取证，而不是只看截图。
- 用项目自己的构建方式（有的预装 gradle 直接 `gradle`，有的用 `gradlew`，有的要 Docker 脚本），先确认再用。

## 原生库打包（Android）
- 所有 arm64-v8a 原生库（.so）**必须 16KB 页对齐**（Android 15+ 安装检查，Play 商店强制）。链接显式加：
  `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`
- Rust/Cargo 可用 `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` 指向带上述参数的 NDK clang 包装器，或 `RUSTFLAGS="-C link-arg=..."`。
- 打包前验证：`llvm-readelf -l libxxx.so | grep LOAD`，每个 LOAD 段 `Align` ≥ `0x4000`。

## 依赖下载架构匹配（重要）
- **下载 NDK / build-tools / platform-tools / 任何工具链，必须跟本机架构一致。** 本机是 **arm64 (aarch64)**，就下 aarch64/arm64 版本。
- **绝不能给 arm64 机器下 x86_64/x64 的工具链**——那会浪费大量时间去修「为什么跑不起来 / aapt2 adb 不是可执行 ELF」。
- 判断「已安装」时校验 ELF 架构（aarch64 = `e_machine=0xB7`），PATH 优先选本机架构的工具。

## 脚本 / 安装逻辑
- 安装脚本必须**真实自检**（`command -v <bin>` / `<bin> --version`），不能打印假成功。
- 解压第三方压缩包**按文件头 magic 判断真实格式**，不按扩展名、不依赖 `file` 命令（最小环境常没装）。扩展名会骗人（`.tar.gz` 实为 XZ 已出现）。
- 「安装构建环境」必须**幂等**：已装的跳过只装缺的。用标记文件记录「压缩包名|大小」，换了版本（文件名/大小不同）必须重装。
- 复制 / 归类白名单必须与脚本逻辑一致，不能只写关键字（实际文件名常带版本号 / 架构后缀），否则文件没进目标位置，脚本只会「未找到」。
- 内嵌 shell 时注意转义：Kotlin raw string 里用 `${'$'}` 表示字面美元符（直接写美元符会被当模板变量）；别依赖目标环境没装的工具（如 `fuser`/`pgrep`）。

## UI 布局规范
- 水平并排按钮 ≥ 12dp 间距；禁止两个等分按钮紧贴。动态生成按钮行同样设间距。
- 破坏性操作（删除/恢复/格式化）与相邻 ≥ 16dp，用描边次要样式区分 + **必须二次确认**；别用 error 色当正常按钮色。
- 首页只做状态展示，低频 / 破坏性操作放独立工具页或设置页。长耗时任务走前台服务 + WakeLock + 进度条，不放易被冻结的协程。
- 动态列表项用布局文件 + inflater，别在代码里层层 addView 拼（难对齐、样式漂移）。每项风格与页面其它卡片一致，项间距统一。
- 信息用「label 固定宽 + value 撑满」键值行分行展示，value 单行截断，别把所有字段拼一坨。
- 已安装组件状态行**必须显示真实版本号**，多源回退读取，拿不到明确写「版本未知」。
- 填 API Key/地址/连接参数的表单**必须带「测试连接」按钮**就地验证；可选项用下拉选 + 留自定义兜底。
- 扫描类列表必须过滤备份/临时/隐藏文件（`.bak` `.old` `.tmp` `~`、`.` 开头），避免旧版本同名条目干扰。

## 颜色与主题
- 颜色**跟随主题**，禁止硬编码色值。用主题属性 / ColorStateList，切主题自动跟随。
- 自定义控件的选中 / 激活态颜色必须从主题管理器取，别写死。

## Skill / MCP 管理（AI CLI）
- Skill 统一写 `~/.agents/skills/<name>/SKILL.md`（open standard），多个 CLI 都默认识别，写一份别写两份。
- `SKILL.md` 以 YAML frontmatter 开头，必填 `name`/`description`；`name` 须 `^[a-z0-9]+(-[a-z0-9]+)*${'$'}` 且与目录名一致，`description` 1..1024 字符。
- 编辑导入的 skill **写回它原来的文件**，不能一律写 `.agents`（否则被同名高优先级覆盖，编辑看似无效）。
- MCP 写进各 CLI 配置的 `mcp.<name>` 段（`type`/`command`/`url`/`enabled`）。配置可能 JSONC，容错读取，绝不破坏用户配置。

## 其它通用习惯
- 优先用成熟方案不自造轮子；解析/渲染/编解码/协议/格式转换先找成熟库，别手写正则解析标准格式（必然漏 case）。
- 防崩溃：可能返回 null 的调用判 null 或给占位；异步回调回 UI 前加「还活着」检查；`setText` 别传可能为 null 的对象。
- 点击运行脚本 / 长耗时准备时**先立刻切到进度界面并提示**再后台跑，别等准备完才切页（用户以为点击没反应）。
- 全中文交流；UI 改动先询问。
""".trimIndent()

    /** 首次写入内置 skill；旧版内置内容自愈升级，写回原位置、不新增副本。
     *  每个 skill 用一个「特征串」判断是否已是当前版本（marker 出现即跳过）。 */
    fun ensureBuiltin(ctx: Context): Int {
        var n = 0
        n += ensureOne(ctx, "codetool-browser", BUILTIN_DESC, BUILTIN_BODY, "browser_status")
        n += ensureOne(ctx, "codetool-shizuku", SHIZUKU_DESC, SHIZUKU_BODY, "shizuku_exec")
        n += ensureOne(ctx, "codetool-dev", DEV_DESC, DEV_BODY, "codetool-dev-conventions")
        return n
    }

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
        txt.substring(3, end).lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("name:")) name = t.removePrefix("name:").trim().trim('"', '\'')
            if (t.startsWith("description:")) desc = t.removePrefix("description:").trim().trim('"', '\'')
        }
        return name to desc
    }

    private fun stripFrontmatter(txt: String): String {
        if (!txt.startsWith("---")) return txt
        val end = txt.indexOf("\n---", 4)
        return if (end >= 0) txt.substring(end + 4).trimStart('\n', '\r') else txt
    }
}
