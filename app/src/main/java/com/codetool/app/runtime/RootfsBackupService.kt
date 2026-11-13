package com.codetool.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codetool.app.CodeToolApp
import com.codetool.app.MainActivity
import com.codetool.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * rootfs 备份 / 恢复前台服务。
 *
 * tar 打包/解包 700MB+ 几万文件要几十秒到几分钟，放 fragment 协程里一旦锁屏
 * 进程被冻结就会中断、留下坏归档。这里：
 * - 前台服务（dataSync）+ 常驻进度通知，系统不会冻结/回收
 * - PARTIAL_WAKE_LOCK（30 分钟上限自动释放）保证熄屏 CPU 不 deep suspend
 * - 进度广播给首页刷新进度条；通知栏带「取消」action
 * - 静态快照 [snapshot] 让 app 被杀/页面重建后能恢复进度显示
 */
class RootfsBackupService : Service() {

    /** 进度快照，供 UI 在页面重建后恢复进度条显示。 */
    data class Snapshot(val percent: Int, val message: String, val kind: String)

    companion object {
        private const val TAG = "RootfsBackup"
        private const val NOTIF_ID = 1004   // 1001 终端 / 1002 解压 / 1003 保活

        const val ACTION_BACKUP = "com.codetool.action.BACKUP_ROOTFS"
        const val ACTION_RESTORE = "com.codetool.action.RESTORE_ROOTFS"
        const val ACTION_CANCEL = "com.codetool.action.CANCEL_BACKUP"
        const val EXTRA_BACKUP_PATH = "backup_path"

        const val BROADCAST = "com.codetool.ROOTFS_BACKUP_PROGRESS"
        const val EXTRA_PERCENT = "percent"      // -1 = 不确定（转圈）
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"
        const val EXTRA_KIND = "kind"            // "backup" | "restore"

        @Volatile var running = false; private set

        /** UI 重建后恢复进度显示的快照。lastPercent = -2 表示「无进行中任务」。 */
        @Volatile var lastPercent = -2
        @Volatile var lastMessage = ""
        @Volatile var lastKind = ""

        fun snapshot(): Snapshot? =
            if (running) Snapshot(lastPercent, lastMessage, lastKind) else null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var kind = "backup"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // destroy tar 进程 → runTar 返回失败 → Manager 清理/回滚 → publish(error) → 收尾
                RootfsBackupManager.cancelCurrent()
                return START_NOT_STICKY
            }
            ACTION_BACKUP, ACTION_RESTORE -> {
                if (running) return START_NOT_STICKY
                running = true
                kind = if (intent.action == ACTION_RESTORE) "restore" else "backup"
                lastPercent = 0
                lastMessage = if (kind == "backup") "正在准备备份…" else "正在准备恢复…"
                lastKind = kind

                acquireWakeLock()
                startForegroundSafe(buildNotification(lastMessage, 0, indeterminate = true))

                val path = intent.getStringExtra(EXTRA_BACKUP_PATH).orEmpty()
                job = scope.launch {
                    try {
                        val result = if (kind == "backup") {
                            RootfsBackupManager.backup(applicationContext) { pct, msg ->
                                publish(pct, msg)
                            }
                        } else {
                            RootfsBackupManager.restore(applicationContext, File(path)) { pct, msg ->
                                publish(pct, msg)
                            }
                        }
                        val (ok, msg) = result
                        if (ok) publish(100, msg, done = true)
                        else publish(100, "", error = msg)
                    } catch (e: Exception) {
                        Log.e(TAG, "backup/restore failed", e)
                        publish(100, "", error = e.message ?: "unknown")
                    } finally {
                        running = false
                        lastPercent = -2
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 上报进度：更新静态快照 + 发广播 + 刷通知。
     * 去重（同 percent+message 不重复刷），不确定模式下每 1.2s 的 -1 只刷一次。
     */
    private fun publish(percent: Int, message: String, done: Boolean = false, error: String? = null) {
        if (!done && error == null && percent == lastPercent && message == lastMessage) return
        lastPercent = percent
        lastMessage = message

        sendBroadcast(
            Intent(BROADCAST).setPackage(packageName)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_DONE, done)
                .apply { if (error != null) putExtra(EXTRA_ERROR, error) }
        )

        val op = if (kind == "backup") "备份" else "恢复"
        val text = when {
            done -> message.ifEmpty { "$op 完成" }
            error != null -> "$op 失败：$error"
            else -> message
        }
        // 完成/失败不显示进度条；进行中 percent<0 显示转圈
        val showProgress = !done && error == null
        notify(
            buildNotification(
                text,
                if (showProgress) percent.coerceAtLeast(0) else -1,
                indeterminate = showProgress && percent < 0,
            )
        )
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codetool:backup_cpu").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)   // 30 分钟上限，异常时系统也会自动释放
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock acquire failed: ${e.message}")
        }
    }

    private fun startForegroundSafe(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun notify(n: Notification) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, n)
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
            .setContentTitle("CodeTool · rootfs 备份")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent >= 0) b.setProgress(100, percent, indeterminate)
        if (running) {
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, RootfsBackupService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(R.drawable.ic_stop, "取消", cancel)
        }
        return b.build()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
