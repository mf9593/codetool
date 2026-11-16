package com.codetool.app.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * AI 模型定时切换（一律按北京时间 UTC+8，与 DeepSeek 计费口径一致）。
 *
 * 规则（DeepSeek 官方口径）：
 * - 高峰：周一至周五（不含中国法定节假日）北京时间 09:00–12:00、14:00–18:00；
 * - 其余时段（午休 12:00–14:00、夜间、周末、法定节假日全天）均为闲时；
 * - 高峰 / 闲时各绑定一个模型，到点自动切换，切换后只提示用户重启终端。
 *
 * 行为：
 * - 到点由 AlarmManager 触发（App 在后台也切），切换完成后再提示；
 * - 打开 App 时兜底补一次（休眠/被杀错过闹钟）；
 * - 检测到 mimo/opencode 仍在运行时，弹防误触窗（点外部/返回键不会消失）告知重启终端。
 *
 * 存储：共享桥目录 share/model-schedule.json（容器内 /usr/share/tawc/model-schedule.json），
 * MCP（codetool-mcp.py 的 model_schedule_* 工具）读写同一份规则。
 */
object ModelScheduleManager {

    const val ACTION_APPLY = "com.codetool.app.action.MODEL_SCHEDULE_APPLY"
    const val CHANNEL = "model_schedule"

    private const val FILE = "model-schedule.json"
    private const val REQ_CODE = 0x4D53   // "MS"
    private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 定时切换规则。两段高峰均支持跨零点；周末/法定节假日可整日按闲时。 */
    data class Schedule(
        var enabled: Boolean = false,
        /** 第一段高峰（默认 DeepSeek 上午段）。 */
        var peak1Start: String = "09:00",
        var peak1End: String = "12:00",
        /** 第二段高峰（默认 DeepSeek 下午段）；开始=结束表示不启用该段。 */
        var peak2Start: String = "14:00",
        var peak2End: String = "18:00",
        var peakModel: String = "",
        var peakTarget: String = "opencode",
        var offModel: String = "",
        var offTarget: String = "opencode",
        /** 周六 / 周日全天按闲时处理（默认开）。 */
        var weekendOff: Boolean = true,
        /** 中国法定节假日全天按闲时处理（默认开）。 */
        var holidayOff: Boolean = true,
        /** 法定节假日日期集合（不落盘，由 ChinaHolidays 注入）。 */
        val holidayDates: Set<String> = emptySet(),
    )

    fun file(ctx: Context): File =
        File(ctx.applicationContext.dataDir, "share/$FILE")

    fun load(ctx: Context): Schedule {
        val holidays = ChinaHolidays.all(ctx)
        val f = file(ctx)
        if (!f.isFile) return Schedule(holidayDates = holidays)
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            ?: return Schedule(holidayDates = holidays)
        return Schedule(
            enabled = o.optBoolean("enabled", false),
            peak1Start = o.optString("peak1Start", "09:00"),
            peak1End = o.optString("peak1End", "12:00"),
            peak2Start = o.optString("peak2Start", "14:00"),
            peak2End = o.optString("peak2End", "18:00"),
            peakModel = o.optString("peakModel", ""),
            peakTarget = o.optString("peakTarget", "opencode"),
            offModel = o.optString("offModel", ""),
            offTarget = o.optString("offTarget", "opencode"),
            weekendOff = o.optBoolean("weekendOff", true),
            holidayOff = o.optBoolean("holidayOff", true),
            holidayDates = holidays,
        )
    }

