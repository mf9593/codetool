package com.codetool.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 包裹快捷键子 ViewPager2 的容器：down 时禁止父级（主 ViewPager2）拦截触摸，
 * 解决同方向嵌套 ViewPager2 的水平滑动冲突——面板上左右滑动只切换面板，
 * 不会误切主页面（终端/工具）。
 */
class DisallowInterceptLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
    }
}
