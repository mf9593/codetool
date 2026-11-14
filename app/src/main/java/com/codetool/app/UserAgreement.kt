package com.codetool.app

import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 用户协议 / 免责声明管理。
 * - 首次启动（或协议版本更新后）强制弹窗，必须点「同意」才能用，否则退出。
 * - 已同意后可在「关于」页再次查看全文。
 * - 正文单一来源：res/raw/user_agreement.txt（GitHub 仓库 docs/USER_AGREEMENT.md 同源同步）。
 */
object UserAgreement {

    /** 协议版本：正文有实质变更时 +1，已同意旧版的用户会重新弹一次。 */
    const val VERSION = 1
    private const val PREFS = "user_agreement"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    /** 是否已接受当前版本的协议。 */
    fun isAccepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= VERSION

    private fun markAccepted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCEPTED_VERSION, VERSION).apply()
    }

    /** 读取协议正文（内置 raw，离线可用）。 */
    fun loadText(ctx: Context): String = runCatching {
        ctx.resources.openRawResource(R.raw.user_agreement).bufferedReader().use { it.readText() }
    }.getOrDefault("（协议正文加载失败）")

    /**
     * 首次启动强制同意弹窗。
     * @param onAccepted 点了「同意」（内部已记录，调用方无需再处理）
     * @param onDeclined 点了「不同意」或按返回（调用方应退出）
     */
    fun showFirstLaunchDialog(activity: android.app.Activity,
                              onAccepted: () -> Unit = {},
                              onDeclined: () -> Unit = {}) {
        val tv = makeTextView(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("用户协议与免责声明")
            .setView(wrapScrollable(activity, tv))
            .setCancelable(false) // 必须明确选择，返回键/点外部不关闭
            .setPositiveButton("同意") { d, _ ->
                d.dismiss()
                markAccepted(activity)
                onAccepted()
            }
            .setNegativeButton("不同意并退出") { d, _ ->
                d.dismiss()
                onDeclined()
            }
            .show()
    }

    /** 关于页「查看协议」：只读弹窗，可关闭。 */
    fun showViewerDialog(activity: android.app.Activity) {
        val tv = makeTextView(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("用户协议与免责声明（v$VERSION）")
            .setView(wrapScrollable(activity, tv))
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun makeTextView(ctx: Context): TextView = TextView(ctx).apply {
        text = loadText(ctx)
        textSize = 14f
        setLineSpacing(0f, 1.25f)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        // 协议里没有链接，纯文本即可；如日后加 URL 可启用 Linkify
    }

    /** 包一层 ScrollView，长文可滚动（弹窗高度有限）。 */
    private fun wrapScrollable(ctx: Context, tv: TextView): View =
        ScrollView(ctx).apply { addView(tv) }
}
