# 第三方组件与许可声明

CodeTool 本体以 **GNU General Public License v3.0** 发布，全文见根目录 [`LICENSE`](LICENSE)。

下列组件随源码或 APK 分发，各自保留原始许可证。本文件不改变任何上游许可证。

## 随 APK 分发的组件

| 组件 | 用途 | 许可证 | 源码 / 许可 |
|---|---|---|---|
| **tawc / tawcroot** | `app/src/main/jniLibs/arm64-v8a/libtawcroot.so`、`libtawc_compositor.so`：容器运行时与 Wayland 合成器 | GPL-3.0（部分代码 MIT） | https://github.com/taowen/tawc |
| **proot-distro rootfs** | `app/src/main/assets/ubuntu-noble-aarch64-pd-v4.17.3.tar.xz`：Ubuntu 24.04 aarch64 根文件系统 | 打包脚本 GPL-3.0；镜像内各软件包保留自身许可证（见镜像内 `/usr/share/doc/*/copyright`） | https://github.com/termux/proot-distro · 软件包源码：https://archive.ubuntu.com/ubuntu/pool/ |
| **Termux terminal-emulator** | `terminal-emulator/`：终端模拟器（源码集成） | Apache-2.0 | https://github.com/termux/termux-app · [`third_party/licenses/Apache-2.0.txt`](third_party/licenses/Apache-2.0.txt) |
| **Termux terminal-view** | `terminal-view/`：终端渲染视图（源码集成） | Apache-2.0 | 同上 |
| **sora-editor** | 代码编辑器（`io.github.Rosemoe.sora-editor:editor:0.23.6`） | LGPL-2.1 | https://github.com/Rosemoe/sora-editor · [`third_party/licenses/LGPL-2.1.txt`](third_party/licenses/LGPL-2.1.txt) |
| **xkeyboard-config** | `app/src/main/assets/xkb/`：键盘布局数据 | MIT | https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config |
| **glibc locale-archive** | `app/src/main/assets/locale/`：本地化数据 | LGPL-2.1+ | https://www.gnu.org/software/libc/ |
| **AndroidX / Material Components** | UI 基础库 | Apache-2.0 | https://cs.android.com/androidx · https://github.com/material-components/material-components-android |
| **Kotlin / kotlinx.coroutines** | 语言与协程 | Apache-2.0 | https://github.com/JetBrains/kotlin · https://github.com/Kotlin/kotlinx.coroutines |
| **XZ for Java** | XZ 解压（`org.tukaani:xz:1.10`） | Public Domain | https://tukaani.org/xz/java.html |

## 仅构建期使用（不随 APK 分发）

- Gradle、Android Gradle Plugin、Google Maven / Maven Central / 阿里云镜像 —— 仅作为构建工具与依赖下载源。

## 说明

- `termux-src/` 是 Termux 上游源码的本地参考副本（GPL-3.0），**不纳入本仓库**，构建未引用。
- Chrome / Firefox 不随 APK 分发，由用户在「工具 → 包管理」中自行安装；本项目不包含 Chrome 专有代码或商标授权。
- sora-editor 使用 LGPL-2.1；本项目整体以 GPL-3.0 发布，符合 LGPL-2.1 第 3 条转换为 GPL 的条件。
