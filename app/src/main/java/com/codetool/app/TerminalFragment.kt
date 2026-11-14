package com.codetool.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.codetool.app.terminal.TerminalSessionManager
import com.codetool.app.util.AppSettings
import com.codetool.app.util.FileLog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.termux.view.TerminalView
import com.termux.terminal.TerminalSession
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 终端页：用 Termux 的 TerminalView 渲染，背后用 tawcroot 跑 Ubuntu rootfs 里的 bash。
 * - 底部快捷键行（ESC/TAB/CTRL/ALT/方向/Home...）
 * - 字体大小可调（顶部设置 → 底部弹窗），默认调大
 * - 输入法弹出时强制 resize（否则终端不刷新）
 */
class TerminalFragment : Fragment() {

    companion object {
        private const val TAG = "TerminalFragment"
        @Volatile var instance: TerminalFragment? = null
            private set
        /** 终端未就绪时排队的命令（会话就绪/激活标签后自动发送）。 */
        @Volatile var queuedCommand: String? = null

        /** 把排队命令补发给当前激活会话（切换标签、恢复前台、新建会话后调用）。 */
        fun flushQueued(context: android.content.Context?) {
            val cmd = queuedCommand ?: return
            val s = TerminalTabs.activeSession()
            if (TerminalTabs.isAlive(s)) {
                queuedCommand = null
                val bytes = cmd.toByteArray()
                s!!.write(bytes, 0, bytes.size)
                if (context != null) {
                    com.codetool.app.util.FileLog.i(context, "Script", "已补发排队命令: ${cmd.trim()}")
                }
            }
        }

        /**
         * 在终端运行脚本：复制进容器 /root/.codetool-scripts/ 后，向 bash stdin 发
         * `sh <路径> <参数…>`，脚本及其输出显示在终端里。
         * 终端未启动时排队命令并切到终端页（会话就绪后自动发送）。
         */
        fun runScript(context: android.content.Context, file: java.io.File, name: String, vararg args: String) {
            val containerPath = "/root/.codetool-scripts/${file.name}"
            val cmd = buildString {
                append("sh ").append(containerPath)
                for (a in args) if (a.isNotEmpty()) append(" '").append(a.replace("'", "'\\''")).append("'")
                append("\n")
            }
            // ★ 先立即切到终端页：构建包是 GB 级，复制可能耗时几十秒，
            //   等复制完再切页会让用户以为「点击没反应」，看不到终端。
            val main = context as? MainActivity
            main?.runOnUiThread {
                main.switchToTerminal()
            }

            // 复制脚本进容器（后台）——★ 必须复制完成后才发命令：
            // 否则 sh 命令可能先于文件到达容器，bash 报 "No such file"（终端看似没反应）
            Thread {
                try {
                    val rootfs = com.codetool.app.runtime.RootfsManager.get(context).ensureExtractedSync()
                    val scriptDir = java.io.File(rootfs, "root/.codetool-scripts")
                    scriptDir.mkdirs()
                    val dest = java.io.File(scriptDir, file.name)
                    file.copyTo(dest, overwrite = true)
                    dest.setExecutable(true)
                    com.codetool.app.util.FileLog.i(context, "Script", "已复制脚本: $containerPath")
                    // 只有「安装构建环境」需要这些压缩包：避免跑个系统信息脚本也复制 GB 级文件
                    if (file.name.contains("buildenv")) {
                        copyBuildArchives(context, file.parentFile, scriptDir)
                    }
                } catch (e: Exception) {
                    com.codetool.app.util.FileLog.e(context, "Script", "复制脚本失败: ${e.message}", e)
                    main?.runOnUiThread {
                        android.widget.Toast.makeText(
                            context, "脚本准备失败: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }
                // 复制完成：写命令到当前激活标签（或排队），并切到终端页让用户看到输出
                (context as? MainActivity)?.runOnUiThread {
                    val active = TerminalTabs.activeSession()
                    // 激活标签已退出时，退回到任意一个仍存活的标签，避免写进死会话
                    val s = if (TerminalTabs.isAlive(active)) active
                            else TerminalTabs.tabs.firstOrNull { it.session.isRunning }?.session
                    if (TerminalTabs.isAlive(s)) {
                        val bytes = cmd.toByteArray()
                        s!!.write(bytes, 0, bytes.size)
                        com.codetool.app.util.FileLog.i(context, "Script", "已发送到终端: ${cmd.trim()}")
                        android.widget.Toast.makeText(context, "已发送到终端: $name", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        // 终端未就绪/标签已退出：排队（新建或激活会话后自动补发）
                        queuedCommand = cmd
                        com.codetool.app.util.FileLog.i(context, "Script", "终端未就绪，排队: ${cmd.trim()}")
                        android.widget.Toast.makeText(context, "终端未就绪，已排队: $name", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    // ★ 始终切到终端页：否则用户在首页运行后看不到输出，以为「终端没更新」
                    (context as? MainActivity)?.switchToTerminal()
                }
            }.start()
        }

        /**
         * 把脚本目录里的 JDK/SDK/NDK 压缩包复制进容器（「安装构建环境」需要）。
         * 只拷贝 jdk/sdk/ndk 相关压缩包；同名且同大小的跳过（增量）。
         */
        private fun copyBuildArchives(
            context: android.content.Context, srcDir: java.io.File?, destDir: java.io.File
        ) {
            if (srcDir == null || !srcDir.isDirectory) return
            // 与 03-buildenv.sh 的归类保持一致，文件名可能不带类别关键字：
            //   r27d_...            → NDK
            //   buildtools_...      → build-tools
            //   platformtools_...   → platform-tools
            //   4.3.0-aarch64...    → CMake（官方命名是纯版本号）
            val archive = Regex("(?i).*\\.(tar\\.gz|tgz|tar\\.xz|tar\\.bz2|zip|tar)$")
            val known = Regex(
                "(?i).*(jdk|jre|java|temurin|zulu|ndk|r2[0-9][a-z]|sdk|commandlinetools|" +
                    "platform-?tools|build-?tools|cmake|flutsdk|flutter).*"
            )
            val cmakeVersion = Regex("(?i)^[0-9]+\\.[0-9]+\\.[0-9]+-(aarch64|arm64).*")
            val files = srcDir.listFiles { f ->
                f.isFile && archive.matches(f.name) &&
                    (known.containsMatchIn(f.name) || cmakeVersion.containsMatchIn(f.name))
            } ?: return
            com.codetool.app.util.FileLog.i(
                context, "Script", "待复制构建包: ${files.joinToString { it.name }}"
            )
            for (f in files) {
                try {
                    val d = java.io.File(destDir, f.name)
                    if (d.exists() && d.length() == f.length()) continue // 已复制过
                    com.codetool.app.util.FileLog.i(
                        context, "Script", "复制构建包进容器: ${f.name} (${f.length() / 1024 / 1024}MB)"
                    )
                    f.copyTo(d, overwrite = true)
                } catch (e: Exception) {
                    com.codetool.app.util.FileLog.e(context, "Script", "复制构建包失败 ${f.name}: ${e.message}", e)
                }
            }
        }

        /**
         * 后台执行一条命令，不切页面、也不依赖终端页是否打开过。
         *
         * 桌面点图标安装应用时会用到：合成器页在前台，终端页可能在后台甚至
         * 从未创建过会话。这里如果没有活的会话就现场建一个（关掉视图不影响
         * 进程），保证安装脚本一定能跑起来。
         *
         * @param onStarted 命令写入成功后回调（主线程）。
         */
        fun runInBackground(context: android.content.Context, cmd: String, onStarted: (() -> Unit)? = null) {
            val line = if (cmd.endsWith("\n")) cmd else "$cmd\n"
            val appCtx = context.applicationContext
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.post {
                val live = TerminalTabs.activeSession()
                if (TerminalTabs.isAlive(live)) {
                    val bytes = line.toByteArray()
                    live!!.write(bytes, 0, bytes.size)
                    com.codetool.app.util.FileLog.i(context, "Terminal", "后台执行: ${line.lineSequence().first()}")
                    onStarted?.invoke()
                    return@post
                }
                Thread {
                    try {
                        val mgr = com.codetool.app.terminal.TerminalSessionManager(appCtx)
                        val spec = mgr.prepare()
                        main.post {
                            try {
                                val s = mgr.createSession(spec)
                                TerminalTabs.tabs.add(
                                    TerminalTabs.Tab(mgr, s, TerminalTabs.nextTitle())
                                )
                                if (TerminalTabs.active !in TerminalTabs.tabs.indices) {
                                    TerminalTabs.active = TerminalTabs.tabs.size - 1
                                }
                                val bytes = line.toByteArray()
                                s.write(bytes, 0, bytes.size)
                                com.codetool.app.util.FileLog.i(context, "Terminal", "后台新会话执行: ${line.lineSequence().first()}")
                                onStarted?.invoke()
                            } catch (e: Exception) {
                                mgr.abandon()
                                com.codetool.app.util.FileLog.e(context, "Terminal", "后台建会话失败: ${e.message}", e)
                            }
                        }
                    } catch (e: Exception) {
                        com.codetool.app.util.FileLog.e(context, "Terminal", "后台准备会话失败: ${e.message}", e)
                    }
                }.start()
            }
        }

        /**
         * 往终端发送一条命令（自动补换行）。终端未就绪时排队，就绪后自动发送。
         * 用于文件管理「cd 到终端」等场景。
         */
        fun sendCommand(context: android.content.Context, cmd: String) {
            val line = if (cmd.endsWith("\n")) cmd else "$cmd\n"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val s = TerminalTabs.activeSession()
                if (TerminalTabs.isAlive(s)) {
                    val bytes = line.toByteArray()
                    s!!.write(bytes, 0, bytes.size)
                    com.codetool.app.util.FileLog.i(context, "Terminal", "已发送: ${line.trim()}")
                } else {
                    queuedCommand = line
                    com.codetool.app.util.FileLog.i(context, "Terminal", "终端未就绪，排队: ${line.trim()}")
                }
                (context as? MainActivity)?.switchToTerminal()
            }
        }

        /**
         * 运行脚本前先选目标终端标签：底部弹窗列出已打开的标签。
         * 没有标签（或只有一个）时直接走默认逻辑（自动创建/当前标签）。
         */
        fun pickTabAndRun(context: android.content.Context, file: java.io.File, name: String, vararg args: String) {
            // 只列出仍存活的标签；已退出的标签无法接收命令
            val tabs = TerminalTabs.tabs.mapIndexed { i, t -> i to t }
                .filter { it.second.session.isRunning }
            if (tabs.size <= 1) {
                runScript(context, file, name, *args)
                return
            }
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
            val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
                context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF44474A.toInt())

            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(12))
            }
            box.addView(TextView(context).apply {
                text = "选择运行终端"
                textSize = 17f
                setTextColor(onSurface)
                setPadding(dp(24), dp(14), dp(24), dp(2))
            })
            box.addView(TextView(context).apply {
                text = "$name · 点击一个终端标签运行"
                textSize = 12f
                setTextColor(onSurfaceVariant)
                setPadding(dp(24), 0, dp(24), dp(8))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })

            val sheet = BottomSheetDialog(context)
            // selectableItemBackground 是 attr 不是 drawable 资源 id，必须先解析，
            // 否则 View.setBackgroundResource 会抛 Resources$NotFoundException 闪退。
            val rowBg = android.util.TypedValue().also {
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }
            tabs.forEach { (index, tab) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    isClickable = true
                    setBackgroundResource(rowBg.resourceId)
                }
                row.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_terminal)
                    imageTintList = ColorStateList.valueOf(onSurface)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, 0, 0)
                }
                col.addView(TextView(context).apply {
                    text = tab.title
                    textSize = 15f
                    setTextColor(onSurface)
                })
                col.addView(TextView(context).apply {
                    text = when {
                        index == TerminalTabs.active && tab.session.isRunning -> "当前标签 · 运行中"
                        tab.session.isRunning -> "运行中"
                        else -> "已退出（将自动重建）"
                    }
                    textSize = 12f
                    setTextColor(onSurfaceVariant)
                })
                row.addView(col)
                row.setOnClickListener {
                    TerminalTabs.active = index
                    sheet.dismiss()
                    runScript(context, file, name, *args)
                }
                box.addView(row)
            }
            sheet.setContentView(box)
            sheet.show()
        }
    }

    private var terminalContainer: ViewGroup? = null
    private var tabBar: LinearLayout? = null
    private var addingTab = false
    /** 首帧遮罩是否已揭开（只在第一次 attach 时盖一次）。 */
    private var maskRevealed = false

    /** 当前激活标签的终端视图。 */
    private fun activeView(): TerminalView? = TerminalTabs.activeTab()?.view

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_terminal, container, false)
        terminalContainer = v.findViewById(R.id.terminalContainer)
        tabBar = v.findViewById(R.id.tabBar)
        v.findViewById<View>(R.id.newTabBtn).setOnClickListener { addTab(switchTo = true) }
        v.findViewById<View>(R.id.retryBtn).setOnClickListener {
            hideError()
            val tab = TerminalTabs.activeTab()
            if (tab == null || !tab.session.isRunning) addTab(switchTo = true) else attachActiveView()
        }
        setupShortcutRow(v)
        // ★ App 启动即自动拉起终端会话（不依赖用户先进入终端页），
        //   之后切到终端页直接挂接，脚本也能立即运行。
        if (TerminalTabs.tabs.isEmpty()) addTab(switchTo = true)
        return v
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // 清理已退出的标签（bash exit/崩溃），避免脚本写入死会话
        val iter = TerminalTabs.tabs.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            if (!t.session.isRunning) {
                t.view = null
                t.manager.detachView()
                iter.remove()
            }
        }
        if (TerminalTabs.active >= TerminalTabs.tabs.size) {
            TerminalTabs.active = (TerminalTabs.tabs.size - 1).coerceAtLeast(0)
        }
        if (TerminalTabs.tabs.isEmpty()) {
            addTab(switchTo = true)
        } else {
            TerminalTabs.activeTab()?.let { it.manager.applyThemeColors(it.session) }
            attachActiveView()
            refreshTabBar()
            // ★ 页面重新可见时补发排队命令 + 强制重绘（后台输出可能在不可见期间到达）
            flushQueued(requireContext())
            activeView()?.post { activeView()?.onScreenUpdated() }
        }
    }

    override fun onPause() {
        if (instance === this) instance = null
        super.onPause()
    }

    /** 新建标签页（后台 prepare 会话，完成后挂到视图并切换）。 */
    private fun addTab(switchTo: Boolean = true) {
        if (addingTab) return
        addingTab = true
        val appCtx = requireContext().applicationContext
        Thread {
            val mgr = TerminalSessionManager(appCtx)
            // 把快捷键条的粘性 Ctrl/Alt 状态接入 TerminalView：
            // 软键盘字母经 TerminalView.inputCodePoint → readControlKey() 读取此状态，
            // 才能把字母组合成 Ctrl+字母（否则软键盘字母直接进终端、不带修饰）。
            wireModifierProviders(mgr)
            try {
                val spec = mgr.prepare()
                activity?.runOnUiThread {
                    addingTab = false
                    if (!isAdded || view == null) {
                        mgr.abandon()
                        return@runOnUiThread
                    }
                    try {
                        val s = mgr.createSession(spec)
                        val tab = TerminalTabs.Tab(mgr, s, TerminalTabs.nextTitle())
                        TerminalTabs.tabs.add(tab)
                        if (switchTo) TerminalTabs.active = TerminalTabs.tabs.size - 1
                        attachActiveView()
                        refreshTabBar()
                        flushQueued(requireContext())
                        Log.i(TAG, "tab added: ${tab.title}")
                    } catch (e: Exception) {
                        mgr.abandon()
                        showError("创建终端失败", e)
                    }
                }
            } catch (e: Exception) {
                addingTab = false
                mgr.abandon()
                Log.e(TAG, "prepare tab failed", e)
                activity?.runOnUiThread { if (isAdded) showError("会话启动失败", e) }
            }
        }.start()
    }

    /** 切换标签页。 */
    private fun switchTab(index: Int) {
        if (index !in TerminalTabs.tabs.indices) return
        TerminalTabs.active = index
        TerminalTabs.activeTab()?.let { it.manager.applyThemeColors(it.session) }
        attachActiveView()
        refreshTabBar()
        flushQueued(requireContext())
    }

    /** 关闭标签页（长按标签触发）。 */
    private fun closeTab(index: Int) {
        val tab = TerminalTabs.tabs.getOrNull(index) ?: return
        if (TerminalTabs.tabs.size <= 1) {
            Toast.makeText(requireContext(), "至少保留一个终端", Toast.LENGTH_SHORT).show()
            return
        }
        tab.manager.stop()
        tab.view = null
        TerminalTabs.tabs.removeAt(index)
        if (TerminalTabs.active > index) TerminalTabs.active--
        if (TerminalTabs.active >= TerminalTabs.tabs.size) {
            TerminalTabs.active = (TerminalTabs.tabs.size - 1).coerceAtLeast(0)
        }
        attachActiveView()
        refreshTabBar()
        flushQueued(requireContext())
    }

    /** 把 Activity 上下文交给各标签的会话（主题色解析必须用界面实际主题）。 */
    private fun bindThemeContexts() {
        val ctx = context ?: return
        TerminalTabs.tabs.forEach {
            it.manager.themeContext = ctx
            it.manager.applyThemeColors(it.session)
        }
    }

    /** 把当前激活标签的会话挂到容器里（视图按需创建；视图销毁后重建重挂）。 */
    private fun attachActiveView() {
        bindThemeContexts()
        val container = terminalContainer ?: return
        container.removeAllViews()
        val tab = TerminalTabs.activeTab() ?: return
        // ★ Fragment 被 FragmentStateAdapter 重建时旧 tv 仍有旧父（废弃容器），
        //   直接 addView 会抛 "child already has a parent"。先解除旧父再挂。
        (tab.view?.parent as? ViewGroup)?.removeView(tab.view)
        val tv = tab.view ?: TerminalView(requireContext(), null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = true
            isFocusableInTouchMode = true
        }.also { created ->
            tab.view = created
            tab.manager.attach(created, tab.session)
            // 首帧黑帧遮罩：仅第一次揭开
            if (!maskRevealed) {
                view?.findViewById<View>(R.id.terminalMask)?.let { mask ->
                    created.postDelayed({ mask.visibility = View.GONE }, 150)
                    maskRevealed = true
                }
            }
        }
        tab.manager.onTerminalTapped = { showIme() }
        container.addView(tv)
        tv.post {
            tv.requestFocus()
            tv.onScreenUpdated()
        }
    }

    /** 刷新顶部标签（激活态高亮；右侧 × 关闭，关闭前有确认提示）。 */
    private fun refreshTabBar() {
        val bar = tabBar ?: return
        val ctx = requireContext()
        val surfaceContainer = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurfaceContainer, 0xFFE8E8E8.toInt())
        val primary = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorPrimary, 0xFF00505A.toInt())
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
        val onSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBFC8C8.toInt())
        val outlineVariant = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOutlineVariant, 0xFF44474A.toInt())
        bar.removeAllViews()
        TerminalTabs.tabs.forEachIndexed { i, tab ->
            val active = i == TerminalTabs.active
            val chip = Chip(ctx).apply {
                text = tab.title
                textSize = 13f
                isCheckable = false
                isClickable = true
                isCloseIconVisible = true
                isChipIconVisible = false
                chipMinHeight = 36.dp.toFloat()
                chipCornerRadius = 18.dp.toFloat()
                // 激活态：主题同款（surfaceContainer）+ 主色描边，不额外加浅色填充
                chipStrokeWidth = (if (active) 1.5f else 1f) * resources.displayMetrics.density
                chipStrokeColor = ColorStateList.valueOf(if (active) primary else outlineVariant)
                chipBackgroundColor = ColorStateList.valueOf(
                    if (active) surfaceContainer else android.graphics.Color.TRANSPARENT)
                setTextColor(if (active) onSurface else onSurfaceVariant)
                closeIconTint = ColorStateList.valueOf(if (active) onSurface else onSurfaceVariant)
                setPadding(12.dp, 0, 8.dp, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 36.dp).apply { marginEnd = 4.dp }
                setOnClickListener { switchTab(i) }
                setOnLongClickListener { confirmCloseTab(i); true }
                setOnCloseIconClickListener { confirmCloseTab(i) }
            }
            bar.addView(chip)
        }
    }

    /** 关闭标签前的确认提示。 */
    private fun confirmCloseTab(index: Int) {
        val tab = TerminalTabs.tabs.getOrNull(index) ?: return
        if (TerminalTabs.tabs.size <= 1) {
            Toast.makeText(requireContext(), "至少保留一个终端", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("关闭 ${tab.title}？")
            .setMessage("该终端中正在运行的命令会被终止，输出也将不再保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("关闭") { _, _ ->
                closeTab(index)
                Toast.makeText(requireContext(), "已关闭 ${tab.title}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // 快捷键修饰键（粘性）
    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false
    /** FN 键：切换生效（FN+字母 = ESC+字母；方向键映射 Home/End/PGUP/PGDN）。 */
    private var fnActive = false
    /** 修饰键激活色（主题 primaryContainer，setupShortcutRow 时解析）。 */
    private var modActiveColor: Int = 0xFF00505A.toInt()
    /** 修饰键未激活色（主题 surfaceContainer）。 */
    private var modInactiveColor: Int = 0xFF1A2426.toInt()
    /** CTRL/ALT/SHIFT 按钮引用（ViewPager2 分页后用于复位着色）。 */
    private val modButtons = mutableListOf<MaterialCardView>()

    /** 由 MainActivity 在 IME 变化时调用：强制终端 resize 刷新（不等 IME 隐藏才更新）。 */
    fun resizeTerminal() {
        val tv = activeView() ?: return
        tv.post {
            tv.updateSize()
            tv.requestLayout()
            tv.postInvalidate()
        }
    }

    /** 由 MainActivity 在设置弹窗改字号后调用（应用到所有标签视图）。 */
    fun updateFontSize(size: Int) {
        TerminalTabs.tabs.forEach { tab ->
            tab.view?.post {
                tab.view?.setTextSize(size)
                tab.view?.updateSize()
                tab.view?.onScreenUpdated()
            }
        }
    }

    /**
     * 在终端会话内运行脚本：复制进容器 /root/.codetool-scripts/ 后，向 bash stdin 发
     * `sh <路径> <参数…>`，脚本及其输出就显示在终端里（而非后台任务页）。
     * 终端未启动时回退到 TaskManager 后台任务。
     */
    fun runScriptInTerminal(file: java.io.File, name: String, vararg args: String) {
        runScript(requireContext(), file, name, *args)
    }

    /** 设置终端 emulator 前景/背景色跟随主题（深浅色切换）。 */
    private fun applyTerminalTheme(s: TerminalSession) {
        TerminalTabs.tabs.firstOrNull { it.session === s }?.manager?.applyThemeColors(s)
    }

    /** 顶部按钮：隐藏/显示底部快捷键行。 */
    fun toggleShortcutRow() {
        val scroll = view?.findViewById<View>(R.id.shortcutScroll) ?: return
        scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ==================== 快捷键 ====================

    private fun setupShortcutRow(v: View) {
        val pager = v.findViewById<ViewPager2>(R.id.shortcutScroll) ?: return
        val ctx = requireContext()
        // 长方形按键：宽大于高，压缩上下边距（40x34，比正方形更扁更紧凑）
        val btnW = 40.dp
        val btnH = 34.dp
        val gap = 3.dp
        // 主题色：终端背景跟随深浅色切换
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOnSurface, 0xFFDEE4E2.toInt())
        val surfaceContainer = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurfaceContainer, 0xFF1A2426.toInt())
        val primaryContainer = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF00505A.toInt())
        modActiveColor = primaryContainer
        modInactiveColor = surfaceContainer
        modButtons.clear()

        // 统一按键字体：MaterialButton 的 textAppearance 会在布局阶段覆盖 typeface，
        // 导致部分按键显示成 Roboto Medium、部分显示普通字重，看起来粗细割裂。
        // 这里改用 MaterialCardView + 自绘 TextView/ImageView，字体只设置一次且不会被覆盖。
        val keyTypeface = Typeface.MONOSPACE
        val outline = com.google.android.material.color.MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOutline, 0xFF74777F.toInt())
        fun makeBtn(tag: String, label: String, textSize: Float = 11f, iconRes: Int = 0): MaterialCardView {
            val card = MaterialCardView(ctx).apply {
                radius = 8.dp.toFloat()
                strokeWidth = 1.dp
                strokeColor = outline
                setCardBackgroundColor(surfaceContainer)
                cardElevation = 0f
                isClickable = true
                isFocusable = true
            }
            if (iconRes != 0) {
                val img = ImageView(ctx).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(onSurface)
                    // 缩小一档再画：图标原始 24dp 全尺寸画出来比 11sp 文字粗一头，
                    // 缩到 ~16dp 且线宽 1.2 后视觉重量才和文字笔画匹配。
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(9.dp, 9.dp, 9.dp, 9.dp)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                card.addView(img)
            } else {
                val tv = TextView(ctx).apply {
                    text = label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize)
                    setTextColor(onSurface)
                    typeface = keyTypeface
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    letterSpacing = 0f
                    paint.isSubpixelText = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                card.addView(tv)
            }
            card.tag = tag
            card.setOnClickListener { onShortcut(tag, card) }
            if (tag == "CTRL" || tag == "ALT" || tag == "SHIFT") modButtons.add(card)
            return card
        }
        fun makeRow(keys: List<Triple<String, String, Int>>): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((tag, label, iconRes) in keys) {
                val lp = LinearLayout.LayoutParams(btnW, btnH)
                lp.setMargins(gap, 0, gap, 0)
                // 文字键统一 11sp（方向/回车走图标，不再放大字符）
                row.addView(makeBtn(tag, label, 11f, iconRes), lp)
            }
            return row
        }
        fun rowLp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 3.dp }

        // 面板一 第一行：ESC TAB ALT DEL ↑ 回车 HOME PGUP
        val row1 = listOf(
            Triple("ESC", "ESC", 0), Triple("TAB", "TAB", 0), Triple("ALT", "ALT", 0),
            Triple("DELETE", "DEL", 0), Triple("UP", "", R.drawable.ic_key_up),
            Triple("ENTER", "", R.drawable.ic_key_enter),
            Triple("HOME", "HOME", 0), Triple("PGUP", "PGUP", 0)
        )
        // 面板一 第二行：CTRL SHIFT / ← ↓ → END PGDN
        val row2 = listOf(
            Triple("CTRL", "CTRL", 0), Triple("SHIFT", "SHIFT", 0), Triple("/", "/", 0),
            Triple("LEFT", "", R.drawable.ic_key_left), Triple("DOWN", "", R.drawable.ic_key_down),
            Triple("RIGHT", "", R.drawable.ic_key_right),
            Triple("END", "END", 0), Triple("PGDN", "PGDN", 0)
        )
        // 面板二 第一行：CLI 符号 + 常用组合键（@ $ 管道 波浪 + ^P ^X ^C ^V）
        val row3 = listOf(
            Triple("AT", "@", 0), Triple("DOLLAR", "\$", 0), Triple("PIPE", "|", 0),
            Triple("TILDE", "~", 0),
            Triple("CTRL_P", "^P", 0), Triple("CTRL_X", "^X", 0),
            Triple("CTRL_C", "^C", 0), Triple("CTRL_V", "^V", 0)
        )
        // 面板二 第二行：补充 CLI 高频组合键（行首/行尾/删行/删词/清屏/EOF/挂起）
        val row4 = listOf(
            Triple("CTRL_A", "^A", 0), Triple("CTRL_E", "^E", 0),
            Triple("CTRL_U", "^U", 0), Triple("CTRL_K", "^K", 0),
            Triple("CTRL_W", "^W", 0), Triple("CTRL_L", "^L", 0),
            Triple("CTRL_D", "^D", 0), Triple("CTRL_Z", "^Z", 0)
        )
        val pages = listOf(listOf(row1, row2), listOf(row3, row4))

        // ViewPager2 对 wrap_content 高度支持差：显式给两行长方形按键高度
        pager.layoutParams = pager.layoutParams.apply { height = btnH * 2 + 3.dp + 8.dp }
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val panel = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                return object : RecyclerView.ViewHolder(panel) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val panel = holder.itemView as LinearLayout
                panel.removeAllViews()
                for (keys in pages[position]) panel.addView(makeRow(keys), rowLp())
            }
        }
    }

    private fun onShortcut(tag: String, btn: MaterialCardView) {
        val s = TerminalTabs.activeSession() ?: return
        val color = { active: Boolean -> if (active) modActiveColor else modInactiveColor }
        when (tag) {
            "CTRL" -> { ctrlActive = !ctrlActive; btn.setCardBackgroundColor(color(ctrlActive)) }
            "ALT" -> { altActive = !altActive; btn.setCardBackgroundColor(color(altActive)) }
            "SHIFT" -> { shiftActive = !shiftActive; btn.setCardBackgroundColor(color(shiftActive)) }
            "FN" -> { fnActive = !fnActive; btn.setCardBackgroundColor(color(fnActive)) }
            else -> {
                val bytes = keySequence(tag, ctrlActive, altActive, shiftActive, fnActive)
                if (bytes.isNotEmpty()) s.write(bytes, 0, bytes.size)
                // 修饰键消费一次后自动复位（shift/alt/ctrl 单次生效；FN 保持切换态直到再次点击）
                if (ctrlActive || altActive || shiftActive) resetModifiers()
            }
        }
    }

    /** 修饰键复位（单次生效后恢复未激活态）。 */
    private fun resetModifiers() {
        ctrlActive = false
        altActive = false
        shiftActive = false
        for (b in modButtons) {
            b.setCardBackgroundColor(modInactiveColor)
        }
    }

    /**
     * 复位单个粘性修饰键（软键盘字母带 Ctrl/Alt 发出后消费一次）。
     * 只复位并重着色对应按钮，不影响其它修饰键。
     */
    private fun resetSingleModifier(ctrl: Boolean, alt: Boolean) {
        if (ctrl) {
            ctrlActive = false
            modButtons.firstOrNull { it.tag == "CTRL" }?.setCardBackgroundColor(modInactiveColor)
        }
        if (alt) {
            altActive = false
            modButtons.firstOrNull { it.tag == "ALT" }?.setCardBackgroundColor(modInactiveColor)
        }
    }

    /**
     * 把快捷键条的粘性 Ctrl/Alt 状态接入某个 manager 的 TerminalView，
     * 并挂上「消费一次后复位」回调：软键盘字母带 Ctrl/Alt 发出后自动复位按钮。
     */
    private fun wireModifierProviders(mgr: TerminalSessionManager) {
        mgr.setModifierProviders(ctrl = { ctrlActive }, alt = { altActive })
        mgr.onModifierConsumed = { ctrl, alt ->
            // onCodePoint 可能在非 UI 线程回调（IME 路径），切回主线程改按钮着色
            activity?.runOnUiThread { resetSingleModifier(ctrl, alt) }
        }
    }

    /** 计算按键字节序列（含 ctrl/alt/shift/fn 修饰）。 */
    private fun keySequence(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, fn: Boolean): ByteArray {
        // CLI 一键组合键（^P/^C/^X…）：无论粘性修饰键状态如何，直接发控制码，不受干扰
        if (key.startsWith("CTRL_") && key.length == 6) {
            val c = key[5]
            if (c in 'A'..'Z') return byteArrayOf((c - 'A' + 1).toByte())
        }
        // ENTER / ESC / TAB 等控制键不受 shift 影响（直接发原序列）
        val base = when (key) {
            "ESC" -> "\u001b"
            "TAB" -> "\t"
            "ENTER" -> "\r"
            "BS" -> "\u0008"
            "F1" -> "\u001bOP"
            "F2" -> "\u001bOQ"
            "F3" -> "\u001bOR"
            "F4" -> "\u001bOS"
            "F5" -> "\u001b[15~"
            "F6" -> "\u001b[17~"
            "F7" -> "\u001b[18~"
            "F8" -> "\u001b[19~"
            "F9" -> "\u001b[20~"
            "F10" -> "\u001b[21~"
            "F11" -> "\u001b[23~"
            "F12" -> "\u001b[24~"
            "/" -> if (shift) "?" else "/"
            "-" -> if (shift) "_" else "-"
            "HOME" -> "\u001b[H"
            "UP" -> "\u001b[A"
            "END" -> "\u001b[F"
            "RIGHT" -> "\u001b[C"
            "PGUP" -> "\u001b[5~"
            "LEFT" -> "\u001b[D"
            "PGDN" -> "\u001b[6~"
            "DOWN" -> "\u001b[B"
            "INSERT" -> "\u001b[2~"
            "DELETE" -> "\u001b[3~"
            "PIPE" -> "|"
            "AMP" -> "&"
            "SEMI" -> ";"
            "QUOTE" -> "'"
            "BACKTICK" -> "`"
            "DOLLAR" -> "$"
            "ASTERISK" -> "*"
            "TILDE" -> "~"
            "AT" -> "@"
            // CLI 组合键：直接发控制码（letter - 'a' + 1），一键生效，不走粘性修饰
            "CTRL_A" -> "\u0001"
            "CTRL_C" -> "\u0003"
            "CTRL_D" -> "\u0004"
            "CTRL_E" -> "\u0005"
            "CTRL_K" -> "\u000b"
            "CTRL_L" -> "\u000c"
            "CTRL_P" -> "\u0010"
            "CTRL_U" -> "\u0015"
            "CTRL_V" -> "\u0016"
            "CTRL_W" -> "\u0017"
            "CTRL_X" -> "\u0018"
            "CTRL_Z" -> "\u001a"
            else -> if (key.length == 1 && key[0] in '0'..'9') key else ""
        }
        // FN 修饰（与 termux keyboard 一致）：
        // - Fn+字母 → ESC letter（如 vim 操作）；
        // - Fn+数字 1-9 0 → F1-F10；
        // - Fn+方向 → Home/End/PGUP/PGDN（← PGUP，→ PGDN）
        if (fn && base.isNotEmpty()) {
            val fnBase = when {
                base.length == 1 && (base[0] in 'a'..'z' || base[0] in 'A'..'Z') -> "\u001b$base"
                base.length == 1 && base[0] == '1' -> "\u001bOP"
                base.length == 1 && base[0] == '2' -> "\u001bOQ"
                base.length == 1 && base[0] == '3' -> "\u001bOR"
                base.length == 1 && base[0] == '4' -> "\u001bOS"
                base.length == 1 && base[0] == '5' -> "\u001b[15~"
                base.length == 1 && base[0] == '6' -> "\u001b[17~"
                base.length == 1 && base[0] == '7' -> "\u0001b[18~"
                base.length == 1 && base[0] == '8' -> "\u001b[19~"
                base.length == 1 && base[0] == '9' -> "\u001b[20~"
                base.length == 1 && base[0] == '0' -> "\u001b[21~"
                key == "UP" -> "\u001b[H"
                key == "DOWN" -> "\u001b[F"
                key == "LEFT" -> "\u001b[5~"
                key == "RIGHT" -> "\u001b[6~"
                else -> base
            }
            return fnBase.toByteArray()
        }
        if (ctrl || alt || shift) {
            // xterm 修饰位：m = 1 + shift*1 + alt*2 + ctrl*4
            val m = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            val seq = when (key) {
                "UP" -> "\u001b[1;${m}A"
                "DOWN" -> "\u001b[1;${m}B"
                "RIGHT" -> "\u001b[1;${m}C"
                "LEFT" -> "\u001b[1;${m}D"
                "HOME" -> "\u001b[1;${m}H"
                "END" -> "\u001b[1;${m}F"
                "PGUP" -> "\u001b[5;${m}~"
                "PGDN" -> "\u001b[6;${m}~"
                "INSERT" -> "\u001b[2;${m}~"
                "DELETE" -> "\u001b[3;${m}~"
                else -> base
            }
            return seq.toByteArray()
        }
        return base.toByteArray()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ==================== 错误 / IME ====================

    private fun showError(title: String, e: Exception) {
        val box = view?.findViewById<LinearLayout>(R.id.errorBox) ?: return
        val tv = box.findViewById<MaterialTextView>(R.id.errorText)
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        tv.text = "$title:\n${sw.toString().take(1500)}"
        box.visibility = View.VISIBLE
        FileLog.e(requireContext(), TAG, title, e)
        view?.postDelayed({
            view?.findViewById<LinearLayout>(R.id.errorBox)?.visibility = View.GONE
        }, 6000)
    }

    private fun hideError() {
        view?.findViewById<LinearLayout>(R.id.errorBox)?.visibility = View.GONE
    }

    private fun showIme() {
        val tv = activeView() ?: return
        tv.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroyView() {
        if (instance === this) instance = null
        // 视图销毁但会话保留（后台继续运行）；下次进入时重建视图并重新挂接。
        // ★ 只清「当前容器里」的视图：通知栏进入时 standard 模式可能新建 MainActivity，
        //   新 Fragment 的 attachActiveView 会把旧 Fragment 的 tab.view re-parent 到新容器；
        //   若这里无条件 it.view = null + detachView()，会清掉新 Fragment 刚挂好的视图引用，
        //   导致终端空白 + 键盘无效。仅当视图还挂在当前容器才清理。
        val container = terminalContainer
        TerminalTabs.tabs.forEach { tab ->
            val v = tab.view
            if (v != null && v.parent === container) {
                tab.view = null
                tab.manager.detachView()
            }
            tab.manager.themeContext = null
            tab.manager.onTerminalTapped = null
        }
        terminalContainer = null
        tabBar = null
        super.onDestroyView()
    }
}
