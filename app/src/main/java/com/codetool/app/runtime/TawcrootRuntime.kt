package com.codetool.app.runtime

import android.content.Context
import android.util.Log
import com.codetool.app.util.AppSettings
import java.io.File

/**
 * 管理 libtawcroot.so 的解压与调用。
 *
 * libtawcroot.so 是 tawcroot 项目编译产物（ET_EXEC 伪装成 .so 以便 jniLibs 打包）。
 * 它不是标准 JNI 库，而是一个可执行文件，通过 Process 启动：
 *   libtawcroot.so -r <rootfs> -b <src:dst>... -- <cmd>...
 *
 * 编译方式：clone https://github.com/taowen/tawc，执行 tawcroot/build.sh --abi=aarch64
 * 产物：app/src/main/jniLibs/arm64-v8a/libtawcroot.so
 *
 * 若 APK 已通过 jniLibs 打包，则无需解压，直接用 nativeLibraryDir 路径。
 * 这里同时支持「打包」和「运行时解压」两种模式。
 */
class TawcrootRuntime private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TawcrootRuntime"
        private const val SO_NAME = "libtawcroot.so"
        @Volatile private var INSTANCE: TawcrootRuntime? = null
        fun get(context: Context): TawcrootRuntime =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TawcrootRuntime(context.applicationContext).also { INSTANCE = it }
            }
    }

    /** libtawcroot.so 的可执行路径。优先用 jniLibs 里的（打包模式）。 */
    val binaryPath: String by lazy {
        // 1. 先看 jniLibs（APK 打包模式，最常见）
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val packaged = File(nativeDir, SO_NAME)
        if (packaged.exists() && packaged.canExecute()) {
            Log.i(TAG, "using packaged tawcroot: $packaged")
            return@lazy packaged.absolutePath
        }
        // 2. 否则用运行时解压目录（见 ensureExtracted）
        val extracted = File(context.filesDir, "tawcroot/$SO_NAME")
        extracted.absolutePath
    }

    /** 确保 libtawcroot.so 可执行。优先用 jniLibs 打包的（nativeLibraryDir），
     *  assets 里没有 libtawcroot.so（在 lib/ 不在 assets），所以不要从 assets 读。 */
    fun ensureExtracted(): File {
        // 1. nativeLibraryDir（jniLibs 打包，最常见）
        val packaged = File(context.applicationInfo.nativeLibraryDir, SO_NAME)
        if (packaged.exists() && packaged.canExecute()) {
            Log.i(TAG, "tawcroot at nativeLibraryDir: $packaged")
            return packaged
        }
        // 2. 已有解压副本
        val out = File(context.filesDir, "tawcroot/$SO_NAME")
        if (out.exists() && out.canExecute()) return out
        // 3. 尝试从 assets 解压（仅当确实打包在 assets 时）
        return try {
            out.parentFile?.mkdirs()
            context.assets.open(SO_NAME).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.setExecutable(true)
            Log.i(TAG, "extracted tawcroot from assets to $out")
            out
        } catch (e: Exception) {
            Log.w(TAG, "assets has no $SO_NAME (jniLibs mode), fallback: $packaged")
            packaged
        }
    }

    /**
     * 构造启动命令的字符串数组，供 TerminalSession 使用。
     *
     *   tawcroot -r <rootfs> \
     *     -b /proc:/proc \           # proc 视图
     *     -b /sys:/sys \             # sys 视图
     *     -b /dev:/dev \             # 设备
     *     -- /bin/bash -l
     *
     * 注意：不绑定 /sdcard —— 在 App 上下文绑定 FUSE /sdcard 会失败导致 tawcroot 退出。
     * 不传 --host-user：tawcroot 伪装 root（uid=0），apt/pip/npm 才可写系统目录。
     */
    fun buildLaunchCommand(rootfsDir: File): Array<String> {
        ensureGuiBridge(rootfsDir)
        // Android 共享存储挂载：按终端设置里的「挂载目录」选项决定挂什么。
        // 默认仅 Download（最小暴露）；可选整个共享存储或不挂载。路径保持一致，
        // 用户填 /storage/emulated/0/Download/... 在容器里同样可用。
        val storageBind = when (AppSettings.mountMode(context)) {
            AppSettings.MountMode.FULL -> "/storage/emulated/0:/storage/emulated/0"
            AppSettings.MountMode.DOWNLOAD -> "/storage/emulated/0/Download:/storage/emulated/0/Download"
            else -> null  // NONE：不挂载
        }
        val binds = buildList {
            add("/proc:/proc")
            add("/sys:/sys")
            add("/dev:/dev")
            add(shareDir().absolutePath + ":/usr/share/tawc")
            if (storageBind != null) add(storageBind)
        }
        return buildList {
            add(binaryPath)
            add("-r"); add(rootfsDir.absolutePath)
            for (b in binds) { add("-b"); add(b) }
            add("--")
            add("/bin/bash"); add("-l")
        }.toTypedArray()
    }

    /** 直接用 Process 启动一个命令（用于脚本执行、包管理等非交互场景）。 */
    fun exec(rootfsDir: File, vararg cmd: String): Process {
        ensureGuiBridge(rootfsDir)
        val argv = buildList {
            add(binaryPath)
            add("-r"); add(rootfsDir.absolutePath)
            add("-b"); add("/proc:/proc")
            add("-b"); add("/dev:/dev")
            add("-b"); add(shareDir().absolutePath + ":/usr/share/tawc")
            add("--")
            addAll(cmd)
        }.toTypedArray()
        val pb = ProcessBuilder(*argv)
            .directory(rootfsDir)   // tawcroot chroot 后 cwd 必须是容器根，否则 getcwd 失败
            .redirectErrorStream(true)
        // 与终端会话保持一致的容器环境（App 进程继承的是 Android 的 PATH）
        pb.environment().apply {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            // 统一北京时间（UTC+8），与 AI 模型定时切换口径一致
            put("TZ", "Asia/Shanghai")
            // 合成器在 App 进程 setenv 了 XKB_CONFIG_ROOT 指向 App 私有目录，
            // chroot 内不可见 → xkbcommon 加载失败 → GUI 客户端（spark-store 等）段错误。
            // 容器内直接用发行版自带的 /usr/share/X11/xkb，必须移除该变量。
            remove("XKB_CONFIG_ROOT")
            remove("TAWC_XKB_CONFIG_ROOT")
        }
        return pb.start()
    }

    /** compositor 的 Wayland socket 所在目录（Android 侧）。 */
    private fun shareDir(): File = File(context.dataDir, "share").apply { mkdirs() }

    /**
     * 容器内 GUI 桥：
     * - 把 Android 侧 share 目录 bind 到 /usr/share/tawc（above）；
     * - 写入登录 shell 环境，使容器里启动的 Wayland 客户端能找到合成器 socket。
     */
    private fun ensureGuiBridge(rootfsDir: File) {
        try {
            val share = File(rootfsDir, "usr/share/tawc").apply { mkdirs() }
            // 浏览器桥挂载点（真实目录由 BrowserBridge 在 Android 侧 dataDir/share 下创建）
            File(share, "browser").mkdirs()
            // Shizuku 桥挂载点（真实目录由 ShizukuBridge 在 Android 侧 dataDir/share 下创建）
            File(share, "shizuku").mkdirs()
            // 应用日志镜像目录（Android 侧 dataDir/share/logs），容器内模型可直接读
            File(share, "logs").mkdirs()
            File(shareDir(), "logs/crash").mkdirs()

            // 日志 MCP 已下线：清理旧版注入的脚本、Token 与运行文件。
            // 共享日志目录保留，容器内模型直接读 /usr/share/tawc/logs/codetool.log。
            runCatching {
                File(rootfsDir, "usr/local/bin/codetool-logs-mcp").delete()
                File(rootfsDir, "root/.config/codetool/log-mcp-token").delete()
                val pidFile = File(shareDir(), "logs/log-mcp.pid")
                val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
                if (pid != null && pid > 0) {
                    val cmdline = runCatching {
                        File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8)
                    }.getOrNull().orEmpty()
                    if (cmdline.contains("codetool-logs-mcp")) android.os.Process.killProcess(pid)
                }
                pidFile.delete()
                File(shareDir(), "logs/log-mcp.stop").delete()
            }

            val profileDir = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
            val profile = File(profileDir, "codetool-wayland.sh")
            // 显式把 /usr/local/bin 放进 PATH：codetool-browser 装在那里，
            // 终端会话默认 PATH 不一定包含它。
            val content = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export XDG_RUNTIME_DIR=/tmp\n" +
                "export WAYLAND_DISPLAY=/usr/share/tawc/wayland-0\n" +
                // 统一北京时间（UTC+8）：终端 date、CLI、脚本与 AI 模型定时切换口径一致
                "export TZ=Asia/Shanghai\n"
            if (!profile.exists() || profile.readText() != content) {
                profile.writeText(content)
            }

            // 时区校准：容器内 /etc/timezone + /etc/localtime 指向北京时间，
            // 避免 Ubuntu 默认 UTC 导致 date / 日志 / 高峰闲时判断全部差 8 小时。
            runCatching {
                val tz = "Asia/Shanghai\n"
                val tzFile = File(rootfsDir, "etc/timezone")
                if (!tzFile.isFile || tzFile.readText() != tz) tzFile.writeText(tz)
                val zone = File(rootfsDir, "usr/share/zoneinfo/Asia/Shanghai")
                val localtime = File(rootfsDir, "etc/localtime")
                if (zone.isFile) {
                    // 链接目标必须是「容器内绝对路径」，chroot 后按容器根解析
                    val target = "/usr/share/zoneinfo/Asia/Shanghai"
                    val ok = localtime.exists() && java.nio.file.Files.isSymbolicLink(localtime.toPath()) &&
                        runCatching {
                            java.nio.file.Files.readSymbolicLink(localtime.toPath()).toString()
                        }.getOrNull() == target
                    if (!ok) {
                        localtime.delete()
                        java.nio.file.Files.createSymbolicLink(
                            localtime.toPath(), java.nio.file.Paths.get(target))
                    }
                }
            }

            // 注入 codetool-browser：容器内 CLI / AI 模型调用 app 内置浏览器的统一入口。
            // 单一来源在 assets/scripts/codetool-browser.sh，内容变化会自动覆盖旧版。
            val browser = File(rootfsDir, "usr/local/bin/codetool-browser")
            val script = runCatching {
                context.assets.open("scripts/codetool-browser.sh").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (script != null && (!browser.exists() || browser.readText() != script)) {
                browser.parentFile?.mkdirs()
                browser.writeText(script)
                browser.setExecutable(true, false)
            }

            // 注入 codetool-mcp：mimo / opencode 通过 MCP 调用浏览器 / Android 自动化的 stdio server。
            // 单一来源 assets/scripts/codetool-mcp.py，内容变化自动覆盖。
            val mcp = File(rootfsDir, "usr/local/bin/codetool-mcp")
            val mcpScript = runCatching {
                context.assets.open("scripts/codetool-mcp.py").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (mcpScript != null && (!mcp.exists() || mcp.readText() != mcpScript)) {
                mcp.parentFile?.mkdirs()
                mcp.writeText(mcpScript)
                mcp.setExecutable(true, false)
            }

            // 注入 codetool-android-mcp：结构化 Android UI 自动化的 stdio server。
            // 底层复用 codetool-shizuku，不重复实现 Shizuku 文件桥。
            val androidMcp = File(rootfsDir, "usr/local/bin/codetool-android-mcp")
            val androidMcpScript = runCatching {
                context.assets.open("scripts/codetool-android-mcp.py").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (androidMcpScript != null && (!androidMcp.exists() || androidMcp.readText() != androidMcpScript)) {
                androidMcp.parentFile?.mkdirs()
                androidMcp.writeText(androidMcpScript)
                androidMcp.setExecutable(true, false)
            }

            // 注入 codetool-shizuku：容器内 CLI / AI 模型以 shell 身份调试 App 的统一入口。
            // 单一来源 assets/scripts/codetool-shizuku.sh，内容变化自动覆盖。
            val shizuku = File(rootfsDir, "usr/local/bin/codetool-shizuku")
            val shizukuScript = runCatching {
                context.assets.open("scripts/codetool-shizuku.sh").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (shizukuScript != null && (!shizuku.exists() || shizuku.readText() != shizukuScript)) {
                shizuku.parentFile?.mkdirs()
                shizuku.writeText(shizukuScript)
                shizuku.setExecutable(true, false)
            }

            // 注入中国法定节假日表：app 内定时切换与 MCP 的 model_schedule_* 共用同一份，
            // 单一来源 assets/scripts/model-holidays.json（2025–2027，来源 chinese-days）。
            val holidays = File(rootfsDir, "root/.config/codetool/holidays.json")
            val holidaysJson = runCatching {
                context.assets.open("scripts/model-holidays.json").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (holidaysJson != null && (!holidays.exists() || holidays.readText() != holidaysJson)) {
                holidays.parentFile?.mkdirs()
                holidays.writeText(holidaysJson)
            }

            // 注入 pm：容器里没有 Android 的 /system/bin/pm（rootfs 是 Ubuntu），
            // wrapper 把 pm <args> 转发到 codetool-shizuku 以 shell 身份执行真实 pm。
            // 单一来源 assets/scripts/pm.sh，内容变化自动覆盖。
            val pm = File(rootfsDir, "usr/local/bin/pm")
            val pmScript = runCatching {
                context.assets.open("scripts/pm.sh").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (pmScript != null && (!pm.exists() || pm.readText() != pmScript)) {
                pm.parentFile?.mkdirs()
                pm.writeText(pmScript)
                pm.setExecutable(true, false)
            }

            // 模型能力「默认就绪」：容器一启动就把内置 skill 与 MCP server 写进 CLI
            // 默认扫描路径，不依赖用户先进工具页。两个 ensureBuiltin 都幂等。
            runCatching {
                com.codetool.app.ai.SkillManager.ensureBuiltin(context)
                com.codetool.app.ai.McpManager.ensureBuiltin(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGuiBridge failed: ${e.message}")
        }
    }

    /**
     * 公开的桥注入入口：给「AI 环境自愈 / 首次引导安装环境 / 包管理一键修复」用，
     * 显式重新写入浏览器 / MCP / Shizuku 桥脚本与内置 skill / MCP 配置（幂等）。
     */
    fun ensureGuiBridgePublic(rootfsDir: File) = ensureGuiBridge(rootfsDir)
}
