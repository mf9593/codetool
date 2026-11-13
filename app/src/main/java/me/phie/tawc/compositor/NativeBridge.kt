package me.phie.tawc.compositor

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.codetool.app.gui.TawcCompositorActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小 JNI 桥：只声明 CodeTool 用到的 tawc compositor 原生方法。
 *
 * 包名/类名必须保持 `me.phie.tawc.compositor.NativeBridge`，因为 .so 里的
 * 符号是 `Java_me_phie_tawc_compositor_NativeBridge_*`，改包名会导致链接失败。
 *
 * 原生库由 jniLibs 打包为 libtawc_compositor.so（构建产物 libcompositor.so）。
 */
object NativeBridge {

    private const val TAG = "TawcNativeBridge"

    init {
        System.loadLibrary("tawc_compositor")
    }

    /** 应用上下文（由 TawcCompositorActivity 设置，供 spawnActivity 使用）。 */
    @Volatile
    var appContext: Context? = null

    private fun log(msg: String) {
        appContext?.let { com.codetool.app.util.FileLog.i(it, TAG, msg) }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hosts = ConcurrentHashMap<String, Activity>()

    // ---- 原生方法（tawc compositor） ----

    external fun nativeStartCompositor(
        outputScale: Float,
        displayWidthPx: Int,
        displayHeightPx: Int,
        xwayland: Boolean,
        gtk3BrokenMenusWorkaround: Boolean,
    ): Boolean

    external fun nativeStopCompositor()
    external fun nativeIsCompositorRunning(): Boolean
    external fun nativeStartActivation(xwayland: Boolean)

    external fun nativeRegisterActivitySurface(activityId: String, surface: Surface, width: Int, height: Int)
    external fun nativeOnActivitySurfaceChanged(activityId: String, width: Int, height: Int)
    external fun nativeOnActivitySurfaceDestroyed(activityId: String)
    external fun nativeOnActivityDestroyed(activityId: String)
    external fun nativeOnActivityFocusChanged(activityId: String, hasFocus: Boolean)
    /**
     * 告知合成器「是否有指针类设备」。软触摸板本质就是一个指针设备：
     * 触屏手机上没有物理鼠标，若不置 true，Rust 侧 seat 永远不授予
     * wl_pointer 能力（pointer_present=false），触摸板的移动/点击/滚动
     * 会在 pointer channel 入口被静默丢弃，客户端收不到任何指针事件。
     */
    external fun nativeOnMouseAttachedChanged(attached: Boolean)
    external fun nativeOnTouchEvent(
        activityId: String,
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        eventTime: Long,
    )

    external fun nativeOnPointerEvent(
        activityId: String,
        kind: Int,
        x: Float,
        y: Float,
        button: Int,
        pressed: Boolean,
        vscroll: Float,
        hscroll: Float,
        fromTouchpad: Boolean,
        stop: Boolean,
        eventTime: Long,
    )

    external fun nativeSetOutputScale(scale: Float)
    external fun nativeSetXwaylandEnabled(enabled: Boolean)
    external fun nativeSetTintBuffersByType(enabled: Boolean)
    external fun nativeSetCompositorHold(hold: Boolean)
    external fun nativeQueryState(): String?

    external fun nativeOnHardwareKeyEvent(
        activityId: String,
        keycode: Int,
        pressed: Boolean,
        repeatCount: Int,
    ): Boolean

    external fun nativeOnBackPressed(activityId: String)

    // ---- 文本输入（Android 输入法 → 容器客户端） ----

    external fun nativeCommitText(text: String, deleteBefore: Int, deleteAfter: Int)
    external fun nativeSetComposingText(text: String, deleteBefore: Int, deleteAfter: Int)
    external fun nativeFinishComposingText()
    external fun nativeSendKeyEvent(keycode: Int)
    external fun nativeSendKeyState(keycode: Int, pressed: Boolean)

    /** 当前活跃的输入连接（客户端回推文本时用来纠正 Editable 镜像）。 */
    @Volatile
    var activeInputConnection: TawcInputConnection? = null

    val imeOutput: ImeOutput = RealImeOutput

    // ---- 窗口宿主管理 ----

    /** 窗口 Activity 创建时登记，native 的 finishActivity 回调可按 id 关闭。 */
    fun registerHost(activityId: String, activity: Activity) {
        hosts[activityId] = activity
    }

    fun unregisterHost(activityId: String) {
        hosts.remove(activityId)
    }

    /** 结束所有窗口 Activity（合成器停止/退出桌面时）。 */
    fun finishAllHosts() {
        mainHandler.post {
            for (a in hosts.values.toList()) {
                runCatching { a.finish() }
            }
            hosts.clear()
        }
    }

    // ---- 反向 JNI 回调（Rust 以 static method 调用，必须 @JvmStatic）----

    @JvmStatic
    fun onCompositorStopped() {
        mainHandler.post { finishAllHosts() }
    }

    @JvmStatic
    fun onActivationRequested() {
        // 合成器空闲会自动退出；有客户端连接时按需启动（tawc 的生命周期设计）。
        log("onActivationRequested: Wayland client connected")
        try {
            if (!nativeIsCompositorRunning()) {
                val dm = appContext?.resources?.displayMetrics
                val w = dm?.widthPixels ?: 1156
                val h = dm?.heightPixels ?: 2510
                val started = nativeStartCompositor(2.0f, w, h, false, false)
                nativeSetOutputScale(2.0f)
                nativeSetXwaylandEnabled(false)
                nativeSetTintBuffersByType(false)
                nativeSetCompositorHold(true)
                log("onActivationRequested: compositor start=$started running=${nativeIsCompositorRunning()}")
                // 合成器线程是新的：让页面重新登记已有标签的 Surface + 焦点。
                TawcCompositorActivity.instance?.onCompositorRestarted()
            }
        } catch (e: Throwable) {
            log("onActivationRequested: start failed: $e")
        }
    }

    /**
     * 合成器要求为某个 Linux 窗口创建宿主。新模型不再新开 Activity：
     * 交给已在运行的 [TawcCompositorActivity] 开一个标签（每个标签一个
     * SurfaceView，注册为该窗口的 host）。
     */
    @JvmStatic
    fun spawnActivity(activityId: String) {
        log("spawnActivity($activityId) requested")
        mainHandler.post {
            val shell = TawcCompositorActivity.instance
            if (shell == null) {
                Log.e(TAG, "spawnActivity($activityId): no compositor shell running")
                return@post
            }
            shell.onWindowSpawned(activityId)
        }
    }

    @JvmStatic
    fun finishActivity(activityId: String) {
        log("finishActivity($activityId)")
        mainHandler.post {
            TawcCompositorActivity.instance?.onWindowFinished(activityId)
        }
    }

    @JvmStatic
    fun setActivityFullscreen(activityId: String, fullscreen: Boolean) {
        // 窗口宿主目前固定全屏，忽略
    }

    /**
     * 客户端请求的鼠标指针形状（CSS cursor 名）。Android 负责画光标，
     * 这里只把它映射成系统 PointerIcon —— 系统画的光标大小随系统设置、
     * 永远在所有图层之上，比自绘光标更接近真实桌面。
     */
    @JvmStatic
    fun setPointerIcon(activityId: String, shape: String) {
        val type = pointerIconTypeFor(shape)
        mainHandler.post {
            val activity = TawcCompositorActivity.instance ?: return@post
            activity.setPointerIconFromCompositor(
                activityId, android.view.PointerIcon.getSystemIcon(activity, type)
            )
        }
    }

    /** 客户端自绘的光标位图（老式 X11 客户端 / Xwayland 走这条路）。 */
    @JvmStatic
    fun setPointerIconBitmap(
        activityId: String,
        pixels: IntArray,
        width: Int,
        height: Int,
        hotspotX: Int,
        hotspotY: Int,
    ) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            Log.w(TAG, "setPointerIconBitmap: bad ${width}x$height for ${pixels.size} pixels")
            return
        }
        val bitmap = android.graphics.Bitmap.createBitmap(
            pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        mainHandler.post {
            val activity = TawcCompositorActivity.instance ?: return@post
            val icon = runCatching {
                android.view.PointerIcon.create(bitmap, hotspotX.toFloat(), hotspotY.toFloat())
            }.getOrNull() ?: return@post
            activity.setPointerIconFromCompositor(activityId, icon)
        }
    }

