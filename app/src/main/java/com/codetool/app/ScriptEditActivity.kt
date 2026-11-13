package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.codetool.app.script.ScriptManager
import com.codetool.app.theme.ThemeManager
import io.github.rosemoe.sora.widget.CodeEditor
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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
        findViewById<MaterialButton>(R.id.saveBtn).setOnClickListener { save() }
        toolbar.setNavigationOnClickListener { finish() }

        // 标题栏「复制全部」：把编辑器全文复制到剪贴板，方便把 .log 等内容一键带出
        toolbar.inflateMenu(R.menu.menu_script_edit)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy_all -> { copyAll(); true }
                else -> false
            }
        }

        // 状态栏 inset 由 AppBarLayout(fitsSystemWindows) 自动消费；
        // 这里只处理底部导航条 inset 给保存按钮，避免被手势条遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.saveBtn)) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom)
            insets
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        createMode = intent.getBooleanExtra(EXTRA_CREATE, false)

        if (createMode) {
            toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "新建脚本"
            nameLayout.visibility = android.view.View.VISIBLE
            // 协议头模板
            editor.setText("#!/bin/sh\n#codetool: \n#codetool-desc: \n\n")
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
            toolbar.title = "编辑: ${f.name}"
            editor.setText(runCatching { f.readText() }.getOrDefault(""))
        }
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
