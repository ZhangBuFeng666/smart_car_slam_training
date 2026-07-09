package com.example.icarcontroller

import java.util.concurrent.atomic.AtomicBoolean

class RequestGate {
    private val busy = AtomicBoolean(false)

    fun tryBegin(): Boolean = busy.compareAndSet(false, true)

    fun finish() {
        busy.set(false)
    }
}
