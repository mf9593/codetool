package com.codetool.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.codetool.app.ai.AptMirror
import com.codetool.app.gui.TawcCompositorActivity
import com.google.android.material.button.MaterialButton

/**
 * 包管理页：常用动作，全部在终端里执行，输出实时可见。
 * - 更新依赖：apt-get update && apt-get -y upgrade
 * - 安装 MiMo Code：官方脚本（curl 不存在时先安装 curl）
 * - 安装 opencode：官方脚本（失败时回退 npm 的 opencode-ai）
 * - 安装星火商店：下载 arm64 deb 后用 apt 安装（URL 已修复：tag 无 v 前缀）
 * - 安装 APM：从官方 APT 仓库安装 amber-pm 包管理器
 * - 安装浏览器：Google Chrome + Mozilla Firefox（官方 arm64 版）
 * - 启动星火商店：先开合成器 + 强制 Wayland/软件渲染（修复黑屏）
 */
class PackagesFragment : Fragment() {

    /**
     * 等待 apt/dpkg 锁释放：容器里可能有残留 apt-get（上次安装没跑完/被切走）占着 dpkg 锁，
     * 直接 apt-get install 会报 "Could not get lock"。先等其它 apt/dpkg 进程结束（最多 120 秒）。
     * 定义成函数 + 立即调用，各安装脚本前置引用。
     */
    private val aptWaitLock =
        "wait_apt_lock() { for i in \$(seq 1 60); do " +
            "if ! pgrep -x apt-get >/dev/null 2>&1 && ! pgrep -x apt >/dev/null 2>&1 && ! pgrep -x dpkg >/dev/null 2>&1; then return 0; fi; " +
            "[ \$i -eq 1 ] && echo '检测到其他 apt/dpkg 进程在跑，等待其结束（最多 120 秒）…'; " +
            "sleep 2; done; echo '[WARN] 等待 apt 锁超时，尝试继续（若仍报锁占用，请稍后重试或重启容器）'; }\n" +
            "wait_apt_lock\n"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_packages, container, false)
        v.findViewById<MaterialButton>(R.id.btnUpdate).setOnClickListener {
            runInTerminal(
                "更新依赖 · 装常用组件",
                aptWaitLock +
                    "export DEBIAN_FRONTEND=noninteractive\n" +
                    "apt-get update\n" +
                    "echo '=== 安装常用依赖（浏览器/脚本/NDK·JDK 解压都依赖）==='\n" +
                    "if apt-get install -y ca-certificates wget curl gnupg git unzip xz-utils bzip2 \\\n" +
                    "  iputils-ping iproute2 bind9-dnsutils file nano less \\\n" +
                    "  procps psmisc zip htop tree openssh-client; then\n" +
                    "  echo '[OK] 常用组件安装成功：wget / curl / gpg / unzip / xz / ping / ip / dig / nano / ssh 已可用'\n" +
                    "else\n" +
                    "  echo '[ERROR] 常用组件安装失败（看上方报错，常见原因：apt 锁被占 / 网络问题）'\n" +
                    "fi\n" +
                    "echo '=== 升级已装软件包 ==='\n" +
                    "apt-get -y upgrade || true\n"
            )
        }
        v.findViewById<MaterialButton>(R.id.btnMimo).setOnClickListener {
            runInTerminal(
                "安装 MiMo Code",
                aptWaitLock +
                    "command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y curl ca-certificates; }\n" +
                    "curl -fsSL https://mimo.xiaomi.com/install | bash\n" +
                    com.codetool.app.ai.AiModelManager.FIX_PATH_SCRIPT + "\n" +
                    com.codetool.app.ai.AiEnvHelper.PY3_CHECK_SCRIPT
            )
        }
        // 安装 opencode：官方脚本安装（curl 不存在时先装 curl；失败时回退 npm）
        v.findViewById<MaterialButton>(R.id.btnOpencode).setOnClickListener {
            runInTerminal(
                "安装 opencode",
                aptWaitLock +
                    "command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y curl ca-certificates; }\n" +
                    "echo '=== 1/2 官方脚本安装 opencode ==='\n" +
                    "curl -fsSL https://opencode.ai/v2/install | bash\n" +
                    "if ! command -v opencode >/dev/null 2>&1; then\n" +
                    "  echo '=== 2/2 脚本安装失败，回退 npm 安装 ==='\n" +
                    "  command -v npm >/dev/null 2>&1 || { apt-get update && apt-get install -y npm nodejs; }\n" +
                    "  npm install -g opencode-ai\n" +
                    "fi\n" +
                    "command -v opencode >/dev/null 2>&1 && echo '[OK] opencode 安装完成：opencode' || echo '[ERROR] opencode 安装失败'\n" +
                    com.codetool.app.ai.AiModelManager.FIX_PATH_SCRIPT + "\n" +
                    com.codetool.app.ai.AiEnvHelper.PY3_CHECK_SCRIPT
            )
        }
        v.findViewById<MaterialButton>(R.id.btnSpark).setOnClickListener {
            val ctx = requireContext()
            // 自适应：已装 spark-store → 启动；未装 → 先安装
            val installed = com.codetool.app.gui.DesktopApps.resolveBinary(
                ctx, "spark-store") != null
            if (installed) {
                // 启动：先开合成器，再强制 Wayland + 软件渲染（修复黑屏/崩溃）
                try {
                    ctx.startActivity(
                        Intent(ctx, TawcCompositorActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    // 走 runInlineScript：不发源码到终端，只显示输出
                    TerminalFragment.runInlineScript(
                        ctx,
                        "启动星火商店",
                        "echo '--- 启动星火商店（Wayland + 软件渲染）---'\n" +
                            "unset XKB_CONFIG_ROOT TAWC_XKB_CONFIG_ROOT\n" +
                            "export WAYLAND_DISPLAY=/usr/share/tawc/wayland-0\n" +
                            "export XDG_RUNTIME_DIR=/tmp\n" +
                            "export LIBGL_ALWAYS_SOFTWARE=1\n" +
                            "export GALLIUM_DRIVER=llvmpipe\n" +
                            "export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe\n" +
                            "export ELECTRON_ENABLE_LOGGING=1\n" +
                            "spark-store --ozone-platform=wayland --disable-gpu\n"
                    )
                }, 3500L)
            } else {
                runInTerminal(
                    "安装星火商店",
                    "command -v wget >/dev/null 2>&1 || { apt-get update && apt-get install -y wget ca-certificates; }\n" +
                        "export DEBIAN_FRONTEND=noninteractive\n" +
                        "cd /tmp && rm -f spark-store.deb\n" +
                        "echo '=== 下载星火商店 deb ==='\n" +
                        "( set -e\n" +
                        "  wget -L -O spark-store.deb 'https://gitee.com/spark-store-project/spark-store/releases/download/5.3.2/spark-store_5.3.2_arm64.deb'\n" +
                        "  dpkg-deb -I spark-store.deb >/dev/null 2>&1 || { echo '[ERROR] deb 文件下载损坏或无效'; exit 1; }\n" +
                        "  echo '[OK] deb 文件有效，开始安装'\n" +
                        "  apt-get update\n" +
                        "  echo '=== 安装（若提示 apt 锁被占会自动重试）==='\n" +
                        "  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do\n" +
                        "    if apt-get install -yf ./spark-store.deb; then echo '[OK] 安装成功'; break; fi\n" +
                        "    echo '本次安装未成功（可能 apt 锁被占），2 秒后重试…'\n" +
                        "    sleep 2\n" +
                        "  done\n" +
                        ") || echo '[ERROR] 星火商店安装中断，看上方报错'\n" +
                        "echo ''\n" +
                        "echo '=== 安装结果（真实自检）==='\n" +
                        "command -v spark-store >/dev/null 2>&1 && echo '[OK] spark-store 已可用' || echo '[ERROR] spark-store 未装上，看上方报错（apt 锁/网络）'"
                )
            }
        }
        v.findViewById<MaterialButton>(R.id.btnApmInstall).setOnClickListener {
            runInTerminal(
                "安装 APM",
                aptWaitLock +
                    "command -v wget >/dev/null 2>&1 || { apt-get update && apt-get install -y wget ca-certificates; }\n" +
                    "export DEBIAN_FRONTEND=noninteractive\n" +
                    "dpkg --configure -a\n" +
                    "mkdir -p /etc/apt/trusted.gpg.d\n" +
                    "echo '=== 下载 APM GPG 密钥 ==='\n" +
                    "( set -e\n" +
                    "  wget -q -O /etc/apt/trusted.gpg.d/apm.gpg 'https://gitee.com/amber-ce/amber-pm/raw/master/src/etc/apt/trusted.gpg.d/apm.gpg'\n" +
                    "  [ -s /etc/apt/trusted.gpg.d/apm.gpg ] || { echo '[ERROR] GPG 密钥下载失败（空文件，网络问题）'; exit 1; }\n" +
                    "  echo 'deb [signed-by=/etc/apt/trusted.gpg.d/apm.gpg] https://d.spark-app.store/apm-deb-source /' > /etc/apt/sources.list.d/apm.list\n" +
                    "  apt-get update\n" +
                    "  apt-get install -y apm\n" +
                    ") || echo '[ERROR] APM 安装中断，看上方报错（GPG 密钥/网络）'\n" +
                    "echo ''\n" +
                    "echo '=== 安装结果（真实自检）==='\n" +
                    "command -v apm >/dev/null 2>&1 && echo '[OK] APM 已可用：apm' || echo '[ERROR] APM 未装上，看上方报错（gitee 密钥下载或 apt 源）'\n" +
                    "echo '用法：apm install <包名> 安装应用'\n" +
                    "echo '网页商店：https://erotica.spark-app.store/arm64-apm/'"
            )
        }
        // 安装 ADB：Ubuntu rootfs 不带 adb，apt 装官方 android-tools 包；
        // 容器与 App 共享网络命名空间（tawcroot 不隔离 netns），可直连本机无线调试。
        v.findViewById<MaterialButton>(R.id.btnAdbInstall).setOnClickListener {
            runInTerminal(
                "安装 ADB",
                aptWaitLock +
                    "command -v adb >/dev/null 2>&1 || { apt-get update && apt-get install -y adb; }\n" +
                    "echo ''\n" +
                    "echo '=== 安装结果（真实自检）==='\n" +
                    "command -v adb >/dev/null 2>&1 && adb version 2>/dev/null | head -1 || echo '[ERROR] adb 未装上，看上方 apt 报错（网络/软件源）'\n" +
                    "echo ''\n" +
                    "echo '连接本机（容器与 Android 共享网络，无需外接电脑）：'\n" +
                    "echo '  1) 设置 → 开发者选项 → 无线调试，保持开启并记下端口'\n" +
                    "echo '  2) adb connect 127.0.0.1:<端口>'\n" +
                    "echo '  3) adb devices 确认为 device（首次连接要配对：adb pair 127.0.0.1:<配对端口>）'"
            )
        }
        // 安装浏览器：Chrome / Firefox 拆成两个独立按钮，共用前置修复段。
        // 注意：不再在这里 inline 装 wget/gnupg（旧行 `command -v xxx || apt-get install -y ...`
        // 可能在 export DEBIAN_FRONTEND 之前跑、apt 配置弹窗会卡死）。wget/gnupg 改由
        // 「更新依赖 · 装常用组件」预装；Chrome 按钮里有缺 gpg 的友好检查（不卡死）。
        val browserPrereq = aptWaitLock +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "echo '=== 0/1 修复上次中断残留 + 清理容器跑不了的 snapd ==='\n" +
            // dpkg --configure -a 遇到损坏的 snapd（proot 下 CAP_SETFCAP 设不了）会报错返回 1，
            // 不容错会把整段中断、走不到下面的 snapd 移除。加 || true 让它活到移除 snapd。
            "dpkg --configure -a || true\n" +
            // 移除残留 snapd 并 hold 锁死：否则后面 apt-get install 会把它当依赖/推荐拉回来重装，
            // 又在容器里 CAP_SETFCAP 失败炸掉整个安装。
            "dpkg -l snapd >/dev/null 2>&1 && { echo '移除 snapd（容器不支持，会让 dpkg 报错）'; dpkg --remove --force-depends snapd 2>/dev/null || true; dpkg --purge snapd 2>/dev/null || true; }\n" +
            "echo 'snapd hold' | dpkg --set-selections 2>/dev/null || true\n" +
            "dpkg --configure -a || true\n" +
            "echo '[OK] snapd 已清理并锁死，继续安装'\n" +
            // 自愈：确保 wget/gnupg/xz-utils/ca-certificates 在（Chrome 下密钥、Firefox 解压 .tar.xz 都要用）。
            // 以前靠用户先手动点「更新依赖」，容易漏；缺 xz 时 `tar -xJf` 会直接失败。
            "echo '=== 确保前置工具（wget / gnupg / xz-utils / ca-certificates）==='\n" +
            "if dpkg -s wget >/dev/null 2>&1 && dpkg -s gnupg >/dev/null 2>&1 && dpkg -s xz-utils >/dev/null 2>&1; then echo '[OK] wget / gnupg / xz 已就绪'; else apt-get update && apt-get install -y wget gnupg xz-utils ca-certificates || echo '[WARN] 前置工具安装失败，继续尝试（可能已部分安装）'; fi\n"
        // Firefox 走 Mozilla 官方 apt 源（arm64，自动最新版），不依赖硬编码 tarball 链接；
        // 需要 wget（密钥下载）+ ca-certificates（https）+ gnupg（key dearmor）+ xz-utils。
        val firefoxPrereq = aptWaitLock +
            "export DEBIAN_FRONTEND=noninteractive\n" +
            "echo '=== 确保前置工具（wget / xz-utils / ca-certificates / gnupg）==='\n" +
            "if dpkg -s wget >/dev/null 2>&1 && dpkg -s xz-utils >/dev/null 2>&1 && dpkg -s gnupg >/dev/null 2>&1; then echo '[OK] wget / xz / gnupg 已就绪'; else apt-get update && apt-get install -y wget xz-utils ca-certificates gnupg || echo '[WARN] 前置工具安装失败，继续尝试'; fi\n"
        v.findViewById<MaterialButton>(R.id.btnChromeInstall).setOnClickListener {
            runInTerminal(
                "安装 Chrome",
                browserPrereq +
                    "echo '=== 1/1 安装 Google Chrome (arm64 官方版) ==='\n" +
                    "( set -e\n" +
                    "  command -v gpg >/dev/null 2>&1 || { echo '[ERROR] 缺少 gpg：请先在包管理点「更新依赖 · 装常用组件」安装 gnupg'; exit 1; }\n" +
                    "  wget -qO- https://dl.google.com/linux/linux_signing_key.pub | gpg --batch --yes --dearmor -o /usr/share/keyrings/google-chrome.gpg\n" +
                    "  [ -s /usr/share/keyrings/google-chrome.gpg ] || { echo '[ERROR] Chrome 密钥下载失败（网络问题）'; exit 1; }\n" +
                    "  echo 'deb [signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main' > /etc/apt/sources.list.d/google-chrome.list\n" +
                    // 容错：update 失败（失效第三方源）不中断，继续尝试装 chrome
                    "  apt-get update || echo '[WARN] 部分软件源拉取失败，继续尝试安装'\n" +
                    "  apt-get install -y google-chrome-stable\n" +
                    ") || echo '[ERROR] Chrome 安装中断，看上方报错'\n" +
                    "echo ''\n" +
                    "echo '=== 安装结果（真实自检）==='\n" +
                    "command -v google-chrome >/dev/null 2>&1 && echo \"[OK] Chrome 已可用：$(google-chrome --version 2>/dev/null)\" || echo '[ERROR] Chrome 未装上，请检查上方 apt 报错（大陆网络可能需要代理）'\n" +
                    "echo '装好后：终端点「显示」打开 GUI；工具 → MCP 页可查看浏览器状态'"
            )
        }
        v.findViewById<MaterialButton>(R.id.btnFirefoxInstall).setOnClickListener {
            runInTerminal(
                "安装 Firefox",
                // Firefox 走 Mozilla 官方 apt 源（arm64 自动最新版），不再用硬编码 tarball 链接。
                firefoxPrereq +
                    "echo '=== 安装 Mozilla Firefox（官方 apt 源，arm64 自动最新版）==='\n" +
                    "( set -e\n" +
                    "  command -v gpg >/dev/null 2>&1 || { echo '[ERROR] 缺 gpg：请先在包管理点「更新依赖 · 装常用组件」'; exit 1; }\n" +
                    "  mkdir -p /usr/share/keyrings\n" +
                    // 注意：dearmor 产物必须用 .gpg 扩展名。容器 apt 校验走 apt-key（已废弃），
                    // apt-key 对 .asc 命名的二进制 keyring 读不出 key → NO_PUBKEY 卡死整个 apt。
                    // 实测 .gpg 才通过（ubuntu-archive-keyring.gpg 同理）。故不用 Mozilla 官方文档的 .asc 命名。
                    "  wget -qO- https://packages.mozilla.org/apt/repo-signing-key.gpg | gpg --batch --yes --dearmor -o /usr/share/keyrings/packages.mozilla.org.gpg\n" +
                    "  [ -s /usr/share/keyrings/packages.mozilla.org.gpg ] || { echo '[ERROR] Mozilla 签名密钥下载失败（网络问题）'; exit 1; }\n" +
                    "  rm -f /usr/share/keyrings/packages.mozilla.org.asc\n" +
                    "  echo 'deb [signed-by=/usr/share/keyrings/packages.mozilla.org.gpg] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list\n" +
                    "  apt-get update || echo '[WARN] 部分软件源拉取失败，继续尝试安装'\n" +
                    "  apt-get install -y firefox\n" +
                    ") || echo '[ERROR] Firefox 安装中断，看上方报错'\n" +
                    "echo ''\n" +
                    "echo '=== 安装结果（真实自检）==='\n" +
                    "command -v firefox >/dev/null 2>&1 && echo \"[OK] Firefox 已可用：$(firefox --version 2>/dev/null)\" || echo '[ERROR] Firefox 未装上，请检查上方 apt 报错'\n" +
                    "echo '装好后：终端点「显示」打开 GUI；工具 → MCP 页可查看浏览器状态'"
            )
        }
        // 一键修复 AI 环境：rootfs 缺 python3 / MCP 桥损坏导致 MCP 起不来时自愈
        v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnvFix)
            .setOnClickListener {
                startActivity(android.content.Intent(requireContext(),
                    com.codetool.app.EnvSetupActivity::class.java).apply {
                    putExtra(com.codetool.app.EnvSetupActivity.EXTRA_FIX_MODE, true)
                })
            }
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshInstallStatus()
    }

    /** 浏览器/星火/ADB/MiMo/opencode/APM 都是「不预装、包管理一键装」：按钮实时反映 rootfs 里的安装状态。 */
    private fun refreshInstallStatus() {
        val ctx = requireContext()
        val chrome = view?.findViewById<MaterialButton>(R.id.btnChromeInstall) ?: return
        val firefox = view?.findViewById<MaterialButton>(R.id.btnFirefoxInstall)
        val spark = view?.findViewById<MaterialButton>(R.id.btnSpark)
        val adb = view?.findViewById<MaterialButton>(R.id.btnAdbInstall)
        val mimoBtn = view?.findViewById<MaterialButton>(R.id.btnMimo)
        val ocBtn = view?.findViewById<MaterialButton>(R.id.btnOpencode)
        val apmBtn = view?.findViewById<MaterialButton>(R.id.btnApmInstall)
        val da = com.codetool.app.gui.DesktopApps

        chrome.text = if (da.browserInstalled(ctx))
            installedText("Chrome", dpkgVersion(ctx, "google-chrome-stable"))
        else "安装 Chrome（Google 官方版）"
        firefox?.text = if (da.firefoxInstalled(ctx))
            installedText("Firefox", firefoxVersion(ctx))
        else "安装 Firefox（Mozilla 官方版）"
        spark?.text = if (da.sparkStoreInstalled(ctx))
            installedText("星火商店", dpkgVersion(ctx, "spark-store"), "启动")
        else "安装星火商店"
        adb?.text = if (da.resolveBinary(ctx, "adb") != null)
            installedText("ADB", dpkgVersion(ctx, "adb"))
        else "安装 ADB（Android 调试桥）"

        // MiMo / opencode：版本读缓存文件（probeVersions 写入），不在这里起进程
        val ai = com.codetool.app.ai.AiModelManager
        val mimo = ai.mimoStatus(ctx)
        val oc = ai.opencodeStatus(ctx)
        mimoBtn?.text = if (mimo.installed) installedText("MiMo Code", mimo.version)
                        else "安装 MiMo Code"
        ocBtn?.text = if (oc.installed) installedText("opencode", oc.version)
                      else "安装 opencode（AI 编程助手）"

        // APM 由 apt 安装，版本从容器 dpkg 数据库读（纯文件，不启动进程）
        apmBtn?.text = if (da.resolveBinary(ctx, "apm") != null)
            installedText("APM", dpkgVersion(ctx, "apm", "amber-pm"))
        else "安装 APM（应用包管理器）"
    }

    /** 已安装按钮文案：有版本就带上，拿不到版本时明确写「版本未知」。 */
    private fun installedText(name: String, version: String, action: String = "重装"): String =
        "$name 已安装" + (if (version.isBlank()) "（版本未知）" else " $version") + " · 点此$action"

    /**
     * 从容器 dpkg 数据库读包版本（纯文件流式读取，不启动容器进程）。
     * dpkg/status 每个包一个空行分隔的块，块内是 `Package:` / `Version:` 等字段。
     */
    private fun dpkgVersion(ctx: android.content.Context, vararg pkgs: String): String = runCatching {
        val f = java.io.File(
            com.codetool.app.runtime.RootfsManager.get(ctx.applicationContext).containerRoot,
            "var/lib/dpkg/status")
        if (!f.exists()) return@runCatching ""
        var pkg = ""
        var version = ""
        var found = ""
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                when {
                    line.startsWith("Package: ") -> pkg = line.removePrefix("Package: ").trim()
                    line.startsWith("Version: ") && version.isEmpty() ->
                        version = line.removePrefix("Version: ").trim()
                    line.isBlank() -> {
                        if (pkg in pkgs && version.isNotEmpty()) {
                            found = version
                            break
                        }
                        pkg = ""
                        version = ""
                    }
                }
            }
        }
        found
    }.getOrDefault("")

    /** Firefox 是 tar 包安装，不在 dpkg 数据库里；版本从 application.ini 读。 */
    private fun firefoxVersion(ctx: android.content.Context): String = runCatching {
        val f = java.io.File(
            com.codetool.app.runtime.RootfsManager.get(ctx.applicationContext).containerRoot,
            "opt/firefox/application.ini")
        if (!f.exists()) return@runCatching ""
        Regex("""(?m)^Version=(.+)$""").find(f.readText())?.groupValues?.get(1)?.trim().orEmpty()
    }.getOrDefault("")

    /** 把命令发到当前终端标签，并切到终端页显示执行过程。 */
    /**
     * 在终端运行安装脚本（不回显源码）。
     * 直接 sendCommand 多行脚本会把 echo/if 源码和 `>` 续行也打到终端，
     * 统一走 runInlineScript：写入临时文件 → 复制进容器 → `sh 路径`，只显示输出。
     */
    private fun runInTerminal(name: String, command: String) {
        // 先按终端设置里配置的 apt 源（默认清华）写容器 sources.list：让本页所有 apt 操作
        // （Firefox / Chrome 前置、更新依赖、常用组件等）都用配置源，避开官方源在国内的坏 IP。
        AptMirror.apply(requireContext())
        val src = "echo '[源] 容器 apt 源：${AptMirror.label(AptMirror.currentId(requireContext()))}'\n"
        TerminalFragment.runInlineScript(requireContext(), name, src + command)
    }
}
