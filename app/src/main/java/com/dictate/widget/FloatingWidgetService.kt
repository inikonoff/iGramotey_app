package com.dictate.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
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
        WidgetState.RECORDING  to StateAppearance("🔴", 40f),
        WidgetState.PROCESSING to StateAppearance("⏳", 40f),
        WidgetState.DONE       to StateAppearance("✅", 40f)
    )

    private lateinit var windowManager: WindowManager
    private lateinit var widgetText: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val audioRecorder = AudioRecorder()
    private val apiClient = ApiClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var currentState = WidgetState.IDLE

    // Touch — только для перетаскивания
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val DRAG_THRESHOLD = 12f

    // Двойной тап для выхода
    private var lastTapTime = 0L
    private val DOUBLE_TAP_MS = 350L

    // SharedPreferences
    private val PREFS_NAME = "igramotey_prefs"
    private val PREF_X = "widget_x"
    private val PREF_Y = "widget_y"

    companion object {
        private const val CHANNEL_ID = "dictate_widget_channel"
        private const val NOTIF_ID = 1
        private const val TAG = "iGramotey"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            startForegroundNotification()
            createWidget()
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        savePosition()
        scope.cancel()
        audioRecorder.release()
        try {
            if (::widgetText.isInitialized) windowManager.removeView(widgetText)
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
                .setContentText("Нажми чтобы начать диктовку")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        )
    }

    // ── Widget ────────────────────────────────────────────────────────────

    private fun createWidget() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        widgetText = TextView(this).apply {
            text = stateAppearance[WidgetState.IDLE]!!.emoji
            textSize = stateAppearance[WidgetState.IDLE]!!.textSize
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 50)
            y = prefs.getInt(PREF_Y, 300)
            alpha = 0.92f
        }
        widgetText.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(widgetText, layoutParams)
    }

    private fun savePosition() {
        if (::layoutParams.isInitialized) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_X, layoutParams.x)
                .putInt(PREF_Y, layoutParams.y)
                .apply()
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                // Запоминаем стартовую позицию для определения drag
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging &&
                    (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                    isDragging = true
                }
                if (isDragging) {
                    layoutParams.x = (initialX + dx).toInt()
                    layoutParams.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(widgetText, layoutParams)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // Просто сохраняем позицию — запись не трогаем
                    savePosition()
                    isDragging = false
                    return true
                }
                isDragging = false

                // Это был тап — обрабатываем по состоянию
                val now = System.currentTimeMillis()
                when (currentState) {
                    WidgetState.IDLE -> {
                        // Двойной тап в IDLE = выход
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            Log.d(TAG, "Double tap — exit")
                            Toast.makeText(this, "iGramotey выключен", Toast.LENGTH_SHORT).show()
                            stopSelf()
                            return true
                        }
                        lastTapTime = now
                        startRecording()
                    }
                    WidgetState.RECORDING -> stopRecordingAndProcess()
                    WidgetState.DONE -> setState(WidgetState.IDLE)
                    WidgetState.PROCESSING -> { /* ждём */ }
                }
            }
        }
        return true
    }

    // ── Recording ─────────────────────────────────────────────────────────

    private fun startRecording() {
        val started = audioRecorder.start(this)
        if (started) {
            setState(WidgetState.RECORDING)
            vibrate(50)
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
                        Toast.makeText(
                            this@FloatingWidgetService,
                            "📋 Скопировано!",
                            Toast.LENGTH_SHORT
                        ).show()
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

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun setState(state: WidgetState) {
        currentState = state
        val a = stateAppearance[state]!!
        widgetText.text = a.emoji
        widgetText.textSize = a.textSize
        layoutParams.alpha = when (state) {
            WidgetState.RECORDING  -> 1.0f
            WidgetState.PROCESSING -> 0.75f
            WidgetState.DONE       -> 1.0f
            WidgetState.IDLE       -> 0.92f
        }
        windowManager.updateViewLayout(widgetText, layoutParams)
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
