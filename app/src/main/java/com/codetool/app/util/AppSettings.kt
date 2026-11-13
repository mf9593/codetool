package com.codetool.app.util

import android.content.Context

/**
 * 应用设置（SharedPreferences 封装）。
 * 目前：终端字体大小（默认调大）。
 */
object AppSettings {

    private const val PREFS = "codetool_settings"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_LOG_AUTO_REFRESH = "log_auto_refresh"
    private const val KEY_BATTERY_PROMPTED = "battery_prompted"

    /** 默认终端字号（dp）。Termux 默认 14，这里调大。 */
    const val DEFAULT_FONT_SIZE = 16
    const val MIN_FONT_SIZE = 12
    const val MAX_FONT_SIZE = 44

    /** 默认日志页自动刷新：开启。 */
    const val DEFAULT_LOG_AUTO_REFRESH = true

    fun fontSize(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    fun setFontSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FONT_SIZE, size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)).apply()
    }

    /** 日志页自动刷新开关。 */
    fun logAutoRefresh(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOG_AUTO_REFRESH, DEFAULT_LOG_AUTO_REFRESH)

    fun setLogAutoRefresh(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOG_AUTO_REFRESH, enabled).apply()
    }

    /** 是否已提示过电池优化引导（只提示一次，避免每次启动都跳设置）。 */
    fun batteryPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_PROMPTED, false)

    fun setBatteryPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
    }
}
