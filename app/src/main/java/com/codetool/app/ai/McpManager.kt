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
        /** 出现在哪些 CLI 配置里：MiMo Code / OpenCode。 */
        val sources: List<String> = emptyList(),
    ) {
        val isRemote: Boolean get() = type != "local"
    }

    const val BUILTIN_NAME = "codetool-browser"

    /** 内置 MCP server：app 自带、默认开启，让模型能调用内置浏览器。 */
    val BUILTIN = Server(
        name = BUILTIN_NAME,
        type = "local",
        // 绝对路径：CLI 以 stdio 子进程拉起 server，不依赖登录 shell 的 PATH
        command = listOf("/usr/bin/python3", "/usr/local/bin/codetool-mcp"),
        enabled = true,
        builtin = true,
    )

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

    private fun serverFrom(name: String, o: JSONObject, label: String): Server = Server(
        name = name,
        type = o.optString("type", if (o.has("url")) "remote" else "local"),
        command = when (val c = o.opt("command")) {
            is JSONArray -> (0 until c.length()).map { c.optString(it, "") }.filter { it.isNotEmpty() }
            is String -> c.split(Regex("\\s+")).filter { it.isNotEmpty() }
            else -> emptyList()
        },
        url = o.optString("url", ""),
        enabled = o.optBoolean("enabled", true),
        builtin = name == BUILTIN_NAME,
        sources = listOf(label),
    )

    private fun toJson(s: Server) = JSONObject().apply {
        put("type", s.type)
        if (s.isRemote) put("url", s.url)
        else put("command", JSONArray().also { a -> s.command.forEach { a.put(it) } })
        put("enabled", s.enabled)
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
    private fun mutate(ctx: Context, block: (JSONObject) -> Unit): String? {
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
                f.writeText(obj.toString(2) + "\n")
                written++
            }
        }
        return when {
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
            o.put("enabled", s.enabled)
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
        else if (name == BUILTIN_NAME) mcp.put(name, toJson(BUILTIN.copy(enabled = enabled)))
    }

    fun remove(ctx: Context, name: String): String? = mutate(ctx) { mcp -> mcp.remove(name) }

    /**
     * 注册 / 自愈内置 MCP server（默认开启），让模型开箱可用。
     * 已存在但命令是旧版时只更新命令与类型，**保留用户的 enabled 开关**。
     */
    fun ensureBuiltin(ctx: Context): Int {
        val existing = listServers(ctx).firstOrNull { it.name == BUILTIN_NAME }
            ?: return if (upsert(ctx, BUILTIN) == null) 1 else 0
        if (existing.command == BUILTIN.command && existing.type == BUILTIN.type) return 0
        return if (upsert(ctx, BUILTIN.copy(enabled = existing.enabled)) == null) 1 else 0
    }
}
