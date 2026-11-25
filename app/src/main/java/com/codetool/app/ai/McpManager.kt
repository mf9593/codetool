package com.codetool.app.ai

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.util.Jsonc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MCP 服务管理：直接读写两个 CLI 的原生配置（实时读盘、不缓存）。
 *
 * 扫描的配置文件：
 * - MiMo Code: `~/.config/mimocode/mimocode.jsonc`（也支持 .json）
 * - OpenCode : `~/.config/opencode/opencode.json`（也支持 .jsonc）
 *
 * 两者都是 opencode 系的 `mcp.<name>` 段：
 *   "mcp": { "name": { "type": "local", "command": [...], "enabled": true } }
 *   "mcp": { "name": { "type": "remote", "url": "...", "enabled": true } }
 *
 * - 列表会把两边**已添加的服务全读出来**并标明来源；
 * - 配置里的注释 / 尾逗号不会导致整份文件被跳过（见 [Jsonc]）；
 * - 编辑时只改 type / command / url / enabled，**保留 headers、environment
 *   等其它字段**；JSONC 带注释需要改写成标准 JSON 前会先备份 `.codetool.bak`。
 */
object McpManager {

    data class Server(
        val name: String,
        val type: String = "local",          // local | remote（兼容 sse/http 等旧写法）
        val command: List<String> = emptyList(),
        val url: String = "",
        val enabled: Boolean = true,
        val builtin: Boolean = false,
        /** 列表里展示的简介；内置服务由 app 提供，自定义服务可用配置里的 description。 */
        val description: String = "",
        /** 出现在哪些 CLI 配置里：MiMo Code / OpenCode。 */
        val sources: List<String> = emptyList(),
    ) {
        val isRemote: Boolean get() = type != "local"
    }

    const val BROWSER_NAME = "codetool-browser"
    const val ANDROID_NAME = "codetool-android"

    /** 已下线的日志 MCP：只用于清理旧配置，避免 CLI 启动时找不到脚本。 */
    private const val LEGACY_LOGS_NAME = "codetool-logs"
    private const val LEGACY_LOGS_SCRIPT = "/usr/local/bin/codetool-logs-mcp"

    /** 内置 MCP server：app 自带、默认开启。 */
    val BUILTINS = listOf(
        Server(
            name = BROWSER_NAME,
            type = "local",
            // 绝对路径：CLI 以 stdio 子进程拉起 server，不依赖登录 shell 的 PATH
            command = listOf("/usr/bin/python3", "/usr/local/bin/codetool-mcp"),
            enabled = true,
            builtin = true,
            description = "用 app 内置 Chrome 渲染网页，可打开 URL、执行 JS、查 DOM 和截图，不依赖外部浏览器。",
        ),
        Server(
            name = ANDROID_NAME,
            type = "local",
            // 结构化 Android UI 自动化：底层复用 codetool-shizuku，不重复造桥
            command = listOf("/usr/bin/python3", "/usr/local/bin/codetool-android-mcp"),
            enabled = true,
            builtin = true,
            description = "通过 Shizuku 做结构化 Android UI 自动化：启动应用、导出控件树、按文字点击、输入、通知与截图。",
        ),
    )

    private val BUILTIN_NAMES = BUILTINS.map { it.name }.toSet()

    private fun root(ctx: Context) = RootfsManager.get(ctx.applicationContext).containerRoot

    private data class CliConfig(val label: String, val candidates: List<File>)

    /** 每个 CLI 的主配置候选（按 CLI 实际优先级）。 */
    private fun cliConfigs(ctx: Context): List<CliConfig> {
        val base = root(ctx)
        return listOf(
            CliConfig(
                "MiMo Code",
                listOf(
                    File(base, "root/.config/mimocode/mimocode.jsonc"),
                    File(base, "root/.config/mimocode/mimocode.json"),
                )
            ),
            CliConfig(
                "OpenCode",
                listOf(
                    File(base, "root/.config/opencode/opencode.json"),
                    File(base, "root/.config/opencode/opencode.jsonc"),
                )
            ),
        )
    }

    /** 读列表：两个 CLI 现有配置都读（同 CLI 有两份时也合并来源）。 */
    private fun readTargets(ctx: Context): List<Pair<String, File>> =
        cliConfigs(ctx).flatMap { c ->
            val existing = c.candidates.filter { it.isFile }
            (if (existing.isEmpty()) listOf(c.candidates.first()) else existing).map { c.label to it }
        }

