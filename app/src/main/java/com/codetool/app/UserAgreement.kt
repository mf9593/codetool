package com.codetool.app

import android.content.Context

/**
 * 用户协议 / 免责声明管理（数据与状态）。
 * - 展示统一走全屏页 [AgreementActivity]：首次启动强制「勾选同意 + 下一步」，否则退出；
 *   已同意后可在「关于」页入口再次查看全文。
 * - 正文单一来源：res/raw/user_agreement.txt（GitHub 仓库 docs/USER_AGREEMENT.md 同源同步）。
 */
object UserAgreement {

    /** 协议版本：正文有实质变更时 +1，已同意旧版的用户会重新走一次同意流程。 */
    const val VERSION = 1
    private const val PREFS = "user_agreement"
    private const val KEY_ACCEPTED_VERSION = "accepted_version"

    /** 是否已接受当前版本的协议。 */
    fun isAccepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= VERSION

    /** 记录「已同意当前版本」（AgreementActivity 点下一步时调用）。 */
    fun markAccepted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ACCEPTED_VERSION, VERSION).apply()
    }

    /** 读取协议正文（内置 raw，离线可用）。 */
    fun loadText(ctx: Context): String = runCatching {
        ctx.resources.openRawResource(R.raw.user_agreement).bufferedReader().use { it.readText() }
    }.getOrDefault("（协议正文加载失败）")
}
