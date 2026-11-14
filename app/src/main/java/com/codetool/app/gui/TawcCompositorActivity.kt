package com.codetool.app.gui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import com.codetool.app.R
import com.codetool.app.util.FileLog
import me.phie.tawc.compositor.NativeBridge
import java.io.File
import kotlin.math.roundToInt

/**
 * tawc compositor 桌面页（多标签）。
 *
 * 一个页面同时管理多个 Linux 窗口：
 * - 顶部：标签栏（每窗口一个标签，可切换/关闭）+ 全屏/键盘/退出按钮；
 * - 主体：所有窗口的 SurfaceView 叠放，选中标签即把对应 SurfaceView
 *   提到最前并调用 nativeOnActivityFocusChanged(id, true)，合成器只渲染
 *   前台宿主，于是只显示当前标签；
 * - 底部：可呼出的快捷键条（ESC/TAB/CTRL/ALT/SHIFT/DEL/HOME/PGUP/PGDN/方向键）；
 * - 指针：手指/鼠标事件转发为 Wayland 指针事件，并绘制可见鼠标指针。
 *
 * 窗口创建由原生侧 spawnActivity(id) 通知，不新开 Activity。
 */
open class TawcCompositorActivity : Activity() {

    companion object {
        private const val TAG = "TawcCompositor"
        private const val OUTPUT_SCALE = 2.0f

        /** 兼容旧窗口宿主 intent 的 extra（新模型不再使用）。 */
        const val EXTRA_WINDOW_ID = "window_id"

        private const val BAR_BG = 0xE6101418.toInt()
        private const val TAB_BG = 0x22FFFFFF
        private const val TAB_SELECTED_BG = 0xFFFF6B35.toInt()
        private const val CHIP_ON_BG = 0xFFFF6B35.toInt()

        /** 侧栏宽度（dp）。收起时窗口区占满屏幕。 */
        private const val RAIL_WIDTH_DP = 176
        /** 悬浮窗宽度（dp）：比停靠宽不少、高度自适应，看着像悬浮小窗。 */
        private const val RAIL_FLOAT_WIDTH_DP = 320

        private const val POINTER_KIND_MOTION = 0
        private const val POINTER_KIND_BUTTON = 1
        private const val POINTER_KIND_AXIS = 2

        private const val MOUSE_BUTTON_MASK =
            MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY or
                MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_BACK or
                MotionEvent.BUTTON_FORWARD

        @Volatile
        var instance: TawcCompositorActivity? = null
    }

    private lateinit var root: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var rail: LinearLayout
    private lateinit var floatBtn: android.widget.ImageView
    /** 自绘软件光标：触摸/鼠标移动时跟随，只显示在窗口区（桌面不显示）。 */
    private lateinit var cursorView: android.widget.ImageView

    /** 光标子窗口（TYPE_APPLICATION_PANEL）：见 attachCursorWindow()。 */
    private var cursorWm: android.view.WindowManager? = null
    private var cursorParams: android.view.WindowManager.LayoutParams? = null
    /** 停靠模式下窗口区与侧栏之间的细分割线。 */
    private lateinit var railDivider: View
    private lateinit var surfaceLayer: FrameLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var touchpadContainer: LinearLayout
    private lateinit var touchpadToggleBtn: android.widget.ImageView
    private var touchpadVisible = true
    private lateinit var shortcutBar: LinearLayout
    private lateinit var placeholder: TextView
    private lateinit var fullscreenBtn: android.widget.ImageView
    private lateinit var keyboardBtn: android.widget.ImageView
    private lateinit var desktopPanel: FrameLayout
    private lateinit var desktopGrid: LinearLayout
    private lateinit var desktopScroll: ScrollView
    private lateinit var desktopStatus: TextView
    /** 从 rootfs .desktop 自动发现的已安装 Linux 程序（后台扫描后填充）。 */
    private var discoveredApps: List<DiscoveredApp> = emptyList()
    private var discovering = false

    private var compositorStarted = false
    private var envReady = false
    private var shortcutVisible = false
    private var desktopVisible = true

    /** 侧栏的三种形态。 */
    private enum class RailMode {
        /** 停靠：贴在右侧跟窗口并排，窗口让出宽度（默认，最省空间）。 */
        DOCKED,
        /** 悬浮：半透明浮在窗口之上，可拖动，窗口铺满整屏。 */
        FLOATING,
        /** 隐藏：全屏，只剩左上角悬浮球。 */
        HIDDEN,
    }

    private var railMode = RailMode.DOCKED
    /** 悬浮模式下侧栏左上角的位置（px）。 */
    private var railFloatX = -1
    private var railFloatY = -1
    private var railDragStartX = 0f
    private var railDragStartY = 0f
    private var railDragStartLeft = 0
    private var railDragStartTop = 0
    private lateinit var floatToggleBtn: android.widget.ImageView

    /** 最近一次 inset（供侧栏定位/让位计算）。 */
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    /** 正在安装中的应用 id → 取消轮询用。 */
    private val installing = HashSet<String>()

    // 主题色（loadThemeColors 里解析，侧栏/桌面共用）
    private var cPrimary = 0xFF006A78.toInt()
    private var cOnPrimary = 0xFFFFFFFF.toInt()
    private var cPrimaryContainer = 0xFF00505A.toInt()
    private var cOnPrimaryContainer = 0xFF97F0FF.toInt()
    private var cSurface = 0xFFF2FAFB.toInt()
    private var cSurfaceContainer = 0xFFE6EFF1.toInt()
    private var cSurfaceVariant = 0xFFDBE4E8.toInt()
    private var cOnSurface = 0xFF1B1B1B.toInt()
    private var cOnSurfaceVariant = 0xFF5E6A6D.toInt()
    private var cOutlineVariant = 0xFFC3CBCE.toInt()
    private var cRailBg = 0xFFE6EFF1.toInt()

    /** 鼠标/触控板的绝对屏幕坐标（相对设备只能靠累加得到）。 */
    private var pointerScreenX = -1f
    private var pointerScreenY = -1f

    /** 绝对鼠标上一次事件的 rawX/rawY，用来算增量（避免直接用绝对值到边跳变）。 */
    private var lastAbsX = 0f
    private var lastAbsY = 0f
    /** 鼠标双路径：设备是否填相对轴、是否回退到绝对模式、连续未填计数、屏幕尺寸。 */
    private var relAxisSeen = false
    private var absFallback = false
    private var relAxisMiss = 0
    private var lastScreenW = 0
    private var lastScreenH = 0
    /** 鼠标诊断限频时间戳。 */
    private var lastPcapLog = 0L

    // ---- 触摸板手势状态（整块屏幕当触摸板，光标一直显示）----
    private val padHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 上一次手指位置（view 坐标）。 */
    private var padLastX = 0f
    private var padLastY = 0f
    /** 按下时的位置/时间。 */
    private var padDownX = 0f
    private var padDownY = 0f
    private var padDownTime = 0L
    private var padMoved = false
    /** 长按已触发（右键）。 */
    private var padLong = false
    /** 双击后按住不放（拖拽中，左键已按下）。 */
    private var padDrag = false
    /** 双指滚动中。 */
    private var padScroll = false
    /** 本轮按下出现过双指（双指轻点=右键 / 双指滑动=滚动的判定基础）。 */
    private var padTwoFinger = false
    /** 双指期间移动超过阈值 → 是滚动，不是右键轻点。 */
    private var padTwoFingerMoved = false
    /** 第二根手指按下的时间（轻点时长窗口）。 */
    private var padTwoFingerTime = 0L
    /** 上一次轻点抬手时间（判定双击/拖拽用）。 */
    private var padLastTapUp = 0L
    private var padLongRunnable: Runnable? = null

    private fun cancelPadLong() {
        padLongRunnable?.let { padHandler.removeCallbacks(it) }
        padLongRunnable = null
    }

    private val tabs = LinkedHashMap<String, TabEntry>()
    private var selectedId: String? = null
    private var tabSeq = 0

    /** 修饰键（CTRL/ALT/SHIFT）当前是否按下。 */
    private val modifierState = HashMap<Int, Boolean>()

    /** 快捷键条上修饰键的按钮视图，便于一次性消耗后复位高亮。 */
    private val modifierButtons = HashMap<Int, TextView>()

    private data class TabEntry(
        val id: String,
        var title: String,
        val surfaceView: SurfaceView,
        val chip: LinearLayout,
        val chipLabel: TextView,
    )

    private data class KeyDef(val label: String, val keycode: Int, val toggle: Boolean)

