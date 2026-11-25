package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.codetool.app.util.AppSettings
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页：运行日志（codetool.log） + 崩溃日志（files/crash 下的时间戳文件）。
 * - 每 2 秒自动刷新（可在日志页标题栏「日志设置」底部弹窗里关闭）；
 * - 标题栏复制/清空按钮作用于当前标签页；
 * - 崩溃标签页按文件时间倒序合并显示。
 */
class TasksFragment : Fragment() {

    private lateinit var logText: android.widget.TextView
    private lateinit var statusText: android.widget.TextView
    private lateinit var emptyState: View
    private lateinit var scroll: NestedScrollView
    private lateinit var logTabs: TabLayout
    private lateinit var logSearchBar: View
    private lateinit var logSearchInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var logFilterBar: View
    private lateinit var logFilterStatus: android.widget.TextView
    private lateinit var logFilterClear: android.widget.TextView
    private lateinit var btnLogScroll: com.google.android.material.floatingactionbutton.FloatingActionButton
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var loading = false
    @Volatile
    private var autoRefresh = true
    private var refreshIntervalMs = AppSettings.DEFAULT_LOG_REFRESH_INTERVAL
    /** 是否跟随最新日志（在底部时为 true；用户上滑阅读时为 false，不再自动拽到底）。 */
    private var followBottom = true
    /** 自动滚动模式下用户点了悬浮按钮暂停跟随（阅读历史日志），点恢复继续贴底。 */
    private var autoScrollPaused = false
    /** 正在程序化滚动（避免滚动监听把自己贴底误判成用户上滑）。 */
    private var followingScroll = false
    /** 当前标签页：0=运行日志 1=崩溃日志 */
    private var currentTab = 0
    /** 搜索关键字（空=不过滤）。日志行包含该关键字才显示。 */
    private var searchQuery = ""
    /** 级别筛选：0=全部 1=Debug 2=Info 3=Warn 4=Error。 */
    private var levelFilter = 0
    private val levelNames = arrayOf("全部", "Debug", "Info", "Warn", "Error")
    /** 标签筛选：空=全部，否则按日志模块 tag 过滤。 */
    private var tagFilter = ""
    private var tagOptions: List<String> = listOf("全部")
    /** 时间筛选：0=全部 1=最近1小时 2=最近24小时 3=今天 4=自定义时间段。 */
    private var timeFilter = 0
    private val timeNames = arrayOf("全部", "最近1小时", "最近24小时", "今天", "自定义…")
    /** 自定义时间段的起止（毫秒时间戳，0=未设置）。仅 timeFilter==4 时生效。 */
    private var rangeStart = 0L
    private var rangeEnd = 0L
    /** 时间下拉当前显示文字（自定义时显示具体区间）。 */
    private var timeLabel = "全部"
    private val lineTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val tsPrefix = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    private val refreshTask = object : Runnable {
        override fun run() {
            // 筛选底部弹窗打开时不刷新：刷新会重设日志文本，弹窗背后的列表会跳，且状态不同步。
            if (!filterSheetShowing) loadLog()
            if (autoRefresh) handler.postDelayed(this, refreshIntervalMs.toLong())
        }
    }

