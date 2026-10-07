package com.dictate.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Foreground Service — только для держания разрешения на микрофон.
 * Вся логика состояний живёт в BubbleActivity.
 * Сервис запускается когда пузырь открыт, останавливается когда закрыт.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "iGramotey.Service"
        private const val CHANNEL_ID = "igramotey_recording"
        private const val NOTIF_ID = 43
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@RecordingService
    }

    private val binder = LocalBinder()
    val scope = CoroutineScope(Dispatchers.Main + Job())
    val audioRecorder = AudioRecorder()
    val apiClient = ApiClient()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        audioRecorder.release()
        Log.d(TAG, "onDestroy")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "iGramotey запись",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("iGramotey")
            .setContentText("Идёт запись...")
            .setOngoing(true)
            .build()
    }
}
