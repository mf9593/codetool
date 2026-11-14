package com.codetool.app

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.codetool.app.theme.ThemeManager
import com.codetool.app.util.UpdateUi
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * 关于页（二级页面）：独立 Activity，带标题栏 + 返回按钮。
 *
 * 背景/文字全部通过 Material3 主题属性（colorXxx）引用，跟随当前主题
 * （浅色→浅底深字，深色→深底浅字）。用 [android.app.Activity]（而非
 * AppCompatActivity）避免 Material3.Light 主题被 AppCompat 拒绝的崩溃；
 * 主题由 [ThemeManager.resolveStyle] 在 super.onCreate 前应用。
 */
class AboutActivity : Activity() {

    /** 普通 Activity 没有 lifecycleScope，自建并在 onDestroy 取消。 */
    private val scope = MainScope()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 前应用当前主题（与 MainActivity 的 ThemeManager.apply 一致）
        setTheme(ThemeManager.resolveStyle(this))
        super.onCreate(savedInstanceState)

        // 沉浸式：内容延伸到系统栏下；状态栏/导航栏透明，由标题栏/页面背景接管，
        // 使状态栏区域显示标题栏颜色（"状态栏跟随标题栏"）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val versionName = pInfo?.versionName ?: "debug"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: 0L
        } else {
            pInfo?.versionCode?.toLong() ?: 0L
        }

        // 背景/文字全部跟随当前主题（浅色→浅底深字，深色→深底浅字）。
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
        val onSurfaceVariant = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF44474A.toInt())
        val surfaceContainer = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainer, 0xFFE8E8E8.toInt())
        val surfaceCard = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerHigh, surfaceContainer)
        val bg = MaterialColors.getColor(
            this, android.R.attr.colorBackground, 0xFFFAFAFA.toInt())
        val accent = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFF00505A.toInt())
        // 半透明分隔线（基于主文字色）。
        val divider = (onSurface and 0x00FFFFFF) or (0x22 shl 24)
        // 状态栏图标深浅随标题栏亮度（浅色标题栏→深色图标，深色→浅色）。
        val lightStatus = androidx.core.graphics.ColorUtils.calculateLuminance(surfaceContainer) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightStatus
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = lightStatus

        // ─── 标题栏：标题 + 返回按钮 ───
        val toolbar = MaterialToolbar(this).apply {
            title = "关于"
            setTitleTextColor(onSurface)
            setBackgroundColor(surfaceContainer)
            elevation = 0f
            // 返回箭头（appcompat 内置 up 指示器），主题色着色。
            val backIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@AboutActivity, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            backIcon?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it, onSurface) }
            setNavigationIcon(backIcon)
            setNavigationContentDescription("返回")
            setNavigationOnClickListener { finish() }
        }

        // ─── 头部：图标 + 名称 + 版本标签 ───
        val headerCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            setRadius(dp(20).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also { it.rightMargin = dp(16) }
        }.also { headerLayout.addView(it) }
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        TextView(this).apply {
            text = "CodeTool"
            textSize = 20f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { v -> titleLayout.addView(v) }
        TextView(this).apply {
            text = "Android 终端容器 · 版本 $versionName ($versionCode)"
            textSize = 13f
            setTextColor(onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        }.also { v -> titleLayout.addView(v) }
        headerLayout.addView(titleLayout)
        headerCard.addView(headerLayout)

        // ─── 信息卡片 ───
        val infoCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        TextView(this).apply {
            text = "应用信息"
            textSize = 14f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }.also { infoLayout.addView(it) }
        val infoRows = listOf(
            "包名" to packageName,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "设备" to Build.MODEL,
            "运行时" to "tawcroot + tawc 合成器",
        )
        infoRows.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.topMargin = dp(10); it.bottomMargin = dp(10) }
                    setBackgroundColor(divider)
                }.also { infoLayout.addView(it) }
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }.also { row ->
                TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(onSurfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(
                        dp(72), ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }.also { row.addView(it) }
                TextView(this).apply {
                    text = value
                    textSize = 13f
                    setTextColor(onSurface)
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }.also { row.addView(it) }
                infoLayout.addView(row)
            }
        }
        infoCard.addView(infoLayout)

        // ─── 检查更新卡片（GitHub Releases）───
        val updateCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val updateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { UpdateUi.manualCheck(this@AboutActivity, scope) }
        }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_refresh)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setColorFilter(accent)
        }.also { updateRow.addView(it) }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(12) }
            addView(TextView(this@AboutActivity).apply {
                text = "检查更新"
                textSize = 14f
                setTextColor(onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@AboutActivity).apply {
                text = "当前 $versionName · 从 GitHub 检查"
                textSize = 13f
                setTextColor(onSurfaceVariant)
            })
        }.also { updateRow.addView(it) }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setColorFilter(onSurfaceVariant)
        }.also { updateRow.addView(it) }
        updateCard.addView(updateRow)

        // ─── 用户协议与免责声明卡片 ───
        val agreementCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val agreementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { UserAgreement.showViewerDialog(this@AboutActivity) }
        }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setColorFilter(accent)
        }.also { agreementRow.addView(it) }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(12) }
            addView(TextView(this@AboutActivity).apply {
                text = "用户协议与免责声明"
                textSize = 14f
                setTextColor(onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@AboutActivity).apply {
                text = "查看完整协议（v${UserAgreement.VERSION}）"
                textSize = 13f
                setTextColor(onSurfaceVariant)
            })
        }.also { agreementRow.addView(it) }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setColorFilter(onSurfaceVariant)
        }.also { agreementRow.addView(it) }
        agreementCard.addView(agreementRow)

        // ─── 开源项目卡片 ───
        val ossCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val ossLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        TextView(this).apply {
            text = "参考开源项目"
            textSize = 14f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }.also { ossLayout.addView(it) }
        val projects = listOf(
            "tawc / tawcroot" to "https://github.com/taowen/tawc",
            "Smithay" to "https://github.com/smithay/smithay",
            "Termux" to "https://github.com/termux/termux-app",
            "libhybris" to "https://github.com/libhybris/libhybris",
            "labwc" to "https://github.com/labwc/labwc",
        )
        projects.forEachIndexed { i, (name, url) ->
            if (i > 0) {
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.topMargin = dp(6); it.bottomMargin = dp(6) }
                    setBackgroundColor(divider)
                }.also { ossLayout.addView(it) }
            }
            TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(accent)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }.also { ossLayout.addView(it) }
        }
        ossCard.addView(ossLayout)

        // ─── QQ 交流群卡片（点击跳转手机 QQ 群）───
        val qqCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(16) }
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val qqRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { openQqGroup() }
        }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_group)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setColorFilter(accent)
        }.also { qqRow.addView(it) }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(12) }
            addView(TextView(this@AboutActivity).apply {
                text = "QQ交流群"
                textSize = 14f
                setTextColor(onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@AboutActivity).apply {
                text = "群号 559711374 · 点击加入"
                textSize = 13f
                setTextColor(onSurfaceVariant)
            })
        }.also { qqRow.addView(it) }
        ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setColorFilter(onSurfaceVariant)
        }.also { qqRow.addView(it) }
        qqCard.addView(qqRow)

        // ─── 滚动容器：背景跟随主题（隐藏滚动条，内容超出仍可滑动）───
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            setPadding(dp(20), dp(20), dp(20), dp(28))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@AboutActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(headerCard)
                addView(infoCard)
                addView(updateCard)
                addView(ossCard)
                addView(qqCard)
            })
        }

        // ─── 根布局：标题栏 + 滚动内容 ───
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(toolbar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // 系统栏 inset：顶部让标题栏让出状态栏高度（标题栏延伸到状态栏下），
        // 底部让滚动内容补足手势条，滚动到底不被遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // 标题栏延伸到状态栏下：高度 = 48dp + 状态栏高度，标题内容让出状态栏。
            val lp = toolbar.layoutParams
            lp.height = dp(48) + status.top
            toolbar.layoutParams = lp
            toolbar.updatePadding(top = status.top)
            // 底部间距 0：内容直达底部（小白条沉浸，透明导航栏显示页面背景）
            scroll.updatePadding(bottom = navBars.bottom)
            insets
        }
    }

    /** 跳转 QQ 群 559711374（mqqwpa 协议；未装手机 QQ 时提示）。 */
    private fun openQqGroup() {
        val url = "mqqwpa://im/chat?chat_type=group&uin=559711374"
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "未检测到手机 QQ，请先安装", Toast.LENGTH_SHORT).show()
        }
    }
}
