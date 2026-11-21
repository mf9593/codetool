package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.codetool.app.script.ScriptManager
import com.codetool.app.theme.ThemeManager
import com.codetool.app.util.EditorSearchController
import io.github.rosemoe.sora.widget.CodeEditor
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

/**
 * 脚本编辑独立页面（非弹窗）。
 * - 编辑模式：传入脚本绝对路径，加载内容，保存写回。
 * - 新建模式（CREATE=true）：编辑器预置协议头模板，保存时按文件名在脚本目录新建。
 * 标题栏背景延伸到状态栏（edge-to-edge），左侧带行号。
 */
class ScriptEditActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "script_path"
        private const val EXTRA_CREATE = "create_new"
        private const val EXTRA_DIR = "create_dir"
        private const val EXTRA_TITLE = "create_title"

        /** 编辑已有脚本。 */
        fun launch(context: Context, path: String) {
            context.startActivity(Intent(context, ScriptEditActivity::class.java)
                .putExtra(EXTRA_PATH, path))
        }

        /**
         * 新建脚本/文件（独立页面）。
         * - [dir] 为空时写入 [ScriptManager.scriptDir]（脚本库）；
         * - [title] 用于标题栏文案，如「新建脚本」或「新建文件」。
         */
        fun launchCreate(
            context: Context,
            dir: File? = null,
            title: String = "新建脚本"
        ) {
            context.startActivity(Intent(context, ScriptEditActivity::class.java)
                .putExtra(EXTRA_CREATE, true)
                .putExtra(EXTRA_DIR, dir?.absolutePath)
                .putExtra(EXTRA_TITLE, title))
        }
    }

    private lateinit var editor: CodeEditor
    private lateinit var nameLayout: TextInputLayout
    private lateinit var nameInput: TextInputEditText
    private var scriptFile: File? = null
    private var createMode = false

    /** 进入时的初始内容：用于返回时判断「是否有改动」→ 提示保存。 */
    private var initialContent: String = ""

    /** 编辑内容区是否自动换行（开=长行换行，关=横向滚动，默认关）。 */
    private var wordWrap = false

    /** 内联搜索（点操作行搜索按钮展开，输入即搜编辑内容）。 */
    private lateinit var search: EditorSearchController

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        // edge-to-edge：标题栏背景延伸到状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_script_edit)

        val toolbar = findViewById<MaterialToolbar>(R.id.editToolbar)
        editor = findViewById(R.id.scriptEditor)
        nameLayout = findViewById(R.id.nameLayout)
        nameInput = findViewById(R.id.nameInput)
        // 保存按钮已移入标题栏（menu action_save）；底部不再放按钮
        // 返回（标题栏返回箭头 + 系统返回键）统一走 confirmExit：有改动先提示保存
        toolbar.setNavigationOnClickListener { confirmExit() }
        onBackPressedDispatcher.addCallback(this) { confirmExit() }

        // 标题栏菜单：复制全部（带 .log 内容出去）+ 保存（主操作，移入标题栏）
        // 操作按钮放在标题栏下方独立一行：次操作靠左、主操作「保存」靠右，紧凑高度。
        findViewById<MaterialButton>(R.id.btnEditCopyAll).setOnClickListener { copyAll() }
        findViewById<MaterialButton>(R.id.btnEditSave).setOnClickListener { save() }

        // 撤销/重做：调 sora CodeEditor 原生 undo()/redo()（编辑器自带撤销栈，不造轮子）
        findViewById<MaterialButton>(R.id.btnEditUndo).setOnClickListener { editor.undo() }
        findViewById<MaterialButton>(R.id.btnEditRedo).setOnClickListener { editor.redo() }
        // 自动换行开关（图标按钮）：切换的是**编辑内容区**的换行，不是标题。
        val wrapBtn = findViewById<MaterialButton>(R.id.btnEditWrap)
        wrapBtn.setOnClickListener {
            wordWrap = !wordWrap
            applyWordWrap(wrapBtn)
        }

        // 内联搜索：点操作行搜索按钮展开搜索条，输入即搜编辑内容（sora EditorSearcher 高亮）
        findViewById<MaterialButton>(R.id.btnEditSearch).setOnClickListener {
            search.toggle()
        }
        search = EditorSearchController(
            editor,
            findViewById(R.id.searchBar),
            findViewById(R.id.searchInput),
            findViewById(R.id.searchCount),
            findViewById(R.id.searchPrev),
            findViewById(R.id.searchNext),
            findViewById(R.id.searchClose),
        )

        // 状态栏 inset 由 AppBarLayout(fitsSystemWindows) 自动消费；
        // 保存按钮已移入标题栏，底部无控件，导航条 inset 交给编辑器自身留白

        val path = intent.getStringExtra(EXTRA_PATH)
        createMode = intent.getBooleanExtra(EXTRA_CREATE, false)

        if (createMode) {
            toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "新建脚本"
            toolbar.subtitle = null
            nameLayout.visibility = android.view.View.VISIBLE
            // 协议头模板
            editor.setText("#!/bin/sh\n#codetool: \n#codetool-desc: \n\n")
            initialContent = editor.text.toString()
            // 光标定位到 #codetool: 后（sora-editor 的 setSelection 是 line+column，非单 index）
            editor.requestFocus()
            val text = editor.text.toString()
            val idx = text.indexOf("#codetool: ")
            if (idx >= 0) {
                val before = text.substring(0, idx + "#codetool: ".length)
                val line = before.count { it == '\n' }
                val col = before.length - (before.lastIndexOf('\n') + 1)
                editor.setSelection(line, col)
            } else {
                editor.setSelection(0, 0)
            }
        } else {
            val p = path ?: run { finish(); return }
            val f = File(p)
            if (!f.exists()) { finish(); return }
            scriptFile = f
            toolbar.title = "编辑脚本"
            toolbar.subtitle = f.name   // 副标题：显示正在编辑的文件名
            editor.setText(runCatching { f.readText() }.getOrDefault(""))
            initialContent = editor.text.toString()
        }
        // 按文件类型设 TextMate 语法高亮（.sh 脚本高亮）
        com.codetool.app.util.TextMateEditor.applyTo(
            editor,
            if (createMode) "新建脚本.sh" else (scriptFile?.name ?: path ?: "script.sh")
        )
        // 标题已在上面设置：把标题字体调小（默认 20sp，长文件名更不容易截断），
        // 并应用编辑内容区换行初始状态。
        toolbarTitleView(toolbar)?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, 15f
        )
        applyWordWrap(findViewById(R.id.btnEditWrap))
    }

    /** 工具栏标题 TextView：MaterialToolbar 的标题是内部子 TextView，遍历取之。 */
    private fun toolbarTitleView(toolbar: MaterialToolbar): android.widget.TextView? {
        for (i in 0 until toolbar.childCount) {
            (toolbar.getChildAt(i) as? android.widget.TextView)?.let { return it }
        }
        return null
    }

    /** 应用编辑内容区自动换行：开=长行换行显示，关=横向滚动（默认）。 */
    private fun applyWordWrap(wrapBtn: MaterialButton) {
        wrapBtn.isChecked = wordWrap
        wrapBtn.contentDescription = if (wordWrap) "关闭自动换行" else "自动换行"
        editor.setWordwrap(wordWrap)
    }

    private fun save() {
        try {
            val content = editor.text.toString()
            val target: File
            if (createMode) {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, "请输入脚本文件名", Toast.LENGTH_SHORT).show()
                    return
                }
                val fname = if (name.endsWith(".sh")) name else "$name.sh"
                val dir = ScriptManager.scriptDir
                dir.mkdirs()
                target = File(dir, fname)
                if (target.exists()) {
                    Toast.makeText(this, "文件已存在: $fname", Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                target = scriptFile ?: return
            }
            target.writeText(content)
            target.setExecutable(true)
            Toast.makeText(this, "已保存 ${target.name}", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 内容是否有改动（相对进入时的初始内容）。 */
    private fun isModified(): Boolean = editor.text.toString() != initialContent

    /**
     * 返回拦截：内容有改动时弹「已修改是否保存」对话框（保存 / 不保存 / 取消），
     * 无改动直接退出。标题栏返回箭头与系统返回键都走这里。
     */
    private fun confirmExit() {
        if (!isModified()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("已修改")
            .setMessage("内容已修改，是否保存？")
            .setPositiveButton("保存") { _, _ -> save() }
            .setNegativeButton("不保存") { _, _ -> finish() }
            .setNeutralButton("取消", null)
            .show()
    }

    /** 把编辑器全文复制到剪贴板（含大 .log 也能一键带出）。 */
    private fun copyAll() {
        val text = editor.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("content", text))
        Toast.makeText(this, "已复制全部（${text.length} 字符）", Toast.LENGTH_SHORT).show()
    }
}
