package me.phie.tawc.compositor

import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute

/**
 * 把 Android 输入法（Gboard / 系统键盘）的事件桥接到 Wayland 的
 * zwp_text_input_v3：预编辑、提交、删除都转成容器里客户端能理解的输入。
 *
 * 移植自上游 tawc 的同名实现（行为已验证：Gboard、OpenBoard、AOSP 键盘）。
 * 关键点：
 *  - Wayland 的 preedit 是"叠加层"，不是文档内容，因此 setComposingRegion
 *    标记的区间在 Wayland 侧已经是提交文本，替换前必须先删掉；
 *  - 删除走真实 Backspace/Delete 键，因为很多客户端（终端、GTK 输入框）
 *    根本不实现 delete_surrounding_text；
 *  - 客户端会通过 set_surrounding_text 回推真实文本，用来纠正 Editable 镜像。
 */
class TawcInputConnection(private val targetView: View) : BaseInputConnection(targetView, true) {

    /** 当前 composing 区间的来源：true=我们自己的 preedit，false=客户端已提交文本。 */
    private var composingRegionIsPreedit: Boolean = false

    /** 客户端最近一次告诉我们的光标位置（UTF-16）。 */
    private var lastSyncedCursor: Int = 0

    /** 按我们的出站操作推算出来的客户端光标位置。 */
    private var wireCursor: Int = 0

    /** 当前 Wayland preedit 的 UTF-16 长度。 */
    private var activePreeditUtf16Length: Int = 0

    private data class ReplaceDeltas(val before: Int, val after: Int)

    private var pendingTrailingNewlineCommit: Boolean = false

    /**
     * 提交单字符前的拦截钩子（由 Activity 注入）。当快捷键条上按住了硬件修饰键
     * （CTRL/ALT）时，输入法提交的字母走的是文本路径（nativeCommitText），不会
     * 带上修饰键；这个钩子把该字符改走按键路径（emitModifiedKey），让终端收到
     * 真正的 Ctrl+C / Alt+X。返回 true 表示已消费（不再走普通文本提交）。
     */
    var onCommitCharacter: ((Char) -> Boolean)? = null

    init {
        NativeBridge.activeInputConnection = this
        com.codetool.app.util.FileLog.i(
            targetView.context, "TawcIME",
            "InputConnection created for view=${targetView.javaClass.simpleName}"
        )
    }

    override fun closeConnection() {
        if (NativeBridge.activeInputConnection === this) {
            NativeBridge.activeInputConnection = null
        }
        super.closeConnection()
    }

