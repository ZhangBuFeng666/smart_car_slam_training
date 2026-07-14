package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VisionOverlaySpecTest {
    @Test
    public void mapsNormalizedBoxAcrossCenterCrop() {
        VisionOverlayRect rect = VisionOverlaySpec.mapBox(
                new VisionBox(0.25, 0.25, 0.75, 0.75),
                1000,
                1000,
                640,
                480
        );

        assertEquals(166.67f, rect.getLeft(), 0.1f);
        assertEquals(250.0f, rect.getTop(), 0.1f);
        assertEquals(833.33f, rect.getRight(), 0.1f);
        assertEquals(750.0f, rect.getBottom(), 0.1f);
    }

    @Test
    public void returnsEmptyRectForInvalidDimensions() {
        VisionOverlayRect rect = VisionOverlaySpec.mapBox(
                new VisionBox(0.1, 0.1, 0.9, 0.9),
                0,
                1000,
                640,
                480
        );

        assertEquals(0f, rect.getLeft(), 0f);
        assertEquals(0f, rect.getTop(), 0f);
        assertEquals(0f, rect.getRight(), 0f);
        assertEquals(0f, rect.getBottom(), 0f);
    }
}
