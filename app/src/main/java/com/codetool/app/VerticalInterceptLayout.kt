package com.codetool.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/**
 * 竖直滚动优先容器：包裹终端主体等「主要竖直滚动、但外层是水平 ViewPager2」的视图。
 *
 * 解决：主界面用 ViewPager2 左右切页，终端内容上下滑动时手势带一点水平分量，
 * 就会被外层 ViewPager2 抢去误切 tab。此容器在手指**先竖直滑动**时
 * `requestDisallowInterceptTouchEvent(true)` 禁止父级（主 ViewPager2）拦截，
 * 让终端滚动顺畅；若手指**水平先动**（|dx|>|dy|）则放行，仍可左右切页。
 *
 * 与 DisallowInterceptLayout（盲目禁止父拦截）的区别：这里按方向判定，
 * 只在竖直滚动时禁父，水平滑动仍走外层切页（不牺牲 ViewPager2 的左右滑）。
 */
class VerticalInterceptLayout @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var verticalLocked = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                verticalLocked = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!verticalLocked) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    // 竖直位移超过阈值且明显比水平大 → 判定为竖直滚动，禁父拦截
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        verticalLocked = true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> verticalLocked = false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun abs(v: Float) = if (v < 0) -v else v
}