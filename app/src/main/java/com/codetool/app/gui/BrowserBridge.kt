package com.codetool.app.gui

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/**
 * 容器内 → app 的浏览器桥。
 *
 * 复用 [com.codetool.app.runtime.TawcrootRuntime] 已有的 bind mount：
 * `dataDir/share` ↔ 容器 `/usr/share/tawc`。容器脚本
 * `/usr/local/bin/codetool-browser` 把请求 JSON 原子写入
 * `share/browser/requests/<id>.json`；这里用 FileObserver 收到后回调，
 * 结果写 `share/browser/responses/<id>.json` 让脚本轮询读到。
 *
 * 只做「请求转发 + 响应回写」，不含任何协议解析轮子；调试走标准 CDP。
 * 桥随合成器 Activity 启停（GUI 在跑才有地方渲染窗口）。
 */
object BrowserBridge {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var handler: ((JSONObject, (Boolean, String) -> Unit) -> Unit)? = null

    private var observer: FileObserver? = null

    /** 请求目录 / 响应目录（Android 侧真实路径；容器里对应 /usr/share/tawc/browser/...）。 */
    fun dirs(ctx: Context): Pair<File, File> {
        val base = File(ctx.applicationContext.dataDir, "share/browser")
        return File(base, "requests").apply { mkdirs() } to
            File(base, "responses").apply { mkdirs() }
    }

    /**
     * 启动监听。[onRequest] 在主线程回调，第二个参数是「回响应」函数，
     * 处理完必须调用一次（脚本在等，最多 20s）。
     */
    fun start(ctx: Context, onRequest: (JSONObject, (Boolean, String) -> Unit) -> Unit) {
        stop()
        val (req, res) = dirs(ctx)
        handler = onRequest
        // 清掉上次残留的过期响应，避免新请求误读
        res.listFiles()?.forEach {
            if (it.isFile && it.lastModified() < System.currentTimeMillis() - 60_000) it.delete()
        }
        // String 构造在 minSdk 26 也可用（File 构造需 API 29）
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
                main.post {
                    val respond: (Boolean, String) -> Unit = { ok, msg -> respond(res, id, ok, msg) }
                    runCatching { handler?.invoke(obj, respond) }
                        .onFailure { respond(false, "处理失败：${it.message}") }
                }
            }
        }.also { it.startWatching() }
    }

    private fun respond(res: File, id: String, ok: Boolean, message: String) {
        val body = JSONObject().put("ok", ok).put("message", message).toString()
        runCatching {
            val tmp = File(res, "$id.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(res, "$id.json"))   // 原子，脚本不会读到半截
        }
    }

    fun stop() {
        runCatching { observer?.stopWatching() }
        observer = null
        handler = null
    }
}
