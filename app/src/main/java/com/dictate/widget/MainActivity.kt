package com.dictate.widget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "iGramotey"
        private const val REQ_MIC = 1001
        private const val REQ_OVERLAY = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity.onCreate")
        if (FloatingWidgetService.isRunning) {
            finish()
            return
        }
        checkMic()
    }

    override fun onResume() {
        super.onResume()
        if (hasMic() && Settings.canDrawOverlays(this)) {
            startWidget()
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun checkMic() {
        if (hasMic()) checkOverlay()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
        )
    }

    private fun checkOverlay() {
        if (Settings.canDrawOverlays(this)) {
            checkAccessibility()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Нужно разрешение")
                .setMessage("iGramotey нужно разрешение показывать виджет поверх других приложений.\n\nНайди iGramotey в списке и включи переключатель.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                        REQ_OVERLAY
                    )
                }
                .setNegativeButton("Отмена") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun checkAccessibility() {
        if (isAccessibilityEnabled()) {
            startWidget()
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
                .setNegativeButton("Пропустить") { _, _ -> startWidget() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) checkOverlay()
            else { Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show(); finish() }
        }
    }

    @Deprecated("Needed for overlay result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) checkAccessibility()
            else Toast.makeText(this, "Без разрешения виджет не появится", Toast.LENGTH_LONG).show()
        }
    }

    private fun startWidget() {
        if (FloatingWidgetService.isRunning) { finish(); return }
        Log.d(TAG, "Starting FloatingWidgetService")
        val intent = Intent(this, FloatingWidgetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        finish()
    }
}
