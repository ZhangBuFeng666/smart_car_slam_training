package com.example.icarcontroller

import android.content.Context
import android.content.SharedPreferences

class ParkingThemeStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences(ParkingThemeSpec.preferenceFile(), Context.MODE_PRIVATE)
    )

    fun load(): ParkingThemeMode = ParkingThemeSpec.fromStoredValue(
        preferences.getString(ParkingThemeSpec.preferenceKey(), null)
    )

    fun save(mode: ParkingThemeMode) {
        preferences.edit()
            .putString(ParkingThemeSpec.preferenceKey(), ParkingThemeSpec.storedValue(mode))
            .apply()
    }
}