    private val keyDefs = listOf(
        KeyDef("ESC", KeyEvent.KEYCODE_ESCAPE, false),
        KeyDef("TAB", KeyEvent.KEYCODE_TAB, false),
        KeyDef("CTRL", KeyEvent.KEYCODE_CTRL_LEFT, true),
        KeyDef("ALT", KeyEvent.KEYCODE_ALT_LEFT, true),
        KeyDef("SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT, true),
        KeyDef("DEL", KeyEvent.KEYCODE_FORWARD_DEL, false),
        KeyDef("HOME", KeyEvent.KEYCODE_MOVE_HOME, false),
        KeyDef("PGUP", KeyEvent.KEYCODE_PAGE_UP, false),
        KeyDef("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, false),
        KeyDef("←", KeyEvent.KEYCODE_DPAD_LEFT, false),
        KeyDef("→", KeyEvent.KEYCODE_DPAD_RIGHT, false),
        KeyDef("↑", KeyEvent.KEYCODE_DPAD_UP, false),
        KeyDef("↓", KeyEvent.KEYCODE_DPAD_DOWN, false),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 先套用应用主题（浅/深 + 颜色），侧栏和内嵌桌面都靠 ?attr 取色，
        // 否则这里只有 DeviceDefault，读不到 Material 的 colorPrimary 等属性。
        com.codetool.app.theme.ThemeManager.applyTo(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 软键盘弹出时把窗口内容整体上推，避免遮住容器窗口下半部分。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        NativeBridge.appContext = applicationContext
        instance = this

        // 容器内 → app 的浏览器桥：模型/脚本在终端执行
        // `codetool-browser open <url>`，请求经共享目录到这里，由合成器拉起内置 Chrome 渲染。
        BrowserBridge.start(this) { req, respond ->
            when (req.optString("action")) {
                "open" -> {
                    val url = req.optString("url", "about:blank")
                    if (launchChromeWithUrl(url)) {
                        respond(true, "已在内置浏览器打开：$url（CDP http://127.0.0.1:9222）")
                    } else {
                        respond(false, "浏览器未安装：请在 app 的「工具 → 包管理」点「安装浏览器（Chrome / Firefox）」，装好后重新打开 GUI 显示")
                    }
                }
                else -> respond(false, "未知操作：${req.optString("action")}")
            }
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        buildUi()

        try {
            prepareEnvironment()
        } catch (e: Throwable) {
            FileLog.e(this, TAG, "prepareEnvironment failed: ${e.message}", e)
            Log.e(TAG, "prepareEnvironment failed", e)
        }
        startCompositorIfNeeded()
        // 默认停靠右侧：侧栏与窗口并排，窗口拿到剩下的最大空间。
        setRailMode(RailMode.DOCKED)

        // 旧模型残留：若被带 window_id 启动，登记为一个标签。
        intent?.getStringExtra(EXTRA_WINDOW_ID)?.let { onWindowSpawned(it) }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        instance = this
        intent?.getStringExtra(EXTRA_WINDOW_ID)?.let { onWindowSpawned(it) }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun buildUi() {
        // 侧栏/桌面配色全部取自当前主题（ThemeManager 已 setTheme）。
        loadThemeColors()

        // 左侧竖排侧栏（标签 + 控制按钮）+ 右侧窗口区。横屏下侧栏比顶栏
        // 更省空间：窗口能占满整个高度，只让出很窄的一条。
        surfaceLayer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        placeholder = TextView(this).apply {
            text = "等待 Linux 窗口…\n在终端运行 GUI 程序，或点左上角按钮打开应用"
            setTextColor(cOnSurfaceVariant)
            textSize = 14f
            gravity = Gravity.CENTER
        }

        // ---- 窗口区（侧栏右侧）----
        contentHost = FrameLayout(this).apply {
            addView(surfaceLayer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(placeholder, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        desktopPanel = buildDesktopPanel()
        contentHost.addView(desktopPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // ---- 侧栏 ----
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            // 停靠时与窗口融合：无圆角、无边框（悬浮态由 setRailBackground 切换）
            elevation = 0f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cRailBg)
            }
        }

        // 侧栏顶部：桌面（主页）+ 标题 + 收起
        val railHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val desktopBtn = railIconButton(R.drawable.ic_home, cPrimary) { showDesktop(true) }
        railHeader.addView(desktopBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        val railTitle = TextView(this).apply {
            text = "窗口"
            textSize = 13f
            setTextColor(cOnSurface)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        railHeader.addView(railTitle, LinearLayout.LayoutParams(0, dp(34), 1f))
        // 悬浮/停靠切换：悬浮后半透明浮在窗口上，可拖动（方便边看窗口边调试）。
        floatToggleBtn = railIconButton(R.drawable.ic_float, cOnSurfaceVariant) {
            setRailMode(if (railMode == RailMode.FLOATING) RailMode.DOCKED else RailMode.FLOATING)
        }
        railHeader.addView(floatToggleBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        // 触摸板切换：显示/隐藏侧栏触摸板 + 左中右键（默认显示）。
        touchpadToggleBtn = railIconButton(R.drawable.ic_touchpad, cPrimary) {
            toggleTouchpad()
        }
        railHeader.addView(touchpadToggleBtn, LinearLayout.LayoutParams(dp(34), dp(34)))
        rail.addView(railHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 分隔线
        rail.addView(View(this).apply { setBackgroundColor(cOutlineVariant) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(10); bottomMargin = dp(8) })

        // 标签列表（竖排，可滚动）
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val tabScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        tabScroll.addView(tabRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        rail.addView(tabScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- 触摸板容器：可通过按钮显示/隐藏（触摸板 + 左中右键） ----
        touchpadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val touchpad = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cSurfaceContainer)
                setStroke(dp(1), cOutlineVariant)
            }
        }
        touchpad.setOnTouchListener { _, e -> handleTouchpadTouch(e) }
        touchpadContainer.addView(touchpad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ).apply { topMargin = dp(6) })

        // 左/中/右键
        val mouseBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun mouseBtn(label: String, button: Int): View {
            val b = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(cOnSurface)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(cSurfaceContainer)
                    setStroke(dp(1), cOutlineVariant)
                }
                setOnClickListener {
                    val host = selectedId
                    if (host != null) {
                        val now = android.os.SystemClock.uptimeMillis()
                        sendPointerMotionAt(host, currentPointerPos(), now)
                        sendPointerButtonAt(host, button, true, currentPointerPos(), now)
                        sendPointerButtonAt(host, button, false, currentPointerPos(), now)
                    } else if (button == MotionEvent.BUTTON_PRIMARY) {
                        // 首页无窗口 host：把左键点击派给光标下的桌面图标。
                        clickDesktopAtPointer()
                    }
                }
            }
            return b
        }
        mouseBtnRow.addView(mouseBtn("L", MotionEvent.BUTTON_PRIMARY), LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(4) })
        mouseBtnRow.addView(mouseBtn("M", MotionEvent.BUTTON_TERTIARY), LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(4) })
        mouseBtnRow.addView(mouseBtn("R", MotionEvent.BUTTON_SECONDARY), LinearLayout.LayoutParams(0, dp(30), 1f))
        touchpadContainer.addView(mouseBtnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        rail.addView(touchpadContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 侧栏底部：一排四个按钮（软键盘 / 快捷键条 / 全屏 / 关闭）
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // 软键盘：直接弹出 Android 输入法，打字通过 text-input 进容器客户端。
        val softKeyboardBtn = railIconButton(R.drawable.ic_keyboard, cOnSurfaceVariant) {
            val host = selectedId
            if (host == null) toast("先打开一个窗口") else showKeyboardFor(host)
        }
        // 快捷键条：CTRL/ALT/方向键等，终端里最常用。
        keyboardBtn = railIconButton(R.drawable.ic_key_up, cOnSurfaceVariant) {
            toggleShortcutBar()
        }
        fullscreenBtn = railIconButton(R.drawable.ic_fullscreen, cOnSurfaceVariant) {
            setFullscreen(railMode != RailMode.HIDDEN)
        }
        val closeBtn = railIconButton(R.drawable.ic_close, cOnSurfaceVariant) { finish() }
        actionsRow.addView(softKeyboardBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        actionsRow.addView(keyboardBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        actionsRow.addView(fullscreenBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        actionsRow.addView(closeBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        rail.addView(actionsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ---- 快捷键条：叠在窗口区底部，呼出时不改变 surface 尺寸 ----
        shortcutBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(cRailBg)
                setStroke(dp(1), cOutlineVariant)
            }
            visibility = View.GONE
        }
        val keysScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val keysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (def in keyDefs) {
            keysRow.addView(keyButton(def))
        }
        keysScroll.addView(keysRow)
        shortcutBar.addView(
            keysScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        contentHost.addView(
            shortcutBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(10) }
        )

        // ---- 组装 ----
        root.addView(contentHost, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 停靠模式下窗口与侧栏之间的细分割线（侧栏本体无边框，靠这条线区分）
        railDivider = View(this).apply {
            setBackgroundColor(cOutlineVariant)
            visibility = View.GONE
        }
        root.addView(railDivider, FrameLayout.LayoutParams(dp(1), FrameLayout.LayoutParams.MATCH_PARENT))
        // 侧栏默认停靠右侧；具体位置由 applyRailLayout() 按模式/屏幕尺寸计算，
        // 悬浮模式下同一份 LayoutParams 直接改 left/top 即可拖动。
        root.addView(rail, FrameLayout.LayoutParams(
            dp(RAIL_WIDTH_DP),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START
        ))

        // 侧栏隐藏（全屏）后浮在右上角的悬浮球，点一下恢复停靠侧栏。
        floatBtn = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_home)
            imageTintList = android.content.res.ColorStateList.valueOf(cOnPrimary)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(cPrimary)
            }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setOnClickListener { setRailMode(RailMode.DOCKED) }
        }
        root.addView(floatBtn, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END)
            .apply { rightMargin = dp(12); topMargin = dp(12) })

        // 自绘光标：不再直接放进 root —— 普通 View 会被 SurfaceView 的独立
        // 图层盖住（表现为"移到窗口区一半就消失"）。改成挂在 Activity 的
        // TYPE_APPLICATION_PANEL 子窗口上：子窗口永远画在宿主窗口（包括所有
        // SurfaceView 图层）之上，任何 ROM 上都成立。见 attachCursorWindow()。
        cursorView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor_arrow)
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            visibility = View.GONE
        }
        // 脚本输出条：显示安装/启动的实时输出（attachStatusWindow 挂成子窗口）
        statusStrip = android.widget.TextView(this).apply {
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xD9000000.toInt())
            }
            visibility = View.GONE
        }

        // 窗口 attach 后才能拿到 token 建子窗口（光标 + 脚本输出条）
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attachCursorWindow()
                attachStatusWindow()
                DesktopApps.outputObserver = { tag, line -> appendStatusLine(tag, line) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                detachCursorWindow()
                detachStatusWindow()
                if (DesktopApps.outputObserver != null) DesktopApps.outputObserver = null
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            insetTop = bars.top
            insetRight = bars.right
            insetBottom = maxOf(bars.bottom, ime)
            // 本页是桌面式的沉浸界面：左右/顶部不留系统边距（刘海区直接铺满），
            // 只让出底部导航栏/输入法，避免出现「额头黑边」。
            if (root.paddingBottom != insetBottom) {
                root.setPadding(0, 0, 0, insetBottom)
            }
            // IME 弹出时系统会强制显示导航栏，并可能重置成浅色白底（键盘下方小白条）。
            // 每次 insets 变化都：关对比度强制 + 强制深色外观 + 重新隐藏导航栏。
            // Android 15+ 忽略 navigationBarColor，只有「隐藏 navigationBars」才真正
            // 去掉那条系统白条；延迟再补一次覆盖 IME 动画末尾的系统重置。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            if (imeVisible) {
                val ctrl = WindowInsetsControllerCompat(window, root)
                ctrl.isAppearanceLightNavigationBars = false
                ctrl.hide(WindowInsetsCompat.Type.navigationBars())
                root.post {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isNavigationBarContrastEnforced = false
                    }
                    WindowInsetsControllerCompat(window, root).apply {
                        isAppearanceLightNavigationBars = false
                        hide(WindowInsetsCompat.Type.navigationBars())
                    }
                }
            } else {
                WindowInsetsControllerCompat(window, root).isAppearanceLightNavigationBars = false
            }
            val flp = floatBtn.layoutParams as FrameLayout.LayoutParams
            flp.rightMargin = bars.right + dp(12)
            flp.topMargin = bars.top + dp(12)
            floatBtn.layoutParams = flp
            applyRailLayout()
            insets
        }

        // 悬浮模式下按住侧栏标题区即可拖动（按钮本身仍可正常点击）。
        rail.setOnTouchListener { _, e ->
            if (railMode != RailMode.FLOATING) return@setOnTouchListener false
            // 只有按在标题栏高度内才开始拖，避免抢掉标签/按钮的点击。
            if (e.actionMasked == MotionEvent.ACTION_DOWN &&
                e.y > dp(10) + dp(44)
            ) {
                return@setOnTouchListener false
            }
            dragRail(e)
        }

        // 桌面/无窗口时，滑空白区也能移动光标：ScrollView 会拦截 MOVE，
        // 普通 OnTouchListener 收不到，改为 Activity.dispatchTouchEvent 统一观察。

        setRailMode(RailMode.DOCKED)
        setContentView(root)
        root.post {
            applyRailLayout()
            // PC 式：进入页面光标就显示在窗口区中央
            showCursorAtCenter()
        }
    }

