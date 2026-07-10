package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VehicleMotionSpecTest {
    @Test
    public void threeDimensionalStageCompletesOneCalmTurnInEightSeconds() {
        assertEquals(8000, VehicleMotionSpec.autoRotationDurationMillis());
        assertTrue(VehicleMotionSpec.resumeDelayMillis() >= 700);
        assertTrue(VehicleMotionSpec.resumeDelayMillis() <= 1200);
    }

    @Test
    public void yawIsNormalizedWithoutMovingTheVehicleVertically() {
        assertEquals(5.0f, VehicleMotionSpec.normalizeYawDegrees(725f), 0.001f);
        assertEquals(350.0f, VehicleMotionSpec.normalizeYawDegrees(-10f), 0.001f);
        assertEquals(0.0f, VehicleMotionSpec.verticalTranslationDp(), 0.001f);
    }
}
