package com.darvis.flow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.darvis.flow.overlay.OverlayService
import com.darvis.flow.util.Settings
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen. Configure API keys, Prompt Mode, and start/stop the overlay.
 */
class MainActivity : AppCompatActivity() {

    private var isOverlayRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val whisperKeyInput = findViewById<EditText>(R.id.whisper_api_key)
        val promptToggle = findViewById<MaterialSwitch>(R.id.prompt_toggle)
        val promptKeyInput = findViewById<EditText>(R.id.prompt_api_key)
        val promptProviderLabel = findViewById<TextView>(R.id.prompt_provider_label)
        val promptModelInput = findViewById<EditText>(R.id.prompt_model)
        val btnAnthropic = findViewById<Button>(R.id.btn_anthropic)
        val btnOpenai = findViewById<Button>(R.id.btn_openai)
        val btnOpenrouter = findViewById<Button>(R.id.btn_openrouter)
        val startStopBtn = findViewById<Button>(R.id.btn_start_stop)
        val statusText = findViewById<TextView>(R.id.status_text)

        // Load saved settings
        lifecycleScope.launch {
            whisperKeyInput.setText(Settings.whisperApiKey(this@MainActivity).first())
            promptToggle.isChecked = Settings.promptEnabled(this@MainActivity).first()
            promptKeyInput.setText(Settings.promptApiKey(this@MainActivity).first())
            promptModelInput.setText(Settings.promptModel(this@MainActivity).first())
            updateProviderButtons(
                Settings.promptProvider(this@MainActivity).first(),
                btnAnthropic, btnOpenai, btnOpenrouter, promptProviderLabel
            )
        }

        // Save whisper key on focus loss
        whisperKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) lifecycleScope.launch {
                Settings.setWhisperApiKey(this@MainActivity, whisperKeyInput.text.toString())
            }
        }

        // Prompt toggle
        promptToggle.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { Settings.setPromptEnabled(this@MainActivity, checked) }
        }

        // Save prompt key on focus loss
        promptKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) lifecycleScope.launch {
                Settings.setPromptApiKey(this@MainActivity, promptKeyInput.text.toString())
            }
        }

        // Save model on focus loss
        promptModelInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) lifecycleScope.launch {
                Settings.setPromptModel(this@MainActivity, promptModelInput.text.toString())
            }
        }

        // Provider buttons
        val providerButtons = listOf(
            btnAnthropic to "anthropic",
            btnOpenai to "openai",
            btnOpenrouter to "openrouter"
        )
        providerButtons.forEach { (btn, provider) ->
            btn.setOnClickListener {
                lifecycleScope.launch {
                    Settings.setPromptProvider(this@MainActivity, provider)
                    updateProviderButtons(provider, btnAnthropic, btnOpenai, btnOpenrouter, promptProviderLabel)
                }
            }
        }

        // Start/Stop overlay
        startStopBtn.setOnClickListener {
            if (isOverlayRunning) {
                stopOverlay()
                startStopBtn.text = "Start Whisper Blue"
                statusText.text = "Stopped"
            } else {
                if (checkPermissions()) {
                    startOverlay()
                    startStopBtn.text = "Stop Whisper Blue"
                    statusText.text = "Running — hold the bubble to record"
                }
            }
        }
    }

    private fun updateProviderButtons(
        active: String,
        btnAnthropic: Button,
        btnOpenai: Button,
        btnOpenrouter: Button,
        label: TextView
    ) {
        listOf(btnAnthropic to "anthropic", btnOpenai to "openai", btnOpenrouter to "openrouter").forEach { (btn, p) ->
            btn.isSelected = p == active
            btn.alpha = if (p == active) 1f else 0.5f
        }
        label.text = "Provider: ${active.replaceFirstChar { it.uppercase() }}"
    }

    private fun checkPermissions(): Boolean {
        // Microphone
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            return false
        }

        // Overlay
        if (!AndroidSettings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable overlay permission for Whisper Blue", Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }

        // Notifications (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            return false
        }

        return true
    }

    private fun startOverlay() {
        isOverlayRunning = true
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun stopOverlay() {
        isOverlayRunning = false
        stopService(Intent(this, OverlayService::class.java))
    }
}
