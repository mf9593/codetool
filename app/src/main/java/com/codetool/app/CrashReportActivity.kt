package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codetool.app.util.CrashLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * 崩溃报告页：捕获未捕获异常后展示完整堆栈，避免「闪退无痕」。
 * 由 CrashLogger 在崩溃时启动（NEW_TASK|CLEAR_TASK），读取已落盘的崩溃日志显示。
 * 提供「复制日志」反馈给开发者、「重启应用」重新进入主界面。
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val logText = findViewById<MaterialTextView>(R.id.crashLogText)
        val content = CrashLogger.lastCrashText() ?: "（未读到崩溃详情，但已记录时间）"
        logText.text = content

        findViewById<MaterialButton>(R.id.btnCrashCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CodeTool 崩溃日志", content))
            Toast.makeText(this, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnCrashRestart).setOnClickListener {
            // 清掉崩溃页任务栈，重开主界面
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(i)
            finish()
        }
    }
}
