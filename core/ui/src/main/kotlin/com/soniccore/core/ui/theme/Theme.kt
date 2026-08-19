package com.soniccore.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.soniccore.core.model.settings.AccentPalette
import com.soniccore.core.model.settings.ThemeMode

/** Semantic colours the audio UI needs beyond the Material roles. */
data class SonicColors(
    val spectrumLow: Color,
    val spectrumMid: Color,
    val spectrumHigh: Color,
    val spectrumPeak: Color,
    val curveStroke: Color,
    val curveFill: Color,
    val gridLine: Color,
    val clipWarning: Color,
    val levelSafe: Color,
    val levelCaution: Color,
    val levelDanger: Color,
    val transportBluetooth: Color,
    val transportUsb: Color,
    val transportAnalog: Color,
    val transportWifi: Color,
    val transportBuiltin: Color,
    val batteryGood: Color,
    val batteryLow: Color,
    val batteryCritical: Color,
    val activeGlow: Color,
)

private val DarkSonicColors = SonicColors(
    spectrumLow = Color(0xFF4F8CFF),
    spectrumMid = Color(0xFF9B5DE5),
    spectrumHigh = Color(0xFFF15BB5),
    spectrumPeak = Color(0xFFFFD166),
    curveStroke = Color(0xFF6FD3FF),
    curveFill = Color(0x336FD3FF),
    gridLine = Color(0x1FFFFFFF),
    clipWarning = Color(0xFFEF476F),
    levelSafe = Color(0xFF06D6A0),
    levelCaution = Color(0xFFFFD166),
    levelDanger = Color(0xFFEF476F),
    transportBluetooth = Color(0xFF4F8CFF),
    transportUsb = Color(0xFF06D6A0),
    transportAnalog = Color(0xFFFFD166),
    transportWifi = Color(0xFF9B5DE5),
    transportBuiltin = Color(0xFF8D99AE),
    batteryGood = Color(0xFF06D6A0),
    batteryLow = Color(0xFFFFD166),
    batteryCritical = Color(0xFFEF476F),
    activeGlow = Color(0xFF4F8CFF),
)

private val LightSonicColors = DarkSonicColors.copy(
    gridLine = Color(0x14000000),
    curveFill = Color(0x332E7BD1),
    curveStroke = Color(0xFF1B6BB5),
    spectrumLow = Color(0xFF2E7BD1),
    spectrumMid = Color(0xFF7B3FC4),
    spectrumHigh = Color(0xFFC43F91),
)

val LocalSonicColors = staticCompositionLocalOf { DarkSonicColors }

private fun paletteScheme(palette: AccentPalette, dark: Boolean): ColorScheme {
    val seed = when (palette) {
        AccentPalette.SONIC_BLUE, AccentPalette.DYNAMIC -> Color(0xFF4F8CFF)
        AccentPalette.VIOLET -> Color(0xFF9B5DE5)
        AccentPalette.EMERALD -> Color(0xFF06D6A0)
        AccentPalette.AMBER -> Color(0xFFFFB703)
        AccentPalette.CRIMSON -> Color(0xFFEF476F)
        AccentPalette.MONOCHROME -> Color(0xFF8D99AE)
    }
    return if (dark) {
        darkColorScheme(
            primary = seed,
            onPrimary = Color.Black,
            primaryContainer = seed.copy(alpha = 0.28f).compositeOverBlack(),
            secondary = seed.copy(alpha = 0.75f).compositeOverBlack(),
            background = Color(0xFF0B0E14),
            surface = Color(0xFF11151F),
            surfaceVariant = Color(0xFF1A1F2B),
            onSurfaceVariant = Color(0xFFB6BECC),
            outline = Color(0xFF2A3140),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            background = Color(0xFFF7F9FC),
            surface = Color.White,
            surfaceVariant = Color(0xFFEDF1F7),
            outline = Color(0xFFCBD3E0),
        )
    }
}

private fun Color.compositeOverBlack(): Color =
    Color(red * alpha, green * alpha, blue * alpha, 1f)

/** True-black scheme for AMOLED panels — real power saving, not just a dark grey. */
private fun amoledScheme(palette: AccentPalette): ColorScheme =
    paletteScheme(palette, dark = true).copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF080808),
        surfaceContainer = Color(0xFF0C0C0C),
        surfaceVariant = Color(0xFF141414),
        outline = Color(0xFF262626),
    )

@Composable
fun SonicCoreTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentPalette: AccentPalette = AccentPalette.DYNAMIC,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        themeMode == ThemeMode.AMOLED -> amoledScheme(accentPalette)
        useDynamicColor && supportsDynamic && accentPalette == AccentPalette.DYNAMIC ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> paletteScheme(accentPalette, isDark)
    }

    CompositionLocalProvider(
        LocalSonicColors provides if (isDark) DarkSonicColors else LightSonicColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SonicTypography,
            shapes = SonicShapes,
            content = content,
        )
    }
}
