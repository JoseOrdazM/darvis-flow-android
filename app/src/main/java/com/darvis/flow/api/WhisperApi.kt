package com.darvis.flow.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val N8N_WHISPER_URL =
    "https://jose-ordaz-n8n.vplkfg.easypanel.host/webhook/transcribir"

object WhisperApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File, apiKey: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "voz.m4a", audioFile.asRequestBody("audio/m4a".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(N8N_WHISPER_URL)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Whisper API error (${response.code}): $responseBody"))
            }

            val json = JSONObject(responseBody)
            val text = (json.optString("texto", "").ifEmpty { json.optString("text", "") }).trim()
            if (text.isEmpty()) {
                Result.failure(Exception("Whisper returned empty transcription"))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
