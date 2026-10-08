package com.dictate.widget

import android.content.ClipboardManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Невидимая activity: Android 10+ отдаёт содержимое буфера обмена только приложению
 * с фокусом. Получаем фокус на долю секунды, читаем буфер и передаём текст виджету.
 */
class ClipboardReadActivity : AppCompatActivity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true

        val text = try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        }

        finish()
        overridePendingTransition(0, 0)
        FloatingWidgetService.instance?.onClipboardText(text)
    }
}
