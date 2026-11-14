package com.codetool.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.codetool.app.theme.ThemeManager
import com.codetool.app.util.AppSettings
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    // 五个页面：首页 / 终端 / 工具(脚本库|包管理 tab) / 文件 / 日志
    private val fragments = listOf(
        HomeFragment(),      // 首页：状态 + 终端开关
        TerminalFragment(),  // 终端 + 画面
        ToolsFragment(),     // 工具：脚本库 | 包管理（同页 tab）
        FilesFragment(),     // 文件：文件管理（独立页）
        TasksFragment()      // 日志管理
    )

    private val titles = listOf("首页", "终端", "工具", "文件管理", "日志")

    // 菜单 ID：主题入口 + 设置 + 键盘切换 + GUI 显示
    private val MENU_THEME = 100
    private val MENU_SETTINGS = 300
    private val MENU_KEYBOARD = 400
    private val MENU_DISPLAY = 500
    private val MENU_COPY = 600
    private val MENU_CLEAR = 700
    private val MENU_ABOUT = 750

    /** 首次启动用户协议页的请求码。 */
    private val REQUEST_AGREEMENT = 900
    private val REQUEST_ONBOARD = 901

    /** 当前页位置（用于键盘切换按钮的显隐）。 */
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用当前主题（必须在 setContentView 前；换主题后 recreate 时全局生效）
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        // 崩溃页自检：仅 debug 构建 + 显式 extra 才触发，正常使用永不命中。
        // 用法：adb shell am start -n com.codetool.app/.MainActivity --ez codetool_debug_crash true
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug && intent?.getBooleanExtra("codetool_debug_crash", false) == true) {
            throw IllegalStateException("崩溃页自检（debug 构建专用，验证防闪退机制）")
        }
        // 沉浸式 edge-to-edge：内容延伸到系统栏下，手动处理 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.viewPager)
        bottomNav = findViewById(R.id.bottomNav)

        // 用 Toolbar 替代默认 ActionBar（消除双标题栏）
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        applySystemInsets(toolbar)
        viewPager.adapter = PagerAdapter(this)
        // 允许左右滑动切换
        viewPager.offscreenPageLimit = fragments.size

        // 底部导航 ↔ ViewPager2 联动
        bottomNav.setOnItemSelectedListener { item ->
            val idx = when (item.itemId) {
                R.id.nav_home -> 0
                R.id.nav_terminal -> 1
                R.id.nav_tools -> 2
                R.id.nav_files -> 3
                R.id.nav_tasks -> 4
                else -> 0
            }
            viewPager.currentItem = idx
            true
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPage = position
                bottomNav.menu.getItem(position).isChecked = true
                supportActionBar?.title = titles[position]
                // 键盘切换按钮仅终端页显示
                invalidateOptionsMenu()
                // 首页等非终端页收起输入法：offscreenPageLimit=全部会预创建 TerminalFragment，
                // 其 TerminalView.requestFocus() 会在冷启动时抢焦点弹键盘。
                hideImeUnlessTerminal(position)
            }
        })

        if (savedInstanceState == null) {
            viewPager.currentItem = 0
            supportActionBar?.title = titles[0]
        }

        requestPermissionsIfNeeded()

        // 首次启动强制用户协议：未同意当前版本 → 进全屏协议页（勾选 + 下一步）。
        // 放在 rootfs 自动解压等重操作之前，未同意不干活。RESULT_OK=同意；RESULT_CANCELED=不同意（退出）。
        if (!UserAgreement.isAccepted(this)) {
            AgreementActivity.startFirstLaunch(this, REQUEST_AGREEMENT)
            return  // 未同意前不继续初始化（不自动解压 rootfs）；结果在 onActivityResult 处理
        }

        // 协议已同意 → 首次进入引导页（电池无限制 + 通知前台保活，两项授权后下一步）。
        if (!AppSettings.onboardingDone(this)) {
            OnboardingActivity.start(this, REQUEST_ONBOARD)
            return  // 引导未完成前不初始化；结果在 onActivityResult 处理
        }

        // 首次启动自动解压 rootfs（如果还没就绪）
        maybeAutoInstallRootfs()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_AGREEMENT -> {
                if (resultCode == RESULT_OK) {
                    // 已同意协议 → 进引导页（若未完成）
                    if (!AppSettings.onboardingDone(this)) {
                        OnboardingActivity.start(this, REQUEST_ONBOARD)
                    } else {
                        maybeAutoInstallRootfs()
                    }
                } else {
                    finishAffinity()  // 不同意协议：退出
                }
            }
            REQUEST_ONBOARD -> {
                if (resultCode == RESULT_OK) {
                    maybeAutoInstallRootfs()  // 引导完成：继续正常初始化
                } else {
                    finishAffinity()  // 引导页返回：退出（未授权不进主页）
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 冷启动 / 热恢复兜底：默认首页不该弹输入法。
        // ViewPager 预加载的 TerminalFragment 会在稍后异步 requestFocus 弹 IME，
        // 单次 hide 会与之竞态（时弹时不弹），故立即 + 延迟各收一次。
        hideImeUnlessTerminal(currentPage)
        // 延迟兜底：只在「仍停留在首页」时才收，避免误伤用户主动切到终端页打字。
        window.decorView.postDelayed({ if (currentPage != 1) hideImeUnlessTerminal(currentPage) }, 400)
        window.decorView.postDelayed({ if (currentPage != 1) hideImeUnlessTerminal(currentPage) }, 1200)
    }

    /** 非终端页收起输入法并清焦点，避免预创建的 TerminalView 抢焦点弹键盘。 */
    private fun hideImeUnlessTerminal(position: Int) {
        if (position == 1) return  // 终端页保留正常聚焦
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.ime())
        window.decorView.clearFocus()
        // 有些 IME 由具体子 View 触发，decorView.clearFocus 不够，再让当前焦点 View 也清一次
        currentFocus?.clearFocus()
    }

    private fun maybeAutoInstallRootfs() {
        val mgr = com.codetool.app.runtime.RootfsManager.get(this)
        // 已解压的旧 rootfs 也可能缺 CJK 字体 / 中文 locale：后台补一份（幂等）。
        Thread {
            mgr.ensureCjkFont()
            mgr.ensureZhLocale()
        }.start()
        if (mgr.isReady()) return
        val intent = Intent(this, com.codetool.app.runtime.RootfsInstallService::class.java)
            .setAction(com.codetool.app.runtime.RootfsInstallService.ACTION_START)
        startForegroundService(intent)
    }

    /** 处理系统栏 & IME insets：顶部状态栏、底部手势条、键盘弹出时隐藏底栏并让终端 resize。 */
    private fun applySystemInsets(toolbar: View) {
        val root = findViewById<View>(R.id.rootLayout)
        val appbar = findViewById<View>(R.id.appBar)
        val nav = bottomNav
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // 顶部：AppBar 让出状态栏
            appbar.updatePadding(top = status.top)
            toolbar.updatePadding(top = 0)
            // 底部：底栏让出手势条
            nav.updatePadding(bottom = navBars.bottom)

            // 键盘弹出：隐藏底栏，终端页获得全高度（避免拉伸）
            val imeVisible = ime.bottom > 0
            if (imeVisible) {
                nav.visibility = View.GONE
            } else {
                nav.visibility = View.VISIBLE
            }
            // IME 作为 viewPager 底部 padding，让终端 resize 到键盘上方
            viewPager.updatePadding(bottom = if (imeVisible) ime.bottom else 0)
            // 强制终端 resize（否则输入法弹出时终端不刷新，等隐藏才更新）
            viewPager.post { TerminalFragment.instance?.resizeTerminal() }
            insets
        }
    }

    /** 标题栏右侧菜单：主题（弹窗选色）+ 设置（预留空间可继续加按钮）。 */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 图标跟随主题色（深色主题→白色图标，浅色主题→黑色图标）
        val iconColor = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFFDEE4E2.toInt())
        val icons = listOf(R.drawable.ic_palette, R.drawable.ic_settings,
            R.drawable.ic_keyboard, R.drawable.ic_display)
        // 主题：仅首页显示（点击弹底部弹窗显示主题色块，点击切换）
        val theme = menu.add(0, MENU_THEME, 0, "更换主题")
        theme.setIcon(R.drawable.ic_palette)
        theme.icon?.mutate()?.setTint(iconColor)
        theme.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        theme.isVisible = currentPage == 0
        // 关于：仅首页显示
        val about = menu.add(0, MENU_ABOUT, 1, "关于")
        about.setIcon(R.drawable.ic_info)
        about.icon?.mutate()?.setTint(iconColor)
        about.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        about.isVisible = currentPage == 0
        // 设置（字号等）—— 仅终端页显示
        val settings = menu.add(0, MENU_SETTINGS, 1, "设置")
        settings.setIcon(R.drawable.ic_settings)
        settings.icon?.mutate()?.setTint(iconColor)
        settings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        settings.isVisible = (currentPage == 1)
        // 键盘切换（显示/隐藏终端快捷键；仅终端页可见）
        val kb = menu.add(0, MENU_KEYBOARD, 2, "快捷键")
        kb.setIcon(R.drawable.ic_keyboard)
        kb.icon?.mutate()?.setTint(iconColor)
        kb.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        kb.isVisible = (currentPage == 1)
        // GUI 显示（arlinux 风格桌面；仅终端页可见）
        val disp = menu.add(0, MENU_DISPLAY, 3, "显示")
        disp.setIcon(R.drawable.ic_display)
        disp.icon?.mutate()?.setTint(iconColor)
        disp.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        disp.isVisible = (currentPage == 1)
        // 日志页：复制 / 清空移到标题栏（仅日志页可见）
        val copy = menu.add(0, MENU_COPY, 4, "复制")
        copy.setIcon(R.drawable.ic_copy)
        copy.icon?.mutate()?.setTint(iconColor)
        copy.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        copy.isVisible = currentPage == 4
        val clear = menu.add(0, MENU_CLEAR, 5, "清空")
        clear.setIcon(R.drawable.ic_delete)
        clear.icon?.mutate()?.setTint(iconColor)
        clear.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        clear.isVisible = currentPage == 4
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_THEME) {
            showThemeSheet()
            return true
        }
        if (item.itemId == MENU_ABOUT) {
            startActivity(Intent(this, AboutActivity::class.java))
            return true
        }
        if (item.itemId == MENU_COPY) {
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.copyLog()
            return true
        }
        if (item.itemId == MENU_CLEAR) {
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.clearLog()
            return true
        }
        if (item.itemId == MENU_SETTINGS) {
            showFontSizeDialog()
            return true
        }
        if (item.itemId == MENU_KEYBOARD) {
            TerminalFragment.instance?.toggleShortcutRow()
            return true
        }
        if (item.itemId == MENU_DISPLAY) {
            // 终端页「显示」直接进横屏合成器渲染，跳过中间状态页。
            startActivity(Intent(this, com.codetool.app.gui.TawcCompositorActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 主题选择底部弹窗：模式（浅色/深色/跟随系统按钮切换）+ 8 色网格 + 随机。 */
    private fun showThemeSheet() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val curMode = ThemeManager.currentMode(this)
        val curColor = ThemeManager.currentColor(this)
        val sheet = BottomSheetDialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(32))
        }
        container.addView(android.widget.TextView(this).apply {
            text = "更换主题"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        })

        // ---- 模式：按钮切换（不看颜色）----
        val modeLabel = android.widget.TextView(this).apply {
            text = "模式"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(modeLabel)
        val toggle = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(0, 0, 0, dp(6))
        }
        for (m in ThemeManager.Mode.entries) {
            val b = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = m.displayName
                tag = m
                isCheckable = true
                id = android.view.View.generateViewId()
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            b.setOnClickListener { ThemeManager.setMode(this@MainActivity, m) }
            toggle.addView(b, android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (m == curMode) toggle.check(b.id)
        }
        container.addView(toggle, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))

        container.addView(android.widget.TextView(this).apply {
            text = "颜色"
            textSize = 14f
            setPadding(0, dp(10), 0, dp(4))
        })

        // ---- 8 色网格（每行 4 个）----
        val colors = ThemeManager.Color.entries
        for (start in 0 until colors.size step 4) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            for (c in colors.subList(start, minOf(start + 4, colors.size))) {
                val cell = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    isClickable = true
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setBackgroundResource(R.drawable.ripple_round)
                    setOnClickListener { ThemeManager.setColor(this@MainActivity, c) }
                }
                val swatch = android.view.View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(androidx.core.content.ContextCompat.getColor(this@MainActivity,
                            if (curMode == ThemeManager.Mode.DARK) c.darkSwatch else c.lightSwatch))
                        if (c == curColor) setStroke(dp(3), android.graphics.Color.parseColor("#FF000000"))
                    }
                }
                cell.addView(swatch, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
                val label = android.widget.TextView(this).apply {
                    text = c.displayName
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                    if (c == curColor) setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity,
                        if (curMode == ThemeManager.Mode.DARK) c.darkSwatch else c.lightSwatch))
                }
                cell.addView(label)
                row.addView(cell, android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            container.addView(row)
        }

        // ---- 随机颜色按钮 ----
        val randBtn = com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "随机颜色"
            setIconResource(R.drawable.ic_palette)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { ThemeManager.setRandomColor(this@MainActivity) }
        }
        container.addView(randBtn, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        sheet.setContentView(container)
        sheet.show()
    }

    /** 关于：应用信息 + 参考开源项目地址。卡片式统一 UI。 */
    private fun showAbout() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val pInfo = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { null }
        val versionName = pInfo?.versionName ?: "debug"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: 0L
        } else {
            pInfo?.versionCode?.toLong() ?: 0L
        }

        // 背景/文字全部跟随当前主题（浅色→浅底深字，深色→深底浅字）。
        val onSurface = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurface, 0xFF1B1B1B.toInt())
        val onSurfaceVariant = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF44474A.toInt())
        val surfaceContainer = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainer, 0xFFE8E8E8.toInt())
        val surfaceCard = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurfaceContainerHigh, surfaceContainer)
        val bg = MaterialColors.getColor(
            this, android.R.attr.colorBackground, 0xFFFAFAFA.toInt())
        val accent = MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFF00505A.toInt())
        // 半透明分隔线（基于主文字色）。
        val divider = (onSurface and 0x00FFFFFF) or (0x22 shl 24)

        // 二级页面：全屏对话框，背景跟随主题。
        val sheet = android.app.Dialog(this)
        sheet.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        sheet.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        // ─── 头部：图标 + 名称 + 版本标签 ───
        val headerCard = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            
            setRadius(dp(20).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val headerLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48)).also { it.rightMargin = dp(16) }
        }.also { headerLayout.addView(it) }
        val titleLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        android.widget.TextView(this).apply {
            text = "CodeTool"
            textSize = 20f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { v -> titleLayout.addView(v) }
        android.widget.TextView(this).apply {
            text = "Android 终端容器 · 版本 $versionName ($versionCode)"
            textSize = 13f
            setTextColor(onSurfaceVariant)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        }.also { v -> titleLayout.addView(v) }
        headerLayout.addView(titleLayout)
        headerCard.addView(headerLayout)
        root.addView(headerCard)

        // ─── 信息卡片 ───
        val infoCard = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
            
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val infoLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        // 列头
        android.widget.TextView(this).apply {
            text = "应用信息"
            textSize = 14f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }.also { infoLayout.addView(it) }
        val infoRows = listOf(
            "包名" to packageName,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "设备" to Build.MODEL,
            "运行时" to "tawcroot + tawc 合成器",
            "开源协议" to "GPL-3.0",
        )
        infoRows.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.topMargin = dp(10); it.bottomMargin = dp(10) }
                    setBackgroundColor(divider)
                }.also { infoLayout.addView(it) }
            }
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }.also { row ->
                android.widget.TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(onSurfaceVariant)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dp(72), android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }.also { row.addView(it) }
                android.widget.TextView(this).apply {
                    text = value
                    textSize = 13f
                    setTextColor(onSurface)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }.also { row.addView(it) }
                infoLayout.addView(row)
            }
        }
        infoCard.addView(infoLayout)
        root.addView(infoCard)

        // ─── 开源项目卡片 ───
        val ossCard = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val ossLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        android.widget.TextView(this).apply {
            text = "参考开源项目"
            textSize = 14f
            setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        }.also { ossLayout.addView(it) }
        val projects = listOf(
            "tawc / tawcroot" to "https://github.com/taowen/tawc",
            "Smithay" to "https://github.com/smithay/smithay",
            "Termux" to "https://github.com/termux/termux-app",
            "proot-distro" to "https://github.com/termux/proot-distro",
            "sora-editor" to "https://github.com/Rosemoe/sora-editor",
            "libhybris" to "https://github.com/libhybris/libhybris",
            "labwc" to "https://github.com/labwc/labwc",
        )
        projects.forEachIndexed { i, (name, url) ->
            if (i > 0) {
                android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).also { it.topMargin = dp(6); it.bottomMargin = dp(6) }
                    setBackgroundColor(divider)
                }.also { ossLayout.addView(it) }
            }
            android.widget.TextView(this).apply {
                text = name
                textSize = 13f
                setTextColor(accent)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isClickable = true
                isFocusable = true
                // 让点击有 ripple 反馈
                val bg = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
                setOnClickListener {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }.also { ossLayout.addView(it) }
        }
        ossCard.addView(ossLayout)
        root.addView(ossCard)

        // ─── QQ 交流群卡片（点击跳转手机 QQ 群）───
        val qqCard = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(16) }
            setRadius(dp(16).toFloat())
            setBackgroundTintList(ColorStateList.valueOf(surfaceCard))
        }
        val qqRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { openQqGroup() }
        }
        android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_group)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(24), dp(24))
            setColorFilter(accent)
        }.also { qqRow.addView(it) }
        android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.leftMargin = dp(12) }
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "QQ交流群"
                textSize = 14f
                setTextColor(onSurface)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "群号 559711374 · 点击加入"
                textSize = 13f
                setTextColor(onSurfaceVariant)
            })
        }.also { qqRow.addView(it) }
        android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
            setColorFilter(onSurfaceVariant)
        }.also { qqRow.addView(it) }
        qqCard.addView(qqRow)
        root.addView(qqCard)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            // 背景跟随主题（浅色→浅底，深色→深底），文字用主题前景色，保证清晰。
            setBackgroundColor(bg)
        }
        scroll.addView(root)
        sheet.setContentView(scroll)
        sheet.show()
    }

    /** 打开 QQ 交流群：跳到 QQ 群聊天页；未安装 QQ 则提示。 */
    private fun openQqGroup() {
        // mqqwpa 协议：chat_type=group 打开指定群号（需已安装手机 QQ）
        val url = "mqqwpa://im/chat?chat_type=group&uin=559711374"
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "未检测到手机 QQ，请先安装", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 设置底部弹窗：终端字体大小 + 日志自动刷新开关。 */
    private fun showFontSizeDialog() {
        val dialog = BottomSheetDialog(this)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(32))
        }
        val valueText = android.widget.TextView(this).apply {
            text = "${AppSettings.fontSize(this@MainActivity)}dp"
            textSize = 28f
        }
        container.addView(android.widget.TextView(this).apply {
            text = "终端字体大小"
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        })
        val lp1 = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.addView(valueText, lp1)

        val slider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = AppSettings.MIN_FONT_SIZE.toFloat()
            valueTo = AppSettings.MAX_FONT_SIZE.toFloat()
            stepSize = 1f
            value = AppSettings.fontSize(this@MainActivity).toFloat()
        }
        val lp2 = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp2.setMargins(0, dp(8), 0, dp(8))
        container.addView(slider, lp2)
        // 拖动实时生效（字号即时应用到终端）
        slider.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            valueText.text = "${size}dp"
            AppSettings.setFontSize(this, size)
            TerminalFragment.instance?.updateFontSize(size)
        }

        // ---- 日志自动刷新开关 ----
        container.addView(android.widget.TextView(this).apply {
            text = "日志页"
            textSize = 16f
            setPadding(0, dp(12), 0, dp(4))
        })
        val switchRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val switchLabel = android.widget.TextView(this).apply {
            text = "日志自动刷新"
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }
        switchRow.addView(switchLabel, android.widget.LinearLayout.LayoutParams(0,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val autoRefreshSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = AppSettings.logAutoRefresh(this@MainActivity)
        }
        autoRefreshSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setLogAutoRefresh(this, isChecked)
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.setAutoRefresh(isChecked)
        }
        switchRow.addView(autoRefreshSwitch)
        container.addView(switchRow)

        dialog.setContentView(container)
        dialog.show()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
        // Android 11+ 分区存储：无「所有文件访问」时共享存储只能看到文件夹，
        // 其他应用创建的文件被系统隐藏。跳系统设置页引导用户授予。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
        // 电池优化「无限制」：锁屏后 Doze 会冻结后台进程（终端 ping 等不再运行）。
        // 这是锁屏冻结的根因修复——引导用户把本应用设为不优化（系统弹窗一次）。
        maybePromptBatteryOptimization()
    }

    /** 电池优化豁免：跳系统「忽略电池优化」弹窗；部分 ROM 不支持则跳应用详情页。 */
    private fun maybePromptBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (com.codetool.app.util.AppSettings.batteryPrompted(this)) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        com.codetool.app.util.AppSettings.setBatteryPrompted(this)
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
    }

    /** 首页路径收藏跳转：切到文件页并打开指定目录。 */
    fun openFilesAt(path: String) {
        FilesFragment.openPath(path)
        viewPager.currentItem = 3
        bottomNav.menu.getItem(3).isChecked = true
        supportActionBar?.title = titles[3]
    }

    /** 切到终端页（脚本运行后跳转，让用户看到输出）。 */
    fun switchToTerminal() {
        viewPager.currentItem = 1
        bottomNav.menu.getItem(1).isChecked = true
        supportActionBar?.title = titles[1]
    }

    private inner class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = fragments.size
        override fun createFragment(position: Int): Fragment = fragments[position]
    }
}
