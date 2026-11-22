package com.codetool.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiModelManager
import com.codetool.app.ai.ModelScheduleManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 模型管理（多模型）：实时读容器里的模型清单，列出全部模型，
 * 支持新增 / 编辑 / 删除 / 设为当前 / 从 CLI 导入 / 测试连接 / 自定义服务商。
 */
class AiModelsFragment : Fragment() {

    private lateinit var statusMimo: MaterialTextView
    private lateinit var statusOc: MaterialTextView
    private lateinit var modelList: LinearLayout

    // 定时任务入口摘要（完整设置已移到独立「定时任务」页）
    private lateinit var tvScheduleSummary: MaterialTextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_ai_models, container, false)
        statusMimo = v.findViewById(R.id.aiStatusMimo)
        statusOc = v.findViewById(R.id.aiStatusOpencode)
        modelList = v.findViewById(R.id.aiModelList)

        v.findViewById<MaterialButton>(R.id.btnAiFixPath).setOnClickListener {
            // 走 runInlineScript：脚本写入临时文件再执行，终端不回显整段源码
            TerminalFragment.runInlineScript(
                requireContext(), "修复 PATH", AiModelManager.FIX_PATH_SCRIPT
            )
            toast("已写入 PATH（新开终端会话生效）")
        }
        v.findViewById<MaterialButton>(R.id.btnAiRefreshVersion).setOnClickListener { refreshVersions() }
        v.findViewById<MaterialButton>(R.id.btnAiAdd).setOnClickListener { showEditDialog(null) }
        v.findViewById<MaterialButton>(R.id.btnAiImport).setOnClickListener {
            val n = AiModelManager.importFromCli(requireContext())
            toast(if (n > 0) "已导入 $n 个现有模型" else "清单已有或无可导入配置")
            refresh()
        }
        v.findViewById<MaterialButton>(R.id.btnAiFetchModels).setOnClickListener { showFetchDialog() }
        v.findViewById<MaterialButton>(R.id.btnAiConfig).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), ConfigEditorActivity::class.java))
        }
        v.findViewById<MaterialButton>(R.id.btnAiSync).setOnClickListener { syncToCli() }

        // 定时任务入口：点卡片跳到「定时任务」页（完整设置在那）
        tvScheduleSummary = v.findViewById(R.id.tvScheduleSummary)
        v.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardScheduleEntry)
            .setOnClickListener {
                // ToolsFragment 是 MainActivity 的 ViewPager2 子页，AiModelsFragment 的
                // parentFragment 为 null（子 tab 由 Activity 的 fragmentManager 承载），
                // 从 Activity 的 fragmentManager 里找到 ToolsFragment 再切子 tab。
                val tools = (requireActivity() as? MainActivity)?.supportFragmentManager
                    ?.fragments?.firstOrNull { it is ToolsFragment } as? ToolsFragment
                tools?.openTab(ToolsFragment.TAB_SCHEDULED)
            }
        return v
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (::tvScheduleSummary.isInitialized) renderScheduleSummary()
    }

    // ---------------- 定时任务入口摘要（完整设置已移到「定时任务」页） ----------------

    /** 刷新紧凑摘要：只读规则显示当前状态；点卡片跳「定时任务」页。 */
    private fun renderScheduleSummary() {
        val ctx = requireContext()
        val s = ModelScheduleManager.load(ctx)
        if (!s.enabled) {
            tvScheduleSummary.text = "AI 模型定时切换 · 未启用"
            return
        }
        val now = ModelScheduleManager.beijingNow()
        val slot = ModelScheduleManager.activeSlot(s, now)
        val state = when {
            slot == null -> "已启用（模型未选全）"
            slot.peak -> "高峰 → ${slot.model}"
            slot.holiday -> "法定节假日闲时 → ${slot.model}"
            slot.weekend -> "周末闲时 → ${slot.model}"
            else -> "闲时 → ${slot.model}"
        }
        val next = ModelScheduleManager.nextBoundaryMillis(s, now)
        val nextTxt = next?.let {
            val t = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = System.currentTimeMillis() + it
            }
            ModelScheduleManager.fmtMinutes(
                t.get(java.util.Calendar.HOUR_OF_DAY) * 60 + t.get(java.util.Calendar.MINUTE))
        } ?: "—"
        tvScheduleSummary.text = "AI 模型定时切换 · $state · 下次 $nextTxt"
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 在容器内跑一次 --version 写缓存，然后刷新状态（后台，不阻塞 UI）。 */
    private fun refreshVersions() {
        toast("正在探测版本…")
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AiModelManager.probeVersions(requireContext().applicationContext)
            }
            if (isAdded) refresh()
        }
    }

    /** 实时刷新：每次都重新读盘。 */
    private fun refresh() {
        val ctx = requireContext()
        val mimo = AiModelManager.mimoStatus(ctx)
        val oc = AiModelManager.opencodeStatus(ctx)
        statusMimo.text = "MiMo Code：${mimo.detail}"
        statusOc.text = "opencode：${oc.detail}"

        modelList.removeAllViews()
        val list = AiModelManager.listModels(ctx)
        if (list.isEmpty()) {
            modelList.addView(MaterialTextView(ctx).apply {
                text = "暂无模型。点「新增模型」添加，或「导入现有」从 CLI 配置读取。"
                setPadding(0, 8, 0, 8)
            })
            return
        }
        list.forEach { m ->
            // 规范：列表项用 item_*.xml + inflate，不在 Kotlin 里 addView 拼布局
            val row = layoutInflater.inflate(R.layout.item_ai_model, modelList, false)
            val name = row.findViewById<MaterialTextView>(R.id.modelName)
            val badge = row.findViewById<MaterialTextView>(R.id.modelBadge)
            val meta = row.findViewById<MaterialTextView>(R.id.modelMeta)
            val provider = row.findViewById<MaterialTextView>(R.id.modelProvider)
            val modelId = row.findViewById<MaterialTextView>(R.id.modelId)
            val baseUrl = row.findViewById<MaterialTextView>(R.id.modelBaseUrl)
            val key = row.findViewById<MaterialTextView>(R.id.modelKey)
            val contextLen = row.findViewById<MaterialTextView>(R.id.modelContext)
            val caps = row.findViewById<MaterialTextView>(R.id.modelCaps)
            val btnCurrent = row.findViewById<MaterialButton>(R.id.btnModelCurrent)
            val btnEdit = row.findViewById<MaterialButton>(R.id.btnModelEdit)
            val btnDelete = row.findViewById<MaterialButton>(R.id.btnModelDelete)
            val targetBadge = row.findViewById<MaterialTextView>(R.id.modelTarget)
            val targetBadge2 = row.findViewById<MaterialTextView>(R.id.modelTarget2)

            name.text = m.name
            // 状态用徽标放在标题右侧，颜色跟随主题，不混进标题文字
            when {
                !m.enabled -> {
                    badge.text = "已停用"
                    badge.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_off)
                    badge.setTextColor(MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
                    badge.visibility = View.VISIBLE
                }
                m.current -> {
                    badge.text = "当前"
                    badge.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_current)
                    badge.setTextColor(MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.GRAY))
                    badge.visibility = View.VISIBLE
                }
                else -> badge.visibility = View.GONE
            }
            // CLI 归属徽标：读两个 CLI 的真实配置文件，判断此模型实际被声明在哪些 CLI，
            // 有哪个亮哪个（两个都在就都亮）。不再用过时的 target 单字段，避免误读成「只在一个 CLI」。
            // 两个标签同色（secondaryContainer 底 + onSecondaryContainer 字），并列关系不再用颜色区分。
            val inClis = AiModelManager.clisContaining(ctx, m)
            val badgeText = MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.GRAY)
            val badgeBg = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_target_mimo)
            if ("mimo" in inClis) {
                targetBadge.text = "mimo"
                targetBadge.background = badgeBg
                targetBadge.setTextColor(badgeText)
                targetBadge.visibility = View.VISIBLE
            } else targetBadge.visibility = View.GONE
            if ("opencode" in inClis) {
                targetBadge2.text = "opencode"
                targetBadge2.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_target_oc)
                targetBadge2.setTextColor(badgeText)
                targetBadge2.visibility = View.VISIBLE
            } else targetBadge2.visibility = View.GONE
            meta.text = if (m.apiType == AiModelManager.ApiType.RESPONSES) "Responses API" else "Chat Completions API"
            provider.text = AiModelManager.providerLabel(ctx, m.provider)
            modelId.text = m.modelId
            baseUrl.text = m.baseUrl.ifEmpty { "未设置" }
            key.text = if (m.apiKey.isEmpty()) "未设置" else "••••" + m.apiKey.takeLast(4)
            contextLen.text = if (m.contextLength > 0) AiModelManager.formatContextLength(m.contextLength) else "未知"
            caps.text = buildCapabilityText(m)

            btnCurrent.isEnabled = m.enabled
            btnCurrent.setOnClickListener {
                if (!m.enabled) {
                    toast("该模型已停用，请先点「编辑」启用")
                    return@setOnClickListener
                }
                val ok = AiModelManager.setCurrent(ctx, m.name, m.target)
                toast(if (ok) "已设为 ${m.target} 当前模型" else "失败")
                refresh()
            }
            btnEdit.setOnClickListener { showEditDialog(m) }
            btnDelete.setOnClickListener {
                val bakNote = if (m.target == "mimo")
                    "（清理前备份 mimocode.jsonc、auth.json 为 .codetool.bak）"
                else
                    "（清理前备份 opencode.json 为 .codetool.bak）"
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("删除模型")
                    .setMessage("删除「${m.name}」？若这是「${m.provider}」在此目标下最后一个模型，会一并清理 CLI 配置里的 ${m.provider} 段$bakNote。")
                    .setPositiveButton("删除") { _, _ ->
                        AiModelManager.removeModel(ctx, m.name, m.target)
                        refresh()
                    }
                    .setNegativeButton("取消", null).show()
            }
            modelList.addView(row)
        }
    }

    /** 新增/编辑模型弹窗：服务商下拉 + API 地址 + 测试连接。 */
    private fun showEditDialog(existing: AiModelManager.Model?) {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_ai_model, null)
        val etName = v.findViewById<TextInputEditText>(R.id.etAiName)
        val spProvider = v.findViewById<Spinner>(R.id.spAiProvider)
        val btnDelProvider = v.findViewById<MaterialButton>(R.id.btnAiDeleteProvider)
        val etBaseUrl = v.findViewById<TextInputEditText>(R.id.etAiBaseUrl)
        val etModel = v.findViewById<MaterialAutoCompleteTextView>(R.id.etAiModel)
        val etKey = v.findViewById<TextInputEditText>(R.id.etAiKey)
        val targetBtn = v.findViewById<MaterialButton>(R.id.btnAiTarget)
        val spAiApiType = v.findViewById<Spinner>(R.id.spAiApiType)
        val swAiEnabled = v.findViewById<MaterialSwitch>(R.id.swAiEnabled)
        val testBtn = v.findViewById<MaterialButton>(R.id.btnAiTest)
        val testResult = v.findViewById<MaterialTextView>(R.id.tvAiTestResult)
        val btnFetchCaps = v.findViewById<MaterialButton>(R.id.btnAiFetchCaps)
        val tvCapsResult = v.findViewById<MaterialTextView>(R.id.tvAiCapsResult)
        val etContextLength = v.findViewById<TextInputEditText>(R.id.etAiContextLength)
        // 编辑已有模型时回填上下文长度（>0 才填，0 表示未知留空）
        existing?.let { ex ->
            if (ex.contextLength > 0) etContextLength.setText(ex.contextLength.toString())
        }

        // 编辑历史模型时，若其 provider 已不在列表（旧数据 / 自定义项被删），
        // 临时补一项，保证「添加后还能编辑修改」不会把 provider 悄悄改掉。
        fun buildProviders(): MutableList<AiModelManager.Provider> {
            val l = AiModelManager.allProviders(ctx).toMutableList()
            existing?.let { ex ->
                if (ex.provider.isNotEmpty() && l.none { it.id == ex.provider }) {
                    l.add(AiModelManager.Provider(
                        ex.provider, "${ex.provider}（原配置）", ex.baseUrl,
                        listOf(ex.modelId), builtin = true,
                    ))
                }
            }
            return l
        }
        var providers = buildProviders()
        fun labels(): List<String> = providers.map { it.label } + "＋ 新增自定义服务商（OpenAI 兼容）"
        val providerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels().toMutableList())
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProvider.adapter = providerAdapter

        val modelAdapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        etModel.setAdapter(modelAdapter)
        var currentProvider: AiModelManager.Provider? = null

        fun applyProvider(p: AiModelManager.Provider, overwriteUrl: Boolean) {
            currentProvider = p
            if (overwriteUrl) etBaseUrl.setText(p.baseUrl)
            modelAdapter.clear()
            modelAdapter.addAll(p.models)
            modelAdapter.notifyDataSetChanged()
            btnDelProvider.visibility = if (p.builtin) View.GONE else View.VISIBLE
        }

        existing?.let {
            etName.setText(it.name)
            etName.isEnabled = true   // 允许改名（保存时若改名会先删旧主键条目再存新名）
            etBaseUrl.setText(it.baseUrl)
            etModel.setText(it.modelId)
            etKey.setText(it.apiKey)
        }
        val apiTypeAdapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_item, AiModelManager.ApiType.labels.toMutableList()
        )
        apiTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAiApiType.adapter = apiTypeAdapter
        spAiApiType.setSelection(
            AiModelManager.ApiType.positionOf(existing?.apiType ?: AiModelManager.ApiType.CHAT), false
        )
        swAiEnabled.isChecked = existing?.enabled ?: true

        var target = existing?.target ?: "mimo"
        // 「获取能力」拉到的最新能力值；保存时 readForm() 优先用它，避免旧值覆盖新能力。
        var fetchedCaps: AiModelManager.Model? = null
        targetBtn.text = "目标：$target（点击切换）"
        targetBtn.setOnClickListener {
            target = if (target == "mimo") "opencode" else "mimo"
            targetBtn.text = "目标：$target（点击切换）"
        }

        fun readForm() = AiModelManager.Model(
            name = etName.text?.toString()?.trim().orEmpty(),
            provider = currentProvider?.id.orEmpty(),
            modelId = etModel.text?.toString()?.trim().orEmpty(),
            apiKey = etKey.text?.toString()?.trim().orEmpty(),
            baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty(),
            apiType = AiModelManager.ApiType.fromPosition(spAiApiType.selectedItemPosition),
            enabled = swAiEnabled.isChecked,
            target = target,
            current = existing?.current ?: false,
            // 能力字段：优先用「获取能力」刚拉到的值（fetchedCaps），避免保存时用旧值覆盖掉新能力。
            // 上下文长度：手填框（etContextLength）优先——部分服务商（如 xfyun）/models 不返回该字段，
            // 用户手填后以此为准；留空则用拉到的值 / 旧值。
            contextLength = etContextLength.text?.toString()?.trim()?.toIntOrNull()
                ?: fetchedCaps?.contextLength ?: existing?.contextLength ?: 0,
            maxOutputLength = fetchedCaps?.maxOutputLength ?: existing?.maxOutputLength ?: 0,
            supportsToolCall = fetchedCaps?.supportsToolCall ?: existing?.supportsToolCall ?: true,
            supportsReasoning = fetchedCaps?.supportsReasoning ?: existing?.supportsReasoning ?: true,
            supportsImage = fetchedCaps?.supportsImage ?: existing?.supportsImage ?: true,
            supportsTemperature = fetchedCaps?.supportsTemperature ?: existing?.supportsTemperature ?: true,
        )

        var lastPos = providers.indexOfFirst { it.id == existing?.provider.orEmpty() }.coerceAtLeast(0)
        spProvider.setSelection(lastPos, false)
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == providers.size) {
                    // 哨兵项：新增自定义服务商（OpenAI 兼容）
                    showAddProviderDialog { newP ->
                        providers = buildProviders()
                        providerAdapter.clear()
                        providerAdapter.addAll(labels())
                        providerAdapter.notifyDataSetChanged()
                        val newPos = providers.indexOfFirst { it.id == newP.id }.coerceAtLeast(0)
                        lastPos = newPos
                        spProvider.setSelection(newPos)
                        applyProvider(newP, overwriteUrl = true)
                    }
                    spProvider.post { spProvider.setSelection(lastPos.coerceAtMost(providers.size - 1)) }
                    return
                }
                lastPos = pos
                applyProvider(providers[pos], overwriteUrl = existing == null)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (providers.isNotEmpty()) {
            applyProvider(providers[lastPos], overwriteUrl = existing == null)
            // 编辑已有模型时保留其原 Base URL，不被预置默认值覆盖
            if (existing != null) etBaseUrl.setText(existing.baseUrl)
        }

        btnDelProvider.setOnClickListener {
            val p = currentProvider ?: return@setOnClickListener
            MaterialAlertDialogBuilder(ctx)
                .setTitle("删除服务商")
                .setMessage("删除「${p.label}」？已保存的模型配置不受影响。")
                .setPositiveButton("删除") { _, _ ->
                    AiModelManager.removeCustomProvider(ctx, p.id)
                    providers = AiModelManager.allProviders(ctx).toMutableList()
                    providerAdapter.clear()
                    providerAdapter.addAll(labels())
                    providerAdapter.notifyDataSetChanged()
                    lastPos = 0
                    spProvider.setSelection(0)
                    toast("已删除服务商")
                }
                .setNegativeButton("取消", null).show()
        }

        testBtn.setOnClickListener {
            val m = readForm()
            if (m.baseUrl.isBlank()) {
                testResult.text = "✗ 请先填写 API 地址（Base URL）"
                return@setOnClickListener
            }
            testResult.text = "测试中…（连接超时 8s）"
            testBtn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { AiModelManager.testModel(m) }
                if (isAdded) {
                    testResult.text = r
                    testBtn.isEnabled = true
                }
            }
        }

        btnFetchCaps.setOnClickListener {
            val m = readForm()
            if (m.baseUrl.isBlank() || m.apiKey.isBlank()) {
                tvCapsResult.text = "✗ 请先填写 API 地址与 Key"
                return@setOnClickListener
            }
            if (existing == null) {
                tvCapsResult.text = "✗ 新模型请先保存，或用「从服务商获取模型列表」添加（含能力信息）"
                return@setOnClickListener
            }
            tvCapsResult.text = "获取中…（连接超时 8s）"
            btnFetchCaps.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { AiModelManager.fetchModels(m) }
                if (!isAdded) return@launch
                btnFetchCaps.isEnabled = true
                if (res.models.isEmpty()) {
                    tvCapsResult.text = "✗ ${res.error}"
                    return@launch
                }
                val matched = res.models.firstOrNull { it.id == m.modelId }
                if (matched == null) {
                    tvCapsResult.text = "✓ 连接成功，但列表中未找到模型 ${m.modelId}"
                    return@launch
                }
                val caps = m.copy(
                    contextLength = matched.contextLength,
                    maxOutputLength = matched.maxOutputLength,
                    supportsToolCall = matched.supportsToolCall,
                    supportsReasoning = matched.supportsReasoning,
                    supportsImage = matched.supportsImage,
                    supportsTemperature = matched.supportsTemperature,
                )
                AiModelManager.upsertModel(ctx, caps)
                fetchedCaps = caps   // 缓存，保存时 readForm() 优先用它，避免旧值回退
                val ctxLen = AiModelManager.formatContextLength(matched.contextLength)
                tvCapsResult.text = "✓ 已更新能力：上下文 ${if (ctxLen.isEmpty()) "未知" else ctxLen} · " +
                    "工具${if (matched.supportsToolCall) "✓" else "✗"} · " +
                    "推理${if (matched.supportsReasoning) "✓" else "✗"} · " +
                    "图片${if (matched.supportsImage) "✓" else "✗"}"
                refresh()
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "新增模型" else "编辑模型")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val m = readForm()
                if (m.name.isEmpty() || m.modelId.isEmpty()) {
                    toast("名称与模型 ID 必填")
                    return@setPositiveButton
                }
                // 改名：name+target 是主键，改了名要先删旧条目再存新名，并把 current 标记带过去
                if (existing != null && existing.name != m.name) {
                    val wasCurrent = existing.current
                    AiModelManager.removeModel(ctx, existing.name, existing.target)
                    if (wasCurrent) m.current = true
                }
                AiModelManager.upsertModel(ctx, m)
                toast("已保存（点「设为当前」写入 CLI）")
                refresh()
            }
            .setNegativeButton("取消", null).show()
    }

    /** 同步到 CLI：弹窗选目标（mimo / opencode / 都同步），把所选目标的当前模型写入 CLI 原生配置（后台线程）。 */
    private fun syncToCli() {
        val ctx = requireContext()
        val targets = arrayOf("同步到 mimo", "同步到 opencode", "两个都同步")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("同步到 CLI")
            .setItems(targets) { _, which ->
                val syncTargets = when (which) {
                    0 -> listOf("mimo")
                    1 -> listOf("opencode")
                    else -> listOf("mimo", "opencode")
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val (okCount, details) = withContext(Dispatchers.IO) {
                        AiModelManager.syncAll(ctx, syncTargets)
                    }
                    // 配置已写入全局文件，但运行中的 CLI 进程启动时读一次即缓存、不会重读——
                    // 只有重启终端会话才生效。检测到 CLI 在跑时提示重启（与定时切换口径一致），
                    // 否则用户会误以为"同步后只在本终端生效"。
                    val clis = ModelScheduleManager.runningClis()
                    val tip = if (clis.isNotEmpty()) {
                        "；检测到 ${clis.joinToString("、")} 仍在运行，请重启终端后生效"
                    } else ""
                    // 明确口径：报「同步到哪些 CLI、各几个模型」，避免「成功 2 个」被误读成只同步了一个模型。
                    // details 形如「mimo 8 个模型 ✓」，直接拼进 Toast；失败的（✗）单独点出。
                    val okParts = details.filter { it.contains("✓") }
                    val failParts = details.filter { it.contains("✗") || it.contains("跳过") }
                    val msg = buildString {
                        append(if (okParts.isNotEmpty()) "已同步到 ${okParts.joinToString("、")}" else "没有同步任何 CLI")
                        if (failParts.isNotEmpty()) append("；未同步：${failParts.joinToString("、")}")
                        append(tip)
                    }
                    toast(msg)
                    android.util.Log.i("AiSync", "syncAll details=${details.joinToString("，")}")
                    if (isAdded) refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Chatbox / Cherry Studio 同款「从服务商添加模型」流程：
     * 选服务商 → 填 Key + Base URL → GET /models 拉取 → 点列表项（或全部）写入清单。
     * 复用 AiModelManager.fetchModels（标准 HttpURLConnection + org.json），不引第三方库。
     */
    private fun showFetchDialog() {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_ai_fetch, null)
        val spProvider = v.findViewById<Spinner>(R.id.spFetchProvider)
        val etBaseUrl = v.findViewById<TextInputEditText>(R.id.etFetchBaseUrl)
        val etKey = v.findViewById<TextInputEditText>(R.id.etFetchKey)
        val btnTarget = v.findViewById<MaterialButton>(R.id.btnFetchTarget)
        val btnGo = v.findViewById<MaterialButton>(R.id.btnFetchGo)
        val tvStatus = v.findViewById<MaterialTextView>(R.id.tvFetchStatus)
        val listContainer = v.findViewById<LinearLayout>(R.id.fetchModelList)
        val btnAddAll = v.findViewById<MaterialButton>(R.id.btnFetchAddAll)

        var providers = AiModelManager.allProviders(ctx).toMutableList()
        fun labels(): List<String> = providers.map { it.label } + "＋ 新增自定义服务商（OpenAI 兼容）"
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels().toMutableList())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProvider.adapter = adapter
        var currentProvider: AiModelManager.Provider? = null
        var lastPos = 0
        var target = "mimo"
        var fetched: List<AiModelManager.FetchedModel> = emptyList()

        fun applyProvider(p: AiModelManager.Provider) {
            currentProvider = p
            etBaseUrl.setText(p.baseUrl)
        }

        spProvider.setSelection(0, false)
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos == providers.size) {
                    showAddProviderDialog { newP ->
                        providers = AiModelManager.allProviders(ctx).toMutableList()
                        adapter.clear()
                        adapter.addAll(labels())
                        adapter.notifyDataSetChanged()
                        val np = providers.indexOfFirst { it.id == newP.id }.coerceAtLeast(0)
                        lastPos = np
                        spProvider.setSelection(np)
                        applyProvider(newP)
                    }
                    spProvider.post { spProvider.setSelection(lastPos.coerceAtMost(providers.size - 1)) }
                    return
                }
                lastPos = pos
                applyProvider(providers[pos])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        if (providers.isNotEmpty()) applyProvider(providers[0])

        btnTarget.setOnClickListener {
            target = if (target == "mimo") "opencode" else "mimo"
            btnTarget.text = "写入目标：$target（点击切换）"
        }

        fun baseModel() = AiModelManager.Model(
            provider = currentProvider?.id.orEmpty(),
            apiKey = etKey.text?.toString()?.trim().orEmpty(),
            baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty(),
            target = target,
        )

        fun addModel(fm: AiModelManager.FetchedModel): Boolean {
            val base = baseModel()
            val fullName = "${base.provider}/${fm.id}"
            val existing = AiModelManager.listModels(ctx).firstOrNull {
                it.name == fullName && it.target == base.target
            }
            AiModelManager.upsertModel(ctx, base.copy(
                name = fullName, modelId = fm.id,
                enabled = existing?.enabled ?: true,
                current = existing?.current ?: false,
                contextLength = fm.contextLength,
                maxOutputLength = fm.maxOutputLength,
                supportsToolCall = fm.supportsToolCall,
                supportsReasoning = fm.supportsReasoning,
                supportsImage = fm.supportsImage,
                supportsTemperature = fm.supportsTemperature,
            ))
            return existing != null
        }

        btnGo.setOnClickListener {
            val m = baseModel()
            tvStatus.text = "获取中…（连接超时 8s）"
            btnGo.isEnabled = false
            listContainer.removeAllViews()
            btnAddAll.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { AiModelManager.fetchModels(m) }
                if (!isAdded) return@launch
                btnGo.isEnabled = true
                if (res.models.isEmpty()) {
                    tvStatus.text = "✗ ${res.error}"
                    return@launch
                }
                fetched = res.models
                val withCaps = res.models.count { it.contextLength > 0 }
                val capNote = if (withCaps > 0) "（$withCaps 个含能力信息）" else ""
                tvStatus.text = "✓ 共 ${res.models.size} 个模型$capNote，点击添加（或点「全部添加」）"
                res.models.forEach { fm ->
                    listContainer.addView(MaterialButton(ctx).apply {
                        text = fm.id
                        isAllCaps = false
                        setSingleLine(true)
                        maxLines = 1
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                        setOnClickListener {
                            val wasUpdate = addModel(fm)
                            toast(if (wasUpdate) "已更新：${fm.id}" else "已添加：${fm.id}")
                            refresh()
                        }
                    })
                }
                btnAddAll.visibility = View.VISIBLE
            }
        }

        btnAddAll.setOnClickListener {
            var added = 0
            var updated = 0
            fetched.forEach { if (addModel(it)) updated++ else added++ }
            toast("已添加 $added 个，更新 $updated 个")
            refresh()
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("从服务商获取模型")
            .setView(v)
            .setNegativeButton("关闭") { _, _ -> refresh() }
            .show()
    }

    /** 新增自定义服务商（OpenAI 兼容协议），保存后可在下拉里复用。 */
    private fun showAddProviderDialog(onAdded: (AiModelManager.Provider) -> Unit) {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_ai_provider, null)
        val etId = v.findViewById<TextInputEditText>(R.id.etProvId)
        val etLabel = v.findViewById<TextInputEditText>(R.id.etProvLabel)
        val etUrl = v.findViewById<TextInputEditText>(R.id.etProvBaseUrl)
        val etModels = v.findViewById<TextInputEditText>(R.id.etProvModels)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("新增自定义服务商（OpenAI 兼容）")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val id = etId.text?.toString()?.trim().orEmpty()
                val label = etLabel.text?.toString()?.trim().orEmpty().ifEmpty { id }
                val url = etUrl.text?.toString()?.trim().orEmpty()
                val models = etModels.text?.toString()
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                if (id.isBlank() || url.isBlank()) {
                    toast("Provider ID 与 API 地址必填")
                    return@setPositiveButton
                }
                val p = AiModelManager.Provider(id, label, url, models, builtin = false)
                if (!AiModelManager.addCustomProvider(ctx, p)) {
                    toast("保存失败：ID 为空或与已有服务商重复")
                    return@setPositiveButton
                }
                toast("已添加服务商：$label")
                onAdded(p)
            }
            .setNegativeButton("取消", null).show()
    }

    /** 拼接模型能力摘要：工具 / 推理 / 图片逐项展示。上下文长度单独一行展示，不再绑架能力显示——
     *  工具/推理/图片是独立字段（部分服务商如 xfyun 的 /models 不返回，但默认按支持处理），
     *  即使上下文未知也应把已知的能力显示出来，而不是整体显示「未知」。 */
    private fun buildCapabilityText(m: AiModelManager.Model): String {
        return "工具${if (m.supportsToolCall) "✓" else "✗"} · " +
            "推理${if (m.supportsReasoning) "✓" else "✗"} · " +
            "图片${if (m.supportsImage) "✓" else "✗"}"
    }
}
