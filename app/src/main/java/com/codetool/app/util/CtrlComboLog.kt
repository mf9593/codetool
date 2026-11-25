package com.codetool.app.util

import android.content.Context
import java.io.File

/**
 * Ctrl 组合键诊断日志：除写入应用日志外，额外追加到公共目录
 * /storage/emulated/0/Download/ctrlcombo.log，便于在外部直接读取定位。
 */
object CtrlComboLog {
    @JvmStatic
    fun i(context: Context, msg: String) {
        FileLog.i(context, "CtrlCombo", msg)
        appendPublic(context, msg)
    }

    @JvmStatic
    fun w(context: Context, msg: String) {
        FileLog.w(context, "CtrlCombo", msg)
        appendPublic(context, "WARN: $msg")
    }

    private fun appendPublic(context: Context, msg: String) {
        runCatching {
            // 安全：写到应用外部私有目录（不再写公共 Download 根 + 全局可读/可写），
            // 防其它应用读取终端 Ctrl 组合键信息或篡改/删除日志。
            val dir = context.getExternalFilesDir("logs") ?: return@runCatching
            val f = File(dir, "ctrlcombo.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        }
    }
}
