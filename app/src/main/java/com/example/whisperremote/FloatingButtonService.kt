package com.example.whisperremote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import java.io.File

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButton: ImageView? = null
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private lateinit var apiClient: ApiClient

    private val CHANNEL_ID = "FloatingButtonServiceChannel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        apiClient = ApiClient(this)
        createNotificationChannel()
        startForeground(1, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingButton() {
        floatingButton = ImageView(this).apply {
            setImageResource(R.drawable.whisper_remote)
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1.0f 
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val params = WindowManager.LayoutParams(
            150, 150,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        floatingButton?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(floatingButton, params)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) toggleRecording()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(floatingButton, params)
            Log.d("FloatingButtonService", "Floating button added successfully")
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to add floating button", e)
        }
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        Log.d("FloatingButtonService", "Starting recording...")
        audioFile = File(externalCacheDir, "recording.m4a")
        
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioFile?.absolutePath)
            try {
                prepare()
                start()
                isRecording = true
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
                Log.d("FloatingButtonService", "Recording started")
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Start failed", e)
            }
        }
    }

    private fun stopRecording() {
        Log.d("FloatingButtonService", "Stopping recording...")
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Stop failed", e)
        }
        mediaRecorder = null
        isRecording = false
        floatingButton?.setImageResource(R.drawable.whisper_remote)

        audioFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                apiClient.transcribe(file) { transcribedText ->
                    if (transcribedText != null) {
                        Log.d("FloatingButtonService", "Transcribed: $transcribedText")
                        Handler(Looper.getMainLooper()).post {
                            DictationAccessibilityService.getInstance()?.pasteText(transcribedText)
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WhisperRemote Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhisperRemote")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingButton?.let { 
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error removing button", e)
            }
        }
        mediaRecorder?.release()
    }
}
