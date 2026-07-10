package com.example.icarcontroller

data class ParkingCanvasStyle(
    val mapBackground: String,
    val grid: String,
    val mapLane: String,
    val parkingSlot: String,
    val occupiedSlot: String,
    val mapWarning: String,
    val route: String,
    val routeHalo: String,
    val mapText: String,
    val mapMutedText: String,
    val checkpoint: String,
    val checkpointStroke: String,
    val robot: String,
    val visionSky: String,
    val visionRoad: String,
    val visionLane: String,
    val vehicle: String,
    val blueVehicle: String,
    val detection: String,
    val warning: String,
    val scan: String,
    val visionLabelBackground: String,
    val detectionLabelBackground: String,
    val warningLabelBackground: String,
    val warningLabelText: String,
    val visionLabelText: String
) {
    companion object {
        @JvmStatic
        fun from(palette: ParkingPalette): ParkingCanvasStyle =
            if (isDark(palette.background)) dark(palette) else light(palette)

        private fun light(palette: ParkingPalette) = ParkingCanvasStyle(
            mapBackground = palette.surface,
            grid = palette.border,
            mapLane = palette.textSecondary,
            parkingSlot = palette.border,
            occupiedSlot = palette.accentSoft,
            mapWarning = palette.danger,
            route = palette.accent,
            routeHalo = withAlpha(palette.accent, "30"),
            mapText = palette.textPrimary,
            mapMutedText = palette.textSecondary,
            checkpoint = palette.surface,
            checkpointStroke = palette.accent,
            robot = palette.accent,
            visionSky = palette.background,
            visionRoad = palette.surfaceAlt,
            visionLane = palette.textSecondary,
            vehicle = palette.textSecondary,
            blueVehicle = palette.accentSoft,
            detection = palette.accent,
            warning = palette.danger,
            scan = withAlpha(palette.accent, "96"),
            visionLabelBackground = palette.surface,
            detectionLabelBackground = palette.accentSoft,
            warningLabelBackground = palette.danger,
            warningLabelText = "#FFFFFF",
            visionLabelText = palette.textPrimary
        )

        private fun dark(palette: ParkingPalette) = ParkingCanvasStyle(
            mapBackground = "#0A0E0C",
            grid = "#242C28",
            mapLane = "#5B6660",
            parkingSlot = "#37413C",
            occupiedSlot = "#474D49",
            mapWarning = "#A64C3E",
            route = palette.accent,
            routeHalo = withAlpha(palette.accent, "30"),
            mapText = "#CDD6D1",
            mapMutedText = "#7E8B84",
            checkpoint = "#121714",
            checkpointStroke = palette.accent,
            robot = "#4FE1B6",
            visionSky = "#070A09",
            visionRoad = "#121714",
            visionLane = "#7A827D",
            vehicle = "#313935",
            blueVehicle = "#26465E",
            detection = "#4FE1B6",
            warning = "#E16B64",
            scan = withAlpha(palette.accent, "96"),
            visionLabelBackground = "#0A110E",
            detectionLabelBackground = "#0A271F",
            warningLabelBackground = "#67231F",
            warningLabelText = "#FFFFFF",
            visionLabelText = "#E5ECE8"
        )

        private fun withAlpha(color: String, alpha: String): String =
            "#$alpha${color.removePrefix("#")}".uppercase()

        private fun isDark(color: String): Boolean {
            val rgb = color.removePrefix("#").takeLast(6).toLong(16)
            val red = rgb shr 16 and 0xFF
            val green = rgb shr 8 and 0xFF
            val blue = rgb and 0xFF
            return red * 299 + green * 587 + blue * 114 < 128_000
        }
    }
}
