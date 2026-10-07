package com.dictate.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iGramotey"
        private const val REQ_MIC = 1001
        private const val REQ_NOTIFICATIONS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity.onCreate")
        checkMic()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun checkMic() {
        if (hasMic()) checkNotifications()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
        )
    }

    private fun checkNotifications() {
        if (hasNotificationPermission()) checkAccessibility()
        else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
        }
    }

    private fun checkAccessibility() {
        if (isAccessibilityEnabled()) {
            launch()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Автовставка текста")
                .setMessage(
                    "Хочешь, чтобы iGramotey вставлял текст прямо в поле ввода?\n\n" +
                    "Включи «iGramotey — автовставка» в:\n" +
                    "Настройки → Специальные возможности\n\n" +
                    "Без этого текст будет копироваться в буфер обмена."
                )
                .setPositiveButton("Включить") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Пропустить") { _, _ -> launch() }
                .setCancelable(false)
                .show()
        }
    }

    private fun launch() {
        BubbleManager.showBubble(this, autoExpand = true)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) checkNotifications()
                else { Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show(); finish() }
            }
            REQ_NOTIFICATIONS -> checkAccessibility()
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из настроек accessibility
        if (hasMic() && hasNotificationPermission()) launch()
    }
}
