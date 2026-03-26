package com.example.whisperremote

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: DictationAccessibilityService? = null

        fun getInstance(): DictationAccessibilityService? {
            Log.d("DictationService", "Getting instance: ${instance != null}")
            return instance
        }
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var hasFocusedEditable = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DictationService", "Service connected and instance set")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    focusedNode = AccessibilityNodeInfo.obtain(source)
                    if (!hasFocusedEditable) {
                        hasFocusedEditable = true
                        FloatingButtonService.getInstance()?.onTextFieldFocused()
                        Log.d("DictationService", "Editable field focused: ${source.className}")
                    }
                } else {
                    if (hasFocusedEditable) {
                        hasFocusedEditable = false
                        FloatingButtonService.getInstance()?.onTextFieldFocusLost()
                        Log.d("DictationService", "Focus left editable field")
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Fires on significant navigation: home screen, app switch, new activity.
                // Ask Android directly whether an editable field is still active.
                if (hasFocusedEditable) {
                    val activeFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (activeFocus == null || !activeFocus.isEditable) {
                        hasFocusedEditable = false
                        FloatingButtonService.getInstance()?.onTextFieldFocusLost()
                        Log.d("DictationService", "Window changed, no editable focus — hiding button")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    fun pasteText(text: String) {
        Log.d("DictationService", "pasteText called with: $text")

        // 1. Always copy to clipboard as a reliable backup
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WhisperRemote", text)
            clipboard.setPrimaryClip(clip)
            Log.d("DictationService", "Copied to clipboard")
        } catch (e: Exception) {
            Log.e("DictationService", "Clipboard copy failed", e)
        }

        // 2. Try to find the currently focused editable field
        // Strategy: current focus -> tracked node -> root focus
        val targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: focusedNode
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        Log.d("DictationService", "Target node found: ${targetNode != null}")

        targetNode?.let { node ->
            if (node.isEditable) {
                // Try PASTE first (inserts at cursor)
                val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d("DictationService", "ACTION_PASTE result: $pasteSuccess")

                if (!pasteSuccess) {
                    // Fallback to SET_TEXT (replaces entire field content)
                    val arguments = Bundle()
                    arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    val setSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    Log.d("DictationService", "ACTION_SET_TEXT result: $setSuccess")
                }
            } else {
                Log.e("DictationService", "Node is not editable")
            }
        } ?: Log.e("DictationService", "No target node found for pasting")

        // Notify FloatingButtonService that paste is done
        FloatingButtonService.getInstance()?.onPasteComplete(hasFocusedEditable)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d("DictationService", "Service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}