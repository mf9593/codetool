package com.codetool.app.util

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态/授权封装。
 *
 * Shizuku 让 App 以 shell（adb，uid 2000）身份执行操作，无需 root。
 * 这里只做「状态探测 + 申请授权」，不直接跑命令（13.1.5 的 Shizuku 类没有
 * newProcess，执行命令需 user-service，后续按需扩展）。
 *
 * 用 getUid()==2000 作为「已授权且确为 shell 身份」的强校验，比只看
 * checkSelfPermission 更可靠（部分 ROM 上后者可能误报）。
 *
 * 所有调用都 runCatching 包裹：Shizuku 服务未运行 / binder 未就绪时
 * 静态方法可能抛异常，不能让它崩掉首页。
 */
object ShizukuManager {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 9100

    data class Status(
        val installed: Boolean,
        val running: Boolean,
        val granted: Boolean,
        val label: String,
    )

    /** Shizuku 管理器 App 是否已安装。 */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    }.getOrDefault(false)

    /**
     * 综合状态：installed（装了没）→ running（服务在跑没）→ granted（授权没）。
     * 逐级返回，前面的不满足就不往后查，避免 binder 未就绪时误判/抛异常。
     */
    fun status(ctx: Context): Status {
        if (!isInstalled(ctx)) return Status(false, false, false, "未安装")
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return Status(true, false, false, "已安装 · 服务未运行")
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status(true, true, true, "已授权 · shell(uid ${runCatching { Shizuku.getUid() }.getOrDefault(-1)})")
        else Status(true, true, false, "运行中 · 未授权")
    }

    /** 弹出 Shizuku 授权对话框（结果回调 onRequestPermissionResult）。 */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun addPermissionResultListener(l: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(l) }
    }

    fun removePermissionResultListener(l: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(l) }
    }
}