    /** 写配置：每个 CLI 写它实际存在的那份；都不存在时创建默认路径。 */
    private fun writeTargets(ctx: Context): List<Pair<String, File>> =
        cliConfigs(ctx).map { c ->
            c.label to (c.candidates.firstOrNull { it.isFile } ?: c.candidates.first())
        }

    /** 简单 shell 分词：支持单引号/双引号/反斜杠转义，空格/制表符分隔。
     *  用于把用户填的整行启动命令（可能含带空格路径，如 `python3 "/path with space/mcp.py"`）
     *  正确拆成参数数组——避免 split(Regex("\\s+")) 把引号内空格拆断导致 CLI 启动失败。 */
    fun tokenizeCommand(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < cmd.length) {
            val ch = cmd[i]
            when {
                inSingle -> if (ch == '\'') inSingle = false else cur.append(ch)
                inDouble -> when (ch) {
                    '"' -> inDouble = false
                    '\\' -> if (i + 1 < cmd.length) { cur.append(cmd[i + 1]); i++ }
                    else -> cur.append(ch)
                }
                else -> when (ch) {
                    '\'' -> inSingle = true
                    '"' -> inDouble = true
                    '\\' -> if (i + 1 < cmd.length) { cur.append(cmd[i + 1]); i++ }
                    ' ', '\t' -> if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun serverFrom(name: String, o: JSONObject, label: String): Server = Server(
        name = name,
        type = o.optString("type", if (o.has("url")) "remote" else "local"),
        command = when (val c = o.opt("command")) {
            is JSONArray -> (0 until c.length()).map { c.optString(it, "") }.filter { it.isNotEmpty() }
            is String -> tokenizeCommand(c)
            else -> emptyList()
        },
        url = o.optString("url", ""),
        enabled = o.optBoolean("enabled", true),
        builtin = name in BUILTIN_NAMES,
        description = o.optString("description", "").trim()
            .ifBlank { BUILTINS.firstOrNull { it.name == name }?.description.orEmpty() },
        sources = listOf(label),
    )

    private fun toJson(s: Server) = JSONObject().apply {
        put("type", s.type)
        if (s.isRemote) put("url", s.url)
        else put("command", JSONArray().also { a -> s.command.forEach { a.put(it) } })
        put("enabled", s.enabled)
        put("description", s.description)
    }

    /** 合并两个 CLI 配置里的 mcp 段（同名以 MiMo 优先），并标出来源。实时读盘。 */
    fun listServers(ctx: Context): List<Server> {
        val map = LinkedHashMap<String, Server>()
        for ((label, f) in readTargets(ctx)) {
            val mcp = runCatching { Jsonc.parse(f.readText())?.obj }.getOrNull()
                ?.optJSONObject("mcp") ?: continue
            mcp.keys().forEach { name ->
                val o = mcp.optJSONObject(name) ?: return@forEach
                val prev = map[name]
                map[name] = if (prev == null) serverFrom(name, o, label)
                else prev.copy(sources = (prev.sources + label).distinct())
            }
        }
        return map.values.sortedWith(compareBy({ !it.builtin }, { it.name }))
    }

    /** 找不到配置可写时返回错误原因；成功返回 null。 */
    private fun mutate(ctx: Context, block: (JSONObject) -> Unit): String? =
        synchronized(AiModelManager.configLock) {
        var written = 0
        var skipped = 0
        for ((_, f) in writeTargets(ctx)) {
            val raw = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
            val parsed = when {
                raw.isNullOrBlank() -> Jsonc.Parsed(JSONObject(), false)
                else -> Jsonc.parse(raw)
            }
            if (parsed == null) {
                skipped++
                continue
            }
            val obj = parsed.obj
            val mcp = obj.optJSONObject("mcp") ?: JSONObject().also { obj.put("mcp", it) }
            val before = mcp.toString()
            block(mcp)
            // 没有任何实际变化就不写盘：避免切换一个服务时把另一份 JSONC 重排/丢注释
            if (mcp.toString() == before) continue
            runCatching {
                f.parentFile?.mkdirs()
                if (parsed.needsRewrite && raw != null) {
                    // JSONC 的注释无法被标准 JSON 序列化保留：先备份原文件再改写
                    File(f.absolutePath + ".codetool.bak").writeText(raw)
                }
                // 先写临时文件再原子替换：避免 MCP 页读盘时看到写了一半的 JSON。
                val tmp = File(f.parentFile, f.name + ".codetool.tmp")
                tmp.writeText(obj.toString(2) + "\n")
                if (!tmp.renameTo(f)) {
                    f.writeText(obj.toString(2) + "\n")
                    tmp.delete()
                }
                written++
            }
        }
        when {
            written == 0 && skipped > 0 -> "配置文件含无法解析的内容，未改写（原文件已保留）"
            written == 0 -> "没有可写的配置文件"
            else -> null
        }
    }

    fun upsert(ctx: Context, s: Server): String? {
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(s.name)) return "名称只能含字母/数字/.-_"
        if (!s.isRemote && s.command.isEmpty()) return "本地服务必须填启动命令"
        if (s.isRemote && s.url.isBlank()) return "远程服务必须填 URL"
        return mutate(ctx) { mcp ->
            // 合并进已有对象：保留 headers / environment 等本页未展示的字段
            val o = mcp.optJSONObject(s.name) ?: JSONObject()
            o.put("type", s.type)
            // M2：保留该文件自己已设的 enabled（用户显式开关优先），缺失才用 s.enabled。
            // 避免 ensureBuiltin 用合并列表把某 CLI 的 enabled 强加给另一个 CLI（如 mimo 开、opencode 关被改回开）。
            if (!o.has("enabled")) o.put("enabled", s.enabled)
            o.put("description", s.description)
            if (s.isRemote) {
                o.put("url", s.url)
                o.remove("command")
            } else {
                o.put("command", JSONArray().also { a -> s.command.forEach { a.put(it) } })
                o.remove("url")
            }
            mcp.put(s.name, o)
        }
    }

    /** 开关：只改 enabled，保留其它字段。 */
    fun setEnabled(ctx: Context, name: String, enabled: Boolean): String? = mutate(ctx) { mcp ->
        val o = mcp.optJSONObject(name)
        if (o != null) o.put("enabled", enabled)
        else BUILTINS.firstOrNull { it.name == name }?.let {
            mcp.put(name, toJson(it.copy(enabled = enabled)))
        }
    }

    fun remove(ctx: Context, name: String): String? = mutate(ctx) { mcp -> mcp.remove(name) }

    /**
     * 注册 / 自愈全部内置 MCP server（默认开启），让模型开箱可用。
     * 已存在但命令是旧版时只更新命令与类型，**保留用户的 enabled 开关**。
     *
     * 注意：必须**逐配置文件**检查内置服务是否齐全——若某份配置（如 opencode.json）
     * 被误删/重建丢了 mcp 段，而另一份（mimocode.jsonc）里同名服务还在，用合并列表判断
     * 会误以为"已存在"而漏补，导致该 CLI 的 MCP 消失。这里对每个 CLI 的配置文件分别补。
     */
    fun ensureBuiltin(ctx: Context): Int {
        // 日志 MCP 已下线：只移除确实指向旧内置脚本的条目，
        // 用户自建的同名服务（命令不同）不动。
        val legacy = listServers(ctx).firstOrNull {
            it.name == LEGACY_LOGS_NAME && it.command.any { c -> c.contains(LEGACY_LOGS_SCRIPT) }
        }
        if (legacy != null) remove(ctx, legacy.name)

        var n = 0
        val current = listServers(ctx)
        val targets = writeTargets(ctx)
        for (b in BUILTINS) {
            val existing = current.firstOrNull { it.name == b.name }
            // 只要该内置服务在某一份配置文件里缺失，就补全（upsert 写所有目标，幂等）；
            // 已存在但命令/类型变了也要更新。enabled 交给 upsert 逐文件保留各自状态，
            // 这里用默认 b.enabled（内置默认启用），不用合并列表的 existing.enabled（会跨 CLI 污染）。
            val missingInSome = targets.any { (_, f) -> b.name !in readMcpNames(f) }
            val changed = existing != null && (existing.command != b.command || existing.type != b.type)
            if (!missingInSome && !changed) continue
            val toAdd = b
            if (upsert(ctx, toAdd) == null) n++
        }
        return n
    }

    /** 读取单个配置文件自己的 mcp 段里所有服务名（不跨文件合并）。 */
    private fun readMcpNames(f: File): Set<String> {
        if (!f.isFile) return emptySet()
        val parsed = try {
            Jsonc.parse(f.readText())
        } catch (_: Throwable) {
            null
        } ?: return emptySet()
        val mcp = parsed.obj.optJSONObject("mcp") ?: return emptySet()
        val names = HashSet<String>()
        val it = mcp.keys()
        while (it.hasNext()) names.add(it.next())
        return names
    }
}
