package com.example.icarcontroller

class JarvisLocalSafety(private val cancelLocalMotion: () -> Unit) {
    fun beforeControlTaskStop() = cancelLocalMotion()

    fun beforeEmergencyStop() = cancelLocalMotion()
}
