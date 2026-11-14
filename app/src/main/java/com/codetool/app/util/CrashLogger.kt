package com.codetool.app.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.codetool.app.CrashReportActivity
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
                // 启动崩溃报告页（独立 :crash 进程），主进程随后退出也不影响它显示
                val ctx = context
                if (ctx != null) {
                    val intent = Intent(ctx, CrashReportActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    ctx.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "show crash page failed", e)
            } finally {
                // 给崩溃页（独立进程）一点启动时间，再结束崩溃的主进程。
                // 不再调用 defaultHandler：避免系统「应用已停止」弹窗和崩溃页打架。
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                android.os.Process.killProcess(android.os.Process.myPid())
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

    /** 最近一次崩溃的文本内容（供 UI 读取）。
     *  崩溃页运行在独立 :crash 进程，内存里的 lastCrashFile 为空，
     *  所以这里直接从 files/crash/ 读时间戳最新的那份崩溃文件。 */
    fun lastCrashText(): String? {
        val ctx = context ?: return null
        val dir = File(ctx.filesDir, "crash")
        val latest = dir.listFiles { f -> f.name.startsWith("crash-") }?.maxByOrNull { it.name }
            ?: return lastCrashFile?.takeIf { it.exists() }?.readText()
        return runCatching { latest.readText() }.getOrNull()
    }
}

/** 版本信息小工具。 */
object BuildInfo {
    fun versionName(ctx: Context): String =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
}
