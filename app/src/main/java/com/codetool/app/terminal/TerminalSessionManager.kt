package com.codetool.app.terminal

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import com.codetool.app.util.AppSettings
import com.codetool.app.util.FileLog

/**
 * 终端会话管理器。
 *
 * 用 Termux 的 TerminalSession 驱动一个伪终端，背后用 tawcroot 运行 rootfs 里的 bash。
 * 不走 proot。
 *
 * TerminalSession 构造：shellPath + args[] + env[] + transcriptRows + client
 * 实际 exec argv = [shellPath, *args]
 */
class TerminalSessionManager(private val context: Context) {

    companion object { private const val TAG = "TermSessionMgr" }

    @Volatile
    var currentSession: TerminalSession? = null
        private set

    /**
     * 快捷键条的粘性修饰键提供者（由 TerminalFragment 设置）。
     * TerminalView 在处理按键时会回调 readControlKey()/readAltKey() 读取这些状态，
     * 从而把软键盘字母组合成 Ctrl/Ctrl+Alt 序列（否则软键盘字母直接进终端、不带修饰）。
     */
    @Volatile
    var externalCtrlProvider: (() -> Boolean)? = null
    @Volatile
    var externalAltProvider: (() -> Boolean)? = null

    /** TerminalFragment 调用：把快捷键条的 Ctrl/Alt 状态接入 viewClient。 */
    fun setModifierProviders(ctrl: (() -> Boolean)?, alt: (() -> Boolean)?) {
        externalCtrlProvider = ctrl
        externalAltProvider = alt
    }

    /**
     * 粘性修饰键被「消费」后的回调（TerminalFragment 设置）。
     * TerminalView 每处理一个字符都调 onCodePoint；当带了 Ctrl/Alt（controlDown/altDown）
     * 说明快捷键条的粘性状态被用掉一次 → 回调 Fragment 复位按钮着色和状态。
     */
    @Volatile
    var onModifierConsumed: ((ctrl: Boolean, alt: Boolean) -> Unit)? = null

    /** 会话命令规格：在后台准备，主线程据此创建 TerminalSession。 */
    data class SessionSpec(
        val shellPath: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String
    )

    /** 点击终端回调（用于弹输入法）。由 TerminalFragment 设置。 */
    var onTerminalTapped: (() -> Unit)? = null

    /** 当前挂载的 TerminalView（onTextChanged 时用来实时重绘）。 */
    @Volatile
    private var attachedView: TerminalView? = null

    /**
     * 主题解析上下文：Fragment 可见时设为 Activity context（保证与界面实际深浅色一致），
     * 视图销毁时清空。不能用 applicationContext：App 强制浅色/深色时它会按系统夜间模式
     * 取到相反的颜色（典型的「浅色界面 + 浅色文字」不可读问题）。
     */
    @Volatile
    var themeContext: Context? = null

    /** onTextChanged 采样计数（诊断刷新链路，避免日志爆炸）。 */
    @Volatile
    private var textChangedCount = 0

    /** 是否已向 TerminalService 记过一次活跃会话（避免 stop()/onSessionFinished 重复扣减）。 */
    @Volatile
    private var sessionCounted = false

    /**
     * 后台线程：启动服务、确保 rootfs 解压、构建 tawcroot 启动命令。
     * 返回 [SessionSpec]；不创建 TerminalSession（需主线程 Looper）。
     */
    fun prepare(): SessionSpec {
        Log.i(TAG, "prepare: step1 startForegroundService")
        // 1. 确保终端服务已启动（持有 wake lock）
        context.startForegroundService(
            Intent(context, TerminalService::class.java)
                .setAction(TerminalService.ACTION_START)
                .putExtra(TerminalService.EXTRA_KEEP_WIFI, true)
        )
        TerminalService.instance?.onSessionStarted()
        sessionCounted = true

        // 2. 确保 rootfs 已解压（阻塞，后台线程）
        Log.i(TAG, "prepare: step2 ensureExtractedSync")
        val rootfs = RootfsManager.get(context).ensureExtractedSync()

        // 3. 通过 tawcroot 启动 bash（伪装 root）
        Log.i(TAG, "prepare: step3 ensureExtracted + buildLaunch")
        val runtime = TawcrootRuntime.get(context)
        runtime.ensureExtracted()
        val cmd = runtime.buildLaunchCommand(rootfs)
        val shellPath = cmd.first()
        // ★ argv[0] 必须是程序名（shellPath）：Termux createSubprocess 里 execvp(cmd, argv=args)，
        //   argv[0]=args[0]。若 args[0] 是 "-r" 而程序是 libtawcroot.so，则 tawcroot 把 rootfs 当
        //   选项解析报 "unknown option"。所以 args 必须包含 shellPath 作为首个元素。
        val args = cmd
        val env = arrayOf(
            "HOME=/root",
            "USER=root",
            "SHELL=/bin/bash",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 合成器在 App 进程 setenv 了 TAWC_XKB_CONFIG_ROOT，Termux 的
            // createSubprocess 会把父进程环境合并进来 → chroot 内不可见的
            // XKB_CONFIG_ROOT 让 xkbcommon 失败，GUI 客户端段错误。必须清掉。
            "XKB_CONFIG_ROOT=",
            "TAWC_XKB_CONFIG_ROOT="
        )
        Log.i(TAG, "prepare: shell=$shellPath args=${args.joinToString(" ")}")
        // cwd 必须是 rootfs 路径：tawcroot chroot 到 rootfs 后，该 inode 即容器内 "/"，
        // 否则（如 cwd="/"）chroot 后 getcwd 失败 → bash 启动即退出。
        return SessionSpec(shellPath, args, env, rootfs.absolutePath)
    }