    internal fun targetsView(view: View): Boolean = targetView === view

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: return false
        // 硬件修饰键（快捷键条锁存的 CTRL/ALT）+ 输入法单字符：改走按键路径，
        // 让终端收到真正的 Ctrl+C / Alt+X。文本路径（nativeCommitText）不带修饰键，
        // 所以锁存的 CTRL 对输入法字母无效，必须在这里桥接到按键路径。
        if (str.length == 1) {
            com.codetool.app.util.CtrlComboLog.i(targetView.context, "commitText 单字符 '$str' composingPreedit=$composingRegionIsPreedit")
            if (!composingRegionIsPreedit && onCommitCharacter?.invoke(str[0]) == true) {
                com.codetool.app.util.CtrlComboLog.i(targetView.context, "commitText -> 已被拦截为组合键")
                return true
            }
        }
        if (!composingRegionIsPreedit) {
            val ed = editable
            if (ed != null) {
                val composingStart = BaseInputConnection.getComposingSpanStart(ed)
                val composingEnd = BaseInputConnection.getComposingSpanEnd(ed)
                if (composingStart >= 0 && composingEnd > composingStart &&
                    str == ed.subSequence(composingStart, composingEnd).toString()
                ) {
                    BaseInputConnection.removeComposingSpans(ed)
                    composingRegionIsPreedit = false
                    return true
                }
            }
        }
        if (newCursorPosition != 1) return false
        val deltas = computeReplaceDeltas() ?: return false
        val (before, after) = deltas
        super.commitText(text, newCursorPosition)
        com.codetool.app.util.FileLog.i(
            targetView.context, "TawcIME",
            "commitText \"$str\" before=$before after=$after composingPreedit=$composingRegionIsPreedit"
        )
        // 诊断：commitText 时读合成器内部状态，看焦点是否在客户端上。
        // focused_surface 没直接暴露，用 pointer_focus + toplevels 间接判断。
        runCatching {
            val snap = NativeBridge.nativeQueryState()
            if (snap != null) {
                val fields = snap.split(" ").filter {
                    it.startsWith("pointer_focus") || it.startsWith("pointer_x") ||
                        it.startsWith("pointer_y") || it.startsWith("toplevels=") ||
                        it.startsWith("hosts=")
                }.joinToString(" ")
                com.codetool.app.util.FileLog.i(
                    targetView.context, "TawcIME", "state[commit] $fields"
                )
            }
        }
        NativeBridge.nativeCommitText(str, before, after)
        wireCursor = (wireCursor - before).coerceAtLeast(0) + str.length
        activePreeditUtf16Length = 0
        pendingTrailingNewlineCommit = str.endsWith("\n")
        composingRegionIsPreedit = false
        return true
    }

    override fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = commitText(text, newCursorPosition)

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        val str = text?.text?.toString() ?: return false
        return commitText(str, 1)
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        val info = correctionInfo ?: return false
        val offset = info.offset
        val oldText = info.oldText?.toString() ?: return false
        val newText = info.newText?.toString() ?: return false
        if (offset < 0) return false
        val ed = editable ?: return false
        if (offset + oldText.length > ed.length) return false
        if (ed.subSequence(offset, offset + oldText.length).toString() != oldText) return false
        return replaceText(offset, offset + oldText.length, newText, 1, null)
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        false

    override fun replaceText(
        start: Int,
        end: Int,
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean {
        val str = text.toString()
        val ed = editable ?: return false
        if (start < 0 || end < 0 || start > end || start > ed.length || end > ed.length) return false
        val cursor = wireCursor.coerceIn(0, ed.length)
        if (cursor < start || cursor > end) return false
        val before = cursor - start
        val after = end - cursor
        if (!super.replaceText(start, end, text, newCursorPosition, textAttribute)) return false
        NativeBridge.nativeCommitText(str, before, after)
        wireCursor = (wireCursor - before).coerceAtLeast(0) + str.length
        activePreeditUtf16Length = 0
        pendingTrailingNewlineCommit = str.endsWith("\n")
        composingRegionIsPreedit = false
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val str = text?.toString() ?: ""
        if (newCursorPosition != 1) return false
        // 快捷键条锁存 CTRL/ALT 时，Gboard 等输入法打字母通常先走预编辑
        // （setComposingText）而不是 commitText，必须在这里也拦截，否则组合键失效。
        if (str.length == 1) {
            com.codetool.app.util.FileLog.i(targetView.context, "CtrlCombo", "setComposingText 单字符 '$str' composingPreedit=$composingRegionIsPreedit")
            if (!composingRegionIsPreedit && onCommitCharacter?.invoke(str[0]) == true) {
                com.codetool.app.util.FileLog.i(targetView.context, "CtrlCombo", "setComposingText -> 已被拦截为组合键")
                if (activePreeditUtf16Length > 0) {
                    NativeBridge.nativeFinishComposingText()
                    activePreeditUtf16Length = 0
                }
                pendingTrailingNewlineCommit = false
                return true
            }
        }
        val deltas = computeReplaceDeltas() ?: return false
        val (before, after) = deltas
        super.setComposingText(text, newCursorPosition)
        com.codetool.app.util.FileLog.i(
            targetView.context, "TawcIME",
            "setComposingText \"$str\" before=$before after=$after"
        )
        NativeBridge.nativeSetComposingText(str, before, after)
        wireCursor = (wireCursor - before).coerceAtLeast(0)
        activePreeditUtf16Length = str.length
        pendingTrailingNewlineCommit = false
        composingRegionIsPreedit = true
        return true
    }

    override fun setComposingText(
        text: CharSequence,
        newCursorPosition: Int,
        textAttribute: TextAttribute?,
    ): Boolean = setComposingText(text, newCursorPosition)

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        super.setComposingRegion(start, end)
        composingRegionIsPreedit = false
        return true
    }

    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean =
        setComposingRegion(start, end)

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        NativeBridge.nativeFinishComposingText()
        wireCursor += activePreeditUtf16Length
        activePreeditUtf16Length = 0
        pendingTrailingNewlineCommit = false
        composingRegionIsPreedit = false
        return true
    }

    private data class DeleteKeyPlan(
        val beforeKeys: Int,
        val afterKeys: Int,
        val deletedBeforeUnits: Int,
    )

    private fun unitsToKeyPlan(beforeUnits: Int, afterUnits: Int): DeleteKeyPlan {
        if (beforeUnits == 0 && afterUnits == 0) return DeleteKeyPlan(0, 0, 0)
        val ed = editable
        if (ed == null || ed.isEmpty()) return DeleteKeyPlan(beforeUnits, afterUnits, beforeUnits)
        val cursor = wireCursor.coerceIn(0, ed.length)
        val beforeStart = (cursor - beforeUnits).coerceAtLeast(0)
        val afterEnd = (cursor + afterUnits).coerceAtMost(ed.length)
        return DeleteKeyPlan(
            Character.codePointCount(ed, beforeStart, cursor),
            Character.codePointCount(ed, cursor, afterEnd),
            cursor - beforeStart,
        )
    }

    /** 发 before 个 Backspace + after 个 Delete 键；终端类客户端只认这个。 */
    private fun emitKeys(before: Int, after: Int) {
        repeat(before) { NativeBridge.nativeSendKeyEvent(KeyEvent.KEYCODE_DEL) }
        repeat(after) { NativeBridge.nativeSendKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL) }
    }

    private fun emitModifiedKey(event: KeyEvent) {
        val modifiers = mutableListOf<Int>()
        if (event.isCtrlPressed &&
            event.keyCode != KeyEvent.KEYCODE_CTRL_LEFT && event.keyCode != KeyEvent.KEYCODE_CTRL_RIGHT
        ) modifiers += KeyEvent.KEYCODE_CTRL_LEFT
        if (event.isAltPressed &&
            event.keyCode != KeyEvent.KEYCODE_ALT_LEFT && event.keyCode != KeyEvent.KEYCODE_ALT_RIGHT
        ) modifiers += KeyEvent.KEYCODE_ALT_LEFT
        if (event.isShiftPressed &&
            event.keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && event.keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
        ) modifiers += KeyEvent.KEYCODE_SHIFT_LEFT

        modifiers.forEach { NativeBridge.nativeSendKeyState(it, true) }
        NativeBridge.nativeSendKeyEvent(event.keyCode)
        modifiers.asReversed().forEach { NativeBridge.nativeSendKeyState(it, false) }
    }

    private fun computeReplaceDeltas(): ReplaceDeltas? {
        if (composingRegionIsPreedit) return ReplaceDeltas(0, 0)
        val ed = editable ?: return ReplaceDeltas(0, 0)
        val start = BaseInputConnection.getComposingSpanStart(ed)
        val end = BaseInputConnection.getComposingSpanEnd(ed)
        if (start < 0 || end < 0 || start >= end) return ReplaceDeltas(0, 0)
        val cursor = Selection.getSelectionStart(ed).coerceAtLeast(0)
        if (cursor < start || cursor > end) return null
        if (cursor != lastSyncedCursor || cursor != wireCursor) return null
        return ReplaceDeltas(cursor - start, end - cursor)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val keys = unitsToKeyPlan(beforeLength, afterLength)
        super.deleteSurroundingText(beforeLength, afterLength)
        emitKeys(keys.beforeKeys, keys.afterKeys)
        wireCursor = (wireCursor - keys.deletedBeforeUnits).coerceAtLeast(0)
        pendingTrailingNewlineCommit = false
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val ed = editable ?: return false
        val cursor = wireCursor.coerceIn(0, ed.length)
        val before16 = utf16FromCodePoints(ed, cursor, -beforeLength)
        val after16 = utf16FromCodePoints(ed, cursor, afterLength)
        return deleteSurroundingText(before16, after16)
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        NativeBridge.nativeSendKeyEvent(KeyEvent.KEYCODE_ENTER)
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun performSpellCheck(): Boolean = false

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) {
            emitModifiedKey(event)
        } else {
            NativeBridge.nativeSendKeyEvent(event.keyCode)
        }
        return true
    }

    override fun setImeConsumesInput(imeConsumesInput: Boolean): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean {
        val ed = editable ?: return false
        if (start < 0 || end < 0 || start > ed.length || end > ed.length) return false
        val curStart = Selection.getSelectionStart(ed)
        val curEnd = Selection.getSelectionEnd(ed)
        if (start != curStart || end != curEnd) return false
        return true
    }

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = false
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        super.getExtractedText(request, flags)

    /**
     * 客户端回推权威文本/选区，用来纠正 Editable 镜像。
     *
     * 组合过程中（activePreeditUtf16Length > 0）的普通回显直接忽略：那只是
     * 客户端在回显我们刚发的 preedit，照抄会把输入法的组合状态清掉，
     * 导致"每敲一个字母就被提交一次"。
     */
    fun updateFromCompositor(text: String, selStart: Int, selEnd: Int, authoritative: Boolean) {
        val ed = editable ?: return
        if (!authoritative && activePreeditUtf16Length > 0) return
        val newSelStart = selStart.coerceIn(0, text.length)
        val newSelEnd = selEnd.coerceIn(0, text.length)
        val curText = ed.toString()
        val curSelStart = Selection.getSelectionStart(ed)
        val curSelEnd = Selection.getSelectionEnd(ed)

        BaseInputConnection.removeComposingSpans(ed)
        composingRegionIsPreedit = false
        activePreeditUtf16Length = 0
        val preserveWireCursor = pendingTrailingNewlineCommit &&
            shouldPreserveWireCursorForTrailingNewlineReport(text, newSelStart)
        pendingTrailingNewlineCommit = false
        lastSyncedCursor = newSelStart
        if (!preserveWireCursor) wireCursor = newSelStart

        if (curText != text) ed.replace(0, ed.length, text)
        if (curSelStart != newSelStart || curSelEnd != newSelEnd) {
            Selection.setSelection(ed, newSelStart, newSelEnd)
        }
        NativeBridge.imeOutput.updateSelection(targetView, newSelStart, newSelEnd, -1, -1)
    }

    private fun utf16FromCodePoints(ed: Editable, cursor: Int, codePointDelta: Int): Int {
        if (codePointDelta == 0) return 0
        val s = ed.toString()
        var idx = cursor
        if (codePointDelta > 0) {
            var remaining = codePointDelta
            while (remaining > 0 && idx < s.length) {
                idx += Character.charCount(s.codePointAt(idx))
                remaining--
            }
            return idx - cursor
        }
        var remaining = -codePointDelta
        while (remaining > 0 && idx > 0) {
            idx -= Character.charCount(s.codePointBefore(idx))
            remaining--
        }
        return cursor - idx
    }

    private fun shouldPreserveWireCursorForTrailingNewlineReport(text: String, reportedCursor: Int): Boolean {
        if (wireCursor <= reportedCursor || wireCursor > text.length) return false
        for (i in reportedCursor until wireCursor) {
            if (text[i] != '\n') return false
        }
        return true
    }
}