    /** `wp_cursor_shape_v1` 的名字 → 最接近的 Android 系统光标类型。 */
    private fun pointerIconTypeFor(shape: String): Int = when (shape) {
        "" -> android.view.PointerIcon.TYPE_NULL
        "pointer" -> android.view.PointerIcon.TYPE_HAND
        "text" -> android.view.PointerIcon.TYPE_TEXT
        "vertical-text" -> android.view.PointerIcon.TYPE_VERTICAL_TEXT
        "crosshair" -> android.view.PointerIcon.TYPE_CROSSHAIR
        "help" -> android.view.PointerIcon.TYPE_HELP
        "wait", "progress" -> android.view.PointerIcon.TYPE_WAIT
        "context-menu" -> android.view.PointerIcon.TYPE_CONTEXT_MENU
        "copy" -> android.view.PointerIcon.TYPE_COPY
        "alias" -> android.view.PointerIcon.TYPE_ALIAS
        "no-drop", "not-allowed" -> android.view.PointerIcon.TYPE_NO_DROP
        "grab" -> android.view.PointerIcon.TYPE_GRAB
        "grabbing" -> android.view.PointerIcon.TYPE_GRABBING
        "move", "all-scroll" -> android.view.PointerIcon.TYPE_ALL_SCROLL
        "e-resize", "w-resize", "ew-resize", "col-resize",
        "horizontal-double-arrow" -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        "n-resize", "s-resize", "ns-resize", "row-resize",
        "vertical-double-arrow" -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        "ne-resize", "sw-resize", "nesw-resize" ->
            android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
        "nw-resize", "se-resize", "nwse-resize" ->
            android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
        "zoom-in" -> android.view.PointerIcon.TYPE_ZOOM_IN
        "zoom-out" -> android.view.PointerIcon.TYPE_ZOOM_OUT
        else -> android.view.PointerIcon.TYPE_ARROW
    }

