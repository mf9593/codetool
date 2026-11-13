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
        appendPublic(msg)
    }

    @JvmStatic
    fun w(context: Context, msg: String) {
        FileLog.w(context, "CtrlCombo", msg)
        appendPublic("WARN: $msg")
    }

    private fun appendPublic(msg: String) {
        runCatching {
            // 写到 Download 根目录扁平文件，并显式设为全局可读，
            // 否则应用私有 umask 会让外部 shell（不同 uid）读不到。
            val f = File("/storage/emulated/0/Download/ctrlcombo.log")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] $msg\n")
            f.setReadable(true, false)
            f.setWritable(true, false)
        }
    }
}
