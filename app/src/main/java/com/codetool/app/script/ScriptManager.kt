package com.codetool.app.script

import android.content.Context
import android.os.Environment
import java.io.File

/** 一个被协议识别为 codetool 脚本的脚本文件。 */
data class ScriptInfo(
    val file: File,
    val name: String,
    val desc: String,
    /** 参数定义（`#codetool-param:` 行，按顺序作为 $1/$2...）。 */
    val params: List<String>
)

/**
 * 脚本库管理：协议识别 + 示例 + 收藏。
 *
 * 协议标准头（必须带对应协议才能识别，固定格式，APP 解析后弹窗显示）：
 * ```
 * #!/bin/sh
 * #codetool: <标题>                 # 弹窗标题
 * #codetool-desc: <说明>            # 弹窗说明
 * #codetool-param: <参数标签>        # 参数输入框（可多行，按顺序作为 $1/$2...）
 * <脚本体>
 * ```
 * 仅当文件前 10 行内出现 `#codetool:` 行，才被识别为 codetool 脚本。
 * 不带协议头的普通 .sh 文件会被忽略。
 *
 * 脚本目录：/storage/emulated/0/Download/codetool/script
 * 收藏（脚本 + 路径）存于 SharedPreferences。
 */
object ScriptManager {

    private const val PREFS = "codetool_scripts"
    private const val KEY_SCRIPT_FAVS = "script_favs"
    private const val KEY_PATH_FAVS = "path_favs"

    /** 脚本目录（外部存储，用户可自行放入 .sh）。 */
    val scriptDir: File
        get() = File(Environment.getExternalStorageDirectory(), "Download/codetool/script")

    // ---------------- 协议识别 ----------------

    /** 是否被识别为 codetool 脚本（前 10 行含 #codetool:）。 */
    fun isScript(file: File): Boolean = parseScript(file) != null

    /** 解析脚本协议头；不合法返回 null。 */
    fun parseScript(file: File): ScriptInfo? {
        if (!file.isFile || file.length() > 256 * 1024) return null
        return try {
            val head = file.readText().lineSequence().take(10).toList()
            val nameLine = head.firstOrNull { it.trimStart().startsWith("#codetool:") } ?: return null
            val name = nameLine.substringAfter("#codetool:", "").trim()
            val desc = head.firstOrNull { it.trimStart().startsWith("#codetool-desc:") }
                ?.substringAfter("#codetool-desc:", "")?.trim() ?: ""
            // 参数：每个 #codetool-param: 一行，按顺序作为 $1/$2...
            val params = head.filter { it.trimStart().startsWith("#codetool-param:") }
                .map { it.substringAfter("#codetool-param:", "").trim() }
                .filter { it.isNotEmpty() }
            if (name.isEmpty()) null else ScriptInfo(file, name, desc, params)
        } catch (e: Exception) {
            null
        }
    }

    /** 备份 / 临时文件不算脚本：否则旧的 .bak 会以同名出现在库里，用户容易点错。 */
    private val JUNK_SUFFIX = listOf(".bak", ".old", ".orig", ".tmp", ".swp", ".save", ".disabled")

    private fun isJunk(f: File): Boolean {
        val n = f.name
        if (n.startsWith(".") || n.endsWith("~")) return true
        val lower = n.lowercase()
        return JUNK_SUFFIX.any { lower.endsWith(it) }
    }

    /** 列出脚本目录内所有被识别为 codetool 脚本的文件（跳过备份/隐藏文件）。 */
    fun listScripts(): List<ScriptInfo> {
        val dir = scriptDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.asSequence()
            ?.filter { it.isFile && !isJunk(it) }
            ?.mapNotNull { parseScript(it) }
            ?.sortedBy { it.name }
            ?.toList() ?: emptyList()
    }

    // ---------------- 示例 ----------------

    /** 旧版示例（升级后自动清理，避免库里堆积；用户自建脚本不受影响）。 */
    private val legacyExamples = listOf(
        "02-hello.sh", "03-date.sh", "04-git-status.sh", "05-greet.sh", "06-mimocode.sh"
    )

