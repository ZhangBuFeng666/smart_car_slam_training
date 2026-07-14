package com.example.icarcontroller

import android.content.Context

class JarvisSpeechPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(AUTO_SPEECH_KEY, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(AUTO_SPEECH_KEY, enabled).apply()
    }

    companion object {
        const val PREFERENCE_FILE = "jarvis_speech"
        const val AUTO_SPEECH_KEY = "auto_speech_enabled"
        const val DEFAULT_ENABLED = true
    }
}
