package com.codetool.app

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiModelManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var etContent: TextInputEditText
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
        val content = etContent.text?.toString().orEmpty()
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
