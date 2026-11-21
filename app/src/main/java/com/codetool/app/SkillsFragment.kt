package com.codetool.app

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codetool.app.ai.SkillManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView

/** Skill 管理：扫描共享目录 + MiMo / OpenCode 等兼容目录，实时读盘；编辑写回原位置。 */
class SkillsFragment : Fragment() {

    private companion object {
        const val STATE_PENDING_PATH = "pending_edit_path"
    }

    private lateinit var list: LinearLayout
    private lateinit var status: MaterialTextView

    /** 全屏编辑页 launcher：回传 name/desc/body，写回（新增或覆盖原文件）。 */
    private val skillEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != SkillEditorActivity.RESULT_SAVED) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val name = data.getStringExtra(SkillEditorActivity.EXTRA_NAME).orEmpty()
        val desc = data.getStringExtra(SkillEditorActivity.EXTRA_DESC).orEmpty()
        val body = data.getStringExtra(SkillEditorActivity.EXTRA_BODY).orEmpty()
        val existing = pendingEdit
        val ctx = context ?: return@registerForActivityResult   // Fragment 已 detach 时安全退出，别 requireContext 崩溃
        val err = SkillManager.upsert(ctx, existing, name, desc, body)
        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
        else {
            Toast.makeText(ctx, "已保存，重启 CLI 会话即可识别", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private var pendingEdit: SkillManager.Skill? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_skills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.skillList)
        status = view.findViewById(R.id.skillStatus)
        // 重建（旋转/进程回收）后恢复待编辑 skill：编辑页开着时宿主 Activity 若重建，
        // 字段 pendingEdit 会归零 → 保存把「编辑」误当「新增」。按存的路径从磁盘重读恢复。
        savedInstanceState?.getString(STATE_PENDING_PATH)?.let { path ->
            pendingEdit = SkillManager.listSkills(requireContext())
                .firstOrNull { it.file.absolutePath == path }
        }
        SkillManager.ensureBuiltin(requireContext())
        view.findViewById<MaterialButton>(R.id.btnSkillAdd).setOnClickListener { showEdit(null) }
        view.findViewById<MaterialButton>(R.id.btnSkillBuiltin).setOnClickListener {
            val n = SkillManager.ensureBuiltin(requireContext())
            toast(if (n > 0) "已恢复内置 skill" else "内置 skill 已存在")
            refresh()
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 只存文件路径（轻量、唯一标识），重建时按路径重读，不序列化整个 Skill 对象。
        pendingEdit?.let { outState.putString(STATE_PENDING_PATH, it.file.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val ctx = requireContext()
        val items = SkillManager.listSkills(ctx)
        status.text = "扫描共享目录 + MiMo / OpenCode / Claude / Codex 兼容目录 · 现有 ${items.size} 个"
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(MaterialTextView(ctx).apply {
                text = "暂无 Skill。\n点「新增 Skill」新建，或「恢复内置」注册内置浏览器 skill。"
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
            val row = layoutInflater.inflate(R.layout.item_skill, list, false)
            val name = row.findViewById<MaterialTextView>(R.id.skillName)
            val badge = row.findViewById<MaterialTextView>(R.id.skillBadge)
            val desc = row.findViewById<MaterialTextView>(R.id.skillDesc)
            val source = row.findViewById<MaterialTextView>(R.id.skillSource)
            val path = row.findViewById<MaterialTextView>(R.id.skillPath)

            name.text = s.name
            // 内置徽标放标题右侧，颜色跟随主题（5 个内置 skill 都要显示）
            if (s.id in SkillManager.BUILTIN_IDS) {
                badge.text = "内置"
                badge.background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge_current)
                badge.setTextColor(MaterialColors.getColor(
                    ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.GRAY))
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
            desc.text = s.description.ifEmpty { "（无 description）" }
            source.text = s.source
            path.text = s.pathText

            row.findViewById<MaterialButton>(R.id.btnSkillEdit).setOnClickListener { showEdit(s) }
            row.findViewById<MaterialButton>(R.id.btnSkillDelete).setOnClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("删除 Skill")
                    .setMessage("删除「${s.name}」？会删掉它所在的文件/目录（${s.pathText}），模型下次启动不再识别。")
                    .setPositiveButton("删除") { _, _ ->
                        SkillManager.remove(s)
                        refresh()
                    }
                    .setNegativeButton("取消", null).show()
            }
            list.addView(row)
        }
    }

    private fun showEdit(existing: SkillManager.Skill?) {
        pendingEdit = existing
        val intent = android.content.Intent(requireContext(), SkillEditorActivity::class.java)
        if (existing != null) {
            intent.putExtra(SkillEditorActivity.EXTRA_NAME, existing.name)
            intent.putExtra(SkillEditorActivity.EXTRA_DESC, existing.description)
            intent.putExtra(SkillEditorActivity.EXTRA_BODY, existing.body)
        }
        skillEditor.launch(intent)
    }
}
