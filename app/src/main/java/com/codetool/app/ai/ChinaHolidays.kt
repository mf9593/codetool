package com.codetool.app.ai

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 中国法定节假日（放假日期，含调休放假）。
 *
 * 数据来源：开源数据集 chinese-days（https://github.com/vsme/chinese-days，MIT），
 * 内置 2025–2027 年，生成到 assets/scripts/model-holidays.json。
 * 周末调休上班日仍按周末算闲时（DeepSeek 口径是「周一至周五」，不按调休上班）。
 *
 * 后续年份更新方式（无需升级 App）：在共享目录写 share/model-holidays.json，
 * 内容为 ISO 日期数组，例如 ["2028-01-01","2028-01-02"]，会与内置数据合并。
 */
object ChinaHolidays {

    @Volatile private var builtin: Set<String>? = null
    @Volatile private var extraStamp: Long = Long.MIN_VALUE
    @Volatile private var extra: Set<String> = emptySet()

    private val DATE_RE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private fun readArray(text: String): Set<String> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).map { arr.getString(it) }.filter { DATE_RE.matches(it) }.toSet()
    }.getOrDefault(emptySet())

    fun builtin(ctx: Context): Set<String> {
        builtin?.let { return it }
        val s = runCatching {
            ctx.assets.open("scripts/model-holidays.json").bufferedReader().use { it.readText() }
        }.getOrNull()?.let { readArray(it) } ?: emptySet()
        builtin = s
        return s
    }

    /** 内置节假日 + 可选覆盖文件（share/model-holidays.json，按 mtime 缓存）。 */
    fun all(ctx: Context): Set<String> {
        val base = builtin(ctx)
        val f = File(ctx.applicationContext.dataDir, "share/model-holidays.json")
        val stamp = if (f.isFile) f.lastModified() else 0L
        if (stamp != extraStamp) {
            extraStamp = stamp
            extra = if (stamp == 0L) emptySet() else readArray(
                runCatching { f.readText() }.getOrDefault("")
            )
        }
        return if (extra.isEmpty()) base else base + extra
    }
}
