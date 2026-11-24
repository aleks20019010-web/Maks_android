package com.maks.music.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor

object MaksColors {
    // Velvet Midnight Obsidian & Indigo Glass Palette (inspired by EQ Studio hardware UI)
    val SpaceBg = Color(0xFF0A0B13)
    val SpaceBgDeep = Color(0xFF06070D)
    val SpaceSurface = Color(0xFF121422)
    val SpaceSurfaceElevated = Color(0xFF181B2C)
    val SpaceSurfaceRecessed = Color(0xFF0E101B)
    val SpaceBorder = Color(0x18FFFFFF)
    val SpaceBorderGlow = Color(0x4D8B5CF6)

    // Signature Violet -> Sky-Cyan Luminous Accents
    val VioletAccent = Color(0xFF8B5CF6)
    val LavenderGlow = Color(0xFFA78BFA)
    val CyanAccent = Color(0xFF4FD1FF)
    val TealAccent = Color(0xFF2DD4BF)

    // Typography hierarchy
    val TextPrimary = Color(0xFFF5F7FF)
    val TextSecondary = Color(0xFFC5CCE2)
    val TextMuted = Color(0xFF77809E)

    val LikeRose = Color(0xFFFB7185)
    val WarningAmber = Color(0xFFF59E0B)
}

private val MaksDarkScheme = darkColorScheme(
    primary = MaksColors.VioletAccent,
    secondary = MaksColors.CyanAccent,
    tertiary = MaksColors.TealAccent,
    background = MaksColors.SpaceBg,
    surface = MaksColors.SpaceSurface,
    surfaceVariant = MaksColors.SpaceSurfaceElevated,
    onPrimary = Color(0xFFFFFFFF),
    onBackground = MaksColors.TextPrimary,
    onSurface = MaksColors.TextPrimary,
    onSurfaceVariant = MaksColors.TextMuted,
)

@Composable
fun MaksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaksDarkScheme,
        content = content
    )
}

/**
 * Извлекает сбалансированный акцентный цвет из обложки трека
 * (либо возвращает фирменный небесно-голубой акцент при отсутствии обложки).
 */
fun extractAccentColor(bitmap: Bitmap?): Color {
    if (bitmap == null || bitmap.isRecycled) return MaksColors.CyanAccent
    return runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 20, 20, false)
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        var wSum = 0f
        val hsv = FloatArray(3)

        for (y in 0 until scaled.height) {
            for (x in 0 until scaled.width) {
                val px = scaled.getPixel(x, y)
                AndroidColor.colorToHSV(px, hsv)
                val s = hsv[1]
                val v = hsv[2]
                val w = (0.2f + s * 1.8f) * (1f - kotlin.math.abs(v - 0.55f))
                rSum += AndroidColor.red(px) * w
                gSum += AndroidColor.green(px) * w
                bSum += AndroidColor.blue(px) * w
                wSum += w
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (wSum <= 0f) return MaksColors.CyanAccent

        val rgb = AndroidColor.rgb(
            (rSum / wSum).toInt().coerceIn(0, 255),
            (gSum / wSum).toInt().coerceIn(0, 255),
            (bSum / wSum).toInt().coerceIn(0, 255),
        )
        AndroidColor.colorToHSV(rgb, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.80f)
        hsv[2] = hsv[2].coerceIn(0.60f, 0.90f)
        Color(AndroidColor.HSVToColor(hsv))
    }.getOrDefault(MaksColors.CyanAccent)
}
