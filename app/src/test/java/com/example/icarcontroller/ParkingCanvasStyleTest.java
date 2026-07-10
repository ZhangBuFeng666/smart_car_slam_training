package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ParkingCanvasStyleTest {
    @Test
    public void lightPaletteProducesWhiteAndIceBlueCanvasSurfaces() {
        ParkingCanvasStyle style = ParkingCanvasStyle.from(
                ParkingThemeSpec.palette(ParkingThemeMode.LIGHT)
        );

        assertEquals("#FFFFFF", style.getMapBackground());
        assertEquals("#C9DBE5", style.getGrid());
        assertEquals("#62B9E8", style.getRoute());
        assertEquals("#FFFFFF", style.getCheckpoint());
        assertEquals("#EEF8FD", style.getVisionSky());
        assertEquals("#F7FBFD", style.getVisionRoad());
        assertEquals("#62B9E8", style.getDetection());
        assertEquals("#C83E4D", style.getWarning());
        assertEquals("#FFFFFF", style.getWarningLabelText());
    }

    @Test
    public void darkPalettePreservesTheExistingBlackGoldCanvasColors() {
        ParkingCanvasStyle style = ParkingCanvasStyle.from(
                ParkingThemeSpec.palette(ParkingThemeMode.DARK)
        );

        assertEquals("#0A0E0C", style.getMapBackground());
        assertEquals("#242C28", style.getGrid());
        assertEquals("#C6B57B", style.getRoute());
        assertEquals("#121714", style.getCheckpoint());
        assertEquals("#070A09", style.getVisionSky());
        assertEquals("#121714", style.getVisionRoad());
        assertEquals("#4FE1B6", style.getDetection());
        assertEquals("#E16B64", style.getWarning());
        assertEquals("#FFFFFF", style.getWarningLabelText());
    }

    @Test
    public void lightAndDarkPalettesProduceDistinctCanvasStyles() {
        ParkingCanvasStyle light = ParkingCanvasStyle.from(
                ParkingThemeSpec.palette(ParkingThemeMode.LIGHT)
        );
        ParkingCanvasStyle dark = ParkingCanvasStyle.from(
                ParkingThemeSpec.palette(ParkingThemeMode.DARK)
        );

        assertNotEquals(light, dark);
        assertNotEquals(light.getMapText(), light.getMapBackground());
        assertNotEquals(dark.getVisionLabelText(), dark.getVisionSky());
    }
}