    /** 主线程：创建 TerminalSession（Termux 构造需要 Looper）。 */
    fun createSession(spec: SessionSpec): TerminalSession {
        val session = TerminalSession(
            spec.shellPath,
            spec.cwd,                               // cwd=rootfs 路径：chroot 后即容器内 "/"
            spec.args,                              // args[]
            spec.env,                               // env[]
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client
        )
        currentSession = session
        Log.i(TAG, "session created on main thread")
        return session
    }

    /** 在 UI 线程把会话挂到视图。 */
    fun attach(view: TerminalView, session: TerminalSession) {
        attachedView = view
        // ★ TerminalViewClient 必须先在 setTextSize 之前设置：
        //   setTextSize 可能触发 updateSize（内部调 mClient.onEmulatorSet()），
        //   若不先设会 NPE。同理后续的长按、按键回调都要 mClient 非空。
        view.setTerminalViewClient(viewClient)
        // Termux 的 mRenderer 只在 setTextSize()/setTypeface() 里创建，
        // 直接 attachSession → updateSize → mRenderer.mFontWidth 会 NPE。
        // 所以先 setTextSize 初始化 renderer（字号可调，默认调大）。
        view.setTextSize(AppSettings.fontSize(context))
        // ★ attachSession 之前先设主题色：session 的 emulator 颜色表创建后即存在，
        //   提前设置让 attachSession 内部首帧就用主题色（否则先画一帧默认黑色再改回，
        //   用户首次滑到终端页会看到「黑→主题色」闪烁）。
        applyThemeColors(session)
        // attach 期间临时隐藏：杜绝任何中间帧（黑帧）被合成到屏幕
        view.visibility = View.INVISIBLE
        view.attachSession(session)
        // 下一帧前恢复显示：此时颜色已是主题色
        view.post {
            applyThemeColors(session)
            view.visibility = View.VISIBLE
        }
    }

    /** 视图销毁时解除挂接（会话本身继续运行）。 */
    fun detachView() {
        attachedView = null
    }

    /** 按主题设置终端前景/背景/光标色（深浅色切换）。 */
    fun applyThemeColors(session: TerminalSession) {
        try {
            // attach 前 emulator 尚未创建（Termux 在 attachSession 内初始化）：
            // 直接跳过，attach 完成后的 post 会再次应用。
            val emulator = session.emulator ?: return
            val themeCtx = themeContext ?: context
            val onSurface = com.google.android.material.color.MaterialColors.getColor(
                themeCtx, com.google.android.material.R.attr.colorOnSurface, 0xFFDEE4E2.toInt())
            val surface = com.google.android.material.color.MaterialColors.getColor(
                themeCtx, com.google.android.material.R.attr.colorSurface, 0xFF0B1213.toInt())
            val colors = emulator.mColors.mCurrentColors
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = onSurface
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = surface
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = onSurface
            // ★ 默认背景色的单元格渲染器不会填色，靠 View 自身背景；
            //   动态创建的 TerminalView 没有 XML background，必须在这里同步，否则页面底色透出。
            attachedView?.setBackgroundColor(surface)
            FileLog.i(context, TAG, "applyThemeColors fg=0x${Integer.toHexString(onSurface)} bg=0x${Integer.toHexString(surface)} cursor=0x${Integer.toHexString(onSurface)}")
            attachedView?.postInvalidate()
        } catch (e: Exception) {
            Log.w(TAG, "applyThemeColors failed: ${e.message}")
            FileLog.e(context, TAG, "applyThemeColors failed: ${e.message}", e)
        }
    }

    /** 停止当前会话。计数交给 onSessionFinished，避免多标签下重复扣减。 */
    fun stop() {
        val s = currentSession
        currentSession = null
        attachedView = null
        s?.finishIfRunning()
        if (s == null) releaseCount()
    }

    /** 创建会话失败等场景：释放 prepare() 记过的活跃会话计数。 */
    fun abandon() {
        currentSession = null
        attachedView = null
        releaseCount()
    }

    private fun releaseCount() {
        if (sessionCounted) {
            sessionCounted = false
            TerminalService.instance?.onSessionEnded()
        }
    }

