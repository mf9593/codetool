package com.codetool.app.gui

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codetool.app.CodeToolApp
import com.codetool.app.MainActivity
import com.codetool.app.R
import com.codetool.app.util.FileLog

/**
 * 浏览器桥前台服务：常驻承载容器内 → app 的浏览器桥（BrowserBridge），
 * 让模型 / CLI 调浏览器**不依赖 GUI 合成器**。
 *
 * 之前 BrowserBridge 绑在 [TawcCompositorActivity] 生命周期上（onCreate 起、
 * onDestroy 停），导致「必须先在终端点『显示』打开合成器」浏览器 MCP 才可用。
 * 实际上文件桥只是 FileObserver、CDP 只是容器内 Chrome 的启动参数，都不碰渲染，
 * 完全可以抽出来后台常驻。
 *
 * 本服务在 App 启动时拉起（[CodeToolApp]），持有前台通知把进程提到前台优先级，
 * 锁屏不被冻结。`action=open` 默认起**无头 Chrome**（`--headless=new` + CDP 9222），
 * 纯后台自动化（开页面/eval/截图/读 CDP），不开窗口、不需要合成器渲染。
 *
 * 要肉眼看浏览器画面时，走 [TawcCompositorActivity.launchChromeWithUrl]
 * （带窗口模式）；这里不抢合成器职责。
 */
class BrowserBridgeService : Service() {

