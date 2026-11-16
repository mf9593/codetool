package com.codetool.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.codetool.app.theme.ThemeManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors

/**
 * 更新日志全屏页（独立 Activity，标题栏 + 返回）。由关于页「更新日志」入口进入。
 *
 * 与 [AboutActivity] 同样的程序化构建 + 沉浸式处理；内容为 [Changelog] 各版本
 * 标题加粗 + 要点列表，可滚动。不用弹窗，保证长文本可读。
 */
class ChangelogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val actionBarSize = {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        }
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
        val onSurfaceVariant = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF44474A.toInt())
        val surfaceContainer = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainer, 0xFFE8E8E8.toInt())
        val bg = MaterialColors.getColor(
            this, android.R.attr.colorBackground, 0xFFFAFAFA.toInt())
        val lightStatus =
            androidx.core.graphics.ColorUtils.calculateLuminance(surfaceContainer) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightStatus
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = lightStatus

        val toolbar = MaterialToolbar(this).apply {
            title = "更新日志"
            setTitleTextColor(onSurface)
            setBackgroundColor(surfaceContainer)
            elevation = 0f
            val backIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@ChangelogActivity, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            backIcon?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it, onSurface) }
            setNavigationIcon(backIcon)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }

        // 各版本标题加粗 + 要点列表
        val sb = android.text.SpannableStringBuilder()
        Changelog.entries.forEachIndexed { i, e ->
            if (i > 0) sb.append("\n\n")
            val start = sb.length
            sb.append("${e.version}（${e.date}）")
            sb.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            e.items.forEach { sb.append("\n· ").append(it) }
        }
        val tv = TextView(this).apply {
            text = sb
            textSize = 15f
            setTextColor(onSurface)
            setLineSpacing(0f, 1.2f)
        }
        val hint = TextView(this).apply {
            text = "版本说明与 docs/devlog 对应；新版本发布时在 Changelog.kt 顶部追加。"
            textSize = 12f
            setTextColor(onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(28))
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(toolbar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, actionBarSize()))
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = toolbar.layoutParams
            lp.height = actionBarSize() + status.top
            toolbar.layoutParams = lp
            toolbar.updatePadding(top = status.top)
            scroll.updatePadding(bottom = navBars.bottom)
            insets
        }
    }
}
