package com.ayagmar.pimobile.ui.theme

enum class ThemePreference(
    val value: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromValue(value: String?): ThemePreference {
            return entries.firstOrNull { preference -> preference.value == value } ?: SYSTEM
        }
    }
}
