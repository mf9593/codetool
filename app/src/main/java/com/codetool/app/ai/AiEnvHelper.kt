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
 * 背景（真实踩坑）：内置 MCP server 靠容器内 `/usr/local/bin/codetool-mcp`
 * 与 `/usr/local/bin/codetool-android-mcp`（shebang `#!/usr/bin/env python3`）启动，
 * 但 rootfs 基础镜像**没装 python3**（/usr/bin/python3 不存在）→ MCP 起不来、
 * browser_* / android_* / shizuku_* 工具全不可见。所以安装环境 = 在容器内
 * `apt-get install python3`（tawcroot 模拟 root，可写系统目录）+ 确保桥脚本注入。
 */
object AiEnvHelper {

    private const val TAG = "AiEnvHelper"
    private val MCP_SCRIPTS = listOf(
        "/usr/local/bin/codetool-mcp",
        "/usr/local/bin/codetool-android-mcp",
    )

    /**
     * shell 片段：CLI（mimo / opencode）安装完成后检测 python3，缺则提示去环境修复。
     * 注意：**不在这里 apt 装 python3**——apt 更新/安装统一归「一键修复 AI 环境」
     * （[ensure]），CLI 安装脚本只负责装 CLI 本体 + 自动补 curl/npm 等下载依赖。
     */
    const val PY3_CHECK_SCRIPT =
        "command -v python3 >/dev/null 2>&1 || " +
            "echo '[提示] 容器缺 python3：内置 MCP（浏览器 / Android 自动化）暂不可用，请到 工具 → 包管理 点「一键修复 AI 环境」安装'"

    data class Status(
        val rootfsReady: Boolean,
        val python3Installed: Boolean,
        val mcpScriptOk: Boolean,
        val mcpConfigured: Boolean,
        /** python3 真实版本（如 "3.12"），来自文件读，非 exec 探测。 */
        val pythonVersion: String = "",
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
        val mcp = ready && MCP_SCRIPTS.all { File(rt, it.removePrefix("/")).exists() }
        val configured = runCatching {
            val names = McpManager.listServers(ctx).map { it.name }.toSet()
            McpManager.BUILTINS.all { it.name in names }
        }.getOrDefault(false)
        return Status(
            rootfsReady = ready,
            python3Installed = python,
            mcpScriptOk = mcp,
            mcpConfigured = configured,
            pythonVersion = if (python) python3Version(rt).orEmpty() else "",
        )
    }

    /**
     * python3 版本号（纯文件读，不起进程）：Ubuntu/Debian 在 usr/lib/python3.X 挂版本目录，
     * usr/bin/python3 -> python3.X 是相对符号链接，File.name 拿不到，故扫 lib 目录。
     */
    private fun python3Version(rt: File): String? {
        val lib = File(rt, "usr/lib")
        val dirs = lib.listFiles { f -> f.isDirectory && Regex("python3\\.\\d+").matches(f.name) }
            ?: return null
        return dirs.maxByOrNull { it.name }?.name?.removePrefix("python")
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

        // 1. rootfs 未解压则解压（内部幂等：marker 存在即跳过）；解压完继续装 python3，
        //    不能解压完就返回（否则 python3 仍缺，却报「环境就绪」）。
        if (!rootfsReady(ctx)) {
            onLog("正在解压 rootfs（首次约数十秒）…")
            try {
                RootfsManager.get(ctx.applicationContext).ensureExtractedSync()
                onLog("rootfs 就绪")
            } catch (t: Throwable) {
                onLog("rootfs 解压失败：${t.message}")
                FileLog.e(ctx, TAG, "rootfs extract failed", t)
                return false
            }
        }

        // 1.5 老版本解压的 rootfs 可能缺 resolv.conf（当时没有 DNS 配置逻辑），
        //     apt 全靠域名解析，缺了必失败。幂等：存在即跳过，内容与 RootfsManager.setupContainer 一致。
        val resolv = File(rt, "etc/resolv.conf")
        if (!resolv.exists()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")
            onLog("已补写 DNS 配置（rootfs 缺 resolv.conf）")
        }

        // 1.7 应用配置的 apt 源（默认清华；官方源在国内易被 DNS 污染导致 apt 挂死）。
        //    在 apt 前写 sources.list，确保安装用配置的镜像。
        AptMirror.apply(ctx, rt)
        onLog("apt 源：${AptMirror.label(AptMirror.currentId(ctx))}")

        // 2. apt 装 python3（幂等：已存在则跳过）。
        //    注意：tawcroot 不做 PATH 查找，guest 可执行文件必须传绝对路径（/bin/sh），
        //    传 "sh" 会 ENOENT（errno=-2）——踩过的坑。
        if (!File(rt, "usr/bin/python3").exists()) {
            onLog("容器缺 python3，正在安装（apt-get update + install，约 1-2 分钟）…")
            val okUpdate = runInContainer(ctx, rt, onLog, "/bin/sh", "-c",
                "apt-get update -qq >/dev/null 2>&1 || true")
            if (!okUpdate.first) onLog("apt update 警告：${okUpdate.second.take(120)}")
            val okInstall = runInContainer(ctx, rt, onLog, "/bin/sh", "-c",
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

    /**
     * 在容器内跑一条命令，返回 (exitOk, 输出)。输出**逐行流式**推给 [onLine]，
     * 让安装卡片实时显示 apt 等命令的真实终端内容（之前只回传状态文案，
     * 用户看不到输出）。
     * cmd[0] 必须是容器内**绝对路径**（如 /bin/sh、/usr/bin/apt-get）：
     * tawcroot 不做 PATH 查找，短名会 ENOENT。
     */
    private fun runInContainer(
        ctx: Context,
        rootfs: File,
        onLine: (String) -> Unit,
        vararg cmd: String
    ): Pair<Boolean, String> {
        val rt = TawcrootRuntime.get(ctx)
        return try {
            val proc = rt.exec(rootfs, *cmd)
            val out = StringBuilder()
            // 逐行读（exec 已 redirectErrorStream(true)，stdout+stderr 合并）：
            // apt 下载进度用 \r 重写不换行，按 \r 分段；纯进度行（如 "22% [..]"）跳过。
            proc.inputStream.bufferedReader().forEachLine { raw ->
                raw.split('\r').forEach { seg ->
                    val s = seg.trim()
                    if (s.isNotBlank() && !s.matches(PROGRESS_LINE)) {
                        out.appendLine(s)
                        onLine(s)
                    }
                }
            }
            val ok = proc.waitFor() == 0
            ok to out.toString().trim()
        } catch (t: Throwable) {
            false to (t.message ?: "exec 异常")
        }
    }

    /** apt 下载/安装进度行（如 "22% [1 python3 22.4 kB/22.4 kB 100%]"），日志里过滤掉。 */
    private val PROGRESS_LINE = Regex("^\\d+%.*")
}