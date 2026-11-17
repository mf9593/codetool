package com.codetool.app

/**
 * 更新日志（关于页「更新日志」入口展示）。
 *
 * 每次发版在 [entries] 顶部加一条 [Entry]；内容按实际功能变更编写，
 * 与 docs/devlog 对应。versionCode 单调递增。
 */
object Changelog {

    data class Entry(
        val version: String,
        val date: String,
        val items: List<String>,
    )

    val entries: List<Entry> = listOf(
        Entry(
            version = "2.0.4",
            date = "2026-11-17",
            items = listOf(
                "修复 Firefox 安装后包管理页仍显示「未安装」的问题（状态检测找错条目，恒返回未安装；现直接检测 Firefox 二进制）。",
                "合成器桌面新增 Firefox 浏览器图标：装好 Firefox 后桌面直接可启动，不再只显示 Chrome 一个浏览器。",
                "合成器桌面「终端」从 weston-terminal 换成 foot：原生 Wayland 终端，功能更全（滚动回看、字体配色、复制粘贴、可配置），轻量无需 GPU。",
                "Firefox 桌面图标安装走 Mozilla 官方 tarball 直装，绕开 apt/snapd（此前 apt 装会被 snapd 卡死）。",
            ),
        ),
        Entry(
            version = "2.0.3",
            date = "2026-11-17",
            items = listOf(
                "修复浏览器安装被 snapd 卡死的问题：Mozilla Firefox 的 apt 包硬依赖 snapd，而 snapd 在容器里无法完成配置导致安装必然失败。现改从 Mozilla 官方源下载 tarball 直装（解压到 /opt/firefox + 软链），完全绕开 apt/snapd。实机验证 Firefox 146.0 装上。",
                "Google Chrome 安装前置段容错加固：dpkg --configure -a 遇残留 snapd 报错不再中断整段；apt-get update 因失效第三方源失败也不再用一票否决中断安装。",
                "「更新依赖」升级为「更新依赖 · 装常用组件」：一键安装 ca-certificates、wget、curl、gnupg、git、unzip、xz-utils、bzip2，覆盖浏览器安装与 NDK/JDK 等脚本所需的下载和解压工具，各安装脚本不再各自临时装依赖（避免卡在 apt 上）。",
                "合成器首页默认应用图标（星火商店 / 浏览器 / 文件 / 终端）接上安装命令：点未安装的图标会真正执行对应安装脚本，不再只提示「暂无自动安装方式」。安装脚本统一在缺前置工具时给出指引而非卡死。",
            ),
        ),
        Entry(
            version = "2.0.2",
            date = "2026-11-16",
            items = listOf(
                "AI 模型定时切换迁入独立「定时任务」工具页（后续定时功能在此扩展），AI 模型页只留紧凑摘要入口。",
                "AI 模型定时切换改为 DeepSeek 计费口径：周一至周五（不含中国法定节假日）09:00–12:00、14:00–18:00 为高峰，其余（含午休、夜间、周末、法定节假日全天）为闲时；内置 2025–2027 中国法定节假日表（支持后续年份扩展）。",
                "定时切换新增两段高峰时间可编辑、周六/周日与法定节假日开关，到点自动切模型（后台也生效），检测到 CLI 仍在运行会弹防误触窗提醒重启终端。",
                "工具→备份页新增「可恢复的备份」列表（文件名 + 时间 + 大小），恢复逻辑不变。",
                "文件管理页修复「应用数据」入口选中高亮与其它入口不一致的问题。",
                "AI 模型配置文件编辑页标题栏新增「格式化」按钮（保留 JSON/JSONC 注释原文，只调整缩进）。",
                "容器与终端统一北京时间（UTC+8）：date、日志与高峰/闲时判断口径一致。",
                "MCP 新增 model_list / model_schedule_get / model_schedule_set 工具（读写同一份定时规则）。",
                "首页系统状态新增「终端时间」行（北京时间，每秒刷新，便于与终端 date 校准）。",
                "关于页「更新日志」改为独立全屏页（长文本滚动阅读，不再弹窗）。",
                "关于页「重新进入引导」改为清除标记、重启 App 后重走「用户协议 → 权限设置 → 环境安装」三页（不再立即跳转）。",
                "关于页新增「更新弹窗」预览入口（检查更新行长按），便于确认发现新版本的弹窗样式。",
                "「解除子进程限制」检测无需 Shizuku 即可判定（读系统设置）；Shizuku 仅作已授权时的交叉校验。",
                "引导页「全部文件访问权限」改为直跳本应用的系统开关页，无需再手动查找。",
                "修复深色模式下冷启动 / 页面切换闪白屏（起始窗口跟随系统深浅）。",
                "修复 AI 模型页卡片间距不一致（定时任务卡上下各 12dp，与其它卡片对齐）。",
            ),
        ),
        Entry(
            version = "2.0.1",
            date = "2026-11-15",
            items = listOf(
                "首次启动新增第三引导页：一键安装 AI 环境（python3 + MCP 桥 + 浏览器）。",
                "包管理页新增「安装 ADB」与一键修复入口，组件安装幂等（只装缺的）。",
                "MCP 页新增环境状态卡（python3 版本 / 文件桥 / 配置 + 一键修复入口）。",
                "关于页改版：三张中卡片（应用信息 / 操作行 / 参考开源项目），标题栏与返回对齐修复。",
                "脚本编辑页「保存」移入标题栏；内置示例脚本 02-greet 定稿。",
                "Skill 商店章节：7 个已验证商店 + 强制安全审查；内置 multi-search-engine、dev-god 等 skill 集成。",
                "配置 / skill 编辑页标题栏按钮（复制 / 说明 / 保存）修复不显示问题。",
                "sora-editor TextMate 语法高亮（多格式，跟随主题）；终端字体默认 30。",
                "挂载目录改为下拉框；深色模式标题栏跟随主题。",
                "GitHub Actions 自动构建 APK（keystore 缺失时回退签名）；源码备份。",
            ),
        ),
        Entry(
            version = "2.0.0",
            date = "2026-11-13",
            items = listOf(
                "CodeTool 基础能力：tawcroot 容器 + tawc 合成器（GUI 显示）、终端会话与脚本库。",
                "AI 模型管理：多模型（商汤 SenseNova 等）+ 服务商地址/Key + 测试连接 + 导入现有 + 写入 CLI 配置。",
                "Skill / MCP 统一管理页，内置 codetool-browser、codetool-system 等 skill 与浏览器 MCP server。",
                "文件管理：内部存储 + 应用数据 + 日志快捷入口、路径收藏。",
                "子进程限制检测与一键解除（Shizuku 优先，诚实 Unknown）。",
                "Shizuku 全链路：status / exec / screenshot / uidump，容器内 pm 等命令可直接用。",
                "首页系统状态卡（rootfs / 路径 / tawcroot / 终端服务 / AI），主题色状态图标。",
            ),
        ),
    )
}
