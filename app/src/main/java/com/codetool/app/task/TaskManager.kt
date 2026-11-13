package com.codetool.app.task

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import com.codetool.app.util.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 任务状态。 */
enum class TaskStatus { RUNNING, OK, FAIL }

/** 一个任务（脚本运行 / 命令执行），含输出、错误、日志。 */
data class Task(
    val id: Int,
    val name: String,
    val command: String,
    val status: TaskStatus,
    val output: String,
    val error: String,
    val log: String,
    val timestamp: Long,
    /** 关联引用（如脚本文件绝对路径），用于脚本库联动状态。 */
    val ref: String? = null
)

/**
 * 任务管理：在容器里执行脚本/命令，记录输出、错误和日志。
 * 任务页显示错误和日志。线程安全，任务列表通过 StateFlow 暴露给 UI。
 */
object TaskManager {

    private val counter = AtomicInteger(0)
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks

    /** 在后台线程执行命令（tawcroot exec），生成任务并更新状态。 */
    fun run(context: Context, name: String, command: String, vararg args: String) {
        val ctx = context.applicationContext
        val id = counter.incrementAndGet()
        val start = System.currentTimeMillis()
        add(Task(id, name, command, TaskStatus.RUNNING, "", "", "开始执行: $command ${args.joinToString(" ")}", start))

        // 后台执行
        Thread {
            try {
                val rootfs = RootfsManager.get(ctx).ensureExtractedSync()
                val runtime = TawcrootRuntime.get(ctx)
                val proc = runtime.exec(rootfs, command, *args)
                val out = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                val log = buildString {
                    appendLine("命令: $command ${args.joinToString(" ")}")
                    appendLine("退出码: $code")
                    append("输出:\n").append(out.trimEnd())
                }
                FileLog.i(ctx, "Task", log)
                if (code == 0) {
                    update(id) { it.copy(status = TaskStatus.OK, output = out, log = log) }
                } else {
                    val err = out.ifBlank { "exit=$code" }
                    update(id) { it.copy(status = TaskStatus.FAIL, error = err, output = out, log = log) }
                    FileLog.e(ctx, "Task", "失败($code): $name $err")
                }
            } catch (e: Exception) {
                FileLog.e(ctx, "Task", "执行异常: $name ${e.message}", e)
                update(id) {
                    it.copy(status = TaskStatus.FAIL, error = e.message ?: "未知错误",
                        log = it.log + "\n异常: ${e.message}")
                }
            }
        }.start()
    }

    /** 运行脚本：把脚本复制进容器 /root/.codetool-scripts/ 再执行，参数按序传 $1/$2...。 */
    fun runScript(context: Context, scriptFile: File, scriptName: String, vararg args: String) {
        val ctx = context.applicationContext
        Thread {
            try {
                val rootfs = RootfsManager.get(ctx).ensureExtractedSync()
                val scriptDir = File(rootfs, "root/.codetool-scripts")
                scriptDir.mkdirs()
                val dest = File(scriptDir, scriptFile.name)
                scriptFile.copyTo(dest, overwrite = true)
                dest.setExecutable(true)
                // ★ 容器内路径（chroot 后 /root/...），不能用 Android 绝对路径
                val containerPath = "/root/.codetool-scripts/${scriptFile.name}"
                run(ctx, "脚本: $scriptName", "/bin/sh", containerPath, *args)
            } catch (e: Exception) {
                FileLog.e(ctx, "Task", "运行脚本失败: $scriptName ${e.message}", e)
            }
        }.start()
    }

    fun clear() { _tasks.value = emptyList() }

    private fun add(t: Task) { _tasks.value = _tasks.value + t }

    private fun update(id: Int, transform: (Task) -> Task) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }
}
