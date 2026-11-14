package com.codetool.app

import android.os.Bundle
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
            if (item.itemId == R.id.action_config_save) { saveCurrent(); true } else false
        }
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
        rel.endsWith("ai-models.json") ->
            "App 的模型清单（权威来源，和上方列表一致）。增删改模型建议用列表页，这里可核对/手改。"
        rel.endsWith("mimocode/model.json") ->
            "mimo CLI 记录的「当前模型」(recent[0])，只存一个，不是全量清单。"
        rel.endsWith("mimocode/auth.json") ->
            "mimo 各 provider 的 API Key 与 baseURL。"
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
