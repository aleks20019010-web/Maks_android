package com.maks.music.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor

object MaksColors {
    // Deep OLED Obsidian & High-Contrast Indigo Glass Palette
    val SpaceBg = Color(0xFF05060A)
    val SpaceBgDeep = Color(0xFF000000)
    val SpaceSurface = Color(0xFF0D0F1A)
    val SpaceSurfaceElevated = Color(0xFF151829)
    val SpaceSurfaceRecessed = Color(0xFF080A12)
    val SpaceBorder = Color(0x26FFFFFF)
    val SpaceBorderGlow = Color(0x7A9353FF)

    // Vivid Violet -> Electric Cyan Luminous Accents
    val VioletAccent = Color(0xFF9353FF)
    val LavenderGlow = Color(0xFFBA94FF)
    val CyanAccent = Color(0xFF00E5FF)
    val TealAccent = Color(0xFF00F5D4)

    // High-Contrast Typography Hierarchy
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFDCE2F5)
    val TextMuted = Color(0xFF94A0C4)

    val LikeRose = Color(0xFFFF3366)
    val WarningAmber = Color(0xFFFFB000)
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
 * Извлекает сочный акцентный цвет из обложки трека
 * (либо возвращает фирменный неоново-голубой акцент при отсутствии обложки).
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
                val w = (0.2f + s * 2.0f) * (1f - kotlin.math.abs(v - 0.55f))
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
        hsv[1] = hsv[1].coerceIn(0.65f, 1.0f)
        hsv[2] = hsv[2].coerceIn(0.75f, 1.0f)
        Color(AndroidColor.HSVToColor(hsv))
    }.getOrDefault(MaksColors.CyanAccent)
}