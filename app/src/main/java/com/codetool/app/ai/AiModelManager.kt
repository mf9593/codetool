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

    /** 与 McpManager 共享的配置文件写锁：AI 模型同步与 MCP 增删可能并发写同一份 mimo/opencode 配置，
     *  统一用这把锁串行化「读-改-写」，避免互相覆盖对方刚写入的字段。 */
    @JvmField
    val configLock = Any()

    /** 思考强度全部档位（opencode ReasoningEfforts 全集，不含 "default"）。
     *  opencode：capabilities.reasoning.effort = 该集合（据此建 #variant，openai-compatible 协议每档一个）。
     *  mimo：provider.<id>.models.<id>.variants = {每档:{}}（mimo 据此建可切档位，含小米 MiMo）。 */
    val ALL_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

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
        // 国家超算互联网（SCNet）：OpenAI 兼容协议，base_url 必须带 /v1（仅接口版本）。
        // 模型名以「模型列表」页为准；填完 Key 用「测试连接」会拉真实 /models 校验。
        Provider("scnet", "国家超算互联网", "https://api.scnet.cn/api/llm/v1",
            listOf("DeepSeek-V4.1-Flash-1017", "DeepSeek-V4-Pro-0813", "Qwen3.8-Max", "Qwen3.8-Flash", "GLM-5.3", "MiniMax-M2.5")),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            listOf("deepseek/deepseek-chat", "anthropic/claude-sonnet-4.5", "openai/gpt-4o", "google/gemini-2.5-pro")),
        Provider("modelscope", "魔搭 ModelScope", "https://api-inference.modelscope.cn/v1",
            listOf("Qwen/Qwen3-Coder-480B-A35B-Instruct", "deepseek-ai/DeepSeek-V3.2-Exp", "moonshotai/Kimi-K2-Instruct", "ZhipuAI/GLM-4.6")),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1",
            listOf("deepseek-ai/deepseek-r1", "meta/llama-3.3-70b-instruct", "qwen/qwen2.5-coder-32b-instruct", "nvidia/llama-3.3-nemotron-super-49b-v1")),
        // 讯飞星辰 MaaS：模型 id 是平台自己的写法（xop 前缀），以「模型列表」页为准。
        Provider("xfyun", "讯飞星辰 MaaS", "https://maas-api.cn-huabei-1.xf-yun.com/v1",
            listOf("xopdeepseekv4pro", "xopglm53", "xopqwen35397b", "xminimaxm25")),
        // 腾讯 TokenHub（Token Plan）：完整 URL 是 .../plan/v3/chat/completions，
        // 这里存 base（.../plan/v3），程序/CLI 会自动再拼 /chat/completions。
        Provider("tencent", "腾讯 TokenHub", "https://tokenhub.tencentmaas.com/plan/v3",
            listOf("deepseek-v4.1-flash", "deepseek-v4-pro", "glm-5.3", "kimi-k3", "minimax-m3", "hy4")),
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
        atomicWrite(file, jsonPretty(arr))
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
        // 模型能力（从 /models API 返回的字段解析；0 / true 为兜底，表示"未知，按全支持处理"）
        var contextLength: Int = 0,
        var maxOutputLength: Int = 0,
        var supportsToolCall: Boolean = true,
        var supportsReasoning: Boolean = true,
        var supportsImage: Boolean = true,
        var supportsTemperature: Boolean = true,
        // 思考强度（reasoning effort）：""=默认（不声明）；否则为所选档位（none/minimal/low/medium/high/
        // xhigh/max）。同步到 opencode 顶层 model#档位 + capabilities.reasoning.effort 支持集合，
        // 以及 mimo provider.<id>.models.<id>.variants（据此建可切档位）。
        var reasoningEffort: String = "",
    ) {
        fun toJson() = JSONObject()
            .put("name", name).put("provider", provider).put("modelId", modelId)
            .put("apiKey", apiKey).put("baseUrl", baseUrl)
            .put("apiType", apiType).put("enabled", enabled)
            .put("target", target).put("current", current)
            .put("contextLength", contextLength).put("maxOutputLength", maxOutputLength)
            .put("supportsToolCall", supportsToolCall).put("supportsReasoning", supportsReasoning)
            .put("supportsImage", supportsImage).put("supportsTemperature", supportsTemperature)
            .put("reasoningEffort", reasoningEffort)

        companion object {
            fun fromJson(o: JSONObject) = Model(
                name = fixLegacyFetchedModelName(o.optString("name", "")),
                provider = o.optString("provider", ""),
                modelId = o.optString("modelId", ""),
                apiKey = o.optString("apiKey", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiType = o.optString("apiType", ApiType.CHAT),
                enabled = o.optBoolean("enabled", true),
                target = o.optString("target", "mimo"),
                current = o.optBoolean("current", false),
                contextLength = o.optInt("contextLength", 0),
                maxOutputLength = o.optInt("maxOutputLength", 0),
                supportsToolCall = o.optBoolean("supportsToolCall", true),
                supportsReasoning = o.optBoolean("supportsReasoning", true),
                supportsImage = o.optBoolean("supportsImage", true),
                supportsTemperature = o.optBoolean("supportsTemperature", true),
                reasoningEffort = o.optString("reasoningEffort", ""),
            )

            /**
             * 修复历史 bug 存下的坏模型名：此前 addModel 用字符串模板 "${provider}/$fm.id"，
             * Kotlin 模板在 "." 处截断，实际存成 "provider/FetchedModel(id=xxx, ...).id"。
             * 读时提取其中的模型 id，重建为 "provider/id"（仅此精确形态，不误伤正常名）。
             */
            private fun fixLegacyFetchedModelName(raw: String): String {
                val m = Regex("""^(.+)/FetchedModel\(id=([^,]+),.*\)\.id$""").matchEntire(raw)
                return m?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: raw
            }
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

    /**
     * 查询某模型实际被声明在哪些 CLI（读两个 CLI 的真实配置文件，而非过时的 target 单字段）。
     * 判定依据：
     *  - opencode：opencode.json 的 provider.<prov>.models.<modelId> 是否存在
     *  - mimo：mimocode.jsonc（JSONC）的 provider.<prov>.models.<modelId> 是否存在
     * 返回集合，取值 "mimo" / "opencode"；都不在则空集（模型还没同步进任何 CLI）。
     * provider 用 effProvider 兜底，和写入时一致，保证能对上。
     */
    fun clisContaining(ctx: Context, m: Model, all: List<Model> = listModels(ctx)): Set<String> {
        val out = mutableSetOf<String>()
        val prov = effProvider(m, all)
        val mid = m.modelId
        // opencode.json（纯 JSON）——V2 格式顶层是 providers（复数），读它判定归属
        runCatching {
            val oc = opencodeCfg(ctx)
            if (oc.isFile) {
                val pObj = JSONObject(oc.readText()).optJSONObject("providers")?.optJSONObject(prov)
                if (pObj?.optJSONObject("models")?.has(mid) == true) out.add("opencode")
            }
        }
        // mimocode.jsonc（JSONC）——必须用现成的 Jsonc.parse()，不能自己写正则去注释：
        // 自写 Regex("//.*") 会把 baseURL 里的 https:// 一起砍掉，JSON 损坏、解析失败、mimo 判定丢失。
        runCatching {
            val mc = mimoConfig(ctx)
            if (mc.isFile) {
                val parsed = Jsonc.parse(mc.readText())
                val pObj = parsed?.obj?.optJSONObject("provider")?.optJSONObject(prov)
                if (pObj?.optJSONObject("models")?.has(mid) == true) out.add("mimo")
            }
        }
        return out
    }

    private fun saveModels(ctx: Context, list: List<Model>) {
        val file = modelsFile(ctx).apply { parentFile?.mkdirs() }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        atomicWrite(file, jsonPretty(arr))
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
        synchronized(configLock) {
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
                    runCatching { atomicWrite(cfg, jsonPretty(parsed.obj) + "\n") }
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
                        atomicWrite(auth, jsonPretty(obj))
                    }
                }
            }
        } else {
            // opencode.json：删 providers.<id>（V2 格式，纯 JSON）
            val file = opencodeCfg(ctx)
            if (file.isFile) {
                runCatching {
                    val obj = JSONObject(file.readText())
                    val providers = obj.optJSONObject("providers") ?: return@runCatching
                    if (providers.has(provider)) {
                        backupFile(file)
                        providers.remove(provider)
                        if (providers.length() == 0) obj.remove("providers")
                        atomicWrite(file, jsonPretty(obj))
                    }
                }
            }
        }
        }
    }

    /** 删改 CLI 配置前备份原文件为 <name>.codetool.bak（覆盖旧备份）。 */
    private fun backupFile(f: File) {
        runCatching { f.copyTo(File(f.parentFile, f.name + ".codetool.bak"), overwrite = true) }
    }

    /** 原子写：先写同目录临时文件再 rename（同文件系统原子替换），失败回退直接写。
     *  避免写一半崩溃/断电留下截断 JSON，触发「损坏→空对象覆盖」的链式丢数据。 */
    private fun atomicWrite(file: File, content: String) {
        val dir = file.parentFile
        if (dir != null) {
            val tmp = File(dir, file.name + ".codetool.tmp")
            try {
                tmp.writeText(content)
                if (tmp.renameTo(file)) return
            } catch (_: Exception) { }
            runCatching { tmp.delete() }
        }
        file.writeText(content)
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
        // 默认模型两个 CLI 一起切：同步已把全部启用模型写进 mimo 和 opencode，
        // 切默认（recent[0] / model）就该两个 CLI 同时生效，否则只动一个 CLI
        // （历史上 target 恒为 mimo 时，定时切换从不动 opencode）。target 只保留作内部状态标记。
        val enabled = list.filter { it.enabled }
        val okMimo = writeMimo(ctx, m, enabled)
        val okOc = writeOpencode(ctx, m, enabled)
        // M5：两个 CLI 都要成功才返回 true——否则定时切换在 opencode 写失败时误报「同时生效」。
        return okMimo && okOc
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
                val hash = oc.indexOf('#')
                val noVar = if (hash > 0) oc.substring(0, hash) else oc   // 顶层 model 可能带 #档位（variant）
                val slash = noVar.indexOf('/')
                val p = if (slash > 0) noVar.substring(0, slash) else ""
                val mid = if (slash > 0) noVar.substring(slash + 1) else noVar
                // V2：连接信息在 providers.<p>.settings（baseURL/apiKey），不是 V1 的 env/provider.options
                val pSettings = cfg.optJSONObject("providers")?.optJSONObject(p)?.optJSONObject("settings")
                val key = pSettings?.optString("apiKey", "").orEmpty()
                list.add(Model(
                    name = noVar, provider = p, modelId = mid, apiKey = key,
                    baseUrl = pSettings?.optString("baseURL", "").orEmpty(),
                    target = "opencode", current = true,
                ))
                added++
            }
        }
        if (added > 0) saveModels(ctx, list)
        return added
    }

    // ---------------- 写入各 CLI 原生配置（含 Base URL） ----------------

    /**
     * 有效 provider：provider 字段为空时兜底推导，绝不让模型因 provider 空而被整条丢弃、
     * 也避免被错分到一个没有 baseURL/key 的孤儿 provider 下。
     * 推导顺序：provider → 同 baseUrl 的已声明 provider（同 API 地址=同服务商，最可靠）→
     * name/modelId 的 '/' 前缀 → "custom"。
     * 背景：自定义模型（如 sensenova-6.8-flash）常只填 name/baseUrl 没填 provider；它和
     * sensenova-u1.5-lite 用同一 baseURL，应继承 sensenova 而非单开 custom（custom 没 key 连不上）。
     * [all] 传入该 target 全部启用模型，用于 baseUrl 继承匹配。
     */
    private fun effProvider(m: Model, all: List<Model> = listOf(m)): String =
        m.provider.ifEmpty {
            all.firstOrNull {
                it.provider.isNotEmpty() && it.baseUrl.isNotEmpty() && it.baseUrl == m.baseUrl
            }?.provider
                ?: m.name.substringBefore('/').takeIf { it.isNotEmpty() && m.name.contains('/') }
                ?: m.modelId.substringBefore('/').takeIf { it.isNotEmpty() && m.modelId.contains('/') }
                ?: "custom"
        }

    /** 按有效 provider 分组（provider 空时用 effProvider 兜底），保证每条启用模型都被声明。 */
    private fun groupByProvider(enabled: List<Model>): Map<String, List<Model>> {
        val out = LinkedHashMap<String, MutableList<Model>>()
        for (m in enabled) {
            val key = effProvider(m, enabled)
            out.getOrPut(key) { mutableListOf() }.add(m)
        }
        return out
    }

    /** 判断某 opencode providers.<id> 是否为 codetool 生成（清理旧残留时只动这些，不动内置/手工 provider）。
     *  判据：package=@opencode/ai/providers/openai-compatible 且 env 首项以 _API_KEY 结尾（envVarFor 的形态）。 */
    private fun isCodetoolProvider(po: JSONObject): Boolean {
        val pkg = po.optString("package", "")
        if (pkg != "@opencode/ai/providers/openai-compatible") return false
        val env = po.optJSONArray("env") ?: return false
        return env.length() > 0 && env.optString(0).endsWith("_API_KEY")
    }

    /** 格式化上下文长度为可读字符串（262144 → "262K"，1048576 → "1M"），0 返回空。 */
    fun formatContextLength(tokens: Int): String = when {
        tokens >= 1000000 -> "${tokens / 1000000}M"
        tokens >= 1000 -> "${tokens / 1000}K"
        tokens > 0 -> tokens.toString()
        else -> ""
    }

    /**
     * 写 mimo：auth.json（provider->{type,key,baseURL}）+ model.json（recent[0]=默认）+ mimocode.jsonc（声明全部启用模型）。
     * current = 默认模型（recent[0]）；enabled = 该 target 下所有已启用模型，全部声明进
     * provider.<id>.models.<modelId>，让 mimo 一次认得所有配置的模型（不止 current）。
     * 这样「配好模型 + 同步」即可被识别，不必逐个「设为当前」。
     * 注意：分组用 groupByProvider（provider 空时按 baseUrl 继承），保证每条启用模型都被声明。
     */
    fun writeMimo(ctx: Context, current: Model, enabled: List<Model> = listOf(current)): Boolean = synchronized(configLock) {
        runCatching {
        val m = current
        val mProv = effProvider(m, enabled)
        val auth = mimoAuth(ctx).apply { parentFile?.mkdirs() }
        // H1：auth.json 存在但解析失败（损坏/半写/编码错）时返回 false 不写盘，
        // 避免空对象覆盖导致其它 provider 的 key 被静默清空。
        val obj = if (auth.isFile) runCatching { JSONObject(auth.readText()) }.getOrNull() ?: return@runCatching false
        else JSONObject()
        // 所有启用模型的 provider 都写 key（同一 provider 取第一个非空 key）；分组用 groupByProvider 兜底
        val byProvider = groupByProvider(enabled)
        byProvider.forEach { (prov, models) ->
            val key = models.firstOrNull { it.apiKey.isNotEmpty() }?.apiKey ?: models.first().apiKey
            val entry = obj.optJSONObject(prov) ?: JSONObject()
            entry.put("type", "api")
            entry.put("key", key)
            val bu = models.firstOrNull { it.baseUrl.isNotEmpty() }?.baseUrl.orEmpty()
            if (bu.isNotEmpty()) entry.put("baseURL", bu)
            obj.put(prov, entry)
        }
        atomicWrite(auth, jsonPretty(obj))

        val mf = mimoModel(ctx).apply { parentFile?.mkdirs() }
        // H1：model.json 存在但解析失败时返回 false 不写盘，避免清空 favorite/variant。
        val mo = if (mf.isFile) runCatching { JSONObject(mf.readText()) }.getOrNull() ?: return@runCatching false
        else JSONObject()
        val recent = JSONArray()
        recent.put(JSONObject().put("providerID", effProvider(m, enabled)).put("modelID", m.modelId))
        mo.put("recent", recent)
        mo.put("favorite", mo.optJSONArray("favorite") ?: JSONArray())
        mo.put("variant", mo.optJSONObject("variant") ?: JSONObject())
        atomicWrite(mf, jsonPretty(mo))

        // 关键：mimo 列模型时按 provider.<id>.options.baseURL 取 API 地址（和 opencode
        // 的 opencode.json 同构）。只写 auth.json 的 baseURL 不够——实测 mimo 认得 provider
        // 却拿不到地址，模型列表为空。这里把 baseURL 也写进 mimocode.jsonc（JSONC 安全写）。
        // 并把所有启用模型都声明进 provider.<id>.models.<modelId>，让 mimo 绕过静态 registry
        // 直接认这些模型（自定义模型如 sensenova-6.8-flash 不在 registry 也能显示）。
        if (byProvider.isNotEmpty()) {
            val cfg = mimoConfig(ctx).apply { parentFile?.mkdirs() }
            val raw = if (cfg.isFile) runCatching { cfg.readText() }.getOrNull() else null
            // H1：mimocode.jsonc 存在但读取失败/内容无效（null/空白）→ 不写盘，
            // 避免回退空对象覆盖，导致用户 JSONC 注释与其它字段（mcp/agent 等）被静默清空。
            if (raw.isNullOrBlank() && cfg.isFile) return@runCatching false
            val parsed = raw?.let { Jsonc.parse(it) } ?: Jsonc.Parsed(JSONObject(), false)
            if (parsed != null) {
                val obj = parsed.obj
                val providers = obj.optJSONObject("provider") ?: JSONObject().also { obj.put("provider", it) }
                byProvider.forEach { (prov, models) ->
                    val p = providers.optJSONObject(prov) ?: JSONObject().also { providers.put(prov, it) }
                    val options = p.optJSONObject("options") ?: JSONObject().also { p.put("options", it) }
                    val bu = models.firstOrNull { it.baseUrl.isNotEmpty() }?.baseUrl.orEmpty()
                    if (bu.isNotEmpty()) options.put("baseURL", bu)
                    val modelsMap = p.optJSONObject("models") ?: JSONObject()
                    models.forEach { mm ->
                        val decl = JSONObject()
                        decl.put("id", mm.modelId)
                        decl.put("name", mm.name.substringAfter('/').ifEmpty { mm.modelId })
                        decl.put("reasoning", mm.supportsReasoning)
                        // 思考强度档位：全部模型（含小米 MiMo，实测支持档位）写全量档位（mimo 据此建可切档位）。
                        // 用户选中档位经 model.json 的 variant 状态（见下），TUI ctrl+t / /variants 可切。
                        if (mm.supportsReasoning) {
                            val variants = JSONObject()
                            ALL_EFFORTS.forEach { variants.put(it, JSONObject()) }
                            decl.put("variants", variants)
                        }
                        decl.put("tool_call", mm.supportsToolCall)
                        decl.put("temperature", mm.supportsTemperature)
                        decl.put("attachment", mm.supportsImage)
                        decl.put("limit", JSONObject().put(
                            "context", if (mm.contextLength > 0) mm.contextLength else 262144
                        ).put(
                            "output", if (mm.maxOutputLength > 0) mm.maxOutputLength else 65536
                        ))
                        // 不硬编码 cost=0：与 opencode 一致，假定价数据无意义（mimo 靠 registry 而非 cost 识别模型）。
                        modelsMap.put(mm.modelId, decl)
                    }
                    p.put("models", modelsMap)
                    providers.put(prov, p)
                }
                obj.put("provider", providers)
                // 原文件是 JSONC（含注释/尾逗号）→ 先备份（与 McpManager 对齐）；标准 JSON
                // 序列化无法保留注释，备份至少可回退，避免用户注释被静默剥离。
                if (parsed.needsRewrite && cfg.isFile) backupFile(cfg)
                atomicWrite(cfg, jsonPretty(obj) + "\n")
            }
        }
        true
    }.getOrDefault(false)
    }

    /**
     * 写 opencode.json（opencode V2 格式，V1 的 provider/npm/options 已 deprecated）：
     * providers.<id> = { name, package, settings.{baseURL,apiKey}, models.<modelId> = { name, capabilities, limit } }
     * - package：@opencode/ai/providers/openai-compatible（OpenAI 兼容协议），V1 叫 npm、已废弃。
     * - name：provider 显示名（BUILTIN_PROVIDERS.label，自定义兜底 id）。
     * - settings：baseURL + apiKey 都放这里（V1 放 options、顶层 env，均已废弃）。
     * - models.<modelId>：带 capabilities（tools/reasoning/temperature/input/output）+ limit（context/output），
     *   这是 opencode 模型列表区分能力/分类的依据——只写 name 的话列表里每行只有 ID、看不出差异。
     * 顶层 model = current 默认（providerId/modelId）。
     */
    fun writeOpencode(ctx: Context, current: Model, enabled: List<Model> = listOf(current)): Boolean = synchronized(configLock) {
        runCatching {
        val m = current
        val mProv = effProvider(m, enabled)
        val file = opencodeCfg(ctx).apply { parentFile?.mkdirs() }
        // H1：opencode.json 存在但解析失败时返回 false 不写盘，避免清空 mcp/agent/skills/theme 等字段。
        val obj = if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@runCatching false
        else JSONObject()
        // 文件被误删/重建后会丢非模型段（如 mcp）。MCP 由 McpManager 统一管理、两 CLI 同步写，
        // 这里在 opencode 配置缺少 mcp 段时从 mimo 配置继承，保证同步后 MCP 不丢（含用户自建服务）。
        if (!obj.has("mcp")) {
            runCatching {
                val mimoFile = f(ctx, "root/.config/mimocode/mimocode.jsonc")
                val mimoMcp = if (mimoFile.isFile) Jsonc.parse(mimoFile.readText())?.obj?.optJSONObject("mcp") else null
                if (mimoMcp != null && mimoMcp.length() > 0) obj.put("mcp", JSONObject(mimoMcp.toString()))
            }
        }
        // 顶层 model = current 默认。若当前模型选了思考强度且支持档位，
        // 经 variant 表达为 "provider/id#档位"（opencode 用 capabilities.reasoning.effort 建的 variant）；
        // 默认（未选档位）不带 #，用模型自身默认档位。
        val modelRef = if (m.reasoningEffort.isNotEmpty() && m.supportsReasoning) {
            "${mProv}/${m.modelId}#${m.reasoningEffort}"
        } else {
            "${mProv}/${m.modelId}"
        }
        obj.put("model", modelRef)
        val byProvider = groupByProvider(enabled)
        if (byProvider.isNotEmpty()) {
            // V2 顶层键是 providers（复数）；顺手清掉 V1 残留的 provider/options/env，避免新旧混用。
            val providers = obj.optJSONObject("providers") ?: JSONObject()
            obj.remove("provider")
            // 清理旧残留：codetool 管理的 provider（本类生成的、带 env=<ID>_API_KEY + openai-compatible package）
            // 不在本次启用清单（byProvider）的，整段删除；在清单里的，其 models 里不在启用列表的旧模型也删。
            // 避免旧版本生成的无 effort 声明（如 xfyun/spark-x2.6）残留，也避免用户删除模型后配置不同步。
            // 非 codetool 管理的（内置 opencode provider、用户手工加的其它 package）一律不动，防误删。
            val codetoolProvs = ArrayList<String>()
            providers.keys().forEach { codetoolProvs.add(it) }
            for (pid in codetoolProvs) {
                val po = providers.optJSONObject(pid) ?: continue
                if (!isCodetoolProvider(po)) continue
                if (pid !in byProvider.keys) {
                    providers.remove(pid)
                } else {
                    val keepModels = byProvider[pid]?.map { it.modelId }?.toSet() ?: emptySet()
                    val mo = po.optJSONObject("models") ?: continue
                    val mids = ArrayList<String>()
                    mo.keys().forEach { mids.add(it) }
                    for (mid in mids) {
                        if (mid !in keepModels) mo.remove(mid)
                    }
                }
            }
            byProvider.forEach { (prov, models) ->
                val pObj = JSONObject()
                pObj.put("name", providerById(ctx, prov)?.label ?: prov)
                // package：opencode V2 靠它选运行时适配器（OpenAI 兼容协议）
                pObj.put("package", "@opencode/ai/providers/openai-compatible")
                // env：声明该 provider 的 API Key 环境变量名。opencode 只有识别到「环境连接」
                //（provider.env 声明 + 服务进程环境变量已设置）才把 provider 当已连接，
                // 模型选择器才会按服务商分组；否则 connected()=false，所有模型平铺混在 free 里。
                pObj.put("env", JSONArray().put(envVarFor(prov)))
                // settings：baseURL + apiKey（同一 provider 取第一个非空）
                val settings = JSONObject()
                val bu = models.firstOrNull { it.baseUrl.isNotEmpty() }?.baseUrl.orEmpty()
                if (bu.isNotEmpty()) settings.put("baseURL", bu)
                val key = models.firstOrNull { it.apiKey.isNotEmpty() }?.apiKey.orEmpty()
                if (key.isNotEmpty()) settings.put("apiKey", key)
                pObj.put("settings", settings)
                val modelsMap = JSONObject()
                models.forEach { mm ->
                    modelsMap.put(mm.modelId, buildOpencodeModelDecl(mm))
                }
                pObj.put("models", modelsMap)
                providers.put(prov, pObj)
            }
            obj.put("providers", providers)
        }
        atomicWrite(file, jsonPretty(obj))
        true
    }.getOrDefault(false)
    }

    /**
     * 构造单个模型的 V2 声明：name + capabilities + limit。
     * capabilities 把清单里已知的模型能力（工具/推理/温度/图片）映射成 opencode 的分类元数据，
     * 让 /models 列表能显示每个模型的能力差异，而不是只列一列 ID。
     */
    private fun buildOpencodeModelDecl(mm: Model): JSONObject {
        val decl = JSONObject()
        decl.put("name", mm.name.substringAfter('/').ifEmpty { mm.modelId })
        val caps = JSONObject()
        caps.put("tools", mm.supportsToolCall)
        caps.put("temperature", mm.supportsTemperature)
        // reasoning：v2 是对象 { supported: bool }，不是裸 boolean。
        // 思考强度（schema 确认）：capabilities.reasoning.effort = 模型支持的档位集合，
        // opencode 据此自动建 #variant（openai-compatible 协议每个档位一个 variant，settings.reasoningEffort）。
        // 用户选中档位不写这里——它经顶层 model: "provider/id#档位" 选中对应 variant（见 writeOpencode）。
        val reasoning = JSONObject().put("supported", mm.supportsReasoning)
        if (mm.supportsReasoning) {
            val arr = JSONArray()
            ALL_EFFORTS.forEach { arr.put(it) }
            reasoning.put("effort", arr)
        }
        caps.put("reasoning", reasoning)
        // input：媒体类型数组；支持图片则含 image，恒含 text
        val input = JSONArray().put("text")
        if (mm.supportsImage) input.put("image")
        caps.put("input", input)
        caps.put("output", JSONArray().put("text"))
        decl.put("capabilities", caps)
        // limit：上下文 / 输出上限（缺省给常用兜底值，0 会让 opencode 当未知）
        val limit = JSONObject()
        limit.put("context", if (mm.contextLength > 0) mm.contextLength else 262144)
        limit.put("output", if (mm.maxOutputLength > 0) mm.maxOutputLength else 65536)
        decl.put("limit", limit)
        return decl
    }

    /**
     * opencode 环境连接用的 API Key 变量名：<PROVIDER_ID>_API_KEY（大写，非字母数字转下划线）。
     * 与 opencode 官方目录 provider 的 env 命名约定一致（如 sensenova → SENSENOVA_API_KEY）。
     */
    private fun envVarFor(provider: String): String =
        provider.replace(Regex("[^A-Za-z0-9]"), "_").uppercase() + "_API_KEY"

    /**
     * 把各 provider 的 API Key 写入 opencode 后台服务进程的环境变量（opencode service set env），
     * 让 opencode 把 provider 识别为「环境连接」。这是模型选择器按服务商分组的必要条件：
     * opencode 的 connected() 只看认证集成连接，配置里 settings.apiKey 不产生连接。
     * 变量名 = envVarFor(provider)；无 key 的 provider 清掉旧变量。best-effort，失败不阻塞同步。
     * 注意：每个 service set env 会重启 opencode 后台服务，多个 provider 连跑会连续重启（终态正确）。
     */
    fun applyOpencodeEnv(ctx: Context, enabled: List<Model>): Boolean = runCatching {
        val app = ctx.applicationContext
        val root = RootfsManager.get(app).containerRoot
        val runtime = TawcrootRuntime.get(app)
        val oc = opencodeBins(ctx).firstOrNull { it.isFile }?.absolutePath ?: return false
        if (!root.isDirectory || !File(runtime.binaryPath).exists()) return false
        val byProvider = groupByProvider(enabled)
        val sb = StringBuilder()
        byProvider.forEach { (prov, models) ->
            val varName = envVarFor(prov)
            val key = models.firstOrNull { it.apiKey.isNotEmpty() }?.apiKey.orEmpty()
            if (key.isNotEmpty()) sb.append("\"$oc\" service set env $varName '${key.replace("'", "'\\''")}'\n")
            else sb.append("\"$oc\" service unset env $varName\n")
        }
        if (sb.isEmpty()) return true
        val p = runtime.exec(root, "/bin/sh", "-c", sb.toString())
        p.inputStream.bufferedReader().readText()
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroy()
            return false
        }
        p.exitValue() == 0
    }.getOrDefault(false)

    /**
     * 同步到 CLI：把每个目标（mimo / opencode）当前模型重新写入 CLI 原生配置。
     * 用途：改了 baseURL / 修了写入逻辑 / 手动改过 JSON 后，一键让 CLI 配置和清单对齐。
     * 可指定只同步某些目标（mimo / opencode），默认两个都同步。
     * 返回 (成功目标数, 明细)。
     */
    fun syncAll(ctx: Context, targets: List<String> = listOf("mimo", "opencode")): Pair<Int, List<String>> {
        val details = mutableListOf<String>()
        var okCount = 0
        val allEnabled = listModels(ctx).filter { it.enabled }
        for (target in targets) {
            // 当前模型：优先该 target 标记为 current 的；没有就用第一个启用模型兜底。
            // （同步写入的是「全部启用模型」，target 只决定哪个 CLI 把哪个模型当默认，
            //   不再按 target 切分——否则把所有模型目标都留在 mimo 时，同步 opencode 会一个都写不进去。）
            var cur = listModels(ctx).find { it.target == target && it.current && it.enabled }
                ?: allEnabled.firstOrNull()
            if (cur == null) {
                details.add("$target：没有已启用模型，跳过")
                continue
            }
            // 关键：写入该 CLI 的是「全部启用模型」（让 CLI 一次认得全部），cur 作默认（recent[0]）。
            val ok = if (target == "mimo") {
                writeMimo(ctx, cur, allEnabled)
            } else {
                val okWrite = writeOpencode(ctx, cur, allEnabled)
                // 同步 opencode 后建立「环境连接」：opencode 模型选择器只有 connected()=true 才按服务商分组，
                // 否则所有模型平铺混在 free 里。settings.apiKey 不产生连接，必须 service set env。
                if (okWrite) {
                    val envOk = applyOpencodeEnv(ctx, allEnabled)
                    android.util.Log.w("AiSync", "applyOpencodeEnv ok=$envOk providers=${groupByProvider(allEnabled).keys}")
                }
                okWrite
            }
            android.util.Log.w("AiSync", "syncAll target=$target ok=$ok models=${allEnabled.size} cur=${cur.name}")
            details.add("$target ${allEnabled.size} 个模型${if (ok) " ✓" else " ✗"}")
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
        atomicWrite(file, content)
        true
    }.getOrDefault(false)

    data class FetchedModel(
        val id: String,
        val contextLength: Int = 0,
        val maxOutputLength: Int = 0,
        val supportsToolCall: Boolean = true,
        val supportsReasoning: Boolean = true,
        val supportsImage: Boolean = true,
        val supportsTemperature: Boolean = true,
    )

    data class FetchResult(val models: List<FetchedModel>, val error: String)

    /**
     * 拉取服务商支持的模型列表（Chatbox / Cherry Studio 同款流程）：
     * GET &lt;baseUrl&gt;/models，兼容 OpenAI 的 {"data":[{"id":...}]} 与直接数组 / 字符串数组。
     * 同时解析能力字段（有则用，无则兜底）：
     * - context_length / max_output_length → 上下文 / 输出长度
     * - supported_features → 工具调用、推理
     * - input_modalities → 图片理解
     * - supported_sampling_parameters → temperature
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
        val models = runCatching {
            val t = r.body.trim()
            val arr = if (t.startsWith("{")) {
                JSONObject(t).optJSONArray("data") ?: JSONArray()
            } else JSONArray(t)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i)
                when {
                    o != null -> parseFetchedModel(o)
                    else -> {
                        val id = arr.optString(i, "").takeIf { it.isNotEmpty() }
                        if (id != null) FetchedModel(id) else null
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (models.isEmpty()) return FetchResult(
            emptyList(), "已连接 (HTTP ${r.code})，但响应里没解析出模型 ID（服务商格式可能不兼容）"
        )
        return FetchResult(models, "")
    }

    /** 从 /models 响应的单个模型对象解析 ID + 能力字段（无字段则用兜底默认值）。 */
    private fun parseFetchedModel(o: JSONObject): FetchedModel? {
        val id = o.optString("id", "").ifEmpty { o.optString("name", "") }.takeIf { it.isNotEmpty() }
            ?: return null
        // 上下文/输出长度：各服务商字段名不统一，逐个兜底尝试（实测只有 context_length 命中时
        // 其余服务商拿到的都是 0，卡片永远显示「未知」）：
        //   context_length        —— 部分服务商（裸字段）
        //   max_model_len         —— vLLM / Ollama / 多数自托管
        //   context_window        —— Groq
        //   top_provider.context_length / max_completion_tokens —— OpenRouter（嵌套对象）
        fun pickInt(vararg paths: Pair<String, String>): Int {
            for ((obj, key) in paths) {
                val target = if (obj == ".") o else o.optJSONObject(obj) ?: continue
                val v = target.optInt(key, 0)
                if (v > 0) return v
            }
            return 0
        }
        val contextLength = pickInt(
            "." to "context_length",
            "." to "max_model_len",
            "." to "context_window",
            "top_provider" to "context_length",
        )
        val maxOutputLength = pickInt(
            "." to "max_output_length",
            "." to "max_completion_tokens",
            "." to "max_tokens",
            "top_provider" to "max_completion_tokens",
        )
        // supported_features 存在才覆盖默认值（无此字段的服务商保持 true 兜底）
        val sf = o.optJSONArray("supported_features")
        var toolCall = true
        var reasoning = true
        if (sf != null) {
            toolCall = hasString(sf, "tools")
            reasoning = hasString(sf, "reasoning")
        }
        // input_modalities 存在才判断图片理解
        val im = o.optJSONArray("input_modalities")
        var image = true
        if (im != null) image = hasString(im, "image")
        // supported_sampling_parameters 存在才判断 temperature
        val sp = o.optJSONArray("supported_sampling_parameters")
        var temperature = true
        if (sp != null) temperature = hasString(sp, "temperature")
        return FetchedModel(
            id = id,
            contextLength = contextLength,
            maxOutputLength = maxOutputLength,
            supportsToolCall = toolCall,
            supportsReasoning = reasoning,
            supportsImage = image,
            supportsTemperature = temperature,
        )
    }

    /** JSONArray 是否包含某字符串（大小写不敏感）。 */
    private fun hasString(arr: JSONArray, s: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.optString(i, "").equals(s, ignoreCase = true)) return true
        }
        return false
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
            // 低危：按 id 精确匹配模型列表（兼容裸数组或 {"data":[...]} 包装），
            // 避免 body.contains 撞上其它字段（如 "object":"...model..."）误报「在列表中」。
            val has = m.modelId.isNotEmpty() && listContainsId(models.body, m.modelId)
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

    /** 判断模型列表 JSON（裸数组或 {"data":[...]} 包装）是否含指定 id（精确匹配）。 */
    private fun listContainsId(body: String, id: String): Boolean = runCatching {
        val tryArr = runCatching { JSONArray(body) }.getOrNull()
        if (tryArr != null) {
            return@runCatching (0 until tryArr.length()).any { i ->
                tryArr.optJSONObject(i)?.optString("id") == id
            }
        }
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
        data != null && (0 until data.length()).any { i -> data.optJSONObject(i)?.optString("id") == id }
    }.getOrDefault(false)

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
