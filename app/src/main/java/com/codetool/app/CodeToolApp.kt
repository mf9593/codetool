package com.codetool.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.codetool.app.util.CrashLogger
import com.codetool.app.gui.BrowserBridgeService

class CodeToolApp : Application() {
    companion object {
        const val CHANNEL_TERMINAL = "terminal_session"
        const val CHANNEL_ROOTFS = "rootfs_ops"
        const val CHANNEL_KEEPALIVE = "keepalive_ops"
        const val CHANNEL_GUI = "gui_background"
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃日志必须在最前面，捕获所有闪退
        CrashLogger.init(this)
        createChannels()
        // TextMate 语法高亮（编辑器）：一次性加载语法/主题资源，幂等
        com.codetool.app.util.TextMateEditor.init(this)
        // Shizuku 桥常驻：监听容器请求，让 AI 模型以 shell 身份调试 App（不依赖 GUI）
        // 只建目录 + 起 FileObserver，很轻；bind user-service 推迟到首个请求才做。
        com.codetool.app.shizuku.ShizukuBridge.start(this)
        // 浏览器桥前台服务常驻：让模型/CLI 调浏览器不依赖 GUI 合成器（无头 Chrome 后台跑）。
        BrowserBridgeService.start(this)
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
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GUI,
                    "GUI 后台运行",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "GUI 合成器最小化后的返回入口"
                    setShowBadge(false)
                }
            )
        }
    }
}
