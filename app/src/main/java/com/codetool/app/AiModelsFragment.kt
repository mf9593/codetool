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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiModelManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_ai_models, container, false)
        statusMimo = v.findViewById(R.id.aiStatusMimo)
        statusOc = v.findViewById(R.id.aiStatusOpencode)
        modelList = v.findViewById(R.id.aiModelList)

        v.findViewById<MaterialButton>(R.id.btnAiFixPath).setOnClickListener {
            TerminalFragment.sendCommand(requireContext(), AiModelManager.FIX_PATH_SCRIPT)
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
        return v
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
            val btnCurrent = row.findViewById<MaterialButton>(R.id.btnModelCurrent)
            val btnEdit = row.findViewById<MaterialButton>(R.id.btnModelEdit)
            val btnDelete = row.findViewById<MaterialButton>(R.id.btnModelDelete)
            val targetBadge = row.findViewById<MaterialTextView>(R.id.modelTarget)

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
            // 目标徽标：mimo / opencode，颜色跟随主题，一眼看出模型属于哪个 CLI 配置
            when (m.target) {
                "opencode" -> {
                    targetBadge.text = "opencode"
                    targetBadge.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_target_oc)
                    targetBadge.setTextColor(MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.GRAY))
                    targetBadge.visibility = View.VISIBLE
                }
                else -> {
                    targetBadge.text = "mimo"
                    targetBadge.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_target_mimo)
                    targetBadge.setTextColor(MaterialColors.getColor(
                        ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.GRAY))
                    targetBadge.visibility = View.VISIBLE
                }
            }
            meta.text = "目标 ${m.target}  ·  " +
                if (m.apiType == AiModelManager.ApiType.RESPONSES) "Responses API" else "Chat Completions API"
            provider.text = AiModelManager.providerLabel(ctx, m.provider)
            modelId.text = m.modelId
            baseUrl.text = m.baseUrl.ifEmpty { "未设置" }
            key.text = if (m.apiKey.isEmpty()) "未设置" else "••••" + m.apiKey.takeLast(4)

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
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("删除模型")
                    .setMessage("删除「${m.name}」？只删清单，不改 CLI 现有配置。")
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
        val swAiEnabled = v.findViewById<SwitchCompat>(R.id.swAiEnabled)
        val testBtn = v.findViewById<MaterialButton>(R.id.btnAiTest)
        val testResult = v.findViewById<MaterialTextView>(R.id.tvAiTestResult)

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

    /** 同步到 CLI：把每个目标的当前模型重新写入 CLI 原生配置（后台线程）。 */
    private fun syncToCli() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val (okCount, details) = withContext(Dispatchers.IO) {
                AiModelManager.syncAll(ctx)
            }
            toast("已同步 $okCount 个目标：${details.joinToString("，")}")
            if (isAdded) refresh()
        }
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
        var fetched: List<String> = emptyList()

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

        fun addModel(modelId: String) {
            val base = baseModel()
            AiModelManager.upsertModel(ctx, base.copy(
                name = "${base.provider}/$modelId", modelId = modelId, enabled = true,
            ))
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
                tvStatus.text = "✓ 共 ${res.models.size} 个模型，点击添加（或点「全部添加」）"
                res.models.forEach { modelId ->
                    listContainer.addView(MaterialButton(ctx).apply {
                        text = modelId
                        isAllCaps = false
                        setSingleLine(true)
                        maxLines = 1
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(4) }
                        setOnClickListener {
                            addModel(modelId)
                            toast("已添加：$modelId")
                            refresh()
                        }
                    })
                }
                btnAddAll.visibility = View.VISIBLE
            }
        }

        btnAddAll.setOnClickListener {
            fetched.forEach { addModel(it) }
            toast("已添加 ${fetched.size} 个模型")
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
}
