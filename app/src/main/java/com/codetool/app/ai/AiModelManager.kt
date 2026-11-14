package com.codetool.app.ai

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import com.codetool.app.util.Jsonc
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * AI 模型管理（多模型）：一份 codetool 自己的模型清单 + 写入各 CLI 的原生配置。
 *
 * 所有读取都是「实时读盘」——每次调用都重新读配置文件，不缓存，保证 UI 与
 * 外部改动（脚本/终端/CLI 自己改的配置）保持一致。
 *
 * - 清单：/root/.config/codetool/ai-models.json（数组，UI 的数据源）
 * - mimo：二进制 /root/.mimocode/bin/mimo（默认不在 PATH）；
 *   认证 /root/.local/share/mimocode/auth.json，模型 /root/.local/state/mimocode/model.json
 * - opencode：配置 /root/.config/opencode/opencode.json
 */
object AiModelManager {

    // ---------------- 服务商列表（规范：不让用户手填 provider） ----------------

    /** 服务商：写入配置的 id / 显示名 / 默认 Base URL / 常用模型。 */
    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val models: List<String>,
        val builtin: Boolean = true,
    )

    /**
     * 预置服务商。Base URL 是「建议值」，选中后填进输入框仍可编辑；地址以服务商
     * 实际文档为准，填完用「测试连接」验证（国内网络 / 私有部署地址都可能不同）。
     * 统一走 OpenAI 兼容协议：GET /models + POST /chat/completions。
     */
    val BUILTIN_PROVIDERS: List<Provider> = listOf(
        // 小米 MiMo 与商汤 SenseNova 是两家不同服务商，各自独立地址，不能合并。
        Provider("mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1",
            listOf("mimo-v2-flash", "mimo-v2-pro")),
        Provider("sensenova", "商汤 SenseNova", "https://token.sensenova.cn/v1",
            listOf("deepseek-v4.1-flash", "SenseNova-V6-Turbo")),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner")),
        Provider("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1",
            listOf("moonshot-v1-8k", "moonshot-v1-32k", "kimi-k2-0711-preview")),
        Provider("qwen", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            listOf("qwen-plus", "qwen-turbo", "qwen-max")),
        Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4",
            listOf("glm-4.6", "glm-4-flash", "glm-4-air")),
        Provider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1",
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen3-8B")),
        Provider("openai", "OpenAI", "https://api.openai.com/v1",
            listOf("gpt-4o", "gpt-4o-mini")),
        Provider("anthropic", "Anthropic", "https://api.anthropic.com/v1",
            listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest")),
    )

    private fun customProvidersFile(ctx: Context) = f(ctx, "root/.config/codetool/ai-providers.json")

    /** 用户新增的服务商（OpenAI 兼容协议），持久化到容器配置目录，可跨页面/重启复用。 */
    fun listCustomProviders(ctx: Context): MutableList<Provider> {
        val file = customProvidersFile(ctx)
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                val models = o.optJSONArray("models")?.let { a ->
                    (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
                } ?: emptyList()
                Provider(id, o.optString("label", id), o.optString("baseUrl", ""), models, false)
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveCustomProviders(ctx: Context, list: List<Provider>) {
        val file = customProvidersFile(ctx).apply { parentFile?.mkdirs() }
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id).put("label", p.label).put("baseUrl", p.baseUrl)
                .put("models", JSONArray().also { a -> p.models.forEach { a.put(it) } }))
        }
        file.writeText(jsonPretty(arr))
    }

    /** 新增自定义服务商；成功 true，id 重复 / 必填为空 / 与预置冲突返回 false。 */
    fun addCustomProvider(ctx: Context, p: Provider): Boolean {
        if (p.id.isBlank() || p.baseUrl.isBlank()) return false
        val list = listCustomProviders(ctx)
        if (BUILTIN_PROVIDERS.any { it.id == p.id } || list.any { it.id == p.id }) return false
        list.add(p.copy(builtin = false))
        saveCustomProviders(ctx, list)
        return true
    }

    fun removeCustomProvider(ctx: Context, id: String) {
        val list = listCustomProviders(ctx)
        list.removeAll { it.id == id }
        saveCustomProviders(ctx, list)
    }

    /** 下拉数据源：预置 + 用户自定义。末尾「＋ 新增」哨兵由 UI 自己追加。 */
    fun allProviders(ctx: Context): List<Provider> = BUILTIN_PROVIDERS + listCustomProviders(ctx)

    fun providerById(ctx: Context, id: String): Provider? = allProviders(ctx).find { it.id == id }

    // ---------------- 模型清单 ----------------

    /** API 协议：chat = /chat/completions，responses = OpenAI Responses API /responses。 */
    object ApiType {
        const val CHAT = "chat"
        const val RESPONSES = "responses"
        val labels = arrayOf("Chat Completions API（/chat/completions）", "Responses API（/responses）")
        fun fromPosition(pos: Int) = if (pos == 1) RESPONSES else CHAT
        fun positionOf(type: String) = if (type == RESPONSES) 1 else 0
    }

    /** 一条模型配置。target = "mimo" | "opencode"；baseUrl = OpenAI 兼容 API 地址。 */
    data class Model(
        var name: String = "",
        var provider: String = "",
        var modelId: String = "",
        var apiKey: String = "",
        var baseUrl: String = "",
        var apiType: String = ApiType.CHAT,
        var enabled: Boolean = true,
        var target: String = "mimo",
        var current: Boolean = false,
    ) {
        fun toJson() = JSONObject()
            .put("name", name).put("provider", provider).put("modelId", modelId)
            .put("apiKey", apiKey).put("baseUrl", baseUrl)
            .put("apiType", apiType).put("enabled", enabled)
            .put("target", target).put("current", current)

        companion object {
            fun fromJson(o: JSONObject) = Model(
                name = o.optString("name", ""),
                provider = o.optString("provider", ""),
                modelId = o.optString("modelId", ""),
                apiKey = o.optString("apiKey", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiType = o.optString("apiType", ApiType.CHAT),
                enabled = o.optBoolean("enabled", true),
                target = o.optString("target", "mimo"),
                current = o.optBoolean("current", false),
            )
        }
    }

    data class Status(val installed: Boolean, val version: String, val detail: String)

    /**
     * 美化 JSON（2 空格缩进）并去掉 Android JSONObject 默认产生的 `\/` 转义。
     * JSONObject.toString() 会把所有 `/` 写成 `\/`（历史遗留的安全转义，JSON 合法但人读/手编难受）。
     * 合法 JSON 里 `\/` 只可能出现在 provider 配置的 URL 路径，还原成 `/` 安全且不影响数据。
     */
    private fun jsonPretty(any: Any): String {
        val pretty = when (any) {
            is JSONObject -> any.toString(2)
            is JSONArray -> any.toString(2)
            else -> any.toString()
        }
        return pretty.replace("\\/", "/")
    }

    private fun root(ctx: Context): File =
        RootfsManager.get(ctx.applicationContext).containerRoot
    private fun f(ctx: Context, p: String) = File(root(ctx), p)

    private fun modelsFile(ctx: Context) = f(ctx, "root/.config/codetool/ai-models.json")
    private fun mimoBin(ctx: Context) = f(ctx, "root/.mimocode/bin/mimo")
    private fun mimoAuth(ctx: Context) = f(ctx, "root/.local/share/mimocode/auth.json")
    private fun mimoModel(ctx: Context) = f(ctx, "root/.local/state/mimocode/model.json")
    /** mimo 主配置（JSONC）：自定义 provider 的 baseURL 要写进 provider.<id>.options.baseURL。 */
    private fun mimoConfig(ctx: Context) = f(ctx, "root/.config/mimocode/mimocode.jsonc")
    private fun opencodeBins(ctx: Context) = listOf(
        "root/.opencode/bin/opencode", "usr/local/bin/opencode",
        "root/.local/bin/opencode", "usr/bin/opencode",
    ).map { f(ctx, it) }
    private fun opencodeCfg(ctx: Context) = f(ctx, "root/.config/opencode/opencode.json")

    /** 实时读取模型清单（每次读盘）。文件不存在返回空列表。 */
    fun listModels(ctx: Context): MutableList<Model> {
        val file = modelsFile(ctx)
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { Model.fromJson(arr.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveModels(ctx: Context, list: List<Model>) {
        val file = modelsFile(ctx).apply { parentFile?.mkdirs() }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(jsonPretty(arr))
    }

    fun upsertModel(ctx: Context, model: Model) {
        val list = listModels(ctx)
        val idx = list.indexOfFirst { it.name == model.name && it.target == model.target }
        if (idx >= 0) list[idx] = model else list.add(model)
        saveModels(ctx, list)
    }

    fun removeModel(ctx: Context, name: String, target: String) {
        val list = listModels(ctx)
        val removed = list.firstOrNull { it.name == name && it.target == target }
        list.removeAll { it.name == name && it.target == target }
        saveModels(ctx, list)
        // 自动清理：该 provider 在此 target 下已无其它模型时，把 CLI 原生配置里的
        // provider 段一并清掉（writeMimo/writeOpencode 只写不删，不清会残留）。
        if (removed != null) {
            val provider = removed.provider
            if (provider.isNotEmpty() && list.none { it.target == target && it.provider == provider }) {
                cleanupProvider(ctx, target, provider)
            }
        }
    }

    /**
     * 从 CLI 原生配置里摘掉一个 provider 的残留（清前备份 .codetool.bak）。
     * 只删 provider 段本身 + mimo 的 auth key 段，**保留 mcp 等其它字段**。
     * mimo：mimocode.jsonc 的 provider.<id>（JSONC 安全写）+ auth.json 的 <id>。
     * opencode：opencode.json 的 provider.<id>（纯 JSON）。
     */
    private fun cleanupProvider(ctx: Context, target: String, provider: String) {
        if (target == "mimo") {
            // mimocode.jsonc：用 Jsonc 解析（保注释/容错），删 provider.<id> 后写回
            val cfg = mimoConfig(ctx)
            if (cfg.isFile) {
                val raw = runCatching { cfg.readText() }.getOrNull().orEmpty()
                val parsed = Jsonc.parse(raw) ?: return
                val providers = parsed.obj.optJSONObject("provider") ?: return
                if (providers.has(provider)) {
                    backupFile(cfg)
                    providers.remove(provider)
                    if (providers.length() == 0) parsed.obj.remove("provider")
                    runCatching { cfg.writeText(jsonPretty(parsed.obj) + "\n") }
                }
            }
            // auth.json：删 provider 的 key 段（纯 JSON）
            val auth = mimoAuth(ctx)
            if (auth.isFile) {
                runCatching {
                    val obj = JSONObject(auth.readText())
                    if (obj.has(provider)) {
                        backupFile(auth)
                        obj.remove(provider)
                        auth.writeText(jsonPretty(obj))
                    }
                }
            }
        } else {
            // opencode.json：删 provider.<id>（纯 JSON）
            val file = opencodeCfg(ctx)
            if (file.isFile) {
                runCatching {
                    val obj = JSONObject(file.readText())
                    val providers = obj.optJSONObject("provider") ?: return@runCatching
                    if (providers.has(provider)) {
                        backupFile(file)
                        providers.remove(provider)
                        if (providers.length() == 0) obj.remove("provider")
                        file.writeText(jsonPretty(obj))
                    }
                }
            }
        }
    }

    /** 删改 CLI 配置前备份原文件为 <name>.codetool.bak（覆盖旧备份）。 */
    private fun backupFile(f: File) {
        runCatching { f.copyTo(File(f.parentFile, f.name + ".codetool.bak"), overwrite = true) }
    }

    /** 设为当前：同 target 内其它清 current，本条置 current，并写入对应 CLI 原生配置。 */
    fun setCurrent(ctx: Context, name: String, target: String): Boolean {
        val list = listModels(ctx)
        var found: Model? = null
        list.forEach {
            if (it.target == target) it.current = (it.name == name)
            if (it.name == name && it.target == target) found = it
        }
        val m = found ?: return false
        if (!m.enabled) return false   // 停用的模型不能设为当前
        saveModels(ctx, list)
        return if (target == "opencode") writeOpencode(ctx, m) else writeMimo(ctx, m)
    }

    /** 从 CLI 现有原生配置导入为清单条目（首次/清单为空时用）。 */
    fun importFromCli(ctx: Context): Int {
        val list = listModels(ctx)
        if (list.isNotEmpty()) return 0
        var added = 0
        runCatching {
            val auth = JSONObject(mimoAuth(ctx).readText())
            val recent = JSONObject(mimoModel(ctx).readText()).optJSONArray("recent")
            if (recent != null) for (i in 0 until recent.length()) {
                val r = recent.getJSONObject(i)
                val p = r.optString("providerID", "")
                val mid = r.optString("modelID", "")
                if (p.isEmpty() || mid.isEmpty()) continue
                val key = auth.optJSONObject(p)?.optString("key", "").orEmpty()
                // CLI 原生配置里没有 API 地址，绝不按 provider 猜一个填进去
                // （同名 provider 在不同服务商/CLI 下地址可能完全不同）。
                // 留空，由用户在编辑弹窗里选服务商或手填，并用「测试连接」确认。
                list.add(Model(
                    name = "$p/$mid", provider = p, modelId = mid, apiKey = key,
                    baseUrl = "",
                    target = "mimo", current = i == 0,
                ))
                added++
            }
        }
        runCatching {
            val cfg = JSONObject(opencodeCfg(ctx).readText())
            val oc = cfg.optString("model", "")
            if (oc.isNotEmpty()) {
                val slash = oc.indexOf('/')
                val p = if (slash > 0) oc.substring(0, slash) else ""
                val mid = if (slash > 0) oc.substring(slash + 1) else oc
                val key = cfg.optJSONObject("env")?.optString("${p.uppercase()}_API_KEY", "").orEmpty()
                list.add(Model(
                    name = oc, provider = p, modelId = mid, apiKey = key,
                    baseUrl = cfg.optJSONObject("provider")?.optJSONObject(p)
                        ?.optJSONObject("options")?.optString("baseURL", "").orEmpty(),
                    target = "opencode", current = true,
                ))
                added++
            }
        }
        if (added > 0) saveModels(ctx, list)
        return added
    }

    // ---------------- 写入各 CLI 原生配置（含 Base URL） ----------------

    /** 写 mimo：auth.json（provider->{type,key,baseURL}）+ model.json（recent[0]）+ mimocode.jsonc（provider baseURL）。 */
    fun writeMimo(ctx: Context, m: Model): Boolean = runCatching {
        val auth = mimoAuth(ctx).apply { parentFile?.mkdirs() }
        val obj = if (auth.isFile) runCatching { JSONObject(auth.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        val entry = obj.optJSONObject(m.provider) ?: JSONObject()
        entry.put("type", "api")
        entry.put("key", m.apiKey)
        if (m.baseUrl.isNotEmpty()) entry.put("baseURL", m.baseUrl)
        obj.put(m.provider, entry)
        auth.writeText(jsonPretty(obj))

        val mf = mimoModel(ctx).apply { parentFile?.mkdirs() }
        val mo = if (mf.isFile) runCatching { JSONObject(mf.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        val recent = JSONArray()
        recent.put(JSONObject().put("providerID", m.provider).put("modelID", m.modelId))
        mo.put("recent", recent)
        mo.put("favorite", mo.optJSONArray("favorite") ?: JSONArray())
        mo.put("variant", mo.optJSONObject("variant") ?: JSONObject())
        mf.writeText(jsonPretty(mo))

        // 关键：mimo 列模型时按 provider.<id>.options.baseURL 取 API 地址（和 opencode
        // 的 opencode.json 同构）。只写 auth.json 的 baseURL 不够——实测 mimo 认得 provider
        // 却拿不到地址，模型列表为空。这里把 baseURL 也写进 mimocode.jsonc（JSONC 安全写）。
        if (m.baseUrl.isNotEmpty() && m.provider.isNotEmpty()) {
            val cfg = mimoConfig(ctx).apply { parentFile?.mkdirs() }
            val raw = if (cfg.isFile) runCatching { cfg.readText() }.getOrNull() else null
            val parsed = when {
                raw.isNullOrBlank() -> Jsonc.Parsed(JSONObject(), false)
                else -> Jsonc.parse(raw)
            }
            if (parsed != null) {
                val obj = parsed.obj
                val providers = obj.optJSONObject("provider") ?: JSONObject().also { obj.put("provider", it) }
                val p = providers.optJSONObject(m.provider) ?: JSONObject().also { providers.put(m.provider, it) }
                val options = p.optJSONObject("options") ?: JSONObject().also { p.put("options", it) }
                options.put("baseURL", m.baseUrl)
                cfg.writeText(jsonPretty(obj) + "\n")
            }
        }
        true
    }.getOrDefault(false)

    /** 写 opencode.json：model + env.{PROVIDER}_API_KEY + provider.<id>.options.baseURL。 */
    fun writeOpencode(ctx: Context, m: Model): Boolean = runCatching {
        val file = opencodeCfg(ctx).apply { parentFile?.mkdirs() }
        val obj = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        obj.put("model", if (m.provider.isNotEmpty()) "${m.provider}/${m.modelId}" else m.modelId)
        if (m.apiKey.isNotEmpty()) {
            val env = obj.optJSONObject("env") ?: JSONObject()
            env.put("${m.provider.uppercase()}_API_KEY", m.apiKey)
            obj.put("env", env)
        }
        if (m.baseUrl.isNotEmpty() && m.provider.isNotEmpty()) {
            val providers = obj.optJSONObject("provider") ?: JSONObject()
            val pObj = providers.optJSONObject(m.provider) ?: JSONObject()
            val options = pObj.optJSONObject("options") ?: JSONObject()
            options.put("baseURL", m.baseUrl)
            pObj.put("options", options)
            providers.put(m.provider, pObj)
            obj.put("provider", providers)
        }
        file.writeText(jsonPretty(obj))
        true
    }.getOrDefault(false)

    /**
     * 同步到 CLI：把每个目标（mimo / opencode）当前模型重新写入 CLI 原生配置。
     * 用途：改了 baseURL / 修了写入逻辑 / 手动改过 JSON 后，一键让 CLI 配置和清单对齐。
     * 返回 (成功目标数, 明细)。
     */
    fun syncAll(ctx: Context): Pair<Int, List<String>> {
        val details = mutableListOf<String>()
        var okCount = 0
        for (target in listOf("mimo", "opencode")) {
            val cur = listModels(ctx).find { it.target == target && it.current }
            android.util.Log.w("AiSync", "syncAll target=$target cur=${cur?.name} key=${cur?.apiKey?.take(8)} baseUrl=${cur?.baseUrl}")
            if (cur == null) {
                details.add("$target：无当前模型，跳过")
                continue
            }
            val ok = if (target == "mimo") writeMimo(ctx, cur) else writeOpencode(ctx, cur)
            android.util.Log.w("AiSync", "syncAll target=$target ok=$ok authAfter=${mimoAuth(ctx).takeIf { it.isFile }?.readText()?.take(80)}")
            details.add("$target：${cur.name} ${if (ok) "✓" else "✗"}")
            if (ok) okCount++
        }
        return okCount to details
    }

    // ---------------- 版本：多源回退（规范：必须显示真实版本号） ----------------

    private val VERSION_RE = Regex("""\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.]+)?""")

    /** 把 "MiMo Code 0.1.16" 之类输出规整成纯版本号。 */
    private fun normalizeVersion(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return VERSION_RE.find(s)?.value ?: s.take(40)
    }

    private fun readVersion(ctx: Context, cache: String): String = runCatching {
        normalizeVersion(f(ctx, cache).takeIf { it.isFile }?.readText().orEmpty())
    }.getOrDefault("")

    /**
     * 浅层扫描安装目录里的 package.json / VERSION / version（跳过 node_modules），
     * 纯文件读、限深度与数量，首页刷新可直接调用，不阻塞。
     */
    private fun scanVersion(ctx: Context, relDirs: List<String>): String = runCatching {
        for (rel in relDirs) {
            val dir = f(ctx, rel)
            if (!dir.isDirectory) continue
            val files = dir.walkTopDown().maxDepth(3)
                .onEnter { it.name != "node_modules" && it.name != ".cache" && it.name != "vendor" }
                .filter { it.isFile && (it.name == "package.json" || it.name == "VERSION" || it.name == "version") }
                .take(40)
            for (file in files) {
                val txt = runCatching { file.readText().take(65536) }.getOrDefault("")
                val v = if (file.name == "package.json") {
                    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
                } else VERSION_RE.find(txt)?.value
                val n = normalizeVersion(v.orEmpty())
                if (n.isNotEmpty()) return n
            }
        }
        ""
    }.getOrDefault("")

    fun mimoVersion(ctx: Context): String =
        readVersion(ctx, "root/.mimocode/.codetool-version")
            .ifEmpty { scanVersion(ctx, listOf("root/.mimocode", "root/.local/share/mimocode")) }

    fun opencodeVersion(ctx: Context): String =
        readVersion(ctx, "root/.opencode/.codetool-version")
            .ifEmpty { scanVersion(ctx, listOf("root/.opencode", "root/.local/share/opencode")) }

    /**
     * 容器内跑一次 CLI --version 写缓存（一次性补救；IO 线程调用）。
     * 用 tawcroot 而非宿主直接 exec：CLI 依赖容器 glibc，Android bionic 跑不了。
     */
    fun probeVersions(ctx: Context): Boolean = runCatching {
        val app = ctx.applicationContext
        val root = RootfsManager.get(app).containerRoot
        val runtime = TawcrootRuntime.get(app)
        if (!root.isDirectory || !File(runtime.binaryPath).exists()) return false
        val script =
            "[ -x /root/.mimocode/bin/mimo ] && /root/.mimocode/bin/mimo --version 2>/dev/null | head -1 > /root/.mimocode/.codetool-version\n" +
                "for od in /root/.opencode/bin/opencode /usr/local/bin/opencode /root/.local/bin/opencode /usr/bin/opencode; do\n" +
                "  if [ -x \"\$od\" ]; then \"\$od\" --version 2>/dev/null | head -1 > /root/.opencode/.codetool-version; break; fi\n" +
                "done\n" +
                "exit 0\n"
        val p = runtime.exec(root, "/bin/sh", "-c", script)
        p.inputStream.bufferedReader().readText()
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroy()
            return false
        }
        true
    }.getOrDefault(false)

    fun mimoStatus(ctx: Context): Status {
        if (!mimoBin(ctx).isFile) return Status(false, "", "未安装")
        val v = mimoVersion(ctx)
        return Status(true, v, if (v.isNotEmpty()) "v$v" else "已安装 · 版本未知")
    }

    fun opencodeStatus(ctx: Context): Status {
        if (opencodeBins(ctx).none { it.isFile }) return Status(false, "", "未安装")
        val v = opencodeVersion(ctx)
        return Status(true, v, if (v.isNotEmpty()) "v$v" else "已安装 · 版本未知")
    }

    // ---------------- 测试连接（规范：配置弹窗必须带） ----------------

    private data class HttpResult(val code: Int, val body: String)

    private fun http(urlStr: String, method: String, key: String, body: String?): HttpResult {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = c.responseCode
        val text = runCatching {
            (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText().take(524288) }.orEmpty()
        }.getOrDefault("")
        c.disconnect()
        return HttpResult(code, text)
    }

    private fun shortBody(b: String) = b.replace(Regex("\\s+"), " ").take(120)

    /** 列表里显示服务商「显示名」而不是裸 provider id。 */
    fun providerLabel(ctx: Context, id: String): String =
        allProviders(ctx).find { it.id == id }?.label ?: id.ifEmpty { "未指定" }

    // ---------------- 配置文件编辑器（核对/直接改原始 JSON，可手删模型） ----------------

    /** 可直接编辑的配置文件清单：label 展示用，rel 是相对容器根的路径。 */
    data class EditableConfig(val label: String, val rel: String)

    fun listEditableConfigs(): List<EditableConfig> = listOf(
        EditableConfig("mimo 模型 (model.json)", "root/.local/state/mimocode/model.json"),
        EditableConfig("mimo 认证 (auth.json)", "root/.local/share/mimocode/auth.json"),
        EditableConfig("mimo 主配置 (mimocode.jsonc)", "root/.config/mimocode/mimocode.jsonc"),
        EditableConfig("opencode 配置 (opencode.json)", "root/.config/opencode/opencode.json"),
    )

    /** 读配置文件原文（容错：不存在/读失败返回 null）。 */
    fun readConfigFile(ctx: Context, rel: String): String? = runCatching {
        val f = f(ctx, rel)
        if (f.isFile) f.readText() else null
    }.getOrNull()

    /** 写配置文件原文（编辑器保存用）。返回是否成功。 */
    fun writeConfigFile(ctx: Context, rel: String, content: String): Boolean = runCatching {
        val file = f(ctx, rel).apply { parentFile?.mkdirs() }
        // 写前备份（.codetool.bak），避免改坏后无法回退
        if (file.isFile) runCatching { file.copyTo(File(file.parentFile, file.name + ".codetool.bak"), overwrite = true) }
        file.writeText(content)
        true
    }.getOrDefault(false)

    data class FetchResult(val models: List<String>, val error: String)

    /**
     * 拉取服务商支持的模型列表（Chatbox / Cherry Studio 同款流程）：
     * GET &lt;baseUrl&gt;/models，兼容 OpenAI 的 {"data":[{"id":...}]} 与直接数组 / 字符串数组。
     * 必须在 IO 线程调用。返回的 error 为空表示成功。
     */
    fun fetchModels(m: Model): FetchResult {
        val base = m.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return FetchResult(emptyList(), "请先填写 API 地址（Base URL）")
        if (!base.startsWith("http://") && !base.startsWith("https://"))
            return FetchResult(emptyList(), "API 地址需以 http(s):// 开头")
        val r = runCatching { http("$base/models", "GET", m.apiKey, null) }.getOrElse { e ->
            return FetchResult(emptyList(), "网络错误：${e.message ?: "不可达 / 超时"}")
        }
        if (r.code !in 200..299) {
            val why = when (r.code) {
                401, 403 -> "API Key 无效或无权限"
                404 -> "接口不存在，请核对 API 地址（是否需要 /v1）"
                429 -> "请求被限流"
                else -> shortBody(r.body)
            }
            return FetchResult(emptyList(), "HTTP ${r.code}：$why")
        }
        val ids = runCatching {
            val t = r.body.trim()
            val arr = if (t.startsWith("{")) {
                JSONObject(t).optJSONArray("data") ?: JSONArray()
            } else JSONArray(t)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                when {
                    o != null -> o.optString("id", "").ifEmpty { o.optString("name", "") }
                    else -> arr.optString(i, "")
                }.takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
        if (ids.isEmpty()) return FetchResult(
            emptyList(), "已连接 (HTTP ${r.code})，但响应里没解析出模型 ID（服务商格式可能不兼容）"
        )
        return FetchResult(ids, "")
    }

    /**
     * 测试模型连通性（必须在 IO 线程调用）：
     * 1) GET <baseUrl>/models 校验 Key 与模型是否在列表；
     * 2) 不支持 /models 时退回一次最小 chat/completions 请求。
     * 返回可直接展示给用户的中文结果。
     */
    fun testModel(m: Model): String {
        val base = m.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "✗ 请先填写 API 地址（Base URL）"
        if (!base.startsWith("http://") && !base.startsWith("https://"))
            return "✗ API 地址需以 http(s):// 开头"

        val models = runCatching { http("$base/models", "GET", m.apiKey, null) }.getOrNull()
        if (models != null && models.code in 200..299) {
            val has = m.modelId.isNotEmpty() && models.body.contains(m.modelId, ignoreCase = true)
            return if (has) "✓ 连接成功 (HTTP ${models.code})，模型 ${m.modelId} 在可用列表中"
            else "✓ 连接成功 (HTTP ${models.code})，但列表未找到 ${m.modelId}（请核对模型 ID）"
        }

        // 按所选协议构造最小请求：chat=/chat/completions(messages)，responses=/responses(input)
        val isResponses = m.apiType == ApiType.RESPONSES
        val endpoint = if (isResponses) "$base/responses" else "$base/chat/completions"
        val proto = if (isResponses) "Responses API" else "Chat Completions API"
        val payload = if (isResponses) {
            JSONObject().put("model", m.modelId)
                .put("input", "hi").put("max_output_tokens", 1).toString()
        } else {
            JSONObject().put("model", m.modelId)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .put("max_tokens", 1).toString()
        }
        val chat = runCatching { http(endpoint, "POST", m.apiKey, payload) }.getOrNull()
        return when {
            chat == null -> "✗ 无法连接：网络不可达 / 超时 / 证书错误（GET /models → HTTP ${models?.code ?: "—"}）"
            chat.code in 200..299 -> "✓ $proto 可达 (HTTP ${chat.code})，模型 ${m.modelId} 可用"
            chat.code == 401 || chat.code == 403 -> "✗ API Key 无效或无权限 (HTTP ${chat.code})"
            chat.code == 404 -> "✗ 接口不存在 (HTTP 404)，请核对 API 地址 / 协议（$proto）与模型 ID"
            chat.code == 429 -> "✗ 请求被限流 (HTTP 429)，Key 可能有效但额度不足"
            else -> "✗ HTTP ${chat.code}（$proto）：${shortBody(chat.body)}"
        }
    }

    // ---------------- PATH 修复 ----------------

    val FIX_PATH_SCRIPT =
        "mkdir -p /etc/profile.d\n" +
            "cat > /etc/profile.d/codetool-ai.sh <<'AIEOF'\n" +
            "# AI CLI (mimo / opencode) 加入 PATH\n" +
            "for d in /root/.mimocode/bin /root/.opencode/bin /root/.local/bin; do\n" +
            "  [ -d \"\$d\" ] && case \":\$PATH:\" in *\":\$d:\"*) ;; *) PATH=\"\$d:\$PATH\";; esac\n" +
            "done\n" +
            "export PATH\n" +
            "AIEOF\n" +
            "chmod +x /etc/profile.d/codetool-ai.sh\n" +
            "grep -q 'codetool-ai.sh' /root/.bashrc 2>/dev/null || " +
            "echo '[ -f /etc/profile.d/codetool-ai.sh ] && . /etc/profile.d/codetool-ai.sh' >> /root/.bashrc\n" +
            "# 缓存版本供首页/AI 页快速显示\n" +
            "[ -x /root/.mimocode/bin/mimo ] && /root/.mimocode/bin/mimo --version 2>/dev/null | head -1 > /root/.mimocode/.codetool-version\n" +
            "for od in /root/.opencode/bin/opencode /usr/local/bin/opencode /root/.local/bin/opencode; do\n" +
            "  if [ -x \"\$od\" ]; then \"\$od\" --version 2>/dev/null | head -1 > /root/.opencode/.codetool-version; break; fi\n" +
            "done\n" +
            "echo '[OK] AI CLI PATH 已写入（新开终端会话生效）'\n"
}
