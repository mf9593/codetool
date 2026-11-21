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
    private const val KEY_LOG_REFRESH_INTERVAL = "log_refresh_interval_ms"
    private const val KEY_FILE_SHOW_HIDDEN = "file_show_hidden"
    private const val KEY_BATTERY_PROMPTED = "battery_prompted"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"
    private const val KEY_MOUNT_MODE = "mount_mode"
    private const val KEY_APT_MIRROR = "apt_mirror"

    /** 默认终端字号（dp）。Termux 默认 14，这里调大。 */
    const val DEFAULT_FONT_SIZE = 30
    const val MIN_FONT_SIZE = 12
    const val MAX_FONT_SIZE = 44

    /** 默认日志页自动刷新：开启。 */
    const val DEFAULT_LOG_AUTO_REFRESH = true

    /** 默认日志刷新间隔：2 秒。 */
    const val DEFAULT_LOG_REFRESH_INTERVAL = 2000
    /** 可选刷新间隔范围：1 秒 ~ 10 秒。 */
    const val MIN_LOG_REFRESH_INTERVAL = 1000
    const val MAX_LOG_REFRESH_INTERVAL = 10000

    /** 默认 APT 源镜像 id：清华（国内网络下官方源易被 DNS/CDN 污染导致 apt 挂死）。 */
    const val DEFAULT_APT_MIRROR = "tuna"

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

    /** 日志页自动刷新间隔（毫秒），1~10 秒。 */
    fun logRefreshIntervalMs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LOG_REFRESH_INTERVAL, DEFAULT_LOG_REFRESH_INTERVAL)
            .coerceIn(MIN_LOG_REFRESH_INTERVAL, MAX_LOG_REFRESH_INTERVAL)

    fun setLogRefreshIntervalMs(context: Context, ms: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LOG_REFRESH_INTERVAL, ms.coerceIn(MIN_LOG_REFRESH_INTERVAL, MAX_LOG_REFRESH_INTERVAL)).apply()
    }

    /** 文件管理是否显示隐藏文件（以 . 开头）。默认不显示。 */
    fun fileShowHidden(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FILE_SHOW_HIDDEN, false)

    fun setFileShowHidden(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FILE_SHOW_HIDDEN, enabled).apply()
    }

    /** 是否已提示过电池优化引导（只提示一次，避免每次启动都跳设置）。 */
    fun batteryPrompted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_PROMPTED, false)

    fun setBatteryPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
    }

    /** 首次引导页（电池+通知授权）是否已完成。 */
    fun onboardingDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** 清除引导完成标记（关于页「重新进入引导」用，幂等）。 */
    fun resetOnboarding(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_DONE, false).apply()
    }

    private const val KEY_FORCE_ENV_SETUP = "force_env_setup"

    /** 标记「下次进主页前强制进 AI 环境安装页」（关于页重置引导用）。 */
    fun forceEnvSetupOnce(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FORCE_ENV_SETUP, true).apply()
    }

    /** 读取并清除「强制进环境安装页」标记；没标记返回 false。 */
    fun consumeForceEnvSetup(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getBoolean(KEY_FORCE_ENV_SETUP, false)
        if (v) p.edit().remove(KEY_FORCE_ENV_SETUP).apply()
        return v
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 发布新版本时强制用户「重走引导 / 重新阅读设置」的开关（默认关闭）。
    //
    // 用法：要构建一个需要用户重新走引导、重新确认设置的新版本时，把
    // [FORCE_REONBOARD_AFTER_VERSION] 改成该版本的 versionCode（例如 2.1.0 的
    // versionCode=5 就写 5），并在 MainActivity.onCreate 调用 [maybeForceReOnboard]。
    // 首次以 >= 该版本的代码启动时会把 onboarding_done 清掉，引导链自动重跑。
    //
    // 注意两件事（缺一不可）：
    //  - 只清引导，**不清协议同意状态**。协议正文有实质变更时，还必须把
    //    com.codetool.app.UserAgreement.VERSION +1，已同意旧版协议的用户才会
    //    重新走一次同意流程（两套机制独立，别混淆）。
    //  - 阈值只生效一次（按 versionCode 记 applied），之后改回 0 即可永久关闭，
    //    不会每次启动都骚扰用户。
    // ─────────────────────────────────────────────────────────────────────────
    private const val FORCE_REONBOARD_AFTER_VERSION = 0   // 0 = 不启用
    private const val KEY_REONBOARD_APPLIED_VERSION = "reonboard_applied_version"

    /** 达到 [FORCE_REONBOARD_AFTER_VERSION] 版本后首次启动清除引导标记（幂等）。 */
    fun maybeForceReOnboard(context: Context) {
        if (FORCE_REONBOARD_AFTER_VERSION <= 0) return
        val vc = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrNull() ?: return
        if (vc < FORCE_REONBOARD_AFTER_VERSION) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_REONBOARD_APPLIED_VERSION, 0) >= vc) return
        prefs.edit().putLong(KEY_REONBOARD_APPLIED_VERSION, vc).apply()
        resetOnboarding(context)
    }

    /** 容器挂载 Android 共享存储的模式。 */
    object MountMode {
        const val DOWNLOAD = "download"   // 仅 Download（默认，最小暴露）
        const val FULL = "full"           // 整个共享存储 /storage/emulated/0
        const val NONE = "none"           // 不挂载
    }

    fun mountMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MOUNT_MODE, MountMode.DOWNLOAD) ?: MountMode.DOWNLOAD

    fun setMountMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MOUNT_MODE, mode).apply()
    }

    /** 容器 APT 源镜像 id（默认清华，见 [DEFAULT_APT_MIRROR]）。 */
    fun aptMirror(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APT_MIRROR, DEFAULT_APT_MIRROR) ?: DEFAULT_APT_MIRROR

    fun setAptMirror(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_APT_MIRROR, id).apply()
    }

}
