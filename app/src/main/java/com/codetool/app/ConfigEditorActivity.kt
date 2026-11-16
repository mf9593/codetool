package com.codetool.app

import android.os.Bundle
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import org.json.JSONTokener
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiModelManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textview.MaterialTextView
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 配置文件编辑器（全屏页）：大空间直接编辑原始 JSON/JSONC，比弹窗好编辑。
 * 顶部下拉选文件，工具栏「保存」写回（写前备份 .codetool.bak）。
 * 每个文件带说明，避免「内容跟 AI 模型对不上」的困惑（CLI 配置只存当前模型，非全量清单）。
 */
class ConfigEditorActivity : AppCompatActivity() {

    private lateinit var spFile: Spinner
    private lateinit var etContent: CodeEditor
    private lateinit var hint: MaterialTextView
    private var configs: List<AiModelManager.EditableConfig> = emptyList()
    private var selected = 0
    private var loadedText = ""   // 当前已加载原文，用于判断是否有改动

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.codetool.app.theme.ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_editor)

        val toolbar = findViewById<MaterialToolbar>(R.id.configToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        spFile = findViewById(R.id.spConfigFile)
        etContent = findViewById(R.id.etConfigContent)
        hint = findViewById(R.id.configHint)

        configs = AiModelManager.listEditableConfigs()
        val labels = configs.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFile.adapter = adapter

        selected = 0
        loadFile(0)
        spFile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos != selected) {
                    selected = pos
                    loadFile(pos)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_config_save -> { saveCurrent(); true }
                R.id.action_config_copy -> { copyToClipboard(etContent.text.toString()); true }
                R.id.action_config_help -> { showHelp(); true }
                R.id.action_config_format -> { formatCurrent(); true }
                else -> false
            }
        }
    }

    /** 菜单必须在 onCreateOptionsMenu inflate，否则标题栏按钮不显示（复制/说明/保存）。 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_config_editor, menu)
        return true
    }

    /**
     * 标题栏「格式化」：只对 JSON / JSONC 配置文件显示。
     * 只调整空白缩进，字符串、数字与注释原文一律保留（不做 parse→重序列化，
     * 避免 org.json 丢注释、改数字格式）。
     */
    private fun formatCurrent() {
        val cfg = configs.getOrNull(selected) ?: return
        if (!isJsonConfig(cfg.rel)) {
            Toast.makeText(this, "当前文件不是 JSON 格式", Toast.LENGTH_SHORT).show()
            return
        }
        val text = etContent.text.toString()
        // 语法不合法时不格式化：缩进器按字符走，坏 JSON 会被排得更乱
        validateJson(text, cfg.rel.endsWith(".jsonc"))?.let { err ->
            Toast.makeText(this, "⚠️ $err，请先修正语法再格式化", Toast.LENGTH_LONG).show()
            return
        }
        val formatted = prettyJson(text)
        // 再校验一次，防止格式化器自身引入问题（宁可不动）
        if (validateJson(formatted, cfg.rel.endsWith(".jsonc")) != null) {
            Toast.makeText(this, "格式化失败：结果校验不通过，已保留原文", Toast.LENGTH_LONG).show()
            return
        }
        if (formatted == text) {
            Toast.makeText(this, "已经是格式化状态", Toast.LENGTH_SHORT).show()
            return
        }
        etContent.setText(formatted)
        Toast.makeText(this, "已格式化（内容未改）", Toast.LENGTH_SHORT).show()
    }

    private fun isJsonConfig(rel: String): Boolean =
        rel.endsWith(".json") || rel.endsWith(".jsonc")

    /**
     * 保留字符串 / 数字原文与注释的 JSON(C) 缩进格式化。
     * 规则：{ [ 后换行缩进，} ] 前换行回退，逗号换行，冒号后一个空格；
     * 字符串内与注释内容原样输出，注释位置也保留。
     */
    private fun prettyJson(src: String): String {
        val out = StringBuilder(src.length + 64)
        var i = 0
        var indent = 0
        var inString = false

        fun writeIndent() { repeat(indent) { out.append("  ") } }
        fun skipWsAhead() { while (i < src.length && src[i].isWhitespace()) i++ }

        while (i < src.length) {
            val c = src[i]
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; out.append(c); i++ }

                // 行注释：原样保留，强制换行后继续（否则后续 token 会被注释吃掉）
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') { out.append(src[i]); i++ }
                    if (i < src.length) i++
                    out.append('\n'); writeIndent(); skipWsAhead()
                }

                // 块注释：原样保留
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    out.append("/*"); i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                        out.append(src[i]); i++
                    }
                    if (i + 1 < src.length) { out.append("*/"); i += 2 }
                }

                c == '{' || c == '[' -> {
                    out.append(c)
                    i++
                    skipWsAhead()
                    val close = if (c == '{') '}' else ']'
                    if (i < src.length && src[i] == close) {
                        out.append(close); i++          // 空对象/空数组保持一行
                    } else {
                        indent++
                        out.append('\n'); writeIndent()
                    }
                }

                c == '}' || c == ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
                    out.append('\n'); writeIndent()
                    out.append(c); i++
                }

                c == ',' -> { out.append(','); i++; skipWsAhead(); out.append('\n'); writeIndent() }
                c == ':' -> { out.append(": "); i++; skipWsAhead() }
                c.isWhitespace() -> i++
                else -> { out.append(c); i++ }
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    /** 复制编辑器内容到剪贴板。 */
    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("config", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /** 弹出当前文件的用途说明（标题栏说明按钮）。 */
    private fun showHelp() {
        val cfg = configs.getOrNull(selected) ?: return
        val msg = fileHint(cfg.rel).ifEmpty { "（该文件暂无说明）" }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(cfg.label)
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    private fun loadFile(idx: Int) {
        val cfg = configs.getOrNull(idx) ?: return
        hint.text = fileHint(cfg.rel)
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                AiModelManager.readConfigFile(this@ConfigEditorActivity, cfg.rel)
            }
            loadedText = content ?: "（文件不存在或为空）"
            etContent.setText(loadedText)
            // 先 setText 再设语言（TextMate 语法加载异步，后设置避免清空已渲染正文）
            com.codetool.app.util.TextMateEditor.applyTo(etContent, cfg.rel)
        }
    }

    private fun saveCurrent() {
        val cfg = configs.getOrNull(selected) ?: return
        val content = etContent.text.toString()
        // 保存前 JSON 校验（容错 .jsonc 注释）：语法错误时提示行号，用户可选择返回修正或强制保存。
        validateJson(content, cfg.rel.endsWith(".jsonc"))?.let { err ->
            Toast.makeText(this, "⚠️ $err（仍可强制保存，但 CLI 可能读不了）", Toast.LENGTH_LONG).show()
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AiModelManager.writeConfigFile(this@ConfigEditorActivity, cfg.rel, content)
            }
            if (ok) {
                loadedText = content
                Toast.makeText(this@ConfigEditorActivity, "已保存 ${cfg.label}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ConfigEditorActivity, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 校验 JSON/JSONC 文本，返回 null 表示合法，否则返回带行号的错误描述。
     * .jsonc 容忍行注释与块注释（先剥掉再 parse）；.json 严格要求无注释。
     * 不抛异常，parse 失败即视为语法错误。
     */
    private fun validateJson(text: String, allowComment: Boolean): String? {
        val body = if (allowComment) stripJsonComments(text) else text
        return try {
            JSONTokener(body).nextValue()
            null
        } catch (e: Exception) {
            "JSON 语法错误：${e.message}"
        }
    }

    /** 剥掉 JSONC 的 // 行注释与块注释（不处理字符串内的 //，仅用于校验前的粗剥）。 */
    private fun stripJsonComments(s: String): String {
        val sb = StringBuilder(); var i = 0; var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++; continue
            }
            if (c == '"') { inStr = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { while (i < s.length && s[i] != '\n') i++; continue }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') { i += 2; while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++; i += 2; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** 每个文件的用途说明，澄清「CLI 配置只存当前模型，不是全量清单」。 */
    private fun fileHint(rel: String): String = when {
        rel.endsWith("mimocode/model.json") ->
            "mimo CLI 记录的「当前模型」(recent[0])，只存一个，不是全量清单。"
        rel.endsWith("mimocode/auth.json") ->
            "mimo 各 provider 的 API Key 与 baseURL（缺 key 则模型列表拉不到）。"
        rel.endsWith("mimocode.jsonc") ->
            "mimo 主配置：自定义 provider 的地址在 provider.<id>.options.baseURL；mcp 服务也在这里。"
        rel.endsWith("opencode.json") ->
            "opencode CLI 配置：当前模型 + provider 地址 + env Key。"
        else -> ""
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
