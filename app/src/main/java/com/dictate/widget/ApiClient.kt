package com.dictate.widget

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed class ApiResult {
    data class Success(val text: String) : ApiResult()
    data class Error(val message: String) : ApiResult()
}

class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // Groq может думать
        .build()

    fun processAudio(audioFile: File): ApiResult {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("${BuildConfig.API_BASE_URL}/api/dictate")
                .addHeader("X-App-Token", BuildConfig.APP_SECRET_TOKEN)
                .post(requestBody)
                .build()

            Log.d("ApiClient", "Sending audio: ${audioFile.length()} bytes")

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            Log.d("ApiClient", "Response ${response.code}: $body")

            if (!response.isSuccessful) {
                return ApiResult.Error("Ошибка сервера: ${response.code}")
            }

            val json = JSONObject(body)
            val status = json.optString("status")
            val text = json.optString("text")

            if (status == "success" && text.isNotBlank()) {
                ApiResult.Success(text)
            } else {
                ApiResult.Error(text.ifBlank { "Пустой ответ от сервера" })
            }

        } catch (e: Exception) {
            Log.e("ApiClient", "Request failed: ${e.message}")
            val msg = when {
                e.message?.contains("timeout", ignoreCase = true) == true ->
                    "Сервер не ответил вовремя. Попробуй ещё раз."
                e.message?.contains("Unable to resolve host") == true ->
                    "Нет интернета"
                else -> "Ошибка соединения: ${e.message?.take(60)}"
            }
            ApiResult.Error(msg)
        }
    }
}
