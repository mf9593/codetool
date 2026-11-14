package com.codetool.app.runtime

import android.content.Context
import android.util.Log
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
        val binds = listOf(
            "/proc:/proc",
            "/sys:/sys",
            "/dev:/dev",
            shareDir().absolutePath + ":/usr/share/tawc"
        )
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

            val profileDir = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
            val profile = File(profileDir, "codetool-wayland.sh")
            // 显式把 /usr/local/bin 放进 PATH：codetool-browser 装在那里，
            // 终端会话默认 PATH 不一定包含它。
            val content = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                "export XDG_RUNTIME_DIR=/tmp\n" +
                "export WAYLAND_DISPLAY=/usr/share/tawc/wayland-0\n"
            if (!profile.exists() || profile.readText() != content) {
                profile.writeText(content)
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

            // 注入 codetool-mcp：mimo / opencode 通过 MCP 调用内置浏览器的 stdio server。
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
}
