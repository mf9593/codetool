package com.codetool.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 容器内 → app 的 Shizuku 桥：让 AI 模型以 shell（adb，uid 2000）身份调试 App。
 *
 * 复用 TawcrootRuntime 已有的 bind mount：`dataDir/share` ↔ 容器 `/usr/share/tawc`。
 * 容器脚本 `/usr/local/bin/codetool-shizuku` 把请求 JSON 原子写入
 * `share/shizuku/requests/<id>.json`；这里 FileObserver 收到后用 Shizuku
 * user-service 执行命令，结果写 `share/shizuku/responses/<id>.json` 供脚本轮询。
 *
 * 桥常驻（随 App 进程），不依赖 GUI 合成器——调试截屏/dumpsys 不需要 GUI。
 * 未授权时明确返回提示，让模型引导用户去首页点 Shizuku 行申请授权。
 */
object ShizukuBridge {

    private val main = Handler(Looper.getMainLooper())
    // Shizuku bind/exec 是阻塞调用，放后台单线程串行执行，避免卡主线程
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var observer: FileObserver? = null
    private var userService: IShizukuUserService? = null
    private var connection: ServiceConnection? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var appContext: Context? = null

    /** 请求/响应目录（Android 侧真实路径；容器里对应 /usr/share/tawc/shizuku/...）。 */
    fun dirs(ctx: Context): Pair<File, File> {
        val base = File(ctx.applicationContext.dataDir, "share/shizuku")
        return File(base, "requests").apply { mkdirs() } to
            File(base, "responses").apply { mkdirs() }
    }

