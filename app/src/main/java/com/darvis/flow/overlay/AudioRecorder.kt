package com.darvis.flow.overlay

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Simple MediaRecorder wrapper. Records to M4A (AAC) for efficient upload to Whisper API.
 * File is saved to app cache dir — auto-cleaned by system.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File {
        val file = File(context.cacheDir, "darvis_recording_${System.currentTimeMillis()}.m4a")
        outputFile = file

        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(16_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return file
    }

    fun stop(): File? {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            // MediaRecorder can throw if stopped too quickly
        }
        recorder = null
        return outputFile
    }

    fun cancel() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
