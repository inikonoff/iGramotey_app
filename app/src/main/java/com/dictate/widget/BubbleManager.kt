package com.dictate.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Создаёт и обновляет Bubble-уведомление.
 * Bubble требует:
 *   1. Канал с IMPORTANCE_HIGH
 *   2. ShortcutInfo с setLongLived(true)
 *   3. BubbleMetadata с PendingIntent на BubbleActivity
 *   4. Notification с MessagingStyle или setShortcutId
 */
object BubbleManager {

    const val CHANNEL_ID = "igramotey_bubble"
    const val NOTIF_ID = 42
    const val SHORTCUT_ID = "igramotey_shortcut"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "iGramotey",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Диктовка текста"
            setShowBadge(false)
            // Без звука и вибрации при обновлениях состояния
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    fun pushShortcut(context: Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setLongLived(true)
            .setShortLabel("iGramotey")
            .setIntent(Intent(context, BubbleActivity::class.java).apply {
                action = Intent.ACTION_DEFAULT
            })
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun showBubble(context: Context, autoExpand: Boolean = true) {
        createChannel(context)
        pushShortcut(context)

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(context, autoExpand))
    }

    fun updateBubble(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(context, autoExpand = false))
    }

    fun dismiss(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIF_ID)
    }

    private fun buildNotification(context: Context, autoExpand: Boolean): Notification {
        val bubbleIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, BubbleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val bubbleMetadata = Notification.BubbleMetadata.Builder(
            bubbleIntent,
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        )
            .setDesiredHeight(200)
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(true)
            .build()

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("iGramotey")
            .setContentText("Диктовка текста")
            .setShortcutId(SHORTCUT_ID)
            .setBubbleMetadata(bubbleMetadata)
            .setOnlyAlertOnce(true)
            .build()
    }
}
