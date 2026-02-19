package com.darvis.flow.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud LLM prompt structurer — same logic as desktop DraVis Flow.
 * Sends raw transcription to an LLM to restructure into a clean prompt.
 * Supports Anthropic, OpenAI, and OpenRouter (OpenAI-compatible).
 */
object PromptStructurer {

    private const val SYSTEM_PROMPT = """You are a prompt structurer. Take my raw speech transcript and turn it into a clean, first-person prompt I can paste into an LLM.

Rules:
- Write as ME talking to the AI ('I want', 'my project' — never 'the speaker' or 'the user')
- Match complexity to the request: simple ask → 2-3 direct lines, NO sections. Only use ## sections for complex multi-part requests.
- Adapt sections to the content (Context, Goal, Constraints, Tech Stack, etc.) — never use the same template twice
- Do NOT add information I didn't mention
- Do NOT pad or over-explain — be more concise than the original speech, not more verbose
- Remove filler, repetition, and hedging — keep only what matters
- Output ONLY the structured prompt — no preamble, no meta-commentary"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun structure(
        text: String,
        provider: String,
        model: String,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (provider.lowercase()) {
                "anthropic" -> callAnthropic(text, model, apiKey)
                "openai" -> callOpenAiCompat(text, model, apiKey, "https://api.openai.com/v1/chat/completions")
                "openrouter" -> callOpenAiCompat(text, model, apiKey, "https://openrouter.ai/api/v1/chat/completions")
                else -> Result.failure(Exception("Unsupported provider: $provider"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun callAnthropic(text: String, model: String, apiKey: String): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("system", SYSTEM_PROMPT)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            }))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return Result.failure(Exception("Anthropic error (${response.code}): ${extractError(responseBody)}"))
        }

        val content = JSONObject(responseBody).optJSONArray("content") ?: return Result.failure(Exception("No content in response"))
        val parts = (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .mapNotNull { it.optString("text")?.trim()?.takeIf { t -> t.isNotEmpty() } }

        return if (parts.isEmpty()) Result.failure(Exception("Empty response")) else Result.success(parts.joinToString("\n\n"))
    }

    private fun callOpenAiCompat(text: String, model: String, apiKey: String, url: String): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return Result.failure(Exception("API error (${response.code}): ${extractError(responseBody)}"))
        }

        val content = JSONObject(responseBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()

        return if (content.isNullOrEmpty()) Result.failure(Exception("Empty response")) else Result.success(content)
    }

    private fun extractError(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200)
    } catch (_: Exception) { body.take(200) }
}
