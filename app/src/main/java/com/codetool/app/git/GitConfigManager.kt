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

    /** 各平台 SSH 私钥文件名：github → id_ed25519，gitee → id_ed25519_gitee（分平台独立密钥）。 */
    private fun keyFileName(platform: String) = if (platform == "gitee") "id_ed25519_gitee" else "id_ed25519"

    fun sshPrivateKey(ctx: Context, platform: String = "github") = File(sshDir(ctx), keyFileName(platform))

    fun sshPublicKey(ctx: Context, platform: String = "github"): String? =
        File(sshDir(ctx), keyFileName(platform) + ".pub")
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }

    /** 生成 ed25519 密钥（已存在则直接返回现有公钥）。platform: "github" / "gitee"。 */
    fun generateSshKey(ctx: Context, platform: String = "github"): Result<String> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        val dir = sshDir(ctx).apply { mkdirs() }
        chmod(dir, 0x1C0) // 0700
        val key = sshPrivateKey(ctx, platform)
        if (key.exists()) {
            return Result.success(sshPublicKey(ctx, platform) ?: "密钥已存在")
        }
        val containerPath = "/root/.ssh/${keyFileName(platform)}"
        return runCatching {
            val p = TawcrootRuntime.get(ctx).exec(
                root(ctx), "/usr/bin/ssh-keygen", "-t", "ed25519", "-N", "", "-C", "codetool-$platform",
                "-f", containerPath
            )
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code != 0 || !key.exists()) {
                throw IOException("ssh-keygen 失败（exit=$code）：${out.take(300)}")
            }
            chmod(key, 0x180) // 0600
            File(dir, "${keyFileName(platform)}.pub").takeIf { it.exists() }?.let { chmod(it, 0x1A4) } // 0644
            writeSshConfig(ctx)
            ensureKnownHosts(ctx, dir)
            sshPublicKey(ctx, platform) ?: out.trim()
        }
    }

    /** 预置 known_hosts，模型首次 ssh 时不必交互确认主机指纹。
     *  ★ 后台 fire-and-forget：ssh-keyscan 要联网抓 github/gitee 指纹，网络慢会拖几秒，
     *    不能阻塞「导入/生成密钥」的主流程（否则弹窗迟迟不消失）。调用方无需等待结果；
     *    即使 known_hosts 还没写好，ssh 测试也带 StrictHostKeyChecking=accept-new 兜底。 */
    private fun ensureKnownHosts(ctx: Context, dir: File) {
        Thread {
            runCatching {
                val scan = TawcrootRuntime.get(ctx).exec(
                    root(ctx), "/bin/bash", "-lc",
                    "command -v ssh-keyscan >/dev/null 2>&1 && " +
                        // -T 5：每个主机连接最多等 5 秒，避免某主机连不上时 ssh-keyscan 无限等待。
                        "ssh-keyscan -T 5 github.com gitee.com 2>/dev/null >> /root/.ssh/known_hosts || true"
                )
                scan.inputStream.bufferedReader().use { it.readText() }
                scan.waitFor()
                File(dir, "known_hosts").takeIf { it.exists() }?.let { chmod(it, 0x1A4) }
            }
        }.start()
    }

    /** 手动导入已有 SSH 私钥（用户自备密钥，覆盖容器对应平台的私钥）。platform: "github" / "gitee"。 */
    fun importSshKey(ctx: Context, privateKey: String, publicKey: String, platform: String = "github"): Result<String> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        val priv = privateKey.trim()
        if (!priv.startsWith("-----BEGIN") || !priv.contains("PRIVATE KEY-----")) {
            return Result.failure(
                IllegalArgumentException("私钥格式不对：应以 -----BEGIN ... PRIVATE KEY----- 开头")
            )
        }
        return runCatching {
            val dir = sshDir(ctx).apply { mkdirs() }
            chmod(dir, 0x1C0) // 0700
            val keyFile = sshPrivateKey(ctx, platform)
            keyFile.writeText(priv + "\n")
            chmod(keyFile, 0x180) // 0600
            // ★ 必须用 ssh-keygen -y 从导入的私钥反推公钥写进 .pub，保证私钥/公钥严格配对。
            //   不能直接信用户填的 publicKey：若用户只粘私钥、公钥留空或填错，
            //   残留的旧 .pub 会和私钥不配对——ssh 认证时按 .pub 提供公钥，
            //   提供了没在平台注册的旧公钥 → Permission denied（私钥本身是对的也没用）。
            val derivedPub = derivePublicKey(ctx, platform) ?: publicKey.trim()
            if (derivedPub.isNotEmpty()) {
                val pubFile = File(dir, "${keyFileName(platform)}.pub")
                pubFile.writeText(derivedPub.trim() + "\n")
                chmod(pubFile, 0x1A4) // 0644
            }
            writeSshConfig(ctx)
            ensureKnownHosts(ctx, dir)
            sshPublicKey(ctx, platform) ?: "导入成功（未提供公钥，仅写入私钥）"
        }
    }

    /** 从容器对应平台私钥反推公钥（ssh-keygen -y），失败返回 null。 */
    private fun derivePublicKey(ctx: Context, platform: String = "github"): String? {
        return runCatching {
            val p = TawcrootRuntime.get(ctx).exec(
                root(ctx), "/usr/bin/ssh-keygen", "-y", "-f", "/root/.ssh/${keyFileName(platform)}"
            )
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            val line = out.trim()
            if (code != 0 || !line.startsWith("ssh-") && !line.startsWith("ecdsa-") &&
                !line.startsWith("rsa-")) null else line
        }.getOrNull()
    }

    /** 写 ~/.ssh/config：把 github.com / gitee.com 各自映射到对应的 IdentityFile，
     *  让 ssh / git 访问不同平台时自动用各自的密钥（分平台密钥的核心）。
     *  IdentitiesOnly=yes 避免串用别的钥匙；missing 平台的 IdentityFile 不存在时 ssh 会跳过。 */
    private fun writeSshConfig(ctx: Context) {
        runCatching {
            val dir = sshDir(ctx).apply { mkdirs() }
            val cfg = buildString {
                for (p in PROVIDERS) {
                    append("Host ").append(p.host).append('\n')
                    append("    IdentityFile ~/.ssh/").append(keyFileName(p.id)).append('\n')
                    append("    IdentitiesOnly yes\n")
                }
            }
            val f = File(dir, "config")
            f.writeText(cfg)
            chmod(f, 0x1A4) // 0644
        }
    }

    /** 测试 GitHub / Gitee 的 SSH 认证（ssh -T）。分平台密钥：各主机用各自的 IdentityFile，
     *  哪个平台没配钥匙就单独提示，不影响另一个平台的测试。 */
    fun verifySsh(ctx: Context): Result<String> {
        if (!containerReady(ctx)) return Result.failure(IllegalStateException("容器尚未就绪"))
        writeSshConfig(ctx) // 确保分平台 IdentityFile 映射是最新
        val report = StringBuilder()
        var allOk = true
        for (p in PROVIDERS) {
            val host = p.host
            if (!sshPrivateKey(ctx, p.id).exists()) {
                allOk = false
                report.append("– ").append(p.label).append("：未配置密钥，请先生成或导入").append('\n')
                continue
            }
            var ok = false
            var detail = ""
            var code = -1
            val err = runCatching {
                val q = TawcrootRuntime.get(ctx).exec(
                    root(ctx), "/usr/bin/ssh", "-T",
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "BatchMode=yes",
                    "-o", "ConnectTimeout=12",
                    "git@$host"
                )
                val out = q.inputStream.bufferedReader().use { it.readText() }
                code = q.waitFor()
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