    /** 启动监听。常驻，App 进程存活期间一直监听。 */
    fun start(ctx: Context) {
        if (observer != null) return
        appContext = ctx.applicationContext
        val (req, res) = dirs(ctx)
        // 清掉上次残留的过期响应，避免新请求误读
        runCatching {
            res.listFiles()?.forEach {
                if (it.isFile && it.lastModified() < System.currentTimeMillis() - 60_000) it.delete()
            }
        }
        @Suppress("DEPRECATION")
        observer = object : FileObserver(req.absolutePath, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !path.endsWith(".json")) return
                val f = File(req, path)
                val body = runCatching { f.readText() }.getOrDefault("")
                f.delete()
                if (body.isEmpty()) return
                val obj = runCatching { JSONObject(body) }.getOrNull() ?: return
                val id = obj.optString("id", "").replace(Regex("[^A-Za-z0-9._-]"), "")
                if (id.isEmpty()) return
                io.execute { handle(obj, id, res) }
            }
        }.also { it.startWatching() }
    }

    fun stop() {
        runCatching { observer?.stopWatching() }
        observer = null
        unbind()
    }

    /**
     * 供 App 内直接执行一条 shell 命令（走 Shizuku user-service，shell uid 2000 身份）。
     * 返回 (ok, 输出/错误说明)。用于需要特权但用户已开 Shizuku 的场景
     * （如检测 phantom process：dumpsys activity settings），免 root、免手动连 adb。
     * 注意：内部 ensureService 会轮询阻塞（最多 3s），调用方须在后台线程。
     */
    fun execShell(cmd: String): Pair<Boolean, String> = execViaShizuku(cmd)

    private fun handle(obj: JSONObject, id: String, res: File) {
        val respond: (Boolean, String) -> Unit = { ok, msg -> respond(res, id, ok, msg) }
        val action = obj.optString("action", "exec")
        when (action) {
            "exec" -> {
                val cmd = obj.optString("cmd", "")
                if (cmd.isBlank()) { respond(false, "缺少 cmd"); return }
                val out = execViaShizuku(cmd)
                respond(out.first, out.second)
            }
            // 截屏 / UI dump：shell 写 /data/local/tmp（可写），App 再拷进 share 供容器读
            // （shell 的 SELinux context u:r:shell:s0 不能直接写 App dataDir/share）
            "screenshot" -> {
                val tmp = "/data/local/tmp/codetool-shot.png"
                val cmd = "screencap -p $tmp"
                val out = execViaShizuku(cmd)
                if (!out.first || !out.second.startsWith("exit=0")) {
                    respond(false, "截屏失败：${out.second}"); return
                }
                copyTmpToShare(tmp, "shot.png", respond)
            }
            "ui_dump" -> {
                val tmp = "/data/local/tmp/codetool-ui.xml"
                val cmd = "uiautomator dump $tmp"
                val out = execViaShizuku(cmd)
                if (!out.first || !out.second.startsWith("exit=0")) {
                    respond(false, "UI dump 失败：${out.second}"); return
                }
                copyTmpToShare(tmp, "ui.xml", respond)
            }
            else -> respond(false, "未知操作：$action")
        }
    }

    /**
     * 把 /data/local/tmp 里的中转文件拷进 share/shizuku/ 供容器读，用 App 自己的
     * uid（能读写自己的 dataDir/share）。成功后删掉中转文件。
     */
    private fun copyTmpToShare(tmpPath: String, name: String, respond: (Boolean, String) -> Unit) {
        val ctx = appContext ?: run { respond(false, "桥未启动"); return }
        val dirs = dirs(ctx)
        val dest = File(dirs.second.parentFile, name)   // share/shizuku/<name>
        val src = File(tmpPath)
        runCatching {
            dest.delete()
            src.copyTo(dest, overwrite = true)
            src.delete()
        }.onSuccess {
            respond(true, "已生成：${dest.absolutePath}（容器内 /usr/share/tawc/shizuku/$name）")
        }.onFailure {
            respond(false, "中转拷贝失败：${it.message}")
        }
    }

    /** 通过 Shizuku user-service 执行 cmd，返回 (ok, 输出)。 */
    private fun execViaShizuku(cmd: String): Pair<Boolean, String> {
        // 先确认已授权（shell 身份）
        val granted = runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1)
        if (granted != 0) {
            return false to "Shizuku 未授权：请在 app 首页点「Shizuku」行申请授权（以 shell 身份运行），授权后重试。"
        }
        val svc = ensureService()
            ?: return false to "Shizuku user-service 绑定失败（服务未运行？请确认 Shizuku 管理器正在运行）。"
        return try {
            val out = svc.exec(cmd)
            val ok = !out.startsWith("exit=-1")
            ok to out
        } catch (e: Exception) {
            false to "执行失败：${e.message}"
        }
    }

    private fun ensureService(): IShizukuUserService? {
        userService?.let { return it }
        val ctx = appContext ?: return null
        return try {
            val args = userServiceArgs()
            serviceArgs = args
            Shizuku.bindUserService(args, createConnection())
            // onServiceConnected 在主线程回调，这里轮询等绑定完成（最多 6s）
            var i = 0
            while (userService == null && i < 60) {
                Thread.sleep(100); i++
            }
            if (userService == null) {
                com.codetool.app.util.FileLog.w(
                    ctx, "ShizukuBridge",
                    "bindUserService 6s 未回调：Shizuku 未运行/版本过旧/授权状态异常"
                )
            }
            userService
        } catch (e: Exception) {
            // 之前异常被静默吞掉，出错只能猜——必须落日志。
            com.codetool.app.util.FileLog.e(ctx, "ShizukuBridge", "bindUserService failed", e)
            null
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val comp = ComponentName("com.codetool.app", "com.codetool.app.shizuku.ShizukuUserService")
        val ctx = appContext
        // **关键**：version 必须随安装变化（用 lastUpdateTime，单位压成秒防 Int 溢出）。
        // 不传（默认 0）时 Shizuku 端按 (包名+versionCode+tag) 复用旧 user-service 进程：
        // app 重装后 APK 路径已变，旧 service 的 dex 路径失效 → onServiceConnected 永不回调
        // → 表现为「已授权但绑定失败」（真实踩坑，debug 反复装机必现）。
        val lastUpdateSec = runCatching {
            ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)?.lastUpdateTime ?: 0L
        }.getOrDefault(0L) / 1000L
        return Shizuku.UserServiceArgs(comp)
            .version(lastUpdateSec.toInt())
            .daemon(false)
            .processNameSuffix("shizuku")
    }

    private fun createConnection(): ServiceConnection {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val raw = binder ?: return
                userService = IShizukuUserService.Stub.asInterface(raw)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                userService = null
            }
        }
        connection = conn
        return conn
    }

    private fun unbind() {
        runCatching {
            val args = serviceArgs
            val conn = connection
            if (args != null && conn != null) Shizuku.unbindUserService(args, conn, true)
        }
        connection = null
        userService = null
        serviceArgs = null
    }

    private fun respond(res: File, id: String, ok: Boolean, message: String) {
        val body = JSONObject().put("ok", ok).put("message", message).toString()
        runCatching {
            val tmp = File(res, "$id.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(res, "$id.json"))   // 原子，脚本不会读到半截
        }
    }
}
