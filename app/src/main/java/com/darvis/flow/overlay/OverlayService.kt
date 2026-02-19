package com.darvis.flow.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.darvis.flow.DarvisApp
import com.darvis.flow.MainActivity
import com.darvis.flow.R
import com.darvis.flow.api.PromptStructurer
import com.darvis.flow.api.WhisperApi
import com.darvis.flow.util.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Foreground service that shows a floating bubble overlay.
 *
 * UX: Hold bubble → record, release → transcribe → copy to clipboard + toast.
 * The bubble can be dragged to reposition.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var audioRecorder: AudioRecorder

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRecording = false
    private var isDragging = false

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val dragThreshold = 10

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioRecorder = AudioRecorder(this)
        createBubble()
        startForeground(1, createNotification())
    }

    override fun onDestroy() {
        scope.cancel()
        if (isRecording) audioRecorder.cancel()
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, DarvisApp.CHANNEL_OVERLAY)
            .setContentTitle("Darvis Flow")
            .setContentText("Hold the bubble to record")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        val bubbleIcon = bubbleView.findViewById<ImageView>(R.id.bubble_icon)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    startRecording(bubbleIcon)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isRecording) {
                        stopRecordingAndProcess(bubbleIcon)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun startRecording(icon: ImageView) {
        if (isRecording) return
        isRecording = true
        icon.setColorFilter(0xFFFF3399.toInt()) // Magenta when recording
        audioRecorder.start()
    }

    private fun stopRecordingAndProcess(icon: ImageView) {
        if (!isRecording) return
        isRecording = false
        icon.clearColorFilter()

        val audioFile = audioRecorder.stop() ?: return

        scope.launch {
            icon.alpha = 0.5f // Dim while processing

            try {
                val whisperKey = Settings.whisperApiKey(this@OverlayService).first()
                if (whisperKey.isBlank()) {
                    showToast("Set your OpenAI API key in Darvis settings")
                    return@launch
                }

                // Step 1: Transcribe
                val transcription = WhisperApi.transcribe(audioFile, whisperKey).getOrThrow()

                // Step 2: Optional prompt structuring
                var result = transcription
                val promptEnabled = Settings.promptEnabled(this@OverlayService).first()
                if (promptEnabled) {
                    val provider = Settings.promptProvider(this@OverlayService).first()
                    val model = Settings.promptModel(this@OverlayService).first()
                    val promptKey = Settings.promptApiKey(this@OverlayService).first()

                    if (promptKey.isNotBlank()) {
                        PromptStructurer.structure(transcription, provider, model, promptKey)
                            .onSuccess { result = it }
                            .onFailure { showToast("Prompt mode failed, using raw text") }
                        // Fallback to raw transcription on any error — never lose text
                    }
                }

                // Step 3: Copy to clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Darvis", result))
                showToast("✅ Copied! Paste anywhere")

                // Step 4: Show notification with the text (tappable)
                showResultNotification(result)

            } catch (e: Exception) {
                showToast("Error: ${e.message?.take(80)}")
            } finally {
                icon.alpha = 1f
                audioFile.delete()
            }
        }
    }

    private fun showResultNotification(text: String) {
        val notification = NotificationCompat.Builder(this, DarvisApp.CHANNEL_RESULT)
            .setContentTitle("Darvis — Ready to paste")
            .setContentText(text.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(2, notification)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
