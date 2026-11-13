package me.phie.tawc.compositor

import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * 对 InputMethodManager 的最小封装。
 *
 * 单独抽出来是为了让输入法相关调用集中在一处：显示/隐藏键盘、同步选区、
 * 在客户端内容类型变化时重启输入连接。
 */
interface ImeOutput {
    fun updateSelection(view: View, selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    fun showSoftInput(view: View)
    fun hideSoftInput(view: View)
    fun restartInput(view: View)
}

object RealImeOutput : ImeOutput {
    override fun updateSelection(
        view: View,
        selStart: Int,
        selEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        view.context.getSystemService(InputMethodManager::class.java)
            ?.updateSelection(view, selStart, selEnd, composingStart, composingEnd)
    }

    override fun showSoftInput(view: View) {
        view.context.getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun hideSoftInput(view: View) {
        view.context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun restartInput(view: View) {
        view.context.getSystemService(InputMethodManager::class.java)
            ?.restartInput(view)
    }
}
