package com.codetool.app.util

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.codetool.app.R
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/**
 * 编辑器内联搜索/替换控制器：封装 sora-editor 的 [EditorSearcher]，供三个编辑页
 * （ScriptEdit / ConfigEditor / SkillEditor）复用。
 *
 * 职责（MT 管理器风格）：
 *  - 显示/隐藏搜索条（开关走调用方的标题栏搜索按钮 toggle）
 *  - 查找：输入即搜（高亮由 sora 负责）、上一个/下一个跳转、匹配计数
 *  - 替换：读「替换为」输入框，替换当前匹配（replaceCurrentMatch）/ 全部替换（replaceAll）
 *  - 关闭：搜索条内 × 图标 + 调用方标题栏按钮都能收起
 * 搜索条为底部通栏，键盘弹出时用 IME inset 把自己顶到键盘上方（不依赖 windowSoftInputMode）。
 * 不自己实现查找/替换算法——sora EditorSearcher 内置匹配 + 高亮 + 后台线程 + 替换，只触发它即可。
 *
 * 构造只传查找相关的 6 个视图；替换相关（replaceInput / searchReplace / searchReplaceAll）
 * 从 searchBar 内部 findViewById 查找，故新增替换功能不改动三个编辑页的调用签名。
 *
 * 线程：EditorSearcher.search/gotoNext/gotoPrevious/replace* 均可在主线程调（内部自管后台线程）。
 * 空 query 不能传给 search()（会抛 IllegalArgumentException），空时走 stopSearch 清高亮。
 * 计数是异步的：search() 后台算完才填 matchedPositionCount，无 listener 回调，故搜索后
 * post 一个延迟 Runnable 再读计数（见 scheduleCountRefresh），否则立即读必然是 0 → 误报「无结果」。
 */
