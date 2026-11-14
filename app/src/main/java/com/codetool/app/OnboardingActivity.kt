package com.codetool.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.codetool.app.theme.ThemeManager
import com.codetool.app.util.AppSettings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 首次进入引导页：申请两项保活权限。
 * ① 电池无限制（忽略电池优化）——避免锁屏 Doze 冻结容器/终端；
 * ② 通知权限（Android 13+）——前台服务保活的通知必须可见。
 * 两项都授权后「下一步」才可用（ROM 不支持某项时该项视为已满足）。
 */
class OnboardingActivity : Activity() {

    private lateinit var cardsContainer: LinearLayout
    private lateinit var hint: TextView
    private lateinit var next: MaterialButton
    private var batteryBadge: TextView? = null
    private var notifyBadge: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_onboarding)

        findViewById<MaterialToolbar>(R.id.onboardToolbar).setNavigationOnClickListener { finish() }
        cardsContainer = findViewById(R.id.onboardCards)
        hint = findViewById(R.id.onboardHint)
        next = findViewById(R.id.onboardNext)

        buildCards()
        next.setOnClickListener {
            AppSettings.setOnboardingDone(this)
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()  // 从系统设置/权限弹窗返回后刷新状态
    }

    private fun buildCards() {
        cardsContainer.removeAllViews()
        batteryBadge = makeCard(
            icon = R.drawable.ic_wireless,
            title = "电池无限制",
            desc = "把本应用设为「忽略电池优化」，避免锁屏后系统冻结容器和终端（否则后台任务会中断）。",
        ) { requestBattery() }
        notifyBadge = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            makeCard(
                icon = R.drawable.ic_info,
                title = "通知权限",
                desc = "允许通知，前台服务（rootfs 解压、终端保活、下载）才能持续运行不被系统杀掉。",
            ) { requestNotif() }
        } else null  // Android 13 以下无运行时通知权限，视为已满足
    }

    /** 建一张权限卡片，返回状态徽标 TextView（供 refreshUi 更新）。 */
    private fun makeCard(icon: Int, title: String, desc: String, onClick: () -> Unit): TextView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(14) }
            radius = dp(16).toFloat()
            val surface = MaterialColorsHelper.surface(this@OnboardingActivity)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
        ImageView(this).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setColorFilter(MaterialColorsHelper.accent(this@OnboardingActivity))
        }.also { row.addView(it) }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.leftMargin = dp(14) }
            addView(TextView(this@OnboardingActivity).apply {
                text = title
                textSize = 15f
                setTextColor(MaterialColorsHelper.onSurface(this@OnboardingActivity))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@OnboardingActivity).apply {
                text = desc
                textSize = 13f
                setTextColor(MaterialColorsHelper.onSurfaceVariant(this@OnboardingActivity))
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(4) }
            })
        }.also { row.addView(it) }
        // 状态徽标（已开启/待授权）
        val badge = TextView(this).apply {
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.leftMargin = dp(8) }
        }.also { row.addView(it) }
        card.addView(row)
        cardsContainer.addView(card)
        return badge
    }

    private fun refreshUi() {
        val batOk = batteryGranted()
        val ntfOk = notifGranted()
        updateBadge(batteryBadge, batOk)
        updateBadge(notifyBadge, ntfOk)
        val allOk = batOk && ntfOk
        next.isEnabled = allOk
        hint.text = if (allOk) "已完成全部授权，可以开始使用了" else "请先完成以上两项授权（点卡片跳转授权）"
    }

    private fun updateBadge(badge: TextView?, ok: Boolean) {
        badge?.let {
            it.text = if (ok) "已开启 ✓" else "待授权"
            it.setTextColor(if (ok) 0xFF1B8A4B.toInt() else MaterialColorsHelper.accent(this))
        }
    }

    private fun batteryGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun notifGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) refreshUi()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_NOTIF = 100
        fun start(activity: Activity, requestCode: Int) {
            activity.startActivityForResult(Intent(activity, OnboardingActivity::class.java), requestCode)
        }
    }
}

/** 小工具：集中取 Material 主题色（避免各处重复 TypedValue 解析）。 */
private object MaterialColorsHelper {
    fun surface(a: Activity): Int = com.google.android.material.color.MaterialColors.getColor(
        a, com.google.android.material.R.attr.colorSurface, 0)
    fun onSurface(a: Activity): Int = com.google.android.material.color.MaterialColors.getColor(
        a, com.google.android.material.R.attr.colorOnSurface, 0)
    fun onSurfaceVariant(a: Activity): Int = com.google.android.material.color.MaterialColors.getColor(
        a, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    fun accent(a: Activity): Int = com.google.android.material.color.MaterialColors.getColor(
        a, com.google.android.material.R.attr.colorPrimary, 0)
}
