package com.dictate.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Пункт «iGramotey» в меню выделения текста (ACTION_PROCESS_TEXT).
 * Выделенный текст приходит прямо в intent — буфер обмена не нужен.
 *  - поле редактируемое: результат возвращается в приложение и заменяет выделение;
 *  - текст только для чтения: результат кладётся в буфер обмена.
 * Прозрачная activity; касание экрана во время обработки отменяет её.
 */
class ProcessTextActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Нет выделенного текста", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "⏳ iGramotey обрабатывает…", Toast.LENGTH_SHORT).show()

        scope.launch {
            val result = withContext(Dispatchers.IO) { ApiClient().processText(text) }
            when (result) {
                is ApiResult.Success -> {
                    if (readOnly) {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("igramotey", result.text))
                        Toast.makeText(this@ProcessTextActivity, "📋 Готово — текст в буфере обмена", Toast.LENGTH_LONG).show()
                    } else {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result.text)
                        )
                    }
                }
                is ApiResult.Error ->
                    Toast.makeText(this@ProcessTextActivity, result.message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
