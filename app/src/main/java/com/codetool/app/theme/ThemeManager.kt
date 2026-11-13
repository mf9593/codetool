package com.codetool.app.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import com.codetool.app.R

/**
 * 主题管理器：模式（浅色/深色/跟随系统）× 颜色（青/琥珀/翠绿/湖蓝）+ 随机。
 *
 * 设计原则：所有页面/布局都通过 `?attr/colorXxx` 引用颜色，不硬编码 `@color/xxx`。
 * 换主题只需调用 [setMode]/[setColor]，保存偏好后重建 Activity，颜色全局生效，无需重写 UI。
 *
 * 存储：codetool_theme 偏好
 *  - theme_mode  : light / dark / system
 *  - theme_color : cyan / amber / green / blue
 * 旧版 theme_id（light/dark/cyan/amber/green/blue）自动迁移。
 */
object ThemeManager {

    private const val PREFS = "codetool_theme"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_COLOR = "theme_color"
    private const val OLD_KEY = "theme_id"   // 旧版：单值

    /** 深浅模式：浅色 / 深色 / 跟随系统。 */
    enum class Mode(val id: String, val displayName: String) {
        LIGHT("light", "浅色"),
        DARK("dark", "深色"),
        SYSTEM("system", "跟随系统")
    }

    /** 主题颜色（每色有浅色 + 深色两套样式）。共 8 色。 */
    enum class Color(
        val id: String,
        val displayName: String,
        @StyleRes val lightStyle: Int,
        @StyleRes val darkStyle: Int,
        @ColorRes val lightSwatch: Int,
        @ColorRes val darkSwatch: Int
    ) {
        CYAN("cyan", "青",
            R.style.Theme_CodeTool, R.style.Theme_CodeTool_Dark,
            R.color.light_primary, R.color.primary),
        AMBER("amber", "琥珀",
            R.style.Theme_CodeTool_Light_Amber, R.style.Theme_CodeTool_Dark_Amber,
            R.color.light_amber_primary, R.color.amber_primary),
        GREEN("green", "翠绿",
            R.style.Theme_CodeTool_Light_Green, R.style.Theme_CodeTool_Dark_Green,
            R.color.light_green_primary, R.color.green_primary),
        BLUE("blue", "湖蓝",
            R.style.Theme_CodeTool_Light_Blue, R.style.Theme_CodeTool_Dark_Blue,
            R.color.light_blue_primary, R.color.blue_primary),
        VIOLET("violet", "紫罗兰",
            R.style.Theme_CodeTool_Light_Violet, R.style.Theme_CodeTool_Dark_Violet,
            R.color.light_violet_primary, R.color.violet_primary),
        ROSE("rose", "玫红",
            R.style.Theme_CodeTool_Light_Rose, R.style.Theme_CodeTool_Dark_Rose,
            R.color.light_rose_primary, R.color.rose_primary),
        ORANGE("orange", "橙",
            R.style.Theme_CodeTool_Light_Orange, R.style.Theme_CodeTool_Dark_Orange,
            R.color.light_orange_primary, R.color.orange_primary),
        SLATE("slate", "蓝灰",
            R.style.Theme_CodeTool_Light_Slate, R.style.Theme_CodeTool_Dark_Slate,
            R.color.light_slate_primary, R.color.slate_primary)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun modeOf(id: String?): Mode = when (id) {
        "dark" -> Mode.DARK
        "system" -> Mode.SYSTEM
        else -> Mode.LIGHT
    }

    private fun colorOf(id: String?): Color = when (id) {
        "amber" -> Color.AMBER
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "violet" -> Color.VIOLET
        "rose" -> Color.ROSE
        "orange" -> Color.ORANGE
        "slate" -> Color.SLATE
        else -> Color.CYAN
    }

    /** 当前模式（旧版 theme_id 迁移：light→LIGHT，dark/cyan→DARK）。 */
    fun currentMode(ctx: Context): Mode {
        val p = prefs(ctx)
        p.getString(KEY_MODE, null)?.let { return modeOf(it) }
        // 旧版迁移
        return when (p.getString(OLD_KEY, null)) {
            "light" -> Mode.LIGHT
            "dark", "cyan" -> Mode.DARK
            else -> Mode.LIGHT   // 默认浅色
        }
    }

    /** 当前颜色（旧版 theme_id 迁移：amber/green/blue 为对应深色）。 */
    fun currentColor(ctx: Context): Color {
        val p = prefs(ctx)
        p.getString(KEY_COLOR, null)?.let { return colorOf(it) }
        return colorOf(p.getString(OLD_KEY, null))   // 旧版颜色即颜色
    }

    /** 是否系统深色模式。 */
    fun isSystemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按模式+颜色解析最终样式。 */
    fun resolveStyle(ctx: Context): Int {
        val color = currentColor(ctx)
        return when (currentMode(ctx)) {
            Mode.LIGHT -> color.lightStyle
            Mode.DARK -> color.darkStyle
            Mode.SYSTEM -> if (isSystemDark(ctx)) color.darkStyle else color.lightStyle
        }
    }

    /** 当前应显示的色块（用于弹窗当前主题高亮）。 */
    fun currentSwatch(ctx: Context): Int {
        val color = currentColor(ctx)
        return if (currentMode(ctx) == Mode.DARK) color.darkSwatch
        else if (currentMode(ctx) == Mode.LIGHT) color.lightSwatch
        else if (isSystemDark(ctx)) color.darkSwatch else color.lightSwatch
    }

    /** 应用主题到 Activity。必须在 setContentView 之前调用（onCreate 开头）。 */
    fun apply(activity: AppCompatActivity) {
        activity.setTheme(resolveStyle(activity))
    }

    /** 给普通 Activity 用（合成器页不是 AppCompatActivity，但同样要跟随主题）。 */
    fun applyTo(activity: android.app.Activity) {
        activity.setTheme(resolveStyle(activity))
    }

    /** 切换模式（浅色/深色/跟随系统）：持久化 + 重建。 */
    fun setMode(activity: AppCompatActivity, mode: Mode) {
        prefs(activity).edit().putString(KEY_MODE, mode.id).apply()
        activity.recreate()
    }

    /** 切换颜色：持久化 + 重建。 */
    fun setColor(activity: AppCompatActivity, color: Color) {
        prefs(activity).edit().putString(KEY_COLOR, color.id).apply()
        activity.recreate()
    }

    /** 随机颜色（保留当前模式）。 */
    fun setRandomColor(activity: AppCompatActivity) {
        setColor(activity, Color.entries.random())
    }
}
