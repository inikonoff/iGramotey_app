package com.dictate.widget

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(context: Context): Boolean {
        return try {
            val file = File(context.cacheDir, "dictate_${System.currentTimeMillis()}.m4a")
            outputFile = file

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            Log.d("AudioRecorder", "Recording started: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording: ${e.message}")
            release()
            false
        }
    }

    /**
     * Останавливает запись и возвращает файл.
     * Возвращает null если записи не было.
     */
    fun stop(): File? {
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            Log.d("AudioRecorder", "Recording stopped: ${outputFile?.length()} bytes")
            outputFile
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to stop recording: ${e.message}")
            release()
            null
        } finally {
            outputFile = null
        }
    }

    fun release() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
    }
}
