package com.codetool.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.codetool.app.script.ScriptManager
import com.codetool.app.util.AppSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * 下载页：容器内离线下载依赖的安装与状态检测。
 * 目前承载「构建环境」卡片（JDK / SDK / NDK / build-tools / platform-tools / CMake / Flutter），
 * 从包管理页独立出来，避免包管理与下载安装混在一起。
 */
class DownloadsFragment : Fragment() {

    companion object {
        /** 检测结果缓存：避免每次 onResume 都读盘。30 秒内直接复用，刷新/安装后强制失效。 */
        @Volatile private var cache: List<EnvComp>? = null
        @Volatile private var cacheAt = 0L
        private const val CACHE_TTL_MS = 30_000L

        private fun invalidateCache() {
            cache = null
            cacheAt = 0L
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_downloads, container, false)
        // 安装构建环境：确保内置 03-buildenv.sh 在（与脚本库同源），再在终端运行。
        // runScript 会复制脚本 + JDK/SDK/NDK 压缩包进容器（GB 级，先切终端页提示），扫目录只装缺的。
        v.findViewById<MaterialButton>(R.id.btnBuildEnvInstall).setOnClickListener {
            invalidateCache() // 安装会重写 env 文件，回来后重新检测
            ScriptManager.ensureLocalScripts(requireContext())
            val f = java.io.File(ScriptManager.scriptDir, "03-buildenv.sh")
            if (!f.isFile) {
                Toast.makeText(requireContext(),
                    "未找到构建环境脚本（03-buildenv.sh），请先打开脚本库同步", Toast.LENGTH_LONG).show()
            } else {
                TerminalFragment.runScript(requireContext(), f, "安装构建环境")
            }
        }
        // 手动刷新：忽略缓存重新检测（env 文件改了 / 手动删了组件时用）
        v.findViewById<MaterialButton>(R.id.btnBuildEnvRefresh).setOnClickListener {
            invalidateCache()
            refreshBuildEnv(force = true)
        }
        // 在线下载：设置 + 取消全部
        v.findViewById<MaterialButton>(R.id.btnDownloadSettings).setOnClickListener {
            showDownloadSettings()
        }
        v.findViewById<MaterialButton>(R.id.btnDownloadCancel).setOnClickListener {
            DownloadService.cancelAll(requireContext())
            dlStates.clear()
            renderOnlineList()
            Toast.makeText(requireContext(), "已取消全部下载", Toast.LENGTH_SHORT).show()
        }
        return v
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 页面可见时每 2 秒对账一次：文件已完成 / 服务已不在跑该 id → 清掉残留进度。 */
    private val reconcileTask = object : Runnable {
        override fun run() {
            reconcileDownloads()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        reconcileDownloads()
        // 构建环境检测状态（读 env 文件 + 少量版本文件，后台线程；30s 内走缓存）
        refreshBuildEnv()
        renderOnlineList()
        handler.removeCallbacks(reconcileTask)
        handler.postDelayed(reconcileTask, 2000)
    }

    override fun onPause() {
        handler.removeCallbacks(reconcileTask)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(reconcileTask)
        super.onDestroyView()
    }

    /**
     * 清理错过广播的残留进度：
     * - 文件已完成且服务已不在跑该 id（后台下完切回来）
     * - 服务已结束 / 被系统杀掉，且超过 2 秒启动宽限
     */
    private fun reconcileDownloads() {
        val now = System.currentTimeMillis()
        val stale = dlStates.keys.filter { id ->
            val pkg = BuildPackageCatalog.packages.firstOrNull { it.id == id }
            val active = DownloadService.isActive(id)
            val st = dlStates[id]
            // 失败状态保留，等用户重试/取消，不要 2 秒后自动消失
            if (st != null && st.error != null) return@filter false
            if (pkg == null) {
                return@filter !active && st != null && now - st.startedAt > 2000
            }
            val downloaded = BuildPackageCatalog.isDownloaded(pkg)
            (downloaded && !active) || (!active && st != null && now - st.startedAt > 2000)
        }
        if (stale.isNotEmpty()) {
            stale.forEach { dlStates.remove(it) }
            renderOnlineList()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(), dlReceiver,
            IntentFilter(DownloadService.BROADCAST_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(dlReceiver) }
        super.onStop()
    }

    // ---------------- 在线下载 ----------------

    /** 下载进度：id -> 状态。 */
    private data class DlState(
        val percent: Int, val downloaded: Long, val total: Long, val error: String?,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val dlStates = HashMap<String, DlState>()

    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra(DownloadService.EXTRA_PROGRESS_ID) ?: return
            val pct = intent.getIntExtra(DownloadService.EXTRA_PERCENT, -1)
            val done = intent.getBooleanExtra(DownloadService.EXTRA_DONE, false)
            val error = intent.getStringExtra(DownloadService.EXTRA_ERROR)
            val downloaded = intent.getLongExtra(DownloadService.EXTRA_DOWNLOADED, 0)
            val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, 0)
            val cancelled = intent.getBooleanExtra(DownloadService.EXTRA_CANCELLED, false)
            when {
                cancelled -> dlStates.remove(id)
                done -> {
                    dlStates.remove(id)
                    Toast.makeText(ctx, "下载完成", Toast.LENGTH_SHORT).show()
                }
                error != null -> dlStates[id] = DlState(pct, downloaded, total, error)
                else -> dlStates[id] = DlState(pct, downloaded, total, null)
            }
            renderOnlineList()
        }
    }

    /** 渲染在线下载列表：本地已有 → 「已下载」；进行中 → 进度；失败 → 重试。 */
    private fun renderOnlineList() {
        val ctx = context ?: return
        val list = view?.findViewById<LinearLayout>(R.id.onlineList) ?: return
        list.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for (pkg in BuildPackageCatalog.packages) {
            val row = inflater.inflate(R.layout.item_download_package, list, false)
            row.findViewById<TextView>(R.id.dlLabel).text = pkg.label
            row.findViewById<TextView>(R.id.dlDesc).text = pkg.desc
            val status = row.findViewById<TextView>(R.id.dlStatus)
            val btn = row.findViewById<MaterialButton>(R.id.dlBtn)
            val st = dlStates[pkg.id]
            var action: () -> Unit = { startDownload(pkg) }
            when {
                st != null && st.error != null -> {
                    status.text = "下载失败：${st.error}"
                    status.setTextColor(MaterialColors.getColor(
                        row, com.google.android.material.R.attr.colorError, 0))
                    btn.text = "重试"
                    btn.isEnabled = true
                }
                st != null && (DownloadService.isActive(pkg.id) ||
                    System.currentTimeMillis() - st.startedAt < 3000) -> {
                    val pctText = if (st.percent >= 0) "${st.percent}% · " else ""
                    val sizeText = if (st.total > 0)
                        "${BuildPackageCatalog.humanSize(st.downloaded)} / ${BuildPackageCatalog.humanSize(st.total)}"
                    else BuildPackageCatalog.humanSize(st.downloaded)
                    status.text = "下载中 $pctText$sizeText"
                    status.setTextColor(MaterialColors.getColor(
                        row, com.google.android.material.R.attr.colorPrimary, 0))
                    btn.text = "取消"
                    btn.isEnabled = true
                    action = { cancelDownload(pkg.id) }
                }
                BuildPackageCatalog.isDownloaded(pkg) -> {
                    status.text = "已下载 · ${BuildPackageCatalog.humanSize(BuildPackageCatalog.localFile(pkg).length())}"
                    status.setTextColor(MaterialColors.getColor(
                        row, com.google.android.material.R.attr.colorPrimary, 0))
                    btn.text = "重新下载"
                    btn.isEnabled = true
                }
                else -> {
                    status.text = "未下载 · 约 ${BuildPackageCatalog.humanSize(pkg.sizeBytes)}"
                    status.setTextColor(MaterialColors.getColor(
                        row, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
                    btn.text = "下载"
                    btn.isEnabled = true
                }
            }
            btn.setOnClickListener { action() }
            list.addView(row)
        }
        val repo = AppSettings.downloadRepo(ctx)
        val mirrorText = if (AppSettings.downloadMirrorOn(ctx))
            "镜像 ${AppSettings.downloadMirrorPrefix(ctx)}" else "直连"
        view?.findViewById<TextView>(R.id.tvDownloadHint)?.text =
            "仓库 $repo · $mirrorText；下载到 codetool/data/，已有文件显示「已下载」。"
        val active = dlStates.values.any { it.error == null }
        view?.findViewById<View>(R.id.btnDownloadCancel)?.visibility =
            if (active) View.VISIBLE else View.GONE
    }

    /** 加入下载队列：配置仓库优先，缺失时自动回退上游（JDK 只在自己仓库，不回退）。 */
    private fun startDownload(pkg: BuildPackage) {
        val ctx = requireContext()
        val repo = AppSettings.downloadRepo(ctx)
        val mirrorOn = AppSettings.downloadMirrorOn(ctx)
        val prefix = AppSettings.downloadMirrorPrefix(ctx)
        fun urlOf(r: String): String = BuildPackageCatalog.assetUrl(r, pkg)
        val urls = buildList {
            val primary = urlOf(repo)
            add(if (mirrorOn) BuildPackageCatalog.mirrorUrl(primary, prefix) else primary)
            if (mirrorOn) add(primary) // 镜像太慢/不可用时回退直连
            if (pkg.hasUpstream && repo.trim() != BuildPackageCatalog.UPSTREAM_REPO) {
                val fallback = urlOf(BuildPackageCatalog.UPSTREAM_REPO)
                add(if (mirrorOn) BuildPackageCatalog.mirrorUrl(fallback, prefix) else fallback)
                if (mirrorOn) add(fallback)
            }
        }
        dlStates[pkg.id] = DlState(-1, 0, pkg.sizeBytes, null)
        renderOnlineList()
        DownloadService.start(ctx, pkg.id, pkg.label, pkg.asset, urls)
    }

    /** 取消单个下载：服务端停止当前读取/移出队列，UI 立即移除进度状态。 */
    private fun cancelDownload(id: String) {
        DownloadService.cancelOne(requireContext(), id)
        dlStates.remove(id)
        renderOnlineList()
    }

    /** 下载设置：仓库 / 镜像开关 / 镜像选择 / 自定义镜像 / 自定义 GitHub URL。 */
    private fun showDownloadSettings() {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_download_settings, null)
        val repoInput = v.findViewById<TextInputEditText>(R.id.dlRepoInput)
        val mirrorSwitch = v.findViewById<MaterialSwitch>(R.id.dlMirrorSwitch)
        val mirrorInput = v.findViewById<MaterialAutoCompleteTextView>(R.id.dlMirrorInput)
        val mirrorCustomLayout = v.findViewById<View>(R.id.dlMirrorCustomLayout)
        val mirrorCustomInput = v.findViewById<TextInputEditText>(R.id.dlMirrorCustomInput)
        val urlInput = v.findViewById<TextInputEditText>(R.id.dlUrlInput)
        val btnCustom = v.findViewById<MaterialButton>(R.id.btnDlCustom)

        repoInput.setText(AppSettings.downloadRepo(ctx))
        mirrorSwitch.isChecked = AppSettings.downloadMirrorOn(ctx)

        val presets = listOf(
            "ghfast.top（推荐）" to "https://ghfast.top/",
            "gh-proxy.com" to "https://gh-proxy.com/",
            "ghproxy.net" to "https://ghproxy.net/",
            "自定义" to ""
        )
        var selectedPrefix = AppSettings.downloadMirrorPrefix(ctx)
        val initLabel = presets.firstOrNull { it.second.isNotEmpty() && it.second == selectedPrefix }?.first
            ?: "自定义"
        var isCustom = initLabel == "自定义"
        mirrorInput.setAdapter(
            android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_1, presets.map { it.first })
        )
        mirrorInput.setText(initLabel, false)
        mirrorCustomLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
        mirrorCustomInput.setText(if (isCustom) selectedPrefix else "")
        mirrorInput.setOnItemClickListener { _, _, pos, _ ->
            val p = presets.getOrNull(pos) ?: return@setOnItemClickListener
            selectedPrefix = p.second
            isCustom = p.first == "自定义"
            mirrorCustomLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
            mirrorCustomInput.isEnabled = mirrorSwitch.isChecked
            if (!isCustom) mirrorCustomInput.setText("")
        }
        mirrorSwitch.setOnCheckedChangeListener { _, checked ->
            mirrorInput.isEnabled = checked
            mirrorCustomInput.isEnabled = checked
        }
        mirrorInput.isEnabled = mirrorSwitch.isChecked
        mirrorCustomInput.isEnabled = mirrorSwitch.isChecked

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setView(v)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        btnCustom.setOnClickListener {
            val raw = urlInput.text?.toString()?.trim().orEmpty()
            if (!raw.startsWith("https://github.com/")) {
                Toast.makeText(ctx, "仅支持 https://github.com/ 链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = raw.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) {
                Toast.makeText(ctx, "无法从 URL 识别文件名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prefix = when {
                !mirrorSwitch.isChecked -> ""
                isCustom -> mirrorCustomInput.text?.toString()?.trim().orEmpty()
                else -> selectedPrefix
            }
            val id = "custom_${System.currentTimeMillis()}"
            dlStates[id] = DlState(-1, 0, 0, null)
            DownloadService.start(ctx, id, name, name,
                listOf(BuildPackageCatalog.mirrorUrl(raw, prefix)))
            Toast.makeText(ctx, "已加入下载：$name", Toast.LENGTH_SHORT).show()
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val repo = repoInput.text?.toString()?.trim().orEmpty()
                if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) {
                    Toast.makeText(ctx, "仓库格式应为 owner/repo", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val prefix = when {
                    !mirrorSwitch.isChecked -> ""
                    isCustom -> mirrorCustomInput.text?.toString()?.trim().orEmpty()
                    else -> selectedPrefix
                }
                if (mirrorSwitch.isChecked && prefix.isNotBlank() && !prefix.startsWith("http")) {
                    Toast.makeText(ctx, "镜像前缀需以 http(s):// 开头", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppSettings.setDownloadRepo(ctx, repo)
                AppSettings.setDownloadMirrorOn(ctx, mirrorSwitch.isChecked)
                AppSettings.setDownloadMirrorPrefix(ctx, prefix)
                renderOnlineList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---------------- 构建环境检测状态 ----------------

    /** 单个构建组件的检测结果。 */
    private data class EnvComp(val label: String, val installed: Boolean, val version: String)

    /** 后台探测构建环境并渲染到 buildEnvList；优先用缓存，避免每次进页面都读盘。 */
    private fun refreshBuildEnv(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val cached = cache
        if (!force && cached != null && now - cacheAt < CACHE_TTL_MS) {
            view?.findViewById<LinearLayout>(R.id.buildEnvList)?.let { renderBuildEnv(it, cached) }
            return
        }
        val ctx = requireContext().applicationContext
        Thread {
            val comps = detectBuildEnv(ctx)
            cache = comps
            cacheAt = System.currentTimeMillis()
            (activity as? MainActivity)?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val list = view?.findViewById<LinearLayout>(R.id.buildEnvList) ?: return@runOnUiThread
                renderBuildEnv(list, comps)
            }
        }.start()
    }

    private fun renderBuildEnv(list: LinearLayout, comps: List<EnvComp>) {
        list.removeAllViews()
        val inflater = LayoutInflater.from(list.context)
        for (c in comps) {
            val row = inflater.inflate(R.layout.item_buildenv, list, false)
            row.findViewById<TextView>(R.id.bvLabel).text = c.label
            val v = row.findViewById<TextView>(R.id.bvValue)
            v.text = if (c.installed) {
                // 已装必显版本；拿不到真实版本时明确写「版本未知」，不混「已装」二字
                if (c.version.isNotBlank()) "已安装 · ${c.version}" else "已安装（版本未知）"
            } else "未安装"
            v.setTextColor(if (c.installed)
                MaterialColors.getColor(row, com.google.android.material.R.attr.colorPrimary)
            else MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant))
            list.addView(row)
        }
    }

    /**
     * 探测构建环境：优先读脚本生成的 /etc/profile.d/codetool-buildenv.sh，
     * 用其中的 JAVA_HOME / ANDROID_HOME / ANDROID_NDK_HOME / CMAKE_HOME / FLUTTER_HOME 定位组件，
     * 只做「路径是否存在 + 少量版本文件」校验，不再全盘扫描。
     * env 文件缺失（尚未安装过）时退回 buildenv 目录下的一层浅查找。
     */
    private fun detectBuildEnv(ctx: android.content.Context): List<EnvComp> {
        val root = com.codetool.app.runtime.RootfsManager.get(ctx).containerRoot
        val env = readEnvFile(root)
        val base = java.io.File(root, "root/buildenv")

        val jdk17 = envPath(root, env["JAVA_HOME"]) ?: shallowHome(java.io.File(base, "jdk")) {
            java.io.File(it, "bin/java").isFile
        }
        // JDK 21：脚本只导出一个 JAVA_HOME，另看 jdk21/（一层浅查找）
        val jdk21 = shallowHome(java.io.File(base, "jdk21")) {
            java.io.File(it, "bin/java").isFile
        }
        val sdkHome = envPath(root, env["ANDROID_HOME"]) ?: shallowHome(java.io.File(base, "sdk")) {
            java.io.File(it, "build-tools").isDirectory && java.io.File(it, "platforms").isDirectory
        }
        val ndkHome = envPath(root, env["ANDROID_NDK_HOME"]) ?: shallowHome(java.io.File(base, "ndk")) {
            java.io.File(it, "source.properties").isFile && java.io.File(it, "toolchains").isDirectory
        }
        val cmakeHome = envPath(root, env["CMAKE_HOME"]) ?: shallowHome(java.io.File(base, "cmake")) {
            java.io.File(it, "bin/cmake").isFile
        }
        val flutterHome = envPath(root, env["FLUTTER_HOME"]) ?: shallowHome(java.io.File(base, "flutter")) {
            java.io.File(it, "bin/flutter").isFile
        }
        return listOf(
            jdkComp("JDK 17", jdk17),
            jdkComp("JDK 21", jdk21),
            sdkComp(sdkHome),
            ndkComp(ndkHome),
            buildtoolsComp(sdkHome),
            platformtoolsComp(sdkHome),
            cmakeComp(cmakeHome),
            flutterComp(flutterHome),
        )
    }

    /** 解析 env 文件里的 `export KEY="VALUE"`；PATH 之类动态值不参与检测。 */
    private fun readEnvFile(root: java.io.File): Map<String, String> {
        val f = java.io.File(root, "etc/profile.d/codetool-buildenv.sh")
        if (!f.isFile) return emptyMap()
        val re = Regex("""^\s*export\s+([A-Za-z_][A-Za-z0-9_]*)=(?:"([^"]*)"|'([^']*)'|(\S+))\s*$""")
        val map = HashMap<String, String>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val m = re.find(line) ?: continue
                    val v = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
                    map[m.groupValues[1]] = v
                }
            }
        }
        return map
    }

    /** 容器内绝对路径（/root/...）→ app 侧 rootfs 下的 File。 */
    private fun envPath(root: java.io.File, path: String?): java.io.File? {
        if (path.isNullOrBlank() || !path.startsWith("/")) return null
        return java.io.File(root, path.removePrefix("/"))
    }

    /** 一层浅查找：base 本身或它的一级子目录里首个满足 marker 的目录。 */
    private fun shallowHome(base: java.io.File?, marker: (java.io.File) -> Boolean): java.io.File? {
        if (base == null || !base.isDirectory) return null
        if (marker(base)) return base
        return base.listFiles()?.firstOrNull { it.isDirectory && marker(it) }
    }

    /** JDK：bin/java + lib/server/libjvm.so；版本从 release 的 JAVA_VERSION 读。 */
    private fun jdkComp(label: String, home: java.io.File?): EnvComp {
        val ok = home != null && java.io.File(home, "bin/java").isFile &&
            java.io.File(home, "lib/server/libjvm.so").isFile
        val ver = home?.let { propValue(java.io.File(it, "release"), "JAVA_VERSION").trim('"') } ?: ""
        return EnvComp(label, ok, ver)
    }

    /** 读取 properties 文件里的 `key=value`（容忍 `key = value` 的空格），读不到返回空串。 */
    private fun propValue(f: java.io.File, key: String): String = runCatching {
        if (!f.isFile) return@runCatching ""
        val re = Regex("^\\s*" + Regex.escape(key) + "\\s*=\\s*(.*?)\\s*$")
        var out = ""
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val m = re.find(line) ?: continue
                out = m.groupValues[1]
                break
            }
        }
        out
    }.getOrDefault("")

    /** SDK：build-tools + platforms；版本 = 已装平台里最高的 API（如 37.0）。 */
    private fun sdkComp(home: java.io.File?): EnvComp {
        val hasBt = home != null && java.io.File(home, "build-tools").isDirectory
        val platformsDir = home?.let { java.io.File(it, "platforms") }
        val hasPlat = platformsDir?.isDirectory == true
        // 平台版本：优先 AndroidVersion.ApiLevel，读不到退回目录名 android-XX；取数值最大者。
        val api = platformsDir?.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { d ->
                val v = propValue(java.io.File(d, "source.properties"), "AndroidVersion.ApiLevel")
                    .ifBlank { d.name.removePrefix("android-") }
                v.toDoubleOrNull()?.let { it to v }
            }
            .maxByOrNull { it.first }?.second.orEmpty()
        return EnvComp("Android SDK", hasBt && hasPlat, if (api.isBlank()) "" else "API $api")
    }

    /** NDK：source.properties + toolchains；版本从 Pkg.Revision 读（如 27.3.x）。 */
    private fun ndkComp(home: java.io.File?): EnvComp {
        val ok = home != null && java.io.File(home, "source.properties").isFile &&
            java.io.File(home, "toolchains").isDirectory
        val ver = home?.let { propValue(java.io.File(it, "source.properties"), "Pkg.Revision") } ?: ""
        return EnvComp("NDK", ok, ver)
    }

    /** build-tools：arm64 aapt2（SDK 可能混 x86_64，须 ELF aarch64）；版本 = 最高版本目录名。 */
    private fun buildtoolsComp(sdkHome: java.io.File?): EnvComp {
        val btDir = sdkHome?.let { java.io.File(it, "build-tools") }
            ?: return EnvComp("build-tools", false, "")
        if (!btDir.isDirectory) return EnvComp("build-tools", false, "")
        // 版本子目录按版本降序（数字段比较，避免 "9" 排在 "10" 前），取第一个含 arm64 aapt2 的
        val verDirs = (btDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedByDescending { v -> v.name.split('.').fold(0L) { acc, p -> acc * 1000 + (p.toIntOrNull() ?: 0) } }
        for (vd in verDirs) {
            val aapt2 = java.io.File(vd, "aapt2")
            if (isArm64Elf(aapt2)) return EnvComp("build-tools", true, vd.name)
        }
        return EnvComp("build-tools", false, "")
    }

    /** platform-tools：arm64 adb；版本从 source.properties 的 Pkg.Revision 读（如 37.0.0）。 */
    private fun platformtoolsComp(sdkHome: java.io.File?): EnvComp {
        val platDir = sdkHome?.let { java.io.File(it, "platform-tools") }
            ?: return EnvComp("platform-tools", false, "")
        val ok = isArm64Elf(java.io.File(platDir, "adb"))
        val ver = propValue(java.io.File(platDir, "source.properties"), "Pkg.Revision")
        return EnvComp("platform-tools", ok, ver)
    }

    /** CMake：bin/cmake + share 目录；版本从 share/cmake-<ver> 目录名读（如 4.3）。 */
    private fun cmakeComp(home: java.io.File?): EnvComp {
        val ok = home != null && java.io.File(home, "bin/cmake").isFile &&
            java.io.File(home, "share").isDirectory
        val ver = home?.let { h ->
            (java.io.File(h, "share").listFiles() ?: emptyArray())
                .asSequence()
                .filter { it.isDirectory && it.name.startsWith("cmake-") }
                .map { it.name.removePrefix("cmake-") }
                .maxByOrNull { it.toDoubleOrNull() ?: -1.0 }
                .orEmpty()
        } ?: ""
        return EnvComp("CMake", ok, ver)
    }

    /** Flutter：bin/flutter；版本多源回退：version → bin/cache/flutter.version.json → 浅克隆 commit 短哈希。 */
    private fun flutterComp(home: java.io.File?): EnvComp {
        val ok = home != null && java.io.File(home, "bin/flutter").isFile
        val ver = home?.let { flutterVersion(it) } ?: ""
        return EnvComp("Flutter", ok, ver)
    }

    private fun flutterVersion(home: java.io.File): String {
        // 1) <home>/version（部分发行版/自定义构建会写）
        val v1 = runCatching {
            val f = java.io.File(home, "version")
            if (f.isFile) f.readText().trim() else ""
        }.getOrDefault("")
        if (v1.isNotBlank()) return v1.take(40)
        // 2) bin/cache/flutter.version.json 的 frameworkVersion
        val v2 = runCatching {
            val f = java.io.File(home, "bin/cache/flutter.version.json")
            if (!f.isFile) "" else Regex("\"frameworkVersion\"\\s*:\\s*\"([^\"]+)\"")
                .find(f.readText())?.groupValues?.get(1).orEmpty()
        }.getOrDefault("")
        if (v2.isNotBlank()) return v2
        // 3) 浅克隆没有 version/tag：给 commit 短哈希（比「版本未知」具体，可定位快照）
        return runCatching {
            val headFile = java.io.File(home, ".git/HEAD")
            if (!headFile.isFile) return@runCatching ""
            val head = headFile.readText().trim()
            val ref = if (head.startsWith("ref: ")) head.removePrefix("ref: ").trim() else head
            val hash = if (ref.startsWith("refs/")) {
                val rf = java.io.File(home, ".git/$ref")
                if (rf.isFile) rf.readText().trim() else ""
            } else ref
            if (hash.length >= 7) "commit ${hash.take(7)}" else ""
        }.getOrDefault("")
    }

    /** ELF aarch64 校验：magic=7f454c46 且 e_machine(offset 18, LE)=0xb7。防把 x86_64 的 aapt2/adb 当已装。 */
    private fun isArm64Elf(f: java.io.File): Boolean = runCatching {
        if (!f.isFile || f.length() < 20) return@runCatching false
        val hdr = java.io.RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(20); raf.readFully(b); b
        }
        hdr[0] == 0x7f.toByte() && hdr[1] == 0x45.toByte() && hdr[2] == 0x4c.toByte() &&
            hdr[3] == 0x46.toByte() && hdr[18] == 0xb7.toByte() && hdr[19] == 0x00.toByte()
    }.getOrDefault(false)
}
