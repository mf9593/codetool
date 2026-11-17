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
    private lateinit var btnLogFilter: android.widget.TextView
    private lateinit var btnLogLevel: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var loading = false
    @Volatile
    private var autoRefresh = true
    /** 当前标签页：0=正常日志 1=崩溃日志 */
    private var currentTab = 0
    /** 搜索关键字（空=不过滤）。日志行包含该关键字才显示。 */
    private var searchQuery = ""
    /** 级别筛选：0=全部 1=Info 2=Warn 3=Error。 */
    private var levelFilter = 0
    private val levelNames = arrayOf("全部级别", "仅 Info", "仅 Warn", "仅 Error")

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
        btnLogFilter = v.findViewById(R.id.btnLogFilter)
        btnLogLevel = v.findViewById(R.id.btnLogLevel)

        // 「筛选」按钮：展开/收起搜索栏
        btnLogFilter.setOnClickListener {
            logSearchBar.visibility =
                if (logSearchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (logSearchBar.visibility == View.VISIBLE) logSearchInput.requestFocus()
        }
        // 搜索框输入 → 更新关键字并重新加载（过滤）
        logSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                loadLog()
            }
        })
        // 级别按钮：循环切换 全部→Info→Warn→Error
        btnLogLevel.setOnClickListener {
            levelFilter = (levelFilter + 1) % 4
            btnLogLevel.text = levelNames[levelFilter]
            loadLog()
        }

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
            // 应用级别筛选 + 搜索关键字过滤
            val text = applyFilter(raw)
            activity?.runOnUiThread {
                loading = false
                if (!isAdded || view == null) return@runOnUiThread
                if (text.isBlank()) {
                    logText.text = ""
                    emptyState.visibility = View.VISIBLE
                    if (currentTab == 1) {
                        statusText.text = "崩溃日志 · 暂无崩溃记录"
                        (emptyState.findViewById<android.widget.TextView>(R.id.emptyHint))?.let {
                            it.text = "暂无崩溃日志。应用发生闪退时，崩溃信息会显示在这里。"
                        }
                    } else {
                        statusText.text = if (searchQuery.isNotEmpty() || levelFilter != 0)
                            "应用日志 · 无匹配行（调整搜索/级别筛选）" else "应用日志 · 暂无日志"
                        (emptyState.findViewById<android.widget.TextView>(R.id.emptyHint))?.let {
                            it.text = if (searchQuery.isNotEmpty() || levelFilter != 0)
                                "没有符合当前搜索/级别筛选的日志行。"
                            else "暂无日志。执行命令或操作应用后，日志会显示在这里。"
                        }
                    }
                } else {
                    emptyState.visibility = View.GONE
                    logText.text = highlight(text)
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val shown = text.count { it == '\n' } + 1
                    if (currentTab == 1) {
                        val files = crashDir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()
                        statusText.text = "崩溃日志 · ${files.size} 份 · $shown 行 · 更新于 $time"
                    } else {
                        statusText.text = "应用日志 · $shown 行 · 更新于 $time"
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }.start()
    }

    /** 按级别 + 搜索关键字过滤日志行。 */
    private fun applyFilter(raw: String): String {
        if (levelFilter == 0 && searchQuery.isEmpty()) return raw
        val q = searchQuery.lowercase()
        return raw.lines().filter { line ->
            val levelOk = when (levelFilter) {
                1 -> line.contains(" I/") || line.contains("/INFO") || !line.matches(Regex(".*( W/| E/).*"))
                2 -> line.contains(" W/") || line.contains("/WARN")
                3 -> line.contains(" E/") || line.contains("/ERROR")
                else -> true
            }
            val searchOk = q.isEmpty() || line.lowercase().contains(q)
            levelOk && searchOk
        }.joinToString("\n")
    }

    /**
     * 高亮：搜索关键字（强调色背景）+ Error 行（红）/ Warn 行（橙）。
     * 用 Spannable 给整行或关键字上色，纯 UI 不改文本。
     */
    private fun highlight(text: String): android.text.SpannableString {
        val sp = android.text.SpannableString(text)
        val accent = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorPrimary, 0)
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
