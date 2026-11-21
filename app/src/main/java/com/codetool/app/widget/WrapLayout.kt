package com.codetool.app.widget

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * 横向自动换行容器：按钮一行放不下时换到下一行。
 *
 * 只做最简单的流式排版（左到右、从上到下），支持子 View 的 margin。
 * 用于编辑器标题栏的操作按钮，避免窄屏 / 大字体时把标题挤没或触发 overflow。
 */
class WrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) {
            MAX_CHILD_WIDTH
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        var rowWidth = 0
        var rowHeight = 0
        var contentWidth = 0
        var contentHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (rowWidth > 0 && rowWidth + childWidth > available) {
                contentWidth = maxOf(contentWidth, rowWidth)
                contentHeight += rowHeight
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
        contentWidth = maxOf(contentWidth, rowWidth)
        contentHeight += rowHeight

        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingLeft && x + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }
            val left = x + lp.leftMargin
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
    }

    private companion object {
        const val MAX_CHILD_WIDTH = (1 shl 30) - 1
    }
}
