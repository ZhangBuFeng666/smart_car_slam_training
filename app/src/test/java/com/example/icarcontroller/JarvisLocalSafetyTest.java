package com.example.icarcontroller;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import org.junit.Test;

public class JarvisLocalSafetyTest {
    @Test
    public void everyRemoteStopFirstCancelsLocalMotionRepeater() {
        AtomicInteger cancellations = new AtomicInteger();
        JarvisLocalSafety safety = new JarvisLocalSafety(() -> {
            cancellations.incrementAndGet();
            return Unit.INSTANCE;
        });

        safety.beforeControlTaskStop();
        safety.beforeEmergencyStop();

        assertEquals(2, cancellations.get());
    }
}
