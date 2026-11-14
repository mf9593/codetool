package com.codetool.app

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codetool.app.ai.AiModelManager
import com.codetool.app.runtime.RootfsInstallService
import com.codetool.app.runtime.RootfsManager
import com.codetool.app.runtime.TawcrootRuntime
import com.codetool.app.script.ScriptManager
import com.codetool.app.task.TaskManager
import com.codetool.app.terminal.TerminalService
import com.codetool.app.util.ShizukuManager
import com.codetool.app.util.UpdateUi
import rikka.shizuku.Shizuku
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首页：状态显示 + 终端开关。
 * - 显示 rootfs 解压进度、tawcroot 可用性、终端服务运行状态
 * - 一键启动/停止终端服务
 * - 监听 RootfsInstallService 广播实时刷新进度
 */
class HomeFragment : Fragment() {

    private lateinit var rootfsText: TextView
    private lateinit var pathText: TextView
    private lateinit var tawcrootText: TextView
    private lateinit var serviceText: TextView
    private lateinit var aiStatusText: TextView
    private lateinit var rootfsIcon: ImageView
    private lateinit var tawcrootIcon: ImageView
    private lateinit var serviceIcon: ImageView
    private lateinit var aiStatusIcon: ImageView
    private lateinit var shizukuText: TextView
    private lateinit var shizukuIcon: ImageView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var scriptFavList: android.widget.LinearLayout
    private lateinit var scriptFavEmpty: TextView
    private lateinit var pathFavList: android.widget.LinearLayout
    private lateinit var pathFavEmpty: TextView

    private var receiver: BroadcastReceiver? = null
    /** 版本探测只在本页面生命周期内尝试一次，避免每次 onResume 都起 tawcroot。 */
    private var versionProbeAttempted = false

    /** 图标颜色语义：三个 tone 都是引用主题属性的 ColorStateList，切主题自动跟随。 */
    private enum class IconTone(val csl: Int) {
        OK(R.color.icon_tone_ok),
        ERROR(R.color.icon_tone_error),
        NEUTRAL(R.color.icon_tone_neutral),
    }

    /**
     * 状态卡图标统一入口：图形表达「这行是什么」，颜色表达「状态如何」。
     * 颜色不在布局里各写各的 tint，统一用主题派生的 tone（禁止硬编码色值）。
     */
    private fun applyIcon(iv: ImageView, res: Int, tone: IconTone) {
        iv.setImageResource(res)
        ContextCompat.getColorStateList(requireContext(), tone.csl)?.let {
            ImageViewCompat.setImageTintList(iv, it)
        }
    }

    /** 刷新 Shizuku 状态行：图形贴合语义（代码=运行时），颜色表达状态。 */
    private fun updateShizukuStatus() {
        if (!isAdded) return
        // runCatching：Shizuku 服务未就绪/binder 异常时不让首页崩掉
        val st = runCatching { ShizukuManager.status(requireContext()) }.getOrNull() ?: return
        shizukuText.text = "Shizuku: ${st.label}"
        val tone = when {
            st.granted -> IconTone.OK
            st.installed -> IconTone.NEUTRAL
            else -> IconTone.ERROR
        }
        applyIcon(shizukuIcon, R.drawable.ic_wireless, tone)
    }

