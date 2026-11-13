package com.codetool.app.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codetool.app.CodeToolApp
import com.codetool.app.MainActivity
import com.codetool.app.R

/**
 * 终端前台服务。
 *
 * 核心职责：持有 PARTIAL_WAKE_LOCK，保证锁屏熄屏后 CPU 不进入 deep suspend，
 * 从而让终端会话的网络通信不会中断（解决 ACSIDE 那种"锁屏断网"问题）。
 *
 * 设计要点：
 * - 有活跃终端会话时 acquire PARTIAL_WAKE_LOCK（不亮屏，只保 CPU）
 * - 可选 acquire WifiLock（FULL_HIGH_PERF），保证 WiFi 无线电不进低功耗挂起，
 *   适合 AI 流式长连接
 * - 所有会话结束才 release，避免长期耗电
 * - 前台通知让用户能随时停止
 */
class TerminalService : Service() {

    companion object {
        private const val TAG = "TerminalService"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.codetool.action.START_TERMINAL"
        const val ACTION_STOP = "com.codetool.action.STOP_TERMINAL"
        const val EXTRA_KEEP_WIFI = "keep_wifi"

        // 单例引用，方便 Fragment 直接拿
        @Volatile
        var instance: TerminalService? = null
            private set
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var activeSessions = 0
    @Volatile private var keepWifi = true

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "codetool:terminal_cpu")
        wakeLock.setReferenceCounted(false)
        instance = this
        Log.i(TAG, "TerminalService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTerminal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                keepWifi = intent?.getBooleanExtra(EXTRA_KEEP_WIFI, true) ?: true
                startForeground(NOTIF_ID, buildNotification("终端运行中"))
                ensureLocks()
            }
        }
        return START_STICKY
    }

    /**
     * 有终端会话开始时调用（由 TerminalSession 通知）。
     */
    fun onSessionStarted() {
        activeSessions++
        ensureLocks()
        Log.i(TAG, "session started, active=$activeSessions")
    }

    /**
     * 有终端会话结束时调用。
     */
    fun onSessionEnded() {
        activeSessions = (activeSessions - 1).coerceAtLeast(0)
        Log.i(TAG, "session ended, active=$activeSessions")
        if (activeSessions == 0) {
            releaseLocks()
        }
    }

    private fun ensureLocks() {
        if (!wakeLock.isHeld) {
            // 不带超时：长持有，靠 onSessionEnded 释放。
            // 若担心泄漏可加兜底超时（如 30 分钟），但 AI 长任务可能超时，故默认不设。
            wakeLock.acquire()
            Log.i(TAG, "PARTIAL_WAKE_LOCK acquired")
        }
        if (keepWifi && wifiLock == null) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "codetool:terminal_wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiLock (FULL_HIGH_PERF) acquired")
            } catch (e: Exception) {
                Log.w(TAG, "WifiLock acquire failed: ${e.message}")
            }
        }
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.i(TAG, "PARTIAL_WAKE_LOCK released")
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WifiLock released")
        }
        wifiLock = null
    }

    private fun stopTerminal() {
        releaseLocks()
        activeSessions = 0
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CodeToolApp.CHANNEL_TERMINAL)
            .setContentTitle("CodeTool 终端")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stop, "停止", stopPi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        instance = null
        Log.i(TAG, "TerminalService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
