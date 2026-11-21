package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codetool.app.git.GitConfigManager
import com.codetool.app.util.FileLog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git 页：管理 GitHub / Gitee 的 token 与 SSH 密钥，配置写进容器 HOME。
 *
 * 为什么放在 app 里而不是每次发给模型：
 * - 凭据落盘后，容器里的 `git clone/push` 以及模型执行的任何 shell 命令都会自动读取；
 * - 模型看不到明文 token（除非主动 cat 文件），也不需要用户在对话里反复粘贴。
 */
class GitFragment : Fragment() {

    private lateinit var accountList: LinearLayout
    private lateinit var sshBadge: MaterialTextView
    private lateinit var sshHint: MaterialTextView
    private lateinit var sshResult: MaterialTextView
    /** SSH 卡片当前所选平台（github / gitee）：分平台独立密钥，生成/导入/复制作用于它。 */
    private var selectedSshPlatform: String = "github"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_git, container, false)
        accountList = v.findViewById(R.id.gitAccountList)
        sshBadge = v.findViewById(R.id.gitSshBadge)
        sshHint = v.findViewById(R.id.gitSshHint)
        sshResult = v.findViewById(R.id.gitSshResult)

        // 平台切换：选中后刷新徽标/指纹，并让生成/导入/复制作用于该平台
        val toggle = v.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
            R.id.gitSshPlatformToggle)
        toggle.check(R.id.gitSshPlatformGithub)
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                selectedSshPlatform = if (checkedId == R.id.gitSshPlatformGitee) "gitee" else "github"
                renderSsh()
            }
        }

        v.findViewById<MaterialButton>(R.id.btnEditIdentity).setOnClickListener { showIdentityDialog() }
        v.findViewById<MaterialButton>(R.id.btnGenSsh).setOnClickListener { generateSsh() }
        v.findViewById<MaterialButton>(R.id.btnImportSsh).setOnClickListener { importSsh() }
        v.findViewById<MaterialButton>(R.id.btnCopySsh).setOnClickListener { copyPublicKey() }
        v.findViewById<MaterialButton>(R.id.btnTestSsh).setOnClickListener { testSsh() }
        return v
    }

    override fun onResume() {
        super.onResume()
        render()
        // 旧版共享凭据一次性拆分：写 git 配置要起容器进程，放 IO 线程，完成后再刷新一次。
        val ctx = requireContext()
        if (GitConfigManager.hasLegacyCredentials(ctx)) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { GitConfigManager.migrateLegacy(ctx) }
                if (isAdded) render()
            }
        }
    }

    private fun render() {
        renderAccounts()
        renderIdentity()
        renderSsh()
    }

    // ---------------- 账号 ----------------

    private fun renderAccounts() {
        val ctx = requireContext()
        accountList.removeAllViews()
        val containerOk = GitConfigManager.containerReady(ctx)
        for (p in GitConfigManager.PROVIDERS) {
            val row = layoutInflater.inflate(R.layout.item_git_account, accountList, false)
            val acc = GitConfigManager.account(ctx, p.host)
            row.findViewById<MaterialTextView>(R.id.gitProviderName).text = p.label

            val badge = row.findViewById<MaterialTextView>(R.id.gitProviderBadge)
            badge.text = if (acc != null) "已配置" else "未配置"
            badge.background = ContextCompat.getDrawable(
                ctx, if (acc != null) R.drawable.bg_badge_current else R.drawable.bg_badge_off)
            badge.setTextColor(MaterialColors.getColor(
                row, if (acc != null) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurfaceVariant))

            row.findViewById<MaterialTextView>(R.id.gitAccUser).text =
                acc?.user?.ifEmpty { "（未填）" } ?: "未配置"
            row.findViewById<MaterialTextView>(R.id.gitAccToken).text =
                acc?.maskedToken ?: "—"

            row.findViewById<MaterialButton>(R.id.btnGitConfig).apply {
                text = if (acc != null) "修改" else "配置"
                setOnClickListener { showTokenDialog(p) }
            }
            row.findViewById<MaterialButton>(R.id.btnGitTest).apply {
                isEnabled = acc != null
                setOnClickListener { testToken(p, acc) }
            }
            row.findViewById<MaterialButton>(R.id.btnGitClear).apply {
                visibility = if (acc != null) View.VISIBLE else View.GONE
                setOnClickListener { confirmClear(p) }
            }
            accountList.addView(row)
        }
        if (!containerOk) {
            sshResult.visibility = View.VISIBLE
            sshResult.text = "容器尚未就绪：请先完成首页初始化，再配置 Git 凭据。"
        }
    }

    private fun showTokenDialog(p: GitConfigManager.Provider) {
        val ctx = requireContext()
        val acc = GitConfigManager.account(ctx, p.host)
        val v = layoutInflater.inflate(R.layout.dlg_git_token, null)
        val etUser = v.findViewById<TextInputEditText>(R.id.etGitUser)
        val etToken = v.findViewById<TextInputEditText>(R.id.etGitToken)
        val tvResult = v.findViewById<MaterialTextView>(R.id.tvGitTestResult)
        val btnTest = v.findViewById<MaterialButton>(R.id.btnGitTestToken)
        etUser.setText(acc?.user.orEmpty())
        etToken.setText(acc?.token.orEmpty())
        // 获取 token 的路径指引：两家平台入口不同，写在弹窗里省得用户到处找
        v.findViewById<MaterialTextView>(R.id.tvGitTokenHelp).text = when (p.id) {
            "github" -> "获取：github.com → Settings → Developer settings → Personal access tokens\n" +
                "（classic 勾 repo 权限即可；推荐 fine-grained，可选仓库、可过期）"
            else -> "获取：gitee.com → 设置 → 安全设置 → 私人令牌\n" +
                "（勾选 projects 权限即可读写仓库）"
        }

        fun showResult(text: String, error: Boolean) {
            tvResult.visibility = View.VISIBLE
            tvResult.text = text
            tvResult.setTextColor(MaterialColors.getColor(
                v, if (error) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorPrimary))
        }

        btnTest.setOnClickListener {
            val token = etToken.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                showResult("请先填写 Token", true)
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            showResult("测试中…", false)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { GitConfigManager.verifyToken(p, token) }
                btnTest.isEnabled = true
                r.onSuccess { login ->
                    if (etUser.text.isNullOrBlank()) etUser.setText(login)
                    showResult("✔ 认证成功：$login", false)
                }.onFailure { e ->
                    showResult("✘ ${e.message ?: "认证失败"}", true)
                }
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("${p.label} 账号")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = etToken.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    showResult("请先填写 Token", true)
                    return@setOnClickListener
                }
                val typedUser = etUser.text?.toString()?.trim().orEmpty()
                val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                saveBtn.isEnabled = false
                lifecycleScope.launch {
                    // 用户名留空时用 API 返回的 login，避免把 URL 里的用户名写错
                    val user = if (typedUser.isNotEmpty()) typedUser else {
                        withContext(Dispatchers.IO) { GitConfigManager.verifyToken(p, token) }
                            .getOrElse { e ->
                                saveBtn.isEnabled = true
                                showResult("✘ ${e.message ?: "认证失败"}", true)
                                return@launch
                            }
                    }
                    val r = withContext(Dispatchers.IO) {
                        GitConfigManager.saveAccount(requireContext(), p.host, user, token)
                    }
                    saveBtn.isEnabled = true
                    r.onSuccess {
                        FileLog.i(requireContext(), "Git", "已保存 ${p.label} 凭据：$user")
                        dialog.dismiss()
                        toast("${p.label} 凭据已保存")
                        render()
                    }.onFailure { e ->
                        FileLog.e(requireContext(), "Git", "保存 ${p.label} 凭据失败", e)
                        showResult("✘ 保存失败：${e.message}", true)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun testToken(p: GitConfigManager.Provider, acc: GitConfigManager.Account?) {
        if (acc == null) return
        toast("正在测试 ${p.label}…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GitConfigManager.verifyToken(p, acc.token) }
            r.onSuccess { login ->
                toast("${p.label} ✔ 认证成功：$login")
            }.onFailure { e ->
                toast("${p.label} ✘ ${e.message ?: "认证失败"}")
            }
        }
    }

    private fun confirmClear(p: GitConfigManager.Provider) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清除 ${p.label} 凭据")
            .setMessage("会从容器 ~/.git-credentials 删除 ${p.host} 的 token，其它平台不受影响。")
            .setPositiveButton("清除") { _, _ ->
                val r = GitConfigManager.clearAccount(requireContext(), p.host)
                r.onSuccess {
                    toast("已清除 ${p.label} 凭据")
                    render()
                }.onFailure { toast("清除失败：${it.message}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 提交身份 ----------------

    private fun renderIdentity() {
        val (name, email) = GitConfigManager.identity(requireContext())
        view?.findViewById<MaterialTextView>(R.id.gitUserName)?.text =
            name.ifEmpty { "未设置" }
        view?.findViewById<MaterialTextView>(R.id.gitUserEmail)?.text =
            email.ifEmpty { "未设置" }
    }

    private fun showIdentityDialog() {
        val ctx = requireContext()
        val (name, email) = GitConfigManager.identity(ctx)
        val v = layoutInflater.inflate(R.layout.dlg_git_identity, null)
        val etName = v.findViewById<TextInputEditText>(R.id.etGitName)
        val etEmail = v.findViewById<TextInputEditText>(R.id.etGitEmail)
        etName.setText(name)
        etEmail.setText(email)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("提交身份")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val n = etName.text?.toString()?.trim().orEmpty()
                val e = etEmail.text?.toString()?.trim().orEmpty()
                if (n.isEmpty() || e.isEmpty()) {
                    toast("用户名和邮箱都要填")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        GitConfigManager.saveIdentity(requireContext(), n, e)
                    }
                    r.onSuccess {
                        toast("提交身份已保存")
                        renderIdentity()
                    }.onFailure { toast("保存失败：${it.message}") }
                }
            }
            .show()
    }

    // ---------------- SSH ----------------

    private fun renderSsh() {
        val ctx = requireContext()
        val plat = selectedSshPlatform
        val platLabel = if (plat == "gitee") "Gitee" else "GitHub"
        val pub = GitConfigManager.sshPublicKey(ctx, plat)
        val hasKey = GitConfigManager.sshPrivateKey(ctx, plat).exists()
        sshBadge.text = if (hasKey) "$platLabel 已配置" else "$platLabel 未配置"
        sshBadge.background = ContextCompat.getDrawable(
            ctx, if (hasKey) R.drawable.bg_badge_current else R.drawable.bg_badge_off)
        sshBadge.setTextColor(MaterialColors.getColor(
            sshBadge, if (hasKey) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant))
        sshHint.text = if (pub != null) {
            "$platLabel 公钥指纹：" + pub.take(60) + if (pub.length > 60) "…" else ""
        } else {
            "当前选中 $platLabel，尚无密钥。ed25519 密钥在容器内 /root/.ssh 生成，私钥不会离开设备；两个平台各自独立一把。"
        }
        (view?.findViewById<MaterialButton>(R.id.btnCopySsh))?.isEnabled = pub != null
    }

    private fun generateSsh() {
        val plat = selectedSshPlatform
        val platLabel = if (plat == "gitee") "Gitee" else "GitHub"
        if (GitConfigManager.sshPrivateKey(requireContext(), plat).exists()) {
            toast("$platLabel 密钥已存在，可直接复制公钥去平台添加")
            return
        }
        toast("正在生成 $platLabel SSH 密钥…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                GitConfigManager.generateSshKey(requireContext(), plat)
            }
            r.onSuccess {
                toast("$platLabel 密钥已生成，记得复制公钥到平台添加")
                renderSsh()
            }.onFailure { e ->
                FileLog.e(requireContext(), "Git", "生成 $platLabel SSH 密钥失败", e)
                sshResult.visibility = View.VISIBLE
                sshResult.text = "生成失败：${e.message}"
            }
        }
    }

    /** 手动导入用户已有的 SSH 私钥（覆盖现有密钥，覆盖前二次确认）。 */
    private fun importSsh() {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dlg_git_ssh_import, null)
        val etPriv = v.findViewById<TextInputEditText>(R.id.etSshPriv)
        val etPub = v.findViewById<TextInputEditText>(R.id.etSshPub)
        val tvResult = v.findViewById<MaterialTextView>(R.id.tvSshImportResult)

        fun showResult(text: String, error: Boolean) {
            tvResult.visibility = View.VISIBLE
            tvResult.text = text
            tvResult.setTextColor(MaterialColors.getColor(
                v, if (error) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorPrimary))
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("手动导入 SSH 密钥")
            .setView(v)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val priv = etPriv.text?.toString().orEmpty().trim()
                if (priv.isEmpty()) {
                    showResult("请先粘贴私钥", true)
                    return@setOnClickListener
                }
                val pub = etPub.text?.toString().orEmpty().trim()
                val plat = selectedSshPlatform
                val platLabel = if (plat == "gitee") "Gitee" else "GitHub"
                val saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                saveBtn.isEnabled = false
                fun proceed() {
                    lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) {
                            GitConfigManager.importSshKey(ctx, priv, pub, plat)
                        }
                        saveBtn.isEnabled = true
                        r.onSuccess {
                            FileLog.i(ctx, "Git", "已手动导入 $platLabel SSH 密钥")
                            dialog.dismiss()
                            toast("$platLabel SSH 密钥已导入，可用「测试 SSH」验证")
                            renderSsh()
                        }.onFailure { e ->
                            FileLog.e(ctx, "Git", "导入 $platLabel SSH 密钥失败", e)
                            showResult("✘ 导入失败：${e.message}", true)
                        }
                    }
                }
                if (GitConfigManager.sshPrivateKey(ctx, plat).exists()) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("覆盖现有密钥")
                        .setMessage("容器里已有 $platLabel 的 SSH 密钥，导入会覆盖 /root/.ssh/${if (plat == "gitee") "id_ed25519_gitee" else "id_ed25519"}；若新公钥与平台已添加的不同，需在平台更新为新公钥。")
                        .setPositiveButton("覆盖") { _, _ -> proceed() }
                        .setNegativeButton("取消", null)
                        .show()
                } else proceed()
            }
        }
        dialog.show()
    }

    private fun copyPublicKey() {
        val plat = selectedSshPlatform
        val platLabel = if (plat == "gitee") "Gitee" else "GitHub"
        val pub = GitConfigManager.sshPublicKey(requireContext(), plat)
        if (pub == null) {
            toast("$platLabel 还没有公钥，请先生成或导入")
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ssh public key", pub))
        toast("$platLabel 公钥已复制，去 $platLabel → SSH Keys 粘贴")
    }

    private fun testSsh() {
        sshResult.visibility = View.VISIBLE
        sshResult.text = "正在测试 SSH（首次连接会自动记录主机指纹）…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { GitConfigManager.verifySsh(requireContext()) }
            sshResult.text = r.getOrElse { it.message ?: "测试失败" }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
