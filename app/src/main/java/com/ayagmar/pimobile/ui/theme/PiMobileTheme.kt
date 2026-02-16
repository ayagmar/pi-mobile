@file:Suppress("MagicNumber")

package com.ayagmar.pimobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PiLightColors =
    lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF3559E0),
        onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        secondary = androidx.compose.ui.graphics.Color(0xFF5F5B71),
        tertiary = androidx.compose.ui.graphics.Color(0xFF7A4D9A),
        error = androidx.compose.ui.graphics.Color(0xFFB3261E),
    )

private val PiDarkColors =
    darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFB8C3FF),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF00237A),
        secondary = androidx.compose.ui.graphics.Color(0xFFC9C4DD),
        tertiary = androidx.compose.ui.graphics.Color(0xFFE7B6FF),
        error = androidx.compose.ui.graphics.Color(0xFFF2B8B5),
    )

@Composable
fun PiMobileTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val useDarkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val colorScheme = if (useDarkTheme) PiDarkColors else PiLightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
