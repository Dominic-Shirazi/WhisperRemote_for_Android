package com.example.whisperremote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

class FloatingButtonService : Service() {

    companion object {
        private var instance: FloatingButtonService? = null
        fun getInstance(): FloatingButtonService? = instance
    }

    private enum class ButtonState {
        HIDDEN,               // no text field focused, not recording
        IDLE,                 // text field focused, ready to record
        RECORDING,            // recording, text field still focused
        RECORDING_NO_FOCUS,   // recording, user navigated away from text field
        PROCESSING            // transcribing / pasting
    }

    private lateinit var windowManager: WindowManager
    private var container: LinearLayout? = null
    private var floatingButton: ImageView? = null
    private var hintLabel: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private var state = ButtonState.HIDDEN
    private var isRecording = false
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private lateinit var apiClient: ApiClient

    private val mainHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "FloatingButtonServiceChannel"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        apiClient = ApiClient(this)
        createNotificationChannel()
        startForeground(1, createNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val buttonPx = 150  // keep same size as before

        floatingButton = ImageView(this).apply {
            setImageResource(R.drawable.whisper_remote)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        hintLabel = TextView(this).apply {
            text = "Tap a text field to finish"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.TRANSPARENT)
            addView(floatingButton, LinearLayout.LayoutParams(buttonPx, buttonPx))
            addView(hintLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(4) })
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
        windowParams = params

        container?.setOnTouchListener(object : View.OnTouchListener {
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
                            windowManager.updateViewLayout(container, params)
                            isMoved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) handleTap()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Failed to add overlay", e)
        }
    }

    private fun handleTap() {
        when (state) {
            ButtonState.IDLE -> startRecording()
            ButtonState.RECORDING -> stopRecording()
            ButtonState.RECORDING_NO_FOCUS -> { /* ignore — user needs to tap a text field first */ }
            ButtonState.PROCESSING -> { /* ignore — transcription in flight */ }
            ButtonState.HIDDEN -> { /* shouldn't happen */ }
        }
    }

    // Called by DictationAccessibilityService when an editable field gains focus
    fun onTextFieldFocused() {
        mainHandler.post {
            when (state) {
                ButtonState.HIDDEN -> setState(ButtonState.IDLE)
                ButtonState.RECORDING_NO_FOCUS -> setState(ButtonState.RECORDING)
                else -> {}
            }
        }
    }

    // Called by DictationAccessibilityService when focus leaves all editable fields
    fun onTextFieldFocusLost() {
        mainHandler.post {
            when (state) {
                ButtonState.IDLE -> setState(ButtonState.HIDDEN)
                ButtonState.RECORDING -> setState(ButtonState.RECORDING_NO_FOCUS)
                else -> { /* PROCESSING stays visible until paste completes */ }
            }
        }
    }

    // Called by DictationAccessibilityService after paste completes
    fun onPasteComplete(hasFocus: Boolean) {
        mainHandler.post {
            setState(if (hasFocus) ButtonState.IDLE else ButtonState.HIDDEN)
        }
    }

    // Re-add the overlay to WindowManager so it sits on top of other overlays
    // (e.g. chat bubbles). Same-type windows are z-ordered by insertion time.
    private fun bringToFront() {
        val view = container ?: return
        val params = windowParams ?: return
        try {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "bringToFront failed", e)
        }
    }

    private fun setState(newState: ButtonState) {
        val wasHidden = state == ButtonState.HIDDEN
        state = newState
        when (state) {
            ButtonState.HIDDEN -> {
                container?.visibility = View.GONE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.IDLE -> {
                if (wasHidden) bringToFront()
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
            ButtonState.RECORDING -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 1.0f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.RECORDING_NO_FOCUS -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.VISIBLE
                floatingButton?.alpha = 0.4f
                floatingButton?.setImageResource(R.drawable.whisper_remote_record)
            }
            ButtonState.PROCESSING -> {
                container?.visibility = View.VISIBLE
                hintLabel?.visibility = View.GONE
                floatingButton?.alpha = 0.5f
                floatingButton?.setImageResource(R.drawable.whisper_remote)
            }
        }
    }

    private fun startRecording() {
        audioFile = File(externalCacheDir, "recording.m4a")
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
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
                setState(ButtonState.RECORDING)
                Log.d("FloatingButtonService", "Recording started")
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Start failed", e)
            }
        }
    }

    private fun stopRecording() {
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
        setState(ButtonState.PROCESSING)

        audioFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                apiClient.transcribe(file) { transcribedText ->
                    if (transcribedText != null) {
                        Log.d("FloatingButtonService", "Transcribed: $transcribedText")
                        mainHandler.post {
                            DictationAccessibilityService.getInstance()?.pasteText(transcribedText)
                        }
                    } else {
                        mainHandler.post { setState(ButtonState.HIDDEN) }
                    }
                }
            } else {
                setState(ButtonState.HIDDEN)
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WhisperRemote Service", NotificationManager.IMPORTANCE_LOW
            )
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
        instance = null
        container?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("FloatingButtonService", "Error removing overlay", e)
            }
        }
        mediaRecorder?.release()
    }
}