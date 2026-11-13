package com.codetool.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/**
 * 管理 Ubuntu rootfs 的解压与定位。
 *
 * rootfs 以 noble-server-cloudimg-arm64-root.tar.xz 形式打包进 assets。
 * 首次运行解压到 filesDir/rootfs/，之后直接复用。
 *
 * 解压是耗时操作（209M xz，解压后约 700M+），必须在后台线程做，
 * 并通过 [State] 暴露进度给 UI。
 */
class RootfsManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        // pd 镜像（25M，proot-distro 格式但 tawcroot 可用；解压后 ~133M）
        private const val ASSET_NAME = "ubuntu-noble-aarch64-pd-v4.17.3.tar.xz"
        private const val ROOTFS_DIR_NAME = "rootfs"

        @Volatile private var INSTANCE: RootfsManager? = null
        fun get(context: Context): RootfsManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RootfsManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    sealed class State {
        object NotExtracted : State()
        data class Extracting(val percent: Int) : State()
        data class Ready(val dir: File) : State()
        data class Failed(val error: String) : State()
    }

    @Volatile var state: State = State.NotExtracted
        private set

    /** 解压目标目录（外层，tar 解到这）。 */
    val rootfsDir: File = File(context.filesDir, ROOTFS_DIR_NAME)

    /** 真正的 Linux 根目录：pd 镜像根在顶层目录 ubuntu-noble-aarch64/ 下。 */
    val containerRoot: File
        get() = File(rootfsDir, "ubuntu-noble-aarch64")

    private val marker: File
        get() = File(containerRoot, ".codetool_ready")

    /** 同步解压（阻塞，仅在确认未解压时调用）。后台线程调用。
     *  [onProgress] 在后台线程回调，percent ∈ [0,100]。 */
    fun ensureExtractedSync(onProgress: ((Int) -> Unit)? = null): File {
        if (marker.exists()) {
            state = State.Ready(containerRoot)
            return containerRoot
        }
        synchronized(this) {
            if (marker.exists()) {
                state = State.Ready(containerRoot)
                return containerRoot
            }
            return try {
                state = State.Extracting(0)
                onProgress?.invoke(0)
                Log.i(TAG, "extracting rootfs from assets...")
                // 清掉上次失败残留，重新解
                rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                // 先取 asset 总大小（noCompress xz → openFd 可用）
                val total = try {
                    context.assets.openFd(ASSET_NAME).use { it.length }
                } catch (e: Exception) {
                    -1L
                }
                context.assets.open(ASSET_NAME).use { input ->
                    // toybox tar 不支持 --strip-components，且 tar -J 需外部 xz（App 内无）。
                    // 方案：纯 Java XZ 解压 → 管道给 tar -x（无 -J）
                    // mknod 设备节点报错非致命（tawcroot 会绑定 /dev），tar 继续解包
                    val pb = ProcessBuilder(
                        "tar", "-x", "-C", rootfsDir.absolutePath
                    ).redirectErrorStream(true)
                    val proc = pb.start()
                    val counting = CountingInputStream(input, total) { pct ->
                        onProgress?.invoke(pct)
                        state = State.Extracting(pct)
                    }
                    val xz = XZInputStream(counting)
                    try {
                        xz.copyTo(proc.outputStream)
                    } catch (e: java.io.IOException) {
                        // EPIPE：tar 早退。仍校验核心文件决定成败。
                        Log.w(TAG, "pipe broke: ${e.message}")
                    }
                    runCatching { xz.close() }
                    counting.close()
                    runCatching { proc.outputStream.close() }
                    proc.waitFor()
                    // 校验核心文件而非退出码（mknod 错误会导致非零退出，但内容已解包）
                    if (!File(containerRoot, "bin/bash").exists()) {
                        val err = proc.inputStream.bufferedReader().readText()
                        throw RuntimeException("rootfs missing bash: $err")
                    }
                }
                marker.writeText("ok")
                setupContainer(containerRoot)
                state = State.Ready(containerRoot)
                onProgress?.invoke(100)
                Log.i(TAG, "rootfs ready at $containerRoot")
                containerRoot
            } catch (e: Exception) {
                Log.e(TAG, "extract failed", e)
                state = State.Failed(e.message ?: "unknown")
                throw e
            }
        }
    }

    /** 计数输入流：按已读字节数回调百分比。 */
    private class CountingInputStream(
        private val src: InputStream,
        private val total: Long,
        private val onProgress: (Int) -> Unit
    ) : InputStream() {
        private var readBytes = 0L
        private var lastPct = -1
        private val buf = ByteArray(64 * 1024)

        override fun read(): Int {
            val b = src.read()
            if (b >= 0) { readBytes++; report() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) { readBytes += n; report() }
            return n
        }

        private fun report() {
            if (total <= 0) return
            val pct = ((readBytes * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
        }
    }

    /** 解压后容器基础配置：DNS、hosts、目录权限。 */
    private fun setupContainer(root: File) {
        try {
            // Android 无 /etc/resolv.conf，替换为公共 DNS（223.5.5.5 阿里等）
            val etc = File(root, "etc")
            val resolv = File(etc, "resolv.conf")
            if (resolv.exists()) resolv.delete()
            File(etc, "resolv.conf").writeText(
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n"
            )
            val hosts = File(etc, "hosts")
            if (hosts.exists()) hosts.delete()
            File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
            // 常见目录确保存在
            for (d in listOf("root", "tmp", "var/tmp", "run", "dev", "proc", "sys")) {
                File(root, d).mkdirs()
            }
            ensureCjkFont()
            ensureZhLocale()
            Log.i(TAG, "container base setup done")
        } catch (e: Exception) {
            Log.w(TAG, "container setup failed (non-fatal): ${e.message}")
        }
    }

    /**
     * 把设备自带的 CJK 字体复制进 rootfs。
     *
     * rootfs 里只有 DejaVu，没有中文字形，Wayland 客户端（星火商店/Electron）
     * 里的中文会渲染成方块。Android 系统自带 NotoSansCJK，直接复用最省事，
     * 不用往 APK 里再塞一份几十 MB 的字体。
     *
     * 幂等：已经有字体文件就直接返回。解压后和每次启动都会调一次。
     */
    fun ensureCjkFont() {
        try {
            val root = containerRoot
            if (!root.isDirectory) return
            val dstDir = File(root, "usr/share/fonts/truetype/noto-cjk")
            if (dstDir.isDirectory && dstDir.listFiles()?.any { it.length() > 1_000_000 } == true) return

            val candidates = listOf(
                File("/system/fonts/NotoSansCJK-Regular.ttc"),
                File("/system/fonts/NotoSansCJKsc-Regular.otf"),
                File("/system/fonts/NotoSansSC-Regular.otf"),
                File("/system/fonts/DroidSansFallback.ttf"),
            )
            val src = candidates.firstOrNull { it.isFile && it.length() > 100_000 }
            if (src == null) {
                Log.w(TAG, "no CJK font available on device")
                return
            }
            dstDir.mkdirs()
            src.copyTo(File(dstDir, src.name), overwrite = true)

            // fontconfig 显式收录该目录（根目录本身已被默认 fonts.conf 覆盖，
            // 这里再写一条 conf.d，避免某些发行版配置不递归子目录）。
            val confDir = File(root, "etc/fonts/conf.d")
            if (confDir.isDirectory) {
                File(confDir, "99-codetool-cjk.conf").writeText(
                    "<?xml version=\"1.0\"?>\n" +
                        "<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">\n" +
                        "<fontconfig>\n" +
                        "  <dir>/usr/share/fonts/truetype/noto-cjk</dir>\n" +
                        "</fontconfig>\n"
                )
            }
            Log.i(TAG, "CJK font installed: ${src.name} (${src.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "install CJK font failed: ${e.message}")
        }
    }

    /** 是否已解压就绪。 */
    fun isReady(): Boolean = marker.exists()

    /** 删除符号链接或普通文件（避免写入被软链重定向）。 */
    private fun deleteIfLinkOrFile(f: File) {
        runCatching {
            val p = f.toPath()
            if (java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.delete(p)
            }
        }
    }

    /**
     * 预置中文 locale（提前注入文件，不依赖运行时 export LANG）：
     *  - assets/locale/locale-archive（含 zh_CN.utf8，由容器内 localedef 预生成）
     *    → usr/lib/locale/locale-archive
     *  - assets/locale/default-locale → etc/default/locale
     * 容器默认只有 C.utf8，Qt/微信/GTK 按 LANG 回退英文、fontconfig 也可能选错
     * CJK 字形。幂等：文件已存在且大小一致则跳过。
     */
    fun ensureZhLocale() {
        try {
            val root = containerRoot
            if (!root.isDirectory) return

            val localeDir = File(root, "usr/lib/locale").apply { mkdirs() }
            val archive = File(localeDir, "locale-archive")
            val needArchive = !archive.isFile || archive.length() < 1_000_000L
            if (needArchive) {
                // rootfs 里这两个路径可能是软链（tawcroot link / ../locale.conf），
                // 直接 writeText 会写到链接目标且 tawcroot 内看不到。先删链再写普通文件。
                deleteIfLinkOrFile(archive)
                context.assets.open("locale/locale-archive").use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val defDir = File(root, "etc/default").apply { mkdirs() }
            val def = File(defDir, "locale")
            val text = context.assets.open("locale/default-locale").bufferedReader().use { it.readText() }
            if (!def.isFile || def.readText() != text) {
                deleteIfLinkOrFile(def)
                def.writeText(text)
            }

            Log.i(TAG, "zh_CN locale installed: archive=${archive.length()} bytes")
        } catch (e: Exception) {
            Log.w(TAG, "install zh_CN locale failed: ${e.message}")
        }
    }

    /** 把磁盘上的真实状态同步到内存（不触发解压），供状态页显示。 */
    fun refreshState() {
        if (state is State.Extracting) return
        state = if (marker.exists()) State.Ready(containerRoot) else State.NotExtracted
    }
    /** 异步解压，回调在主线程。 */
    fun ensureExtractedAsync(onDone: (Result<File>) -> Unit) {
        if (isReady()) {
            state = State.Ready(containerRoot)
            onDone(Result.success(containerRoot))
            return
        }
        Thread {
            try {
                val dir = ensureExtractedSync()
                (context as? android.app.Activity)?.runOnUiThread {
                    onDone(Result.success(dir))
                } ?: onDone(Result.success(dir))
            } catch (e: Exception) {
                onDone(Result.failure(e))
            }
        }.start()
    }

    /** 删除已解压的 rootfs（用于「重新解压」/清理）。 */
    fun clean() {
        rootfsDir.deleteRecursively()
        state = State.NotExtracted
    }
}
