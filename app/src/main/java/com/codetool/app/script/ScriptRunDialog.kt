package com.codetool.app.script

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.codetool.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 脚本运行弹窗：显示脚本标题/说明 + 参数输入框，点「运行」才真正执行（防误触）。
 * 参数值按 `#codetool-param:` 顺序作为 $1/$2... 传给脚本。
 *
 * - 输入框用 OutlinedBox（有明确边框，浅色/深色主题都可见）；
 * - 点「运行」后把本次参数按脚本路径记住，下次打开自动填入；
 * - 脚本库与首页收藏共用。
 */
object ScriptRunDialog {

    fun show(context: Context, s: ScriptInfo, onRun: (Array<String>) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_script_run, null, false)
        val desc = view.findViewById<TextView>(R.id.scriptRunDesc)
        val container = view.findViewById<LinearLayout>(R.id.scriptRunParams)
        val hint = view.findViewById<TextView>(R.id.scriptRunHint)
        val inputs = mutableListOf<TextInputEditText>()

        if (s.params.isEmpty()) {
            desc.text = if (s.desc.isNotEmpty())
                "${s.desc}\n\n该脚本无需参数，点击「运行」执行"
            else
                "该脚本无需参数，点击「运行」执行"
            hint.visibility = View.GONE
        } else {
            if (s.desc.isEmpty()) {
                desc.visibility = View.GONE
            } else {
                desc.text = s.desc
            }
            val saved = ScriptManager.savedParams(context, s.file.absolutePath)
            s.params.forEachIndexed { i, label ->
                val item = LayoutInflater.from(context)
                    .inflate(R.layout.item_script_param, container, false)
                item.findViewById<TextInputLayout>(R.id.scriptParamLayout).hint = label
                val et = item.findViewById<TextInputEditText>(R.id.scriptParamInput)
                if (i < saved.size) et.setText(saved[i])
                et.setSelection(et.text?.length ?: 0)
                container.addView(item)
                inputs.add(et)
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(s.name)
            .setView(view)
            .setPositiveButton("运行") { _, _ ->
                val args = inputs.map { it.text?.toString()?.trim().orEmpty() }.toTypedArray()
                if (s.params.isNotEmpty()) {
                    ScriptManager.saveParams(context, s.file.absolutePath, args)
                }
                onRun(args)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
