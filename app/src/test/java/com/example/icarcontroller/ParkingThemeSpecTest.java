package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ParkingThemeSpecTest {
    @Test
    public void lightModeIsTheFirstLaunchDefault() {
        assertEquals(ParkingThemeMode.LIGHT, ParkingThemeSpec.defaultMode());
    }

    @Test
    public void persistedThemeValuesRoundTripAndUnknownValuesFallBack() {
        assertEquals(ParkingThemeMode.LIGHT, ParkingThemeSpec.fromStoredValue("light"));
        assertEquals(ParkingThemeMode.DARK, ParkingThemeSpec.fromStoredValue("dark"));
        assertEquals(ParkingThemeMode.LIGHT, ParkingThemeSpec.fromStoredValue("unexpected"));
        assertEquals("dark", ParkingThemeSpec.storedValue(ParkingThemeMode.DARK));
    }

    @Test
    public void themeToggleAlternatesBetweenLightAndDark() {
        assertEquals(ParkingThemeMode.DARK, ParkingThemeSpec.nextMode(ParkingThemeMode.LIGHT));
        assertEquals(ParkingThemeMode.LIGHT, ParkingThemeSpec.nextMode(ParkingThemeMode.DARK));
    }

    @Test
    public void allPrimaryPagesUseTheSelectedTheme() {
        assertTrue(ParkingThemeSpec.pageUsesSelectedTheme("home"));
        assertTrue(ParkingThemeSpec.pageUsesSelectedTheme("drive"));
        assertTrue(ParkingThemeSpec.pageUsesSelectedTheme("ai"));
        assertTrue(ParkingThemeSpec.pageUsesSelectedTheme("vision"));
        assertTrue(ParkingThemeSpec.pageUsesSelectedTheme("nav"));
    }

    @Test
    public void palettesProvideBrightLightSurfacesAndBlackGoldDarkAccents() {
        ParkingPalette light = ParkingThemeSpec.palette(ParkingThemeMode.LIGHT);
        ParkingPalette dark = ParkingThemeSpec.palette(ParkingThemeMode.DARK);

        assertEquals("#FFFFFF", light.getSurface());
        assertEquals("#EEF8FD", light.getBackground());
        assertEquals("#62B9E8", light.getAccent());
        assertEquals("#1477A8", light.getAccentText());
        assertEquals("#080B0A", dark.getBackground());
        assertEquals("#C6B57B", dark.getAccent());
        assertEquals("#C6B57B", dark.getAccentText());
        assertNotEquals(light.getTextPrimary(), light.getSurface());
        assertNotEquals(dark.getTextPrimary(), dark.getSurface());
    }

    @Test
    public void preferencesUseStableNames() {
        assertEquals("parking_ui", ParkingThemeSpec.preferenceFile());
        assertEquals("theme_mode", ParkingThemeSpec.preferenceKey());
    }
}
