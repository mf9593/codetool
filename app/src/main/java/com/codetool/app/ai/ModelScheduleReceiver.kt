package com.codetool.app.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.codetool.app.R

/**
 * 定时切换的到点触发：自动切换模型 → 通知提示「请重新打开终端」→ 排下一次边界闹钟。
 *
 * 不弹 Activity/Dialog：闹钟触发时 App 可能在后台，通知栏提示最稳妥；
 * 下发通知失败（用户未授予 POST_NOTIFICATIONS）也不影响切换本身。
 */
class ModelScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ModelScheduleManager.ACTION_APPLY) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val (switched, msg) = ModelScheduleManager.applyNow(app)
                // 无论是否发生切换，都要排下一次边界
                ModelScheduleManager.rescheduleAlarm(app)
                if (switched && ModelScheduleManager.isCliRunning()) {
                    // 后台已切换完成；有 CLI 仍在跑 → 标记待提示（App 回前台弹防误触窗）+ 通知栏兜底
                    val clis = ModelScheduleManager.runningClis()
                    val text = ModelScheduleManager.restartPromptText(msg, clis)
                    ModelScheduleManager.markRestartPrompt(app, text)
                    val slot = ModelScheduleManager.activeSlot(ModelScheduleManager.load(app))
                    val label = if (slot?.peak == true) "高峰" else "闲时"
                    notify(app, "模型已定时切换（$label）", text)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notify(ctx: Context, title: String, text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        ModelScheduleManager.CHANNEL,
                        "模型定时切换",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "按高峰/闲时自动切换 AI 模型后的提醒" }
                )
            }
            val n = NotificationCompat.Builder(ctx, ModelScheduleManager.CHANNEL)
                .setSmallIcon(R.drawable.ic_ai)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull() ?: text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }

    private companion object {
        const val NOTIFY_ID = 0x4D5347   // "MSG"
    }
}
