package com.codetool.app.shizuku

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku user-service 实现：在 shell（adb，uid 2000）身份下执行命令并回收输出。
 *
 * 由 Shizuku 通过 AIDL 绑定启动（见 ShizukuBridge.bind），运行在 shell 身份的
 * 独立进程里，所以能做截屏、dumpsys、uiautomator 等 adb 才能做的事，无需 root。
 */
class ShizukuUserService : IShizukuUserService.Stub() {

    override fun exec(cmd: String): String {
        return try {
            // 经 sh -c 执行，让管道/重定向可用；外部 timeout 兜底防止命令挂死。
            val argv = arrayOf("/system/bin/sh", "-c", "timeout 30 " + cmd)
            val pb = ProcessBuilder(*argv).redirectErrorStream(true)
            val p = pb.start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
            val code = p.waitFor()
            "exit=$code\n${sb.toString().trimEnd()}"
        } catch (e: Exception) {
            "exit=-1\n执行异常：${e.message}"
        }
    }
}
