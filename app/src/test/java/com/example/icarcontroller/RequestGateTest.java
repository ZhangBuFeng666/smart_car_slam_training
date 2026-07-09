package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestGateTest {
    @Test
    public void rejectsSecondRequestUntilFirstFinishes() {
        RequestGate gate = new RequestGate();

        assertTrue(gate.tryBegin());
        assertFalse(gate.tryBegin());

        gate.finish();

        assertTrue(gate.tryBegin());
    }
}
