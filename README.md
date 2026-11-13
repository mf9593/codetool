# CodeTool

手机上的 Linux 终端容器：**锁屏熄屏不断网**，内置终端、脚本库、包管理、AI 模型配置、Skill / MCP 管理。

基于 [tawcroot](https://github.com/taowen/tawc/tree/main/tawcroot)（无 root、非 proot）运行 Ubuntu 24.04 aarch64 rootfs，并复用 Termux 的终端模拟器渲染。

## 特性

| 页面 | 功能 |
|---|---|
| 首页 | rootfs / tawcroot / 终端服务 / AI 状态一目了然，一键启停终端服务 |
| 终端 | Termux 终端视图，多标签会话，直接运行 Ubuntu rootfs 内的 bash |
| 工具 | 脚本库、包管理、AI 模型、Skill、MCP、rootfs 备份，同页 tab 切换 |
| 文件管理 | 浏览 / 编辑 / 删除容器内外文件，内置代码编辑器 |
| 日志 | 查看终端服务与运行日志 |

工具页细分：

- **脚本库**：添加 / 编辑 / 删除脚本，支持参数；参数按脚本路径记住，下次自动回填
- **包管理**：apt update / upgrade / 搜索 / 安装，非交互执行；一键安装 Chrome / Firefox（**不预装进 APK**）
- **AI 模型**：多模型管理，服务商下拉选择，自定义 OpenAI 兼容服务商，支持 Responses API 与「测试连接」
- **Skill**：统一扫描 `~/.agents/skills`、`~/.config/mimocode/skills`、`~/.config/opencode/skills`、`~/.claude/skills`、`~/.codex/skills`，目录式与单文件都支持；编辑写回原文件
- **MCP**：合并 MiMo Code / OpenCode 配置，兼容 JSONC（注释、尾逗号），保留 `headers` / `environment` 等未知字段，带启停开关
- **备份**：rootfs 打包 / 恢复（放工具页，避免首页误触）

关于页另有 **检查更新**：一键从 GitHub Releases 检查最新版本，支持国内镜像下载。

## 核心原理

ACSIDE 等终端 App 锁屏约 1 分钟后，系统进入 deep suspend，CPU 挂起、前台服务被冻结，长连接随之中断。

CodeTool 的 `TerminalService` 持有：

- `PARTIAL_WAKE_LOCK`：锁屏后 CPU 不休眠
- `WifiLock (FULL_HIGH_PERF)`：WiFi 无线电保持活跃
- 引用计数：无活跃会话时释放，避免无谓耗电

Linux 容器由 [tawcroot](https://github.com/taowen/tawc) 提供，单进程、无 root，性能优于 PRoot。GUI 能力由 tawc 合成器提供，通过共享目录文件桥与 Android 侧通信。

## 环境要求

- Android 8.0+（minSdk 26），**仅 arm64-v8a**
- 首次启动需解压约 100MB rootfs，请预留存储空间
- 构建需要 JDK 17、Android SDK（compileSdk 36）

## 构建

```bash
git clone https://github.com/mf9593/codetool.git
cd codetool

# 1. 配置 SDK 路径（Android Studio 会自动生成）
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 2. 构建 Debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

仓库已包含：

- `app/src/main/assets/ubuntu-noble-aarch64-pd-v4.17.3.tar.xz` —— Ubuntu 24.04 aarch64 rootfs（来自 proot-distro）
- `app/src/main/jniLibs/arm64-v8a/libtawcroot.so` —— tawcroot 运行时
- `app/src/main/jniLibs/arm64-v8a/libtawc_compositor.so` —— tawc Wayland 合成器

一般无需重新编译原生库。需要自行编译 tawcroot 时：

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<版本>
bash scripts/build-tawcroot.sh
```

> 所有 arm64 原生库必须 16KB 页对齐（Android 15+ 要求），脚本已带 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`。

## 开源协议

本项目以 **GNU General Public License v3.0** 发布，全文见 [`LICENSE`](LICENSE)。

随项目分发的第三方组件（tawc / tawcroot、proot-distro rootfs、Termux 终端库、sora-editor 等）及其许可证见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

> `termux-src/` 是 Termux 上游源码的本地参考副本，**不在本仓库中**，构建未引用。

## 开发记录

每次代码改动都记录在 [`docs/devlog/`](docs/devlog/README.md)，当天一个文件、按任务分节。

- [2026-11-13](docs/devlog/2026-11-13.md)

## 目录结构

```
codetool/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/                     # rootfs、脚本、xkb、locale
│       ├── jniLibs/arm64-v8a/           # tawcroot / tawc compositor
│       ├── java/com/codetool/app/
│       │   ├── MainActivity.kt          # 5 页 + 底部导航 + 滑动
│       │   ├── HomeFragment.kt          # 首页：状态 + 开关
│       │   ├── TerminalFragment.kt      # 终端画面
│       │   ├── ToolsFragment.kt         # 工具页 6 个 tab
│       │   ├── ScriptsFragment.kt       # 脚本库
│       │   ├── PackagesFragment.kt      # 包管理
│       │   ├── AiModelsFragment.kt      # AI 模型
│       │   ├── SkillsFragment.kt        # Skill 管理
│       │   ├── McpFragment.kt           # MCP 管理
│       │   ├── RootfsBackupFragment.kt  # 备份 / 恢复
│       │   ├── FilesFragment.kt         # 文件管理
│       │   ├── TasksFragment.kt         # 日志
│       │   ├── terminal/
│       │   │   ├── TerminalService.kt   # 前台服务 + WakeLock（不断网核心）
│       │   │   └── TerminalSessionManager.kt
│       │   ├── gui/                     # tawc 合成器 Activity / JNI
│       │   ├── ai/                      # AI 模型、Skill、MCP 管理
│       │   ├── runtime/                 # tawcroot、rootfs、备份
│       │   └── util/                    # JSONC 解析等
│       └── res/                         # 布局 / 菜单 / 图标
├── terminal-emulator/                   # Termux 终端模拟器（Apache-2.0）
├── terminal-view/                       # Termux 终端视图（Apache-2.0）
├── scripts/build-tawcroot.sh
├── docs/devlog/                         # 开发记录
├── LICENSE
├── THIRD_PARTY_NOTICES.md
├── settings.gradle.kts
└── build.gradle.kts
```

## 致谢

- [tawc / tawcroot](https://github.com/taowen/tawc) —— Linux 容器运行时与 Wayland 合成器
- [Termux](https://github.com/termux/termux-app) —— terminal-emulator / terminal-view
- [proot-distro](https://github.com/termux/proot-distro) —— Ubuntu rootfs
- [sora-editor](https://github.com/Rosemoe/sora-editor) —— 代码编辑器
- [Smithay](https://github.com/smithay/smithay)、[libhybris](https://github.com/libhybris/libhybris)、[labwc](https://github.com/labwc/labwc)

## 免责声明

本项目与 Google、Termux、Ubuntu、Canonical 无隶属关系。Chrome / Firefox 由用户自行安装，APK 不包含其二进制或商标授权。
