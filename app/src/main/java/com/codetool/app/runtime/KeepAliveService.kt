package com.codetool.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codetool.app.CodeToolApp
import com.codetool.app.MainActivity
import com.codetool.app.R

/**
 * 后台任务保活前台服务。
 *
 * 长时间安装/下载这类任务跑在后台线程，锁屏后 Android 会冻结
 * 后台进程（实测锁屏 ~110 秒后探测进程就停了），导致引导/下载中断、卡死。
 *
 * 任务开始时拉起本服务（startForegroundService），持有 PARTIAL_WAKE_LOCK（防 CPU 冻结）+
 * WifiLock（锁屏 WiFi 省电断开时保持联网）+ 常驻通知，把进程提到前台优先级，
 * 锁屏期间不被冻结、不断网；任务结束调 ACTION_STOP 自停（或由宿主 stopKeepAlive）。
 *
 * 不绑定具体任务：只负责保活，进度/日志仍由各任务自己上报（codetool.log）。
 */
class KeepAliveService : Service() {
    // 低危：wakeLock 改为实例字段——服务重建时旧引用释放，静态持有会泄漏 CPU 唤醒锁。
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val TAG = "KeepAlive"
        private const val NOTIF_ID = 1003
        const val ACTION_START = "com.codetool.action.KEEPALIVE_START"
        const val ACTION_STOP = "com.codetool.action.KEEPALIVE_STOP"
        const val EXTRA_TEXT = "text"

        @Volatile var running = false; private set

        /** 拉起保活（任务开始时调）。text 显示在通知上。 */
        fun start(context: Context, text: String = "后台任务运行中…") {
            // 锁屏联网豁免：Doze 深度会限制后台网络，前台服务+WakeLock+WifiLock 豁免不了，
            // 需「忽略电池优化」白名单。任务开始前引导用户允许（未忽略且未请求过才弹）。
            com.codetool.app.util.BatteryOpt.ensureExempt(context)
            val intent = Intent(context, KeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TEXT, text)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 后台启动受限等场景不致命，任务仍可在前台使用时继续
                Log.w(TAG, "startKeepAlive failed: ${e.message}")
            }
        }

        /** 结束保活（任务完成/失败时调）。 */
        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stopKeepAlive failed: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodeTool:KeepAlive")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "后台任务运行中…"
                running = true
                // 先 startForeground 再拿 wake lock，确保通知及时显示
                startForeground(NOTIF_ID, buildNotification(text))
                wakeLock?.acquire(60 * 60 * 1000L) // 上限 1 小时，防泄漏
                acquireWifiLock()
                Log.i(TAG, "keepalive acquired: $text")
            }
        }
        return START_NOT_STICKY
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "releaseWakeLock: ${e.message}")
        }
        // 低危：锁屏 WiFi 省电断开会让下载/安装任务断网（CPU 不冻结也连不上），释放时一并释放。
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "releaseWifiLock: ${e.message}")
        }
        wifiLock = null
    }

    /** 锁屏期间保持 WiFi 活跃（FULL_HIGH_PERF）：无此锁，锁屏后 WiFi 进省电模式断开，任务断网。 */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "codetool:keepalive_wifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WifiLock (FULL_HIGH_PERF) acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock acquire failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CodeToolApp.CHANNEL_KEEPALIVE)
            .setContentTitle("CodeTool 后台任务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