    /** Shizuku 授权结果回调：授权成功刷新状态，被拒提示。 */
    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.REQUEST_CODE) {
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(requireContext(), "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
            if (isAdded) updateShizukuStatus()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        rootfsText = v.findViewById(R.id.rootfsText)
        pathText = v.findViewById(R.id.pathText)
        tawcrootText = v.findViewById(R.id.tawcrootText)
        serviceText = v.findViewById(R.id.serviceText)
        aiStatusText = v.findViewById(R.id.aiStatusText)
        rootfsIcon = v.findViewById(R.id.rootfsIcon)
        tawcrootIcon = v.findViewById(R.id.tawcrootIcon)
        serviceIcon = v.findViewById(R.id.serviceIcon)
        aiStatusIcon = v.findViewById(R.id.aiStatusIcon)
        shizukuText = v.findViewById(R.id.shizukuText)
        shizukuIcon = v.findViewById(R.id.shizukuIcon)
        progressBar = v.findViewById(R.id.progressBar)
        applyIcon(v.findViewById(R.id.pathIcon), R.drawable.ic_folder, IconTone.NEUTRAL)
        // Shizuku：点击状态行申请授权（未装/未运行时点按提示指引）
        v.findViewById<View>(R.id.shizukuRow).setOnClickListener {
            val st = runCatching { ShizukuManager.status(requireContext()) }.getOrNull()
            when {
                st == null || !st.installed ->
                    Toast.makeText(requireContext(), "未安装 Shizuku 管理器，请先安装", Toast.LENGTH_SHORT).show()
                !st.running ->
                    Toast.makeText(requireContext(), "Shizuku 服务未运行，请打开 Shizuku 启动", Toast.LENGTH_SHORT).show()
                st.granted -> Toast.makeText(requireContext(), "已授权，无需重复申请", Toast.LENGTH_SHORT).show()
                else -> ShizukuManager.requestPermission()
            }
        }
        scriptFavList = v.findViewById(R.id.scriptFavList)
        scriptFavEmpty = v.findViewById(R.id.scriptFavEmpty)
        pathFavList = v.findViewById(R.id.pathFavList)
        pathFavEmpty = v.findViewById(R.id.pathFavEmpty)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshStatus()
        // Shizuku：注册授权结果回调 + 首次刷新状态行
        ShizukuManager.addPermissionResultListener(shizukuPermListener)
        updateShizukuStatus()

        // 只监听 rootfs 解压进度（备份/恢复入口已移到工具页，由那个页面自己收广播）
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val done = intent.getBooleanExtra(RootfsInstallService.EXTRA_DONE, false)
                val error = intent.getStringExtra(RootfsInstallService.EXTRA_ERROR)
                val pct = intent.getIntExtra(RootfsInstallService.EXTRA_PERCENT, -1)
                if (done) {
                    refreshStatus()
                } else if (error != null) {
                    progressBar.hide()
                    refreshStatus()
                } else if (pct >= 0) {
                    progressBar.show()
                    progressBar.setProgressCompat(pct, true)
                    rootfsText.text = "正在解压 Linux 系统… $pct%"
                }
            }
        }
        receiver = r
        requireContext().registerReceiver(
            r,
            IntentFilter(RootfsInstallService.BROADCAST_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshFavorites()
        // 应用内更新：静默检查 GitHub 最新 Release（6 小时一次，仅发现新版本时弹窗）
        UpdateUi.autoCheck(this)
    }

    override fun onDestroyView() {
        ShizukuManager.removePermissionResultListener(shizukuPermListener)
        receiver?.let { requireContext().unregisterReceiver(it) }
        receiver = null
        super.onDestroyView()
    }

    /** 首页收藏区：脚本收藏 + 路径收藏（长按收藏；点击运行/打开文件管理）。 */
    private fun refreshFavorites() {
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        scriptFavList.removeAllViews()
        pathFavList.removeAllViews()

        // 脚本收藏（点击弹运行弹窗，点「运行」在终端内执行）
        val favScripts = ScriptManager.favScripts(ctx)
            .mapNotNull { ScriptManager.parseScript(File(it)) }
            .sortedBy { it.name }
        scriptFavEmpty.visibility = if (favScripts.isEmpty()) View.VISIBLE else View.GONE
        for (s in favScripts) {
            scriptFavList.addView(buildFavRow(
                scriptFavList,
                icon = R.drawable.ic_scripts,
                title = s.name,
                subtitle = s.file.name,
                onClick = {
                    com.codetool.app.script.ScriptRunDialog.show(ctx, s) { args ->
                        // 在终端会话内运行（先选择目标标签，自动切到终端页）
                        // 提示由 runScript 在实际发送后弹出，避免还没选标签就说“已运行”
                        TerminalFragment.pickTabAndRun(ctx, s.file, s.name, *args)
                    }
                }
            ))
        }

        // 路径收藏（打开文件管理）
        val favPaths = ScriptManager.favPaths(ctx).sorted()
        pathFavEmpty.visibility = if (favPaths.isEmpty()) View.VISIBLE else View.GONE
        for (p in favPaths) {
            pathFavList.addView(buildFavRow(
                pathFavList,
                icon = R.drawable.ic_folder,
                title = File(p).name.ifEmpty { p },
                subtitle = p,
                onClick = { (activity as? MainActivity)?.openFilesAt(p) }
            ))
        }
    }

    /** 构造通用收藏卡片：图标 + 标题 + 副标题，整卡可点（脚本/路径收藏共用）。 */
    private fun buildFavRow(parent: ViewGroup, icon: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        // 必须传 parent inflate：否则根元素的 layout_marginBottom 丢失，卡片间距失效
        val v = layoutInflater.inflate(R.layout.item_fav_card, parent, false)
        v.findViewById<ImageView>(R.id.favIcon).setImageResource(icon)
        v.findViewById<TextView>(R.id.favTitle).text = title
        v.findViewById<TextView>(R.id.favSubtitle).text = subtitle
        v.setOnClickListener { onClick() }
        return v
    }

    /** 打开 QQ 交流群：跳到 QQ 群聊天页；未安装 QQ 则提示。 */
    private fun openQqGroup() {
        // mqqwpa 协议：chat_type=group 打开指定群号（需已安装手机 QQ）
        val url = "mqqwpa://im/chat?chat_type=group&uin=559711374"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "未检测到手机 QQ，请先安装", Toast.LENGTH_SHORT).show()
        }
    }

    /** refreshStatus 里 IO 计算的结果集合（避免嵌套 Pair）。 */
    private data class StatusSnapshot(
        val rootfsLabel: String, val rootfsTone: IconTone,
        val aiLabel: String, val aiTone: IconTone,
    )

    private fun refreshStatus() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val rootfs = RootfsManager.get(ctx)
            rootfs.refreshState()
            val runtime = TawcrootRuntime.get(ctx)

            // 计算状态（都是轻量判断：state + 读几个小文件）。
            // 不再统计 rootfs 占用大小——全盘 walkTopDown 遍历几万文件极慢，
            // 这个数字对用户也没什么用，直接显示「已就绪」。
            val snapshot = withContext(Dispatchers.IO) {
                val (label, tone) = when (val st = rootfs.state) {
                    is RootfsManager.State.Extracting ->
                        "解压中 ${st.percent}%" to IconTone.NEUTRAL
                    is RootfsManager.State.Failed ->
                        "失败: ${st.error}" to IconTone.ERROR
                    is RootfsManager.State.Ready ->
                        "已就绪" to IconTone.OK
                    else -> "待解压（首次启动自动解压）" to IconTone.NEUTRAL
                }
                // AI CLI：规范——必须显示真实版本号，拿不到明确写「版本未知」
                val mimo = AiModelManager.mimoStatus(ctx)
                val oc = AiModelManager.opencodeStatus(ctx)
                val m = when {
                    !mimo.installed -> "mimo 未装"
                    mimo.version.isEmpty() -> "mimo 已装·版本未知"
                    else -> "mimo ${mimo.version}"
                }
                val o = when {
                    !oc.installed -> "opencode 未装"
                    oc.version.isEmpty() -> "opencode 已装·版本未知"
                    else -> "opencode ${oc.version}"
                }
                val aiTone = if (mimo.installed || oc.installed) IconTone.OK else IconTone.NEUTRAL
                StatusSnapshot(label, tone, "$m · $o", aiTone)
            }
            val path = rootfs.containerRoot.absolutePath
            val tawOk = runtime.binaryExists()
            val tawLabel = if (tawOk) "可用" else "缺失（需编译）"
            val tawTone = if (tawOk) IconTone.OK else IconTone.ERROR
            val svcRunning = TerminalService.instance != null
            val svcLabel = if (svcRunning) "运行中" else "未启动"
            val svcIcon = R.drawable.ic_terminal_prompt  // 运行/未启动都用终端造型，状态靠文字区分
            val svcTone = if (svcRunning) IconTone.OK else IconTone.NEUTRAL

            // 更新行：图形贴合该行文字语义，颜色跟随主题
            rootfsText.text = "Rootfs: ${snapshot.rootfsLabel}"
            applyIcon(rootfsIcon, R.drawable.ic_storage, snapshot.rootfsTone)
            pathText.text = "路径: $path"
            tawcrootText.text = "tawcroot: $tawLabel"
            applyIcon(tawcrootIcon, R.drawable.ic_monitor, tawTone)
            serviceText.text = "终端服务: $svcLabel"
            applyIcon(serviceIcon, svcIcon, svcTone)
            aiStatusText.text = "AI: ${snapshot.aiLabel}"
            applyIcon(aiStatusIcon, R.drawable.ic_ai_model, snapshot.aiTone)

            // 进度条显示/隐藏
            when (val st = rootfs.state) {
                is RootfsManager.State.Extracting -> {
                    progressBar.show()
                    progressBar.setProgressCompat(st.percent, true)
                }
                is RootfsManager.State.Ready -> {
                    progressBar.setProgressCompat(100, false)
                    progressBar.hide()
                }
                else -> progressBar.hide()
            }

            // 版本缺失时后台一次性探测（容器内跑 CLI --version 写缓存），
            // 不阻塞上面的状态刷新；完成后只补一次 AI 行，本生命周期不重试。
            val needProbe = !versionProbeAttempted &&
                snapshot.aiLabel.contains("版本未知") &&
                rootfs.state is RootfsManager.State.Ready
            if (needProbe) {
                versionProbeAttempted = true
                launch(Dispatchers.IO) {
                    AiModelManager.probeVersions(ctx.applicationContext)
                    val line = withContext(Dispatchers.IO) {
                        val m = AiModelManager.mimoStatus(ctx)
                        val o = AiModelManager.opencodeStatus(ctx)
                        val mm = when {
                            !m.installed -> "mimo 未装"
                            m.version.isEmpty() -> "mimo 已装·版本未知"
                            else -> "mimo ${m.version}"
                        }
                        val oo = when {
                            !o.installed -> "opencode 未装"
                            o.version.isEmpty() -> "opencode 已装·版本未知"
                            else -> "opencode ${o.version}"
                        }
                        "$mm · $oo"
                    }
                    withContext(Dispatchers.Main) {
                        if (isAdded && view != null) aiStatusText.text = "AI: $line"
                    }
                }
            }
        }
    }
}

// helper extension（放在这里避免单独文件）
private fun com.codetool.app.runtime.TawcrootRuntime.binaryExists(): Boolean =
    try { java.io.File(binaryPath).exists() } catch (e: Exception) { false }
