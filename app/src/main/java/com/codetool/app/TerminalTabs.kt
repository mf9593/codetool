package com.codetool.app

import com.codetool.app.terminal.TerminalSessionManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * 终端标签页注册表。
 *
 * 会话（bash 进程）与 TerminalFragment 生命周期解耦：
 * - 切换底部导航页面、屏幕旋转、视图重建都不影响会话运行（后台保持输出）；
 * - 每个标签一个独立 TerminalSessionManager + TerminalSession；
 * - 视图可随 Fragment 销毁并重建，重建后重新 attach 到同一个会话。
 */
object TerminalTabs {

    class Tab(
        val manager: TerminalSessionManager,
        val session: TerminalSession,
        var title: String
    ) {
        /** 当前挂接的 TerminalView（视图销毁时置空，重建时再挂）。 */
        var view: TerminalView? = null
    }

    /** 标签列表（顺序即显示顺序）。 */
    val tabs = mutableListOf<Tab>()

    /** 当前激活标签下标。 */
    @Volatile
    var active: Int = 0

    /** 标签编号只增不减，关闭后新建不会重名。 */
    private var seq = 0

    fun nextTitle(): String = "终端 ${++seq}"

    fun activeTab(): Tab? = tabs.getOrNull(active)

    fun activeSession(): TerminalSession? = activeTab()?.session

    fun isAlive(s: TerminalSession?): Boolean = s != null && s.isRunning
}
