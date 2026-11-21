package com.codetool.app

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.addCallback
import com.codetool.app.util.EditorSearchController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Skill 编辑器（全屏页）：大空间编辑 Markdown 正文，比弹窗好编辑。
 * 名称/描述用输入框，正文用 sora-editor（与配置/脚本编辑器同一库，行号+高亮）。
 * 结果通过 setResult 回传给 SkillsFragment（编辑是覆盖原文件；新增返回新内容）。
 */
class SkillEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_DESC = "description"
        const val EXTRA_BODY = "body"
        const val RESULT_SAVED = RESULT_FIRST_USER
    }

    // findViewById 在个别设备/系统版本上可能返回 null（真机偶发），统一用可空类型 +
    // 安全访问，缺视图时功能降级（不工作）而不是启动即 NPE 崩溃。
    private var etName: TextInputEditText? = null
    private var etDesc: TextInputEditText? = null
    private var etBody: CodeEditor? = null

    /** 进入时的初始 description / 正文：用于返回时判断「是否有改动」→ 提示保存。 */
    private var initialDesc: String = ""
    private var initialBody: String = ""

    /** 内联搜索（点标题栏搜索按钮展开，输入即搜编辑内容）。依赖的视图缺失时保持 null。 */
    private var search: EditorSearchController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.codetool.app.theme.ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skill_editor)

        val toolbar = findViewById<Toolbar>(R.id.skillToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etName = findViewById(R.id.etSkillName)
        etDesc = findViewById(R.id.etSkillDesc)
        etBody = findViewById(R.id.etSkillBody)

        // 内联搜索：点标题栏搜索按钮展开搜索条，输入即搜编辑内容（sora EditorSearcher 高亮）
        // 仅当编辑器与搜索条相关视图全部就绪才构建控制器；任一缺失则保持 null（搜索功能降级）。
        etBody?.let { body ->
            val searchBar = findViewById<View>(R.id.searchBar)
            val searchInput = findViewById<EditText>(R.id.searchInput)
            val searchCount = findViewById<TextView>(R.id.searchCount)
            val searchPrev = findViewById<View>(R.id.searchPrev)
            val searchNext = findViewById<View>(R.id.searchNext)
            val searchClose = findViewById<View>(R.id.searchClose)
            if (searchBar != null && searchInput != null && searchCount != null &&
                searchPrev != null && searchNext != null && searchClose != null) {
                search = EditorSearchController(
                    body,
                    searchBar,
                    searchInput,
                    searchCount,
                    searchPrev,
                    searchNext,
                    searchClose,
                )
            }
        }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val desc = intent.getStringExtra(EXTRA_DESC).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (name.isNotEmpty()) {
            etName?.setText(name)
            etName?.isEnabled = false   // name = 目录名，编辑时不改
            // 主标题「编辑 Skill」+ 副标题 skill 名：现在标题栏只留搜索/保存 2 个图标，
            // 空间充足，两个都显示得下（名字用 subtitle 完整可见，不再被图标挤压）。
            title = "编辑 Skill"
            supportActionBar?.subtitle = name
        } else {
            title = "新增 Skill"
            supportActionBar?.subtitle = null
        }
        etDesc?.setText(desc)
        initialDesc = desc
        initialBody = body
        // 返回（标题栏返回箭头 + 系统返回键）统一走 confirmExit：有改动先提示保存
        onBackPressedDispatcher.addCallback(this) { confirmExit() }
        // sora-editor 的 setText 在布局完成前调用偶发不渲染，post 到下一帧确保正文显示。
        // 注意：必须先 setText 再 applyTo 设语言——TextMate 语法加载是异步的，
        // 语言设置的太早会把已渲染的正文清掉（正文区全空）。
        // sora-editor 的 setText 在布局完成前调用偶发不渲染，post 到下一帧确保正文显示。
        // 注意：必须先 setText 再 applyTo 设语言——TextMate 语法加载是异步的，
        // 语言设置的太早会把已渲染的正文清掉（正文区全空）。
        // post 的 lambda 无参数（Runnable），故捕获非空的局部 val，避免在 lambda 里再读可空字段。
        etBody?.let { bodyView ->
            bodyView.post {
                bodyView.setText(body)
                // Markdown 语法高亮（skill 正文是 .md）
                com.codetool.app.util.TextMateEditor.applyTo(bodyView, "skill.md")
            }
        }

        toolbar?.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_skill_save -> { saveAndFinish(); true }
                R.id.action_skill_copy -> { copyToClipboard(); true }
                R.id.action_skill_help -> { showHelp(); true }
                R.id.action_skill_search -> { search?.toggle(); true }
                R.id.action_skill_undo -> { etBody?.undo(); true }
                R.id.action_skill_redo -> { etBody?.redo(); true }
                else -> false
            }
        }
    }

    /** 菜单必须在 onCreateOptionsMenu inflate，否则标题栏按钮不显示（复制/说明/保存）。 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_skill_editor, menu)
        return true
    }

    /** 复制 skill 完整内容（正文为主）到剪贴板。 */
    private fun copyToClipboard() {
        val text = etBody?.text?.toString() ?: ""
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("skill", text))
        Toast.makeText(this, "已复制 skill 正文到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /** 弹出 skill 编辑说明（标题栏说明按钮）。 */
    private fun showHelp() {
        val msg = "• 名称：小写字母/数字/连字符，= 容器里的目录名，编辑时不可改。\n" +
            "• description：1-1024 字，写清楚「什么时候该用这个 skill」，模型据此决定是否加载。\n" +
            "• 指令正文：Markdown，模型加载后遵循。新建写 ~/.agents/skills/<名称>/SKILL.md；" +
            "编辑导入的 skill 会写回它原来的目录。\n" +
            "• 保存后重启 CLI 会话（mimo / opencode）才会识别到改动。"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Skill 编辑说明")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        confirmExit()
        return true
    }

    private fun saveAndFinish() {
        val name = etName?.text?.toString()?.trim().orEmpty()
        val desc = etDesc?.text?.toString()?.trim().orEmpty()
        val body = etBody?.text?.toString() ?: ""
        if (name.isEmpty()) {
            Toast.makeText(this, "名称必填（小写字母/数字/连字符）", Toast.LENGTH_SHORT).show()
            return
        }
        if (desc.isEmpty()) {
            Toast.makeText(this, "description 必填（写清楚何时该用）", Toast.LENGTH_SHORT).show()
            return
        }
        intent.putExtra(EXTRA_NAME, name)
        intent.putExtra(EXTRA_DESC, desc)
        intent.putExtra(EXTRA_BODY, body)
        setResult(RESULT_SAVED, intent)
        finish()
    }

    /** 内容是否有改动（description 或正文相对进入时的初始值）。名称编辑时禁用，不参与判断。 */
    private fun isModified(): Boolean =
        etDesc?.text?.toString().orEmpty() != initialDesc ||
            (etBody?.text?.toString() ?: "") != initialBody

    /**
     * 返回拦截：内容有改动时弹「已修改是否保存」对话框（保存 / 不保存 / 取消），
     * 无改动直接退出。系统返回键走 onBackPressedDispatcher，标题栏返回箭头走 onSupportNavigateUp。
     */
    private fun confirmExit() {
        if (!isModified()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("已修改")
            .setMessage("内容已修改，是否保存？")
            .setPositiveButton("保存") { _, _ -> saveAndFinish() }
            .setNegativeButton("不保存") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }
}
