package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

public class DriveSafetySpecTest {
    @Test
    public void activeMovementMustStopWhenPageOrLifecycleChanges() {
        assertTrue(DriveSafetySpec.shouldStop("front", DriveExitEvent.PAGE_CHANGE));
        assertTrue(DriveSafetySpec.shouldStop("left", DriveExitEvent.THEME_CHANGE));
        assertTrue(DriveSafetySpec.shouldStop("turn_right", DriveExitEvent.APP_PAUSE));
    }

    @Test
    public void idleStateDoesNotEmitAnExtraLifecycleStop() {
        assertFalse(DriveSafetySpec.shouldStop(null, DriveExitEvent.PAGE_CHANGE));
        assertFalse(DriveSafetySpec.shouldStop("", DriveExitEvent.APP_PAUSE));
    }

    @Test
    public void stopIsSentImmediatelyAndAgainAfterTheMoveQueue() {
        assertEquals(
                Arrays.asList(StopDispatchLane.URGENT, StopDispatchLane.MOVE_BARRIER),
                DriveSafetySpec.stopDispatchLanes()
        );
    }
}
