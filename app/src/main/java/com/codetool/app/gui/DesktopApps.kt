package com.codetool.app.gui

import android.content.Context
import com.codetool.app.R
import com.codetool.app.runtime.KeepAliveService
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.util.FileLog
import java.io.File

/**
 * 桌面应用条目。
 *
 * 合成器内置桌面共用这一份目录，避免图标/命令漂移。
 *
 * - [cmd] 启动命令：可以是绝对路径，也可以是 PATH 名（如 `spark-store`）；
 * - [args] 追加参数（Wayland/软件渲染相关）；
 * - [install] 未安装时在容器里执行的安装脚本（空串表示没有安装途径）；
 * - [note] 图标下方的补充说明（可为空）；
 * - 桌面应用：点图标即可启动；未安装的走自动安装脚本。
 */
data class DesktopApp(
    val id: String,
    val name: String,
    val cmd: String,
    val iconRes: Int,
    val args: String = "",
    val install: String = "",
    val note: String = "",
)

/**
 * 从 rootfs 的 .desktop 文件自动发现的「已安装」Linux 程序。
 *
 * 和 [DesktopApp]（内置 curated 列表）分开：发现的程序没有安装脚本、没有
 * 内置图标，只是告诉用户「你装了这个、点一下能启动」。
 */
data class DiscoveredApp(
    val name: String,
    val exec: String,
    val icon: String,
)

object DesktopApps {

    /**
     * 脚本输出观察者：runScript 每读一行就回调一次（主线程外！）。
     * 合成器用它把安装/启动进度实时显示在屏幕上，用户才知道有没有报错。
     */
    @Volatile
    var outputObserver: ((tag: String, line: String) -> Unit)? = null

    /** 命令可能在 PATH 里，也可能在几个常见的安装目录下。 */
    private val SEARCH_DIRS = listOf(
        "usr/bin", "usr/local/bin", "bin", "opt/bin",
        "opt/durapps/spark-store/bin", "opt/apps/spark-store/files/bin",
    )


    val apps: List<DesktopApp> = listOf(
        DesktopApp(
            id = "spark-store",
            name = "星火商店",
            cmd = "spark-store",
            iconRes = R.drawable.ic_packages,
            args = "--ozone-platform=wayland --disable-gpu",
        ),
        DesktopApp(
            id = "chrome",
            name = "浏览器",
            cmd = "google-chrome",
            iconRes = R.drawable.ic_files,
            args = "--ozone-platform=wayland --disable-gpu --disable-gpu-compositing " +
                "--enable-unsafe-swiftshader --no-sandbox --disable-dev-shm-usage",
        ),
        DesktopApp(
            id = "pcmanfm",
            name = "文件",
            cmd = "pcmanfm",
            iconRes = R.drawable.ic_folder,
        ),
        DesktopApp(
            id = "terminal",
            name = "终端",
            cmd = "weston-terminal",
            iconRes = R.drawable.ic_terminal,
        ),
    )

    /** 在 rootfs 里查找可执行文件；找不到返回 null。 */
    fun resolveBinary(context: Context, cmd: String): File? {
        val root = RootfsManager.get(context.applicationContext).containerRoot
        if (cmd.startsWith("/")) return findPresent(File(root, cmd.removePrefix("/")))
        for (dir in SEARCH_DIRS) {
            findPresent(File(root, "$dir/$cmd"))?.let { return it }
        }
        // 部分程序（例如 spark-store）只有 .desktop / 包装脚本，退一步看 PATH 下的同名目录情形。
        return null
    }

    /**
     * 判断容器内的可执行文件是否存在。
     *
     * 不能只用 [File.exists]：它会跟随软链接，而容器里很多二进制是**指向容器内
     * 绝对路径**的软链（如 Chrome 的 `usr/bin/google-chrome` → `/etc/alternatives/…`
     * → `/opt/google/chrome/google-chrome`）。从宿主侧解析这些绝对路径会跑到
     * Android 根 `/opt/...` 找不到，[File.exists] 就返回 false，导致「明明装了却
     * 显示未安装」。这里补一次 `NOFOLLOW_LINKS` 检查：只要软链本身存在就算已安装
     * —— 容器内启动时这些链接是能正常解析的。
     */
    private fun findPresent(f: File): File? {
        if (f.exists()) return f
        val links = java.nio.file.Files.exists(
            f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
        )
        return if (links) f else null
    }