class EditorSearchController(
    private val editor: CodeEditor,
    private val searchBar: View,
    private val searchInput: EditText,
    private val searchCount: TextView,
    searchPrev: View,
    searchNext: View,
    searchClose: View,
    private val onClosed: (() -> Unit)? = null,
) {
    /** 普通文本匹配（非正则、非全词）+ 大小写不敏感。够用且对脚本/配置最直观。 */
    private val options = EditorSearcher.SearchOptions(
        EditorSearcher.SearchOptions.TYPE_NORMAL, true
    )

    /** 替换相关视图：从 searchBar 内部查找，避免改动三个编辑页的构造调用。 */
    private val replaceInput: EditText =
        searchBar.findViewById(R.id.replaceInput)
    private val btnReplace: View? = searchBar.findViewById(R.id.searchReplace)
    private val btnReplaceAll: View? = searchBar.findViewById(R.id.searchReplaceAll)

    /** 替换输入框是否已展开（MT 风格：首次点替换/全部先展开输入框，二次才执行）。 */
    private var replaceRevealed = false

    var isOpen: Boolean = false
        private set

    /** 延迟刷新计数的 Runnable；新搜索会取消旧的，避免读到上一次的后台结果。 */
    private var countPending: Runnable? = null

    init {
        // 输入即搜：文本变化重新 search（sora 自带防抖式的后台匹配 + 高亮）
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                runSearch(s?.toString() ?: "")
            }
        })

        // 键盘 search 动作 = 下一个匹配（符合主流编辑器习惯）
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                gotoNext()
                true
            } else {
                false
            }
        }

        searchPrev.setOnClickListener { gotoPrevious() }
        searchNext.setOnClickListener { gotoNext() }
        // 关闭按钮（收在搜索条内）：关闭搜索条、清高亮。toggle 的关闭路径之一。
        searchClose.setOnClickListener { close() }

        // 替换（MT 风格二次点击）：首次点展开替换输入框，二次点执行替换当前 / 全部替换
        btnReplace?.setOnClickListener { onReplaceClick(replaceAll = false) }
        btnReplaceAll?.setOnClickListener { onReplaceClick(replaceAll = true) }

        setupImeInset()
    }

    /**
     * 替换按钮处理（二次点击）：替换输入框未展开时，首次点击只展开输入框并聚焦（让用户先填替换词）；
     * 已展开时执行替换（replaceAll=true 全部替换，否则替换当前匹配）。展开/执行二态切换。
     */
    private fun onReplaceClick(replaceAll: Boolean) {
        if ((searchInput.text?.toString() ?: "").isEmpty()) return
        if (!replaceRevealed) {
            // 首次点击：展开替换输入框，聚焦让用户直接输入替换词
            replaceRevealed = true
            replaceInput.visibility = View.VISIBLE
            replaceInput.requestFocus()
            return
        }
        val rep = replaceInput.text?.toString() ?: ""
        if (replaceAll) editor.searcher.replaceAll(rep) else editor.searcher.replaceCurrentMatch(rep)
        scheduleCountRefresh()
    }

    /** 显示搜索条并聚焦；已显示则聚焦回输入框。带 220ms 上滑展开动画。 */
    fun open() {
        if (isOpen) {
            searchInput.requestFocus()
            return
        }
        isOpen = true
        searchBar.visibility = View.VISIBLE
        searchBar.alpha = 0f
        searchBar.translationY = searchBar.height.toFloat().coerceAtLeast(1f)
        searchBar.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        searchInput.requestFocus()
        // 不强制弹软键盘（用户可能在用实体键盘/不想被打断）；需要时点输入框自然弹出
    }

    /** 隐藏搜索条、清高亮、清回调。带 220ms 下滑收起动画，结束后彻底 GONE（不留残条）。 */
    fun close() {
        if (!isOpen) return
        isOpen = false
        runSearch("")
        searchCount.text = ""
        searchBar.animate()
            .alpha(0f)
            .translationY(searchBar.height.toFloat().coerceAtLeast(1f))
            .setDuration(220L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                searchBar.visibility = View.GONE
                searchBar.translationY = 0f
                searchBar.alpha = 1f
            }
            .start()
        onClosed?.invoke()
    }

    /** 显示则关闭，隐藏则打开。 */
    fun toggle() {
        if (isOpen) close() else open()
    }

    /** 跳到下一个匹配并延迟刷新计数（跳转时初始搜索可能还没算完，用延迟读兜底）。 */
    fun gotoNext() {
        if ((searchInput.text?.toString() ?: "").isEmpty()) return
        editor.searcher.gotoNext()
        scheduleCountRefresh()
    }

    /** 跳到上一个匹配并延迟刷新计数。 */
    fun gotoPrevious() {
        if ((searchInput.text?.toString() ?: "").isEmpty()) return
        editor.searcher.gotoPrevious()
        scheduleCountRefresh()
    }

    /**
     * IME inset：键盘弹出时把搜索条内容顶到键盘上方，同时背景一直延伸到屏幕最底。
     * 用 bottomPadding 而非 bottomMargin：padding 让搜索条背景铺满到屏幕底边（盖住导航条/小白条区域），
     * 内容（输入框/按钮）被 padding 顶到导航条上方不被遮挡；margin 则会把整条上移、
     * 底部露出背后透明内容（小白条区域看不到背景色）。不依赖 windowSoftInputMode，跨版本一致。
     */
    private fun setupImeInset() {
        ViewCompat.setOnApplyWindowInsetsListener(searchBar) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // 键盘可见时内容顶到键盘上方；不可见时至少避开导航条/小白条。背景始终铺到屏幕最底。
            val bottom = maxOf(ime.bottom, nav.bottom)
            if (v.paddingBottom != bottom) {
                v.updatePadding(bottom = bottom)
            }
            insets
        }
    }

    /** 执行搜索：空 query 清高亮，非空触发 sora 匹配并「延迟」刷新计数（后台算完才准）。 */
    private fun runSearch(q: String) {
        // 新搜索 → 取消上一次的延迟刷新，避免读到旧 pattern 的后台结果
        countPending?.let { searchInput.removeCallbacks(it) }
        countPending = null
        if (q.isEmpty()) {
            editor.searcher.stopSearch()
            searchCount.text = ""
            return
        }
        editor.searcher.search(q, options)
        scheduleCountRefresh()
    }

    /**
     * 延迟刷新计数。sora 的 search() 是 fire-and-forget：后台 currentThread 跑完才填
     * matchedPositionCount，此刻立即读必然是 0（→ 误显示「无结果」）。没有 listener 回调，
     * 只能 post 一个延迟 Runnable 等后台算完再读；期间若又有新搜索，会被 runSearch 取消重排。
     * 150ms 对脚本/配置量级足够；超大文件极端情况可能仍偏早，但下一次跳转/输入会再刷新兜底。
     */
    private fun scheduleCountRefresh() {
        val r = Runnable {
            countPending = null
            updateCount()
        }
        countPending = r
        searchInput.postDelayed(r, 150)
    }

    /** 刷新「第 X / Y 处」计数。搜索在后台跑，计数是尽力而为（下一次操作会再刷新）。 */
    private fun updateCount() {
        val searcher = editor.searcher
        val total = searcher.matchedPositionCount
        if (total <= 0) {
            searchCount.text = "无结果"
            return
        }
        // sora 的 position index 从 0 起，展示成 1 基
        val cur = searcher.currentMatchedPositionIndex + 1
        searchCount.text = "第 $cur / $total 处"
    }
}
