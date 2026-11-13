package com.codetool.app.runtime

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * rootfs 备份 / 恢复。
 *
 * rootfs 里含大量符号链接（/bin、/usr/bin/google-chrome 等），不能用 Kotlin
 * copyRecursively（会跟随/破坏链接）。改用宿主 toybox 的 tar 原样归档，
 * 保留符号链接与权限位。归档不压缩（rootfs 解压后约 700MB，压缩耗时长且
 * toybox tar 对 -z/-J 支持不稳）。
 *
 * 备份目录：Download/codetool/backup/（公共目录，用户可在文件管理直接看到/拷走）
 *
 * 进度：tar 不输出进度，故用「已写/已解字节 ÷ 预估总字节」定时轮询（1.2s）
 * 得到 0..99 的确定百分比；拿不到总字节时回退为不确定进度（-1，UI 转圈）。
 * 进度通过 [onProgress] 回调（percent, message）上报，由前台服务转发广播+通知。
 *
 * 取消：[cancelCurrent] 置标志并 destroy 当前 tar 进程，tar 结束走原有失败清理
 * （备份删半成品 / 恢复自动回滚），不会留下坏 rootfs。
 */
object RootfsBackupManager {

    fun backupDir(): File = File(
        Environment.getExternalStorageDirectory(), "Download/codetool/backup"
    ).apply { mkdirs() }

