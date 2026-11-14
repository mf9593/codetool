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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_packages, container, false)
        v.findViewById<MaterialButton>(R.id.btnUpdate).setOnClickListener {
            runInTerminal(
                "更新依赖",
                "apt-get update && apt-get -y upgrade"
            )
        }
        v.findViewById<MaterialButton>(R.id.btnMimo).setOnClickListener {
            runInTerminal(
                "安装 MiMo Code",
                "command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y curl ca-certificates; }\n" +
                    "curl -fsSL https://mimo.xiaomi.com/install | bash\n" +
                    com.codetool.app.ai.AiModelManager.FIX_PATH_SCRIPT
            )
        }
        // 安装 opencode：官方脚本安装（curl 不存在时先装 curl；失败时回退 npm）
        v.findViewById<MaterialButton>(R.id.btnOpencode).setOnClickListener {
            runInTerminal(
                "安装 opencode",
                "command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y curl ca-certificates; }\n" +
                    "echo '=== 1/2 官方脚本安装 opencode ==='\n" +
                    "curl -fsSL https://opencode.ai/install | bash\n" +
                    "if ! command -v opencode >/dev/null 2>&1; then\n" +
                    "  echo '=== 2/2 脚本安装失败，回退 npm 安装 ==='\n" +
                    "  command -v npm >/dev/null 2>&1 || { apt-get update && apt-get install -y npm nodejs; }\n" +
                    "  npm install -g opencode-ai\n" +
                    "fi\n" +
                    "command -v opencode >/dev/null 2>&1 && echo '[OK] opencode 安装完成：opencode' || echo '[ERROR] opencode 安装失败'\n" +
                    com.codetool.app.ai.AiModelManager.FIX_PATH_SCRIPT
            )
        }
        v.findViewById<MaterialButton>(R.id.btnSparkInstall).setOnClickListener {
            runInTerminal(
                "安装星火商店",
                "command -v wget >/dev/null 2>&1 || { apt-get update && apt-get install -y wget ca-certificates; }\n" +
                    "cd /tmp && rm -f spark-store.deb\n" +
                    "wget -L -O spark-store.deb 'https://gitee.com/spark-store-project/spark-store/releases/download/5.3.2/spark-store_5.3.2_arm64.deb'\n" +
                    "if dpkg-deb -I spark-store.deb >/dev/null 2>&1; then\n" +
                    "  echo '[OK] deb 文件有效，开始安装'\n" +
                    "  apt-get update\n" +
                    "  apt-get install -yf ./spark-store.deb\n" +
                    "  echo '星火商店安装完成：spark-store'\n" +
                    "else\n" +
                    "  echo '[ERROR] deb 文件下载损坏或无效'\n" +
                    "  ls -la spark-store.deb\n" +
                    "  file spark-store.deb\n" +
                    "fi"
            )
        }
        v.findViewById<MaterialButton>(R.id.btnApmInstall).setOnClickListener {
            runInTerminal(
                "安装 APM",
                "command -v wget >/dev/null 2>&1 || { apt-get update && apt-get install -y wget ca-certificates; }\n" +
                    "mkdir -p /etc/apt/trusted.gpg.d\n" +
                    "wget -q -O /etc/apt/trusted.gpg.d/apm.gpg 'https://gitee.com/amber-ce/amber-pm/raw/master/src/etc/apt/trusted.gpg.d/apm.gpg'\n" +
                    "if [ ${'$'}? -ne 0 ]; then\n" +
                    "  echo '[ERROR] GPG 密钥下载失败'\n" +
                    "  echo '  请前往 https://gitee.com/amber-ce/amber-pm/releases 手动下载 deb 安装'\n" +
                    "  exit 1\n" +
                    "fi\n" +
                    "echo 'deb [signed-by=/etc/apt/trusted.gpg.d/apm.gpg] https://d.spark-app.store/apm-deb-source /' > /etc/apt/sources.list.d/apm.list\n" +
                    "apt-get update\n" +
                    "apt-get install -y amber-pm\n" +
                    "echo 'APM 安装完成'\n" +
                    "echo '用法：apm install <包名> 安装应用'\n" +
                    "echo '网页商店：https://erotica.spark-app.store/arm64-apm/'"
            )
        }
        // 安装浏览器：Google Chrome + Mozilla Firefox（官方 arm64）
        v.findViewById<MaterialButton>(R.id.btnBrowserInstall).setOnClickListener {
            runInTerminal(
                "安装浏览器",
                "command -v wget >/dev/null 2>&1 || { apt-get update && apt-get install -y wget ca-certificates; }\n" +
                    "command -v gpg >/dev/null 2>&1 || apt-get install -y gnupg\n" +
                    "export DEBIAN_FRONTEND=noninteractive\n" +
                    "echo '=== 0/2 修复上次可能中断的 dpkg 状态 ==='\n" +
                    "dpkg --configure -a\n" +
                    "echo '=== 1/2 安装 Google Chrome (arm64 官方版) ==='\n" +
                    "( set -e\n" +
                    "  wget -qO- https://dl.google.com/linux/linux_signing_key.pub | gpg --batch --yes --dearmor -o /usr/share/keyrings/google-chrome.gpg\n" +
                    "  [ -s /usr/share/keyrings/google-chrome.gpg ] || { echo '[ERROR] Chrome 密钥下载失败（网络问题）'; exit 1; }\n" +
                    "  echo 'deb [signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main' > /etc/apt/sources.list.d/google-chrome.list\n" +
                    "  apt-get update && apt-get install -y google-chrome-stable\n" +
                    ") || echo '[ERROR] Chrome 安装中断，看上方报错'\n" +
                    "echo ''\n" +
                    "echo '=== 2/2 安装 Mozilla Firefox (arm64 官方版) ==='\n" +
                    "( set -e\n" +
                    "  install -d -m 0755 /etc/apt/keyrings\n" +
                    "  wget -qO- https://packages.mozilla.org/apt/repo-signing-key.gpg | tee /etc/apt/keyrings/packages.mozilla.org.asc >/dev/null\n" +
                    "  echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list\n" +
                    "  apt-get update && apt-get install -y firefox\n" +
                    ") || echo '[ERROR] Firefox 安装中断，看上方报错'\n" +
                    "echo ''\n" +
                    "echo '=== 安装结果 ==='\n" +
                    "command -v google-chrome >/dev/null 2>&1 && echo '[OK] Chrome 已可用：google-chrome' || echo '[ERROR] Chrome 未装上，请检查上方 apt 报错（大陆网络可能需要代理）'\n" +
                    "command -v firefox >/dev/null 2>&1 && echo '[OK] Firefox 已可用' || echo '[WARN] Firefox 未装上（不影响内置浏览器）'\n" +
                    "echo '装好后：终端点「显示」打开 GUI；工具 → MCP 页可查看浏览器状态'"
            )
        }
        // 启动星火商店：先开合成器，再强制 Wayland + 软件渲染（修复黑屏/崩溃）
        v.findViewById<MaterialButton>(R.id.btnSparkLaunch).setOnClickListener {
            val ctx = requireContext()
            // 1. 打开合成器窗口：native compositor 启动后才会有 wayland-0 socket
            try {
                ctx.startActivity(
                    Intent(ctx, TawcCompositorActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
            // 2. 等待 compositor socket 就绪（3.5s），再发启动命令
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    TerminalFragment.sendCommand(
                        ctx,
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
                },
                3500L
            )
        }
        refreshBrowserStatus()
        return v
    }

    override fun onResume() {
        super.onResume()
        refreshBrowserStatus()
    }

    /** 浏览器是「不预装、包管理一键装」：按钮实时反映 rootfs 里是否已装 Chrome。 */
    private fun refreshBrowserStatus() {
        val b = view?.findViewById<MaterialButton>(R.id.btnBrowserInstall) ?: return
        val installed = com.codetool.app.gui.DesktopApps.browserInstalled(requireContext())
        b.text = if (installed) "浏览器已安装（Chrome）· 点此重装"
        else "安装浏览器（Chrome / Firefox）"
    }

    /** 把命令发到当前终端标签，并切到终端页显示执行过程。 */
    private fun runInTerminal(name: String, command: String) {
        val ctx = requireContext()
        TerminalFragment.sendCommand(ctx, command)
        Toast.makeText(ctx, "已在终端执行：$name", Toast.LENGTH_SHORT).show()
    }
}
