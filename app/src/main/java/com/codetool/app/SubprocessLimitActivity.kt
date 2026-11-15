package com.codetool.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.codetool.app.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri

/**
 * 解除子进程限制（全屏引导页）：Android 12+ 会终止启动过多子进程的应用（phantom process），
 * 容器 / 多开会因此被系统杀掉。本页提供状态检测 + 解除方式引导，具体操作（开发者选项开关 /
 * 无线调试命令）由用户完成——App 进程无 shell/root 权限，不能替用户改系统设置。
 *
 * 检测走「渐进」：Android 无公开 API 读子进程限制（MAX_PHANTOM_PROCESSES 是
 * ActivityManagerConstants 内部字段），普通 App 进程读不到。codetool 的可靠路径是
 * Shizuku（shell uid 2000 = 免 root 的 adb）跑 dumpsys 解析；Shizuku 不可用时降级
 * Settings.Global 试探；都不行诚实显示「无法自动检测」引导手动确认，不假装知道。
 */
class SubprocessLimitActivity : AppCompatActivity() {

    /** 检测结果状态。 */
    private sealed class PhantomState {
        data class Unrestricted(val via: String) : PhantomState()  // 已解除
        data class Limited(val max: Int, val via: String) : PhantomState()  // 仍受限
        data class Unknown(val reason: String) : PhantomState()  // 读不到，走手动确认
    }

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
     * 渐进检测子进程限制状态（后台线程，Shizuku 命令会阻塞）。优先级：
     * ① Shizuku 跑 dumpsys 解析 max_phantom_processes（最可靠，免 root/adb）；
     * ② Settings.Global 试探（辅助，多数 ROM 读不到）；
     * ③ 都不行 → Unknown，诚实引导手动确认。
     */
    private fun refreshStatus() {
        tvStatus.text = "检测中…"
        tvStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { detectPhantomState() }
            renderState(state)
        }
    }

    private fun detectPhantomState(): PhantomState {
        // ① Shizuku（shell 身份跑 dumpsys，免 root/adb，最可靠）
        runCatching {
            val (ok, out) = ShizukuBridge.execShell("dumpsys activity settings")
            if (ok) {
                // 宽松正则：namespace 可能是 activity_manager 或 activity，字段 max_phantom_processes
                val m = Regex("max_phantom_processes=(\\d+)").find(out)
                val max = m?.groupValues?.get(1)?.toIntOrNull()
                if (max != null) {
                    return if (max >= Int.MAX_VALUE - 1) PhantomState.Unrestricted("Shizuku")
                    else PhantomState.Limited(max, "Shizuku")
                }
            }
        }
        // ② Settings.Global 试探（辅助：部分机型 monitor 开关在 Global 表）
        runCatching {
            if (Settings.Global.getString(contentResolver, "settings_enable_monitor_phantom_procs") == "0") {
                return PhantomState.Unrestricted("Settings")
            }
            val max = Settings.Global.getInt(contentResolver, "max_phantom_processes", -1)
            if (max >= Int.MAX_VALUE - 1) return PhantomState.Unrestricted("Settings")
            if (max in 1..100) return PhantomState.Limited(max, "Settings")
        }
        // ③ 都读不到：诚实返回 Unknown
        return PhantomState.Unknown("App 进程无权限读取子进程限制（需 Shizuku 或 root）")
    }

    private fun renderState(state: PhantomState) {
        val green = 0xFF1B8A4B.toInt()
        val warn = 0xFFB26A00.toInt()
        val neutral = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        when (state) {
            is PhantomState.Unrestricted -> {
                tvStatus.text = "✓ 已解除（子进程限制已关闭 / 上限已放宽）· 经 ${state.via} 检测"
                tvStatus.setTextColor(green)
            }
            is PhantomState.Limited -> {
                tvStatus.text = "⚠ 仍受限：最多 ${state.max} 个子进程 · 经 ${state.via} 检测，请按下方方式解除"
                tvStatus.setTextColor(warn)
            }
            is PhantomState.Unknown -> {
                tvStatus.text = "无法自动检测（${state.reason}）· 可开启 Shizuku 自动检测，或在开发者选项确认「停用子进程限制」是否打开"
                tvStatus.setTextColor(neutral)
            }
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
