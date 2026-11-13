package com.codetool.app.debug

import android.app.Activity
import android.os.Bundle
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import java.io.File

/**
 * 仅 debug 构建：`adb shell am start -n com.codetool.app/.debug.RunCmdActivity --es cmd "..."`，
 * 在 app 进程（有网络）里通过 tawcroot 执行命令，输出写到 files/debug-cmd.out。
 * 用于自动化安装/排查，release 包不注册此 Activity。
 */
class RunCmdActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 优先 cmd_b64（base64，避免 adb shell 引号剥离/特殊字符问题）；否则 cmd
        val b64 = intent.getStringExtra("cmd_b64")
        val cmd = if (!b64.isNullOrBlank()) {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        } else {
            intent.getStringExtra("cmd").orEmpty()
        }
        val outFile = File(filesDir, "debug-cmd.out")
        if (cmd.isBlank()) {
            outFile.writeText("empty cmd\n")
            finish()
            return
        }
        Thread {
            val sb = StringBuilder()
            sb.append("$ ").append(cmd).append('\n')
            try {
                if (cmd == "--query") {
                    sb.append("[running]=").append(
                        runCatching { me.phie.tawc.compositor.NativeBridge.nativeIsCompositorRunning() }
                            .getOrElse { "err:$it" }
                    ).append('\n')
                    sb.append("[state]=").append(
                        runCatching { me.phie.tawc.compositor.NativeBridge.nativeQueryState() }
                            .getOrElse { "err:$it" }
                    ).append('\n')
                } else {
                    val rootfs = RootfsManager.get(this).ensureExtractedSync()
                    val p = TawcrootRuntime.get(this).exec(rootfs, "/bin/bash", "-lc", cmd)
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                        }
                    }
                    val code = p.waitFor()
                    sb.append("\n[exit=").append(code).append("]\n")
                }
            } catch (e: Throwable) {
                sb.append("\n[error] ").append(e).append('\n')
            }
            outFile.writeText(sb.toString())
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // ★ Android 17 ActivityController：finish() 必须在 super.onResume() 内同步调用，
        //   否则异步调用会在 resume 回调里抛 IllegalStateException（"did not call finish() prior to onResume()"）。
        finish()
    }
}
