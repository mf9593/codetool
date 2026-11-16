package com.codetool.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiEnvHelper
import com.codetool.app.theme.ThemeManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动第三引导页 / 包管理「一键修复环境」共用：
 * 检测器容器 AI 环境（rootfs + python3 + codetool-mcp 桥），缺就一键安装
 * （apt 装 python3 + 注入桥）。装好才能用 MCP（browser_* / shizuku_*）。
 *
 * 用法：
 *  - 首次引导：OnboardingActivity 完成后由 MainActivity 跳入（requestCode 见调用方）
 *  - 包管理一键修复：从 PackagesFragment 跳入，装完返回
 */
class EnvSetupActivity : AppCompatActivity() {

    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvLog: MaterialTextView
    private lateinit var progressCard: android.view.View
    private lateinit var progressTitle: MaterialTextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnNext: MaterialButton
    private lateinit var btnInstall: MaterialButton

    /** 一键修复模式（包管理进入）不要求「开始使用」禁用到装完，检测完即可回。 */
    private var fixMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_env_setup)

        fixMode = intent.getBooleanExtra(EXTRA_FIX_MODE, false)

        findViewById<MaterialToolbar>(R.id.envToolbar).setNavigationOnClickListener { finish() }
        tvStatus = findViewById(R.id.envStatusText)
        tvLog = findViewById(R.id.envLogText)
        progressCard = findViewById(R.id.envProgressCard)
        progressTitle = findViewById(R.id.envProgressTitle)
        progress = findViewById(R.id.envProgress)
        btnNext = findViewById(R.id.envNext)
        btnInstall = findViewById(R.id.envInstallBtn)

        // 两种模式统一：检测到缺项就显示「一键修复环境」（apt 安装入口在此按钮），
        // 全绿则隐藏；「开始使用/返回」始终可点，不强制。
        btnInstall.setOnClickListener { install() }
        btnNext.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
        if (fixMode) btnNext.text = "返回"
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()  // 从后台/安装返回后刷新
    }

    private fun refreshStatus() {
        val st = AiEnvHelper.check(this)
        tvStatus.text = if (st.allOk) {
            "✓ 环境就绪（python3 ✓ MCP 桥 ✓ 配置 ✓）"
        } else {
            buildString {
                if (!st.rootfsReady) append("\n✗ rootfs 未解压（进入主页后自动解压）")
                if (!st.python3Installed) append("\n✗ 缺少 python3（MCP 无法启动）")
                if (!st.mcpScriptOk) append("\n✗ MCP 桥脚本缺失")
                if (!st.mcpConfigured) append("\n✗ MCP 配置未注册")
            }.trim().ifEmpty { "环境异常" }
        }
        // 缺项 → 一键修复按钮出现（唯一 apt 安装入口）；全绿 → 隐藏
        btnInstall.visibility =
            if (st.allOk) android.view.View.GONE else android.view.View.VISIBLE
        btnInstall.isEnabled = true
        btnNext.isEnabled = true
    }

    private fun install() {
        btnInstall.isEnabled = false
        progressCard.visibility = android.view.View.VISIBLE
        progressTitle.visibility = android.view.View.VISIBLE
        progress.visibility = android.view.View.VISIBLE
        tvLog.text = ""
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AiEnvHelper.ensure(this@EnvSetupActivity) { line ->
                    runOnUiThread { tvLog.append(line + "\n") }
                }
            }
            progress.visibility = android.view.View.GONE
            progressTitle.visibility = android.view.View.GONE
            refreshStatus()
            if (!ok) {
                tvStatus.text = "修复未完成，请重试或查看上方输出"
                btnInstall.isEnabled = true
            }
        }
    }

    companion object {
        const val EXTRA_FIX_MODE = "fix_mode"

        fun startForResult(activity: Activity, requestCode: Int, fixMode: Boolean = false) {
            activity.startActivityForResult(
                Intent(activity, EnvSetupActivity::class.java).putExtra(EXTRA_FIX_MODE, fixMode),
                requestCode
            )
        }
    }
}