    /** 从当前主题解析侧栏/桌面要用的颜色。 */
    private fun loadThemeColors() {
        fun color(attr: Int, fallback: Int): Int =
            com.google.android.material.color.MaterialColors.getColor(
                this, attr, fallback
            )

        cPrimary = color(com.google.android.material.R.attr.colorPrimary, 0xFF006A78.toInt())
        cOnPrimary = color(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt())
        cPrimaryContainer =
            color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF00505A.toInt())
        cOnPrimaryContainer =
            color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF97F0FF.toInt())
        cSurface = color(com.google.android.material.R.attr.colorSurface, 0xFFF2FAFB.toInt())
        cSurfaceContainer = color(
            com.google.android.material.R.attr.colorSurfaceContainer, 0xFFE6EFF1.toInt()
        )
        cSurfaceVariant =
            color(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFDBE4E8.toInt())
        cOnSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
        cOnSurfaceVariant = color(
            com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5E6A6D.toInt()
        )
        cOutlineVariant =
            color(com.google.android.material.R.attr.colorOutlineVariant, 0xFFC3CBCE.toInt())
        cRailBg = cSurfaceContainer
    }

    /**
     * 切换侧栏形态。
     *
     *  - [RailMode.DOCKED]：贴右停靠，窗口让出宽度（默认，最省空间）；
     *  - [RailMode.FLOATING]：半透明悬浮在窗口上，可拖动，窗口铺满；
     *  - [RailMode.HIDDEN]：全屏，只剩右上角悬浮球。
     *
     * 状态栏永远隐藏；悬浮/全屏时导航栏也隐藏。
     */
    private fun setRailMode(mode: RailMode) {
        railMode = mode
        FileLog.i(this, TAG, "railMode -> $mode")
        val floating = mode == RailMode.FLOATING
        when (mode) {
            RailMode.DOCKED -> {
                rail.visibility = View.VISIBLE
                floatBtn.visibility = View.GONE
            }
            RailMode.FLOATING -> {
                rail.visibility = View.VISIBLE
                floatBtn.visibility = View.GONE
                // 初次进入悬浮的位置由 applyRailLayout() 决定（右下侧、屏高 35%）
            }
            RailMode.HIDDEN -> {
                rail.visibility = View.GONE
                floatBtn.visibility = View.VISIBLE
                // 下次恢复悬浮时重新贴右。
                railFloatX = -1
            }
        }
        setRailBackground(floating)
        // 悬浮按钮高亮表示当前是悬浮态。
        floatToggleBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (floating) cPrimary else cOnSurfaceVariant
        )
        applyFullscreenState(mode != RailMode.DOCKED)
        applyWindowInsetsState()
        applyRailLayout()
        resetPointer()
    }

    /** 显示/隐藏侧栏触摸板区域（触摸板 + 左中右键）。 */
    private fun toggleTouchpad() {
        touchpadVisible = !touchpadVisible
        touchpadContainer.visibility = if (touchpadVisible) View.VISIBLE else View.GONE
        // 按钮高亮表示触摸板当前可见。
        touchpadToggleBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (touchpadVisible) cPrimary else cOnSurfaceVariant
        )
    }

    /** 停靠时窗口让出侧栏宽度 + 分割线（悬浮/全屏时窗口铺满）。 */
    private fun applyWindowInsetsState() {
        val lp = contentHost.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = 0
        // 只让出侧栏宽度 + 1px 分割线。旧代码多减了 insetRight（右侧刘海/cutout 宽度），
        // 导致横屏前摄在右时窗口右缘到屏幕边之间空出一条 = 黑边。侧栏用 gravity=END
        // 贴真右缘盖住刘海区，窗口内容铺到侧栏左边即可，不再额外避让 cutout。
        lp.rightMargin =
            if (railMode == RailMode.DOCKED) dp(RAIL_WIDTH_DP) + dp(1) else 0
        contentHost.layoutParams = lp
    }

    private fun rootWidth(): Int =
        if (root.width > 0) root.width else resources.displayMetrics.widthPixels

    private fun rootHeight(): Int =
        if (root.height > 0) root.height else resources.displayMetrics.heightPixels

    /** 按当前模式摆放侧栏：停靠贴右撑满全高（无边距）；悬浮是宽矮浮窗（可拖）。 */
    private fun applyRailLayout() {
        if (!::rail.isInitialized) return
        val railW = dp(if (railMode == RailMode.FLOATING) RAIL_FLOAT_WIDTH_DP else RAIL_WIDTH_DP)
        val lp = rail.layoutParams as FrameLayout.LayoutParams
        lp.width = railW
        if (railMode == RailMode.FLOATING) {
            // 悬浮小窗：宽度大、高度自适应（矮），圆角浮层
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            // 悬浮靠绝对坐标定位，需要最新的 root 尺寸
            val maxX = (rootWidth() - railW).coerceAtLeast(0)
            if (railFloatX < 0) railFloatX = maxX
            lp.leftMargin = railFloatX.coerceIn(0, maxX)
            lp.topMargin = railFloatY.coerceIn(0, (rootHeight() - (if (rail.height > 0) rail.height else dp(240))).coerceAtLeast(0))
            lp.bottomMargin = 0
            lp.gravity = Gravity.TOP or Gravity.START
        } else {
            // 停靠：用 gravity=END 贴右、MATCH_PARENT 高度撑满。
            // 不再手算 leftMargin=rootWidth()-railW-insetRight：从小窗返回时 root.width
            // 还是旧值，手算的 margin 会把侧栏放错（表现为居中）。gravity 贴右不受
            // root 宽度时序影响，父容器一变就自动贴右。
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.bottomMargin = 0
        }
        rail.layoutParams = lp
        rail.bringToFront()
        // 分割线：只停靠时显示，贴在侧栏左侧。与侧栏同宽同 gravity，左移 1px。
        if (::railDivider.isInitialized) {
            val dlp = railDivider.layoutParams as FrameLayout.LayoutParams
            if (railMode == RailMode.DOCKED) {
                railDivider.visibility = View.VISIBLE
                dlp.width = dp(1)
                dlp.height = FrameLayout.LayoutParams.MATCH_PARENT
                dlp.gravity = Gravity.END
                dlp.leftMargin = railW
                dlp.topMargin = 0
                dlp.bottomMargin = 0
                railDivider.layoutParams = dlp
            } else {
                railDivider.visibility = View.GONE
            }
        }
        if (::cursorView.isInitialized) cursorView.bringToFront()
        floatBtn.bringToFront()
    }

    /** 悬浮侧栏拖动：只改 LayoutParams，不重建布局。 */
    private fun dragRail(e: MotionEvent): Boolean {
        val lp = rail.layoutParams as FrameLayout.LayoutParams
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                railDragStartX = e.rawX
                railDragStartY = e.rawY
                railDragStartLeft = lp.leftMargin
                railDragStartTop = lp.topMargin
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxX = (rootWidth() - lp.width).coerceAtLeast(0)
                val railH = if (rail.height > 0) rail.height else dp(240)
                val maxY = (rootHeight() - railH).coerceAtLeast(0)
                lp.leftMargin = (railDragStartLeft + (e.rawX - railDragStartX).toInt())
                    .coerceIn(0, maxX)
                lp.topMargin = (railDragStartTop + (e.rawY - railDragStartY).toInt())
                    .coerceIn(0, maxY)
                rail.layoutParams = lp
                railFloatX = lp.leftMargin
                railFloatY = lp.topMargin
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    /** 侧栏底色：停靠 = 不透明纯色（与窗口融合）；悬浮 = 半透明圆角浮窗。 */
    private fun setRailBackground(floating: Boolean) {
        if (floating) {
            rail.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(cRailBg, 0xE6))
                setStroke(
                    dp(1),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(cOutlineVariant, 0x99)
                )
            }
            // 悬浮窗浮在窗口画面上，需要阴影才看得出是浮层
            rail.elevation = dp(10).toFloat()
        } else {
            // 停靠：无圆角无边框，靠 railDivider 这条细线与窗口分界
            rail.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cRailBg)
            }
            rail.elevation = 0f
        }
    }

    // ------------------------------------------------------------------
    // 内置桌面（应用启动器）
    // ------------------------------------------------------------------

    /**
     * 内置桌面：应用图标网格。
     *
     * 已安装的应用点一下直接在合成器里新开标签；未安装的点一下触发容器内的
     * 安装脚本，装好后自动启动。这样用户不用切到终端手敲 apt。
     */
    private fun buildDesktopPanel(): FrameLayout {
        val panel = FrameLayout(this).apply {
            setBackgroundColor(cSurface)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(24))
        }

        val title = TextView(this).apply {
            text = "首页"
            textSize = 18f
            setTextColor(cOnSurface)
            setPadding(0, 0, 0, dp(2))
        }
        column.addView(title)

        desktopStatus = TextView(this).apply {
            text = "点图标启动；未安装的会自动下载安装"
            textSize = 12f
            setTextColor(cOnSurfaceVariant)
            setPadding(0, 0, 0, dp(16))
        }
        column.addView(desktopStatus)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        desktopGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(desktopGrid, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        desktopScroll = scroll

        // 窗口宽度变化（缩成小窗/旋转/侧栏停靠切换）时按新宽度重新换行。
        // 旧实现按屏幕宽度一次性算好每行个数，小窗下会溢出/显示不全。
        // 只在宽度真变时重建，避免重建改变高度又触发 onLayoutChange 造成死循环。
        var lastGridWidth = -1
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            if (w > 0 && w != lastGridWidth && ::desktopGrid.isInitialized) {
                lastGridWidth = w
                refreshDesktop()
            }
        }

        panel.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        refreshDesktop()
        // 冷启动就扫描已安装的 .desktop，避免「必须点一下首页才刷新出更多图标」。
        // loadDiscoveredApps 内部后台跑、完成后回主线程 refreshDesktop；此时
        // desktopScroll.width 可能还是 0，布局完成后的 onLayoutChange 会再排一次网格。
        loadDiscoveredApps()
        return panel
    }

    /** 重建图标网格（安装状态会变，所以每次显示都重建）。按可用宽度自适应每行个数并填满整行。 */
    private fun refreshDesktop() {
        if (!::desktopGrid.isInitialized) return
        desktopGrid.removeAllViews()
        // 按网格容器「实际渲染宽度」算每行个数（固定理想宽度），再把整行宽度均分给每格。
        // 用容器实测宽度而非屏幕宽度：缩成小窗/侧栏停靠时仍能正确换行、不溢出。
        // 容器尚未布局时（width=0）跳过——布局完成后的 onLayoutChange 会再触发一次。
        val scrollWidth = if (::desktopScroll.isInitialized) desktopScroll.width else 0
        if (scrollWidth <= 0) return
        val ideal = (84 * resources.displayMetrics.density).toInt()
        val available = scrollWidth
        val perRow = (available / ideal).coerceAtLeast(2)
        val cellWidth = available / perRow

        // 行填充器：curated + 发现的已安装程序共用同一套自适应换行逻辑。
        var row: LinearLayout? = null
        var idx = 0
        fun nextCell(v: View) {
            if (idx % perRow == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                desktopGrid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            row!!.addView(v, LinearLayout.LayoutParams(cellWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
            idx++
        }
        DesktopApps.apps.forEach { app -> nextCell(desktopCell(app, cellWidth)) }
        // 发现的已安装 Linux 程序：附加在 curated 列表后面，让用户看到容器里实际装了什么。
        discoveredApps.forEach { d -> nextCell(discoveredCell(d, cellWidth)) }
    }

    /**
     * 后台扫描 rootfs 的 .desktop，更新 [discoveredApps] 并刷新网格。
     * 文件 IO，主线程外跑；完成后回主线程 refreshDesktop。已在扫描中则跳过。
     */
    private fun loadDiscoveredApps() {
        if (discovering) return
        discovering = true
        Thread {
            val found = try {
                DesktopApps.discoverInstalled(this)
            } catch (e: Throwable) {
                FileLog.e(this, TAG, "discover failed: ${e.message}", e)
                emptyList()
            }
            runOnUiThread {
                discovering = false
                discoveredApps = found
                if (desktopVisible) refreshDesktop()
            }
        }.start()
    }

    /** 鼠标悬浮反馈：PC 式光标停在图标上时高亮描边+背景，移开复原。 */
    /** 桌面格子的高亮状态（存到 cell.tag，供光标位置驱动选中/复原）。 */
    private class CellHl(
        val iconBg: android.graphics.drawable.GradientDrawable,
        val base: Int,
    ) {
        var highlighted = false
        fun set(on: Boolean, primary: Int, outline: Int, strokeOn: Int, strokeOff: Int) {
            if (on == highlighted) return
            highlighted = on
            if (on) {
                iconBg.setStroke(strokeOn, primary)
                iconBg.setColor(androidx.core.graphics.ColorUtils.blendARGB(base, primary, 0.18f))
            } else {
                iconBg.setStroke(strokeOff, outline)
                iconBg.setColor(base)
            }
        }
    }

    /**
     * 给桌面格子注册高亮信息。注意：app 用「虚拟光标 + 触摸板」交互，手指滑动是
     * ACTION_MOVE，不会派发 ACTION_HOVER_*，所以不能靠 setOnHoverListener；
     * 选中态由 updateDesktopHover() 根据光标坐标主动驱动。
     */
    private fun registerDesktopCell(cell: View, iconBg: android.graphics.drawable.GradientDrawable, base: Int) {
        cell.tag = CellHl(iconBg, base)
    }

    /** 点击即时缩放反馈：应用冷启动慢，先给视觉响应避免「点了没反应」的延迟感。 */
    private fun pressFeedback(v: View) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(90).start() }
            .start()
    }

    /** 当前被光标选中的桌面格子。 */
    private var hoveredCell: View? = null

    /** 光标移动后按坐标更新「选中」高亮：只高亮命中格子，其余复原。 */
    private fun updateDesktopHover() {
        if (!desktopVisible) {
            clearDesktopHover()
            return
        }
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val x = pointerScreenX - loc[0]
        val y = pointerScreenY - loc[1]
        val hit = findClickableAt(root, x, y)
        // 只有带 CellHl 标记的才是桌面图标（侧栏按钮等也 clickable，要排除）
        val target = if (hit?.tag is CellHl) hit else null
        if (target === hoveredCell) return
        clearDesktopHover()
        target?.let {
            (it.tag as CellHl).set(true, cPrimary, cOutlineVariant, dp(2), dp(1))
            it.invalidate()
            hoveredCell = it
        }
    }

    private fun clearDesktopHover() {
        hoveredCell?.let {
            (it.tag as? CellHl)?.set(false, cPrimary, cOutlineVariant, dp(2), dp(1))
            it.invalidate()
        }
        hoveredCell = null
    }

    private fun desktopCell(app: DesktopApp, width: Int): View {
        val installed = DesktopApps.isInstalled(this, app)
        val busy = app.id in installing
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
        }

        val iconBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (installed) cSurfaceContainer else cSurfaceVariant)
            setStroke(dp(1), cOutlineVariant)
        }
        val iconBox = FrameLayout(this).apply {
            background = iconBg
            alpha = if (installed) 1f else 0.5f
        }
        val icon = android.widget.ImageView(this).apply {
            setImageResource(app.iconRes)
            setColorFilter(cPrimary)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        iconBox.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        cell.addView(iconBox, LinearLayout.LayoutParams(dp(56), dp(56)))

        val name = TextView(this).apply {
            text = app.name
            textSize = 12f
            setTextColor(cOnSurface)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
        }
        cell.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val hint = TextView(this).apply {
            text = when {
                busy -> "安装中…"
                installed -> ""
                else -> "未安装"
            }
            textSize = 10f
            setTextColor(if (installed) cOnSurfaceVariant else cPrimary)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        cell.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        registerDesktopCell(cell, iconBg, if (installed) cSurfaceContainer else cSurfaceVariant)
        cell.setOnClickListener { pressFeedback(cell); onDesktopAppClick(app) }
        return cell
    }

    /**
     * 发现的已安装 Linux 程序格子：无内置图标/安装脚本，点一下直接在合成器里启动。
     * 图标用首字母占位（真实主题图标在容器里，加载成本高，后续可换成图标缓存）。
     */
    private fun discoveredCell(d: DiscoveredApp, width: Int): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
        }
        val iconBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(cSurfaceContainer)
            setStroke(dp(1), cOutlineVariant)
        }
        val letter = TextView(this).apply {
            text = d.name.trim().take(1).uppercase().ifEmpty { "?" }
            textSize = 22f
            setTextColor(cPrimary)
            gravity = Gravity.CENTER
        }
        val iconBox = FrameLayout(this).apply {
            background = iconBg
            addView(letter, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        cell.addView(iconBox, LinearLayout.LayoutParams(dp(56), dp(56)))
        val name = TextView(this).apply {
            text = d.name
            textSize = 12f
            setTextColor(cOnSurface)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
        }
        cell.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val hint = TextView(this).apply {
            text = "已安装"
            textSize = 10f
            setTextColor(cOnSurfaceVariant)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        cell.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        registerDesktopCell(cell, iconBg, cSurfaceContainer)
        cell.setOnClickListener { pressFeedback(cell); launchDiscovered(d) }
        return cell
    }

    /** 启动一个自动发现的 Linux 程序（直接用它的 Exec 命令行，带合成器环境前缀）。 */
    private fun launchDiscovered(d: DiscoveredApp) {
        showDesktop(false)
        toast("启动 ${d.name}…")
        val extra = DesktopApps.containerArgsFor(d.exec)
        val cmd = (d.exec + if (extra.isNotEmpty()) " $extra" else "").trim()
        DesktopApps.runScript(
            this,
            "GuiLaunch",
            DesktopApps.ENV_PREFIX + cmd
        )
        FileLog.i(this, TAG, "discover launch ${d.name}: $cmd")
    }


    private fun onDesktopAppClick(app: DesktopApp) {
        if (app.id in installing) {
            toast("${app.name} 正在安装，请稍候…")
            return
        }
        if (DesktopApps.isInstalled(this, app)) {
            launchAppInTab(app)
            return
        }
        if (app.install.isEmpty()) {
            toast("${app.name} 暂无自动安装方式")
            return
        }
        installing.add(app.id)
        refreshDesktop()
        toast("开始安装 ${app.name}…")
        DesktopApps.runScript(this, "Install", app.install)
        pollInstallDone(app, System.currentTimeMillis())
    }

    /** 轮询二进制是否出现；装好后自动启动，超时则提示。 */
    private fun pollInstallDone(app: DesktopApp, startAt: Long) {
        root.postDelayed({
            if (isFinishing || isDestroyed) {
                installing.remove(app.id)
                return@postDelayed
            }
            if (DesktopApps.isInstalled(this, app)) {
                installing.remove(app.id)
                refreshDesktop()
                toast("${app.name} 安装完成")
                launchAppInTab(app)
                return@postDelayed
            }
            if (System.currentTimeMillis() - startAt > 10 * 60_000) {
                installing.remove(app.id)
                refreshDesktop()
                toast("${app.name} 安装超时，请看终端输出")
                return@postDelayed
            }
            pollInstallDone(app, startAt)
        }, 1500)
    }

    /** 在合成器里新开一个标签渲染该应用。 */
    private fun launchAppInTab(app: DesktopApp) {
        showDesktop(false)
        toast("启动 ${app.name}…")
        // 直接起容器进程（不走终端会话）：输出进 codetool.log，失败原因可查。
        // spark-store 的安装按钮依赖 D-Bus 后端（ssinstall）+ sudo（ssinstall 用
        // sudo -u 弹 zenity/复制桌面文件），启动前拉起总线 + 装 sudo/bwrap/
        // ssinstall 透传 shim；其它应用不需要，跳过以免拖慢启动。
        val prefix = if (app.id == "spark-store") {
            DesktopApps.ENV_PREFIX + DesktopApps.SUDO_SETUP + DesktopApps.BWRAP_SETUP +
                DesktopApps.DBUS_SETUP
        } else {
            DesktopApps.ENV_PREFIX
        }
        DesktopApps.runScript(
            this,
            "GuiLaunch",
            prefix + DesktopApps.launchLine(app)
        )
        FileLog.i(this, TAG, "launch ${app.name}: ${DesktopApps.launchLine(app)}")
    }


    /**
     * 带 URL 启动内置 Chrome，供容器内 `codetool-browser` 桥调用。
     *
     * 同时开标准 Chrome DevTools Protocol 端口（127.0.0.1:9222）：容器与 app 共享
     * loopback（tawcroot 不隔离 netns），模型可用 curl / 任意 CDP 客户端做
     * 页面列表、执行 JS、截图、读 console 等调试，不需要外部浏览器。
     * Chrome 136+ 要求 --user-data-dir 非默认才允许远程调试，必须显式指定。
     *
     * @return false = 浏览器未安装（让桥如实回报给模型）。
     */
    private fun launchChromeWithUrl(url: String): Boolean {
        val app = DesktopApps.apps.firstOrNull { it.id == "chrome" } ?: return false
        if (!DesktopApps.isInstalled(this, app)) return false
        showDesktop(false)
        // 单引号包裹 + 转义内部单引号，避免 URL 里的 shell 元字符造成注入
        val quoted = "'" + url.replace("'", "'\\''") + "'"
        // --no-first-run 关键：Chrome 首次运行会弹「服务条款」向导并阻塞 CDP 初始化，
        // 实测 9222 因此没起来；跳过首启向导后 DevTools 正常监听。
        val cdpArgs = "--no-first-run --no-default-browser-check " +
            "--remote-debugging-port=9222 --remote-allow-origins='*' " +
            "--user-data-dir=/root/.config/codetool-chrome"
        val cmd = DesktopApps.ENV_PREFIX + DesktopApps.launchLine(app) + " " + cdpArgs + " " + quoted
        DesktopApps.runScript(this, "GuiLaunch", cmd)
        FileLog.i(this, TAG, "browser open: $url")
        return true
    }

    /** 显示/隐藏内置桌面。显示时窗口仍在后台渲染，不打断应用。 */
    private fun showDesktop(show: Boolean) {
        desktopVisible = show
        if (!::desktopPanel.isInitialized) return
        if (show) {
            hoveredCell = null       // refreshDesktop() 重建格子，旧引用失效
            refreshDesktop()
            loadDiscoveredApps()   // 后台扫描已安装的 Linux 程序，装了什么一目了然
            desktopPanel.visibility = View.VISIBLE
            desktopPanel.bringToFront()
            placeholder.visibility = View.GONE
            showCursorAtCenter() // PC 式：光标一直显示，回桌面时摆到中央
            updateDesktopHover() // 中央位置若压在图标上，立即给选中态
            // 回内置桌面：释放指针捕获，让首页图标能收到系统指针的 hover/单击
            releasePointerCapture()
        } else {
            clearDesktopHover()
            desktopPanel.visibility = View.GONE
            if (tabs.isEmpty()) placeholder.visibility = View.VISIBLE
            else updatePointerCapture()   // 进 Wayland 窗口：请求捕获，鼠标碰边不再跳变
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 侧栏/桌面用的图标按钮：矢量图标 + 圆角点击区，跟随主题。 */
    private fun railIconButton(
        iconRes: Int,
        tint: Int,
        onClick: () -> Unit,
    ): android.widget.ImageView = android.widget.ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { onClick() }
    }

    private fun keyButton(def: KeyDef): TextView =
        TextView(this).apply {
            text = def.label
            textSize = 11f
            setTextColor(cOnSurface)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(cSurfaceVariant)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
            ).apply { marginEnd = dp(5) }
            if (def.toggle) modifierButtons[def.keycode] = this
            setOnClickListener {
                val host = selectedId ?: return@setOnClickListener
                if (def.toggle) {
                    val now = !(modifierState[def.keycode] ?: false)
                    modifierState[def.keycode] = now
                    setModifierVisual(this, def.keycode, now)
                    FileLog.i(this@TawcCompositorActivity, "CtrlCombo", "toggle ${def.label} keycode=${def.keycode} -> $now host=$host")
                    com.codetool.app.util.CtrlComboLog.i(this@TawcCompositorActivity, "toggle ${def.label} keycode=${def.keycode} -> $now host=$host")
                    runCatching {
                        NativeBridge.nativeOnHardwareKeyEvent(host, def.keycode, now, 0)
                    }
                } else {
                    runCatching {
                        NativeBridge.nativeOnHardwareKeyEvent(host, def.keycode, true, 0)
                        NativeBridge.nativeOnHardwareKeyEvent(host, def.keycode, false, 0)
                    }
                }
            }
        }

    /** 复位/设置某个修饰键按钮的高亮（一次性消耗 Ctrl+字母后调用）。 */
    private fun setModifierVisual(btn: TextView, keycode: Int, on: Boolean) {
        (btn.background as? android.graphics.drawable.GradientDrawable)?.setColor(
            if (on) cPrimary else cSurfaceVariant
        )
        btn.setTextColor(if (on) cOnPrimary else cOnSurface)
    }

    /**
     * 输入法提交单字符时的拦截：若快捷键条锁存了 CTRL/ALT，把该字符改走按键路径
     * （emitModifiedKey），让终端收到真正的 Ctrl+C / Alt+X；否则放行走普通文本。
     * 一次性消耗：发完即复位修饰键高亮，后续字母恢复正常输入。
     */
    private fun consumeCommittedChar(ch: Char): Boolean {
        val host = selectedId
        com.codetool.app.util.CtrlComboLog.i(this, "consumeCommittedChar ch='$ch' host=$host ctrl=${modifierState[KeyEvent.KEYCODE_CTRL_LEFT]} alt=${modifierState[KeyEvent.KEYCODE_ALT_LEFT]}")
        if (host == null) { com.codetool.app.util.CtrlComboLog.i(this, "  -> 无 host，放行文本"); return false }
        val ctrl = modifierState[KeyEvent.KEYCODE_CTRL_LEFT] == true
        val alt = modifierState[KeyEvent.KEYCODE_ALT_LEFT] == true
        if (!ctrl && !alt) { com.codetool.app.util.CtrlComboLog.i(this, "  -> CTRL/ALT 未锁存，放行文本"); return false }
        val keycode = charToKeyCode(ch)
        if (keycode == null) { com.codetool.app.util.CtrlComboLog.i(this, "  -> 字符 '$ch' 无法映射 keycode，放行文本"); return false }
        com.codetool.app.util.CtrlComboLog.i(this, "  -> 发送 keycode=$keycode ctrl=$ctrl alt=$alt（硬件按键路径）")
        runCatching {
            NativeBridge.nativeOnHardwareKeyEvent(host, keycode, true, 0)
            NativeBridge.nativeOnHardwareKeyEvent(host, keycode, false, 0)
            if (alt) NativeBridge.nativeOnHardwareKeyEvent(host, KeyEvent.KEYCODE_ALT_LEFT, false, 0)
            if (ctrl) NativeBridge.nativeOnHardwareKeyEvent(host, KeyEvent.KEYCODE_CTRL_LEFT, false, 0)
        }.onFailure { com.codetool.app.util.CtrlComboLog.w(this, "  -> 发送异常：${it.message}") }
        modifierState[KeyEvent.KEYCODE_CTRL_LEFT] = false
        modifierState[KeyEvent.KEYCODE_ALT_LEFT] = false
        modifierButtons[KeyEvent.KEYCODE_CTRL_LEFT]?.let {
            setModifierVisual(it, KeyEvent.KEYCODE_CTRL_LEFT, false)
        }
        modifierButtons[KeyEvent.KEYCODE_ALT_LEFT]?.let {
            setModifierVisual(it, KeyEvent.KEYCODE_ALT_LEFT, false)
        }
        com.codetool.app.util.CtrlComboLog.i(this, "  -> 已发送并复位锁存")
        return true
    }

    /** 单字符 → Android keycode（字母/数字/空格），供 Ctrl/Alt 组合键用。 */
    private fun charToKeyCode(ch: Char): Int? = when (ch) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        ' ' -> KeyEvent.KEYCODE_SPACE
        else -> null
    }

    // ------------------------------------------------------------------
    // 全屏 / 快捷键条
    // ------------------------------------------------------------------

    /**
     * 全屏切换（用户入口）：全屏 = 收起侧栏 + 隐藏系统栏 + 窗口铺满。
     * 真正的窗口/系统栏设置都在 [applyFullscreenState]。
     */
    private fun setFullscreen(on: Boolean) {
        setRailMode(if (on) RailMode.HIDDEN else RailMode.DOCKED)
    }

    private fun applyFullscreenState(on: Boolean) {
        fullscreenBtn.setImageResource(
            if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 沉浸式桌面：状态栏永远隐藏；导航栏也任何模式下都隐藏
        // （否则非全屏停靠时底部会有一条白色手势小白条，破坏融合感）。
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        // IME 弹出时系统会强制显示导航栏；不设背景色它就是默认白底，
        // 在键盘下方留一条白条。设成透明并关掉对比度强制（否则系统会根据
        // 导航栏内容自动加白底/暗底/分割线），底栏即可沉浸跟随键盘背景。
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // 导航栏强制深色外观：合成器是始终黑底的沉浸界面，Android 15+ 已忽略
        // navigationBarColor，浅色主题下若设成 lightBars=true，系统会给导航栏加
        // 白色 scrim，IME 弹出时底部就出现小白条。强制 false（深色）让底栏随
        // 键盘深色沉浸，无白条。状态栏本就隐藏，保留主题相关即可。
        val mode = com.codetool.app.theme.ThemeManager.currentMode(this)
        val dark = mode == com.codetool.app.theme.ThemeManager.Mode.DARK ||
            (mode == com.codetool.app.theme.ThemeManager.Mode.SYSTEM &&
                com.codetool.app.theme.ThemeManager.isSystemDark(this))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = false
        }
        // 全屏铺到刘海区；停靠时也铺满（顶部本来就不留边）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun toggleShortcutBar() {
        shortcutVisible = !shortcutVisible
        shortcutBar.visibility = if (shortcutVisible) View.VISIBLE else View.GONE
        keyboardBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (shortcutVisible) cPrimary else cOnSurfaceVariant
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenState(railMode != RailMode.DOCKED)
        if (compositorStarted) {
            selectedId?.let { id ->
                runCatching { NativeBridge.nativeOnActivityFocusChanged(id, hasFocus) }
            }
        }
        // 焦点变化时刷新指针捕获：窗口刚拿到焦点才能成功请求 capture；失焦时
        // updatePointerCapture 会因 hasWindowFocus=false 自动释放。
        if (hasFocus) updatePointerCapture() else releasePointerCapture()
    }

    // ------------------------------------------------------------------
    // 软键盘（Android 输入法 → 容器客户端）
    // ------------------------------------------------------------------

    /**
     * 支持软键盘的 SurfaceView。
     *
     * Android 只会把输入法挂到「是文本编辑器」的 View 上，所以这里声明
     * `onCheckIsTextEditor = true` 并交出 [me.phie.tawc.compositor.TawcInputConnection]。
     * 输入法打的字经 JNI 交给合成器的 zwp_text_input_v3，最终进入容器里的
     * 客户端（终端、GTK 输入框都支持）。
     *
     * 顺便承担另一个职责：**系统鼠标光标**。以前是自己画一个箭头 View，
     * 但自绘光标大小跟系统设置无关、刷新慢（移动时容易"闪没"），而且不会
     * 随形状变化。现在改成把客户端请求的光标形状映射成 Android 的
     * PointerIcon —— 由系统绘制，永远在所有图层之上，大小跟随系统设置。
     */
    private inner class TawcSurfaceView(val hostId: String) :
        SurfaceView(this@TawcCompositorActivity) {

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo):
            android.view.inputmethod.InputConnection {
            val cached = imeContentTypes[hostId]
            outAttrs.inputType = cached?.first
                ?: (android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or
                android.view.inputmethod.EditorInfo.IME_ACTION_NONE
            val ic = me.phie.tawc.compositor.TawcInputConnection(this)
            // 快捷键条锁存 CTRL/ALT 时，输入法字母改走按键路径（Ctrl+C 等）
            ic.onCommitCharacter = { ch -> consumeCommittedChar(ch) }
            return ic
        }
    }

    /** 每个宿主最近一次报告的内容类型（终端/密码框/数字输入等）。 */
    private val imeContentTypes = HashMap<String, Pair<Int, Int>>()

    fun setPointerIconFromCompositor(activityId: String, icon: android.view.PointerIcon) {
        tabs[activityId]?.surfaceView?.pointerIcon = icon
    }

    fun updateEditableTextFromCompositor(
        activityId: String,
        ic: me.phie.tawc.compositor.TawcInputConnection,
        text: String,
        selStart: Int,
        selEnd: Int,
        authoritative: Boolean,
    ) {
        val tab = tabs[activityId] ?: return
        if (ic.targetsView(tab.surfaceView)) {
            ic.updateFromCompositor(text, selStart, selEnd, authoritative)
        }
    }

    fun showKeyboardFor(activityId: String) {
        val tab = tabs[activityId] ?: return
        val sv = tab.surfaceView
        // 触摸模式下只有 requestFocusFromTouch 才抢得到焦点；
        // 否则系统把 DecorView 当编辑器，showSoftInput 不会弹键盘。
        sv.requestFocusFromTouch()
        sv.requestFocus()
        sv.post {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(sv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            FileLog.i(
                this, TAG,
                "showKeyboard host=$activityId focus=${sv.isFocused} hasWindow=${sv.hasWindowFocus()}"
            )
        }
    }

    fun hideKeyboardFor(activityId: String) {
        val tab = tabs[activityId] ?: return
        NativeBridge.imeOutput.hideSoftInput(tab.surfaceView)
    }

    fun onImeContentTypeChanged(activityId: String, inputType: Int, imeFlags: Int) {
        val tab = tabs[activityId] ?: return
        imeContentTypes[activityId] = inputType to imeFlags
        NativeBridge.imeOutput.restartInput(tab.surfaceView)
    }

    // ------------------------------------------------------------------
    // 标签管理（原生回调入口，主线程）
    // ------------------------------------------------------------------

    fun onWindowSpawned(id: String) {
        if (tabs.containsKey(id)) return
        tabSeq += 1
        val title = "窗口 $tabSeq"
        val sv = TawcSurfaceView(id).apply {
            // 不要给 SurfaceView 设背景：背景会画在窗口图层里盖住 surface
            // （表现为整块纯黑）。黑底由外层 root 提供即可。
            holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { _, e -> dispatchTouchToCompositor(e) }
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!compositorStarted) return
                val frame = holder.surfaceFrame
                runCatching {
                    NativeBridge.nativeRegisterActivitySurface(
                        id, holder.surface, frame.width(), frame.height()
                    )
                    FileLog.i(this@TawcCompositorActivity, TAG, "surface registered $id ${frame.width()}x${frame.height()}")
                }.onFailure {
                    FileLog.e(this@TawcCompositorActivity, TAG, "register surface $id failed: ${it.message}", it)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!compositorStarted) return
                runCatching { NativeBridge.nativeOnActivitySurfaceChanged(id, width, height) }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                runCatching { NativeBridge.nativeOnActivitySurfaceDestroyed(id) }
            }
        })
        surfaceLayer.addView(
            sv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val chip = tabChip(title, id)
        val chipLabel = chip.getChildAt(0) as TextView
        chip.setOnClickListener { selectTab(id) }
        chip.setOnLongClickListener {
            // 长按标签关闭该窗口（✕ 是主入口）
            onWindowFinished(id)
            true
        }
        tabRow.addView(chip)
        tabs[id] = TabEntry(id, title, sv, chip, chipLabel)
        placeholder.visibility = View.GONE
        selectTab(id)
    }

    /** 合成器重启后重新登记已存在的标签 surface（否则旧标签 bound=false → 黑屏）。 */
    fun onCompositorRestarted() {
        compositorStarted = true
        for ((id, tab) in tabs) {
            val holder = tab.surfaceView.holder
            val surface = holder.surface ?: continue
            if (!surface.isValid) continue
            runCatching {
                NativeBridge.nativeRegisterActivitySurface(
                    id, surface, tab.surfaceView.width, tab.surfaceView.height
                )
            }
        }
        selectedId?.let { runCatching { NativeBridge.nativeOnActivityFocusChanged(it, true) } }
        // 重启后的新合成器实例 seat 是空的，重新授予 wl_pointer 能力，
        // 否则重启后软触摸板又会失效（同 startCompositorIfNeeded）。
        runCatching { NativeBridge.nativeOnMouseAttachedChanged(true) }
    }

    fun onWindowTitle(id: String, title: String) {
        val tab = tabs[id] ?: return
        val t = title.trim().ifEmpty { tab.title }
        tab.title = t
        tab.chipLabel.text = t
    }

    fun onWindowFinished(id: String) {
        val tab = tabs.remove(id) ?: return
        runCatching { NativeBridge.nativeOnActivitySurfaceDestroyed(id) }
        runCatching { NativeBridge.nativeOnActivityDestroyed(id) }
        runCatching { NativeBridge.unregisterHost(id) }
        tabRow.removeView(tab.chip)
        surfaceLayer.removeView(tab.surfaceView)
        if (selectedId == id) {
            selectedId = null
            val next = tabs.keys.lastOrNull()
            if (next != null) selectTab(next)
        }
        if (tabs.isEmpty()) {
            // 没有窗口了：回到内置桌面（比一句「等待 Linux 窗口…」更有用）
            showDesktop(true)
        }
    }

    private fun selectTab(id: String) {
        val tab = tabs[id] ?: return
        showDesktop(false)
        showCursorAtCenter()
        val prev = selectedId
        if (prev == id) {
            tab.surfaceView.bringToFront()
            return
        }
        selectedId = id
        for ((tid, t) in tabs) {
            val on = tid == id
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (on) cPrimaryContainer else Color.TRANSPARENT)
            }
            t.chip.background = bg
            t.chipLabel.setTextColor(if (on) cOnPrimaryContainer else cOnSurfaceVariant)
            // SurfaceView 的 surface 是独立窗口图层，bringToFront() 只改 View 顺序，
            // 管不到图层叠加顺序；必须显式隐藏非当前标签的 surface，否则永远
            // 看到"最后一个创建"的那个窗口。
            t.surfaceView.visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        tab.surfaceView.bringToFront()
        if (compositorStarted) {
            // 先让新宿主成为前台（合成器据此渲染），再让旧宿主失焦。
            runCatching { NativeBridge.nativeOnActivityFocusChanged(id, true) }
            prev?.let { runCatching { NativeBridge.nativeOnActivityFocusChanged(it, false) } }
        }
        // 切到新窗口：请求指针捕获（有焦点才成功，焦点没到时 updatePointerCapture
        // 会自动跳过，等 onWindowFocusChanged 再补）。
        updatePointerCapture()
    }

    private fun tabChip(title: String, id: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            ).apply { bottomMargin = dp(2) }
        }
        val label = TextView(this).apply {
            text = title
            textSize = 12.5f
            setTextColor(cOnSurfaceVariant)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val close = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(cOnSurfaceVariant)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { onWindowFinished(id) }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.MATCH_PARENT))
        row.setBackgroundColor(Color.TRANSPARENT)
        return row
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 触摸板逻辑：整块屏幕当 PC 触摸板用，光标一直显示。
     *  - 单指滑动：移动光标（相对位移）
     *  - 单指轻点：左键单击（快速连点两次自然就是双击）
     *  - 双击后按住不放：左键拖拽
     *  - 长按（>500ms 未移动）：右键单击
     *  - 双指滑动：滚动（垂直/水平）
     * 点击都发生在「光标当前位置」，而不是手指位置 —— 跟 PC 触摸板一致。
     */
    private fun dispatchTouchToCompositor(event: MotionEvent): Boolean {
        val host = if (desktopVisible) null else selectedId
        FileLog.i(this, TAG, "dispatch action=${event.actionMasked} host=$host desktopVisible=$desktopVisible cur=(${pointerScreenX.toInt()},${pointerScreenY.toInt()})")
        fun pos(): PointerPos {
            val loc = IntArray(2)
            surfaceLayer.getLocationOnScreen(loc)
            // 钳位到 surface 范围：手指滑到左/上边缘时 pointerScreenX/Y 可能小于
            // surface 原点 loc[0]/loc[1]，算出负的 surfaceX/Y。负 float 传给 Rust
            // compositor 转无符号会回绕到 0/原点（即"左/上越界回原点"）；而右/下
            // 正向溢出会被钳到宽/高（不回绕）。这里统一钳到 [0,尺寸-1]，四边都停在边缘。
            val w = surfaceLayer.width.coerceAtLeast(1)
            val h = surfaceLayer.height.coerceAtLeast(1)
            val sx = (pointerScreenX - loc[0]).coerceIn(0f, (w - 1).toFloat())
            val sy = (pointerScreenY - loc[1]).coerceIn(0f, (h - 1).toFloat())
            return PointerPos(sx, sy)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 触摸板语义：按下不移动光标，只记录手指起点。
                // 光标停在原地，之后的滑动按 delta 推动光标（相对位移），
                // 点击发生在光标当前位置（不是手指位置），和 PC 触摸板一致。
                padLastX = event.x; padLastY = event.y
                padDownX = event.x; padDownY = event.y
                padDownTime = event.eventTime
                padMoved = false; padLong = false; padScroll = false
                padTwoFinger = false; padTwoFingerMoved = false
                if (event.eventTime - padLastTapUp < 300) {
                    // 双击的第二次按下：按住即拖拽（左键按下，松手释放）
                    padDrag = true
                    host?.let {
                        sendPointerMotionAt(it, pos(), event.eventTime)
                        sendPointerButtonAt(it, MotionEvent.BUTTON_PRIMARY, true, pos(), event.eventTime)
                    }
                } else {
                    padDrag = false
                    // 长按 500ms 未移动 → 右键单击（和双指轻点二选一，都保留）
                    padLongRunnable = Runnable {
                        padLongRunnable = null
                        if (!padMoved && !padDrag && !padTwoFinger) {
                            padLong = true
                            host?.let {
                                val now = android.os.SystemClock.uptimeMillis()
                                sendPointerMotionAt(it, pos(), now)
                                sendPointerButtonAt(it, MotionEvent.BUTTON_SECONDARY, true, pos(), now)
                                sendPointerButtonAt(it, MotionEvent.BUTTON_SECONDARY, false, pos(), now)
                            }
                        }
                    }
                    padHandler.postDelayed(padLongRunnable!!, 500)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    // 双指按下：进入滚动/右键判定模式（移动=滚动，轻点=右键）
                    padScroll = true
                    padTwoFinger = true
                    padTwoFingerMoved = false
                    padTwoFingerTime = event.eventTime
                    padMoved = true // 屏蔽单指轻点路径
                    // 以仍按着的手指为滚动基准，避免坐标跳变
                    val keep = if (event.actionIndex == 0) 1 else 0
                    padLastX = event.getX(keep); padLastY = event.getY(keep)
                }
                cancelPadLong()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (padScroll && event.pointerCount >= 2) {
                    // 双指移动：过阈值才算滚动（防止轻点时的轻微抖动误判）
                    val dx = event.getX(0) - padLastX
                    val dy = event.getY(0) - padLastY
                    padLastX = event.getX(0); padLastY = event.getY(0)
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 6f) padTwoFingerMoved = true
                    if (padTwoFingerMoved) {
                        host?.let {
                            val p = pos()
                            runCatching {
                                NativeBridge.nativeOnPointerEvent(
                                    it, POINTER_KIND_AXIS, p.surfaceX, p.surfaceY,
                                    0, false, -dy / 6f, dx / 6f, false, false,
                                    event.eventTime
                                )
                            }
                        }
                    }
                    return true
                }
                val dx = event.getX(0) - padLastX
                val dy = event.getY(0) - padLastY
                padLastX = event.getX(0); padLastY = event.getY(0)
                if (!padMoved &&
                    (kotlin.math.abs(event.getX(0) - padDownX) +
                        kotlin.math.abs(event.getY(0) - padDownY)) > 8f
                ) {
                    padMoved = true
                    cancelPadLong()
                }
                moveCursorBy(dx, dy)
                host?.let { sendPointerMotionAt(it, pos(), event.eventTime) }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指中一指抬起：基准换到剩下的手指，光标/滚动不跳变
                if (event.pointerCount > 1) {
                    val other = if (event.actionIndex == 0) 1 else 0
                    padLastX = event.getX(other)
                    padLastY = event.getY(other)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelPadLong()
                val dur = event.eventTime - padDownTime
                when {
                    padDrag -> {
                        // 双击按住拖拽：松手释放左键
                        padDrag = false
                        host?.let {
                            sendPointerMotionAt(it, pos(), event.eventTime)
                            sendPointerButtonAt(it, MotionEvent.BUTTON_PRIMARY, false, pos(), event.eventTime)
                        }
                    }
                    padTwoFinger && !padTwoFingerMoved &&
                        event.eventTime - padTwoFingerTime < 350 -> {
                        // 双指同按轻点 = 右键单击（Mac 触摸板风格）
                        host?.let {
                            sendPointerMotionAt(it, pos(), event.eventTime)
                            sendPointerButtonAt(it, MotionEvent.BUTTON_SECONDARY, true, pos(), event.eventTime)
                            sendPointerButtonAt(it, MotionEvent.BUTTON_SECONDARY, false, pos(), event.eventTime)
                        }
                    }
                    !padMoved && !padLong && !padTwoFinger && dur < 350 -> {
                        // 单指轻点 = 左键单击；两次轻点自然就是双击。
                        // 首页（无 host）时把点击交给光标下的 Android 桌面图标，
                        // 否则桌面图标永远收不到鼠标/触摸板点击（只有手指直接触摸能用）。
                        if (host == null) {
                            clickDesktopAtPointer()
                        } else {
                            // 先发 motion 更新 compositor 的 pointer_location，否则 button 落在旧位置
                            sendPointerMotionAt(host, pos(), event.eventTime)
                            sendPointerButtonAt(host, MotionEvent.BUTTON_PRIMARY, true, pos(), event.eventTime)
                            sendPointerButtonAt(host, MotionEvent.BUTTON_PRIMARY, false, pos(), event.eventTime)
                            logPointerState("tap")
                        }
                        padLastTapUp = event.eventTime
                    }
                }
                padScroll = false
                padTwoFinger = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPadLong()
                if (padDrag) {
                    padDrag = false
                    host?.let {
                        sendPointerButtonAt(it, MotionEvent.BUTTON_PRIMARY, false, pos(), event.eventTime)
                    }
                }
                padScroll = false
                padTwoFinger = false
                return true
            }
        }
        return true
    }

    /**
     * 相对移动光标（触摸板核心）：dx/dy 为手指增量，光标加位移，
     * 并夹在窗口区内。窗口区和桌面共用 contentHost 的范围。
     */
    private fun moveCursorBy(dx: Float, dy: Float) {
        if (pointerScreenX < 0f || pointerScreenY < 0f) {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            pointerScreenX = loc[0] + root.width / 2f
            pointerScreenY = loc[1] + root.height / 2f
        }
        pointerScreenX += dx
        pointerScreenY += dy
        // 钳位累积值到屏幕范围：否则往左/上滑很多次后 pointerScreenX/Y 漂得很负，
        // 再往反方向要滑很久才回来（死区）。钳住让它和显示光标同步停在边缘。
        pointerScreenX = pointerScreenX.coerceIn(0f, root.width.toFloat())
        pointerScreenY = pointerScreenY.coerceIn(0f, root.height.toFloat())
        moveCursor()
        // 虚拟光标移动 = 选中态变化（PC 式 hover）；手指滑动也走这里，
        // 不依赖系统 ACTION_HOVER_*（那种只有真鼠标才有）。
        updateDesktopHover()
    }

    /** 当前光标的 surface 相对坐标（给侧栏触摸板左/中/右键用）。 */
    private fun currentPointerPos(): PointerPos {
        val loc = IntArray(2)
        surfaceLayer.getLocationOnScreen(loc)
        return PointerPos(pointerScreenX - loc[0], pointerScreenY - loc[1])
    }

    /**
     * 首页（桌面可见）时，把「鼠标/触摸板点击」派发给光标位置下的 Android View。
     *
     * 桌面图标是普通 Android View（OnClickListener），而鼠标链路原本只把点击发给
     * Wayland 窗口 host（selectedId）。首页没有 host，L 键/触摸板轻点就什么也不做
     * ——表现为「鼠标逻辑点不开图标，手指直接触摸却可以」。这里按光标屏幕坐标
     * 命中桌面 View 并 performClick，让两种输入在首页行为一致。
     */
    private fun clickDesktopAtPointer(): Boolean {
        if (!desktopVisible) return false
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val x = pointerScreenX - loc[0]
        val y = pointerScreenY - loc[1]
        val hit = findClickableAt(root, x, y)
        if (hit != null) {
            // 点击即时反馈：先按缩放，让用户明确知道「选中并触发了」；应用冷启动要几秒，
            // 没有这个反馈会显得「点了没反应/有延迟」。
            pressFeedback(hit)
            hit.performClick()
            FileLog.i(this, TAG, "desktop pointer click -> ${hit.javaClass.simpleName} at (${x.toInt()},${y.toInt()})")
            return true
        }
        return false
    }

    /** 从 ViewGroup 里找 (x,y) 命中且最内层的可点击 View；坐标是 root 局部坐标。 */
    private fun findClickableAt(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        val lx = x - v.left
        val ly = y - v.top
        if (lx < 0 || ly < 0 || lx >= v.width || ly >= v.height) return null
        if (v is ViewGroup) {
            // 逆序（后添加的在上层），命中最上层可点击子 View
            for (i in v.childCount - 1 downTo 0) {
                val child = v.getChildAt(i)
                // cursorView 是纯装饰浮层，不参与命中
                if (::cursorView.isInitialized && child === cursorView) continue
                findClickableAt(child, lx, ly)?.let { return it }
            }
        }
        return if (v.isClickable) v else null
    }

    /** 侧栏触摸板的触摸处理：手势语义和整屏触摸板一致，直接复用。 */
    private fun handleTouchpadTouch(e: android.view.MotionEvent): Boolean {
        // 桌面模式触摸板也应能推动光标：临时视为窗口 host 语义
        return dispatchTouchToCompositor(e)
    }

    /**
     * 统一处理"指针类"设备（鼠标、笔记本触摸板）。
     *
     * 关键点：触摸板上报的是 `SOURCE_MOUSE_RELATIVE`（属于 TRACKBALL 类），
     * 用 `isFromSource(SOURCE_MOUSE)` 判断会漏掉，之前触摸板移动完全没反应。
     * 另外相对设备只能给出增量 `AXIS_RELATIVE_X/Y`，必须自己累加出绝对坐标。
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // 决定性诊断：物理鼠标事件是否到达这里。之前 resolvePointer 的 MOUSE 诊断
        // 0 条，但光标在动——需确认物理鼠标走不走 Kotlin 这条路（决定修复是否生效）。
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        ) {
            val nowG = android.os.SystemClock.uptimeMillis()
            if (nowG - lastPcapLog > 300) {
                lastPcapLog = nowG
                FileLog.i(
                    this, TAG,
                    "GENERIC src=${event.source} act=${event.actionMasked} " +
                        "dev=${event.device?.name} raw=(${event.rawX},${event.rawY}) " +
                        "relAxis=(${event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)}," +
                        "${event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)}) sel=$selectedId"
                )
            }
        }
        if (!isPointerDevice(event)) return super.dispatchGenericMotionEvent(event)

        // 首页（桌面可见）时，鼠标点击交给光标下的 Android 桌面图标：
        // 既不能吞掉事件，也不能只 return super（ACTION_BUTTON_PRESS 不会触发
        // View 的 OnClickListener）。用 desktopVisible 判断而非 selectedId ——
        // 回桌面时 selectedId 仍指向上次窗口，旧逻辑会误判为「在窗口里」。
        if (desktopVisible) {
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.actionButton == MotionEvent.BUTTON_PRIMARY
            ) {
                clickDesktopAtPointer()
            }
            return super.dispatchGenericMotionEvent(event)
        }
        val host = selectedId ?: return super.dispatchGenericMotionEvent(event)
        val p = resolvePointer(event) ?: return super.dispatchGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_MOVE -> {
                sendPointerMotionAt(host, p, event.eventTime)
                // 拖动时按键状态也要同步（例如按住左键拖窗口）
                sendPointerButtonsFromMask(host, event, p)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                sendPointerMotionAt(host, p, event.eventTime)
                sendPointerScrollAt(host, event, p)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> MotionEvent.BUTTON_PRIMARY
                    MotionEvent.BUTTON_SECONDARY -> MotionEvent.BUTTON_SECONDARY
                    MotionEvent.BUTTON_TERTIARY -> MotionEvent.BUTTON_TERTIARY
                    MotionEvent.BUTTON_BACK -> MotionEvent.BUTTON_BACK
                    MotionEvent.BUTTON_FORWARD -> MotionEvent.BUTTON_FORWARD
                    else -> 0
                }
                if (button != 0) {
                    sendPointerButtonAt(
                        host, button,
                        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS,
                        p, event.eventTime
                    )
                }
                return true
            }
        }

        // 其它鼠标事件（悬停离开等）：同步按键状态即可
        sendPointerButtonsFromMask(host, event, p)
        return true
    }

    /** 是否是指针类输入设备（真实鼠标 / 触摸板 / 触控笔的悬停）。 */
    private fun isPointerDevice(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return true
        }
        // 笔/其它指针设备的悬停也走这里
        return event.isFromSource(InputDevice.SOURCE_STYLUS) &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
    }

    private data class PointerPos(val surfaceX: Float, val surfaceY: Float)

    /**
     * 计算指针在 surface 内的坐标，并同步屏幕光标。
     *
     * - 绝对设备（鼠标、手指）：用事件坐标换算；
     * - 相对设备（触摸板）：累加 AXIS_RELATIVE_X/Y 得到屏幕坐标。
     *
     * 之前直接把 event.x/event.y 传给合成器有两个问题：一来相对设备没有
     * 绝对坐标，二来侧栏展开时坐标没有减掉侧栏宽度，指针会整体偏移。
     */
    /**
     * 指针捕获控制（当前停用）。
     *
     * 实测：requestPointerCapture 后 Android 把鼠标事件路由给持有 capture 的
     * View，事件不再流经 Activity.dispatchGenericMotionEvent（PCAP relative
     * 计数为 0），送进 Wayland 客户端的指针移动会断——是比"碰边跳变"更严重的
     * 回归。当前架构（Activity 统一分发 → resolvePointer → 合成器）下捕获不可用，
     * 故这里保持 no-op，靠 resolvePointer 的相对累加 + clamp + 跳变保护处理边界。
     * 若将来要启用，需先把指针输入迁到 View.onGenericMotionEvent 管线。
     */
    private fun updatePointerCapture() {
        // no-op：见上方说明
    }

    private fun releasePointerCapture() {
        // no-op：见上方说明
    }

    private fun resolvePointer(event: MotionEvent): PointerPos? {
        val surfaceLoc = IntArray(2)
        surfaceLayer.getLocationOnScreen(surfaceLoc)
        val surfaceW = surfaceLayer.width.coerceAtLeast(1)
        val surfaceH = surfaceLayer.height.coerceAtLeast(1)
        // surfaceLayer 的屏幕原点已经包含侧栏占位，直接用它即可。
        val surfaceX0 = surfaceLoc[0]
        val surfaceY0 = surfaceLoc[1]

        val relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                event.isFromSource(InputDevice.SOURCE_TOUCHPAD))

        if (relative) {
            val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            if (pointerScreenX < 0f || pointerScreenY < 0f) {
                pointerScreenX = surfaceX0 + surfaceW / 2f
                pointerScreenY = surfaceY0 + surfaceH / 2f
            }
            if (dx != 0f || dy != 0f) {
                pointerScreenX += dx
                pointerScreenY += dy
            }
            // 诊断：relative 分支（触摸板/相对源）。确认边缘行为，看是否累加后
            // 未及时钳位导致回绕。限频 300ms。
            val nowR = android.os.SystemClock.uptimeMillis()
            if (nowR - lastPcapLog > 300) {
                lastPcapLog = nowR
                FileLog.i(
                    this, TAG,
                    "MOUSE-REL dx=($dx,$dy) cur=(${pointerScreenX.toInt()},${pointerScreenY.toInt()}) " +
                        "surf=($surfaceX0,$surfaceY0,${surfaceW}x${surfaceH})"
                )
            }
        } else {
            // 物理鼠标（SOURCE_MOUSE）。硬件本质是相对设备，但 Android 同时上报
            // 绝对坐标 rawX/rawY（跟随系统指针）和相对增量 AXIS_RELATIVE_X/Y。
            //
            // 旧实现混用绝对/相对，导致"越界回原点"：推到物理屏边时系统指针被钳位，
            // 部分 ROM 下 rawX/rawY 会瞬移到 0，或首次进入窗口时 pointerScreenX<0
            // 触发 =event.rawX（此刻可能接近 0）→ 光标跳原点。
            //
            // 正解：鼠标一律当纯相对设备——只累加 AXIS_RELATIVE_X/Y，绝对不碰
            // rawX/rawY。累加后由下方 coerceIn 钳在窗口区内，推到边就停住，永不回原点。
            //   - 首帧用 rawX/rawY 定个初始基准（仅一次），之后全靠相对增量推进。
            //   - 相对轴缺失（恒 0）时退回绝对跟随（兼容个别不填相对轴的设备），
            //     但这种设备极少，且不会触发边缘钳位瞬移（它们 rawX 本就连续）。
            val rdx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val rdy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)

            // 诊断：限频打印真实事件数据（确认相对轴有值、raw 在边缘的行为）。
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPcapLog > 400) {
                lastPcapLog = now
                FileLog.i(
                    this, TAG,
                    "MOUSE rel=($rdx,$rdy) raw=(${event.rawX},${event.rawY}) " +
                        "cur=(${pointerScreenX.toInt()},${pointerScreenY.toInt()})"
                )
            }

            if (pointerScreenX < 0f || pointerScreenY < 0f) {
                // 首帧：用绝对坐标定初始基准，之后不再读 rawX/rawY。
                pointerScreenX = event.rawX
                pointerScreenY = event.rawY
            } else if (rdx != 0f || rdy != 0f) {
                // 主路径：纯相对累加，边缘由 coerceIn 卡住，推到边就停。
                pointerScreenX += rdx
                pointerScreenY += rdy
            } else if (event.rawX != lastAbsX || event.rawY != lastAbsY) {
                // 兜底：设备不填相对轴（恒 0）但 raw 在动 → 绝对跟随，但加跳变保护。
                // 物理鼠标推到屏边时 rawX 可能突变回 0（硬件回绕），直接照搬会把光标
                // 搬到原点（即用户报的"越界回原点"）。单次增量超半屏判为回绕，丢弃，
                // 让光标停在原处（配合 coerceIn 就是停在边缘）。
                val ddx = event.rawX - lastAbsX
                val ddy = event.rawY - lastAbsY
                if (kotlin.math.abs(ddx) <= surfaceW / 2f) pointerScreenX += ddx
                if (kotlin.math.abs(ddy) <= surfaceH / 2f) pointerScreenY += ddy
            }
            lastAbsX = event.rawX
            lastAbsY = event.rawY
        }
        // 限制在窗口区内
        pointerScreenX = pointerScreenX.coerceIn(
            surfaceX0.toFloat(), (surfaceX0 + surfaceW - 1).toFloat()
        )
        pointerScreenY = pointerScreenY.coerceIn(
            surfaceY0.toFloat(), (surfaceY0 + surfaceH - 1).toFloat()
        )

        // 自绘软件光标跟随指针（窗口区显示；桌面/无标签时隐藏）
        moveCursor()
        return PointerPos(pointerScreenX - surfaceX0, pointerScreenY - surfaceY0)
    }

    /**
     * 把光标 View 挂进 Activity 的子窗口（TYPE_APPLICATION_PANEL）。
     *
     * 子窗口永远画在宿主窗口之上——包括 SurfaceView 那种独立图层，根治
     * "光标移到窗口区就消失"的问题（普通 View + translationZ 在部分 ROM
     * 上盖不过 SurfaceView 的 surface）。不可聚焦、不可触摸，只负责显示。
     */
    private fun attachCursorWindow() {
        if (cursorWm != null || !::cursorView.isInitialized) return
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            token = window.attributes.token
        }
        try {
            wm.addView(cursorView, lp)
            cursorWm = wm
            cursorParams = lp
            FileLog.i(this, TAG, "cursor panel-window attached")
        } catch (e: Exception) {
            // 兜底：个别 ROM 拒绝 panel 子窗口，退回 root 内画（老行为）
            FileLog.e(this, TAG, "cursor panel-window failed: ${e.message}", e)
            if (cursorView.parent == null) {
                root.addView(cursorView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
                cursorView.translationZ = dp(14).toFloat()
            }
        }
    }

    private fun detachCursorWindow() {
        val wm = cursorWm
        if (wm != null) {
            runCatching { wm.removeView(cursorView) }
            FileLog.i(this, TAG, "cursor panel-window removed")
        }
        cursorWm = null
        cursorParams = null
    }

    // ------------------------------------------------------------------
    // 脚本输出条：应用安装/启动等过程实时显示在屏幕底部，
    // 不用再猜有没有报错。同样是子窗口，盖在合成 surface 之上。
    // ------------------------------------------------------------------

    private lateinit var statusStrip: android.widget.TextView
    private var statusWm: android.view.WindowManager? = null
    private var statusParams: android.view.WindowManager.LayoutParams? = null
    private val statusBuf = StringBuilder()
    private var statusHideRunnable: Runnable? = null

    private fun attachStatusWindow() {
        if (statusWm != null || !::statusStrip.isInitialized) return
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        if (!::statusStrip.isInitialized) return
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            token = window.attributes.token
        }
        try {
            wm.addView(statusStrip, lp)
            statusWm = wm
            statusParams = lp
        } catch (e: Exception) {
            FileLog.e(this, TAG, "status panel-window failed: ${e.message}", e)
        }
    }

    private fun detachStatusWindow() {
        val wm = statusWm
        if (wm != null) runCatching { wm.removeView(statusStrip) }
        statusWm = null
        statusParams = null
    }

    /** runScript 的输出逐行进来（注意：在 IO 线程回调）。 */
    private fun appendStatusLine(tag: String, line: String) {
        if (tag != "Install" && tag != "GuiLaunch") return
        runOnUiThread {
            if (!::statusStrip.isInitialized || statusWm == null) return@runOnUiThread
            synchronized(statusBuf) {
                statusBuf.append(line).append('\n')
                // 只保留末尾 12 行
                var idx = -1
                var count = 0
                for (i in statusBuf.length - 1 downTo 0) {
                    if (statusBuf[i] == '\n') {
                        count++
                        if (count > 12) { idx = i + 1; break }
                    }
                }
                if (idx > 0) statusBuf.delete(0, idx)
            }
            statusStrip.text = statusBuf.toString().trimEnd()
            statusStrip.visibility = View.VISIBLE
            // 脚本结束后 4 秒淡出
            statusHideRunnable?.let { padHandler.removeCallbacks(it) }
            if (line.startsWith("exit=")) {
                statusHideRunnable = Runnable {
                    statusHideRunnable = null
                    statusStrip.visibility = View.GONE
                }
                padHandler.postDelayed(statusHideRunnable!!, 4000)
            }
        }
    }

    /**
     * 软件光标移动到最近一次指针位置。
     *
     * 子窗口模式：x/y 是相对宿主窗口左上角的偏移，用 root 的屏幕原点换算；
     * 坐标没变（<1px）就不重排，省掉多余的窗口事务。
     */
    private fun moveCursor() {
        if (!::cursorView.isInitialized) return
        if (cursorView.visibility != View.VISIBLE) cursorView.visibility = View.VISIBLE
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)
        val curW = cursorView.measuredWidth.takeIf { it > 0 } ?: dp(16)
        val curH = cursorView.measuredHeight.takeIf { it > 0 } ?: dp(16)
        // 钳位：光标尖端（窗口左上角）不出屏，整个光标窗口可见
        val maxW = (root.width - curW).coerceAtLeast(0)
        val maxH = (root.height - curH).coerceAtLeast(0)
        val x = (pointerScreenX - rl[0]).roundToInt().coerceIn(0, maxW)
        val y = (pointerScreenY - rl[1]).roundToInt().coerceIn(0, maxH)
        val lp = cursorParams
        val wm = cursorWm
        if (lp != null && wm != null) {
            if (x != lp.x || y != lp.y) {
                lp.x = x
                lp.y = y
                runCatching { wm.updateViewLayout(cursorView, lp) }
            }
        } else {
            // 兜底（子窗口不可用）：留在 root 里画
            cursorView.translationX = (pointerScreenX - rl[0])
            cursorView.translationY = (pointerScreenY - rl[1])
            cursorView.translationZ = dp(14).toFloat()
            cursorView.bringToFront()
        }
    }

    /** 首页（桌面）触摸观察：ScrollView 拦 MOVE，普通监听收不到，这里统一驱动光标。 */
    private var deskTrack = false
    private var deskLastX = 0f
    private var deskLastY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 桌面可见时所有触摸顺带移动光标（相对位移）；事件照常往下派发，
        // 图标点击、桌面滚动等原有交互不受影响。
        if (desktopVisible) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deskTrack = true
                    deskLastX = ev.rawX
                    deskLastY = ev.rawY
                }
                MotionEvent.ACTION_MOVE -> if (deskTrack) {
                    moveCursorBy(ev.rawX - deskLastX, ev.rawY - deskLastY)
                    deskLastX = ev.rawX
                    deskLastY = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> deskTrack = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 把光标摆到窗口区中央（PC 式：一直显示；新开窗口/回桌面时用）。 */
    private fun showCursorAtCenter() {
        val loc = IntArray(2)
        surfaceLayer.getLocationOnScreen(loc)
        val w = surfaceLayer.width.coerceAtLeast(1)
        val h = surfaceLayer.height.coerceAtLeast(1)
        pointerScreenX = loc[0] + w / 2f
        pointerScreenY = loc[1] + h / 2f
        moveCursor()
    }

    private fun sendPointerMotionAt(host: String, p: PointerPos, time: Long) {
        runCatching {
            NativeBridge.nativeOnPointerEvent(
                host, POINTER_KIND_MOTION, p.surfaceX, p.surfaceY,
                0, false, 0f, 0f, false, false, time
            )
        }
    }

    private fun sendPointerButtonAt(
        host: String,
        button: Int,
        pressed: Boolean,
        p: PointerPos,
        time: Long,
    ) {
        FileLog.i(this, TAG, "btn host=$host code=$button pressed=$pressed pos=(${p.surfaceX.toInt()},${p.surfaceY.toInt()}) t=$time")
        runCatching {
            NativeBridge.nativeOnPointerEvent(
                host, POINTER_KIND_BUTTON, p.surfaceX, p.surfaceY,
                button, pressed, 0f, 0f, false, false, time
            )
        }
    }

    /**
     * 诊断：点击后向合成器线程查询状态快照，提取 pointer_focus / pointer_x /
     * pointer_y / output_scale 写日志。nativeQueryState 走与 pointer 事件相同的
     * event loop channel，会排在刚发的 motion/button 之后处理，所以反映的是
     * 点击被处理后的真实内部状态——能区分「事件没到 Rust」「host 校验失败」
     * 「surface_at 未命中」等故障。仅用于排查，不影响正常路径。
     */
    private fun logPointerState(tag: String) {
        runCatching {
            val snap = NativeBridge.nativeQueryState() ?: return
            val fields = snap.split(" ").filter {
                it.startsWith("output_scale=") || it.startsWith("pointer_") ||
                    it.startsWith("hosts=") || it.startsWith("toplevels=")
            }.joinToString(" ")
            FileLog.i(this, TAG, "state[$tag] $fields")
        }
    }

    private var lastButtonMask = 0

    private fun sendPointerButtonsFromMask(host: String, event: MotionEvent, p: PointerPos) {
        val mask = event.buttonState and MOUSE_BUTTON_MASK
        var changed = mask xor lastButtonMask
        if (changed == 0) return
        lastButtonMask = mask
        while (changed != 0) {
            val bit = changed and (-changed)
            changed = changed and bit.inv()
            sendPointerButtonAt(host, bit, (mask and bit) != 0, p, event.eventTime)
        }
    }

    private fun sendPointerScrollAt(host: String, event: MotionEvent, p: PointerPos) {
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (v == 0f && h == 0f) return
        runCatching {
            NativeBridge.nativeOnPointerEvent(
                host, POINTER_KIND_AXIS, p.surfaceX, p.surfaceY,
                0, false, v, h, false, false, event.eventTime
            )
        }
    }

    /** 侧栏展开/收起时窗口区宽度变化，指针位置随之重置，避免落在旧区域。 */
    private fun resetPointer() {
        pointerScreenX = -1f
        pointerScreenY = -1f
        // PC 式：光标一直显示，侧栏形态切换后重新摆到窗口区中央
        showCursorAtCenter()
    }

    override fun onBackPressed() {
        if (shortcutVisible) {
            toggleShortcutBar()
            return
        }
        // 返回键先关当前标签（关掉对应 Linux 窗口），没有标签时退出桌面。
        val host = selectedId
        if (host != null) {
            onWindowFinished(host)
            return
        }
        finish()
    }

    // ------------------------------------------------------------------
    // 合成器生命周期
    // ------------------------------------------------------------------

    private fun prepareEnvironment() {
        val data = applicationContext.dataDir
        val files = filesDir
        val share = File(data, "share").apply { mkdirs() }
        File(share, "xtmp").mkdirs()
        File(data, "distros").mkdirs()
        File(files, "xwayland").mkdirs()
        val xkb = ensureXkb()

        setenv("TAWC_APP_DATA_DIR", data.absolutePath)
        setenv("TAWC_APP_FILES_DIR", files.absolutePath)
        setenv("TAWC_APP_SHARE_DIR", share.absolutePath)
        setenv("TAWC_DISTROS_DIR", File(data, "distros").absolutePath)
        setenv("TAWC_XWAYLAND_DIR", File(files, "xwayland").absolutePath)
        setenv("TAWC_XWAYLAND_RUNTIME_DIR", File(share, "xtmp").absolutePath)
        setenv("TAWC_XKB_CONFIG_ROOT", xkb.absolutePath)
        setenv("TAWC_NATIVE_LIB_DIR", applicationInfo.nativeLibraryDir)
        setenv("TAWC_XWAYLAND_ENABLED", "0")
        val extLogs = getExternalFilesDir("logs") ?: File(files, "logs")
        extLogs.mkdirs()
        setenv("TAWC_LOG_FILE", File(extLogs, "compositor-native.log").absolutePath)
        envReady = true
        FileLog.i(this, TAG, "env ready xkb=${xkb.absolutePath} share=${share.absolutePath}")
    }

    private fun startCompositorIfNeeded() {
        if (compositorStarted || !envReady) return
        try {
            NativeBridge.nativeStartActivation(false)
            val started = NativeBridge.nativeStartCompositor(
                OUTPUT_SCALE,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
                false,
                false,
            )
            NativeBridge.nativeSetOutputScale(OUTPUT_SCALE)
            NativeBridge.nativeSetXwaylandEnabled(false)
            // 关掉按 buffer 类型染色的调试 tint。
            // 本机没有 dma-buf，所有窗口都走 SHM 回退路径，开着 tint 会让
            // 整个画面被刷上一层品红/红光。tint 只是用来暴露回退路径的调试
            // 手段，产品里默认关。
            NativeBridge.nativeSetTintBuffersByType(false)
            // 页面存活期间钉住合成器：否则 10s 无客户端会自停，
            // 已登记的 Surface 失效，之后再有窗口就连不上（黑屏）。
            NativeBridge.nativeSetCompositorHold(true)
            // 软触摸板就是一个指针设备。触屏手机没有物理鼠标，若不告知
            // Rust「有指针」，seat 永不授予 wl_pointer 能力（pointer_present=
            // false），触摸板的移动/点击/滚动会在 pointer channel 入口被
            // 静默丢弃，客户端收不到任何指针事件。启动即置 true。
            NativeBridge.nativeOnMouseAttachedChanged(true)
            compositorStarted = NativeBridge.nativeIsCompositorRunning() || started
            FileLog.i(this, TAG, "compositor started=$started running=$compositorStarted")
        } catch (e: Throwable) {
            FileLog.e(this, TAG, "start compositor failed: ${e.message}", e)
            Log.e(TAG, "start compositor failed", e)
        }
    }

    override fun onDestroy() {
        BrowserBridge.stop()
        detachCursorWindow()
        detachStatusWindow()
        if (DesktopApps.outputObserver != null) DesktopApps.outputObserver = null
        if (!isChangingConfigurations) {
            if (compositorStarted) {
                runCatching { NativeBridge.nativeSetCompositorHold(false) }
                runCatching { NativeBridge.nativeStopCompositor() }
                compositorStarted = false
            }
            NativeBridge.finishAllHosts()
            if (instance === this) instance = null
        }
        super.onDestroy()
    }

    /** 解压 assets/xkb 到 filesDir/xkb（xkbcommon 启动时要读）。 */
    private fun ensureXkb(): File {
        val out = File(filesDir, "xkb")
        val marker = File(out, ".codetool-xkb")
        if (marker.exists()) return out
        copyAssetDir("xkb", out)
        marker.writeText("1")
        return out
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(dest, child))
        }
    }

    private fun setenv(name: String, value: String) {
        try {
            Os.setenv(name, value, true)
        } catch (e: Throwable) {
            Log.w(TAG, "setenv $name failed: ${e.message}")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