    fun isInstalled(context: Context, app: DesktopApp): Boolean =
        resolveBinary(context, app.cmd) != null

    /** 内置浏览器（Chrome）是否已装进 rootfs；供包管理 / MCP 页显示状态。 */
    fun browserInstalled(context: Context): Boolean =
        apps.firstOrNull { it.id == "chrome" }?.let { isInstalled(context, it) } ?: false

    /** Firefox 是否已装进 rootfs。 */
    fun firefoxInstalled(context: Context): Boolean =
        apps.firstOrNull { it.id == "firefox" }?.let { isInstalled(context, it) } ?: false

    /** 星火商店是否已装进 rootfs。 */
    fun sparkStoreInstalled(context: Context): Boolean =
        resolveBinary(context, "spark-store") != null

    // .desktop 里这些 Exec 需要终端/不值得单独列，跳过。
    private val SKIP_EXEC = setOf("true", "false", "exit", "x-terminal-emulator")
    // 终端程序、以及在 tawcroot 容器里必然起不来的 GUI（无 systemd/portal/session）：
    // zutty 是终端模拟器；epiphany(GNOME Web) 强依赖 xdg-desktop-portal，容器内报
    // "Failed to create XdpPortal instance: Operation not permitted"，点了没窗口。
    private val SKIP_CLI = setOf(
        "vi", "vim", "nvim", "nano", "emacs", "less", "more", "top", "htop",
        "xterm", "uxterm", "x-terminal-emulator", "weston-terminal", "sh", "bash",
        "zutty", "epiphany", "nautilus",
    )
    // 依赖已移除的 Wine 兼容层，点了必然无 UI 或报错，不列为可启动项。
    private val SKIP_WINE_NAME = Regex("wine|winetricks|windows.?virt", RegexOption.IGNORE_CASE)

    /**
     * 扫描 rootfs 的 .desktop 文件，返回已安装的 Linux 程序（去重、按名排序）。
     *
     * 只读 `Name` / `Exec` / `Icon` / `NoDisplay` 几个字段，跳过 NoDisplay=true、
     * 空 Exec 和终端模拟器本身，避免列出一堆用户不关心的条目。Exec 里的
     * `%f/%F/%u/%U/%d/%D/%n/%N/%i/%c/%k/%v/%m` 占位符会被清掉，取第一个 token
     * 作为可执行文件。
     *
     * 在主线程外调用（文件 IO）。找不到 applications 目录或解析失败返回空列表。
     */
    fun discoverInstalled(context: Context): List<DiscoveredApp> {
        val root = RootfsManager.get(context.applicationContext).containerRoot
        val dirs = listOf(
            File(root, "usr/share/applications"),
            File(root, "usr/local/share/applications"),
            File(root, "root/.local/share/applications"),
        )
        val curated = apps.map { it.id }.toSet()
        val out = LinkedHashMap<String, DiscoveredApp>()
        for (dir in dirs) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } ?: continue
            for (f in files) {
                val entry = parseDesktopFile(f) ?: continue
                if (entry.noDisplay) continue
                // Terminal=true 的程序需要终端承载，没有 GUI 窗口 → 不进桌面。
                if (entry.terminal) continue
                val execBin = entry.exec.split(Regex("\\s+")).firstOrNull()
                    ?.replace(Regex("%[fFuUdDnNiCkvvm]"), "")
                    ?.trim()
                    ?.removeSurrounding("\"")
                if (execBin.isNullOrEmpty() || execBin in SKIP_EXEC) continue
                val binName = execBin.substringAfterLast('/')
                if (binName.lowercase() in SKIP_CLI) continue
                if (SKIP_WINE_NAME.containsMatchIn(entry.name) || SKIP_WINE_NAME.containsMatchIn(binName)) continue
                // 去重：同 Exec 二进制只留一条；跳过 curated 列表已覆盖的。
                if (out.containsKey(execBin)) continue
                val name = entry.name.ifBlank { execBin }
                if (curated.any { it.equals(execBin, ignoreCase = true) }) continue
                // exec 去掉 %占位符，作为可点击启动的命令行（保留原参数）。
                val launchExec = entry.exec.replace(Regex("%[fFuUdDnNiCkvvm]"), "").trim()
                out[execBin] = DiscoveredApp(name = name, exec = launchExec, icon = entry.icon)
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    private class DesktopEntry(
        val name: String,
        val exec: String,
        val icon: String,
        val noDisplay: Boolean,
        val terminal: Boolean,
    )

    /** 解析单个 .desktop，只取 [Desktop Entry] 段的几个关键字段。 */
    private fun parseDesktopFile(f: File): DesktopEntry? {
        var name = ""
        var exec = ""
        var icon = ""
        var noDisplay = false
        var terminal = false
        try {
            f.useLines { lines ->
                var inMain = false
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    if (line.startsWith("[")) {
                        inMain = line == "[Desktop Entry]"
                        continue
                    }
                    if (!inMain) continue
                    val eq = line.indexOf('=')
                    if (eq <= 0) continue
                    val key = line.substring(0, eq).trim()
                    val value = line.substring(eq + 1).trim()
                    when (key) {
                        "Name" -> if (name.isEmpty()) name = value
                        "Exec" -> if (exec.isEmpty()) exec = value
                        "Icon" -> if (icon.isEmpty()) icon = value
                        "NoDisplay" -> noDisplay = value.equals("true", ignoreCase = true)
                        "Terminal" -> terminal = value.equals("true", ignoreCase = true)
                    }
                }
            }
        } catch (e: Throwable) {
            // 单个 .desktop 解析失败（权限/编码）不该刷日志，静默跳过。
            return null
        }
        if (exec.isEmpty()) return null
        return DesktopEntry(name, exec, icon, noDisplay, terminal)
    }

