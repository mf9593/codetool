package com.codetool.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.CheckBox
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
        // 「忽略此版本」做成勾选框，避免按钮超过 3 个
        val ignore = CheckBox(activity).apply {
            text = "不再提示此版本"
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (rel.isPrerelease) "发现预发行版本 ${rel.tag}" else "发现新版本 ${rel.tag}")
            .setMessage(msg)
            .setView(ignore)
            .setPositiveButton("去下载") { _, _ ->
                if (ignore.isChecked) prefs.edit().putString(KEY_IGNORED_TAG, rel.tag).apply()
                open(activity, rel.apkUrl ?: rel.pageUrl)
            }
            .setNegativeButton("以后再说") { _, _ ->
                if (ignore.isChecked) prefs.edit().putString(KEY_IGNORED_TAG, rel.tag).apply()
            }
        // 有 APK 直链时给国内镜像（GitHub 资产直连容易失败）
        if (rel.apkUrl != null) {
            builder.setNeutralButton("镜像下载") { _, _ ->
                if (ignore.isChecked) prefs.edit().putString(KEY_IGNORED_TAG, rel.tag).apply()
                open(activity, "https://gh-proxy.com/" + rel.apkUrl)
            }
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
}
