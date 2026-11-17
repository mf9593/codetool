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
 * 日志页：正常日志（codetool.log） + 崩溃日志（files/crash 下的时间戳文件）。
 * - 每 2 秒自动刷新（可在设置底部弹窗里关闭）；
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
    private lateinit var logLevelDropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var logTagDropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var logTimeDropdown: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var btnLogScroll: com.google.android.material.floatingactionbutton.FloatingActionButton
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var loading = false
    @Volatile
    private var autoRefresh = true
    /** 是否跟随最新日志（在底部时为 true；用户上滑阅读时为 false，不再自动拽到底）。 */
    private var followBottom = true
    /** 正在程序化滚动（避免滚动监听把自己贴底误判成用户上滑）。 */
    private var followingScroll = false
    /** 当前标签页：0=正常日志 1=崩溃日志 */
    private var currentTab = 0
    /** 搜索关键字（空=不过滤）。日志行包含该关键字才显示。 */
    private var searchQuery = ""
    /** 级别筛选：0=全部 1=Debug 2=Info 3=Warn 4=Error。 */
    private var levelFilter = 0
    private val levelNames = arrayOf("全部", "Debug", "Info", "Warn", "Error")
    /** 标签筛选：空=全部，否则按日志模块 tag 过滤。 */
    private var tagFilter = ""
    private var tagOptions: List<String> = listOf("全部")
    /** 时间筛选：0=全部 1=最近1小时 2=最近24小时 3=今天。 */
    private var timeFilter = 0
    private val timeNames = arrayOf("全部", "最近1小时", "最近24小时", "今天")
    private val lineTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val tsPrefix = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")

    private val refreshTask = object : Runnable {
        override fun run() {
            loadLog()
            if (autoRefresh) handler.postDelayed(this, 2000)
        }
    }

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

        logSearchBar = v.findViewById(R.id.logSearchBar)
        logSearchInput = v.findViewById(R.id.logSearchInput)
        logLevelDropdown = v.findViewById(R.id.logLevelDropdown)
        logTagDropdown = v.findViewById(R.id.logTagDropdown)
        logTimeDropdown = v.findViewById(R.id.logTimeDropdown)

        // 搜索框输入 → 更新关键字并重新加载（过滤）
        logSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                loadLog()
            }
        })
        // 级别下拉：Material 暴露式下拉菜单，选中即筛选
        logLevelDropdown.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                levelNames
            )
        )
        logLevelDropdown.setText(levelNames[levelFilter], false)
        logLevelDropdown.setOnItemClickListener { _, _, position, _ ->
            levelFilter = position
            loadLog()
        }
        // 标签下拉：选项在 loadLog 里按日志内容动态刷新
        logTagDropdown.setText(tagOptions[0], false)
        logTagDropdown.setOnItemClickListener { _, _, position, _ ->
            tagFilter = if (position == 0) "" else tagOptions.getOrNull(position).orEmpty()
            loadLog()
        }
        // 时间下拉：全部 / 最近1小时 / 最近24小时 / 今天
        logTimeDropdown.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                timeNames
            )
        )
        logTimeDropdown.setText(timeNames[timeFilter], false)
        logTimeDropdown.setOnItemClickListener { _, _, position, _ ->
            timeFilter = position
            loadLog()
        }

        // 悬浮「回到底部 / 回到顶部」按钮
        btnLogScroll = v.findViewById(R.id.btnLogScroll)
        btnLogScroll.setOnClickListener {
            if (followBottom) {
                // 当前在底部 → 回到顶部
                followBottom = false
                scroll.smoothScrollTo(0, 0)
            } else {
                // 当前在上方 → 回到底部并恢复跟随
                followBottom = true
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
        logTabs.addTab(logTabs.newTab().setText("正常日志"), true)
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
                    crashDir(requireContext()).listFiles()?.forEach { it.delete() }
                    loadLog()
                    Toast.makeText(requireContext(), "崩溃日志已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            try {
                logFile(requireContext()).writeText("")
                loadLog()
                Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    /** 设置弹窗调用：动态开关自动刷新。 */
    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
        if (enabled) handler.post(refreshTask) else handler.removeCallbacks(refreshTask)
    }

    /** 标题栏放大镜：展开/收起关键词搜索栏。 */
    fun toggleSearch() {
        if (!isAdded || view == null) return
        val show = logSearchBar.visibility != View.VISIBLE
        logSearchBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) logSearchInput.requestFocus()
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

    /** 按当前状态刷新悬浮按钮：在底部显示「↑ 回顶部」，否则显示「↓ 回底部」。 */
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
            // 解析日志里出现过的模块标签（供标签下拉）
            val tags = collectTags(raw)
            // 应用级别/标签/时间筛选 + 搜索关键字过滤
            val text = applyFilter(raw)
            activity?.runOnUiThread {
                loading = false
                if (!isAdded || view == null) return@runOnUiThread
                refreshTagDropdown(tags)
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
                    // 跟随模式下，先屏蔽滚动监听：设置长文本会把 scrollY 重置为 0，
                    // 不能让它被当成「用户上滑」而关闭跟随。
                    if (followBottom) followingScroll = true
                    logText.text = highlight(text)
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

    /** 刷新标签下拉选项（保持当前选择；选中的标签消失则回到「全部」）。 */
    private fun refreshTagDropdown(tags: List<String>) {
        val options = listOf("全部") + tags
        if (options == tagOptions) return
        tagOptions = options
        logTagDropdown.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                options
            )
        )
        val idx = options.indexOf(tagFilter).let { if (it < 0) 0 else it }
        logTagDropdown.setText(options[idx], false)
        if (idx == 0) tagFilter = ""
    }

    /** 按级别 + 标签 + 时间 + 关键词过滤日志行。日志格式：`时间去 级别/标签: 消息`。 */
    private fun applyFilter(raw: String): String {
        if (!anyFilterActive()) return raw
        val q = searchQuery.lowercase()
        val cutoff = when (timeFilter) {
            1 -> System.currentTimeMillis() - 3600_000L
            2 -> System.currentTimeMillis() - 24 * 3600_000L
            3 -> startOfToday()
            else -> Long.MIN_VALUE
        }
        var lastInTime = true
        return raw.lines().filter { line ->
            // 时间：有时间戳就解析，续行（堆栈）继承上一行的判定
            val ts = parseLineTime(line)
            lastInTime = if (ts != null) ts >= cutoff else lastInTime
            val timeOk = timeFilter == 0 || lastInTime
            val levelOk = when (levelFilter) {
                1 -> line.contains(" D/")
                2 -> line.contains(" I/")
                3 -> line.contains(" W/")
                4 -> line.contains(" E/")
                else -> true
            }
            val tagOk = tagFilter.isEmpty() || line.contains("/$tagFilter:")
            val searchOk = q.isEmpty() || line.lowercase().contains(q)
            timeOk && levelOk && tagOk && searchOk
        }.joinToString("\n")
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
        val red = 0xFFE53935.toInt()
        val orange = 0xFFFB8C00.toInt()
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
            val text = f.readText()
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
