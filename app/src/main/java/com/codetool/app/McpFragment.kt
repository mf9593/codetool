package com.codetool.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codetool.app.ai.McpManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

/** MCP 服务管理：直接写 CLI 的 mcp 配置段，开关 = enabled，CLI 启动默认识别。 */
class McpFragment : Fragment() {

    private lateinit var list: LinearLayout
    private lateinit var status: MaterialTextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_mcp, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.mcpList)
        status = view.findViewById(R.id.mcpStatus)
        McpManager.ensureBuiltin(requireContext())
        view.findViewById<MaterialButton>(R.id.btnMcpAdd).setOnClickListener { showEdit(null) }
        view.findViewById<MaterialButton>(R.id.btnMcpBuiltin).setOnClickListener {
            val n = McpManager.ensureBuiltin(requireContext())
            toast(if (n > 0) "已注册内置 MCP 服务（默认开启）" else "内置服务已存在")
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            refresh()
            refreshBrowserStatus()
        }
    }

    /** 内置 MCP 服务依赖浏览器；未装时给出明确安装入口，别让模型白调 browser_open。 */
    private fun refreshBrowserStatus() {
        val tv = view?.findViewById<MaterialTextView>(R.id.mcpBrowserStatus) ?: return
        val installed = com.codetool.app.gui.DesktopApps.browserInstalled(requireContext())
        tv.text = if (installed) {
            "内置浏览器：已安装（Chrome）— MCP 的 browser_open / browser_eval 可直接调用"
        } else {
            "内置浏览器：未安装 — browser_open 会失败。请先到「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」"
        }
        tv.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                requireContext(),
                if (installed) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorError,
                android.graphics.Color.GRAY
            )
        )
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val ctx = requireContext()
        val items = McpManager.listServers(ctx)
        status.text = "读取 MiMo Code 与 OpenCode 配置（含 JSONC 注释）· 现有 ${items.size} 个"
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(MaterialTextView(ctx).apply {
                text = "暂无 MCP 服务。\n点「恢复内置」注册内置浏览器服务，或「新增服务」。"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
                setTextColor(MaterialColors.getColor(
                    ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY))
            })
            return
        }
        items.forEach { s ->
            // 规范：列表项用 item_*.xml + inflate，不在 Kotlin 里拼布局
            val row = layoutInflater.inflate(R.layout.item_mcp, list, false)
            val name = row.findViewById<MaterialTextView>(R.id.mcpName)
            val badgeType = row.findViewById<MaterialTextView>(R.id.mcpBadgeType)
            val badgeState = row.findViewById<MaterialTextView>(R.id.mcpBadgeState)
            val meta = row.findViewById<MaterialTextView>(R.id.mcpMeta)
            val sources = row.findViewById<MaterialTextView>(R.id.mcpSources)
            val commandRow = row.findViewById<View>(R.id.mcpCommandRow)
            val urlRow = row.findViewById<View>(R.id.mcpUrlRow)
            val command = row.findViewById<MaterialTextView>(R.id.mcpCommand)
            val url = row.findViewById<MaterialTextView>(R.id.mcpUrl)
            val sw = row.findViewById<SwitchCompat>(R.id.mcpEnabled)

            name.text = s.name
            applyBadge(ctx, badgeType, if (s.builtin) "内置" else null, active = true)
            applyBadge(ctx, badgeState, if (s.enabled) null else "已停用", active = false)
            meta.text = when {
                s.isRemote -> "远程服务 · HTTP / SSE"
                s.command.firstOrNull()?.contains("python") == true -> "本地进程 · stdio · Python"
                else -> "本地进程 · stdio"
            }
            sources.text = s.sources.joinToString(" · ").ifEmpty { "未识别" }

            val remote = s.isRemote
            commandRow.visibility = if (remote) View.GONE else View.VISIBLE
            urlRow.visibility = if (remote) View.VISIBLE else View.GONE
            command.text = s.command.joinToString(" ").ifEmpty { "未设置" }
            url.text = s.url.ifEmpty { "未设置" }

            // 开关：直接改配置里的 enabled，CLI 下次启动即生效
            sw.isChecked = s.enabled
            sw.setOnCheckedChangeListener { _, on ->
                val err = McpManager.setEnabled(ctx, s.name, on)
                // 不手动回设 isChecked（会再次触发 listener）；refresh 重建行即可还原
                if (err != null) toast(err)
                else toast(if (on) "已启用 ${s.name}" else "已停用 ${s.name}")
                refresh()
            }

            row.findViewById<MaterialButton>(R.id.btnMcpEdit).setOnClickListener { showEdit(s) }
            row.findViewById<MaterialButton>(R.id.btnMcpDelete).setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("删除 MCP 服务")
                    .setMessage("删除「${s.name}」？会从两个 CLI 配置里移除，模型下次启动不再加载。")
                    .setPositiveButton("删除") { _, _ ->
                        val err = McpManager.remove(ctx, s.name)
                        if (err != null) toast(err)
                        refresh()
                    }
                    .setNegativeButton("取消", null).show()
            }
            list.addView(row)
        }
    }

    /** 徽标统一由代码按主题着色，避免各布局硬编码色值。 */
    private fun applyBadge(ctx: android.content.Context, tv: MaterialTextView, text: String?, active: Boolean) {
        if (text == null) {
            tv.visibility = View.GONE
            return
        }
        tv.text = text
        tv.background = ContextCompat.getDrawable(
            ctx, if (active) R.drawable.bg_badge_current else R.drawable.bg_badge_off)
        tv.setTextColor(MaterialColors.getColor(
            ctx,
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY))
        tv.visibility = View.VISIBLE
    }

    private fun showEdit(existing: McpManager.Server?) {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_mcp, null)
        val etName = v.findViewById<TextInputEditText>(R.id.etMcpName)
        val spType = v.findViewById<Spinner>(R.id.spMcpType)
        val tilCommand = v.findViewById<TextInputLayout>(R.id.tilMcpCommand)
        val etCommand = v.findViewById<TextInputEditText>(R.id.etMcpCommand)
        val tilUrl = v.findViewById<TextInputLayout>(R.id.tilMcpUrl)
        val etUrl = v.findViewById<TextInputEditText>(R.id.etMcpUrl)
        val swEnabled = v.findViewById<SwitchCompat>(R.id.swMcpEnabled)

        existing?.let {
            etName.setText(it.name)
            etName.isEnabled = false
            etCommand.setText(it.command.joinToString(" "))
            etUrl.setText(it.url)
            spType.setSelection(if (it.isRemote) 1 else 0)
            swEnabled.isChecked = it.enabled
        }
        fun syncType() {
            val remote = spType.selectedItemPosition == 1
            tilCommand.visibility = if (remote) View.GONE else View.VISIBLE
            tilUrl.visibility = if (remote) View.VISIBLE else View.GONE
        }
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v2: View?, pos: Int, id: Long) = syncType()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        syncType()

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "新增 MCP 服务" else "编辑 MCP 服务")
            .setView(v)
            .setPositiveButton("保存") { _, _ ->
                val remote = spType.selectedItemPosition == 1
                val s = McpManager.Server(
                    name = etName.text?.toString()?.trim().orEmpty(),
                    type = if (remote) "remote" else "local",
                    command = etCommand.text?.toString()?.trim().orEmpty()
                        .split(Regex("\\s+")).filter { it.isNotEmpty() },
                    url = etUrl.text?.toString()?.trim().orEmpty(),
                    enabled = swEnabled.isChecked,
                    builtin = existing?.builtin ?: false,
                )
                val err = McpManager.upsert(ctx, s)
                if (err != null) toast(err) else {
                    toast("已写入配置，重启 CLI 会话即可识别")
                    refresh()
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