    fun newBackupFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return File(backupDir(), "rootfs-$ts.tar")
    }

    fun listBackups(): List<File> = backupDir().listFiles { f ->
        f.isFile && f.name.startsWith("rootfs-") && f.name.endsWith(".tar")
    }?.sortedByDescending { it.lastModified() } ?: emptyList()

    @Volatile private var currentProcess: Process? = null
    @Volatile private var cancelled = false

    /** 通知栏/页面「取消」调用。destroy 进程后 runTar 自然返回失败 → 触发清理或回滚。 */
    fun cancelCurrent() {
        cancelled = true
        runCatching { currentProcess?.destroy() }
    }

    /**
     * 执行 tar。stderr 合并进 stdout（readText 阻塞到进程结束，进度由独立 poller 线程上报）。
     * 返回 Pair(成功, 输出)。
     */
    private fun runTar(args: Array<String>): Pair<Boolean, String> = try {
        val p = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        currentProcess = p
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        currentProcess = null
        (code == 0 && !cancelled) to out.trim()
    } catch (e: Exception) {
        currentProcess = null
        false to (e.message ?: "tar 执行异常")
    }

    /** 用 toybox du -sk 快速取目录占用字节；失败返回 0（→ 不确定进度）。 */
    private fun duBytes(path: String): Long = try {
        val p = ProcessBuilder(listOf("du", "-sk", path)).redirectErrorStream(true).start()
        val first = p.inputStream.bufferedReader().use { it.readLine() }
        p.waitFor()
        first?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024) ?: 0L
    } catch (_: Exception) {
        0L
    }

    /**
     * 进度轮询线程：定时按「当前字节 / 总字节」算百分比并回调。
     * 总字节为 0（du 不可用）时只上报一次 -1，之后静默等待，UI 显示转圈。
     */
    private class ProgressPoller(
        private val totalBytes: Long,
        private val currentBytes: () -> Long,
        private val label: String,
        private val onProgress: (Int, String) -> Unit,
    ) : Thread("rootfs-backup-progress") {
        @Volatile var stop = false

        override fun run() {
            onProgress(if (totalBytes > 0) 0 else -1, label)
            while (!stop) {
                try {
                    sleep(1200)
                } catch (_: InterruptedException) {
                    break
                }
                if (stop) break
                if (totalBytes <= 0) continue
                val cur = currentBytes()
                // 目录还没建好 / du 瞬时不可用 → 跳过这一帧保持上一帧进度，
                // 避免进度条在「确定百分比」与「转圈」之间来回闪烁。
                if (cur > 0) {
                    onProgress((cur * 100 / totalBytes).coerceIn(0, 99).toInt(), label)
                }
            }
        }

        fun finish() {
            stop = true
            interrupt()
        }
    }

    /**
     * 备份：tar -cf <dest> -C <rootfsParent> <rootfsDirName>
     * 用 -C + 相对目录名，归档里第一层就是 ubuntu-noble-aarch64/，恢复时对称。
     * 进度分子 = 归档文件当前大小（O(1)，极快），分母 = rootfs 占用字节（du 一次）。
     */
    fun backup(
        context: Context,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> {
        cancelled = false
        val root = RootfsManager.get(context.applicationContext).containerRoot
        if (!root.isDirectory) return false to "rootfs 未就绪（还没解压）"
        val dir = backupDir()
        if (!dir.exists() && !dir.mkdirs()) return false to "无法创建备份目录 ${dir.absolutePath}"
        val dest = newBackupFile()
        val label = "正在打包 ${dest.name}…"
        val total = duBytes(root.absolutePath)
        val poller = ProgressPoller(total, { dest.length() }, label, onProgress).also { it.start() }
        val (ok, out) = runTar(arrayOf("tar", "-cf", dest.absolutePath, "-C", root.parent, root.name))
        poller.finish()

        if (cancelled || !ok) {
            dest.delete()   // 删掉不完整归档，避免出现在列表里
            return false to if (cancelled) "备份已取消" else "备份失败：$out"
        }
        val msg = "备份完成：${dest.name} (${dest.length() / 1048576} MB)"
        onProgress(100, msg)
        return true to dest.absolutePath
    }

    /**
     * 恢复：先把现有 rootfs 暂存为 .bak，再 tar -xf 解到 rootfsParent，
     * 成功后补 marker + 删 .bak；失败则把 .bak 改回来，保证不弄坏系统。
     * 进度分母 = .bak 占用字节（≈ 解出后字节），分子 = 解出目录当前字节（du，低频）。
     */
    fun restore(
        context: Context,
        backup: File,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> {
        cancelled = false
        val root = RootfsManager.get(context.applicationContext).containerRoot
        val parent = root.parentFile ?: return false to "rootfs 父目录异常"
        if (!backup.isFile) return false to "备份文件不存在"
        val bak = File(parent, "${root.name}.bak")
        if (bak.exists()) deleteRecursively(bak)   // 清掉上次残留
        onProgress(0, "暂存当前 rootfs…")
        if (root.exists() && !root.renameTo(bak)) return false to "无法暂存当前 rootfs（可能有进程占用）"

        val label = "正在恢复 ${backup.name}…"
        val total = duBytes(bak.absolutePath).takeIf { it > 0 } ?: backup.length()
        val poller = ProgressPoller(
            total,
            { duBytes(File(parent, root.name).absolutePath) },
            label, onProgress,
        ).also { it.start() }
        val (ok, out) = runTar(arrayOf("tar", "-xf", backup.absolutePath, "-C", parent.absolutePath))
        poller.finish()

        val extracted = File(parent, root.name)
        if (ok && extracted.isDirectory) {
            // 确保 marker 存在（备份里一般有；没有也补上，避免首页判定成未解压）
            val marker = File(extracted, ".codetool_ready")
            if (!marker.exists()) marker.writeText("ok")
            RootfsManager.get(context.applicationContext).refreshState()
            deleteRecursively(bak)
            onProgress(100, "恢复完成")
            return true to "已恢复：${backup.name}"
        }
        // 失败（含取消）回滚：删半成品，把 .bak 改回来
        if (extracted.exists()) deleteRecursively(extracted)
        if (bak.exists()) bak.renameTo(root)
        return false to if (cancelled) "恢复已取消（已回滚）" else "恢复失败，已回滚：$out"
    }

    fun deleteBackup(f: File): Boolean = f.delete()

    /** 用宿主 rm -rf 删目录树（rootfs 几万文件，Java 递归删除太慢；且不跟随符号链接）。 */
    private fun deleteRecursively(f: File) {
        try {
            ProcessBuilder(listOf("rm", "-rf", f.absolutePath))
                .redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {
            f.deleteRecursively()
        }
    }
}
