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
import com.codetool.app.ai.AptMirror
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
    private val MENU_SEARCH = 650
    private val MENU_ABOUT = 750
    private val MENU_FILE_SETTINGS = 800
    private val MENU_LOG_SETTINGS = 810
    private val MENU_LOG_FILTER = 815
    private val MENU_KEY_HELP = 850
    /** 合成器运行中（顶栏图标，仅运行时显示，点击回合成器）。 */
    private val MENU_COMPOSITOR_RUNNING = 820

    /** 首次启动用户协议页的请求码。 */
    private val REQUEST_AGREEMENT = 900
    private val REQUEST_ONBOARD = 901
    private val REQUEST_ENV = 902

    /** 当前页位置（用于键盘切换按钮的显隐）。 */
    private var currentPage = 0

    companion object {
        /** 上次所在首页 tab 页码（内存变量，进程存活期间有效）。
         *  返回桌面通知栏还在=进程没死=恢复到该页；完全退出/杀进程=归零=回首页。 */
        private var lastPage = 0
    }

    /** 模型定时切换：前台边界自检 + 防误触重启提示弹窗。 */
    private val scheduleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // 低危：onResume 的两个 IME 隐藏延迟 Runnable，保存引用供 onPause 取消（防 Activity 销毁后执行）。
    private var hideImeRunnable1: Runnable? = null
    private var hideImeRunnable2: Runnable? = null
    private var scheduleTickRunnable: Runnable? = null
    private var modelRestartDialog: androidx.appcompat.app.AlertDialog? = null

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

        // 定时切换提醒弹窗预览（仅 debug 构建）：
        // adb shell am start -n com.codetool.app/.MainActivity --ez codetool_debug_model_dialog true
        if (isDebug && intent?.getBooleanExtra("codetool_debug_model_dialog", false) == true) {
            window.decorView.post {
                showModelRestartPrompt(
                    "已按高峰时段（北京时间 18:45）把 mimo 模型从「sensenova/deepseek-v4-flash」切换为「sensenova/glm-5.2」\n\n" +
                        "检测到 mimo 仍在运行：正在运行的会话仍使用旧模型。\n请关闭当前终端会话后重新打开，新模型才会生效。\n\n" +
                        "（调试预览：实际触发时显示真实切换信息）"
                )
            }
        }

        // 更新弹窗预览（仅 debug 构建，假数据不上网）：
        // adb shell am start -n com.codetool.app/.MainActivity --ez codetool_debug_update_dialog true
        if (isDebug && intent?.getBooleanExtra("codetool_debug_update_dialog", false) == true) {
            window.decorView.post { com.codetool.app.util.UpdateUi.previewDialog(this) }
        }

        // 发布新版本强制重走引导的开关（默认 0=关闭；见 AppSettings.FORCE_REONBOARD_AFTER_VERSION）。
        // 必须在下方「未同意协议 / 引导未完成」判断之前执行，清掉标记后本次启动自然重跑引导。
        com.codetool.app.util.AppSettings.maybeForceReOnboard(this)

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
                // 记录当前页到内存变量：仅进程存活期间有效
                // （返回桌面通知栏还在=进程没死=恢复；完全退出/杀进程=变量归零=回首页）
                lastPage = position
                // 键盘切换按钮仅终端页显示
                invalidateOptionsMenu()
                // 首页等非终端页收起输入法：offscreenPageLimit=全部会预创建 TerminalFragment，
                // 其 TerminalView.requestFocus() 会在冷启动时抢焦点弹键盘。
                hideImeUnlessTerminal(position)
            }
        })

        if (savedInstanceState == null) {
            // 冷启动：恢复到上次所在页面（仅进程存活期间有效，完全退出后 lastPage 归零回首页）
            val last = lastPage.coerceIn(0, fragments.size - 1)
            viewPager.currentItem = last
            supportActionBar?.title = titles[last]
        }

        // 首次启动强制用户协议：未同意当前版本 → 进全屏协议页（勾选 + 下一步）。
        // 放在 rootfs 自动解压等重操作之前，未同意不干活。RESULT_OK=同意；RESULT_CANCELED=不同意（退出）。
        // 注意：权限申请（通知/所有文件/电池）统一放到引导页之后，避免协议一同意就冒出系统权限弹窗。
        if (!UserAgreement.isAccepted(this)) {
            AgreementActivity.startFirstLaunch(this, REQUEST_AGREEMENT)
            return  // 未同意前不继续初始化（不自动解压 rootfs）；结果在 onActivityResult 处理
        }

        // 协议已同意 → 首次进入引导页（电池无限制 + 通知前台保活，两项授权后下一步）。
        if (!AppSettings.onboardingDone(this)) {
            OnboardingActivity.start(this, REQUEST_ONBOARD)
            return  // 引导未完成前不初始化；结果在 onActivityResult 处理
        }

        // 协议已同意 + 引导已完成 → 按 AI 环境状态继续（未就绪进第三引导页）
        maybeGoEnvSetupOrFully()
    }

    /** 协议 + 引导都完成后才走：申请权限 → 自动解压 rootfs。 */
    private fun onFullyOnboarded() {
        requestPermissionsIfNeeded()
        maybeAutoInstallRootfs()
    }

    /** 引导完成后的环境判断：AI 环境（python3/MCP 桥）未就绪 → 第三引导页，否则正常初始化。
     *  重置引导时置 forceEnvSetup 标记，会强制再进一次环境安装页（已装好也重看一遍）。 */
    private fun maybeGoEnvSetupOrFully() {
        val force = com.codetool.app.util.AppSettings.consumeForceEnvSetup(this)
        val envOk = com.codetool.app.ai.AiEnvHelper.check(this).allOk
        if (force || !envOk) {
            EnvSetupActivity.startForResult(this, REQUEST_ENV)
        } else {
            onFullyOnboarded()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_AGREEMENT -> {
                if (resultCode == RESULT_OK) {
                    // 已同意协议 → 进引导页（若未完成）；都完成则按环境状态继续
                    if (!AppSettings.onboardingDone(this)) {
                        OnboardingActivity.start(this, REQUEST_ONBOARD)
                    } else {
                        maybeGoEnvSetupOrFully()
                    }
                } else {
                    finishAffinity()  // 不同意协议：退出
                }
            }
            REQUEST_ONBOARD -> {
                if (resultCode == RESULT_OK) {
                    // 引导完成 → 第三引导页（AI 环境未就绪先装）或正常初始化
                    maybeGoEnvSetupOrFully()
                } else {
                    finishAffinity()  // 引导页返回：退出（未授权不进主页）
                }
            }
            REQUEST_ENV -> {
                if (resultCode == RESULT_OK) {
                    onFullyOnboarded()  // 环境装了（或用户跳过修复模式）：正常初始化
                } else {
                    finishAffinity()  // 第三引导页返回：退出
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
        hideImeRunnable1 = Runnable { if (currentPage != 1) hideImeUnlessTerminal(currentPage) }
        hideImeRunnable2 = Runnable { if (currentPage != 1) hideImeUnlessTerminal(currentPage) }
        window.decorView.postDelayed(hideImeRunnable1, 400)
        window.decorView.postDelayed(hideImeRunnable2, 1200)
        // 定时切换模型：回到前台兜底应用一次（休眠/进程被杀可能错过闹钟）。
        // 后台切换已由 AlarmManager 完成；若仍有 CLI 在运行 → 弹防误触弹窗告知重启终端。
        com.codetool.app.ai.ModelScheduleManager.runAndCheckPrompt(this)?.let {
            showModelRestartPrompt(it)
        }
        scheduleNextModelTick()
        // 合成器运行中悬浮按钮：回到首页时刷新（最小化后仍运行 → 显示；销毁 → 隐藏）。
        updateCompositorFab()
    }

    /**
     * 首页「合成器运行中」顶栏图标：合成器实例存活（前台或后台最小化未销毁）即显示，
     * 点击回合成器。instance 在 onCreate 赋值、onDestroy 置空，可靠反映运行态。
     * 刷新菜单以更新该图标的可见性。
     */
    private fun updateCompositorFab() {
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        scheduleHandler.removeCallbacksAndMessages(null)
        hideImeRunnable1?.let { window.decorView.removeCallbacks(it) }
        hideImeRunnable2?.let { window.decorView.removeCallbacks(it) }
    }

    /**
     * 前台边界自检：AlarmManager 负责后台触发，前台用 Handler 保证到点立刻弹窗
     * （系统闹钟在 MIUI 等 ROM 上可能被延迟/打断）。
     */
    private fun scheduleNextModelTick() {
        scheduleTickRunnable?.let { scheduleHandler.removeCallbacks(it) }
        val s = com.codetool.app.ai.ModelScheduleManager.load(this)
        if (!s.enabled) return
        val delay = com.codetool.app.ai.ModelScheduleManager.nextBoundaryMillis(s) ?: return
        val r = Runnable {
            com.codetool.app.ai.ModelScheduleManager.runAndCheckPrompt(this)?.let {
                showModelRestartPrompt(it)
            }
            scheduleNextModelTick()
        }
        scheduleTickRunnable = r
        scheduleHandler.postDelayed(r, delay + 1000)
    }

    /**
     * 定时切换提醒弹窗（只告知，不操作终端）：后台已完成模型切换，
     * 检测到 CLI 仍在运行时提醒用户手动重启终端。
     * setCancelable(false) + setCanceledOnTouchOutside(false)：点外部或返回键都不会消失，
     * 只有明确点「去终端 / 知道了」才关闭，避免误触把提示弄丢。
     */
    fun showModelRestartPrompt(text: String) {
        if (isFinishing || isDestroyed) return
        if (modelRestartDialog?.isShowing == true) return
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("模型已定时切换")
            .setMessage(text)
            .setPositiveButton("去终端") { _, _ -> viewPager.currentItem = 1 }
            .setNegativeButton("知道了", null)
            .setCancelable(false)
            .create()
        d.setCanceledOnTouchOutside(false)
        // 双保险：显式吞掉返回键，避免个别 ROM 把返回键透传给 Activity 导致弹窗/页面消失
        d.setOnKeyListener { _, keyCode, _ -> keyCode == android.view.KeyEvent.KEYCODE_BACK }
        d.show()
        modelRestartDialog = d
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
        // 说明（解释终端具体功能 + 底部按键作用；仅终端页可见）
        val keyHelp = menu.add(0, MENU_KEY_HELP, 3, "说明")
        keyHelp.setIcon(R.drawable.ic_info)
        keyHelp.icon?.mutate()?.setTint(iconColor)
        keyHelp.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        keyHelp.isVisible = (currentPage == 1)
        // GUI 显示（arlinux 风格桌面；仅终端页可见）
        val disp = menu.add(0, MENU_DISPLAY, 3, "显示")
        disp.setIcon(R.drawable.ic_display)
        disp.icon?.mutate()?.setTint(iconColor)
        disp.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        disp.isVisible = (currentPage == 1)
        // 合成器运行中：顶栏图标（仅首页且合成器运行时显示，点击回合成器）
        val compRun = menu.add(0, MENU_COMPOSITOR_RUNNING, 4, "合成器运行中")
        compRun.setIcon(R.drawable.ic_monitor)
        compRun.icon?.mutate()?.setTint(iconColor)
        compRun.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        compRun.isVisible =
            currentPage == 0 && com.codetool.app.gui.TawcCompositorActivity.instance != null
        // 文件页：文件管理设置（显示隐藏文件开关）——仅文件页显示
        val fileCfg = menu.add(0, MENU_FILE_SETTINGS, 1, "文件设置")
        fileCfg.setIcon(R.drawable.ic_settings)
        fileCfg.icon?.mutate()?.setTint(iconColor)
        fileCfg.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        fileCfg.isVisible = currentPage == 3
        // 日志页：复制 / 清空移到标题栏（仅日志页可见）
        val search = menu.add(0, MENU_SEARCH, 3, "搜索")
        search.setIcon(R.drawable.ic_search)
        search.icon?.mutate()?.setTint(iconColor)
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        search.isVisible = currentPage == 4
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
        // 日志页：日志设置（自动刷新开关）——从终端设置里挪过来
        val logCfg = menu.add(0, MENU_LOG_SETTINGS, 2, "日志设置")
        logCfg.setIcon(R.drawable.ic_settings)
        logCfg.icon?.mutate()?.setTint(iconColor)
        logCfg.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        logCfg.isVisible = currentPage == 4
        // 日志页：筛选（时间/级别/标签）——底部弹窗
        val logFilter = menu.add(0, MENU_LOG_FILTER, 6, "筛选")
        logFilter.setIcon(R.drawable.ic_filter)
        logFilter.icon?.mutate()?.setTint(iconColor)
        logFilter.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        logFilter.isVisible = currentPage == 4
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
        if (item.itemId == MENU_SEARCH) {
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.toggleSearch()
            return true
        }
        if (item.itemId == MENU_CLEAR) {
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.clearLog()
            return true
        }
        if (item.itemId == MENU_SETTINGS) {
            showTerminalSettingsSheet()
            return true
        }
        if (item.itemId == MENU_KEYBOARD) {
            TerminalFragment.instance?.toggleShortcutRow()
            return true
        }
        if (item.itemId == MENU_KEY_HELP) {
            showKeyHelpSheet()
            return true
        }
        if (item.itemId == MENU_COMPOSITOR_RUNNING) {
            // 顶栏「合成器运行中」：点击回合成器（singleTask 会把后台实例带回前台）。
            startActivity(Intent(this, com.codetool.app.gui.TawcCompositorActivity::class.java))
            return true
        }
        if (item.itemId == MENU_DISPLAY) {
            // 终端页「显示」直接进横屏合成器渲染，跳过中间状态页。
            startActivity(Intent(this, com.codetool.app.gui.TawcCompositorActivity::class.java))
            return true
        }
        if (item.itemId == MENU_FILE_SETTINGS) {
            showFileSettingsSheet()
            return true
        }
        if (item.itemId == MENU_LOG_SETTINGS) {
            showLogSettingsSheet()
            return true
        }
        if (item.itemId == MENU_LOG_FILTER) {
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.showFilterSheet()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 底部弹窗统一标题。 */
    private fun sheetTitle(container: android.widget.LinearLayout, text: String) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        container.addView(android.widget.TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        })
    }

    /** 底部弹窗里的小字说明（颜色跟随主题）。 */
    private fun sheetHint(container: android.widget.LinearLayout, text: String) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        container.addView(android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(MaterialColors.getColor(container,
                com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(2), 0, 0)
        })
    }

    /** 底部弹窗里的「左侧标题 + 右侧开关」行，改动即时回调。 */
    private fun addSwitchRow(
        container: android.widget.LinearLayout,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
        }, android.widget.LinearLayout.LayoutParams(
            0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = checked
        }
        sw.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.addView(sw)
        container.addView(row)
    }

    /** 底部弹窗里的「刷新间隔」：Material 滑动条（1~10 秒），改动即存并通知日志页。 */
    private fun addRefreshIntervalRow(container: android.widget.LinearLayout) {
        val current = AppSettings.logRefreshIntervalMs(this)
        val v = layoutInflater.inflate(R.layout.widget_log_refresh_slider, container, false)
        val value = v.findViewById<android.widget.TextView>(R.id.tvLogRefreshValue)
        val slider = v.findViewById<com.google.android.material.slider.Slider>(R.id.slLogRefresh)
        slider.value = (current / 1000).toFloat()
        value.text = "${current / 1000} 秒"
        slider.addOnChangeListener { _, newValue, _ ->
            val sec = newValue.toInt()
            val ms = sec * 1000
            value.text = "$sec 秒"
            AppSettings.setLogRefreshIntervalMs(this@MainActivity, ms)
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.setRefreshInterval(ms)
        }
        container.addView(v)
    }

    /** 文件页「文件设置」底部弹窗：显示隐藏文件开关（改动即时生效，状态持久化）。 */
    private fun showFileSettingsSheet() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val files = supportFragmentManager.fragments
            .filterIsInstance<FilesFragment>().firstOrNull()
        val sheet = BottomSheetDialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(32))
        }
        sheetTitle(container, "文件设置")
        addSwitchRow(container, "显示隐藏文件",
            files?.isShowHidden() ?: AppSettings.fileShowHidden(this)
        ) { enabled ->
            AppSettings.setFileShowHidden(this, enabled)
            files?.setShowHidden(enabled)
        }
        sheetHint(container, "以 . 开头的文件（如 .bashrc、.config、.local）默认不显示")
        sheet.setContentView(container)
        sheet.show()
    }

    /** 日志页「日志设置」底部弹窗：自动滚动开关（终端式，跟随最新日志）。 */
    private fun showLogSettingsSheet() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val sheet = BottomSheetDialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(32))
        }
        sheetTitle(container, "日志设置")
        addSwitchRow(container, "自动滚动", AppSettings.logAutoRefresh(this)) { enabled ->
            AppSettings.setLogAutoRefresh(this, enabled)
            supportFragmentManager.fragments.filterIsInstance<TasksFragment>().firstOrNull()
                ?.setAutoRefresh(enabled)
        }
        addRefreshIntervalRow(container)
        sheetHint(container, "开启自动滚动后按选定的间隔（1~10 秒）自动刷新并滚动到最新日志（终端式）")
        sheet.setContentView(container)
        sheet.show()
    }

    /** 终端「说明」弹窗：精简功能说明 + 第一页/第二页快捷键逐行列出（可滚动）。 */
    private fun showKeyHelpSheet() {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val keyTypeface = android.graphics.Typeface.MONOSPACE
        // attr 属性 ID 不能直接 getColor()（会 Resources$NotFoundException），须经 MaterialColors 解析。
        val primaryColor = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, 0xFF00505A.toInt())

        fun sectionTitle(text: String) = android.widget.TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(primaryColor)
            setPadding(0, dp(12), 0, dp(4))
        }
        // 每行：等宽快捷键列 + 作用说明；iconRes!=0 时快捷键列显示图标（大小与文字行一致）。
        fun rowLine(key: String, desc: String, iconRes: Int = 0) = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            if (iconRes != 0) {
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                    // FIT_START：图标贴列左端，与其它行文字左对齐（FIT_CENTER 会在 120dp 列内居中偏右）
                    scaleType = android.widget.ImageView.ScaleType.FIT_START
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dp(120), dp(20))
                })
            } else {
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = key
                    textSize = 13f
                    typeface = keyTypeface
                    setTextColor(primaryColor)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        dp(120), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                })
            }
            addView(android.widget.TextView(this@MainActivity).apply {
                text = desc
                textSize = 13f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        val intro = android.widget.TextView(this).apply {
            text = "第一页 = 光标/编辑常用键；第二页 = AI 工具（opencode / mimo）命令面板。\n" +
                "修饰键 CTRL/ALT/SHIFT 粘性：点一次带修饰；方向键 / BCK 长按连续；长按 AI 键显示提示。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }

        val p1 = listOf(
            Triple("ESC", "取消 / 退出", 0), Triple("TAB", "补全", 0), Triple("ALT", "Alt 修饰", 0),
            Triple("BCK", "退格（长按连续删除）", 0), Triple("↑↓←→", "移动光标（长按连续）", 0),
            Triple("", "执行命令", R.drawable.ic_key_enter),
            Triple("HOME", "行首", 0), Triple("END", "行尾", 0), Triple("PGUP", "向上翻页", 0),
            Triple("PGDN", "向下翻页", 0),
            Triple("CTRL", "Ctrl 修饰", 0), Triple("SHIFT", "Shift 修饰", 0), Triple("/", "输入 /", 0),
        )
        val p2 = listOf(
            "Ctrl+X 然后 M" to "切换模型", "Ctrl+X 然后 L" to "切换会话",
            "Ctrl+X 然后 N" to "新建会话", "Ctrl+X 然后 ↵" to "排队发送提示（稍后处理）",
            "Ctrl+X 然后 U" to "撤销上一条消息", "Ctrl+X 然后 Q" to "退出",
            "Ctrl+X 然后 A" to "选择 Agent", "Ctrl+X 然后 B" to "切换侧边栏",
            "Ctrl+X 然后 G" to "跳转到消息", "Ctrl+X 然后 X" to "导出会话到编辑器",
            "Ctrl+X 然后 W" to "关闭当前标签页", "^P" to "打开设置", "^O" to "最近会话 + 项目一起开",
            "^R" to "重命名当前会话",
        )

        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        col.addView(intro)
        col.addView(sectionTitle("【第一页】"))
        for ((k, v, i) in p1) col.addView(rowLine(k, v, i))
        col.addView(sectionTitle("【第二页】AI 工具命令面板"))
        for ((k, v) in p2) col.addView(rowLine(k, v))

        val scroll = android.widget.ScrollView(this).apply {
            addView(col)
            // 限制最大高度，避免超出屏幕；内容多可滚动
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.7f).toInt())
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("说明")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
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

        // ---- 12 色网格（每行 4 个）：青/琥珀/翠绿/湖蓝/紫罗兰/玫红/橙/蓝灰/青柠/洋红/正红/靛蓝 ----
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

    /** 终端设置底部弹窗：字体大小 + 容器挂载目录（日志设置已挪到日志页）。 */
    private fun showTerminalSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(28), dp(32))
        }
        sheetTitle(container, "终端设置")

        // ---- 底部按键振动反馈 ----
        val vibRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(0))
        }
        vibRow.addView(android.widget.TextView(this).apply {
            text = "按键振动"
            textSize = 16f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        // 强度滑条在开关之后创建，但开关的 listener 要控制其可见性：用 lateinit 先声明。
        lateinit var vibStrengthLabel: android.widget.TextView
        lateinit var vibStrengthSlider: com.google.android.material.slider.Slider
        lateinit var vibStrengthValue: android.widget.TextView
        val vibSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            isChecked = AppSettings.terminalVibration(this@MainActivity)
        }
        vibSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setTerminalVibration(this@MainActivity, checked)
            val vis = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            vibStrengthLabel.visibility = vis
            vibStrengthSlider.visibility = vis
            vibStrengthValue.visibility = vis
        }
        vibRow.addView(vibSwitch)
        container.addView(vibRow)
        container.addView(android.widget.TextView(this).apply {
            text = "按终端底部按键时的振动反馈（长按方向键连续振动）"
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, dp(4), 0, dp(0))
        })

        // ---- 振动强度（无极滑条，默认弱）----
        vibStrengthLabel = android.widget.TextView(this).apply {
            text = "振动强度"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(0))
        }
        container.addView(vibStrengthLabel)
        vibStrengthValue = android.widget.TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, dp(2), 0, dp(0))
        }
        container.addView(vibStrengthValue)
        vibStrengthSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = AppSettings.MIN_VIBRATION_PCT.toFloat()
            valueTo = AppSettings.MAX_VIBRATION_PCT.toFloat()
            stepSize = 1f
            value = AppSettings.vibrationStrengthPct(this@MainActivity).toFloat()
            val vis = if (AppSettings.terminalVibration(this@MainActivity))
                android.view.View.VISIBLE else android.view.View.GONE
            visibility = vis
        }
        val vibLp = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        vibLp.setMargins(0, dp(4), 0, dp(0))
        container.addView(vibStrengthSlider, vibLp)
        vibStrengthValue.text = "${AppSettings.vibrationStrengthPct(this@MainActivity)}%"
        // 拖动实时生效（强度即时应用到按键振动），并在拖动时实时振动预览便于对比强弱
        vibStrengthSlider.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            vibStrengthValue.text = "$pct%"
            AppSettings.setVibrationStrengthPct(this, pct)
            if (fromUser) TerminalFragment.instance?.previewVibration()
        }
        vibStrengthLabel.visibility = if (AppSettings.terminalVibration(this@MainActivity))
            android.view.View.VISIBLE else android.view.View.GONE

        val valueText = android.widget.TextView(this).apply {
            text = "${AppSettings.fontSize(this@MainActivity)}dp"
            textSize = 28f
        }
        container.addView(android.widget.TextView(this).apply {
            text = "终端字体大小"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(0))
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
        lp2.setMargins(0, dp(4), 0, dp(0))
        container.addView(slider, lp2)
        // 拖动实时生效（字号即时应用到终端）
        slider.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            valueText.text = "${size}dp"
            AppSettings.setFontSize(this, size)
            TerminalFragment.instance?.updateFontSize(size)
        }

        // ---- 容器挂载目录 ----
        container.addView(android.widget.TextView(this).apply {
            text = "容器挂载目录"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(4))
        })
        container.addView(android.widget.TextView(this).apply {
            text = "选择 Android 共享存储挂载进容器的范围（改动后需重启终端服务生效）"
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, 0, 0, dp(6))
        })
        val mountOptions = listOf(
            AppSettings.MountMode.DOWNLOAD to "仅 Download（推荐）",
            AppSettings.MountMode.FULL to "整个共享存储",
            AppSettings.MountMode.NONE to "不挂载",
        )
        // 用下拉框而不是 RadioGroup：动态 addView 的 RadioButton id 都为 0，
        // RadioGroup 互斥靠 id 判重会失效，出现"选两个都选中"的问题。
        val currentMount = AppSettings.mountMode(this)
        val mountSpinner = android.widget.Spinner(this).apply {
            val adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                mountOptions.map { it.second }
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(mountOptions.indexOfFirst { it.first == currentMount }.coerceAtLeast(0))
        }
        mountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?,
                                        position: Int, id: Long) {
                val mode = mountOptions.getOrNull(position)?.first ?: return
                if (mode != currentMount) AppSettings.setMountMode(this@MainActivity, mode)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(mountSpinner, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // ---- APT 换源（容器下载源镜像，默认清华） ----
        container.addView(android.widget.TextView(this).apply {
            text = "APT 换源"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(4))
        })
        container.addView(android.widget.TextView(this).apply {
            text = "容器 apt 镜像下载源（默认清华）"
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, 0, 0, dp(6))
        })
        val currentMirror = AptMirror.currentId(this)
        val mirrorSpinner = android.widget.Spinner(this).apply {
            val adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                AptMirror.ALL.map { it.label }
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter
            setSelection(AptMirror.ALL.indexOfFirst { it.id == currentMirror }.coerceAtLeast(0))
        }
        mirrorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?,
                                        position: Int, id: Long) {
                val mirror = AptMirror.ALL.getOrNull(position) ?: return
                if (mirror.id == currentMirror) return   // 未变不重复写
                AppSettings.setAptMirror(this@MainActivity, mirror.id)
                val ok = AptMirror.apply(this@MainActivity)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "已切换到 ${mirror.label}（下次 apt 生效）"
                    else "已记录选择 ${mirror.label}，容器未就绪",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        container.addView(mirrorSpinner)

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
