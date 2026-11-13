package com.codetool.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codetool.app.script.ScriptInfo
import com.codetool.app.script.ScriptManager
import com.codetool.app.script.ScriptRunDialog
import com.codetool.app.task.TaskManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/**
 * 脚本库页：无「脚本库」标题；中间显示空状态；
 * 点击运行、长按编辑/删除/收藏；支持示例与新建（带协议标准头）。
 */
class ScriptsFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private val items = mutableListOf<ScriptInfo>()
    private var adapter: ScriptAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_scripts, container, false)
        list = v.findViewById(R.id.scriptList)
        emptyState = v.findViewById(R.id.emptyState)
        emptyText = v.findViewById(R.id.emptyText)
        countText = v.findViewById(R.id.countText)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = ScriptAdapter(requireContext(), items)
        list.adapter = adapter

        v.findViewById<MaterialButton>(R.id.addExampleBtn).setOnClickListener { createExamples() }
        v.findViewById<MaterialButton>(R.id.newScriptBtn).setOnClickListener { newScriptDialog() }
        v.findViewById<MaterialButton>(R.id.emptyCreateBtn).setOnClickListener { createExamples() }
        return v
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // 确保内置本地脚本（安装构建环境）始终存在且为最新版
        ScriptManager.ensureLocalScripts(requireContext().applicationContext)
        items.clear()
        items.addAll(ScriptManager.listScripts())
        adapter?.notifyDataSetChanged()
        val fav = items.count { ScriptManager.isScriptFav(requireContext(), it.file.absolutePath) }
        countText.text = if (items.isEmpty()) "" else "${items.size} 个脚本 · $fav 收藏"
        val empty = items.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.text = "暂无脚本"
    }

    private fun createExamples() {
        val n = ScriptManager.createExamples()
        Toast.makeText(requireContext(), if (n > 0) "已创建 $n 个示例脚本" else "示例已存在", Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** 新建脚本：启动独立编辑页（带协议头模板 + 行号 + 文件名输入）。 */
    private fun newScriptDialog() {
        ScriptEditActivity.launchCreate(requireContext())
    }

    /** 点击脚本：显示运行弹窗（标题/说明/参数输入框），点「运行」才在终端内执行（防误触）。 */
    private fun runScript(s: ScriptInfo) {
        val ctx = requireContext()
        ScriptRunDialog.show(ctx, s) { args ->
            // 在终端会话内运行（先选择目标标签，输出显示在终端）
            // 提示由 runScript 在实际发送后弹出，避免还没选标签就说“已运行”
            TerminalFragment.pickTabAndRun(ctx, s.file, s.name, *args)
        }
    }

    /** 长按菜单：编辑 / 收藏 / 删除。 */
    private fun showActions(s: ScriptInfo) {
        val ctx = requireContext()
        val isFav = ScriptManager.isScriptFav(ctx, s.file.absolutePath)
        val opts = arrayOf(
            "编辑内容",
            if (isFav) "取消收藏" else "收藏",
            "删除"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(s.name)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> editScript(s)
                    1 -> { ScriptManager.toggleScriptFav(ctx, s.file.absolutePath); refresh() }
                    2 -> deleteScript(s)
                }
            }
            .show()
    }

    private fun editScript(s: ScriptInfo) {
        // 独立编辑页（非弹窗）：全屏编辑，保存写回
        ScriptEditActivity.launch(requireContext(), s.file.absolutePath)
    }

    private fun deleteScript(s: ScriptInfo) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除脚本")
            .setMessage("确定删除「${s.name}」？")
            .setPositiveButton("删除") { _, _ ->
                s.file.delete()
                // 只清理已有收藏，不能在删除时反向“加收藏”
                if (ScriptManager.isScriptFav(ctx, s.file.absolutePath)) {
                    ScriptManager.toggleScriptFav(ctx, s.file.absolutePath)
                }
                ScriptManager.forgetParams(ctx, s.file.absolutePath)
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class ScriptAdapter(
        private val ctx: Context,
        private val data: List<ScriptInfo>
    ) : RecyclerView.Adapter<ScriptAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.scriptName)
            val desc: TextView = view.findViewById(R.id.scriptDesc)
            val fav: ImageView = view.findViewById(R.id.favStar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_script, parent, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = data[pos]
            h.name.text = s.name
            if (s.desc.isNotEmpty()) {
                h.desc.text = s.desc
                h.desc.visibility = View.VISIBLE
            } else {
                h.desc.visibility = View.GONE
            }
            val fav = ScriptManager.isScriptFav(ctx, s.file.absolutePath)
            h.fav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
            h.itemView.setOnClickListener { runScript(s) }
            h.itemView.setOnLongClickListener { showActions(s); true }
            h.fav.setOnClickListener {
                ScriptManager.toggleScriptFav(ctx, s.file.absolutePath)
                refresh()
            }
        }
    }
}
