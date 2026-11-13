package com.codetool.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志：把关键日志（含终端会话错误、异常堆栈）写到
 * filesDir/logs/codetool.log，供 adb `run-as` 直接拉取排查，避免依赖截图。
 *
 * 用法：FileLog.e(ctx, "TermSessionMgr", "消息", e)
 * 单文件追加，超过 2MB 轮转到 .1（保留上一份）。
 */
object FileLog {

    private const val TAG = "FileLog"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    private fun file(ctx: Context): File {
        val f = logFile ?: run {
            val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
            val nf = File(dir, "codetool.log")
            if (nf.exists() && nf.length() > MAX_BYTES) {
                val old = File(dir, "codetool.log.1")
                nf.renameTo(old)
            }
            logFile = nf
            nf
        }
        return f
    }

    /** 写一行普通日志。 */
    @JvmStatic
    fun i(ctx: Context, tag: String, msg: String) = write(ctx, "I", tag, msg, null)

    /** 写一行警告日志。 */
    @JvmStatic
    fun w(ctx: Context, tag: String, msg: String) = write(ctx, "W", tag, msg, null)

    /** 写一行错误日志，可带异常堆栈。 */
    @JvmStatic
    fun e(ctx: Context, tag: String, msg: String, throwable: Throwable? = null) {
        write(ctx, "E", tag, msg, throwable)
    }

    private fun write(ctx: Context, level: String, tag: String, msg: String, throwable: Throwable?) {
        val sb = StringBuilder()
        sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag)
            .append(": ").append(msg).append('\n')
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw)
        }
        try {
            // 写私有（供 run-as 读取，历史兼容）
            val f = file(ctx)
            synchronized(f) {
                f.appendText(sb.toString())
            }
            // 镜像到外部目录（文件管理器可见）：Android/data/<pkg>/files/logs/codetool.log
            val ext = ctx.getExternalFilesDir("logs")
            if (ext != null) {
                ext.mkdirs()
                val extF = File(ext, "codetool.log")
                synchronized(extF) {
                    extF.appendText(sb.toString())
                }
            }
        } catch (t: Throwable) {
            // 日志失败不影响主流程
            Log.e(TAG, "write log failed", t)
        }
    }
}
