package com.example.icarcontroller

enum class ParkingThemeMode {
    LIGHT,
    DARK
}

data class ParkingPalette(
    val background: String,
    val surface: String,
    val surfaceAlt: String,
    val textPrimary: String,
    val textSecondary: String,
    val accent: String,
    val accentText: String,
    val accentSoft: String,
    val border: String,
    val danger: String
)

object ParkingThemeSpec {
    private val lightPalette = ParkingPalette(
        background = "#EEF8FD",
        surface = "#FFFFFF",
        surfaceAlt = "#F7FBFD",
        textPrimary = "#172027",
        textSecondary = "#58666F",
        accent = "#62B9E8",
        accentText = "#1477A8",
        accentSoft = "#DDF2FC",
        border = "#C9DBE5",
        danger = "#C83E4D"
    )

    private val darkPalette = ParkingPalette(
        background = "#080B0A",
        surface = "#111512",
        surfaceAlt = "#1A201C",
        textPrimary = "#F5F3EA",
        textSecondary = "#B8BDB7",
        accent = "#C6B57B",
        accentText = "#C6B57B",
        accentSoft = "#2E2B20",
        border = "#343B35",
        danger = "#E56B6F"
    )

    @JvmStatic
    fun defaultMode(): ParkingThemeMode = ParkingThemeMode.LIGHT

    @JvmStatic
    fun fromStoredValue(value: String?): ParkingThemeMode = when (value) {
        "dark" -> ParkingThemeMode.DARK
        else -> ParkingThemeMode.LIGHT
    }

    @JvmStatic
    fun storedValue(mode: ParkingThemeMode): String = when (mode) {
        ParkingThemeMode.LIGHT -> "light"
        ParkingThemeMode.DARK -> "dark"
    }

    @JvmStatic
    fun nextMode(mode: ParkingThemeMode): ParkingThemeMode = when (mode) {
        ParkingThemeMode.LIGHT -> ParkingThemeMode.DARK
        ParkingThemeMode.DARK -> ParkingThemeMode.LIGHT
    }

    @JvmStatic
    fun pageUsesSelectedTheme(pageKey: String): Boolean = pageKey in setOf(
        "home",
        "drive",
        "ai",
        "vision",
        "nav"
    )

    @JvmStatic
    fun palette(mode: ParkingThemeMode): ParkingPalette = when (mode) {
        ParkingThemeMode.LIGHT -> lightPalette
        ParkingThemeMode.DARK -> darkPalette
    }

    @JvmStatic
    fun preferenceFile(): String = "parking_ui"

    @JvmStatic
    fun preferenceKey(): String = "theme_mode"
}