    /** 原子写（tmp → rename），MCP 侧同样读这份文件，避免读到半截。 */
    fun save(ctx: Context, s: Schedule) {
        runCatching {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val body = JSONObject()
                .put("enabled", s.enabled)
                .put("peak1Start", s.peak1Start)
                .put("peak1End", s.peak1End)
                .put("peak2Start", s.peak2Start)
                .put("peak2End", s.peak2End)
                .put("peakModel", s.peakModel)
                .put("peakTarget", s.peakTarget)
                .put("offModel", s.offModel)
                .put("offTarget", s.offTarget)
                .put("weekendOff", s.weekendOff)
                .put("holidayOff", s.holidayOff)
                .put("updated", System.currentTimeMillis())
                .toString(2)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) {
                f.writeText(body)
                tmp.delete()
            }
        }
    }

    /** "HH:mm" → 当天分钟数；非法返回 -1。 */
    fun parseTime(v: String): Int {
        val m = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").matchEntire(v.trim()) ?: return -1
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun fmtMinutes(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** [start, end) 区间匹配，支持跨零点（start > end 时视为跨天）。 */
    fun inRange(min: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> min in start until end
        else -> min >= start || min < end
    }

    fun beijingNow(): Calendar = Calendar.getInstance(TZ)

    fun fmtNow(now: Calendar = beijingNow()): String =
        "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

    fun dateKey(now: Calendar): String =
        "%04d-%02d-%02d".format(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        )

    /** 有效的高峰段（分钟）：时间合法且 start != end。 */
    private fun peakRanges(s: Schedule): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val p1s = parseTime(s.peak1Start)
        val p1e = parseTime(s.peak1End)
        if (p1s >= 0 && p1e >= 0 && p1s != p1e) out.add(p1s to p1e)
        val p2s = parseTime(s.peak2Start)
        val p2e = parseTime(s.peak2End)
        if (p2s >= 0 && p2e >= 0 && p2s != p2e) out.add(p2s to p2e)
        return out
    }

    /** 当前时段应使用的模型；关闭定时/未配置模型/时间非法时返回 null。 */
    data class ActiveSlot(
        val peak: Boolean,
        val weekend: Boolean,
        val holiday: Boolean,
        val model: String,
        val target: String,
    )

    fun isWeekend(now: Calendar): Boolean =
        now.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

    fun isHoliday(s: Schedule, now: Calendar): Boolean = dateKey(now) in s.holidayDates

    /** 某时刻是否高峰；规则关闭或时间非法返回 null。 */
    private fun slotPeak(s: Schedule, now: Calendar): Boolean? {
        if (!s.enabled) return null
        val ranges = peakRanges(s)
        if (ranges.isEmpty()) return null
        if (s.weekendOff && isWeekend(now)) return false
        if (s.holidayOff && isHoliday(s, now)) return false
        val min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return ranges.any { (start, end) -> inRange(min, start, end) }
    }

    private fun slotPeakAt(s: Schedule, millis: Long): Boolean? =
        slotPeak(s, Calendar.getInstance(TZ).apply { timeInMillis = millis })

    fun activeSlot(s: Schedule, now: Calendar = beijingNow()): ActiveSlot? {
        val peak = slotPeak(s, now) ?: return null
        val weekend = !peak && s.weekendOff && isWeekend(now)
        val holiday = !peak && s.holidayOff && !weekend && isHoliday(s, now)
        val model = if (peak) s.peakModel else s.offModel
        val target = if (peak) s.peakTarget else s.offTarget
        if (model.isBlank()) return null
        return ActiveSlot(peak, weekend, holiday, model, target)
    }

    fun currentModel(ctx: Context, target: String): String? =
        AiModelManager.listModels(ctx).firstOrNull { it.target == target && it.current }?.name

    /**
     * 容器里正在运行的 mimo / opencode CLI 名称（去重）。
     * 容器进程与 App 同 uid（chroot 不隔离进程视图），直接读 /proc/<pid>/cmdline。
     * 只有还在跑 CLI 时才需要提示用户重启终端——没跑的话下次启动自然会用新模型。
     */
    fun runningClis(): List<String> {
        val names = listOf("mimo", "opencode")
        val found = linkedSetOf<String>()
        val dirs = runCatching { File("/proc").listFiles() }.getOrNull() ?: return emptyList()
        for (dir in dirs) {
            if (dir.name.isEmpty() || !dir.name.all { it.isDigit() }) continue
            val bytes = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            val args = String(bytes, Charsets.UTF_8).split('\u0000')
            for (raw in args) {
                val a = raw.trim()
                if (a.isEmpty()) continue
                for (n in names) {
                    if (a == n || a.endsWith("/$n") || a.contains("/$n/") ||
                        a.endsWith("/$n.js") || a.startsWith("$n ")
                    ) {
                        found.add(n)
                    }
                }
            }
            if (found.size == names.size) break
        }
        return found.toList()
    }

    fun isCliRunning(): Boolean = runningClis().isNotEmpty()

    /** 拼「请重启终端」提示：切换详情 + 实际检测到的 CLI。 */
    fun restartPromptText(changeMsg: String, clis: List<String>): String =
        "$changeMsg\n\n检测到 ${clis.joinToString("、")} 仍在运行：正在运行的会话仍使用旧模型。\n" +
            "请关闭当前终端会话后重新打开，新模型才会生效。"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("model_schedule", Context.MODE_PRIVATE)

    /** 记录待提示文案（后台闹钟触发时 App 可能不在前台，回前台再弹）。 */
    fun markRestartPrompt(ctx: Context, text: String) {
        prefs(ctx).edit()
            .putLong("restart_prompt_at", System.currentTimeMillis())
            .putString("restart_prompt_msg", text)
            .apply()
    }

    /** 取出并清除待提示文案；没有则返回 null。只有 App 前台的弹窗路径调用。 */
    fun consumeRestartPrompt(ctx: Context): String? {
        val p = prefs(ctx)
        if (!p.contains("restart_prompt_at")) return null
        val text = p.getString("restart_prompt_msg", null)
        p.edit().remove("restart_prompt_at").remove("restart_prompt_msg").apply()
        return text ?: "模型已按定时规则切换，请重新打开终端以使用新模型。"
    }

    /**
     * 前台自检入口：应用当前时段 + 重排闹钟；若本次发生切换且仍有 CLI 在跑，
     * 返回需要展示给用户的提示文案（调用方弹防误触弹窗），否则 null。
     */
    fun runAndCheckPrompt(ctx: Context): String? {
        val (switched, msg) = applyNow(ctx)
        rescheduleAlarm(ctx)
        if (switched && isCliRunning()) {
            markRestartPrompt(ctx, restartPromptText(msg, runningClis()))
        }
        return consumeRestartPrompt(ctx)
    }

    /**
     * 应用当前时段（幂等：已是目标模型则不动）。
     * 返回 (是否发生切换, 说明)。
     */
    fun applyNow(ctx: Context): Pair<Boolean, String> {
        val s = load(ctx)
        val slot = activeSlot(s) ?: return false to ""
        val cur = currentModel(ctx, slot.target)
        if (cur == slot.model) return false to ""
        val ok = AiModelManager.setCurrent(ctx, slot.model, slot.target)
        if (!ok) return false to "定时切换失败：目标模型「${slot.model}」不存在或已停用"
        val label = when {
            slot.peak -> "高峰"
            slot.weekend -> "周末闲时"
            slot.holiday -> "法定节假日闲时"
            else -> "闲时"
        }
        return true to "已按${label}时段（北京时间 ${fmtNow()}）把 ${slot.target} 模型从「${cur ?: "未设置"}」切换为「${slot.model}」"
    }

    /**
     * 距下一次「时段变化」的毫秒数。
     * 候选点 = 每天 00:00（周末/节假日/跨天变化）+ 各高峰段开始/结束；
     * 逐个比较前后 1 秒的时段，第一个发生变化的即下一次边界。
     * 扫描 40 天：覆盖春节等超长假期（假期内无边界，需跨过整段假期）。
     */
    fun nextBoundaryMillis(s: Schedule, now: Calendar = beijingNow()): Long? {
        val ranges = peakRanges(s)
        if (ranges.isEmpty()) return null
        val start = now.timeInMillis
        val minutes = (listOf(0) + ranges.flatMap { listOf(it.first, it.second) })
            .distinct().sorted()
        for (day in 0..40) {
            for (m in minutes) {
                val cand = Calendar.getInstance(TZ).apply {
                    timeInMillis = start
                    add(Calendar.DAY_OF_YEAR, day)
                    set(Calendar.HOUR_OF_DAY, m / 60)
                    set(Calendar.MINUTE, m % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (cand <= start) continue
                val before = slotPeakAt(s, cand - 1000)
                val after = slotPeakAt(s, cand)
                if (before != null && after != null && before != after) return cand - start
            }
        }
        return null
    }

    // ---------------- AlarmManager 调度 ----------------

    fun rescheduleAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = applyIntent(ctx)
        runCatching { am.cancel(pi) }
        val s = load(ctx)
        if (!s.enabled) return
        val delay = nextBoundaryMillis(s) ?: return
        val at = System.currentTimeMillis() + delay
        runCatching {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    fun cancelAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(applyIntent(ctx)) }
    }

    private fun applyIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, ModelScheduleReceiver::class.java).setAction(ACTION_APPLY)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ_CODE, intent, flags)
    }
}
