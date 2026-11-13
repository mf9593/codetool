package com.codetool.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codetool.app.CodeToolApp
import com.codetool.app.MainActivity
import com.codetool.app.R
import com.codetool.app.terminal.TerminalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * rootfs 首次启动自动解压服务。
 *
 * App 启动时（CodeToolApp.onCreate）自动拉起：在后台解压 assets 里的
 * noble-server-cloudimg-arm64-root.tar.xz 到 files/rootfs，并显示进度通知。
 * 解压完成/失败后自停。
 *
 * 持有 PARTIAL_WAKE_LOCK，锁屏期间也能继续解压。
 */
class RootfsInstallService : Service() {

    companion object {
        private const val TAG = "RootfsInstall"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.codetool.action.INSTALL_ROOTFS"
        const val ACTION_STOP = "com.codetool.action.STOP_ROOTFS"

        // 广播：解压进度/完成
        const val BROADCAST_PROGRESS = "com.codetool.ROOTFS_PROGRESS"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_DONE = "done"
        const val EXTRA_ERROR = "error"

        @Volatile var running = false; private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastPercent = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                running = true
                startForeground(NOTIF_ID, buildNotification("正在解压 Linux 系统…", 0))
                if (job?.isActive != true) {
                    job = scope.launch {
                        try {
                            val rootfs = RootfsManager.get(applicationContext)
                            // 已在解压中则等待完成；否则解压并上报进度
                            rootfs.ensureExtractedSync { pct ->
                                sendProgress(pct)
                            }
                            sendProgress(100, done = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "rootfs install failed", e)
                            sendProgress(-1, error = e.message ?: "unknown")
                        } finally {
                            running = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun sendProgress(percent: Int, done: Boolean = false, error: String? = null) {
        val ctx = applicationContext
        if (done) {
            ctx.sendBroadcast(Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_DONE, true).putExtra(EXTRA_PERCENT, 100))
            notify(100, "Linux 系统解压完成")
        } else if (error != null) {
            ctx.sendBroadcast(Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_ERROR, error))
            notify(-1, "解压失败：$error")
        } else if (percent != lastPercent) {
            lastPercent = percent
            ctx.sendBroadcast(Intent(BROADCAST_PROGRESS)
                .putExtra(EXTRA_PERCENT, percent))
            notify(percent, "正在解压 Linux 系统… $percent%")
        }
    }

    private fun notify(percent: Int, text: String) {
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, percent))
        } catch (e: Exception) { Log.w(TAG, "notify failed: ${e.message}") }
    }

    private fun buildNotification(text: String, percent: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CodeToolApp.CHANNEL_ROOTFS)
            .setContentTitle("CodeTool")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent >= 0) {
            builder.setProgress(100, percent, percent == 0)
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
