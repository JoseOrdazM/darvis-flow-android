package com.darvis.flow.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "darvis_settings")

/**
 * Persistent settings via DataStore.
 * API keys are stored locally on-device, never transmitted except to the configured provider.
 */
object Settings {

    private val WHISPER_API_KEY = stringPreferencesKey("whisper_api_key")
    private val PROMPT_ENABLED = booleanPreferencesKey("prompt_enabled")
    private val PROMPT_PROVIDER = stringPreferencesKey("prompt_provider")
    private val PROMPT_MODEL = stringPreferencesKey("prompt_model")
    private val PROMPT_API_KEY = stringPreferencesKey("prompt_api_key")

    // --- Whisper ---

    fun whisperApiKey(context: Context): Flow<String> =
        context.dataStore.data.map { it[WHISPER_API_KEY] ?: "" }

    suspend fun setWhisperApiKey(context: Context, key: String) {
        context.dataStore.edit { it[WHISPER_API_KEY] = key.trim() }
    }

    // --- Prompt Mode ---

    fun promptEnabled(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[PROMPT_ENABLED] ?: false }

    suspend fun setPromptEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[PROMPT_ENABLED] = enabled }
    }

    fun promptProvider(context: Context): Flow<String> =
        context.dataStore.data.map { it[PROMPT_PROVIDER] ?: "openrouter" }

    suspend fun setPromptProvider(context: Context, provider: String) {
        context.dataStore.edit { it[PROMPT_PROVIDER] = provider }
    }

    fun promptModel(context: Context): Flow<String> =
        context.dataStore.data.map { it[PROMPT_MODEL] ?: "anthropic/claude-3.5-haiku" }

    suspend fun setPromptModel(context: Context, model: String) {
        context.dataStore.edit { it[PROMPT_MODEL] = model }
    }

    fun promptApiKey(context: Context): Flow<String> =
        context.dataStore.data.map { it[PROMPT_API_KEY] ?: "" }

    suspend fun setPromptApiKey(context: Context, key: String) {
        context.dataStore.edit { it[PROMPT_API_KEY] = key.trim() }
    }
}
