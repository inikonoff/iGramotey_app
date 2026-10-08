package com.dictate.widget

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

/**
 * Сервис автовставки текста в активное поле ввода.
 * Активируется только по явному запросу из FloatingWidgetService через companion-метод.
 * Fallback: если поле не найдено — текст остаётся в буфере обмена.
 */
class PasteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "iGramotey.Paste"

        // Singleton-ссылка на живой экземпляр сервиса
        @Volatile
        private var instance: PasteAccessibilityService? = null

        /**
         * Вставить текст в активное поле.
         * Возвращает true если вставка прошла, false — fallback (буфер обмена уже заполнен).
         */
        fun pasteText(text: String): Boolean {
            val svc = instance ?: run {
                Log.w(TAG, "AccessibilityService not running — clipboard fallback")
                return false
            }
            return svc.doPaste(text)
        }

        fun isAvailable(): Boolean = instance != null

        /** Выделение в поле ввода: исходный текст и границы (без выделения — весь текст поля) */
        class Selection(val text: String, val fullText: String, val start: Int, val end: Int)

        fun getSelection(): Selection? = instance?.doGetSelection()

        /** Заменяет выделение; если поле изменилось за время обработки — false */
        fun replaceSelection(sel: Selection, newText: String): Boolean =
            instance?.doReplaceSelection(sel, newText) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "AccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Нам не нужны события — только вставка по запросу
    }

    /**
     * Ищет сфокусированный EditText и вставляет текст.
     * Стратегия поиска:
     *   1. Глобально сфокусированное поле (getFocus)
     *   2. Обход дерева accessibility в поисках редактируемого узла
     */
    private fun doPaste(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return false
        }

        // Сначала ищем глобально сфокусированный узел
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditableNode(root)

        if (focused == null) {
            Log.w(TAG, "No editable field found — clipboard fallback")
            root.recycle()
            return false
        }

        return try {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "ACTION_SET_TEXT result: $result on ${focused.className}")

            if (!result) {
                // Fallback: ACTION_PASTE (вставляет из буфера — он уже заполнен)
                val pasteResult = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "ACTION_PASTE fallback result: $pasteResult")
                pasteResult
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Paste error: ${e.message}")
            false
        } finally {
            focused.recycle()
            root.recycle()
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable } ?: findEditableNode(root)
        root.recycle()
        return node
    }

    private fun doGetSelection(): Selection? {
        val node = focusedEditable() ?: return null
        try {
            if (node.isPassword || node.isShowingHintText) return null
            val full = node.text?.toString().orEmpty()
            if (full.isBlank()) return null
            var start = node.textSelectionStart
            var end = node.textSelectionEnd
            if (start < 0 || end < 0 || start > full.length || end > full.length) {
                start = 0; end = full.length
            }
            if (start > end) { val t = start; start = end; end = t }
            if (start == end) { start = 0; end = full.length }   // нет выделения — весь текст поля
            val picked = full.substring(start, end)
            if (picked.isBlank()) return null
            return Selection(picked, full, start, end)
        } finally {
            node.recycle()
        }
    }

    private fun doReplaceSelection(sel: Selection, newText: String): Boolean {
        val node = focusedEditable() ?: return false
        return try {
            // Поле должно остаться тем же: текст не менялся, пока шла обработка
            if (node.text?.toString().orEmpty() != sel.fullText) return false
            val combined = sel.fullText.replaceRange(sel.start, sel.end, newText)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                // Курсор в конец вставленного фрагмента
                val sargs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, sel.start + newText.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, sel.start + newText.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sargs)
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "replaceSelection error: ${e.message}")
            false
        } finally {
            node.recycle()
        }
    }

    /**
     * Рекурсивный обход дерева — ищет редактируемый узел.
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocusable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}
