package com.codetool.app.git

import android.content.Context
import android.net.Uri
import android.system.Os
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Git 凭据管理：把 GitHub / Gitee 的 token 与 SSH 私钥写进**容器 HOME**，
 * 让容器里的 `git`、以及模型（mimo / opencode）执行的 shell 命令直接读取，
 * 不需要每次把 token / 私钥贴进对话。
 *
 * 落盘位置（容器内，均为 root 身份可见）：
 * - `~/.git-credentials-github` / `~/.git-credentials-gitee`：两个平台**各存一份**，
 *   通过 `credential.<host>.helper = store --file=...` 按 host 读取，互不影响；
 * - `~/.gitconfig`：user.name / user.email（通过 `git config --global` 写入，**保留其它配置**）；
 * - `~/.ssh/id_ed25519(.pub)`：SSH 密钥（ssh-keygen 在本机容器内核生成，私钥不经过网络）。
 *
 * 安全约定：token 只在 App 与容器本地文件之间传递，不上传到任何第三方服务；
 * 页面上只显示脱敏后的 token。注意：同容器的模型仍具备读取这些文件的权限（见页面安全提示）。
 */
object GitConfigManager {

    data class Provider(
        val id: String,
        val label: String,
        val host: String,
        val apiUserUrl: String,
        /** true：GitHub 走 `Authorization: Bearer`；false：Gitee 走 `access_token` 查询参数。 */
        val bearer: Boolean,
    )

    val PROVIDERS = listOf(
        Provider("github", "GitHub", "github.com", "https://api.github.com/user", true),
        Provider("gitee", "Gitee", "gitee.com", "https://gitee.com/api/v5/user", false),
    )

    data class Account(val user: String, val token: String) {
        /** 脱敏显示：`abcd…wxyz`，短 token 只显示 `****`。 */
        val maskedToken: String
            get() = if (token.length <= 8) "****" else token.take(4) + "…" + token.takeLast(4)
    }

    // ---------------- 路径 ----------------

    private fun root(ctx: Context) = RootfsManager.get(ctx.applicationContext).containerRoot
    private fun home(ctx: Context) = File(root(ctx), "root")
    /** 每个平台一份凭据文件，路径同时写进 git 的 per-host helper。 */
    private fun credFile(ctx: Context, p: Provider) = File(home(ctx), ".git-credentials-${p.id}")
    /** 旧版共享文件：只用于一次性迁移，迁移后删除。 */
    private fun legacyCredFile(ctx: Context) = File(home(ctx), ".git-credentials")
    private fun gitConfigFile(ctx: Context) = File(home(ctx), ".gitconfig")
    private fun sshDir(ctx: Context) = File(home(ctx), ".ssh")

    /** 容器是否已解压（未就绪时不写文件，避免在空目录里造出一堆残留）。 */
    fun containerReady(ctx: Context): Boolean = File(root(ctx), "bin/bash").exists()

    // ---------------- token 凭据 ----------------

    private fun provider(host: String): Provider? = PROVIDERS.firstOrNull { it.host == host }

    /**
     * 读取已保存的 https 凭据（按 host 索引）。
     * 每个平台只读自己的文件；旧版共享文件在迁移完成前作为兜底。
     */
    fun accounts(ctx: Context): Map<String, Account> {
        val out = LinkedHashMap<String, Account>()
        for ((host, acc) in readCredLines(legacyCredFile(ctx))) {
            if (provider(host) != null) out[host] = acc
        }
        for (p in PROVIDERS) {
            readCredFile(credFile(ctx, p))?.let { (_, acc) -> out[p.host] = acc }
        }
        return out
    }

    fun account(ctx: Context, host: String): Account? = accounts(ctx)[host]

