package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JarvisSpeechPreferencesTest {
    @Test
    public void autoSpeechUsesDedicatedDefaultOnPreference() {
        assertEquals("jarvis_speech", JarvisSpeechPreferences.PREFERENCE_FILE);
        assertEquals("auto_speech_enabled", JarvisSpeechPreferences.AUTO_SPEECH_KEY);
        assertTrue(JarvisSpeechPreferences.DEFAULT_ENABLED);
    }
}
