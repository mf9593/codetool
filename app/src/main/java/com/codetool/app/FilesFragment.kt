package com.codetool.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codetool.app.script.ScriptManager
import com.codetool.app.util.AppSettings
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理页：浏览 /sdcard/Download/codetool 下的文件，收藏快速访问，
 * 新建 / 删除文件或目录，切换到容器根目录浏览 Linux 内部文件树。
 */
class FilesFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var pathText: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var entryChips: ChipGroup
    private val entries = mutableListOf<File>()
    private lateinit var adapter: FileAdapter
    private var currentDir: File = defaultDir()

    /** 是否处在容器浏览模式（路径前缀 /data/data/.../rootfs）。 */
    private var inContainer = false

    /** 是否显示以 . 开头的隐藏文件（状态持久化，默认不显示）。 */
    private var showHidden = false

    companion object {
        @Volatile private var pendingPath: String? = null

        /** 由首页路径收藏跳转调用：打开指定目录。 */
        fun openPath(path: String) { pendingPath = path }
    }

    private fun defaultDir(): File =
        File(Environment.getExternalStorageDirectory(), "Download/codetool")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_files, container, false)
        list = v.findViewById(R.id.fileList)
        pathText = v.findViewById(R.id.pathText)
        emptyState = v.findViewById(R.id.emptyState)
        entryChips = v.findViewById(R.id.entryChips)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = FileAdapter(requireContext(), entries)
        list.adapter = adapter
        v.findViewById<ImageView>(R.id.upBtn).setOnClickListener { goUp() }
        v.findViewById<ImageView>(R.id.btnNew).setOnClickListener { showNewSheet() }
        // 标题栏复制按钮：复制当前目录完整路径
        v.findViewById<ImageView>(R.id.btnCopyPath).setOnClickListener { copyPath(currentDir) }
        // 地址栏：点击跳转、长按复制当前路径
        pathText.setOnClickListener { showGotoDialog() }
        pathText.setOnLongClickListener { copyPath(currentDir); true }
        showHidden = AppSettings.fileShowHidden(requireContext())
        // 容器根目录：已是容器模式时再点无效（不能把选中态取消掉，与其它 chip 行为一致）
        v.findViewById<Chip>(R.id.btnContainer).setOnClickListener { c ->
            val chip = c as Chip
            if (inContainer) {
                chip.isChecked = true
                return@setOnClickListener
            }
            toggleContainer()
        }
        bindDirChip(v.findViewById(R.id.btnCodeToolDir)) {
            File(Environment.getExternalStorageDirectory(), "Download/codetool")
        }
        bindDirChip(v.findViewById(R.id.btnDownloadDir)) {
            File(Environment.getExternalStorageDirectory(), "Download")
        }
        // 日志目录：外部 Android/data/<pkg>/files/logs（文件管理器可见）
        bindDirChip(v.findViewById(R.id.btnLogDir)) {
            context?.getExternalFilesDir("logs")
                ?: File(Environment.getExternalStorageDirectory(), "Android/data/com.codetool.app/files/logs")
        }
        // 应用数据根目录（外部）：Android/data/com.codetool.app/
        bindDirChip(v.findViewById(R.id.btnAppDataDir)) {
            File(Environment.getExternalStorageDirectory(), "Android/data/com.codetool.app")
        }
        // 内部数据根目录：/data/user/0/com.codetool.app（files/rootfs、日志、share 桥都在这）
        bindDirChip(v.findViewById(R.id.btnInternalDataDir)) {
            File(requireContext().applicationInfo.dataDir)
        }
        return v
    }

    /**
     * 固定入口 chip 的统一点击逻辑：
     * 已经在该目录时再次点击只保持选中，不触发重新加载、也不会取消选中（此前容器根目录会把自己点掉）。
     */
    private fun bindDirChip(chip: Chip, dirProvider: () -> File?) {
        chip.setOnClickListener {
            val dir = dirProvider() ?: return@setOnClickListener
            if (!inContainer && currentDir.absolutePath == dir.absolutePath) {
                chip.isChecked = true
                return@setOnClickListener
            }
            if (!dir.exists()) dir.mkdirs()
            gotoDir(dir)
        }
    }

    override fun onResume() {
        super.onResume()
        // 设置弹窗可能改过隐藏文件开关，回前台重新读一次
        showHidden = AppSettings.fileShowHidden(requireContext())
        val p = pendingPath
        if (p != null) {
            pendingPath = null
            val f = File(p)
            if (f.isDirectory) currentDir = f
        }
        refresh()
    }

    private fun refresh() {
        val files = currentDir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        entries.clear()
        entries.addAll(files)
        adapter?.notifyDataSetChanged()
        pathText.text = if (inContainer) "🐧 " + currentDir.absolutePath else currentDir.absolutePath
        val empty = entries.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        renderFavChips()
        updateQuickEntries()
    }

    /** 容器模式下不允许 up 到 rootfs 之外。 */
    private fun goUp() {
        val parent = currentDir.parentFile ?: return
        if (inContainer) {
            val rootfs = try {
                com.codetool.app.runtime.RootfsManager.get(requireContext().applicationContext)
                    .ensureExtractedSync()
            } catch (_: Exception) { null }
            val root = rootfs?.absolutePath
            // 不允许越过容器根目录
            if (root != null && parent.absolutePath == root) return
        }
        currentDir = parent
        refresh()
    }

    /** 切换到容器 / 回到外部存储。 */
    private fun toggleContainer() {
        val appCtx = requireContext().applicationContext
        val rootfs = try {
            com.codetool.app.runtime.RootfsManager.get(appCtx).ensureExtractedSync()
        } catch (e: Exception) {
            Toast.makeText(appCtx, "容器未就绪: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        inContainer = !inContainer
        currentDir = if (inContainer) rootfs else defaultDir()
        refresh()
    }

    /** 切到外部存储的固定目录（退出容器模式）。 */
    private fun gotoDir(dir: File) {
        inContainer = false
        currentDir = dir
        refresh()
    }

    /** 点击地址栏：弹输入框，输入绝对路径跳转。 */
    private fun showGotoDialog() {
        val ctx = requireContext()
        val v = layoutInflater.inflate(R.layout.dialog_goto_path, null)
        val input = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPath)
        input.setText(currentDir.absolutePath)
        input.setSelection(input.text?.length ?: 0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("跳转到路径")
            .setView(v)
            .setPositiveButton("跳转") { _, _ ->
                val target = input.text?.toString()?.trim().orEmpty()
                if (target.isEmpty()) {
                    Toast.makeText(ctx, "路径为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val f = File(target)
                if (!f.exists()) {
                    Toast.makeText(ctx, "目录不存在：$target", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!f.isDirectory) {
                    Toast.makeText(ctx, "不是目录：$target", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                gotoDir(f)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 仅供标题栏「文件设置」弹窗读取当前开关状态。 */
    fun isShowHidden(): Boolean = showHidden

    /**
     * 由标题栏「文件设置」弹窗调用：写入开关并立即刷新列表。
     * 持久化交给调用方（MainActivity）一起做，避免两处反复写 SharedPreferences。
     */
    fun setShowHidden(enabled: Boolean) {
        if (showHidden == enabled) return
        showHidden = enabled
        if (view != null) refresh()
    }

    /** 刷新固定入口的激活态（当前所在目录高亮）。 */
    private fun updateQuickEntries() {
        val v = view ?: return
        val ext = Environment.getExternalStorageDirectory()
        val codetool = File(ext, "Download/codetool")
        val download = File(ext, "Download")
        val logs = requireContext().getExternalFilesDir("logs")
        val appData = File(ext, "Android/data/com.codetool.app")
        val internalData = File(requireContext().applicationInfo.dataDir)
        fun same(a: File?, b: File?) =
            a != null && b != null && a.absolutePath.trimEnd('/') == b.absolutePath.trimEnd('/')
        setChipChecked(v.findViewById(R.id.btnContainer), inContainer)
        setChipChecked(v.findViewById(R.id.btnCodeToolDir), !inContainer && same(currentDir, codetool))
        setChipChecked(v.findViewById(R.id.btnDownloadDir), !inContainer && same(currentDir, download))
        setChipChecked(v.findViewById(R.id.btnLogDir), !inContainer && same(currentDir, logs))
        // 之前漏了「应用数据」：其它 chip 选中会高亮，它永远不变 → 看起来样式不一致
        setChipChecked(v.findViewById(R.id.btnAppDataDir), !inContainer && same(currentDir, appData))
        setChipChecked(v.findViewById(R.id.btnInternalDataDir), !inContainer && same(currentDir, internalData))
    }

    private fun setChipChecked(chip: Chip, checked: Boolean) {
        // 描边宽度无法用 state list 表达，跟随 checked 手动设置：
        // 选中 1.5dp 主色（同终端标签），未选中 1dp。
        chip.isChecked = checked
        val density = resources.displayMetrics.density
        chip.chipStrokeWidth = (if (checked) 1.5f else 1f) * density
    }

    /** 新建底部弹窗：新建文件 / 新建文件夹 / 新建脚本。 */
    private fun showNewSheet() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val onSurface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        box.addView(TextView(ctx).apply {
            text = "在当前目录新建"
            textSize = 17f
            setTextColor(onSurface)
            setPadding(dp(24), dp(14), dp(24), dp(2))
        })

        val sheet = BottomSheetDialog(ctx)
        // 新建文件（在当前目录用编辑器创建一个空文件并保存）
        box.addView(sheetRow(ctx, R.drawable.ic_edit, "新建文件", onSurface) {
            sheet.dismiss()
            ScriptEditActivity.launchCreate(requireContext(), currentDir, "新建文件")
        })
        // 新建文件夹（弹窗输入名称）
        box.addView(sheetRow(ctx, R.drawable.ic_folder, "新建文件夹", onSurface) {
            sheet.dismiss()
            promptNewFolder()
        })
        // 新建脚本（跳到脚本目录用编辑器创建）
        box.addView(sheetRow(ctx, R.drawable.ic_download, "新建脚本", onSurface) {
            sheet.dismiss()
            ScriptEditActivity.launchCreate(requireContext(), ScriptManager.scriptDir, "新建脚本")
        })
        sheet.setContentView(box)
        sheet.show()
    }

    /** 弹窗输入文件夹名后创建。 */
    private fun promptNewFolder() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "文件夹名称（不能包含 /）"
            setPadding(dp2(ctx, 24), dp2(ctx, 14), dp2(ctx, 24), dp2(ctx, 14))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("新建文件夹")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (name.contains("/")) {
                    Toast.makeText(ctx, "名称不能包含 /", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newDir = File(currentDir, name)
                if (newDir.exists()) {
                    Toast.makeText(ctx, "已存在", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newDir.mkdirs()) {
                    Toast.makeText(ctx, "已创建：$name", Toast.LENGTH_SHORT).show()
                    refresh()
                } else {
                    Toast.makeText(ctx, "创建失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp2(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun renderFavChips() {
        val ctx = requireContext()
        val favs = ScriptManager.favPaths(ctx)
        // 只移除动态追加的收藏 chip（tag 以 "fav:" 开头），保留固定的快速入口
        val stale = ArrayList<View>()
        for (i in 0 until entryChips.childCount) {
            val c = entryChips.getChildAt(i)
            if ((c.tag as? String)?.startsWith("fav:") == true) stale.add(c)
        }
        for (c in stale) entryChips.removeView(c)
        for (p in favs.sorted()) {
            val chip = layoutInflater
                .inflate(R.layout.view_file_chip, entryChips, false) as Chip
            chip.apply {
                text = File(p).name.ifEmpty { p }
                tag = "fav:$p"
                setOnClickListener {
                    if (!inContainer && currentDir.absolutePath == p) {
                        isChecked = true
                        return@setOnClickListener
                    }
                    val f = File(p)
                    if (f.isDirectory) { currentDir = f; refresh() }
                }
                setOnLongClickListener {
                    ScriptManager.togglePathFav(ctx, p)
                    Toast.makeText(ctx, "已取消收藏", Toast.LENGTH_SHORT).show()
                    renderFavChips(); refresh()
                    true
                }
            }
            setChipChecked(chip, currentDir.absolutePath == p)
            entryChips.addView(chip)
        }
    }

    private fun toggleFav(f: File) {
        val ctx = requireContext()
        val path = f.absolutePath
        val fav = ScriptManager.isPathFav(ctx, path)
        ScriptManager.togglePathFav(ctx, path)
        Toast.makeText(ctx, if (fav) "已取消收藏: ${f.name}" else "已收藏: ${f.name}", Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** 删除文件/文件夹（带二次确认，容器模式只允许删容器内可写的）。 */
    private fun deleteItem(f: File) {
        val ctx = requireContext()
        val isDir = f.isDirectory
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除${if (isDir) "文件夹" else "文件"}")
            .setMessage("${f.name}\n\n此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                try {
                    if (isDir) {
                        // 清空目录内容再删空壳，避免递归耗时长/占空间
                        f.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    if (f.delete()) {
                        Toast.makeText(ctx, "已删除: ${f.name}", Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        Toast.makeText(ctx, "删除失败（权限不足）", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private inner class FileAdapter(
        private val ctx: Context,
        private val data: List<File>
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.fileIcon)
            val name: TextView = view.findViewById(R.id.fileName)
            val info: TextView = view.findViewById(R.id.fileInfo)
            val fav: ImageView = view.findViewById(R.id.fileFav)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_file, parent, false)
            return VH(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = data[pos]
            val isDir = f.isDirectory
            val isApk = !isDir && f.name.lowercase(Locale.US).endsWith(".apk")
            h.icon.setImageResource(
                when {
                    isDir -> R.drawable.ic_folder
                    isApk -> R.drawable.ic_apk
                    else -> R.drawable.ic_files
                }
            )
            h.name.text = f.name
            val info = if (isDir) {
                val n = f.listFiles()?.size ?: 0
                "目录 · $n 项"
            } else {
                val kb = f.length() / 1024.0
                val size = if (kb < 1) "${f.length()} B" else if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
                val mtime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
                if (isApk) "安装包 · $size · $mtime" else "$size · $mtime"
            }
            h.info.text = info
            val fav = ScriptManager.isPathFav(ctx, f.absolutePath)
            h.fav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
            h.itemView.setOnClickListener {
                when {
                    isDir -> { currentDir = f; refresh() }
                    isApk -> installApk(f)
                    // 文本文件（含 .log）：单击直接用内置编辑器打开，不走弹窗
                    isTextFile(f) -> ScriptEditActivity.launch(requireContext(), f.absolutePath)
                    else -> showFileActions(f)
                }
            }
            h.itemView.setOnLongClickListener {
                showItemSheet(f); true
            }
            h.fav.setOnClickListener { toggleFav(f) }
        }
    }

    /** 长按文件/文件夹：底部菜单（复制完整路径 / 收藏 / cd 到终端 / 删除）。 */
    private fun showItemSheet(f: File) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val onSurface = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorOnSurfaceVariant)
        val onError = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorError)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        box.addView(TextView(ctx).apply {
            text = f.name
            textSize = 17f
            setTextColor(onSurface)
            setPadding(dp(24), dp(14), dp(24), dp(2))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        box.addView(TextView(ctx).apply {
            text = f.absolutePath
            textSize = 12f
            setTextColor(onSurfaceVariant)
            setPadding(dp(24), 0, dp(24), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })

        val sheet = BottomSheetDialog(ctx)
        val fav = ScriptManager.isPathFav(ctx, f.absolutePath)
        box.addView(sheetRow(ctx, R.drawable.ic_copy, "复制完整路径", onSurface) {
            copyPath(f); sheet.dismiss()
        })
        box.addView(sheetRow(ctx, if (fav) R.drawable.ic_star else R.drawable.ic_star_outline,
            if (fav) "取消收藏" else "收藏", onSurface) {
            toggleFav(f); sheet.dismiss()
        })
        box.addView(sheetRow(ctx, R.drawable.ic_terminal, "cd 到终端", onSurface) {
            cdToTerminal(f); sheet.dismiss()
        })
        // 用其他应用打开（文本编辑器等）：调系统应用选择器，方便查看 .log 等文件
        if (f.isFile) {
            box.addView(sheetRow(ctx, R.drawable.ic_open, "用其他应用打开", onSurface) {
                sheet.dismiss()
                openWithExternal(f)
            })
        }
        box.addView(sheetRow(ctx, R.drawable.ic_delete, "删除${if (f.isDirectory) "文件夹" else "文件"}", onError) {
            sheet.dismiss()
            deleteItem(f)
        })
        sheet.setContentView(box)
        sheet.show()
    }

    private fun sheetRow(ctx: Context, iconRes: Int, label: String, tint: Int, onClick: () -> Unit): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isClickable = true
            val bg = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
            setBackgroundResource(bg.resourceId)
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        row.addView(TextView(ctx).apply {
            text = label
            textSize = 15f
            setTextColor(tint)
            setPadding(dp(16), 0, 0, 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    private fun copyPath(f: File) {
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
        Toast.makeText(ctx, "已复制路径", Toast.LENGTH_SHORT).show()
    }

    /**
     * 点击 APK：经 FileProvider 生成 content:// URI，用系统安装器打开安装。
     * Android 8+ 需要 REQUEST_INSTALL_PACKAGES + 用户在系统设置里给本应用「安装未知应用」授权。
     * 未授权时系统会弹授权页；若仍无法打开，给出提示。
     */
    private fun installApk(f: File) {
        val ctx = requireContext()
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", f
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.onFailure { e ->
            Toast.makeText(ctx, "无法调起安装器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 用其他应用打开文件（文本编辑器等）。经 FileProvider 生成 content:// URI
     * 授予临时读权限，调系统应用选择器；按扩展名给 MIME（.log/.txt → text/plain）。
     */
    private fun openWithExternal(f: File) {
        val ctx = requireContext()
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", f
            )
            val mime = when (f.extension.lowercase(Locale.US)) {
                "log", "txt", "sh", "conf", "json", "xml", "md" -> "text/plain"
                else -> "application/octet-stream"
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 没有应用能打开时兜底：改成发送文本内容
            if (view.resolveActivity(ctx.packageManager) == null) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, f.readText().take(100000))
                    putExtra(Intent.EXTRA_SUBJECT, f.name)
                }
                ctx.startActivity(Intent.createChooser(send, "用其他应用打开 ${f.name}"))
            } else {
                ctx.startActivity(Intent.createChooser(view, "用其他应用打开 ${f.name}"))
            }
        }.onFailure { e ->
            Toast.makeText(ctx, "打开失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * cd 到终端：容器（tawcroot）未绑定 /sdcard，共享存储路径无法进入容器。
     * 仅当目标在 rootfs 内时，换算为容器内路径（去掉 rootfs 前缀）后发送 cd。
     * 容器模式下传当前绝对路径即对应容器内路径。
     */
    private fun cdToTerminal(f: File) {
        val appCtx = requireContext().applicationContext
        val dir = if (f.isDirectory) f else f.parentFile ?: return
        Thread {
            val rootfs = try {
                com.codetool.app.runtime.RootfsManager.get(appCtx).ensureExtractedSync()
            } catch (e: Exception) {
                null
            }
            val rootPath = rootfs?.absolutePath
            val rel = if (inContainer) {
                // 容器模式：路径已经是容器内路径的宿主映射，去掉 rootfs 前缀即为容器路径
                dir.absolutePath.removePrefix(rootPath ?: "").ifEmpty { "/" }
            } else if (rootPath != null &&
                (dir.absolutePath == rootPath || dir.absolutePath.startsWith("$rootPath/"))) {
                dir.absolutePath.removePrefix(rootPath).ifEmpty { "/" }
            } else null
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (rel == null) {
                    Toast.makeText(requireContext(),
                        "该路径不在容器内（容器未挂载共享存储）", Toast.LENGTH_LONG).show()
                } else {
                    val quoted = rel.replace("'", "'\\''")
                    TerminalFragment.sendCommand(requireContext(), "cd '$quoted'")
                }
            }
        }.start()
    }

    private fun showFileActions(f: File) {
        val ctx = requireContext()
        // 非文本文件：去掉「收藏」，改放「用其他应用打开」
        MaterialAlertDialogBuilder(ctx)
            .setTitle(f.name)
            .setMessage("${f.absolutePath}\n\n${if (f.isDirectory) "目录" else "${f.length()} 字节"}")
            .setPositiveButton("用其他应用打开") { _, _ -> openWithExternal(f) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 是否文本文件（可安全用内置编辑器打开）：按扩展名判断。
     * .log/.txt/.sh/.conf/.json/.xml/.md 及常见代码/配置后缀都算文本。
     */
    private fun isTextFile(f: File): Boolean {
        if (f.isDirectory) return false
        val ext = f.extension.lowercase(Locale.US)
        val textExts = setOf(
            "log", "txt", "text", "sh", "bash", "conf", "cfg", "ini", "json",
            "xml", "md", "py", "js", "ts", "java", "kt", "kts", "c", "cpp",
            "h", "hpp", "go", "rs", "rb", "pl", "lua", "yaml", "yml", "toml",
            "properties", "gradle", "cmake", "mk", "sql", "html", "css", "csv"
        )
        if (ext in textExts) return true
        // 无扩展名但内容看起来是文本（前 1KB 无 NUL 字节）也当作文本，
        // 这样 /etc/hosts、Dockerfile、脚本等能直接打开。
        if (ext.isEmpty() && f.length() in 1..2_000_000) {
            return runCatching {
                f.inputStream().use { ins ->
                    val buf = ByteArray(1024)
                    val n = ins.read(buf)
                    if (n <= 0) false else !buf.copyOf(n).contains(0.toByte())
                }
            }.getOrDefault(false)
        }
        return false
    }
}
