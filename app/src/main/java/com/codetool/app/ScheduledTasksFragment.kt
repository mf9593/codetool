package com.codetool.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codetool.app.ai.AiModelManager
import com.codetool.app.ai.ModelScheduleManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

/**
 * 定时任务页（工具页独立 tab）：当前为 AI 模型定时切换，后续定时功能在此扩展。
 *
 * 定时切换完整设置从 AiModelsFragment 迁到这里，AI 模型页只留紧凑摘要入口。
 * 触发链路（AlarmManager → ModelScheduleReceiver → 切换 + 提示）不变。
 */
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduledTasksFragment : Fragment() {

    private lateinit var switchSchedule: MaterialSwitch
    private lateinit var tvScheduleNow: MaterialTextView
    private lateinit var btnPeak1Start: MaterialButton
    private lateinit var btnPeak1End: MaterialButton
    private lateinit var btnPeak2Start: MaterialButton
    private lateinit var btnPeak2End: MaterialButton
    private lateinit var tvOffRange: MaterialTextView
    private lateinit var btnPeakModel: MaterialButton
    private lateinit var btnOffModel: MaterialButton
    private lateinit var switchWeekendOff: MaterialSwitch
    private lateinit var switchHolidayOff: MaterialSwitch
    /** 代码里回滚开关状态时，避免再次触发 OnCheckedChangeListener 写盘。 */
    private var scheduleSuppress = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_scheduled_tasks, container, false)
        switchSchedule = v.findViewById(R.id.switchSchedule)
        tvScheduleNow = v.findViewById(R.id.tvScheduleNow)
        btnPeak1Start = v.findViewById(R.id.btnPeak1Start)
        btnPeak1End = v.findViewById(R.id.btnPeak1End)
        btnPeak2Start = v.findViewById(R.id.btnPeak2Start)
        btnPeak2End = v.findViewById(R.id.btnPeak2End)
        tvOffRange = v.findViewById(R.id.tvOffRange)
        btnPeakModel = v.findViewById(R.id.btnPeakModel)
        btnOffModel = v.findViewById(R.id.btnOffModel)
        switchWeekendOff = v.findViewById(R.id.switchWeekendOff)
        switchHolidayOff = v.findViewById(R.id.switchHolidayOff)
        setupScheduleCard()
        return v
    }

    override fun onResume() {
        super.onResume()
        if (::switchSchedule.isInitialized) renderSchedule()
    }

    // ---------------- AI 模型定时切换 ----------------

    private fun setupScheduleCard() {
        val ctx = requireContext()
        // 低危：同一份配置只读一次（原重复 load 3 次读盘）。
        val s = ModelScheduleManager.load(ctx)
        switchSchedule.isChecked = s.enabled
        switchWeekendOff.isChecked = s.weekendOff
        switchHolidayOff.isChecked = s.holidayOff
        switchWeekendOff.setOnCheckedChangeListener { _, checked ->
            if (scheduleSuppress) return@setOnCheckedChangeListener
            ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { weekendOff = checked })
            afterScheduleChange()
        }
        switchHolidayOff.setOnCheckedChangeListener { _, checked ->
            if (scheduleSuppress) return@setOnCheckedChangeListener
            ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { holidayOff = checked })
            afterScheduleChange()
        }
        switchSchedule.setOnCheckedChangeListener { _, checked ->
            if (scheduleSuppress) return@setOnCheckedChangeListener
            val cur = ModelScheduleManager.load(ctx).apply { enabled = checked }
            if (checked && (cur.peakModel.isBlank() || cur.offModel.isBlank())) {
                toast("先选好高峰模型和闲时模型再启用")
                scheduleSuppress = true
                switchSchedule.isChecked = false
                scheduleSuppress = false
                return@setOnCheckedChangeListener
            }
            ModelScheduleManager.save(ctx, cur)
            if (checked) {
                ensureNotifPermission()
                val prompt = ModelScheduleManager.runAndCheckPrompt(ctx)
                if (prompt != null) {
                    (activity as? MainActivity)?.showModelRestartPrompt(prompt)
                } else {
                    toast("已启用定时切换")
                }
            } else {
                ModelScheduleManager.cancelAlarm(ctx)
                toast("已关闭定时切换")
            }
            renderSchedule()
        }
        // 两段高峰各自可改；开始与结束相同表示停用该段
        btnPeak1Start.setOnClickListener {
            pickTime(ModelScheduleManager.load(ctx).peak1Start) { t ->
                ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { peak1Start = t })
                afterScheduleChange()
            }
        }
        btnPeak1End.setOnClickListener {
            pickTime(ModelScheduleManager.load(ctx).peak1End) { t ->
                ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { peak1End = t })
                afterScheduleChange()
            }
        }
        btnPeak2Start.setOnClickListener {
            pickTime(ModelScheduleManager.load(ctx).peak2Start) { t ->
                ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { peak2Start = t })
                afterScheduleChange()
            }
        }
        btnPeak2End.setOnClickListener {
            pickTime(ModelScheduleManager.load(ctx).peak2End) { t ->
                ModelScheduleManager.save(ctx, ModelScheduleManager.load(ctx).apply { peak2End = t })
                afterScheduleChange()
            }
        }
        btnPeakModel.setOnClickListener { pickScheduleModel(peak = true) }
        btnOffModel.setOnClickListener { pickScheduleModel(peak = false) }
        renderSchedule()
    }

    /** 规则变更后：立即按新规则应用一次 + 重排边界闹钟；有 CLI 在跑就弹重启提示。 */
    private fun afterScheduleChange() {
        val ctx = requireContext()
        val s = ModelScheduleManager.load(ctx)
        if (s.enabled) {
            ModelScheduleManager.runAndCheckPrompt(ctx)?.let {
                (activity as? MainActivity)?.showModelRestartPrompt(it)
            }
        }
        renderSchedule()
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val parsed = ModelScheduleManager.parseTime(current).let { if (it < 0) 8 * 60 else it }
        android.app.TimePickerDialog(
            requireContext(),
            { _, h, m -> onPicked(ModelScheduleManager.fmtMinutes(h * 60 + m)) },
            parsed / 60, parsed % 60, true
        ).show()
    }

    private fun pickScheduleModel(peak: Boolean) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            // 主线程 IO：listModels + load 读盘放 IO 线程，避免打开弹窗时主线程读盘卡 UI。
            val (all, s) = withContext(Dispatchers.IO) {
                val all = AiModelManager.listModels(ctx.applicationContext).filter { it.enabled }
                val s = ModelScheduleManager.load(ctx.applicationContext)
                all to s
            }
            if (!isAdded) return@launch
            if (all.isEmpty()) {
                toast("没有可用模型，先去「AI 模型」页新增")
                return@launch
            }
            val curName = if (peak) s.peakModel else s.offModel
            val curTarget = if (peak) s.peakTarget else s.offTarget
            // 标签显示服务商（provider），不再显示 target（mimo/opencode）：定时切换现在
            // 两个 CLI 同时生效，标 target 会误导成「只切这个 CLI」；provider 才能区分同名模型。
            val labels = all.map { "${it.name}（${it.provider}）" }.toTypedArray()
            MaterialAlertDialogBuilder(ctx)
                .setTitle(if (peak) "选择高峰模型" else "选择闲时模型")
                .setSingleChoiceItems(labels, all.indexOfFirst { it.name == curName && it.target == curTarget }) { d, which ->
                    val m = all[which]
                    val cur = ModelScheduleManager.load(ctx)
                    if (peak) { cur.peakModel = m.name; cur.peakTarget = m.target }
                    else { cur.offModel = m.name; cur.offTarget = m.target }
                    ModelScheduleManager.save(ctx, cur)
                    d.dismiss()
                    afterScheduleChange()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 刷新定时切换卡片的状态文本（当前北京时间 / 当前时段 / 下次切换）。 */
    private fun renderSchedule() {
        val ctx = requireContext()
        val s = ModelScheduleManager.load(ctx)
        // 规则可能被 MCP / 其它页面改动：同步三个开关（抑制监听器避免回写）
        if (switchSchedule.isChecked != s.enabled || switchWeekendOff.isChecked != s.weekendOff ||
            switchHolidayOff.isChecked != s.holidayOff
        ) {
            scheduleSuppress = true
            switchSchedule.isChecked = s.enabled
            switchWeekendOff.isChecked = s.weekendOff
            switchHolidayOff.isChecked = s.holidayOff
            scheduleSuppress = false
        }
        btnPeak1Start.text = s.peak1Start
        btnPeak1End.text = s.peak1End
        btnPeak2Start.text = s.peak2Start
        btnPeak2End.text = s.peak2End
        tvOffRange.text = "其余所有时段（自动）"
        btnPeakModel.text = "高峰模型：" + s.peakModel.ifBlank { "未选择" }
        btnOffModel.text = "闲时模型：" + s.offModel.ifBlank { "未选择" }

        val p1s = ModelScheduleManager.parseTime(s.peak1Start)
        val p1e = ModelScheduleManager.parseTime(s.peak1End)
        val p2s = ModelScheduleManager.parseTime(s.peak2Start)
        val p2e = ModelScheduleManager.parseTime(s.peak2End)
        if (p1s < 0 || p1e < 0 || p2s < 0 || p2e < 0) {
            tvScheduleNow.text = "时间段无效：时间格式应为 HH:mm"
            return
        }
        val seg1Off = p1s == p1e
        val seg2Off = p2s == p2e
        if (seg1Off && seg2Off) {
            tvScheduleNow.text = "时间段无效：至少启用一段高峰（开始与结束不能相同）"
            return
        }

        val now = ModelScheduleManager.beijingNow()
        val slot = ModelScheduleManager.activeSlot(s, now)
        val state = when {
            !s.enabled -> "未启用"
            slot == null -> "已启用（模型未选全）"
            slot.peak -> "当前高峰 → ${slot.model}"
            slot.holiday -> "当前法定节假日闲时 → ${slot.model}"
            slot.weekend -> "当前周末闲时 → ${slot.model}"
            else -> "当前闲时 → ${slot.model}"
        }
        val next = if (s.enabled) ModelScheduleManager.nextBoundaryMillis(s, now) else null
        val nextTxt = next?.let {
            val t = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = System.currentTimeMillis() + it
            }
            val hm = ModelScheduleManager.fmtMinutes(
                t.get(java.util.Calendar.HOUR_OF_DAY) * 60 + t.get(java.util.Calendar.MINUTE))
            " · 下次切换 $hm"
        } ?: ""
        val offSegs = listOfNotNull(
            "第一段".takeIf { seg1Off },
            "第二段".takeIf { seg2Off },
        )
        val offTxt = if (offSegs.isEmpty()) "" else " · ${offSegs.joinToString("、")}已停用"
        val cliTxt = if (ModelScheduleManager.isCliRunning()) " · CLI 运行中" else ""
        tvScheduleNow.text = "当前北京时间 ${ModelScheduleManager.fmtNow(now)} · $state$nextTxt$offTxt$cliTxt"
    }

    /** Android 13+ 通知权限：定时切换用通知提醒重新打开终端，未授权则提醒会看不到。 */
    private fun ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0x4D53)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
