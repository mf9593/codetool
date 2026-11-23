package com.codetool.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.codetool.app.terminal.TerminalSessionManager
import com.codetool.app.util.AppSettings
import com.codetool.app.util.FileLog
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
                // 路径必须加单引号：脚本文件名可能含空格（如「启动 mimocode.sh」），不加会被 sh 拆词报 No such file
                append("sh '").append(containerPath).append("'")
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
         * 运行一段内联脚本文本，但不把源码回显到终端。
         *
         * 直接 sendCommand 多行脚本时，终端会把「输入」也回显出来（整段 echo/if 源码 +
         * `>` 续行），用户看不到干净的结果。这里先把脚本文本写成缓存文件，再复用
         * runScript 的「复制进容器 + sh 路径执行」流程，终端只显示一行 sh 命令和真正输出。
         */
        fun runInlineScript(context: android.content.Context, name: String, script: String) {
            val tmp = java.io.File(context.cacheDir, "inline-${System.currentTimeMillis()}.sh")
            val ok = runCatching { tmp.writeText(script) }.isSuccess
            if (ok && tmp.length() > 0) {
                runScript(context, tmp, name)
            } else {
                // 兜底：写文件失败时退回直接发送
                sendCommand(context, script)
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
            // 列表放进 NestedScrollView（参与嵌套滚动）：终端标签多时会超出屏幕，裸
            // LinearLayout 不滚动、屏幕外的标签点不到。用 NestedScrollView 而非普通
            // ScrollView 是关键——它能和 BottomSheet 协同嵌套滚动：列表没到顶时下滑滚
            // 列表，到顶后继续下拉就把 sheet 收起/关闭（普通 ScrollView 不派发嵌套滚动、
            // sheet 拿不到列表状态，只能靠禁拖拽避开冲突，但那样就无法下滑关闭弹窗了）。
            val scroll = NestedScrollView(context).apply {
                isFillViewport = true
                setPadding(dp(8), 0, dp(8), 0)
            }
            val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
                listBox.addView(row)
            }
            scroll.addView(listBox)
            box.addView(scroll)
            sheet.setContentView(box)
            // 内层已用 NestedScrollView（参与嵌套滚动）：列表未到顶时下滑滚列表，
            // 到顶后继续下拉由 BottomSheet 接管收起/关闭。因此这里保留 sheet 默认可拖拽，
            // 不再 isDraggable=false（那会让弹窗无法下滑关闭）。
            // 只设直接全展开，跳过半折叠态带来的二次拖拽。
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.show()
        }
    }

    private var terminalContainer: ViewGroup? = null
    private var tabBar: LinearLayout? = null
    private var tabScroll: HorizontalScrollView? = null
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
        tabScroll = v.findViewById(R.id.tabScroll)
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
        // 标签多时 HorizontalScrollView 装不下，激活标签若落在可视区外会看不到。
        // 只在激活 Chip 真在屏外时才滚到它——已可见就绝不滚，避免每次打开/切换终端都跳一下。
        scrollTabBarToActive()
    }

    /** 把标签栏横向滚动到当前激活 Chip（post 到下一帧，等 Chip 完成布局再量位置）。 */
    private fun scrollTabBarToActive() {
        val scroll = tabScroll ?: return
        val bar = tabBar ?: return
        scroll.post {
            val idx = TerminalTabs.active
            if (idx !in TerminalTabs.tabs.indices) return@post
            val chip = bar.getChildAt(idx) ?: return@post
            // 可视区判断：激活 Chip 完全落在当前可视范围内就跳过滚动。
            // 否则每次打开/切换终端都会无条件滚一下，标签少时（本不需滚）也跳、切换也跳。
            val visibleLeft = scroll.scrollX
            val visibleRight = scroll.scrollX + scroll.width
            val fullyVisible = chip.left >= visibleLeft && chip.right <= visibleRight
            if (fullyVisible) return@post
            // 滚到让激活 Chip 偏左居中：目标 x = 左边缘 - 约 1/4 视宽留白
            val target = (chip.left - scroll.width / 4).coerceAtLeast(0)
            smoothScrollTabBarTo(target)
        }
    }

    /**
     * 标签栏平滑滚动到指定 x。HorizontalScrollView 自带 smoothScrollTo 时长极短、无缓动，
     * 视觉上像"瞬间跳过去"（卡顿感）。这里用 ValueAnimator + 减速插值做可控缓动，
     * 滚动更顺滑；同时复用同一 animator，连续触发时先取消旧的、从当前位置续滚，避免叠加抖动。
     */
    private var tabBarScrollAnim: android.animation.ValueAnimator? = null
    private fun smoothScrollTabBarTo(targetX: Int) {
        val scroll = tabScroll ?: return
        tabBarScrollAnim?.cancel()
        val from = scroll.scrollX
        if (from == targetX) return
        val anim = android.animation.ValueAnimator.ofInt(from, targetX).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { scroll.scrollTo(it.animatedValue as Int, 0) }
        }
        tabBarScrollAnim = anim
        anim.start()
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

    /** 方向键长按自动重复：主线程 Handler + 当前重复任务（UP/DOWN/LEFT/RIGHT 按住连发）。 */
    private val keyRepeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var keyRepeatRunnable: Runnable? = null
    /** 长按多久后开始连续移动（毫秒）。 */
    private val keyRepeatInitialMs = 350L
    /** 连续移动的重复间隔（毫秒）。 */
    private val keyRepeatIntervalMs = 60L

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
        /**
         * 按键标签：`^X m` 这类多字符组合把 `^X ` 前缀做成上标小字（幂数），主字符正常；
         * 单字符标签（如 @ | ^C）原样返回。保证多字符标签在统一 btnW 内不挤且不缩小主字符。
         */
        fun styledKeyLabel(label: String, baseSize: Float): CharSequence {
            // 只处理「^X 前缀 + 主字符」的多字符组合；^P/^O 等 2 字符组合保持原样。
            if (label.startsWith("^X ") && label.length > 3) {
                val sp = android.text.SpannableString(label)
                // ^X 前缀（前 2 字符）上标 + 缩小
                sp.setSpan(android.text.style.SuperscriptSpan(), 0, 2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.RelativeSizeSpan(0.85f), 0, 2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // ^X ↵：回车主字符单独调大（突出回车，与其他键稍区分）
                if (label.endsWith("↵")) {
                    sp.setSpan(android.text.style.RelativeSizeSpan(1.7f), label.length - 1,
                        label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                return sp
            }
            return label
        }

        /** 组合键长按提示文案（作用说明），未知 tag 返回 null。包含 AI_ 组合键与 CLI 一键键 ^P/^O。 */
        fun aiHintFor(tag: String): String? = when (tag) {
            "CTRL_P" -> "打开设置（CLI）\n^P"
            "CTRL_O" -> "最近会话 + 项目一起开（CLI）\n^O"
            "AI_MODELS" -> "Switch model（切换模型）\nCtrl+X 然后 M"
            "AI_SESSIONS" -> "Switch session（切换会话）\nCtrl+X 然后 L"
            "AI_NEW" -> "New session（新建会话）\nCtrl+X 然后 N"
            "AI_QUEUE" -> "Queue prompt（排队发送提示，稍后处理）\nCtrl+X 然后 Enter"
            "AI_UNDO" -> "撤销上一条消息\nCtrl+X 然后 U"
            "AI_QUIT" -> "退出\nCtrl+X 然后 Q"
            "AI_AGENT" -> "选择 Agent\nCtrl+X 然后 A"
            "AI_SIDEBAR" -> "切换侧边栏\nCtrl+X 然后 B"
            "AI_JUMPMSG" -> "跳转到消息\nCtrl+X 然后 G"
            "AI_EXPORT" -> "导出会话到编辑器\nCtrl+X 然后 X"
            "AI_CLOSETAB" -> "关闭当前标签页\nCtrl+X 然后 W"
            "AI_RENAME" -> "重命名当前会话\nCtrl+R"
            else -> null
        }
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
                    // 多字符标签（如 ^X m / ^X ⏎）：把 ^X 前缀做成上标小字（幂数形式），
                    // 主字母保持正常 textSize；单字符标签原样。这样两页按键宽度一致且不挤。
                    text = styledKeyLabel(label, textSize)
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
            // 方向键 / BCK 支持长按自动重复（按住连续移动光标 / 连续退格）；其余键保持单击一次。
            if (tag == "UP" || tag == "DOWN" || tag == "LEFT" || tag == "RIGHT" || tag == "BCK") {
                card.setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // 按下立即发一次；初始延迟后开始连续重复
                            if (sendShortcutBytes(tag)) {
                                vibrateKey(false)   // 按下：短振一次
                                val run = object : Runnable {
                                    override fun run() {
                                        if (sendShortcutBytes(tag)) {
                                            vibrateKey(true)   // 长按重复：每次轻振（连续振动效果）
                                            keyRepeatHandler.postDelayed(this, keyRepeatIntervalMs)
                                        }
                                    }
                                }
                                keyRepeatRunnable = run
                                keyRepeatHandler.postDelayed(run, keyRepeatInitialMs)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            keyRepeatRunnable?.let { keyRepeatHandler.removeCallbacks(it) }
                            keyRepeatRunnable = null
                            true
                        }
                        else -> false
                    }
                }
            } else {
                card.setOnClickListener { onShortcut(tag, card) }
            }
            // 组合键长按显示系统原生气泡提示（与底部导航栏长按一致），
            // 用 view.tooltipText（API 26+）而非手动 Toast；单击仍正常触发。
            // 凡是有提示文案的键都设（AI_ 组合键 + CLI 一键键 ^P/^O），不再仅限 AI_ 前缀——
            // 之前 ^P/^O（tag=CTRL_P/CTRL_O）因前缀不匹配被跳过，长按无提示。
            val hint = aiHintFor(tag)
            if (hint != null) card.tooltipText = hint
            if (tag == "CTRL" || tag == "ALT" || tag == "SHIFT") modButtons.add(card)
            return card
        }
        fun makeRow(keys: List<Triple<String, String, Int>>): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((tag, label, iconRes) in keys) {
                // 两页按键宽度/间距必须一致：全部用 btnW + 统一 gap。
                val lp = LinearLayout.LayoutParams(btnW, btnH)
                lp.setMargins(gap, 0, gap, 0)
                // 文字键统一 11sp（方向/回车走图标）；多字符标签用上标（见 makeBtn）。
                row.addView(makeBtn(tag, label, 11f, iconRes), lp)
            }
            return row
        }
        fun rowLp() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 3.dp }

        // 面板一 第一行：ESC TAB ALT BCK ↑ 回车 HOME PGUP
        val row1 = listOf(
            Triple("ESC", "ESC", 0), Triple("TAB", "TAB", 0), Triple("ALT", "ALT", 0),
            Triple("BCK", "BCK", 0), Triple("UP", "", R.drawable.ic_key_up),
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
        // 面板二：opencode/mimo 命令面板。显示字母统一大写（发送逻辑仍用小写，避免 ^X+大写失效）。
        // 排序：@ $ 最前（不要尾部）→ ^P ^O ^R（两个字符键）→ ^X 双组合键（M L N ↵ U Q A B G X W）。
        // ^X 组合：M=Switch model L=Switch session N=New session ↵=Queue prompt
        // U=撤销上一条 Q=退出 A=选择 Agent B=切换侧边栏 G=跳转到消息 X=导出会话到编辑器 W=关闭当前标签页；
        // ^P=Open settings ^O=最近会话+项目 ^R=重命名会话。
        val row3 = listOf(
            Triple("AT", "@", 0), Triple("DOLLAR", "\$", 0),
            Triple("CTRL_P", "^P", 0), Triple("CTRL_O", "^O", 0), Triple("AI_RENAME", "^R", 0),
            Triple("AI_MODELS", "^X M", 0), Triple("AI_SESSIONS", "^X L", 0),
            Triple("AI_NEW", "^X N", 0)
        )
        val row4 = listOf(
            Triple("AI_QUEUE", "^X ↵", 0), Triple("AI_UNDO", "^X U", 0),
            Triple("AI_QUIT", "^X Q", 0), Triple("AI_AGENT", "^X A", 0),
            Triple("AI_SIDEBAR", "^X B", 0), Triple("AI_JUMPMSG", "^X G", 0),
            Triple("AI_EXPORT", "^X X", 0), Triple("AI_CLOSETAB", "^X W", 0)
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
            "AI_MODELS" -> sendAiCommand('m')   // ^X 然后 m = Switch model
            "AI_SESSIONS" -> sendAiCommand('l') // ^X 然后 l = Switch session
            "AI_NEW" -> sendAiCommand('n')      // ^X 然后 n = New session
            "AI_QUEUE" -> sendAiCommand('\r')   // ^X 然后 Enter = Queue prompt（排队发送）
            "AI_UNDO" -> sendAiCommand('u')     // ^X 然后 u = 撤销上一条消息
            "AI_QUIT" -> sendAiCommand('q')     // ^X 然后 q = 退出
            "AI_AGENT" -> sendAiCommand('a')    // ^X 然后 a = 选择 Agent
            "AI_SIDEBAR" -> sendAiCommand('b')  // ^X 然后 b = 切换侧边栏
            "AI_JUMPMSG" -> sendAiCommand('g')  // ^X 然后 g = 跳转到消息
            "AI_EXPORT" -> sendAiCommand('x')   // ^X 然后 x = 导出会话到编辑器
            "AI_CLOSETAB" -> sendAiCommand('w') // ^X 然后 w = 关闭当前标签页
            "AI_RENAME" -> { if (sendShortcutBytes("CTRL_R")) vibrateKey(false) } // Ctrl+R = 重命名会话
            else -> {
                if (sendShortcutBytes(tag)) vibrateKey(false)
            }
        }
    }

    /**
     * AI 工具（opencode / mimo）命令面板动作：按 ^X 打开命令面板，再按一个字母键
     * （m=Switch model / l=Switch session / n=New session）触发对应动作。
     * 面板打开需一点时间，用 Handler 延迟再发字母。
     */
    private fun sendAiCommand(letter: Char) {
        val s = TerminalTabs.activeSession() ?: return
        vibrateKey(false)
        s.write(byteArrayOf(0x18), 0, 1)   // ^X 打开命令面板
        keyRepeatHandler.postDelayed({
            val s2 = TerminalTabs.activeSession() ?: return@postDelayed
            s2.write(byteArrayOf(letter.code.toByte()), 0, 1)
        }, 300)
    }

    /**
     * 发送一次按键序列（含粘性修饰键组合），并消费一次修饰键（CTRL/ALT/SHIFT 单次生效，FN 保持）。
     * 返回是否真的发出了字节（无活动会话时返回 false，供长按重复判断是否继续）。
     */
    private fun sendShortcutBytes(tag: String): Boolean {
        val s = TerminalTabs.activeSession() ?: return false
        val bytes = keySequence(tag, ctrlActive, altActive, shiftActive, fnActive)
        if (bytes.isNotEmpty()) s.write(bytes, 0, bytes.size)
        // 修饰键消费一次后自动复位（shift/alt/ctrl 单次生效；FN 保持切换态直到再次点击）
        if (ctrlActive || altActive || shiftActive) resetModifiers()
        return true
    }

    /** 终端按键振动反馈：尊重设置开关；强度按百分比（无极滑条）映射 amplitude。 */
    private fun vibrateKey(light: Boolean) {
        if (!AppSettings.terminalVibration(requireContext())) return
        runCatching {
            val vm = requireContext().getSystemService(Context.VIBRATOR_SERVICE)
                as? android.os.Vibrator ?: return
            if (!vm.hasVibrator()) return
            // amplitude 按设置百分比缩放（5%~100% → 13~255），duration 固定：
            // light=true 是长按重复的轻脉冲（更短），false 是单击。
            val pct = AppSettings.vibrationStrengthPct(requireContext())
            val dur = if (light) 10L else 22L
            val amp = (pct / 100f * 255f).toInt().coerceIn(1, 255)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vm.vibrate(android.os.VibrationEffect.createOneShot(dur, amp))
            } else {
                @Suppress("DEPRECATION")
                vm.vibrate(dur)
            }
        }
    }

    /**
     * 振动强度预览：设置弹窗拖动强度滑条时实时振动一次，让用户边拖边对比强弱。
     * 强度读当前已保存的百分比（listener 先 setVibrationStrengthPct 再调这里）。
     */
    fun previewVibration() {
        if (!AppSettings.terminalVibration(requireContext())) return
        vibrateKey(false)
    }

    /** 修饰键复位（单次生效后恢复未激活态）。 */    private fun resetModifiers() {
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
            "BCK" -> "\u0008"
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
        // 停止方向键长按重复（视图销毁时不再往会话发键）。
        keyRepeatRunnable?.let { keyRepeatHandler.removeCallbacks(it) }
        keyRepeatRunnable = null
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
