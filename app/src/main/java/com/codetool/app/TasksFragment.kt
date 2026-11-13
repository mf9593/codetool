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
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var loading = false
    @Volatile
    private var autoRefresh = true
    /** 当前标签页：0=正常日志 1=崩溃日志 */
    private var currentTab = 0

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
            val text = try {
                if (currentTab == 1) crashLogs(ctx) else tail(ctx, 400)
            } catch (_: Exception) {
                ""
            }
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
                        statusText.text = "应用日志 · 暂无日志"
                        (emptyState.findViewById<android.widget.TextView>(R.id.emptyHint))?.let {
                            it.text = "暂无日志。执行命令或操作应用后，日志会显示在这里。"
                        }
                    }
                } else {
                    emptyState.visibility = View.GONE
                    logText.text = text
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    if (currentTab == 1) {
                        val files = crashDir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()
                        statusText.text = "崩溃日志 · ${files.size} 份 · 更新于 $time"
                    } else {
                        statusText.text = "应用日志 · 最近 400 行 · 更新于 $time"
                    }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }.start()
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
