package com.codetool.app

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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

    private lateinit var etName: TextInputEditText
    private lateinit var etDesc: TextInputEditText
    private lateinit var etBody: CodeEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_skill_editor)

        val toolbar = findViewById<Toolbar>(R.id.skillToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etName = findViewById(R.id.etSkillName)
        etDesc = findViewById(R.id.etSkillDesc)
        etBody = findViewById(R.id.etSkillBody)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val desc = intent.getStringExtra(EXTRA_DESC).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        if (name.isNotEmpty()) {
            etName.setText(name)
            etName.isEnabled = false   // name = 目录名，编辑时不改
            title = "编辑 Skill"
        } else {
            title = "新增 Skill"
        }
        etDesc.setText(desc)
        // sora-editor 的 setText 在布局完成前调用偶发不渲染，post 到下一帧确保正文显示。
        etBody.post { etBody.setText(body) }

        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_skill_save) { saveAndFinish(); true } else false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun saveAndFinish() {
        val name = etName.text?.toString()?.trim().orEmpty()
        val desc = etDesc.text?.toString()?.trim().orEmpty()
        val body = etBody.text.toString()
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
}
