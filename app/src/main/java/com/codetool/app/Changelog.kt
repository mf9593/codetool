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
            version = "2.0.2",
            date = "2026-11-16",
            items = listOf(
                "AI 模型定时切换改为 DeepSeek 计费口径：周一至周五（不含中国法定节假日）09:00–12:00、14:00–18:00 为高峰，其余（含午休、夜间、周末、法定节假日全天）为闲时；内置 2025–2027 中国法定节假日表（支持后续年份扩展）。",
                "定时切换新增两段高峰时间可编辑、周六/周日与法定节假日开关，到点自动切模型（后台也生效），检测到 CLI 仍在运行会弹防误触窗提醒重启终端。",
                "工具→备份页新增「可恢复的备份」列表（文件名 + 时间 + 大小），恢复逻辑不变。",
                "文件管理页修复「应用数据」入口选中高亮与其它入口不一致的问题。",
                "AI 模型配置文件编辑页标题栏新增「格式化」按钮（保留 JSON/JSONC 注释原文，只调整缩进）。",
                "容器与终端统一北京时间（UTC+8）：date、日志与高峰/闲时判断口径一致。",
                "MCP 新增 model_list / model_schedule_get / model_schedule_set 工具（读写同一份定时规则）。",
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