    /** 启动命令（不含环境变量前缀）。 */
    fun launchLine(app: DesktopApp): String =
        app.cmd + if (app.args.isNotEmpty()) " " + app.args else ""

    /**
     * 自动发现的 .desktop 里 Chromium/Chrome/Electron 类程序通常需要额外参数才能在
     * tawcroot 容器里出窗口：无 user namespace → 必须 --no-sandbox；无 GPU/dma-buf →
     * 软件渲染 + Wayland ozone。裸 Exec（如 `/usr/bin/google-chrome-stable %U`）会
     * 静默起不来。这里按可执行文件名补最小必要参数，其它程序原样返回。
     */
    fun containerArgsFor(exec: String): String {
        val bin = exec.split(Regex("\\s+")).firstOrNull()
            ?.substringAfterLast('/')?.lowercase() ?: return ""
        val chromium = bin.contains("chrome") || bin.contains("chromium") ||
            bin.contains("electron") || bin.contains("code") || bin.contains("microsoft-edge")
        if (!chromium) return ""
        return "--ozone-platform=wayland --no-sandbox --disable-dev-shm-usage " +
            "--disable-gpu --disable-gpu-compositing --enable-unsafe-swiftshader"
    }

    /**
     * 在容器里跑一段 bash 脚本，stdout/stderr 逐行写进 codetool.log。
     *
     * 不用终端会话：后台会话没有可见视图，输出会被丢弃，出问题只能靠猜。
     * 这里直接用 tawcroot 起进程，输出进日志，adb 端能直接看到失败原因。
     * `bash -l` 会读 /etc/profile（TawcrootRuntime 写入的 codetool-wayland.sh
     * 提供 WAYLAND_DISPLAY / XDG_RUNTIME_DIR）。
     */
    fun runScript(
        context: Context,
        tag: String,
        script: String,
        onExit: ((Int) -> Unit)? = null,
        dedicatedLog: java.io.File? = null,
    ): Thread {
        val appCtx = context.applicationContext
        val t = Thread {
            // 长时间任务（应用安装/下载）锁屏会被冻结中断，先拉起保活前台服务
            val keepAlive = tag in KEEPALIVE_TAGS
            if (keepAlive) {
                KeepAliveService.start(appCtx, keepAliveText(tag))
            }
            try {
                val rootfs = RootfsManager.get(appCtx).containerRoot
                // 专属日志：启动前清空，运行时逐行追加，方便用户一键复制反馈
                if (dedicatedLog != null) {
                    runCatching { dedicatedLog.parentFile?.mkdirs(); dedicatedLog.writeText("") }
                }
                val p = com.codetool.app.runtime.TawcrootRuntime.get(appCtx)
                    .exec(rootfs, "/bin/bash", "-lc", "exec 2>&1\n$script")
                // "exec 2>&1" 把 stderr 重定向到 stdout：proot 容器里 Xwayland/tkinter/
                // 程序报错走 stderr，之前只读 stdout 导致"点了没反应/无 UI"时看不到
                // 任何报错。合并后能定位。
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) {
                        FileLog.i(appCtx, tag, line)
                        outputObserver?.invoke(tag, line)
                        if (dedicatedLog != null) {
                            runCatching {
                                val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date())
                                dedicatedLog.appendText("[$ts] $line\n")
                            }
                        }
                    }
                }
                val code = p.waitFor()
                FileLog.i(appCtx, tag, "exit=$code")
                if (dedicatedLog != null) {
                    runCatching { dedicatedLog.appendText("[exit=$code]\n") }
                }
                onExit?.invoke(code)
            } catch (e: Throwable) {
                FileLog.e(appCtx, tag, "run failed: ${e.message}", e)
                onExit?.invoke(-1)
            } finally {
                if (keepAlive) KeepAliveService.stop(appCtx)
            }
        }
        t.isDaemon = true
        t.name = "codetool-$tag"
        t.start()
        return t
    }

    /** 需要保活的长任务标签（应用安装等耗时操作）。 */
    private val KEEPALIVE_TAGS = setOf("Install", "GuiLaunch")

    /** 保活通知文案。 */
    private fun keepAliveText(tag: String): String = when (tag) {
        "Install" -> "正在安装应用…（锁屏不中断）"
        "GuiLaunch" -> "正在启动应用…（锁屏不中断）"
        else -> "后台任务运行中…"
    }

    /**
     * 启动前统一设置的环境变量。
     *
     * - WAYLAND_DISPLAY：连到 tawc 合成器的 socket；
     * - LIBGL/GALLIUM/MESA：本机没有 dma-buf，强制 llvmpipe 软件渲染，
     *   否则 Electron/Chromium 会去要 GPU 然后黑屏；
     * - 清掉 XKB_CONFIG_ROOT：宿主的 xkb 路径会让容器里的客户端崩溃。
     */
    const val ENV_PREFIX =
        "unset XKB_CONFIG_ROOT TAWC_XKB_CONFIG_ROOT\n" +
            // 显式把 /usr/local/bin 放最前：bwrap/sudo 透传 shim 装在这里，必须优先于
            // /usr/bin 里的真身（bwrap 真身在 /usr/bin，proot 下起不来，靠 shim 兜底）。
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
            // ace-run（apm ssinstall 的沙箱入口）用 `who` 探测非 root 用户再 `id -u` 取 uid。
            // proot 容器没有 utmp 登录记录，`who` 返回空 → non_root_user="" → `id -u ""`
            // 报 "no such user"，整条 bwrap 链崩（微信 apm ssinstall 秒失败）。
            // 修法：建一个真实普通用户 android(1000) + who shim 返回它，探测拿到有效 uid。
            "grep -q '^android:' /etc/passwd 2>/dev/null || echo 'android:x:1000:0:android:/root:/bin/bash' >> /etc/passwd\n" +
            "printf '#!/bin/bash\\necho android\\n' > /usr/local/bin/who && chmod +x /usr/local/bin/who\n" +
            // ssinstall 包验证脚本会调 `su --info`（新版 util-linux su 才支持），容器旧 su
            // 报 "unrecognized option '--info'" → OMG-IT-GOES-WRONG → 安装失败。
            // 关键：脚本用绝对路径 /bin/su 调，PATH shim 拦不住。所以把真 su 挪到
            // /bin/su.real，在 /bin/su 原位放 shim（绝对+PATH 都拦截），--info 返回成功，
            // 其余透传给 /bin/su.real（sudo shim 依赖 su -s 切用户）。
            "[ -x /bin/su.real ] || mv /bin/su /bin/su.real 2>/dev/null\n" +
            "cat > /bin/su <<'SUEOF'\n" +
            "#!/bin/bash\n" +
            "if [ \"\$1\" = \"--info\" ]; then echo \"user:\${SUDO_USER:-android}\"; exit 0; fi\n" +
            "exec /bin/su.real \"\$@\"\n" +
            "SUEOF\n" +
            "chmod +x /bin/su\n" +
            "cp -f /bin/su /usr/local/bin/su 2>/dev/null\n" +
            "export WAYLAND_DISPLAY=/usr/share/tawc/wayland-0\n" +
            "export XDG_RUNTIME_DIR=/tmp\n" +
            // 中文语言环境：容器默认只有 C.utf8，Qt/微信/GTK 按 LANG 回退英文，
            // fontconfig 缺 zh Lang 提示时也可能选错 CJK 字形（乱码/方块）。
            // 注入 LANG/LC_ALL/LANGUAGE；CJK 字体由 RootfsManager.ensureCjkFont() 预置。
            "export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 LANGUAGE=zh_CN:zh\n" +
            // 部分程序用 TMPDIR 建临时/套接字目录；容器继承的 TMPDIR 可能指向
            // termux 路径（/data/data/com.termux/files/usr/tmp，不存在）→ 建目录失败。
            // 强制指向存在的 /tmp。
            "mkdir -p /tmp 2>/dev/null; export TMPDIR=/tmp\n" +
            "export LIBGL_ALWAYS_SOFTWARE=1\n" +
            "export GALLIUM_DRIVER=llvmpipe\n" +
            "export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe\n" +
            // X11 应用支持：X11 GUI 需要 DISPLAY + session D-Bus。
            // 之前两个致命问题：① Xwayland 用 & 起，挂在 runScript 的 bash 下，脚本一结束
            // 整个进程树被回收连 Xwayland 一起杀 → X11 应用连 display 时已死
            // ("could not connect to display :1")。改用 setsid 完全脱离进程树，父退出仍存活。
            // ② session D-Bus 没起 ("Unable to acquire session bus"/dbus-launch not found)，
            // zenity/Qt 都依赖 → 用 dbus-launch 起 session 总线并 export 地址。
            "if command -v Xwayland >/dev/null 2>&1; then\n" +
            "  if ! pgrep -x Xwayland >/dev/null 2>&1; then\n" +
            "    rm -f /tmp/.X11-unix/X1 2>/dev/null\n" +
            "    mkdir -p /tmp/.X11-unix\n" +
            "    setsid Xwayland :1 -fullscreen -geometry 1280x800 >/tmp/xwayland-env.log 2>&1 < /dev/null &\n" +
            "    sleep 1\n" +
            "  fi\n" +
            "  export DISPLAY=:1\n" +
            "fi\n" +
            "if command -v dbus-launch >/dev/null 2>&1; then\n" +
            "  if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
            "    eval \"\$(dbus-launch --sh-syntax)\"\n" +
            "    export DBUS_SESSION_BUS_ADDRESS DBUS_SESSION_BUS_PID\n" +
            "  fi\n" +
            "fi\n"

    /**
     * 拉起 D-Bus 总线（session + system），供 spark-store 等需要 D-Bus 后端的
     * GUI 应用使用。proot 容器默认没有 D-Bus 守护进程，spark-store 的安装按钮
     * 调不到 ssinstall 后端 → 点了没反应。
     *
     * 幂等：总线已起就复用，不重复拉。dbus 缺失才装。
     */
    const val DBUS_SETUP =
        "command -v dbus-daemon >/dev/null 2>&1 || { apt-get update; apt-get install -y dbus; }\n" +
            // dbus-launch 在 dbus-x11 包里，session 总线靠它起；只装 dbus 没有
            "command -v dbus-launch >/dev/null 2>&1 || { apt-get update; apt-get install -y dbus-x11; }\n" +
            "mkdir -p /var/run/dbus /run/dbus\n" +
            "[ -f /etc/machine-id ] || dbus-uuidgen --ensure >/dev/null 2>&1 || true\n" +
            // system 总线：ssinstall/apt 后端走这条。先清残留 pid 再起，避免上次
            // 异常退出的 stale pid 让 --fork 静默失败。
            "rm -f /var/run/dbus/system_bus_socket /run/dbus/system_bus_socket\n" +
            "if ! [ -S /var/run/dbus/system_bus_socket ]; then\n" +
            "  rm -f /var/run/dbus/pid /run/dbus/pid\n" +
            "  mkdir -p /run/dbus\n" +
            "  chown -R messagebus:messagebus /run/dbus /var/lib/dbus 2>/dev/null\n" +
            // root 下 dbus-daemon 会 drop capabilities，proot 不支持 → Operation not
            // permitted，总线起不来。以 messagebus 身份启动则非 root、不触发 drop。
            // nohup+& 让它脱离启动脚本独立存活（否则脚本退出就被回收）。
            "  nohup su -s /bin/sh messagebus -c 'exec dbus-daemon --system --nofork' >/dev/null 2>&1 &\n" +
            "  sleep 1\n" +
            "fi\n" +
            // ssinstall 的 hash_check 要求 /var/lib/aptss/lists/ 根目录下有 spark 仓库的
            // Packages 元数据，缺失则直接 exit 1（NO_PACKAGE_FILE_FOUND）。spark 商店下
            // .deb 不走 aptss，所以 lists 默认空。完整 aptss update 会把元数据下到 partial/
            // 再搬到 lists/，但后台跑在 proot 下常被会话回收杀在落盘前。这里后台 best-effort
            // 刷新一次，并兜底：若 lists/ 仍无 Packages 而 partial/ 已下载好，直接搬过去，
            // 保证 hash_check 能找到（partial 里的就是合法的仓库元数据）。
            // 注意文件名是下划线 *_Packages（如 d.spark-app.store_aarch64-store_Packages），
            // 不是点号 *.Packages，glob 写错会导致兜底永远匹配不上。
            "if [ -z \"$(ls -A /var/lib/aptss/lists/*_Packages 2>/dev/null)\" ]; then\n" +
            "  nohup aptss update >/tmp/aptss-update.log 2>&1 &\n" +
            "  sleep 2\n" +
            "  if [ -z \"$(ls -A /var/lib/aptss/lists/*_Packages 2>/dev/null)\" ] && [ -n \"$(ls -A /var/lib/aptss/lists/partial/*_Packages 2>/dev/null)\" ]; then\n" +
            "    cp -f /var/lib/aptss/lists/partial/*_Packages /var/lib/aptss/lists/ 2>/dev/null\n" +
            "    cp -f /var/lib/aptss/lists/partial/*_InRelease /var/lib/aptss/lists/ 2>/dev/null\n" +
            "  fi\n" +
            "fi\n" +
            "export DBUS_SYSTEM_BUS_ADDRESS=unix:path=/var/run/dbus/system_bus_socket\n" +
            // session 总线：GUI 客户端（Electron/spark-store）必需。优先 dbus-launch，
            // 缺失（dbus-x11 没装）就手动起 session dbus-daemon 并导出地址——只依赖
            // dbus 包本身（必装），不依赖可能装不上的 dbus-x11。
            "if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ]; then\n" +
            "  if command -v dbus-launch >/dev/null 2>&1; then\n" +
            "    eval \"\$(dbus-launch --sh-syntax 2>/dev/null)\"\n" +
            "  else\n" +
            "    mkdir -p /tmp/dbus-session\n" +
            "    export DBUS_SESSION_BUS_ADDRESS=\"\$(dbus-daemon --session --print-address --fork 2>/dev/null)\"\n" +
            "    export DBUS_SESSION_BUS_PID\n" +
            "  fi\n" +
            "fi\n" +
            "export DBUS_SESSION_BUS_ADDRESS\n"

    /**
     * 安装 sudo 透传 shim。
     *
     * 星火商店的 ssinstall 用 `sudo -u <user> <cmd>` 弹 zenity / 复制桌面文件，
     * 但 proot 容器里没装 sudo → `sudo: command not found` → 安装直接失败。
     * 而 proot 下我们本就是 root（uid=0），sudo 的 setuid 提权本就无效，所以
     * 做一个透传 shim 即可：
     *   - `sudo -u root <cmd>` / `sudo <cmd>`：直接执行（已是 root）
     *   - `sudo -u <其它用户> <cmd>`：用 `su` 切到该用户执行
     * 环境变量透传（ssinstall 会传 DBUS_SESSION_BUS_ADDRESS=... zenity）。
     *
     * 幂等：每次启动 spark-store 前重写，rootfs 重置也能自愈。放在
     * /usr/local/bin（PATH 优先于 /usr/bin），不覆盖发行版可能存在的真 sudo。
     */
    const val SUDO_SETUP =
        "mkdir -p /usr/local/bin\n" +
            "cat > /usr/local/bin/sudo <<'SUDOEOF'\n" +
            "#!/bin/bash\n" +
            "# proot 容器 sudo 透传 shim：本容器 uid=0，无需真正提权。\n" +
            "if [ \"\$1\" = \"-u\" ]; then shift; u=\"\$1\"; shift; else u=\"root\"; fi\n" +
            "# ssinstall 用 sudo -u 弹 zenity / 查 xdg-user-dir，这些命令容器里可能\n" +
            "# 没装（xdg-user-dirs/zenity）。不能 exec——找不到命令会终止整个脚本，\n" +
            "# 让 ssinstall 后续 apt install 都不跑（表现为 NO_PACKAGE_FILE_FOUND）。\n" +
            "# 改成：命令不存在就静默返回成功，存在才执行。\n" +
            "if [ -z \"\$u\" ] || [ \"\$u\" = \"root\" ] || [ \"\$u\" = \"0\" ]; then\n" +
            "  command -v \"\$1\" >/dev/null 2>&1 || exit 0\n" +
            "  \"\$@\"\n" +
            "  exit \$?\n" +
            "fi\n" +
            "# 切到目标用户执行（保留环境，ssinstall 需要传 DBUS 地址给 zenity）\n" +
            "command -v su >/dev/null 2>&1 || exit 0\n" +
            "su -s /bin/bash \"\$u\" -c 'cd \"\$PWD\"; exec \"\$@\"' _ \"\$@\"\n" +
            "SUDOEOF\n" +
            "chmod +x /usr/local/bin/sudo\n"

    /**
     * bwrap（bubblewrap）透传 shim：绕过 proot 不支持 namespace 的限制。
     *
     * spark-store 装 wechat 等包走 `apm ssinstall` → ace-run 拼出
     * `bwrap --dev-bind / / ... --proc /proc ... bash -c "真实命令"`。bwrap 要创建
     * mount/user namespace，proot 容器里报 `Creating new namespace failed: Operation
     * not permitted` → 安装直接失败。但在 proot 里我们本就跑在沙箱中，bwrap 的隔离是
     * 多余的——让它免沙箱直跑真实命令即可。
     *
     * shim 放 /usr/local/bin（PATH 优先于 /usr/bin/bwrap）。ace-run 拼的最终命令恒为
     * `bash -c "..."`，且 bwrap 的 bind 目标只会是 /、/proc、/host 这类路径，不会出现
     * 独立的 `bash` token，所以从第一个 `bash` 参数处 exec 真实命令是安全的。
     * 幂等：已装就不重复写。
     */
    const val BWRAP_SETUP =
        "mkdir -p /usr/local/bin\n" +
            "if [ ! -x /usr/local/bin/bwrap ]; then\n" +
            "  cat > /usr/local/bin/bwrap <<'BWRAPEOF'\n" +
            "#!/bin/bash\n" +
            "# proot 容器 bwrap 透传 shim：bwrap 需要 namespace，proot 不支持，免沙箱直跑。\n" +
            "n=0\n" +
            "for a in \"\$@\"; do\n" +
            "  n=\$((n+1))\n" +
            "  if [ \"\$a\" = \"bash\" ]; then exec \"\${@:n}\"; fi\n" +
            "done\n" +
            "# 没有 bash token：原样 exec（让真实错误暴露，便于排查）\n" +
            "exec \"\$@\"\n" +
            "BWRAPEOF\n" +
            "  chmod +x /usr/local/bin/bwrap\n" +
            "fi\n"

}
