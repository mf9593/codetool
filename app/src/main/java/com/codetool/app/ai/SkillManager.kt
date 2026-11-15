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
        "通用开发约定与约束（详细版）：开发记录、构建验证、原生库 16KB 页对齐、工具链同架构、脚本真实自检、Kotlin 协程、依赖与 Gradle、Git 规范、敏感信息、内存泄漏、列表性能、混淆发版、深色适配、完整 UI 设计规范。任何 Android / 软件项目干活前先读。"

    private val DEV_BODY = """
---
name: dev-conventions
description: 跨项目通用开发红线与验证约定。在任何 Android / Kotlin 项目开始写码、改动组件、调整 UI、接入依赖、构建打包、混淆发版或排查崩溃前使用；用于约束主题与 UI 适配、图标与无障碍、API/配置核实、构建实机验证、脚本自检、协程与 Context、Git 与密钥，并提供提交前自查清单。
---

# 通用开发约定与约束（详细版）

> 跨项目通用的开发习惯和硬约束。改任何代码前先对照，避免同类问题反复返工。

**目录**
1. [组件开发与改动通用原则](#组件开发与改动通用原则强制)
2. [开发记录](#开发记录强制)
3. [架构图 / 项目地图](#架构图--项目地图强制)
4. [构建与验证](#构建与验证)
5. [构建前排查思路](#构建前排查思路强制)
6. [可复用签名](#可复用签名换环境一致)
7. [崩溃日志记录](#崩溃日志记录强制)
8. [原生库打包](#原生库打包android)
9. [依赖下载架构匹配](#依赖下载架构匹配重要)
10. [脚本 / 安装逻辑](#脚本--安装逻辑)
11. [跨进程 / 桥接通信](#跨进程--桥接通信)
12. [Skill / MCP 管理](#skill--mcp-管理ai-cli)
13. [其它通用习惯](#其它通用习惯)
14. [Kotlin 协程与异步处理](#kotlin-协程与异步处理)
15. [依赖与 Gradle 管理](#依赖与-gradle-管理)
16. [Git 与提交规范](#git-与提交规范)
17. [敏感信息与日志管控](#敏感信息与日志管控)
18. [内存泄漏与 Context 管理](#内存泄漏与-context-管理)
19. [列表性能与图片加载](#列表性能与图片加载)
20. [混淆与发版验证](#混淆与发版验证)
21. [深色模式与屏幕适配](#深色模式与屏幕适配)
22. [UI 设计规范](#ui-设计规范强制)
23. [约束取证命令清单](#约束取证命令清单)

**一句话速查**（最容易漏的两条）：
1. **API / 配置先核实真实存储位置和读取路径**，别想当然；读不到就诚实降级，不假装知道。
2. **功能可达性自检**：菜单要 inflate、Activity 要注册、入口要可达、标题栏按钮要 `always`——逻辑在 ≠ 用户看得见。

---

## 组件开发与改动通用原则（强制）

每开发一个组件、每改一处代码，都要**整体考虑适配和冲突**，不能只看眼前这一块：

- **主题适配**：新组件 / 改动必须跟随主题（浅色 / 深色 / 动态主题色）。颜色用主题属性或 ColorStateList，禁止硬编码色值；切主题后控件颜色要自动跟随。
- **UI 适配**：考虑不同屏幕尺寸、深色模式、挖孔屏 / 状态栏、横竖屏。尺寸用 `dp`/`sp`，间距走统一 dimens，布局不能写死像素或状态栏高度。
- **关联代码适配**：改一个组件要排查**所有引用它的地方**（布局、代码、样式、Adapter、其它 Fragment/Activity），同步更新，别漏改导致风格或行为不一致。
- **改动要考虑 UI 变化**：改逻辑 / 数据 / 配置前，先想清楚会带来什么界面变化（列表刷新、状态图标、按钮态、空态），确保 UI 正确反映新状态。
- **改动要考虑功能冲突**：改代码前排查会不会和已有功能冲突（同 ID 控件、同名资源、重复逻辑、并发 / 生命周期冲突），避免改 A 坏 B。
- **API / 配置 / 设置项必须核实，不能想当然（强制）**：调用系统 API、读写配置项（`Settings.*` / `device_config` / `SharedPreferences` / provider / 环境变量）前，**先核实它真实的存储位置、key 名和读取路径**，别凭记忆或"听起来合理"就用。同类设置可能分散在不同表（如 Android 的 `device_config` vs `Settings.Global`），用错表 API 会静默返回 null / 默认值，**不报错但恒错**（真实踩坑：用 `Settings.Global.getString` 读一个 device_config 的 key，返回 null 导致状态恒判错）。核实手段：官方文档确认 key 属于哪个 namespace、用 `adb shell settings/device_config get` 取真实值、跑起来打印实际读到的值。读不到 / 取不到时明确降级提示「无法确认，请手动检查」，**不要假装知道状态**。

> 一句话：**开发 = 组件 + 主题 + UI + 关联代码 + 潜在冲突 + API/配置核实**，六者一起考虑才算做完。

---

## 开发记录（强制）

- 每次改代码（源码 / 资源 / 脚本 / 构建配置）后，**立即**追加当天的开发记录（devlog），当天文件不存在就新建，不攒到最后补写。
- 每节写清：背景/问题、改动文件（相对路径）、关键实现、验证方式与结果、产物位置、待办。
- 只追加不改历史；更正用「补充」小节。没实机验证的写「待验证」，不默认生效。

---

## 架构图 / 项目地图（强制）

**开发一个项目后，在项目根目录写一个架构图 md**（如 `ARCHITECTURE.md` 或 `架构.md`），让后续查文件不用全盘搜索：

- **项目结构树**：列出主要目录/文件及职责（`app/src/main/java/.../` 各包干什么、`res/layout` 关键布局、`assets`、`jniLibs` 等），一眼看清代码在哪。
- **核心模块与数据流**：画（或文字描述）关键流程，如「UI → ViewModel/Fragment → Manager/Repository → 运行时/容器」，标出核心类和它们怎么串起来。
- **入口与关键文件**：标出 Application、MainActivity、各 Fragment/Activity、核心 Manager 单例、Manifest，方便定位。
- **改代码时同步更新**：新增/移动/重命名关键文件、新增模块时，**顺手更新架构图**，别让地图过期误导人。
- **目标**：新人（或未来的自己）打开这个 md 就知道「XX 功能的代码在哪、怎么串」，不用 grep 全盘搜。

---

## 构建与验证

- 编译通过 ≠ 完成。改完要装到实机 / 实际环境跑一遍验证，别只看 BUILD SUCCESSFUL 就当完成。
- 用项目自己的构建方式（有的预装 gradle 直接 `gradle`，有的用 `gradlew`，有的要 Docker 脚本），先确认再用。
- 验证优先用日志和命令取证，而不是只看截图。
- **构建前先做代码合理性 + UI 排查**（见下节），别只求编译通过。

---

## 构建前排查思路（强制）

**编译通过只是第一步。** 构建前先核查代码合理性和 UI 改动，防止用户调试时踩各种小问题：

- **代码合理性自查**：改的逻辑边界对不对（null / 越界 / 状态残留）？有没有引入新隐患（回调里 `requireContext()` 可能崩、字段在配置变更后丢失、异步时序）？宁可多花两分钟通读，别留坑给调试期。
- **UI 改动不能只「往后加一行」治标**：间距大了就减 padding、缺了就补一个 margin——这种「哪里不对补哪里」的打补丁，往往掩盖真实布局问题（父容器约束、measure 时机、主题覆盖），换台设备/换主题就复发。要**找根因**：为什么这里间距不对？是 padding 叠加、还是缺约束、还是硬编码？从根上改对，而不是只调一个数字。
- **UI 改动要过一遍自查清单**（见「UI 设计规范」第十节）：控件样式、间距一致性、相邻间距、dimens 引用、硬编码色值/尺寸、动态 addView 间距。
- **改动要考虑关联面**：一个组件改了，所有引用它的地方（布局/代码/样式/Adapter/其它页）同步了吗？会不会和已有功能冲突（同名资源、重复逻辑、生命周期/并发冲突）？
- **功能可达性自检（新增，强制）**：逻辑写了、事件接了 ≠ 用户看得见。新功能完成后必须从**用户操作路径**走一遍——入口放对位置了吗（别只藏在某个犄角旮旯）、控件真的显示了吗（标题栏按钮、菜单项、列表项）。真实踩坑：写了 `setOnMenuItemClickListener` 却漏了 `onCreateOptionsMenu`，标题栏按钮一个都不显示（menu 没 inflate）；新页面入口只挂在 onboarding 里，主页/工具页找不到。规律：**菜单要 inflate、Activity 要注册、入口要可达**，三查缺一不可。
- **标题栏按钮专项**：要求常显的标题栏按钮 `showAsAction` 必须用 `always`；`ifRoom` 会因空间不足悄悄折进溢出菜单，用户以为没做。按钮就位后确认图标/文案齐全（menu item 用 `ifRoom` 且无文字时折叠后只剩「⋮」）。

> 一句话：**别只让代码编译过，要让代码对、UI 对、换环境也对。** 构建前花两分钟排查，胜过调试期两小时抓虫。

---

## 可复用签名（换环境一致）

- **调试签名也要统一**：默认 `~/.android/debug.keystore` 是每台机器各自的，换环境构建签名就变，导致**无法覆盖安装**（签名不一致要先卸载，丢数据）。项目应自带一个专用 debug keystore，所有开发环境用同一个。
- **keystore 放仓库外的固定目录**（如项目上层 `keystore/`），`build.gradle` 用 `rootProject.file("../keystore/xxx.jks")` 相对引用，避免硬编码绝对路径。
- **签名文件不入库**：`.gitignore` 必须排除 `*.jks` / `*.keystore` / `keystore/`（见 Git 规范）。keystore 单独备份/同步，换环境时复制过去。
- **debug 用仓库外共享的专用 debug keystore**（保证覆盖安装一致）；**release 必须独立正式签名**，私钥与密码严格单独保管，走环境变量 / 不入库的 `gradle.properties` 注入，别把正式密码写进 build.gradle——debug/release 共用同一个 keystore 会扩大 release 私钥暴露面。
- **验证签名生效**：构建后 `apksigner verify --print-certs app-debug.apk` 看 `Subject DN` 是不是你的 keystore，而不是 `Android Debug`。

---

## 崩溃日志记录（强制）

**默认就接入崩溃日志采集**，别等出了闪退才发现无从排查。开发期错误必须能定位到具体故障：

- **全局未捕获异常处理器**：App 启动（Application.onCreate）就设置 `Thread.setDefaultUncaughtExceptionHandler`，把崩溃堆栈（线程 + Throwable 全栈）写入本地时间戳文件（如 `filesDir/crash/`）。
- **崩溃可视 / 可分享**：崩溃后跳独立进程的报告页展示堆栈，或提供入口查看 / 分享日志文件；主进程退出也不能影响报告展示。
- **幂等初始化**：处理器只设一次，避免重复包装。
- **不吞异常**：捕获后要把原异常交给默认处理器 / 记录后 `exitProcess`，别静默吞掉导致「闪退但没日志」。
- **运行期错误也留痕**：关键失败路径（网络失败、IO 失败、解析失败）用统一日志工具记 `warn/error`（带上下文），Release 关闭 VERBOSE/DEBUG 但保留 error。禁止空 `catch {}`。
- **目标**：任何崩溃 / 异常都能通过日志定位到「哪个文件哪一行、什么原因」，而不是「就是闪退了不知道为啥」。

---

## 原生库打包（Android）

- 所有 arm64-v8a 原生库（.so）**必须 16KB 页对齐**（面向 Android 15+ 设备的安装检查；Google Play 对 targetSdk 35+ 的新应用/更新为强制要求）。链接显式加：
  `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`
- Rust/Cargo 可用 `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` 指向带上述参数的 NDK clang 包装器，或 `RUSTFLAGS="-C link-arg=..."`。
- 打包前验证（双层）：
  - .so 层：`llvm-readelf -l libxxx.so | grep LOAD`，每个 LOAD 段 `Align` ≥ `0x4000`；
  - APK 层：`zipalign -c -P 16 4 app-release.apk`（build-tools 35+），与 .so 结果双取证。

---

## 依赖下载架构匹配（重要）

- **下载 NDK / build-tools / platform-tools / 任何工具链，必须与运行宿主机架构一致。** 先探测本机架构（`uname -m` / `dpkg --print-architecture` / `file <exe>`），再下载与探测结果一致的工具链，不要把某一台机器的架构当成全局事实写死。
- **绝不能给 arm64 机器下 x86_64/x64 的工具链**——那会浪费大量时间去修「为什么跑不起来 / aapt2 adb 不是可执行 ELF」。已真实踩坑：SDK 包里混进 x86_64 的 aapt2/adb，必须校验 ELF `e_machine` 才算已安装（aarch64 = `0xB7`，x86_64 = `0x3E`）。
- 判断「已安装」时校验 ELF 架构，`PATH` 优先选本机架构的 build-tools。

---

## 脚本 / 安装逻辑

- 安装脚本必须**真实自检**（`command -v <bin>` / `<bin> --version`），不能打印假成功。
- 解压第三方压缩包**按文件头 magic 判断真实格式**，不按扩展名、不依赖 `file` 命令（最小环境常没装）。已出现扩展名骗人（`.tar.gz` 实为 XZ）。
  - 常见 magic 速查：gzip `1f 8b`、xz `fd 37 7a 58 5a 00`、zip `50 4b 03 04`、zstd `28 b5 2f fd`、tar 在偏移 257 处有 `ustar`。
- 「安装构建环境」必须**幂等**：已装的跳过只装缺的。用标记文件记录「压缩包名|大小|SHA-256 或版本号」，同名同大小但内容被替换时仅比大小会漏重装，换了版本/内容不同必须重装。
- 复制 / 归类白名单必须与脚本逻辑一致：不能只写 `ndk`/`platform-tools` 关键字（实际文件名常带版本号 / 架构后缀），否则文件没进目标位置，脚本只会「未找到」。
- Kotlin 里避免内嵌长 shell：把脚本拆成独立文件放 `assets/` 读取执行；确需内嵌时用原始字符串（三个双引号）包裹并正确转义 `${'$'}{'${'$'}'}` / `${'$'}{'('}` 等模板前缀，别用 `\\${'$'}{'${'$'}'}var`（会触发 "Unresolved reference"）；别依赖目标环境没装的工具（如 `fuser`/`pgrep`）。

---

## 跨进程 / 桥接通信

- 复用已有的共享目录 / bind mount 做**文件桥**，别另起 HTTP 服务或自造 IPC。
- 调试能力优先用**标准协议**（如 Chrome DevTools Protocol `127.0.0.1:9222`），别手写 WebSocket/HTTP 解析器。

---

## Skill / MCP 管理（AI CLI）

- Skill 统一写 `~/.agents/skills/<name>/SKILL.md`（open standard），多个 CLI 都默认识别，写一份别写两份。
- `SKILL.md` 以 YAML frontmatter 开头，必填 `name`/`description`；`name` 须 `^[a-z0-9]+(-[a-z0-9]+)*${'$'}` 且与目录名一致，`description` 1..1024 字符（写「什么时候触发」，不是「里面有什么」）。
- 编辑导入的 skill **先按加载优先级定位实际生效路径，再写回该文件**，不能一律写 `.agents`（否则被同名高优先级覆盖，编辑看似无效）。
- MCP 按目标 CLI 的**实际 schema** 写入：先读现有配置文件确认顶层键与字段（不同 CLI 差异大，如 `mcp` / `mcpServers` / 各自的 `options` / `env` / `headers`），确认后合并，不臆造字段、不整文件覆盖。配置可能 JSONC，容错读取，绝不破坏用户配置。

---

## 其它通用习惯

- 优先用成熟方案不自造轮子；解析/渲染/编解码/协议/格式转换先找成熟库，别手写正则解析标准格式。
- **方法/函数复用**：开发新功能调用方法时先想「这个逻辑会不会在别处重复用到」。会复用的抽成**公共函数库/工具类**（如 `util/` 下的单例 object），避免重复造轮子、避免多处各写一份改一处漏一处。
- **卡住时参考成熟开源项目**：遇到解决不了的问题，先查成熟开源项目怎么实现（思路/架构/边界处理），借鉴实现思路而不是照抄代码。**同时保证不侵权**：只参考思路、自己实现；确需引入第三方库/代码时核对 License（Apache-2.0/MIT/BSD 可用，GPL 传染性慎用，专有协议禁用），并在注释/CREDITS 标注来源与协议。
- 全中文交流；UI 改动先询问。防崩溃：可能返回 null 的调用判 null；Fragment 回调里用 `viewLifecycleOwner.lifecycleScope.launchWhenStarted { }` 或 `view.post { if (isAdded) ... }` 替代裸 `runOnUiThread`。
- **外部 IO / 文件读取必须容错（强制）**：读写**外部存储 / 共享目录 / 用户路径**的文件时，`exists()` 只保证存在、**不保证可读可写**（权限未授予/路径受限时抛 `EACCES Permission denied`）。所有 `readText()` / `writeText()` / `delete()` / `listFiles()` 必须 `runCatching { ... }.getOrNull()/getOrDefault()` 包裹，**失败降级（当 null/跳过/提示）绝不裸抛**。否则一个文件读不到就会异常一路抛到 `onResume`/`onCreate` **崩掉整页**。判 null 只管返回值，管不了 IO 抛异常——两者都要防。
  - **注意与协程取消的冲突**：`runCatching` 会吞掉 `CancellationException`，在协程里包 IO 时必须显式重抛——`catch (e: CancellationException) { throw e }`，或只捕获 `IOException` / `SecurityException` 等具体异常，否则取消信号被吞、协程不响应取消（与「禁止吞 CancellationException」一条冲突）。
- 点击运行脚本/长耗时准备时**先立刻切到进度界面并提示**再后台准备，别等准备完才切页。

---

## Kotlin 协程与异步处理

- **禁用 GlobalScope**：UI 层强制使用 `lifecycleScope` / `viewModelScope`；底层库使用自定义 `CoroutineScope` + `SupervisorJob` 便于统一取消。
- **异常与取消**：耗时操作（`Dispatchers.IO`）切回主线程时严禁用 `try-catch` 吞掉 `CancellationException`；`withContext` 的异常必须向上传播。
- **回调改造**：跨模块通信优先改为 `suspend` 函数挂起，禁止新增多层嵌套的接口回调。

---

## 依赖与 Gradle 管理

- **版本集中管理**：所有依赖、编译 SDK 版本统一收口到 `libs.versions.toml`（Version Catalog），禁止在 `build.gradle` 中硬编码版本号。
- **禁止动态版本**：严禁使用 `latest.integration` 或 `+` 版本号，确保每次构建产物可重现。
- **引入新库前检查 ABI**：新增的 AAR/JAR 必须包含 `arm64-v8a` 的 `.so`；只有 x86 或只有 armeabi-v7a 的库直接弃用（Android 15 已强推纯 64 位）。

---

## Git 与提交规范

- **分支策略**：功能开发走 `feat/xxx`，修 Bug 走 `fix/xxx`，禁止直接提交到 `main` / `master`。
- **提交信息**：采用 `类型(范围): 简述` 格式（如 `fix(ui): 修复列表错位`），方便自动生成变更日志。
- **禁止入库的文件**：`.gitignore` 必须覆盖 `local.properties`、签名文件（`*.jks`/`*.keystore`）、`build/` 目录及 IDE 配置；严禁把敏感信息提交到仓库。

---

## 敏感信息与日志管控

- **密钥零硬编码**：API Key / Token / 密码严禁写在源码或 XML 中。统一放 `gradle.properties`（不入库）或 `local.properties`，通过 `buildConfigField` 注入。
- **日志封装**：使用统一的日志工具类，Release 包自动关闭 `VERBOSE` / `DEBUG` 级别，严禁打印密码、私钥、用户隐私数据。Release 的 error 日志也须**脱敏**：Token / 私钥 / 手机号 / 用户 ID 一律掩码或只记长度。
- **adb 调试属性**：`adb shell setprop log.tag.xxx` 仅限本地临时调试，不得写入正式业务逻辑。

---

## 内存泄漏与 Context 管理

- **Context 防泄漏**：单例、后台任务、静态变量禁止持有 `Activity` Context，统一使用 `ApplicationContext`。
- **资源释放**：非静态 `Handler` / `Thread` 必须在 `onDestroy` 中移除回调或中断；动态注册的 `BroadcastReceiver` / `ContentObserver` 必须在对应生命周期反注册。
- **Lifecycle 感知**：使用 `LifecycleObserver` 时确保在 `onDestroy` 中 `removeObserver`，避免 Fragment/Activity 无法被 GC 回收。

---

## 列表性能与图片加载

- **强制 RecyclerView**：动态列表一律使用 `RecyclerView` + `ListAdapter` / `AsyncListDiffer` + `DiffUtil`，禁止绕过 DiffUtil 手动全量 `notifyDataSetChanged`（`submitList` 内部本就会 notify，diff 计算走默认后台线程，不在主线程自行算）。
- **图片加载库**：统一使用 Coil 或 Glide 加载网络/本地图片，禁止手写 `decodeStream` 直接塞 `Bitmap`（极易 OOM）；列表图片必须设置 `placeholder` 和 `error` 占位图。

---

## 混淆与发版验证

- **混淆规则同步**：反射调用、Gson/Moshi 反射序列化、JNI 回调的类必须加 `-keep`；`androidx.parcelize` 生成的 Creator 由 AGP 默认规则覆盖，无需手写。
- **Release 实机验证**：发版前必须开启 `minifyEnabled true` 打 release 包，并装到实机完整走一遍核心流程（debug 包不混淆容易掩盖问题）。

---

## 深色模式与屏幕适配

- **主题可扩展（核心）**：所有颜色、间距、圆角、字体统一收口到主题资源（`colors.xml` / `themes.xml` / `dimens.xml` / ColorStateList / Theme 属性），**代码和布局里禁止硬编码**。这样后续加深色模式、换配色、加新主题（如多套配色）时，只改资源文件即可全局生效，不用逐个控件改代码。
- **第三方编辑器/语法高亮库的配色必须跟随 app 主题（强制，真实踩坑）**：接入代码编辑器、语法高亮、富文本等自带配色方案的库时，**不能直接把库的某套主题色（尤其深色主题）硬编码给编辑器**——那会让编辑器背景突然变黑/固定某色，与 app 浅色/深色主题脱节（用户明确视为缺陷）。必须：库配色按 app 当前主题选（浅色主题→库的浅色主题、深色→深色），主题切换时同步；库要求的资源（主题/语法文件）由 app 打包并跟随 app 主题解析。<br>另注意此类库常要求 `setText` 后再设语言（异步加载语言会清空已渲染正文），以及主题文件可能是 JSONC/非标准格式需预处理（剥注释/尾逗号）。
- **深色模式**：所有颜色必须定义 `res/values/colors.xml` 与 `res/values-night/colors.xml`（或依赖 Theme 属性），不能只在浅色一套里写死。
- **沉浸式与挖孔屏**：以 `enableEdgeToEdge()` + `ViewCompat.setOnApplyWindowInsetsListener` 处理 insets 为主；`android:fitsSystemWindows="true"` 仅作兼容旧代码的兜底，禁止写死状态栏高度（Android 15+ targetSdk 35 强制 edge-to-edge，单靠 fitsSystemWindows 不够）。
- **尺寸单位**：字体统一用 `sp`，间距/尺寸统一用 `dp`，Kotlin 代码里设置宽高也必须做 `dp2px` 转换，严禁出现裸 `px` 赋值。

---

## UI 设计规范（强制）

### 核心原则

> **严禁使用任何默认样式 / 裸控件直接上屏。** 所有控件必须经过自定义样式包裹或 XML 属性显式定义。相同类型的控件间距必须一致，相邻控件之间必须留有间距，禁止紧贴。

### 一、禁止默认样式

- **所有控件必须显式指定样式**，禁止出现"系统长什么样就什么样"的裸控件。
- 全局基础控件样式统一定义在 `res/values/styles.xml` 中，至少覆盖 TextView / Button / EditText / CardView（字号、颜色、圆角、高度、padding、背景、elevation、stroke）。
- Material 组件必须继承 `Widget.Material3.*` 并二次定制，不允许直接用裸标签。

### 二、间距系统（Spacing System）

- **间距只使用 4dp 基准的倍数**：4 / 8 / 12 / 16 / 24 / 32 dp。
- **统一定义在 `res/values/dimens.xml`**：`spacing_xs=4dp` / `sm=8dp` / `md=12dp` / `lg=16dp` / `xl=24dp` / `xxl=32dp`。
- **页面级容器**：根布局 padding 统一 `@dimen/spacing_lg`（16dp）。
- **同组控件垂直间距**：统一 `@dimen/spacing_md`（12dp），同组内的相关控件用 `@dimen/spacing_sm`（8dp）。
- **卡片之间 / 列表项间距**：统一 `@dimen/spacing_md`（12dp），列表容器顶部/底部留 `@dimen/spacing_lg`（16dp）。

### 三、相同控件间距相同

- **同类型控件间距必须完全一致**，不允许 A 处按钮间距 8dp、B 处按钮间距 16dp。
- 所有水平并排按钮间距统一 `@dimen/spacing_md`（12dp）：`layout_marginStart="@dimen/spacing_md"`，禁止左右各 6dp 拆分的写法。
- 所有垂直相邻输入框间距统一 `@dimen/spacing_md`（12dp）。
- 所有标签-内容键值行间距统一 `@dimen/spacing_sm`（8dp）。
- **动态 addView 的控件同样遵守**：代码中 `LayoutParams` 的 margin 必须从 `R.dimen.spacing_*` 读取，禁止写死 `12`。

### 四、相邻控件必须留间距

- **任意两个相邻控件之间必须有明确的 margin 或 padding 间隔，禁止紧贴。**
- **容器边缘也要留间距**：内容距屏幕边缘（CardView 边缘）必须有 padding，不允许内容贴边。
- **特殊例外**：仅以下场景允许 0 间距——卡片内部内容区与 CardView 边缘之间（已由 contentPadding 处理）；Divider 分割线。

### 五、控件尺寸规范

| 控件类型 | 最小高度 | 圆角 | 说明 |
|---|---|---|---|
| 主按钮 | 48dp | 12dp | 全宽或自适应，padding 水平 24dp |
| 次按钮（描边） | 48dp | 12dp | stroke 1dp，padding 水平 24dp |
| 输入框 | 56dp | 12dp | 背景带边框或填充色 |
| MaterialCardView | — | 16dp | stroke 1dp、elevation 0 |
| 图标 | 24dp | — | 状态行例外 20dp（dimens 定义 icon_size_* 两档） |
| 列表项 | 64dp+ | 16dp | CardView 包裹 |

### 六、水平并排按钮间距

- 水平并排按钮 ≥ 12dp 间距，禁止两个 `weight=1` 按钮紧贴。
- 动态 addView 的按钮行同样设 `@dimen/spacing_md` 间距。
- 破坏性按钮（删除/恢复/格式化）与相邻按钮 ≥ 16dp，用**描边次要样式**区分 + **必须二次确认**；禁止 `colorError` 文字色（团队约定：红色在 Material 里代表「错误/失败」，正常按钮变红像报错；其他项目沿用需自行确认团队规范）。

### 七、列表项规范

- 动态列表项必须用 `item_*.xml` + `LayoutInflater.inflate()`，禁止 Kotlin 层层 addView 拼。
- 每项一个 MaterialCardView（圆角 16dp、stroke 1dp、elevation 0），项间距 `@dimen/spacing_md`（12dp）。
- 信息用「label 最小宽（`minWidth` 72dp 或按 sp 适配）+ value weight 1」键值行分行展示，value `singleLine` + `ellipsize="end"`，别把所有字段拼一坨（固定 72dp 在大字体/多语言下会截断，用权重或最小宽）。
- 目录扫描类列表必须过滤备份/临时/隐藏文件（`.bak` `.old` `.tmp` `~`、`.` 开头）。

### 八、图标规范（强制）

> **本 Skill 及产出的 UI 一律不使用表情图标 / 装饰性 Unicode 符号。** 图标必须取自开放免费图标库（单一来源），统一为矢量资源，与 UI 一体化协调（尺寸、风格、线宽、主题色一致）。

#### 一、禁用项

- 禁止用 emoji、装饰性 Unicode 符号（U+2714 ✓ / U+2605 ★ / U+2192 → / U+26A0 ⚠ / U+2B00 区段等）、字符画、单个汉字充当图标或状态标记。
- 禁止以文字标签硬替代图标语义；禁止把 PNG/JPG 位图当 UI 图标（品牌 Logo 除外）；禁止手工拉伸图标或改动 viewport 造成变形。
- 禁止使用系统默认未定制的图标凑数；禁止一个项目混用多套图标库。
- 状态表达**必须配文字**（状态行文本 / contentDescription / 按钮文字任一），禁止只靠图标或只靠颜色表达状态。

#### 二、允许的图标来源（开放免费，白名单）

| 图标库 | License | 网格 / 风格 | 适用场景 |
|---|---|---|---|
| Material Symbols / Material Icons | Apache-2.0 | 24dp，可变权重 | Android / Material 3 首选 |
| Lucide | ISC | 24×24，stroke 2 | 跨平台通用，SVG 可转 vector |
| Tabler Icons | MIT | 24×24，stroke 2 | 后台 / 工具类界面 |
| Heroicons | MIT | 24×24,outline/solid | 需线面切换 |
| Bootstrap Icons | MIT | 16px 网格 | 小尺寸辅助图标 |
| Simple Icons | CC0 | 品牌标 | 仅品牌 Logo |

- 单项目**只选一套**主库（Android 推荐 Material Symbols，可全部用内置 vector drawable 或导入单个 SVG），不得混用。
- 引入前核对 License（MIT / Apache-2.0 / ISC / BSD / CC0 可用；CC BY 需署名；GPL / 专有协议禁用）；在 `CREDITS.md`（或 `LICENSES/`）记录库名、版本、License、取材链接，不直接改库内源文件方便升级。
- Android 落地：`res/drawable/ic_<语义>_24.xml` 矢量 drawable，禁止为几个图标引入整套字体文件。

#### 三、统一规格（UI 与图标协调一体）

- **视觉尺寸统一 24dp**（图标库标准网格）；状态行可用 20dp，但全项目统一且仅在 `dimens.xml` 定义 `icon_size_sm=20dp` / `icon_size_md=24dp` 两档，禁止其它尺寸。
- **触摸目标 ≥ 48dp**（`IconButton` 或 `minWidth/minHeight=48dp`），视觉尺寸与点击区域分开设置。
- 线宽、端点圆角、填充风格跟随所选库默认值，不逐个微调；同语义全项目只保留一个文件，命名 `ic_<语义>_<尺寸>.xml`（如 `ic_sync_24`），禁止 `icon1`/`img_2`。

#### 四、颜色与主题

- tint 走主题属性：`app:tint="?attr/colorControlNormal"` / `?attr/colorPrimary`，或 `res/color/ic_tint_*.xml` 的 ColorStateList（深色自动跟随）；`setImageTintList` 只用于切状态，色值仍取自资源，禁止 `setColorFilter(0xFF...)`。
- 状态语义固定「成功 / 警告 / 错误 / 进行中 / 未知」五类，颜色取主题 `color*` 属性，不同状态图标仍用同一套图标（只换色），色值不出现字面量。

#### 五、缩放与对齐

- 图标描边 / 内部留白对齐到所在容器重心；`ImageView` 用 `scaleType="fitCenter"`，禁止无缩放导致裁切；大小字体 / 高对比度 / 深色模式下复测不裁切、对比足够。

#### 六、溯源与版本号

- 已安装组件状态行**必须显示真实版本号**，多源回退读取，拿不到写「版本未知」（沿用原第八节要求）。

### 九、表单与交互

- 表单填 API Key/地址/连接参数**必须带「测试连接」按钮**就地验证；可选项用下拉选 + 留「自定义」兜底。
- 首页只做状态展示，低频/破坏性操作（备份/恢复/删除/清空）放独立工具页或设置页。
- 长耗时任务走**前台服务 + WakeLock + 进度条**，不放易被冻结的协程。Android 14+ 必须声明 `android:foregroundServiceType` 与对应权限（如 `FOREGROUND_SERVICE_DATA_SYNC`），否则启动即抛异常；普通短任务优先 `WorkManager`。

### 十、代码检查清单（提交前自查）

**UI / 布局：**
- [ ] 所有控件是否都带 style 或显式定义了外观属性？
- [ ] 是否存在任何两个相邻控件紧贴（间距为 0）的情况？
- [ ] 相同类型的控件间距是否一致（全局统一）？
- [ ] 所有间距是否引用 `R.dimen.spacing_*` 而非裸写数字？
- [ ] 是否存在硬编码色值（`#xxxxxx`）？
- [ ] 是否存在硬编码 `dp`/`px` 数值而非引用 dimens？
- [ ] 动态 addView 的控件是否设置了统一间距？

**图标 / 无障碍：**
- [ ] 布局 / 字符串 / 注释 / 日志中 grep 不到 emoji 与装饰性 Unicode 符号？
- [ ] 图标均来自选定开源库，来源与 License 已记入 `CREDITS.md`？
- [ ] 尺寸 24dp（状态行例外 20dp 已登记）、触摸目标 ≥ 48dp、命名 `ic_*_24`？
- [ ] tint 引用主题属性 / ColorStateList，无硬编码色值？
- [ ] 每个非装饰图标都有 `contentDescription` 与文字标签？未混入第二套图标库？

**功能可达性：**
- [ ] 新功能从**用户操作路径**走一遍能到达（入口位置、按钮显示、菜单 inflate）？
- [ ] 标题栏常显按钮 `showAsAction="always"`？

**构建 / 验证：**
- [ ] 编译通过后是否已实机 / 实际环境验证（非只看 BUILD SUCCESSFUL）？

---

## 约束取证命令清单

以下命令都能直接复制执行，把「强制」变成可跑的命令（与「构建与验证」呼应，落实"验证优先用日志和命令取证"）。

```bash
# [0 命中] 无表情图标 / 装饰性 Unicode 符号
rg -n --pcre2 '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2190}-\x{21FF}\x{2B00}-\x{2BFF}]' res/layout res/values strings.xml

# [0 命中] 无硬编码色值（ColorStateList 除外）
rg -n '#[0-9a-fA-F]{3,8}' res/values res/layout

# [0 命中] 布局中无裸写 dp / px / sp
rg -n '\b[0-9]+(dp|px|sp)\b' res/layout

# [逐项核对] 非装饰图标均有 contentDescription 与文字标签
rg -n 'ImageView|ImageButton' res/layout

# [逐项核对] 协程内无裸 runCatching（或已显式重抛 CancellationException）
rg -n 'runCatching' java/

# [0 命中] 禁用 GlobalScope
rg -n 'GlobalScope' java/

# [0 命中] 无动态版本
rg -n 'latest\.(integration|release)|"\+"' -g '*.gradle*' -g '*.toml' .

# [每个 LOAD 的 Align >= 0x4000] 16KB 页对齐（.so 层）
llvm-readelf -l libxxx.so | grep LOAD

# [校验通过] 16KB 页对齐（APK 层，build-tools 35+）
zipalign -c -P 16 4 app-release.apk

# [与 uname -m 探测结果一致] 工具链架构
readelf -h "${'$'}(command -v aapt2)" | grep Machine

# [Subject DN 为项目 keystore，不是 Android Debug] 调试签名生效
apksigner verify --print-certs app-debug.apk
```

非命令类检查：注入一次测试异常后确认 `filesDir/crash/` 生成带全栈的时间戳文件；切深色模式 + 最大字体跑一遍核心页，确认颜色、图标 tint、间距无异常。

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