    companion object {
        private const val TAG = "BrowserBridge"
        private const val NOTIF_ID = 1004
        private const val ACTION_START = "com.codetool.action.BROWSER_BRIDGE_START"
        private const val ACTION_STOP = "com.codetool.action.BROWSER_BRIDGE_STOP"

        @Volatile var running = false; private set

        /** App 启动时拉起（幂等）。 */
        fun start(context: Context) {
            val intent = Intent(context, BrowserBridgeService::class.java)
                .setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // 后台启动受限等场景不致命：GUI 合成器开着时 Activity 仍会兜底起桥
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        /** 停止常驻桥（一般不主动调；保留以对称）。 */
        fun stop(context: Context) {
            val intent = Intent(context, BrowserBridgeService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                BrowserBridge.stop()
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 桥常驻：FileObserver 监听容器请求，handler 在本服务里起无头 Chrome。
                // 注意：BrowserBridge.start 内部 stop() 后重启、幂等，必须每次 onStartCommand
                // 都调——running 是 companion 静态变量，服务被系统重建时可能残留 true，
                // 放在 if(!running) 里会跳过桥启动（实测「服务 isForeground 但桥目录没建」）。
                BrowserBridge.start(this) { req, respond -> handleRequest(req, respond) }
                // H9：startForeground 必须无条件调用——running 残留 true 时若走 if(!running)
                // 会跳过 startForeground，系统 5s 判定前台服务未启动 → ANR/RemoteServiceException。
                // startForeground 重复调用幂等、无害，仅用于控制日志/标志。
                if (!running) {
                    running = true
                    Log.i(TAG, "browser bridge service started (headless mode)")
                }
                startForeground(NOTIF_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    /** 处理容器内 codetool-browser / codetool-mcp 发来的请求。 */
    /**
     * 检测容器里是否有「可见的、默认 profile」的 Chrome 主进程在跑。
     * 转发（带窗口）只在已运行的可见实例上有效——它靠默认 profile 的 SingletonLock
     * 把 URL 转给现有实例。可见实例用默认 profile（/root/.config/google-chrome），
     * 无头实例用独立 profile（codetool-chrome-headless），据此排除无头。
     * 只认主浏览器进程（排除 --type=renderer/gpu/utility 等子进程）。
     *
     * 不用容器内扫 /proc 的脚本：tawcroot exec 出来的 /proc 视图看不到容器主进程
     *（终端会话里 /proc 正常、脚本 exec 里看不到 Chrome，未深究 tawcroot 内部差异），
     * 脚本检测恒 false，CHROME_HIT 从未出现。改为纯 Java：读默认 profile 的
     * SingletonLock（Chrome 在跑时必存在，内容为 localhost-<pid>），再读
     * /proc/<pid>/cmdline 校验存活并确认是主浏览器进程（与 TawcrootRuntime 清理
     * 日志 MCP 时读 /proc/<pid>/cmdline 同款写法，同为应用自建的容器子进程）。
     * 检测异常一律按「没跑」处理 → 引导点桌面图标，绝不误触发冷启动。
     */
    private fun isDefaultProfileChromeRunning(): Boolean {
        return try {
            val rootfs = com.codetool.app.runtime.RootfsManager.get(this).containerRoot
            val lock = java.io.File(rootfs, "root/.config/google-chrome/SingletonLock")
            val target = runCatching {
                java.nio.file.Files.readSymbolicLink(lock.toPath()).toString()
            }.getOrNull() ?: return false
            val pid = target.substringAfterLast('-').toIntOrNull() ?: return false
            val cmd = runCatching {
                java.io.File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull().orEmpty()
            if (cmd.isEmpty()) {
                FileLog.w(this, TAG, "visible chrome check: pid=$pid cmdline 读不到（SELinux 或进程已退）")
                return false
            }
            FileLog.i(this, TAG, "visible chrome check pid=$pid :: ${cmd.take(120)}")
            cmd.contains("google-chrome") &&
                !cmd.contains("--type=") &&
                !cmd.contains("codetool-chrome-headless")
        } catch (e: Exception) {
            FileLog.w(this, TAG, "detect visible chrome failed: ${e.message}")
            false
        }
    }

    private fun handleRequest(req: org.json.JSONObject, respond: (Boolean, String) -> Unit) {
        when (req.optString("action")) {
            "ping" -> respond(true, "browser bridge ready（后台常驻，无需 GUI）")
            "open" -> {
                val url = req.optString("url", "about:blank")
                // 默认带窗口：用户点开合成器就能看到实时画面。仅当请求显式 headless=true
                // 才走后台无头（自动化用）。gui 未传时按「带窗口」处理（MCP/CLI 默认都期望看到窗口）。
                val wantHeadless = req.optBoolean("headless", false)
                val activity = TawcCompositorActivity.instance
                if (!wantHeadless && activity != null) {
                    // 带窗口走「转发给已运行的可见 Chrome」路径。可见实例没跑时**绝不冷启动**：
                    // 容器里带窗口冷启动 Chrome 会 SIGTRAP 自崩（exit=133，CHECK 失败，见
                    // devlog 2026-11-19），窗口活不下来。转发只在已运行的可见实例上有效。
                    if (!isDefaultProfileChromeRunning()) {
                        respond(false, "GUI 合成器已开，但没有正在运行的可见 Chrome，无法显示窗口"
                                + "（冷启动会崩溃，已跳过）。请点开合成器桌面上的 Chrome 图标，"
                                + "等它打开后重试 browser_open（不带 headless）；或改用 headless:true 走后台无头。")
                        return
                    }
                    if (activity.openUrlInGuiBrowser(url)) {
                        respond(true, "已转发到 GUI 合成器的可见 Chrome（新开前台标签）：$url ——点开合成器即可看到实时画面")
                    } else {
                        respond(false, "浏览器未安装：请在 app 的「工具 → 包管理」点「安装浏览器（Chrome / Chromium）」")
                    }
                    return
                }
                if (!wantHeadless && activity == null) {
                    // 想要带窗口但合成器没开：明确告知（否则用户 open 了却看不到画面会困惑）。
                    // 不静默退回无头——那不会显示任何窗口。
                    respond(false, "GUI 合成器未运行，无法显示窗口。请先在 app 终端点「显示」打开合成器，"
                            + "再重试 browser_open（不带 headless）；或改用 headless:true 走后台无头（CDP 9222，不显示窗口）。")
                    return
                }
                // 后台无头模式（headless=true）：不碰合成器、不开窗口
                launchHeadlessChrome(url, respond)
            }
            else -> respond(false, "未知操作：${req.optString("action")}")
        }
    }

    /**
     * 起无头 Chrome（`--headless=new` + CDP 9222），纯后台，不依赖合成器渲染。
     * Chrome 进程跑在容器内，监听 9222；容器与 app 共享 loopback，模型可直连 CDP。
     */
    private fun launchHeadlessChrome(url: String, respond: (Boolean, String) -> Unit) {
        val ctx = this
        val app = DesktopApps.apps.firstOrNull { it.id == "chrome" }
        if (app == null || !DesktopApps.isInstalled(ctx, app)) {
            respond(false, "浏览器未安装：请在 app 的「工具 → 包管理」点「安装浏览器（Chrome / Chromium）」，装好后重试（无需打开 GUI）")
            return
        }
        // 单引号包裹 + 转义内部单引号，避免 URL 里的 shell 元字符造成注入
        val quoted = "'" + url.replace("'", "'\\''") + "'"
        // --no-first-run 关键：Chrome 首启会弹「服务条款」向导并阻塞 CDP 初始化（实测 9222 起不来）。
        // --headless=new：无头模式，不出窗口，纯后台渲染 + CDP。
        val cdpArgs = "--no-first-run --no-default-browser-check --headless=new " +
            "--disable-gpu --no-sandbox " +
            "--remote-debugging-port=9222 --remote-allow-origins='*' " +
            "--user-data-dir=/root/.config/codetool-chrome-headless"
        val cmd = DesktopApps.ENV_PREFIX + DesktopApps.launchLine(app) + " " + cdpArgs + " " + quoted
        DesktopApps.runScript(ctx, "BrowserHeadless", cmd)
        respond(true, "已在后台无头浏览器打开：$url（CDP http://127.0.0.1:9222，无需 GUI）")
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CodeToolApp.CHANNEL_GUI)
            .setContentTitle("CodeTool 浏览器桥")
            .setContentText("后台浏览器可用（无头模式），模型可直连 CDP")
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        BrowserBridge.stop()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
