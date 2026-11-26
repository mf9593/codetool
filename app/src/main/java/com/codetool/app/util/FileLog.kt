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
    // 日志会从终端 / 主线程 / 后台任务并发写入；SimpleDateFormat 非线程安全，
    // 时间戳写坏会让日志按块解析失效，所以用 ThreadLocal。
    // 时间戳格式由「日志设置 → 显示日期/毫秒」控制（4 种组合），按 pattern 缓存，设置变更自动重建。
    private class FmtHolder {
        var pattern = ""
        var sdf: SimpleDateFormat? = null
    }
    private val fmt = object : ThreadLocal<FmtHolder>() {
        override fun initialValue() = FmtHolder()
    }

    /** 当前时间戳格式：日志文件始终写完整时间戳（日期 + 毫秒）。
     *  「显示日期/毫秒」只影响日志页查看时的显示（见 TasksFragment.formatLogForDisplay），
     *  不再改变写入格式——时间筛选与跨天日志都依赖完整日期。 */
    private fun fmt(): SimpleDateFormat {
        val pattern = "yyyy-MM-dd HH:mm:ss.SSS"
        val h = fmt.get()
        if (h.sdf == null || h.pattern != pattern) {
            h.pattern = pattern
            h.sdf = SimpleDateFormat(pattern, Locale.US)
        }
        return h.sdf!!
    }

    @Volatile
    private var logFile: File? = null

    /**
     * 镜像日志：容器看不到 app 私有目录，但能看 bind mount 的
     * `dataDir/share`（容器内 `/usr/share/tawc`）。容器内模型直接读这里。
     */
    @Volatile
    private var mirrorLogFile: File? = null

    @Synchronized
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

    @Synchronized
    private fun mirrorFile(ctx: Context): File {
        val f = mirrorLogFile ?: run {
            val dir = File(ctx.dataDir, "share/logs").apply { mkdirs() }
            val nf = File(dir, "codetool.log")
            if (nf.exists() && nf.length() > MAX_BYTES) {
                nf.renameTo(File(dir, "codetool.log.1"))
            }
            // 升级后第一次镜像：把已有私有日志补一份，模型不用等新日志才看得到
            if (!nf.exists()) {
                runCatching {
                    val priv = file(ctx)
                    if (priv.isFile && priv.length() > 0) priv.copyTo(nf, overwrite = false)
                }
            }
            mirrorLogFile = nf
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
        sb.append(fmt().format(Date())).append(' ').append(level).append('/').append(tag)
            .append(": ").append(msg).append('\n')
        if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sb.append(sw)
        }
        val text = sb.toString()
        // 三个目标各自独立：外置存储不可用 / 共享目录没建好时，不能连累其它镜像，
        // 尤其不能让「容器内模型能读日志」这个通道因为外部存储失败而断掉。
        runCatching {
            val f = file(ctx)
            synchronized(f) { f.appendText(text) }
        }.onFailure { Log.e(TAG, "write private log failed", it) }

        runCatching {
            val ext = ctx.getExternalFilesDir("logs")
            if (ext != null) {
                ext.mkdirs()
                val extF = File(ext, "codetool.log")
                synchronized(extF) { extF.appendText(text) }
            }
        }.onFailure { Log.e(TAG, "write external log failed", it) }

        runCatching {
            val mf = mirrorFile(ctx)
            synchronized(mf) { mf.appendText(text) }
        }.onFailure { Log.e(TAG, "write shared log failed", it) }
    }

    /**
     * 清空运行日志（日志页「清空」用）：私有文件、外部镜像、容器共享镜像一起清，
     * 并删掉轮转副本；避免删了私有文件后模型仍能从共享目录读到旧日志。
     */
    fun clear(ctx: Context) {
        runCatching { file(ctx).writeText("") }
        runCatching { File(ctx.filesDir, "logs/codetool.log.1").delete() }
        runCatching { mirrorFile(ctx).writeText("") }
        runCatching { File(ctx.dataDir, "share/logs/codetool.log.1").delete() }
        runCatching {
            val ext = ctx.getExternalFilesDir("logs")
            if (ext != null) {
                File(ext, "codetool.log").writeText("")
                File(ext, "codetool.log.1").delete()
            }
        }
    }
}
