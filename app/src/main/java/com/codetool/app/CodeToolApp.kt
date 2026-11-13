package com.codetool.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.codetool.app.util.CrashLogger

class CodeToolApp : Application() {
    companion object {
        const val CHANNEL_TERMINAL = "terminal_session"
        const val CHANNEL_ROOTFS = "rootfs_ops"
        const val CHANNEL_KEEPALIVE = "keepalive_ops"
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志必须在最前面，捕获所有闪退
        CrashLogger.init(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TERMINAL,
                    "终端会话",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "终端运行时的保活通知" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ROOTFS,
                    "系统操作",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "rootfs 解压等后台操作" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_KEEPALIVE,
                    "后台任务保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "长时间任务运行期间保活，防止锁屏被冻结中断"
                    setShowBadge(false)
                }
            )
        }
    }
}
