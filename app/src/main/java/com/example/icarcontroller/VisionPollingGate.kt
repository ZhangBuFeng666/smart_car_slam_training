package com.example.icarcontroller

class VisionPollingGate {
    @Volatile private var active = false
    @Volatile private var generation = 0L

    @Synchronized
    fun start(): Long {
        generation += 1
        active = true
        return generation
    }

    @Synchronized
    fun stop() {
        generation += 1
        active = false
    }

    fun isActive(): Boolean = active

    fun isActive(token: Long): Boolean = active && generation == token
}