    /** 保存某个平台的 token：只写该平台自己的文件，另一个平台完全不动。 */
    fun saveAccount(ctx: Context, host: String, user: String, token: String): Result<Unit> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪，请先完成初始化"))
        val p = provider(host) ?: return Result.failure(IllegalArgumentException("不支持的平台：$host"))
        return runCatching {
            writeProviderFile(ctx, p, Account(user, token))
            removeLegacyHost(ctx, host)
            ensureHostHelper(ctx, p)
            gitConfig(ctx, "init.defaultBranch", "main")
        }
    }

    /** 清除某个平台的凭据（不影响另一个平台）。 */
    fun clearAccount(ctx: Context, host: String): Result<Unit> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        val p = provider(host) ?: return Result.failure(IllegalArgumentException("不支持的平台：$host"))
        return runCatching {
            credFile(ctx, p).delete()
            removeLegacyHost(ctx, host)
        }
    }

    /** 旧版 `~/.git-credentials` 里是否有本页管理的平台凭据（有才需要后台迁移）。 */
    fun hasLegacyCredentials(ctx: Context): Boolean {
        val f = legacyCredFile(ctx)
        if (!f.exists()) return false
        return runCatching { f.readLines() }.getOrDefault(emptyList()).any { line ->
            CRED_RE.find(line.trim())?.let { provider(it.groupValues[3]) != null } == true
        }
    }

    /**
     * 一次性迁移：旧版把 GitHub / Gitee 写在同一个 `~/.git-credentials`，
     * 升级后拆成独立文件，并给每个 host 配置独立的 store helper。IO 线程调用。
     */
    fun migrateLegacy(ctx: Context) {
        if (!containerReady(ctx)) return
        val legacy = legacyCredFile(ctx)
        if (!legacy.exists()) return
        val entries = runCatching { legacy.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val m = CRED_RE.find(line.trim()) ?: return@mapNotNull null
            val p = provider(m.groupValues[3]) ?: return@mapNotNull null
            p to Account(Uri.decode(m.groupValues[1]), Uri.decode(m.groupValues[2]))
        }
        if (entries.isEmpty()) return
        for ((p, acc) in entries) {
            if (credFile(ctx, p).exists()) continue
            runCatching {
                writeProviderFile(ctx, p, acc)
                ensureHostHelper(ctx, p)
            }
        }
        runCatching {
            val remaining = legacy.readLines().filterNot { line ->
                CRED_RE.find(line.trim())?.let { provider(it.groupValues[3]) != null } == true
            }
            if (remaining.isEmpty()) legacy.delete()
            else {
                legacy.writeText(remaining.joinToString("\n", postfix = "\n"))
                chmod(legacy, 0x180)
            }
        }
    }

    private fun readCredFile(f: File): Pair<String, Account>? {
        if (!f.exists()) return null
        val line = runCatching { f.readLines().firstOrNull { it.isNotBlank() } }.getOrNull() ?: return null
        val m = CRED_RE.find(line.trim()) ?: return null
        return m.groupValues[3] to Account(Uri.decode(m.groupValues[1]), Uri.decode(m.groupValues[2]))
    }

    private fun readCredLines(f: File): List<Pair<String, Account>> {
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val m = CRED_RE.find(line.trim()) ?: return@mapNotNull null
            m.groupValues[3] to Account(Uri.decode(m.groupValues[1]), Uri.decode(m.groupValues[2]))
        }
    }

    private fun writeProviderFile(ctx: Context, p: Provider, a: Account) {
        val f = credFile(ctx, p)
        f.parentFile?.mkdirs()
        f.writeText("https://${Uri.encode(a.user)}:${Uri.encode(a.token)}@${p.host}\n")
        chmod(f, 0x180) // 0600
    }

    /** 让 git 只对这一个 host 使用专属凭据文件（store helper 支持 --file）。 */
    private fun ensureHostHelper(ctx: Context, p: Provider) {
        gitConfig(ctx, "credential.https://${p.host}.helper", "store --file=/root/.git-credentials-${p.id}")
    }

    private fun removeLegacyHost(ctx: Context, host: String) {
        val legacy = legacyCredFile(ctx)
        if (!legacy.exists()) return
        val lines = runCatching { legacy.readLines() }.getOrDefault(emptyList())
        val kept = lines.filterNot { line ->
            CRED_RE.find(line.trim())?.groupValues?.get(3) == host
        }
        if (kept.size == lines.size) return
        if (kept.isEmpty()) legacy.delete()
        else {
            legacy.writeText(kept.joinToString("\n", postfix = "\n"))
            chmod(legacy, 0x180)
        }
    }

    /** 用 `git config --global` 写入，保留用户/模型已有的其它 gitconfig 内容。 */
    private fun gitConfig(ctx: Context, key: String, value: String) {
        val p = TawcrootRuntime.get(ctx).exec(root(ctx), "/usr/bin/git", "config", "--global", key, value)
        p.inputStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        if (code != 0) throw IOException("git config $key 失败（exit=$code）")
    }

    // ---------------- 提交身份 ----------------

    fun identity(ctx: Context): Pair<String, String> {
        val f = gitConfigFile(ctx)
        if (!f.exists()) return "" to ""
        var section = ""
        var name = ""
        var email = ""
        runCatching { f.readLines() }.getOrDefault(emptyList()).forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") ->
                    section = line.substringAfter("[").substringBefore("]").trim().substringBefore(" ")
                section == "user" && line.startsWith("name") && line.contains("=") ->
                    name = line.substringAfter("=").trim()
                section == "user" && line.startsWith("email") && line.contains("=") ->
                    email = line.substringAfter("=").trim()
            }
        }
        return name to email
    }

    fun saveIdentity(ctx: Context, name: String, email: String): Result<Unit> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        return runCatching {
            if (name.isNotBlank()) gitConfig(ctx, "user.name", name.trim())
            if (email.isNotBlank()) gitConfig(ctx, "user.email", email.trim())
        }
    }

    // ---------------- SSH ----------------

    fun sshPrivateKey(ctx: Context) = File(sshDir(ctx), "id_ed25519")
    fun sshPublicKey(ctx: Context): String? =
        File(sshDir(ctx), "id_ed25519.pub")
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }

    /** 生成 ed25519 密钥（已存在则直接返回现有公钥）。 */
    fun generateSshKey(ctx: Context): Result<String> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        val dir = sshDir(ctx).apply { mkdirs() }
        chmod(dir, 0x1C0) // 0700
        val key = sshPrivateKey(ctx)
        if (key.exists()) {
            return Result.success(sshPublicKey(ctx) ?: "密钥已存在")
        }
        return runCatching {
            val p = TawcrootRuntime.get(ctx).exec(
                root(ctx), "/usr/bin/ssh-keygen", "-t", "ed25519", "-N", "", "-C", "codetool",
                "-f", "/root/.ssh/id_ed25519"
            )
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code != 0 || !key.exists()) {
                throw IOException("ssh-keygen 失败（exit=$code）：${out.take(300)}")
            }
            chmod(key, 0x180) // 0600
            File(dir, "id_ed25519.pub").takeIf { it.exists() }?.let { chmod(it, 0x1A4) } // 0644
            // 预置 known_hosts，模型首次 ssh 时不必交互确认主机指纹
            runCatching {
                val scan = TawcrootRuntime.get(ctx).exec(
                    root(ctx), "/bin/bash", "-lc",
                    "command -v ssh-keyscan >/dev/null 2>&1 && " +
                        "ssh-keyscan github.com gitee.com 2>/dev/null >> /root/.ssh/known_hosts || true"
                )
                scan.inputStream.bufferedReader().use { it.readText() }
                scan.waitFor()
                File(dir, "known_hosts").takeIf { it.exists() }?.let { chmod(it, 0x1A4) }
            }
            sshPublicKey(ctx) ?: out.trim()
        }
    }

    /** 用当前密钥测试 GitHub / Gitee 的 SSH 认证（ssh -T）。 */
    fun verifySsh(ctx: Context): Result<String> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        if (!sshPrivateKey(ctx).exists()) return Result.failure(IllegalStateException("还没有 SSH 密钥，请先生成"))
        val report = StringBuilder()
        var allOk = true
        for (host in listOf("github.com", "gitee.com")) {
            var ok = false
            var detail = ""
            var code = -1
            val err = runCatching {
                val p = TawcrootRuntime.get(ctx).exec(
                    root(ctx), "/usr/bin/ssh", "-T",
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=12",
                    "git@$host"
                )
                val out = p.inputStream.bufferedReader().use { it.readText() }
                code = p.waitFor()
                detail = out.trim()
                ok = out.contains("successfully authenticated", true) || out.contains("Hi ", true)
            }.exceptionOrNull()
            if (err != null) {
                allOk = false
                report.append("✘ ").append(host).append("：").append(err.message ?: "执行失败").append('\n')
                continue
            }
            if (!ok) allOk = false
            report.append(if (ok) "✔ " else "✘ ").append(host)
            if (!ok) report.append("（exit=$code）")
            report.append('\n')
            if (!ok && detail.isNotEmpty()) report.append(detail.take(300)).append('\n')
        }
        return if (allOk) Result.success(report.toString().trim())
        else Result.failure(IOException(report.toString().trim()))
    }

    // ---------------- HTTPS token 验证 ----------------

    /**
     * 用官方 API 校验 token 并返回账号名。
     * 必须在后台线程调用（带 10s 超时）。
     */
    fun verifyToken(provider: Provider, token: String): Result<String> {
        val url = if (provider.bearer) provider.apiUserUrl
        else provider.apiUserUrl + "?access_token=" + Uri.encode(token)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CodeTool")
            if (provider.bearer) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                val login = runCatching { JSONObject(body).optString("login") }.getOrDefault("")
                Result.success(login.ifEmpty { "认证成功" })
            } else {
                Result.failure(IOException("HTTP $code：${body.take(200)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    private fun chmod(f: File, mode: Int) {
        runCatching { Os.chmod(f.absolutePath, mode) }
    }

    private val CRED_RE = Regex("""^https://([^:@/]+):([^@/]*)@([^/]+)/?$""")
}
