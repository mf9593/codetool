package com.codetool.app.ai

import android.content.Context
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import com.codetool.app.util.FileLog
import java.io.File

/**
 * AI 环境检查与自愈（装 python3 + 注入 codetool-mcp 桥），供：
 * - 首次启动第三引导页（EnviSetupActivity）：安装环境后 MCP 才能跑；
 * - 包管理「一键修复环境」：环境坏了点一下自愈。
 *
 * 背景（真实踩坑）：内置 MCP server `codetool-browser` 靠容器内
 * `/usr/local/bin/codetool-mcp`（shebang `#!/usr/bin/env python3`）启动，
 * 但 rootfs 基础镜像**没装 python3**（/usr/bin/python3 不存在）→ MCP 起不来、
 * browser_* / shizuku_* 工具全不可见。所以安装环境 = 在容器内
 * `apt-get install python3`（tawcroot 模拟 root，可写系统目录）+ 确保桥脚本注入。
 */
object AiEnvHelper {

    private const val TAG = "AiEnvHelper"
    private const val MCP_SCRIPT = "/usr/local/bin/codetool-mcp"
    private const val PYTHON_CHECK = "command -v python3 || ls /usr/bin/python3"

    data class Status(
        val rootfsReady: Boolean,
        val python3Installed: Boolean,
        val mcpScriptOk: Boolean,
        val mcpConfigured: Boolean,
        val pythonPath: String = "",
    ) {
        val allOk: Boolean get() = rootfsReady && python3Installed && mcpScriptOk && mcpConfigured
    }

    private fun rootfs(ctx: Context): File? =
        runCatching { RootfsManager.get(ctx.applicationContext).containerRoot }.getOrNull()

    private fun marker(ctx: Context): File? =
        rootfs(ctx)?.let { File(it, ".codetool_ready") }

    fun rootfsReady(ctx: Context): Boolean = (marker(ctx)?.exists() == true)

    /** 检查环境：rootfs / python3 / mcp 脚本 / mcp 配置。全部轻量读文件，不跑耗时命令。 */
    fun check(ctx: Context): Status {
        val rt = rootfs(ctx)
        val ready = rootfsReady(ctx)
        val python = ready && File(rt!!, "usr/bin/python3").exists()
        val mcp = ready && File(rt, MCP_SCRIPT.removePrefix("/")).exists()
        val configured = runCatching { McpManager.listServers(ctx).any { it.name == McpManager.BUILTIN_NAME } }
            .getOrDefault(false)
        return Status(
            rootfsReady = ready,
            python3Installed = python,
            mcpScriptOk = mcp,
            mcpConfigured = configured,
        )
    }

    /**
     * 一键安装环境（阻塞，需在后台线程）：确保 rootfs 就绪 → apt 装 python3 →
     * 注入 MCP 桥脚本 + 注册内置 MCP 配置。
     * @param onLog 进度/日志回调（主线程之外调用，回调方自行切线程）
     * @return 是否成功（全绿）
     */
    fun ensure(ctx: Context, onLog: (String) -> Unit = {}): Boolean {
        val rt = rootfs(ctx)
        if (rt == null) { onLog("rootfs 不可用"); return false }

        // 1. rootfs 未解压则解压（内部幂等：marker 存在即跳过）
        if (!rootfsReady(ctx)) {
            onLog("正在解压 rootfs（首次约数十秒）…")
            return try {
                RootfsManager.get(ctx.applicationContext).ensureExtractedSync()
                onLog("rootfs 就绪")
                true
            } catch (t: Throwable) {
                onLog("rootfs 解压失败：${t.message}")
                FileLog.e(ctx, TAG, "rootfs extract failed", t)
                false
            }
        }

        // 2. apt 装 python3（幂等：command -v 已有则跳）
        if (!File(rt, "usr/bin/python3").exists()) {
            onLog("容器缺 python3，正在安装（apt-get update + install，约 1-2 分钟）…")
            val okUpdate = runInContainer(ctx, rt, "sh", "-c",
                "apt-get update -qq >/dev/null 2>&1 || true")
            if (!okUpdate.first) onLog("apt update 警告：${okUpdate.second.take(120)}")
            val okInstall = runInContainer(ctx, rt, "sh", "-c",
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3")
            if (!okInstall.first) {
                onLog("python3 安装失败：${okInstall.second.take(300)}")
                FileLog.e(ctx, TAG, "apt install python3 failed: ${okInstall.second.take(500)}")
                return false
            }
            onLog("python3 安装完成")
        } else onLog("python3 已存在")

        // 3. 注入 MCP 桥脚本（ensureGuiBridge 单一来源，内容变化自动覆盖）
        runCatching { TawcrootRuntime.get(ctx).ensureGuiBridgePublic(rt) }
            .onFailure { FileLog.e(ctx, TAG, "ensureGuiBridge failed", it) }

        // 4. 注册内置 MCP（幂等）
        onLog("注册内置 MCP server…")
        McpManager.ensureBuiltin(ctx)

        // 5. 复核
        val st = check(ctx)
        if (!st.allOk) {
            onLog("环境仍不完整：python3=${st.python3Installed} mcp脚本=${st.mcpScriptOk} 配置=${st.mcpConfigured}")
            return false
        }
        onLog("AI 环境就绪 ✓")
        return true
    }

    /** 在容器内跑一条命令，返回 (exitOk, 输出)。 */
    private fun runInContainer(ctx: Context, rootfs: File, vararg cmd: String): Pair<Boolean, String> {
        val rt = TawcrootRuntime.get(ctx)
        return try {
            val proc = rt.exec(rootfs, *cmd)
            val out = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor() == 0
            ok to out.trim()
        } catch (t: Throwable) {
            false to (t.message ?: "exec 异常")
        }
    }
}