    // ---- 文本输入回调（客户端 → Android 输入法） ----

    @JvmStatic
    fun onUpdateEditableText(
        activityId: String,
        text: String,
        selStart: Int,
        selEnd: Int,
        authoritative: Boolean,
    ) {
        mainHandler.post {
            val ic = activeInputConnection ?: return@post
            TawcCompositorActivity.instance?.updateEditableTextFromCompositor(
                activityId, ic, text, selStart, selEnd, authoritative
            )
        }
    }

    @JvmStatic
    fun onShowKeyboard(activityId: String) {
        mainHandler.post {
            com.codetool.app.util.FileLog.i(
                TawcCompositorActivity.instance ?: return@post, "TawcIME",
                "onShowKeyboard from native host=$activityId (compositor判定客户端enabled text_input)"
            )
            TawcCompositorActivity.instance?.showKeyboardFor(activityId)
        }
    }

    @JvmStatic
    fun onHideKeyboard(activityId: String) {
        mainHandler.post { TawcCompositorActivity.instance?.hideKeyboardFor(activityId) }
    }

    @JvmStatic
    fun onContentTypeChanged(activityId: String, inputType: Int, imeFlags: Int) {
        mainHandler.post {
            TawcCompositorActivity.instance?.let {
                com.codetool.app.util.FileLog.i(it, "TawcIME",
                    "onContentTypeChanged host=$activityId inputType=$inputType flags=$imeFlags")
            }
            TawcCompositorActivity.instance?.onImeContentTypeChanged(activityId, inputType, imeFlags)
        }
    }

    @JvmStatic
    fun updateWindowMetadata(
        activityId: String,
        title: String,
        appId: String,
        desktopId: String,
        desktopName: String,
        iconPath: String,
    ) {
        log("updateWindowMetadata($activityId) title=$title appId=$appId")
        mainHandler.post {
            TawcCompositorActivity.instance?.onWindowTitle(activityId, title)
        }
    }

    @JvmStatic
    fun onToplevelCountChanged(count: Int) {
        log("onToplevelCountChanged($count)")
    }
}
