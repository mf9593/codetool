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
        const val ACTION_KEEPALIVE = "com.codetool.action.KEEPALIVE_TERMINAL"
        const val EXTRA_KEEP_WIFI = "keep_wifi"

        // 单例引用，方便 Fragment 直接拿
        @Volatile
        var instance: TerminalService? = null
            private set

        /**
         * App 启动时保活拉起（幂等）。与浏览器桥一样在 CodeToolApp.onCreate 调用，
         * 让进程重启后终端服务能复活——此前只有桥在 Application 拉起，终端服务只能靠
         * START_STICKY，国产 ROM 常抑制、force-stop 直接取消，导致「重启后桥活着、终端服务没了」。
         * 用 ACTION_KEEPALIVE 只起前台通知、不持 wakeLock/WifiLock（没有真实会话时不耗电）；
         * 用户真开终端时 TerminalSessionManager.prepare() 会发 ACTION_START 并 onSessionStarted 持锁。
         */
        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java).setAction(ACTION_KEEPALIVE)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 后台启动受限等场景不致命：打开终端时 prepare() 会再拉起
                Log.w(TAG, "start failed: ${e.message}")
            }
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private var wifiLock: WifiManager.WifiLock? = null
    private val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
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
            ACTION_KEEPALIVE -> {
                // App 启动保活：只起前台通知让服务常驻、可被系统识别，不持 wakeLock/WifiLock。
                // 此时没有真实终端会话，持锁纯耗电；真开终端时 prepare() 会 ACTION_START + 持锁。
                startForeground(NOTIF_ID, buildNotification("终端待命"))
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
        val n = activeSessions.incrementAndGet()
        ensureLocks()
        // 锁屏联网豁免：终端里跑 opencode/mimo 长任务，Doze 会限制后台网络，需「忽略电池优化」白名单。
        com.codetool.app.util.BatteryOpt.ensureExempt(this)
        Log.i(TAG, "session started, active=$n")
    }

    /**
     * 有终端会话结束时调用。
     */
    fun onSessionEnded() {
        val n = activeSessions.decrementAndGet().coerceAtLeast(0)
        Log.i(TAG, "session ended, active=$n")
        if (n == 0) {
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
        activeSessions.set(0)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 不设「停止」Action：终端服务现在是 App 级保活常驻（与浏览器桥一致），
        // 保留停止按钮容易在通知栏误触 → stopSelf + START_NOT_STICKY 永久停掉且不会自动复活。
        // 要停终端会话请在终端页关标签页；服务本身常驻不耗电（保活模式不持锁）。
        return NotificationCompat.Builder(this, CodeToolApp.CHANNEL_TERMINAL)
            .setContentTitle("CodeTool 终端")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
