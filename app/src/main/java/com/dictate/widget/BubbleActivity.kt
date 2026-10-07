package com.dictate.widget

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BubbleActivity : AppCompatActivity() {

    // ── Состояния ─────────────────────────────────────────────────────────
    enum class State { IDLE, RECORDING, PROCESSING, DONE, ERROR }

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var root: FrameLayout
    private lateinit var iconView: TextView      // эмодзи-иконка состояния
    private lateinit var pulseRing: View         // кольцо пульсации при записи
    private lateinit var timerView: TextView     // обратный отсчёт таймаута

    // ── Service binding ───────────────────────────────────────────────────
    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.LocalBinder).getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
        }
    }

    // ── State ─────────────────────────────────────────────────────────────
    private var currentState = State.IDLE
    private var pulseAnimator: AnimatorSet? = null
    private var timeoutTimer: CountDownTimer? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "iGramotey.Bubble"
        private const val RECORDING_TIMEOUT_MS = 3 * 60 * 1000L  // 3 минуты
        private val STATE_EMOJI = mapOf(
            State.IDLE       to "🎤",
            State.RECORDING  to "🔴",
            State.PROCESSING to "⏳",
            State.DONE       to "✅",
            State.ERROR      to "❌"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate, isLaunchedFromBubble=${isLaunchedFromBubble()}")
        buildUI()
        bindRecordingService()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPulse()
        timeoutTimer?.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Если закрыли пузырь — останавливаем сервис
        stopService(Intent(this, RecordingService::class.java))
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI
    // ══════════════════════════════════════════════════════════════════════

    private fun buildUI() {
        // Корневой контейнер — тёмно-полупрозрачный фон
        root = FrameLayout(this).apply {
            setBackgroundColor(0xCC1a1a2e.toInt())
        }

        // Кольцо пульсации (за иконкой)
        pulseRing = View(this).apply {
            setBackgroundResource(R.drawable.pulse_ring)
            alpha = 0f
            visibility = View.INVISIBLE
        }
        root.addView(pulseRing, FrameLayout.LayoutParams(180, 180, Gravity.CENTER))

        // Основная иконка-эмодзи
        iconView = TextView(this).apply {
            text = STATE_EMOJI[State.IDLE]
            textSize = 48f
            gravity = Gravity.CENTER
        }
        root.addView(iconView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        // Таймер (показывается только при записи)
        timerView = TextView(this).apply {
            text = "3:00"
            textSize = 11f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        root.addView(timerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = 8 })

        setContentView(root)

        // Тап на иконку
        iconView.setOnClickListener { onIconTap() }
    }

    private fun onIconTap() {
        when (currentState) {
            State.IDLE       -> startRecording()
            State.RECORDING  -> stopRecordingAndProcess()
            State.DONE       -> setState(State.IDLE)
            State.ERROR      -> setState(State.IDLE)
            State.PROCESSING -> { /* ждём */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATE MACHINE
    // ══════════════════════════════════════════════════════════════════════

    private fun setState(state: State) {
        Log.d(TAG, "State: $currentState → $state")
        currentState = state

        // Анимированная смена иконки
        iconView.animate()
            .scaleX(0.6f).scaleY(0.6f)
            .setDuration(100)
            .withEndAction {
                iconView.text = STATE_EMOJI[state]
                iconView.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }.start()

        when (state) {
            State.RECORDING  -> { startPulse(); showTimer() }
            else             -> { stopPulse(); hideTimer() }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // RECORDING
    // ══════════════════════════════════════════════════════════════════════

    private fun startRecording() {
        val svc = recordingService ?: run {
            Log.w(TAG, "Service not bound yet")
            Toast.makeText(this, "Сервис не готов, подожди секунду", Toast.LENGTH_SHORT).show()
            return
        }
        val started = svc.audioRecorder.start(this)
        if (!started) {
            Toast.makeText(this, "Не удалось начать запись", Toast.LENGTH_SHORT).show()
            return
        }
        setState(State.RECORDING)
        vibrate(50)
        startTimeout()
    }

    private fun stopRecordingAndProcess() {
        timeoutTimer?.cancel()
        val svc = recordingService ?: return
        setState(State.PROCESSING)
        vibrate(30)

        val audioFile = svc.audioRecorder.stop()
        if (audioFile == null || audioFile.length() < 1000) {
            Toast.makeText(this, "Запись слишком короткая", Toast.LENGTH_SHORT).show()
            setState(State.IDLE)
            return
        }

        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                svc.apiClient.processAudio(audioFile)
            }
            audioFile.delete()

            when (result) {
                is ApiResult.Success -> {
                    copyToClipboard(result.text)
                    PasteAccessibilityService.pasteText(result.text)
                    setState(State.DONE)
                    vibrate(80)
                    delay(3000)
                    if (currentState == State.DONE) setState(State.IDLE)
                }
                is ApiResult.Error -> {
                    Toast.makeText(this@BubbleActivity, result.message, Toast.LENGTH_LONG).show()
                    setState(State.ERROR)
                    delay(2000)
                    setState(State.IDLE)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // TIMEOUT (3 минуты)
    // ══════════════════════════════════════════════════════════════════════

    private fun startTimeout() {
        timeoutTimer?.cancel()
        timeoutTimer = object : CountDownTimer(RECORDING_TIMEOUT_MS, 1000) {
            override fun onTick(ms: Long) {
                val m = ms / 60000
                val s = (ms % 60000) / 1000
                timerView.text = "%d:%02d".format(m, s)
                // Последние 30 секунд — таймер краснеет
                if (ms < 30_000) timerView.setTextColor(0xFFFF4444.toInt())
                else timerView.setTextColor(0xAAFFFFFF.toInt())
            }
            override fun onFinish() {
                Log.d(TAG, "Recording timeout")
                Toast.makeText(this@BubbleActivity, "⏱ Таймаут — обрабатываю", Toast.LENGTH_SHORT).show()
                stopRecordingAndProcess()
            }
        }.start()
    }

    private fun showTimer() {
        timerView.visibility = View.VISIBLE
        timerView.animate().alpha(1f).setDuration(300).start()
    }

    private fun hideTimer() {
        timerView.animate().alpha(0f).setDuration(200).withEndAction {
            timerView.visibility = View.INVISIBLE
            timerView.setTextColor(0xAAFFFFFF.toInt())
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PULSE ANIMATION
    // ══════════════════════════════════════════════════════════════════════

    private fun startPulse() {
        pulseRing.visibility = View.VISIBLE
        pulseRing.alpha = 0.6f
        pulseRing.scaleX = 1f
        pulseRing.scaleY = 1f

        val scaleX = ObjectAnimator.ofFloat(pulseRing, "scaleX", 1f, 1.8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(pulseRing, "scaleY", 1f, 1.8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(pulseRing, "alpha", 0.6f, 0f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing.animate().alpha(0f).setDuration(200).withEndAction {
            pulseRing.visibility = View.INVISIBLE
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun bindRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("igramotey", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "📋 Скопировано!", Toast.LENGTH_SHORT).show()
        }
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
