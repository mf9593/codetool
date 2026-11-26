package com.codetool.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 构建环境包下载服务：前台通知 + PARTIAL_WAKE_LOCK，锁屏也能继续下。
 * - 支持排队（多个包依次下）
 * - 每个包支持多个候选 URL（配置仓库 → 上游兜底）
 * - 先写 `data/<asset>.part`，校验大小后再改名，避免半截文件被安装脚本误用
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIF_ID = 1003

        const val ACTION_START = "com.codetool.app.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.codetool.app.DOWNLOAD_CANCEL"
        const val ACTION_CANCEL_ONE = "com.codetool.app.DOWNLOAD_CANCEL_ONE"

        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_URLS = "urls"

        const val BROADCAST_PROGRESS = "com.codetool.app.DOWNLOAD_PROGRESS"
        const val EXTRA_PROGRESS_ID = "id"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CANCELLED = "cancelled"
        const val EXTRA_DOWNLOADED = "downloaded"
        const val EXTRA_TOTAL = "total"

        @Volatile
        var running = false
            private set

        /** 正在排队/下载的任务 id（页面恢复时用来清理错过广播的残留状态）。 */
        private val activeIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun isActive(id: String): Boolean = activeIds.contains(id)

        /** 加入下载队列。urls 按顺序尝试（第一个失败自动试下一个）。 */
        fun start(ctx: Context, id: String, label: String, filename: String, urls: List<String>) {
            if (urls.isEmpty()) return
            val i = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_FILENAME, filename)
                .putExtra(EXTRA_URLS, urls.toTypedArray())
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        fun cancelAll(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
            } catch (e: Exception) {
                Log.w(TAG, "cancelAll failed: ${e.message}")
            }
        }

        /** 取消单个任务（正在下载或排队中都支持）。 */
        fun cancelOne(ctx: Context, id: String) {
            try {
                ctx.startService(
                    Intent(ctx, DownloadService::class.java)
                        .setAction(ACTION_CANCEL_ONE)
                        .putExtra(EXTRA_ID, id)
                )
            } catch (e: Exception) {
                Log.w(TAG, "cancelOne failed: ${e.message}")
            }
        }
    }

    private data class Task(val id: String, val label: String, val filename: String, val urls: List<String>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Task>()
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var cancelled = false

    /** 当前正在下载的任务 id（用于单包取消判断）。 */
    @Volatile
    private var currentId: String? = null

    /** 已请求单独取消的任务 id；下载循环里检查后退出。 */
    private val cancelledIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled = true
                queue.clear()
                worker?.cancel()
                endForeground()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ONE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                cancelledIds.add(id)
                // ConcurrentLinkedQueue 的迭代器不支持 remove，逐个 remove(Object) 安全删除
                var removed = false
                for (t in queue) {
                    if (t.id == id && queue.remove(t)) removed = true
                }
                // 已从队列移除的，不用再留标记（没有读取循环会检查它）
                if (removed) cancelledIds.remove(id)
                activeIds.remove(id)
                if (queue.isEmpty() && currentId == null) endForeground()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val label = intent.getStringExtra(EXTRA_LABEL) ?: id
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                val urls = intent.getStringArrayExtra(EXTRA_URLS)?.toList().orEmpty()
                if (urls.isEmpty()) return START_NOT_STICKY
                cancelled = false
                cancelledIds.remove(id)
                activeIds.add(id)
                queue.add(Task(id, label, filename, urls))
                startForeground(NOTIF_ID, buildNotification("准备下载…", 0, true))
                running = true
                acquireWakeLock()
                if (worker?.isActive != true) worker = scope.launch { drain() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        try {
            while (!cancelled) {
                val task = queue.poll() ?: break
                currentId = task.id
                if (cancelledIds.contains(task.id)) {
                    cancelledIds.remove(task.id)
                    activeIds.remove(task.id)
                    send(task.id, -1, cancelled = true)
                    currentId = null
                    continue
                }
                when (download(task)) {
                    true -> send(task.id, 100, done = true)
                    false -> send(task.id, -1, error = "下载失败（仓库/镜像不可用或网络异常）")
                    null -> send(task.id, -1, cancelled = true)
                }
                cancelledIds.remove(task.id)
                activeIds.remove(task.id)
                currentId = null
            }
        } finally {
            currentId = null
            cancelledIds.clear()
            activeIds.clear()
            running = false
            queue.clear()
            endForeground()
            releaseWakeLock()
        }
    }

    /** true=成功 false=失败 null=取消 */
    private suspend fun download(task: Task): Boolean? {
        val dir = BuildPackageCatalog.dataDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "mkdirs failed: ${dir.path}")
            return false
        }
        val part = File(dir, task.filename + ".part")
        val out = File(dir, task.filename)
        for ((index, url) in task.urls.withIndex()) {
            if (cancelled || cancelledIds.contains(task.id)) {
                part.delete()
                return null
            }
            updateNotification("正在下载 ${task.label}…", 0, true)
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CodeTool-Downloader")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "HTTP $code: $url")
                    conn.disconnect()
                    continue
                }
                val total = conn.contentLengthLong
                var read = 0L
                var lastPct = -2
                var lastMs = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancelled && !cancelledIds.contains(task.id)) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            val pct = if (total > 0) ((read * 100) / total).toInt() else -1
                            if (pct != lastPct || now - lastMs >= 1000) {
                                lastPct = pct
                                lastMs = now
                                send(task.id, pct, downloaded = read, total = total)
                                val sizeText = if (total > 0)
                                    "${BuildPackageCatalog.humanSize(read)}/${BuildPackageCatalog.humanSize(total)}"
                                else BuildPackageCatalog.humanSize(read)
                                updateNotification(
                                    "正在下载 ${task.label}… " +
                                        (if (pct >= 0) "$pct% · " else "") + sizeText,
                                    pct, pct < 0
                                )
                            }
                        }
                    }
                }
                conn.disconnect()
                if (cancelled || cancelledIds.contains(task.id)) {
                    part.delete()
                    return null
                }
                if (total > 0 && part.length() != total) {
                    Log.w(TAG, "size mismatch ${part.length()} != $total: $url")
                    part.delete()
                    continue
                }
                if (part.length() <= 0) {
                    part.delete()
                    continue
                }
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                return true
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                Log.w(TAG, "download failed #$index: $url : ${e.message}")
            }
        }
        return false
    }

    private fun send(
        id: String, percent: Int, done: Boolean = false, error: String? = null,
        downloaded: Long = 0, total: Long = 0, cancelled: Boolean = false
    ) {
        val i = Intent(BROADCAST_PROGRESS)
            .setPackage(packageName)
            .putExtra(EXTRA_PROGRESS_ID, id)
            .putExtra(EXTRA_PERCENT, percent)
            .putExtra(EXTRA_DONE, done)
            .putExtra(EXTRA_DOWNLOADED, downloaded)
            .putExtra(EXTRA_TOTAL, total)
        if (error != null) i.putExtra(EXTRA_ERROR, error)
        if (cancelled) i.putExtra(EXTRA_CANCELLED, true)
        sendBroadcast(i)
    }

    private fun updateNotification(text: String, percent: Int, indeterminate: Boolean) {
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, percent, indeterminate))
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CodeToolApp.CHANNEL_ROOTFS)
            .setContentTitle("下载构建环境")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (indeterminate || percent < 0) {
            b.setProgress(0, 0, true)
        } else {
            b.setProgress(100, percent.coerceIn(0, 100), false)
        }
        return b.build()
    }

    private fun endForeground() {
        running = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codetool:download")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        } catch (e: Exception) {
            Log.w(TAG, "wakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
