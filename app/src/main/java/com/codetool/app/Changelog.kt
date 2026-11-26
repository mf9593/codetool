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
            version = "2.0.8",
            date = "2026-11-26",
            items = listOf(
                "工具页 Tab 调整为：脚本库 / AI 模型 / 包管理 / 定时任务 / Skill / MCP / Git / 下载 / 备份；新增「下载」页，构建环境卡片从包管理迁入（包管理顶部卡片并入原底部提示）。",
                "下载页新增在线下载列表（GitHub Release，默认 mf9593/codetool-env）：Android SDK 37、NDK r27d/r30b、build-tools、platform-tools、CMake、Flutter、JDK17/21 共 9 个 arm64 包；显示本地是否已下载与大小；前台服务 + WakeLock 后台下载，进度含百分比与已下载字节，支持单包/全部取消，后台下完切回自动刷新。",
                "下载设置：可改下载仓库（owner/repo）、镜像加速（ghfast.top 默认，另 gh-proxy.com / ghproxy.net / 自定义前缀）、自定义 GitHub 直链下载；镜像失败自动回退直连。",
                "构建包目录改为 /storage/emulated/0/Download/codetool/data/：下载页下载到这里，安装脚本直接扫描该目录（容器默认挂载 Download；不挂载模式才复制进容器）；NDK 归类兼容 r27d / r30b，多版本保留并自动选最高版。",
                "文件管理：地址栏新增刷新按钮；标题栏搜索支持当前路径递归（可关深层搜索）、文件名/文本内容（文本格式 ≤10MB、可区分大小写），结果点击跳转目录或定位文件。",
                "Skill 说明：gradle-mirror 改为内置中文说明；codetool-browser 修正为后台无头 Chrome（不再要求打开 GUI 合成器）。",
                "构建环境检测改为读 env 文件（/etc/profile.d/codetool-buildenv.sh）：按 JAVA_HOME / ANDROID_HOME / ANDROID_NDK_HOME / CMAKE_HOME / FLUTTER_HOME 定位组件，不再全盘扫描；30 秒缓存 + 手动刷新；版本号读真实文件（JDK release、NDK Pkg.Revision、build-tools 最高版本、SDK 最高 API、CMake share、Flutter version/commit）。",
                "终端 ^X 组合键对齐：统一左对齐 + 固定缩进；^X ↵ 的回车放大但不撑高行高，^X 与其它组合键水平、垂直一致。终端第二页 /v 键点击直接输入 /variants；说明弹窗补充【思考程度切换】，并说明部分按键按一次即等同长按（如 CTRL 粘性）、^X 表示 CTRL+X 组合键。",
                "日志筛选立即生效：修复筛选弹窗打开时列表不更新、必须等自动刷新才显示；快速连点不丢最后一次；切换筛选不重复读盘、Spannable 后台构建，减少卡顿。",
                "日志标签筛选更全：增量扫描整个运行日志（首次回看 4MB，之后只扫新增字节），覆盖 V/D/I/W/E/A/F 全部级别，上限 100。",
                "日志文件始终记录完整时间戳（yyyy-MM-dd HH:mm:ss.SSS），日志页按「显示日期 / 显示毫秒」裁切显示（旧日志缺失字段不补、不做推断）；时间戳用主题主色，WARN/ERROR 颜色跟随主题（浅/深模式与颜色变体）。",
                "mimo 思考强度档位恢复可选：支持 none/minimal/low/medium/high/xhigh/max，经 opencode 顶层 model#档位与 mimo variants 生效。",
            ),
        ),
        Entry(
            version = "2.0.7",
            date = "2026-11-25",
            items = listOf(
                "全项目安全与稳定性修复：AI 模型 / MCP 配置损坏时不再静默清空（解析失败不写盘）、配置写入改原子写（临时文件+重命名）防写坏、AI 模型与 MCP 共享写锁防并发互相覆盖、JSONC 写回前自动备份。",
                "安全加固：release 构建仅显式提供签名口令才签名、关闭 allowBackup（防 API Key / rootfs 外泄）、debug 后门按调用方 UID 校验（仅 shell/root/本应用）、FileProvider 共享收紧到 Download 目录。",
                "稳定性：合成器实例引用无条件清空（防旋转崩溃）、前台服务 startForeground 无条件调用 + 后台启动失败降级（防 ANR / 终端打不开）、崩溃日志只读尾部 200KB 防 OOM、AI 模型/首页/定时任务的主线程 IO 移后台线程。",
                "思考强度：支持全部档位（none/minimal/low/medium/high/xhigh/max），选档位经 opencode 顶层 model#档位与 mimo variants 生效；小米 MiMo 模型实测无档位（仅开/关思考），档位下拉自动禁用。",
                "锁屏联网修复：后台保活服务新增 WiFi 锁（锁屏 WiFi 省电不断开），并自动请求「忽略电池优化」豁免 Doze 网络限制——锁屏跑 AI 长任务/下载/安装不再断网。",
                "终端稳定性：会话计数改原子操作（防并发丢计数/多释放锁）、会话计数移到创建会话处（防服务启动竞态丢锁）、脚本运行文件名白名单（防路径穿越）、tar 恢复前校验（防穿越）、桌面应用启动脚本加超时看门狗。",
                "终端按键新增振动反馈：可在设置里开关、无极调节强度；方向键与退格长按连发时也轻振。",
                "终端页顶栏新增「说明」入口：分第一页/第二页列出每个按键的作用（含 AI 工具命令面板 ^P/^O/^X 组合键说明）。",
                "终端第二页补齐 ^O（最近会话+项目）、^R（重命名会话）按键，并给 ^P/^O 加长按提示气泡。",
                "终端前台服务改为 App 级保活常驻（与浏览器桥一致）：进程重启后终端服务也能自动复活，不再出现「只剩浏览器桥、终端服务没了」。",
                "修复终端服务与备份服务通知 ID 冲突（同为 1004），并去掉终端通知的「停止」按钮避免误触永久停掉服务。",
                "修复内置 MCP 服务补全逻辑：逐配置文件检查，避免某份配置丢失 mcp 段时被合并列表误判为已存在而漏补。",
                "首页系统状态「终端时间」去掉括号说明，显示更简洁。",
                "终端第二页 ^X U（撤销上一条消息）加二次确认弹窗防误触；^X ↵（排队发送）移到最右并把回车字符调大。",
            ),
        ),
        Entry(
            version = "2.0.6",
            date = "2026-11-22",
            items = listOf(
                "AI 模型同步改为写入全部启用模型到所选 CLI（不再按 target 切分），mimo 与 opencode 都能一次认得全部模型。",
                "模型卡片 CLI 归属标签改读两个 CLI 的真实配置文件判定（双标签并列、独立成行、靠左、同色、间距统一），不再用过时的 target 单字段误读成「只在一个 CLI」。",
                "修复模型归属判定自写正则去 JSONC 注释会砍坏 baseURL 的 https:// 导致 mimo 标签丢失的问题（改用现成 Jsonc 解析器）。",
                "AI 模型能力摘要不再被「上下文长度未知」连坐显示未知：工具调用 / 推理 / 图片能力无条件显示。",
                "上下文长度支持手填（应对 xfyun 等不返回能力字段的服务商）。",
                "同步结果 Toast 改为明确报「同步到哪些 CLI、各几个模型」，避免误读成只同步一个模型。",
                "关于页新增「官网」入口，跳转 GitHub 仓库主页。",
                "更新弹窗重构：去掉勾选框，改为「取消 / 不再提示 / 下载更新」三按钮，对齐更清晰。",
                "AI 模型页提示文案精简（去掉「（如 xiaomi 等）」括号）。",
                "移除 APM 安装入口（绑定星火商店生态，不再提供一键安装）。",
                "app-debug-rules 内置 skill 升级到 v1.1.0，合并「禁止用截图验证 UI」规范，并支持外部 skill 目录启动同步。",
                "修复 opencode 模型无法按服务商分组的问题：写入配置升级到 opencode V2 格式（providers 复数 + package + settings，弃用 V1 的 provider/npm/options），并为每个模型补 capabilities（工具/推理/温度/输入输出媒体类型）+ limit 分类元数据，模型列表按服务商分组、能看出能力差异。",
                "定时切换改为 mimo、opencode 两个 CLI 同时生效（此前因模型 target 恒为 mimo，到点只切 mimo、opencode 默认模型不动）。",
                "定时任务选模型标签改显示服务商（不再显示误导的 target），切换提示注明两个 CLI 同时生效。",
            ),
        ),
        Entry(
            version = "2.0.5",
            date = "2026-11-17",
            items = listOf(
                "合成器桌面「终端」不再安装 foot / weston-terminal（容器 GUI 终端打不开，且与 CodeTool 自带终端重复）：点桌面终端图标直接关闭合成器、切回底部「终端」页，复用自带终端。（注：本版本号发版后即被撤销回退到 2.0.4，但桌面终端复用代码保留生效。）",
            ),
        ),
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
