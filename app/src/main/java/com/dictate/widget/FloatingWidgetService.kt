package com.dictate.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingWidgetService : Service() {

    enum class WidgetState { IDLE, RECORDING, PROCESSING, DONE }

    data class StateAppearance(val emoji: String, val textSize: Float)
    private val stateAppearance = mapOf(
        WidgetState.IDLE       to StateAppearance("🎤", 40f),
        WidgetState.RECORDING  to StateAppearance("🎤", 48f),
        WidgetState.PROCESSING to StateAppearance("⏳", 40f),
        WidgetState.DONE       to StateAppearance("✅", 40f)
    )

    private lateinit var windowManager: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var widgetText: TextView
    private lateinit var clipboardBtn: TextView   // иконка 📋, появляется при копировании
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val audioRecorder = AudioRecorder()
    private val apiClient = ApiClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var currentState = WidgetState.IDLE

    // Touch (для микрофона)
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    // Порог сдвига в dp, а не в пикселях: 18 px на плотных экранах — это ~6 dp,
    // дрожание пальца при удержании считалось перетаскиванием
    private val dragThreshold by lazy { 12f * resources.displayMetrics.density }

    // Запись стартует не на ACTION_DOWN, а после короткого удержания — иначе
    // каждое перетаскивание сначала запускало MediaRecorder (синхронно, на UI-потоке)
    // и тут же его останавливало, отсюда рывок в начале движения
    private val handler = Handler(Looper.getMainLooper())
    private val HOLD_DELAY_MS = 180L
    private val startRecordingRunnable = Runnable {
        if (currentState == WidgetState.IDLE && !isDragging) startRecording()
    }

    // Двойной тап для выхода
    private var lastTapTime = 0L
    private val DOUBLE_TAP_MS = 400L

    // Буфер обмена
    private lateinit var clipboardManager: ClipboardManager
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // SharedPreferences — позиция
    private val WIDGET_SIZE_DP = 64
    private val PREFS_NAME = "igramotey_prefs"
    private val PREF_X = "widget_x"
    private val PREF_Y = "widget_y"

    companion object {
        private const val CHANNEL_ID = "dictate_widget_channel"
        private const val NOTIF_ID = 1
        private const val TAG = "iGramotey"

        @Volatile
        var isRunning = false

        @Volatile
        var instance: FloatingWidgetService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            startForegroundNotification()
            createWidget()
            registerClipboardListener()
            isRunning = true
            instance = this
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        savePosition()
        unregisterClipboardListener()
        handler.removeCallbacks(startRecordingRunnable)
        scope.cancel()
        audioRecorder.release()
        try {
            if (::container.isInitialized) windowManager.removeView(container)
        } catch (e: Exception) {
            Log.e(TAG, "removeView: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "iGramotey", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Плавающий виджет диктовки"
                setShowBadge(false)
            }
        )
        startForeground(NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🎤 iGramotey")
                .setContentText("Зажми виджет и говори")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        )
    }

    // ── Widget ────────────────────────────────────────────────────────────

    private fun createWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Микрофон — основная иконка
        widgetText = TextView(this).apply {
            text = stateAppearance[WidgetState.IDLE]!!.emoji
            textSize = stateAppearance[WidgetState.IDLE]!!.textSize
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            // Круглая подложка фиксированного размера: иконка заметна на любом фоне
            minWidth = dp(WIDGET_SIZE_DP)
            minHeight = dp(WIDGET_SIZE_DP)
            background = backdrop(WidgetState.IDLE)
        }

        // Буфер обмена — вторая иконка, скрыта по умолчанию
        clipboardBtn = TextView(this).apply {
            text = "📋"
            textSize = 32f
            gravity = Gravity.CENTER
            setPadding(14, 14, 14, 14)
            visibility = View.GONE
            minWidth = dp(WIDGET_SIZE_DP)
            minHeight = dp(WIDGET_SIZE_DP)
            background = backdrop(WidgetState.IDLE)
        }

        // Контейнер — горизонтальный, микрофон + (опционально) буфер
        container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(widgetText)
            addView(clipboardBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) })
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // Без этого флага оверлей из сервиса рисуется программно (CPU) —
                    // цветной эмодзи перерисовывался медленно при каждом сдвиге
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 50)
            y = prefs.getInt(PREF_Y, 300)
            alpha = 0.92f
        }

        // Тач только на микрофоне — запись и перетаскивание
        widgetText.setOnTouchListener { _, event -> handleTouch(event) }

        // Тач на 📋 — обработать буфер
        clipboardBtn.setOnClickListener { processClipboardText() }

        windowManager.addView(container, layoutParams)
    }

    private fun savePosition() {
        if (::layoutParams.isInitialized) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_X, layoutParams.x)
                .putInt(PREF_Y, layoutParams.y)
                .apply()
        }
    }

    // ── CLIPBOARD MONITORING ──────────────────────────────────────────────

    private fun registerClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Android 10+ не отдаёт содержимое буфера приложению без фокуса, а у оверлея
        // его нет (FLAG_NOT_FOCUSABLE). Поэтому здесь читаем только описание клипа
        // (оно доступно всегда), а сам текст забираем через ClipboardReadActivity по нажатию на 📋.
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val desc = clipboardManager.primaryClipDescription
                    ?: return@OnPrimaryClipChangedListener
                // Свою запись (после обработки) не показываем как новую
                if (desc.label?.toString() == "igramotey") {
                    Log.d(TAG, "Ignoring own clipboard write")
                    return@OnPrimaryClipChangedListener
                }
                if (!desc.hasMimeType("text/*")) {
                    hideClipboardIcon()
                    return@OnPrimaryClipChangedListener
                }
                Log.d(TAG, "Clipboard changed (text)")
                showClipboardIcon()
            } catch (e: Exception) {
                Log.w(TAG, "Clipboard listener error: ${e.message}")
            }
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        Log.d(TAG, "Clipboard listener registered")
    }

    private fun unregisterClipboardListener() {
        try {
            clipboardListener?.let {
                clipboardManager.removePrimaryClipChangedListener(it)
            }
        } catch (_: Exception) {}
        clipboardListener = null
    }

    private fun showClipboardIcon() {
        if (clipboardBtn.visibility == View.VISIBLE) return
        clipboardBtn.visibility = View.VISIBLE
        clipboardBtn.alpha = 0f
        clipboardBtn.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideClipboardIcon() {
        if (clipboardBtn.visibility != View.VISIBLE) return
        clipboardBtn.animate().alpha(0f).setDuration(200).withEndAction {
            clipboardBtn.visibility = View.GONE
        }.start()
    }

    /** Нажатие на 📋: прозрачная activity получает фокус и читает буфер */
    private fun processClipboardText() {
        if (currentState != WidgetState.IDLE) {
            Toast.makeText(this, "Подожди, идёт обработка", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(this, ClipboardReadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start ClipboardReadActivity: ${e.message}")
            Toast.makeText(this, "Не удалось прочитать буфер", Toast.LENGTH_SHORT).show()
        }
    }

    /** Вызывается из ClipboardReadActivity с текстом из буфера (null — буфер недоступен) */
    fun onClipboardText(raw: String?) {
        val text = raw?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show()
            hideClipboardIcon()
            return
        }

        setState(WidgetState.PROCESSING)
        vibrate(40)
        hideClipboardIcon()

        scope.launch {
            // Дать фокусу вернуться в приложение, куда будет вставка
            delay(300)
            val result = withContext(Dispatchers.IO) {
                apiClient.processText(text)
            }
            when (result) {
                is ApiResult.Success -> {
                    copyToClipboard(result.text)
                    val pasted = PasteAccessibilityService.pasteText(result.text)
                    if (!pasted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(this@FloatingWidgetService, "📋 Скопировано!", Toast.LENGTH_SHORT).show()
                    }
                    setState(WidgetState.DONE)
                    vibrate(80)
                    delay(3000)
                    if (currentState == WidgetState.DONE) setState(WidgetState.IDLE)
                }
                is ApiResult.Error -> {
                    Toast.makeText(this@FloatingWidgetService, result.message, Toast.LENGTH_LONG).show()
                    setState(WidgetState.IDLE)
                }
            }
        }
    }

    // ── Touch (микрофон) ──────────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false

                val now = System.currentTimeMillis()
                when (currentState) {
                    WidgetState.IDLE -> {
                        // Двойной тап = выход
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            Log.d(TAG, "Double tap — stopping")
                            Toast.makeText(this, "iGramotey выключен", Toast.LENGTH_SHORT).show()
                            stopSelf()
                            return true
                        }
                        lastTapTime = now
                        // Зажал — начинаем запись, если палец не двинулся за HOLD_DELAY_MS
                        handler.postDelayed(startRecordingRunnable, HOLD_DELAY_MS)
                    }
                    else -> { /* ничего */ }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging &&
                    (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)) {
                    isDragging = true
                    handler.removeCallbacks(startRecordingRunnable)
                    if (currentState == WidgetState.RECORDING) cancelRecording()
                }
                if (isDragging) {
                    layoutParams.x = (initialX + dx).toInt()
                    layoutParams.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(container, layoutParams)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(startRecordingRunnable)
                if (isDragging) {
                    savePosition()
                } else if (currentState == WidgetState.RECORDING) {
                    // Отпустил палец — стоп и обработка
                    stopRecordingAndProcess()
                }
                isDragging = false
            }
        }
        return true
    }

    // ── Recording ─────────────────────────────────────────────────────────

    private fun startRecording() {
        val started = audioRecorder.start(this)
        if (started) {
            setState(WidgetState.RECORDING)
            vibrate(40)
            Log.d(TAG, "Recording started")
        } else {
            Toast.makeText(this, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndProcess() {
        setState(WidgetState.PROCESSING)
        vibrate(30)

        val audioFile = audioRecorder.stop()
        Log.d(TAG, "Recording stopped: ${audioFile?.length()} bytes")

        if (audioFile == null || !audioFile.exists() || audioFile.length() < 1000) {
            Toast.makeText(this, "Запись слишком короткая", Toast.LENGTH_SHORT).show()
            setState(WidgetState.IDLE)
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) { apiClient.processAudio(audioFile) }
            audioFile.delete()
            when (result) {
                is ApiResult.Success -> {
                    copyToClipboard(result.text)
                    val pasted = PasteAccessibilityService.pasteText(result.text)
                    if (!pasted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(this@FloatingWidgetService, "📋 Скопировано!", Toast.LENGTH_SHORT).show()
                    }
                    setState(WidgetState.DONE)
                    vibrate(80)
                    delay(3000)
                    if (currentState == WidgetState.DONE) setState(WidgetState.IDLE)
                }
                is ApiResult.Error -> {
                    Toast.makeText(this@FloatingWidgetService, result.message, Toast.LENGTH_LONG).show()
                    setState(WidgetState.IDLE)
                }
            }
        }
    }

    private fun cancelRecording() {
        audioRecorder.stop()?.delete()
        setState(WidgetState.IDLE)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setState(state: WidgetState) {
        currentState = state
        val a = stateAppearance[state]!!
        widgetText.text = a.emoji
        widgetText.textSize = a.textSize
        widgetText.background = backdrop(state)
        layoutParams.alpha = when (state) {
            WidgetState.RECORDING  -> 1.0f
            WidgetState.PROCESSING -> 0.75f
            WidgetState.DONE       -> 1.0f
            WidgetState.IDLE       -> 0.92f
        }
        windowManager.updateViewLayout(container, layoutParams)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Скруглённый квадрат (как иконка приложения); цвет зависит от состояния, без обводки */
    private fun backdrop(state: WidgetState) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(when (state) {
            WidgetState.IDLE       -> Color.rgb(158, 158, 158)
            WidgetState.RECORDING  -> Color.rgb(198, 40, 40)
            WidgetState.PROCESSING -> Color.rgb(97, 97, 97)
            WidgetState.DONE       -> Color.rgb(46, 125, 50)
        })
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("igramotey", text))
    }

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)
                    ?.defaultVibrator
                    ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(ms)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate: ${e.message}")
        }
    }
}