    /** 筛选底部弹窗当前是否展示（打开期间暂停自动刷新）。 */
    private var filterSheetShowing = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_tasks, container, false)
        logText = v.findViewById(R.id.logText)
        statusText = v.findViewById(R.id.statusText)
        emptyState = v.findViewById(R.id.emptyState)
        scroll = v.findViewById(R.id.logScroll)
        logTabs = v.findViewById(R.id.logTabs)

        autoRefresh = AppSettings.logAutoRefresh(requireContext())
        refreshIntervalMs = AppSettings.logRefreshIntervalMs(requireContext())

        logSearchBar = v.findViewById(R.id.logSearchBar)
        logSearchInput = v.findViewById(R.id.logSearchInput)
        logFilterBar = v.findViewById(R.id.logFilterBar)
        logFilterStatus = v.findViewById(R.id.logFilterStatus)
        logFilterClear = v.findViewById(R.id.logFilterClear)

        // 搜索框输入 → 更新关键字并重新加载（过滤）
        logSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                loadLog()
            }
        })
        // 筛选状态栏：点击打开筛选底部弹窗（与标题栏「筛选」按钮同入口）
        logFilterBar.setOnClickListener { showFilterSheet() }
        // 清除按钮：一键重置所有筛选
        logFilterClear.setOnClickListener { resetFilters() }
        updateFilterBar()

        // 悬浮「回到顶部 / 回到底部」按钮；自动滚动模式下切到顶部会暂停跟随（读历史），
        // 切回底部恢复跟随贴底。
        btnLogScroll = v.findViewById(R.id.btnLogScroll)
        btnLogScroll.setOnClickListener {
            if (followBottom) {
                // 在底部 → 回到顶部（自动滚动模式下同时暂停跟随，避免又被拽回底部）
                followBottom = false
                if (autoRefresh) autoScrollPaused = true
                scroll.smoothScrollTo(0, 0)
            } else {
                // 在顶部 → 回到底部（自动滚动模式下恢复跟随贴底）
                followBottom = true
                if (autoRefresh) autoScrollPaused = false
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            updateScrollFab()
        }
        scroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                if (followingScroll) return@OnScrollChangeListener
                followBottom = isAtBottom()
                updateScrollFab()
            }
        )

        logTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                loadLog()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        logTabs.addTab(logTabs.newTab().setText("运行日志"), true)
        logTabs.addTab(logTabs.newTab().setText("崩溃日志"))

        // 标题栏复制 / 清空由 MainActivity 转发，这里直接操作当前标签页
        // （原内联按钮已移除，逻辑移到 MainActivity 菜单）
        return v
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refreshTask)
        super.onDestroyView()
    }

    /** 标题栏复制按钮：复制当前标签页文本到剪贴板。 */
    fun copyLog() {
        val text = logText.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), "暂无日志", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("日志", text))
        Toast.makeText(requireContext(), "日志已复制", Toast.LENGTH_SHORT).show()
    }

    /** 标题栏清空按钮：清空当前标签页的日志。 */
    fun clearLog() {
        if (currentTab == 1) {
            // 崩溃日志：清空整个 crash 目录
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("清空崩溃日志")
                .setMessage("将删除所有崩溃日志文件，此操作不可恢复。确定继续？")
                .setPositiveButton("清空") { _, _ ->
                    com.codetool.app.util.CrashLogger.clear(requireContext())
                    loadLog()
                    Toast.makeText(requireContext(), "崩溃日志已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            try {
                com.codetool.app.util.FileLog.clear(requireContext())
                loadLog()
                Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    /** 设置弹窗调用：动态开关自动滚动（终端式跟随最新日志）。 */
    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
        // 开关切换时清掉暂停态：开启即恢复跟随贴底
        autoScrollPaused = false
        if (enabled) {
            handler.post(refreshTask)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } else handler.removeCallbacks(refreshTask)
        updateScrollFab()
    }

    /** 设置弹窗调用：改自动刷新间隔（毫秒，1~10 秒），保存并让下一轮生效。 */
    fun setRefreshInterval(ms: Int) {
        refreshIntervalMs = ms.coerceIn(
            AppSettings.MIN_LOG_REFRESH_INTERVAL, AppSettings.MAX_LOG_REFRESH_INTERVAL
        )
        if (isAdded) AppSettings.setLogRefreshIntervalMs(requireContext(), refreshIntervalMs)
    }

    /** 标题栏放大镜：展开/收起关键词搜索栏。 */
    fun toggleSearch() {
        if (!isAdded || view == null) return
        val show = logSearchBar.visibility != View.VISIBLE
        logSearchBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) logSearchInput.requestFocus()
    }

    /**
     * 标题栏「筛选」/ 筛选状态栏入口：底部弹窗里选 时间 / 级别 / 标签。
     * 用单选按钮组（每节一组），选中即应用并刷新；弹窗内不做搜索（搜索走标题栏放大镜）。
     */
    fun showFilterSheet() {
        if (!isAdded) return
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        filterSheetShowing = true
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        sheet.setOnDismissListener { filterSheetShowing = false }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }

        // 标题 + 副标题
        container.addView(android.widget.TextView(ctx).apply {
            text = "筛选日志"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        container.addView(android.widget.TextView(ctx).apply {
            text = "按时间 / 级别 / 模块标签过滤运行日志（选中即生效）"
            textSize = 13f
            setPadding(0, 0, 0, dp(16))
        })

        // —— 时间 ——
        container.addView(sectionLabel(ctx, "时间", dp))
        val timeGroup = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val timeValues = arrayOf("全部", "最近15分钟", "最近1小时", "最近24小时", "今天", "自定义…")
        // 现有 timeFilter 编码：0全部 1最近1小时 2最近24小时 3今天 4自定义。这里加入「最近15分钟」，
        // 用内部索引映射到 timeFilter 编码，避免打乱旧编码。
        val timeMap = intArrayOf(0, 15, 1, 2, 3, 4)  // UI 行 -> timeFilter 值（15 表示新的「最近15分钟」）
        val currentTimeRow = timeMap.indexOf(timeFilter).let { if (it < 0) 0 else it }
        timeValues.forEachIndexed { idx, name ->
            timeGroup.addView(makeRadioRow(ctx, name, idx == currentTimeRow) {
                when (val tf = timeMap[idx]) {
                    4 -> {
                        // 自定义：先关弹窗再弹时间段选择，确认后写区间
                        sheet.dismiss()
                        showTimeRangeDialog()
                    }
                    15 -> {  // 最近15分钟（新增）
                        timeFilter = 15
                        timeLabel = "最近15分钟"
                        loadLog()
                        updateFilterBar()
                    }
                    else -> {
                        timeFilter = tf
                        rangeStart = 0L
                        rangeEnd = 0L
                        timeLabel = timeValues[idx]
                        loadLog()
                        updateFilterBar()
                    }
                }
            })
        }
        container.addView(timeGroup)

        // —— 级别 ——
        container.addView(sectionLabel(ctx, "级别", dp))
        val levelGroup = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.VERTICAL }
        levelNames.forEachIndexed { idx, name ->
            levelGroup.addView(makeRadioRow(ctx, name, idx == levelFilter) {
                levelFilter = idx
                loadLog()
                updateFilterBar()
            })
        }
        container.addView(levelGroup)

        // —— 标签 ——
        container.addView(sectionLabel(ctx, "模块标签", dp))
        val tagGroup = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val currentTag = tagFilter.ifEmpty { "全部" }
        tagOptions.forEach { tag ->
            tagGroup.addView(makeRadioRow(ctx, tag, tag == currentTag) {
                tagFilter = if (tag == "全部") "" else tag
                loadLog()
                updateFilterBar()
            })
        }
        container.addView(tagGroup)

        // 清除全部筛选按钮
        val clearBtn = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "清除全部筛选"
            setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, 0))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, dp(12), 0, 0)
            setOnClickListener {
                sheet.dismiss()
                resetFilters()
            }
        }
        container.addView(clearBtn)

        // 内容可能超一屏（时间+级别+标签+按钮），包一层 NestedScrollView 让弹窗能上下滑动。
        // 必须用 NestedScrollView（参与 nested scrolling）：BottomSheetDialog 的拖拽手势会把
        // 内部滚动让给 NestedScrollView，普通 ScrollView 会被 BottomSheet 抢走滑动、变成收起弹窗。
        val scrollWrap = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
            addView(container)
        }
        sheet.setContentView(scrollWrap)
        sheet.show()
    }

    /** 弹窗里的小节标题（灰色小字）。 */
    private fun sectionLabel(ctx: Context, text: String, dp: (Int) -> Int): android.widget.TextView =
        android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
            setPadding(0, dp(4), 0, dp(6))
        }

    /** 弹窗里的单选行：RadioButton + 文字，整行可点。 */
    private fun makeRadioRow(ctx: Context, text: String, checked: Boolean, onPick: () -> Unit): android.widget.RadioButton {
        val pad = (12 * ctx.resources.displayMetrics.density).toInt()
        val padH = (8 * ctx.resources.displayMetrics.density).toInt()
        return android.widget.RadioButton(ctx).apply {
            this.text = text
            this.isChecked = checked
            textSize = 15f
            setPadding(padH, pad, padH, pad)
            setOnClickListener { onPick() }
        }
    }

    private fun dp8(ctx: Context): Int = (8 * ctx.resources.displayMetrics.density).toInt()

    /** 重置所有筛选条件（时间/级别/标签），保留搜索关键字。 */
    fun resetFilters() {
        timeFilter = 0
        rangeStart = 0L
        rangeEnd = 0L
        timeLabel = timeNames[0]
        levelFilter = 0
        tagFilter = ""
        loadLog()
        updateFilterBar()
    }

    /** 刷新筛选状态栏：有任一筛选生效则显示摘要，否则隐藏。 */
    private fun updateFilterBar() {
        if (!isAdded || view == null) return
        val parts = mutableListOf<String>()
        if (timeFilter != 0) parts.add(timeLabel)
        if (levelFilter != 0) parts.add(levelNames[levelFilter])
        if (tagFilter.isNotEmpty()) parts.add(tagFilter)
        if (parts.isEmpty()) {
            logFilterBar.visibility = View.GONE
        } else {
            logFilterBar.visibility = View.VISIBLE
            logFilterStatus.text = "筛选：${parts.joinToString(" · ")}"
        }
    }

    /** 是否有任一筛选条件生效。 */
    private fun anyFilterActive(): Boolean =
        searchQuery.isNotEmpty() || levelFilter != 0 || tagFilter.isNotEmpty() || timeFilter != 0

    /** 当前是否已在日志底部（留 8px 容差）。 */
    private fun isAtBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.bottom - 8
    }

    /** 内容是否超过一屏（不足一屏时隐藏悬浮按钮）。 */
    private fun canScroll(): Boolean {
        val child = scroll.getChildAt(0) ?: return false
        return child.height > scroll.height + 8
    }

    /** 按当前状态刷新悬浮按钮：始终是「↑ 回到顶部 / ↓ 回到底部」（自动滚动时兼暂停/恢复）。 */
    private fun updateScrollFab() {
        if (!isAdded || view == null) return
        btnLogScroll.setImageResource(
            if (followBottom) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
        )
        btnLogScroll.contentDescription = if (followBottom) "回到顶部" else "回到底部"
        btnLogScroll.visibility = if (canScroll()) View.VISIBLE else View.GONE
    }

    /**
     * 跟随模式下贴底。长文本布局要等一帧，这里「立即 + 延迟」各贴一次，
     * 期间屏蔽滚动监听，避免把程序化滚动误判成用户上滑。
     */
    private fun scrollToBottomIfFollowing() {
        // 自动滚动（终端式）：除非用户点了暂停，否则始终跟随到底（不因上滑停止）。
        if (autoRefresh && !autoScrollPaused) followBottom = true
        if (!followBottom) {
            followingScroll = false
            updateScrollFab()
            return
        }
        followingScroll = true
        scroll.fullScroll(View.FOCUS_DOWN)
        handler.postDelayed({
            if (isAdded && view != null) {
                scroll.fullScroll(View.FOCUS_DOWN)
                followBottom = true
                updateScrollFab()
            }
            followingScroll = false
        }, 120)
    }

    private fun logFile(ctx: Context): File = File(ctx.filesDir, "logs/codetool.log")
    private fun crashDir(ctx: Context): File = File(ctx.filesDir, "crash").apply { mkdirs() }

    private fun loadLog() {
        if (loading) return
        loading = true
        val ctx = requireContext().applicationContext
        Thread {
            val raw = try {
                if (currentTab == 1) crashLogs(ctx) else tail(ctx, 2000)
            } catch (_: Exception) {
                ""
            }
            // 解析日志里出现过的模块标签（供筛选弹窗里的标签选项）
            val tags = collectTags(raw)
            activity?.runOnUiThread { tagOptions = listOf("全部") + tags }
            // 应用级别/标签/时间筛选 + 搜索关键字过滤
            val text = applyFilter(raw)
            activity?.runOnUiThread {
                loading = false
                if (!isAdded || view == null) return@runOnUiThread
                // 筛选弹窗打开期间跳过本轮列表更新（避免背后列表跳动、状态不同步）。
                if (filterSheetShowing) return@runOnUiThread
                updateFilterBar()
                if (text.isBlank()) {
                    logText.text = ""
                    emptyState.visibility = View.VISIBLE
                    btnLogScroll.visibility = View.GONE
                    if (currentTab == 1) {
                        statusText.text = "崩溃日志 · 暂无崩溃记录"
                        (emptyState.findViewById<android.widget.TextView>(R.id.emptyHint))?.let {
                            it.text = "暂无崩溃日志。应用发生闪退时，崩溃信息会显示在这里。"
                        }
                    } else {
                        statusText.text = if (anyFilterActive())
                            "应用日志 · 无匹配行（调整筛选条件）" else "应用日志 · 暂无日志"
                        (emptyState.findViewById<android.widget.TextView>(R.id.emptyHint))?.let {
                            it.text = if (anyFilterActive())
                                "没有符合当前筛选条件的日志行。"
                            else "暂无日志。执行命令或操作应用后，日志会显示在这里。"
                        }
                    }
                } else {
                    emptyState.visibility = View.GONE
                    // 只有内容变化才重设文本：否则每 2 秒重设会把 scrollY 重置到第一行、
                    // 页面闪一下。跟随模式下先屏蔽滚动监听，避免把重置误判成「用户上滑」。
                    val newText = highlight(text)
                    if (logText.text?.toString() != text) {
                        if (followBottom) followingScroll = true
                        logText.text = newText
                    }
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val shown = text.count { it == '\n' } + 1
                    if (currentTab == 1) {
                        val files = crashDir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()
                        statusText.text = "崩溃日志 · ${files.size} 份 · $shown 行 · 更新于 $time"
                    } else {
                        statusText.text = "应用日志 · $shown 行 · 更新于 $time"
                    }
                    scroll.post { scrollToBottomIfFollowing() }
                }
            }
        }.start()
    }

    /** 从日志内容里提取出现过的模块 tag（` I/Terminal: ` → Terminal），最多 50 个。 */
    private fun collectTags(raw: String): List<String> {
        val set = LinkedHashSet<String>()
        val re = Regex("""\s[IWED]/([^:\n]+):""")
        for (line in raw.lineSequence()) {
            val m = re.find(line) ?: continue
            set.add(m.groupValues[1].trim())
            if (set.size >= 50) break
        }
        return set.toList()
    }

    /** 自定义时间段：起止时间选择弹窗（日期 → 时刻 两段式）。 */
    private fun showTimeRangeDialog() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dlg_log_time_range, null)
        val etStart = v.findViewById<android.widget.EditText>(R.id.etRangeStart)
        val etEnd = v.findViewById<android.widget.EditText>(R.id.etRangeEnd)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        var start = if (rangeStart > 0) rangeStart else now - 3600_000L
        var end = if (rangeEnd > 0) rangeEnd else now
        fun render() {
            etStart.setText(fmt.format(Date(start)))
            etEnd.setText(fmt.format(Date(end)))
        }
        render()
        etStart.setOnClickListener { pickDateTime(start) { start = it; render() } }
        etEnd.setOnClickListener { pickDateTime(end) { end = it; render() } }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义时间段")
            .setView(v)
            .setPositiveButton("确定") { _, _ ->
                if (end < start) {
                    Toast.makeText(ctx, "结束时间不能早于开始时间", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                rangeStart = start
                rangeEnd = end
                timeFilter = 4
                timeLabel = rangeLabel(start, end)
                loadLog()
            }
            .setNeutralButton("清除") { _, _ ->
                rangeStart = 0L
                rangeEnd = 0L
                timeFilter = 0
                timeLabel = timeNames[0]
                loadLog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 先选日期再选时刻。MaterialDatePicker 的 selection 是 UTC 零点，需换回当地日期。 */
    private fun pickDateTime(base: Long, onPicked: (Long) -> Unit) {
        val seed = java.util.Calendar.getInstance().apply { if (base > 0) timeInMillis = base }
        val dp = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
            .setSelection(seed.timeInMillis)
            .setTitleText("选择日期")
            .build()
        dp.addOnPositiveButtonClickListener { sel ->
            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = sel
            }
            val tp = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                .setHour(seed.get(java.util.Calendar.HOUR_OF_DAY))
                .setMinute(seed.get(java.util.Calendar.MINUTE))
                .setTitleText("选择时刻")
                .build()
            tp.addOnPositiveButtonClickListener {
                val out = java.util.Calendar.getInstance().apply {
                    clear()
                    set(
                        utc.get(java.util.Calendar.YEAR),
                        utc.get(java.util.Calendar.MONTH),
                        utc.get(java.util.Calendar.DAY_OF_MONTH),
                        tp.hour,
                        tp.minute,
                        0
                    )
                }
                onPicked(out.timeInMillis)
            }
            tp.show(parentFragmentManager, "logTimePick")
        }
        dp.show(parentFragmentManager, "logDatePick")
    }

    /** 下拉里显示的紧凑区间：同一天只在开头写一次日期。 */
    private fun rangeLabel(start: Long, end: Long): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val a = fmt.format(Date(start))
        val b = fmt.format(Date(end))
        return if (a.substring(0, 5) == b.substring(0, 5)) "$a~${b.substring(6)}" else "$a~$b"
    }

    /** 按级别 + 标签 + 时间 + 关键词过滤日志行。日志格式：`时间去 级别/标签: 消息`。 */
    private fun applyFilter(raw: String): String {
        if (!anyFilterActive()) return raw
        val q = searchQuery.lowercase()
        val now = System.currentTimeMillis()
        val from = when (timeFilter) {
            15 -> now - 15 * 60_000L
            1 -> now - 3600_000L
            2 -> now - 24 * 3600_000L
            3 -> startOfToday()
            4 -> if (rangeStart > 0) rangeStart else Long.MIN_VALUE
            else -> Long.MIN_VALUE
        }
        val to = if (timeFilter == 4 && rangeEnd > 0) rangeEnd else Long.MAX_VALUE
        var lastInTime = true
        var lastLevel = 0  // 上一条日志的级别（1=D 2=I 3=W 4=E），供续行（堆栈）继承
        // 级别字母 -> 内部级别编码
        fun levelOf(ch: Char): Int = when (ch) {
            'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; else -> 0
        }
        return raw.lines().filter { line ->
            // 时间 + 级别：行首有时间戳就解析（`时间去 级别/标签: 消息`），续行继承上一条
            val ts = parseLineTime(line)
            lastInTime = if (ts != null) ts in from..to else lastInTime
            val timeOk = timeFilter == 0 || lastInTime
            // 级别：从时间戳后的 ` X/` 解析级别字母（比 contains 更准），续行继承上一条级别
            val lvl = levelPrefix(line)?.let { levelOf(it) }
            if (lvl != null) lastLevel = lvl
            val levelOk = levelFilter == 0 || lastLevel == levelFilter
            val tagOk = tagFilter.isEmpty() || line.contains("/$tagFilter:")
            val searchOk = q.isEmpty() || line.lowercase().contains(q)
            timeOk && levelOk && tagOk && searchOk
        }.joinToString("\n")
    }

    /**
     * 从日志行解析级别字母：行首时间戳（23 字符）后紧跟 ` X/`，返回该字母（D/I/W/E/V）。
     * 非日志头行（续行 / 堆栈）返回 null，由调用方继承上一条级别。
     */
    private fun levelPrefix(line: String): Char? {
        if (line.length < 26) return null
        if (line[23] != ' ') return null
        val ch = line[24]
        return if (ch in 'A'..'Z' && line[25] == '/') ch else null
    }

    private fun parseLineTime(line: String): Long? {
        if (line.length < 23) return null
        return try {
            lineTimeFmt.parse(line.substring(0, 23))?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 高亮：搜索关键字（强调色背景）+ Error 行（红）/ Warn 行（橙）。
     * 用 Spannable 给整行或关键字上色，纯 UI 不改文本。
     */
    private fun highlight(text: String): android.text.SpannableString {
        val sp = android.text.SpannableString(text)
        val accent = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorPrimary, 0)
        val tsColor = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorTertiary, 0)
            .let { if (it == 0) 0xFF7986CB.toInt() else it }
        // 低危：颜色跟随主题（AGENTS.md 禁硬编码色值）；警告色无 Material 属性，用 tertiary 兜底。
        val red = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorError, 0xFFE53935.toInt())
        val orange = tsColor
        // 整行级别着色
        var idx = 0
        for (line in text.lines()) {
            val start = idx
            val end = idx + line.length
            idx = end + 1
            if (line.contains(" E/") || line.contains("/ERROR")) {
                sp.setSpan(android.text.style.ForegroundColorSpan(red), start, end.coerceAtMost(sp.length), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (line.contains(" W/") || line.contains("/WARN")) {
                sp.setSpan(android.text.style.ForegroundColorSpan(orange), start, end.coerceAtMost(sp.length), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // 时间戳高亮：行首 `yyyy-MM-dd HH:mm:ss.SSS`（23 字符），叠在行色之上
            if (tsPrefix.containsMatchIn(line)) {
                val tsEnd = (start + 23).coerceAtMost(sp.length)
                sp.setSpan(android.text.style.ForegroundColorSpan(tsColor), start, tsEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // 搜索关键字高亮（在级别着色之上再叠一层背景）
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase()
            var from = 0
            val lower = text.lowercase()
            while (true) {
                val i = lower.indexOf(q, from)
                if (i < 0) break
                sp.setSpan(android.text.style.BackgroundColorSpan(accent), i, i + q.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = i + q.length
            }
        }
        return sp
    }

    /** 从文件尾部读最近 [maxLines] 行（避免整份日志读进内存）。 */
    private fun tail(ctx: Context, maxLines: Int): String {
        val f = logFile(ctx)
        if (!f.exists() || f.length() == 0L) return ""
        val maxBytes = 200 * 1024L
        RandomAccessFile(f, "r").use { raf ->
            val start = (raf.length() - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val all = String(bytes, Charsets.UTF_8)
            val lines = all.split('\n')
            // 从中间截断时，第一行可能不完整
            val body = if (start > 0) lines.drop(1) else lines
            return body.takeLast(maxLines).joinToString("\n").trimEnd()
        }
    }

    /** 合并所有崩溃日志（按文件名倒序），每份末尾加分隔头，限制总量。 */
    private fun crashLogs(ctx: Context): String {
        val dir = crashDir(ctx)
        val files = dir.listFiles()?.filter { it.isFile } ?: return ""
        val sorted = files.sortedByDescending { it.name }
        // 每份最多取 400 行，总合并不超过 2000 行
        val perFile = 400
        val maxTotal = 2000
        val sb = StringBuilder()
        var total = 0
        for (f in sorted) {
            if (total >= maxTotal) break
            // OOM 修复：限制每文件读取量（仅尾部 200KB），避免超大崩溃日志一次性读入内存。
            val maxBytes = 200 * 1024L
            val text = if (f.length() > maxBytes) {
                RandomAccessFile(f, "r").use { raf ->
                    val start = raf.length() - maxBytes
                    raf.seek(start)
                    val bytes = ByteArray(maxBytes.toInt())
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
            } else {
                f.readText()
            }
            val lines = text.lines()
            val body = lines.takeLast(perFile)
            sb.append("--- ${f.name} ---\n")
            sb.append(body.joinToString("\n"))
            sb.append("\n\n")
            total += body.size + 2
        }
        return sb.toString().trimEnd()
    }
}
