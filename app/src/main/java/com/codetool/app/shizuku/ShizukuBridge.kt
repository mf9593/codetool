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

    /** 请求/响应目录（Android 侧真实路径；容器里对应 /usr/share/tawc/shizuku/...）。 */
    fun dirs(ctx: Context): Pair<File, File> {
        val base = File(ctx.applicationContext.dataDir, "share/shizuku")
        return File(base, "requests").apply { mkdirs() } to
            File(base, "responses").apply { mkdirs() }
    }

    /** 启动监听。常驻，App 进程存活期间一直监听。 */
    fun start(ctx: Context) {
        if (observer != null) return
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
            else -> respond(false, "未知操作：$action")
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
        return try {
            val args = userServiceArgs()
            serviceArgs = args
            Shizuku.bindUserService(args, createConnection())
            // onServiceConnected 在主线程回调，这里轮询等绑定完成（最多 3s）
            var i = 0
            while (userService == null && i < 30) {
                Thread.sleep(100); i++
            }
            userService
        } catch (e: Exception) {
            null
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val comp = ComponentName("com.codetool.app", "com.codetool.app.shizuku.ShizukuUserService")
        return Shizuku.UserServiceArgs(comp)
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
