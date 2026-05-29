package com.darvis.flow.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

private enum class OverlayState { IDLE, RECORDING, PROCESSING }

/**
 * Foreground service that shows a floating bubble overlay with 3 states:
 * IDLE: mic bubble (draggable) → tap to start recording
 * RECORDING: X + animated waveform + ✓ → tap ✓ to transcribe, tap X to cancel
 * PROCESSING: mic bubble dimmed while transcribing
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var audioRecorder: AudioRecorder
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var state = OverlayState.IDLE

    // Views
    private lateinit var idleContainer: FrameLayout
    private lateinit var recordingContainer: LinearLayout
    private lateinit var barsContainer: LinearLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var btnCancel: FrameLayout
    private lateinit var btnConfirm: FrameLayout

    // Waveform animation
    private val waveformBars = mutableListOf<View>()
    private var waveformAnimator: AnimatorSet? = null

    // Drag state (idle bubble only)
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var savedIdleX = -1
    private val dragThreshold = 10
    private lateinit var params: WindowManager.LayoutParams

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
        if (state == OverlayState.RECORDING) audioRecorder.cancel()
        waveformAnimator?.cancel()
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

        idleContainer = bubbleView.findViewById(R.id.idle_container)
        recordingContainer = bubbleView.findViewById(R.id.recording_container)
        barsContainer = bubbleView.findViewById(R.id.bars_container)
        bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
        btnCancel = bubbleView.findViewById(R.id.btn_cancel)
        btnConfirm = bubbleView.findViewById(R.id.btn_confirm)

        val screenWidth = resources.displayMetrics.widthPixels
        val dp = resources.displayMetrics.density
        val bubblePx = (64 * dp).toInt()
        val marginPx = (8 * dp).toInt()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - bubblePx - marginPx
            y = 200
        }

        setupWaveformBars()
        setupListeners()
        windowManager.addView(bubbleView, params)
    }

    private fun setupWaveformBars() {
        val dp = resources.displayMetrics.density
        val barW = (4 * dp).toInt()
        val barH = (26 * dp).toInt()
        val margin = (3 * dp).toInt()
        val phases = floatArrayOf(0.2f, 0.5f, 0.9f, 0.4f, 0.7f, 0.3f, 0.8f, 0.5f, 0.2f)

        phases.forEach { phase ->
            val bar = View(this)
            bar.layoutParams = LinearLayout.LayoutParams(barW, barH).apply {
                setMargins(margin, 0, margin, 0)
            }
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.RECTANGLE
            drawable.cornerRadius = 2 * dp
            drawable.setColor(0xFF29B6F6.toInt())
            bar.background = drawable
            bar.scaleY = phase * 0.4f + 0.1f
            barsContainer.addView(bar)
            waveformBars.add(bar)
        }
    }

    private fun setupListeners() {
        // Idle bubble: tap to record, drag to move
        idleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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
                    if (!isDragging && state == OverlayState.IDLE) startRecording()
                    true
                }
                else -> false
            }
        }

        btnCancel.setOnClickListener { cancelRecording() }
        btnConfirm.setOnClickListener { confirmRecording() }
    }

    private fun startRecording() {
        state = OverlayState.RECORDING
        savedIdleX = params.x
        // Recording panel (~240dp) is wider than bubble (64dp) — shift left so right edges align
        val dp = resources.displayMetrics.density
        val panelWidth = (240 * dp).toInt()
        val bubbleWidth = (64 * dp).toInt()
        params.x = maxOf(0, savedIdleX - (panelWidth - bubbleWidth))
        windowManager.updateViewLayout(bubbleView, params)
        idleContainer.visibility = View.GONE
        recordingContainer.visibility = View.VISIBLE
        startWaveformAnimation()
        audioRecorder.start()
    }

    private fun cancelRecording() {
        audioRecorder.cancel()
        stopWaveformAnimation()
        showIdle()
    }

    private fun confirmRecording() {
        val audioFile = audioRecorder.stop() ?: run { cancelRecording(); return }
        stopWaveformAnimation()
        state = OverlayState.PROCESSING
        recordingContainer.visibility = View.GONE
        idleContainer.visibility = View.VISIBLE
        bubbleIcon.alpha = 0.4f
        processAudio(audioFile)
    }

    private fun showIdle() {
        state = OverlayState.IDLE
        if (savedIdleX >= 0) {
            params.x = savedIdleX
            windowManager.updateViewLayout(bubbleView, params)
            savedIdleX = -1
        }
        recordingContainer.visibility = View.GONE
        idleContainer.visibility = View.VISIBLE
        bubbleIcon.alpha = 1f
    }

    private fun startWaveformAnimation() {
        waveformAnimator?.cancel()
        val delays = longArrayOf(0, 80, 160, 40, 120, 200, 60, 140, 20)
        val scales = floatArrayOf(0.9f, 0.5f, 1.0f, 0.6f, 0.8f, 0.4f, 0.95f, 0.55f, 0.75f)

        val animators = waveformBars.mapIndexed { i, bar ->
            ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.15f, scales[i]).apply {
                duration = 380 + i * 30L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                startDelay = delays[i]
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        waveformAnimator = AnimatorSet().apply {
            playTogether(*animators.toTypedArray())
            start()
        }
    }

    private fun stopWaveformAnimation() {
        waveformAnimator?.cancel()
        waveformAnimator = null
        waveformBars.forEach { it.scaleY = 0.3f }
    }

    private fun processAudio(audioFile: java.io.File) {
        scope.launch {
            try {
                val transcription = WhisperApi.transcribe(audioFile).getOrThrow()
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
                    }
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper Blue", result))
                showToast("✅ Copied! Paste anywhere")
                showResultNotification(result)
            } catch (e: Exception) {
                showToast("Error: ${e.message?.take(80)}")
            } finally {
                showIdle()
                audioFile.delete()
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, DarvisApp.CHANNEL_OVERLAY)
            .setContentTitle("Whisper Blue")
            .setContentText("Tap the bubble to record")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun showResultNotification(text: String) {
        val notification = NotificationCompat.Builder(this, DarvisApp.CHANNEL_RESULT)
            .setContentTitle("Whisper Blue — Ready to paste")
            .setContentText(text.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(2, notification)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
