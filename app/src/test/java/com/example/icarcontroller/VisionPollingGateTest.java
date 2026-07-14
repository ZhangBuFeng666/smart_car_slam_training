package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VisionPollingGateTest {
    @Test
    public void onlyAllowsPollingBetweenStartAndStop() {
        VisionPollingGate gate = new VisionPollingGate();

        assertFalse(gate.isActive());
        gate.start();
        assertTrue(gate.isActive());
        gate.stop();
        assertFalse(gate.isActive());
    }

    @Test
    public void rejectsResponsesFromAnOlderPollingSession() {
        VisionPollingGate gate = new VisionPollingGate();
        long firstSession = gate.start();
        gate.stop();
        long secondSession = gate.start();

        assertFalse(gate.isActive(firstSession));
        assertTrue(gate.isActive(secondSession));
    }
}