    /** 写入内置示例脚本（只保留两个：一个不带参数、一个带参数输入框）。返回写入的文件数。 */
    fun createExamples(ctx: Context? = null): Int {
        val dir = scriptDir
        if (!dir.exists() && !dir.mkdirs()) {
            ctx?.let { com.codetool.app.util.FileLog.e(it, "Scripts", "示例脚本目录创建失败: ${dir.path}") }
            return 0
        }
        val cleaned = legacyExamples.filter { File(dir, it).exists() }
        cleaned.forEach { File(dir, it).delete() }
        if (cleaned.isNotEmpty()) {
            ctx?.let { com.codetool.app.util.FileLog.i(it, "Scripts", "清理旧示例: ${cleaned.joinToString()}") }
        }
        var count = 0
        // 示例 1：不带参数
        run {
            val f = File(dir, "01-sysinfo.sh")
            if (!f.exists()) {
                f.writeText(
                    "#!/bin/sh\n" +
                        "#codetool: 系统信息\n" +
                        "#codetool-desc: 查看内核 / 系统版本 / 内存 / 磁盘（不带参数示例）\n" +
                        "echo \"==== 系统信息 ====\"\n" +
                        "uname -a\n" +
                        "cat /etc/os-release | head -3\n" +
                        "free -h 2>/dev/null || echo '(无 free)'\n" +
                        "df -h / | tail -1\n" +
                        "uptime\n"
                )
                f.setExecutable(true)
                count++
            }
        }
        // 示例 2：带参数输入框
        run {
            val f = File(dir, "02-greet.sh")
            if (!f.exists()) {
                f.writeText(
                    "#!/bin/sh\n" +
                        "#codetool: 问候（带参数）\n" +
                        "#codetool-desc: 带一个参数输入框的示例（名字）\n" +
                        "#codetool-param: 名字（如 张三）\n" +
                        "echo \"你好，\${1:-世界}！\"\n" +
                        "echo \"今天是 \$(date '+%Y-%m-%d %H:%M:%S')\"\n"
                )
                f.setExecutable(true)
                count++
            }
        }
        // 示例 3：启动 opencode（带参数输入框）
        run {
            val f = File(dir, "04-opencode.sh")
            if (!f.exists()) {
                f.writeText(
                    "#!/bin/sh\n" +
                        "#codetool: 启动 opencode\n" +
                        "#codetool-desc: 启动 opencode CLI，可指定工作目录与附加参数（如模型、提示词）\n" +
                        "#codetool-param: 工作目录（可留空，默认主目录）\n" +
                        "#codetool-param: opencode 附加参数（可留空，原样传给 opencode）\n" +
                        "DIR=\"\${1:-}\"\n" +
                        "ARGS=\"\${2:-}\"\n" +
                        "\n" +
                        "if [ -n \"\$DIR\" ]; then\n" +
                        "  cd \"\$DIR\" 2>/dev/null || { echo \"目录不存在: \$DIR\"; exit 1; }\n" +
                        "fi\n" +
                        "\n" +
                        "if ! command -v opencode >/dev/null 2>&1; then\n" +
                        "  echo \"未找到 opencode，请先在终端安装：\"\n" +
                        "  echo \"  curl -fsSL https://opencode.ai/install | bash\"\n" +
                        "  exit 1\n" +
                        "fi\n" +
                        "\n" +
                        "if [ -n \"\$ARGS\" ]; then\n" +
                        "  exec opencode \$ARGS\n" +
                        "else\n" +
                        "  exec opencode\n" +
                        "fi\n"
                )
                f.setExecutable(true)
                count++
            }
        }
        return count
    }

    // ---------------- 内置本地脚本 ----------------

    /**
     * 确保「安装构建环境」脚本存在且为最新版。
     * 脚本正文放在 assets/scripts/03-buildenv.sh（单一来源）；每次刷新时对比内容，
     * 不一致就覆盖，保证 app 升级后用户目录里的旧脚本自动更新。
     */
    fun ensureLocalScripts(ctx: Context): Int {
        val dir = scriptDir
        if (!dir.exists() && !dir.mkdirs()) return 0
        val script = try {
            ctx.assets.open("scripts/03-buildenv.sh").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            com.codetool.app.util.FileLog.e(ctx, "Scripts", "读取内置 buildenv 脚本失败: ${e.message}")
            return 0
        }
        val f = File(dir, "03-buildenv.sh")
        val old = if (f.exists()) f.readText() else null
        if (old == script) return 0
        f.writeText(script)
        f.setExecutable(true)
        com.codetool.app.util.FileLog.i(ctx, "Scripts", "03-buildenv.sh 已${if (old == null) "写入" else "更新为最新版"}")
        return 1
    }

    // ---------------- 收藏（脚本） ----------------

    private fun favPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun favScripts(ctx: Context): Set<String> =
        favPrefs(ctx).getStringSet(KEY_SCRIPT_FAVS, emptySet())?.toSet() ?: emptySet()

    fun isScriptFav(ctx: Context, path: String): Boolean = path in favScripts(ctx)

    fun toggleScriptFav(ctx: Context, path: String) {
        val p = favPrefs(ctx)
        val cur = p.getStringSet(KEY_SCRIPT_FAVS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!cur.add(path)) cur.remove(path)
        p.edit().putStringSet(KEY_SCRIPT_FAVS, cur).apply()
    }

    // ---------------- 脚本参数记忆 ----------------

    private const val KEY_PARAMS_PREFIX = "params:"

    /** 读取上次运行该脚本时填写的参数（按位置）；没有记录返回空列表。 */
    fun savedParams(ctx: Context, scriptPath: String): List<String> {
        val raw = favPrefs(ctx).getString(KEY_PARAMS_PREFIX + scriptPath, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }
        }.getOrDefault(emptyList())
    }

    /** 点「运行」后记住本次参数；空值也保存，用户清空后下次不会又冒出旧值。 */
    fun saveParams(ctx: Context, scriptPath: String, args: Array<String>) {
        val arr = org.json.JSONArray()
        args.forEach { arr.put(it) }
        favPrefs(ctx).edit().putString(KEY_PARAMS_PREFIX + scriptPath, arr.toString()).apply()
    }

    /** 脚本删除时一并清掉参数，避免路径复用后带出旧值。 */
    fun forgetParams(ctx: Context, scriptPath: String) {
        favPrefs(ctx).edit().remove(KEY_PARAMS_PREFIX + scriptPath).apply()
    }

    // ---------------- 收藏（路径，文件管理） ----------------

    fun favPaths(ctx: Context): Set<String> =
        favPrefs(ctx).getStringSet(KEY_PATH_FAVS, emptySet())?.toSet() ?: emptySet()

    fun isPathFav(ctx: Context, path: String): Boolean = path in favPaths(ctx)

    fun togglePathFav(ctx: Context, path: String) {
        val p = favPrefs(ctx)
        val cur = p.getStringSet(KEY_PATH_FAVS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!cur.add(path)) cur.remove(path)
        p.edit().putStringSet(KEY_PATH_FAVS, cur).apply()
    }
}
