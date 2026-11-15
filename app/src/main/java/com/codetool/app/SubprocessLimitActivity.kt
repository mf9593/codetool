package com.codetool.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import android.content.Intent
import android.net.Uri

/**
 * 解除子进程限制（全屏引导页）：Android 12+ 会终止启动过多子进程的应用（phantom process），
 * 容器 / 多开会因此被系统杀掉。本页提供状态检测 + 解除方式引导，具体操作（开发者选项开关 /
 * 无线调试命令）由用户完成——App 进程无 shell/root 权限，不能替用户改系统设置。
 */
class SubprocessLimitActivity : AppCompatActivity() {

    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvMethodHint: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.codetool.app.theme.ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subprocess_limit)

        findViewById<MaterialToolbar>(R.id.subprocToolbar).setNavigationOnClickListener { finish() }
        tvStatus = findViewById(R.id.tvSubprocStatus)
        tvMethodHint = findViewById(R.id.tvSubprocMethodHint)

        findViewById<MaterialButton>(R.id.btnSubprocRefresh).setOnClickListener { refreshStatus() }
        findViewById<MaterialButton>(R.id.btnSubprocDev).setOnClickListener { showDevSteps() }
        findViewById<MaterialButton>(R.id.btnSubprocOpenDev).setOnClickListener { openDeveloperOptions() }
        findViewById<MaterialButton>(R.id.btnSubprocWireless).setOnClickListener { showWirelessSteps() }

        tvMethodHint.text = "本机 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）· 可直接用「开发者选项」"
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()  // 从开发者选项返回后刷新
    }

    /**
     * 检测子进程限制是否已解除。App 进程读不到 device_config，但 Android 12+ 把
     * 子进程上限同步到了 Settings.Global 的 max_phantom_processes（默认 32，
     * 解除后 =2147483647），这个能读到。读到一个很大的值即视为已解除。
     * 另检测 settings_enable_monitor_phantom_procs（部分机型在 Global 表里为 0）。
     */
    private fun refreshStatus() {
        val lifted = runCatching {
            val max = Settings.Global.getInt(contentResolver, "max_phantom_processes", 0)
            if (max >= Int.MAX_VALUE - 1) return@runCatching true  // 解除上限（2147483647）
            // 部分机型 monitor 开关在 Global 表：=0 表示已关监控
            Settings.Global.getString(contentResolver, "settings_enable_monitor_phantom_procs") == "0"
        }.getOrDefault(false)
        if (lifted) {
            tvStatus.text = "✓ 已解除（子进程监控已关闭 / 上限已放宽）"
            tvStatus.setTextColor(0xFF1B8A4B.toInt())
        } else {
            tvStatus.text = "未确认已解除 · 请按下方方式在开发者选项检查「停用子进程限制」是否打开"
            tvStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        }
    }

    /** 方式一：开发者选项（Android 14+ 可直接开关「停用子进程限制」）。 */
    private fun showDevSteps() {
        MaterialAlertDialogBuilder(this)
            .setTitle("方式一 · 开发者选项")
            .setMessage("1. 开启开发者选项：系统设置 → 关于手机，连点「版本号」7 次。\n\n" +
                "2. 进入开发者选项，找到并打开「停用子进程限制」。\n\n" +
                "部分机型该开关名称/位置可能不同，可搜索「您的机型 + 停用子进程限制」。")
            .setPositiveButton("进入开发者选项") { _, _ -> openDeveloperOptions() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openDeveloperOptions() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show() }
        }
    }

    /** 方式二：无线调试（需配对，命令由用户在配对成功的 adb 终端执行）。 */
    private fun showWirelessSteps() {
        val msg = "需先用「无线调试」配对本机，然后在 adb 终端执行：\n\n" +
            "关闭子进程监控：\n" +
            "  adb shell settings put global settings_enable_monitor_phantom_procs 0\n\n" +
            "放宽子进程上限：\n" +
            "  adb shell device_config put activity_manager max_phantom_processes 2147483647\n\n" +
            "恢复默认（重新开启监控）：\n" +
            "  adb shell settings put global settings_enable_monitor_phantom_procs 1\n\n" +
            "提示：需先在开发者选项开启「无线调试」并完成配对，命令才能执行成功。"
        MaterialAlertDialogBuilder(this)
            .setTitle("方式二 · 无线调试")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, SubprocessLimitActivity::class.java))
        }
    }
}
