package com.codetool.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 应用内更新 UI：
 * - [autoCheck]：首页静默检查（6 小时一次），仅发现未忽略的新版本时弹窗。
 * - [manualCheck]：关于页手动检查，有进度与结果反馈。
 */
object UpdateUi {

    private const val PREFS = "codetool_update"
    private const val KEY_IGNORED_TAG = "ignored_tag"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val COOLDOWN_MS = 6L * 3600 * 1000

    /** 静默自动检查；网络失败不打扰用户。 */
    fun autoCheck(fragment: Fragment) {
        val ctx = fragment.context ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val r = UpdateChecker.check(currentVersion(ctx))
            if (r is UpdateChecker.Result.Update &&
                r.release.tag != prefs.getString(KEY_IGNORED_TAG, null)
            ) {
                fragment.activity
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?.let { showUpdateDialog(it, r.release, prefs) }
            }
        }
    }

    /** 手动检查：显示进度 Toast 与结果。 */
    fun manualCheck(activity: Activity, scope: CoroutineScope) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        scope.launch {
            when (val r = UpdateChecker.check(currentVersion(activity))) {
                is UpdateChecker.Result.Update -> showUpdateDialog(
                    activity, r.release,
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                )
                is UpdateChecker.Result.UpToDate ->
                    Toast.makeText(activity, "当前已是最新版本（${currentVersion(activity)}）", Toast.LENGTH_SHORT).show()
                is UpdateChecker.Result.NoRelease ->
                    Toast.makeText(activity, "GitHub 暂未发布 Release", Toast.LENGTH_SHORT).show()
                is UpdateChecker.Result.Failure ->
                    Toast.makeText(activity, "检查失败：${r.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "0.0.0"

    private fun showUpdateDialog(
        activity: Activity,
        rel: UpdateChecker.Release,
        prefs: SharedPreferences
    ) {
        val sizeInfo = rel.apkSizeMb?.let { "（约 %.1f MB）".format(it) } ?: ""
        val msg = buildString {
            append("当前 ").append(currentVersion(activity))
            append(" → 最新 ").append(rel.tag.removePrefix("v")).append(sizeInfo).append("\n")
            val notes = rel.notes.trim()
            if (notes.isNotEmpty()) {
                append("\n")
                append(if (notes.length > 600) notes.take(600) + "…" else notes)
            }
        }
        val directUrl = rel.apkUrl ?: rel.pageUrl
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (rel.isPrerelease) "发现预发行版本 ${rel.tag}" else "发现新版本 ${rel.tag}")
            .setMessage(msg)
            // 按钮顺序（Material 约定：negative 左 / neutral 中 / positive 右）：
            //   取消（negative，仅关闭）· 不再提示（neutral，忽略此版本）· 下载更新（positive，跳转下载）
            // 「不再提示」从中选框改成中立按钮：去掉勾选框后不存在对齐问题，三个按钮语义也更清晰。
            .setNegativeButton("取消", null)
            .setNeutralButton("不再提示") { _, _ ->
                prefs.edit().putString(KEY_IGNORED_TAG, rel.tag).apply()
            }
            .setPositiveButton("下载更新") { _, _ ->
                open(activity, directUrl)
            }
        builder.show()
    }

    private fun open(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(activity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 预览更新弹窗（调试用）：用假 Release 直接弹「发现新版本」，不上网。
     * 触发：adb `--ez codetool_debug_update_dialog true`，或关于页「检查更新」行长按。
     */
    fun previewDialog(activity: Activity) {
        val mock = UpdateChecker.Release(
            tag = "v9.9.9",
            title = "预览版本",
            notes = "这是「发现新版本」弹窗的预览（不上网）。\n\n" +
                "· 用于确认按钮布局与「忽略此版本」勾选框。\n" +
                "· 正式检查请用「检查更新」。",
            pageUrl = "https://github.com/mf9593/codetool/releases",
            apkUrl = "https://github.com/mf9593/codetool/releases/download/v9.9.9/app-debug.apk",
            apkSizeMb = 66.0,
            isPrerelease = false,
        )
        showUpdateDialog(
            activity, mock,
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        )
    }
}