    /** Termux 终端会话回调。TerminalSessionClient 接口，需实现全部方法。 */
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession?) {
            // ★ 屏幕更新时实时刷新终端：Termux 的 TerminalView.onScreenUpdated() 由
            //   session client 的 onTextChanged 触发（内部 invalidate）。
            //   onTextChanged 在 pty reader 线程调用，需切主线程再 onScreenUpdated。
            val view = attachedView
            if (view == null) {
                if (++textChangedCount % 50 == 1) FileLog.w(context, TAG, "onTextChanged#$textChangedCount attachedView=null")
                return
            }
            view.post {
                val attached = view.isAttachedToWindow
                if (++textChangedCount % 50 == 1) FileLog.i(context, TAG, "onTextChanged#$textChangedCount attached=$attached")
                if (attached) view.onScreenUpdated()
            }
        }
        override fun onTitleChanged(changedSession: TerminalSession?) {}
        override fun onSessionFinished(finishedSession: TerminalSession?) {
            val exit = finishedSession?.exitStatus
            val text = try {
                finishedSession?.emulator?.screen?.transcriptText?.take(600) ?: ""
            } catch (t: Throwable) { "transcript err: $t" }
            Log.i(TAG, "session finished exit=$exit")
            FileLog.i(context, TAG, "session finished exit=$exit\n--- transcript ---\n$text\n--- end ---")
            releaseCount()
        }
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
            // ★ 长按选区复制：Termux 库的 ActionMode 复制按钮会回调到这里。
            //   需要用 ClipboardManager 写入主剪贴板，否则「点击复制没反应」。
            try {
                if (text.isNullOrEmpty()) return
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Terminal selection", text))
                FileLog.i(context, TAG, "copy to clipboard (${text.length} chars)")
            } catch (e: Exception) {
                FileLog.e(context, TAG, "copy clipboard failed: ${e.message}", e)
            }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            // ★ 粘贴（ActionMode 粘贴按钮回调；输入框输入法粘板走 onPasteTextFromClipboardByKeyboard，这里仅处理系统菜单）
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val item = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: return
                if (item.isEmpty()) return
                val session = currentSession ?: return
                val bytes = item.toByteArray()
                session.write(bytes, 0, bytes.size)
                FileLog.i(context, TAG, "paste ${item.length} chars")
            } catch (e: Exception) {
                FileLog.e(context, TAG, "paste clipboard failed: ${e.message}", e)
            }
        }
        override fun onBell(session: TerminalSession?) {}
        override fun onColorsChanged(session: TerminalSession?) {
            // 颜色变化（如终端程序改配色）：重绘以生效
            attachedView?.post { attachedView?.onScreenUpdated() }
        }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) {
            Log.e(tag, message ?: "")
            FileLog.e(context, tag ?: TAG, message ?: "")
        }
        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag, message ?: "")
            FileLog.w(context, tag ?: TAG, message ?: "")
        }
        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag, message ?: "")
            FileLog.i(context, tag ?: TAG, message ?: "")
        }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag, message, e)
            FileLog.e(context, tag ?: TAG, message ?: "", e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag, "", e)
            FileLog.e(context, tag ?: TAG, "", e)
        }
    }

    /** TerminalView 回调（Termux 的 view 层）。日志也写入文件。 */
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {
            // 点击终端：弹出输入法（Termux 由 Activity 的 onSingleTapUp 显示 IME）
            onTerminalTapped?.invoke()
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = externalCtrlProvider?.invoke() ?: false
        override fun readAltKey(): Boolean = externalAltProvider?.invoke() ?: false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // 带了 Ctrl/Alt 说明快捷键条的粘性状态被消费一次 → 通知 Fragment 复位
            val altNow = externalAltProvider?.invoke() ?: false
            FileLog.i(context, TAG, "onCodePoint cp=$codePoint ctrlDown=$ctrlDown altDown=$altNow")
            if (ctrlDown || altNow) {
                onModifierConsumed?.invoke(ctrlDown, altNow)
            }
            return false
        }
        override fun onEmulatorSet() {
            Log.i(TAG, "onEmulatorSet")
            FileLog.i(context, TAG, "onEmulatorSet")
            // ★ updateSize 在 invalidate 之前调 onEmulatorSet：此处重新应用主题色，
            //   确保首帧渲染就用主题色（避免浅色模式黑→白闪烁 + 文字不可见）。
            //   resize 后也重新应用（防止配色被重置）。
            currentSession?.let { applyThemeColors(it) }
        }
        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
            FileLog.e(context, tag, message)
        }
        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
            FileLog.w(context, tag, message)
        }
        override fun logInfo(tag: String, message: String) {
            Log.i(tag, message)
            FileLog.i(context, tag, message)
        }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
            FileLog.e(context, tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, "", e)
            FileLog.e(context, tag, "", e)
        }
    }
}
