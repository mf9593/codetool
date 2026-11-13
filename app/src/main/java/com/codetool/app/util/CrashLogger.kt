package com.codetool.app.util

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 崩溃日志器。
 *
 * 在 App 启动时设置默认未捕获异常处理器：把崩溃堆栈写入
 * filesDir/crash/ 下的时间戳文件，方便排查闪退。
 *
 * 用法：CodeToolApp.onCreate 里调用 [CrashLogger.init]。
 */
object CrashLogger {
    private const val TAG = "CrashLogger"

    @Volatile private var initialized = false
    @Volatile private var context: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /** 崩溃后置文件（供 UI 显示 / 分享） */
    @Volatile
    var lastCrashFile: File? = null
        private set

    fun init(app: Context) {
        if (initialized) return
        initialized = true
        context = app.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = writeCrash(thread, throwable)
                Log.e(TAG, "crash captured -> ${file?.absolutePath}", throwable)
            } catch (e: Exception) {
                Log.e(TAG, "crash logger failed", e)
            } finally {
                // 交给系统默认处理器（仍会崩溃退出，但日志已落盘）
                defaultHandler?.uncaughtException(thread, throwable)
                // 若没有默认处理器，自己退出
                exitProcess(2)
            }
        }
    }

    /** 写入崩溃日志，返回文件（崩溃线程安全）。 */
    private fun writeCrash(thread: Thread, throwable: Throwable): File? {
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "crash").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$ts.txt")
        try {
            file.writeText(buildString {
                appendLine("=== CodeTool 崩溃日志 ===")
                appendLine("时间: $ts")
                appendLine("线程: ${thread.name} (${thread.id})")
                appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("App: ${ctx.packageName} v${BuildInfo.versionName(ctx)}")
                appendLine()
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                appendLine(sw.toString())
                // 附加上下文信息
                appendLine()
                appendLine("=== 关键信息 ===")
                runCatching {
                    appendLine("rootfs ready: ${com.codetool.app.runtime.RootfsManager.get(ctx).isReady()}")
                    appendLine("native lib dir: ${ctx.applicationInfo.nativeLibraryDir}")
                }
            })
            lastCrashFile = file
            return file
        } catch (e: Exception) {
            Log.e(TAG, "write crash failed", e)
            return null
        }
    }

    /** 最近一次崩溃的文本内容（供 UI 读取）。 */
    fun lastCrashText(): String? = lastCrashFile?.takeIf { it.exists() }?.readText()
}

/** 版本信息小工具。 */
object BuildInfo {
    fun versionName(ctx: Context): String =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
}
