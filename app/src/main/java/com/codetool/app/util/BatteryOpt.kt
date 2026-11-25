package com.codetool.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 锁屏联网豁免：长任务（下载/安装/AI 生成）锁屏时 Doze 深度空闲会限制后台网络，
 * 即使前台服务 + WakeLock + WifiLock 也只能防进程冻结/CPU/WiFi，不能豁免 Doze 网络限制。
 * 只有把应用加入「忽略电池优化」白名单（isIgnoringBatteryOptimizations）才豁免。
 *
 * 长任务启动前调 [ensureExempt]：未忽略时弹系统对话框引导用户允许（一次授权后持久生效）。
 */
object BatteryOpt {
    private const val TAG = "BatteryOpt"

    /** 本次进程已请求过（用户拒绝后不再重复弹，避免每次任务都打断）。重启进程后重试。 */
    @Volatile private var requestedThisProcess = false

    fun ensureExempt(context: Context) {
        val app = context.applicationContext
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(app.packageName)) return
            if (requestedThisProcess) return
            requestedThisProcess = true
            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限
            // （manifest 已声明），弹系统对话框让用户允许；允许后系统把应用加入 Doze 白名单。
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            Log.i(TAG, "requesting ignore battery optimizations")
        } catch (e: Exception) {
            // 个别 ROM 无此设置页：不阻塞任务，用户可到系统设置手动允许。
            Log.w(TAG, "request ignore batt opt failed: ${e.message}")
        }
    }
}
