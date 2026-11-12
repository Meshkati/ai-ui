package dev.aiui.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A colour a profile can pick. The chat's whole Material scheme is built from [seed]. */
data class Accent(val name: String, val seed: Int)

val Accents = listOf(
    Accent("Red", 0xFFD93025.toInt()),
    Accent("Orange", 0xFFE8710A.toInt()),
    Accent("Yellow", 0xFFF9AB00.toInt()),
    Accent("Green", 0xFF188038.toInt()),
    Accent("Blue", 0xFF1A73E8.toInt()),
    Accent("Purple", 0xFF9334E6.toInt()),
    Accent("Pink", 0xFFE52592.toInt()),
)

/** True when the scheme in use is a dark one, whichever way the theme setting got there. */
val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f

/**
 * Recolours [content] from [seed], in light or dark to match the surrounding theme.
 * A null seed keeps the surrounding colours. Both cases compose the same way, so
 * switching between them keeps the content's state.
 */
@Composable
fun AccentTheme(seed: Int?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.isDark
    val colors = remember(seed, dark, base) { seed?.let { accentColorScheme(it, dark) } ?: base }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * A Material 3 scheme from one seed colour, using the same tone steps as Material's
 * tonal palettes. Tone is CIELAB lightness, so every hue gets the same contrast.
 */
fun accentColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val hue = Lab.of(seed).hue
    val primary = Palette(hue, 48.0)
    val secondary = Palette(hue, 16.0)
    val tertiary = Palette(hue + 60, 32.0)
    val neutral = Palette(hue, 4.0)
    val variant = Palette(hue, 8.0)
    return if (dark) {
        darkColorScheme(
            primary = primary.tone(80), onPrimary = primary.tone(20),
            primaryContainer = primary.tone(30), onPrimaryContainer = primary.tone(90),
            inversePrimary = primary.tone(40),
            secondary = secondary.tone(80), onSecondary = secondary.tone(20),
            secondaryContainer = secondary.tone(30), onSecondaryContainer = secondary.tone(90),
            tertiary = tertiary.tone(80), onTertiary = tertiary.tone(20),
            tertiaryContainer = tertiary.tone(30), onTertiaryContainer = tertiary.tone(90),
            background = neutral.tone(6), onBackground = neutral.tone(90),
            surface = neutral.tone(6), onSurface = neutral.tone(90),
            surfaceVariant = variant.tone(30), onSurfaceVariant = variant.tone(80),
            surfaceTint = primary.tone(80),
            inverseSurface = neutral.tone(90), inverseOnSurface = neutral.tone(20),
            outline = variant.tone(60), outlineVariant = variant.tone(30),
            surfaceBright = neutral.tone(24), surfaceDim = neutral.tone(6),
            surfaceContainerLowest = neutral.tone(4), surfaceContainerLow = neutral.tone(10),
            surfaceContainer = neutral.tone(12), surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),
        )
    } else {
        lightColorScheme(
            primary = primary.tone(40), onPrimary = primary.tone(100),
            primaryContainer = primary.tone(90), onPrimaryContainer = primary.tone(10),
            inversePrimary = primary.tone(80),
            secondary = secondary.tone(40), onSecondary = secondary.tone(100),
            secondaryContainer = secondary.tone(90), onSecondaryContainer = secondary.tone(10),
            tertiary = tertiary.tone(40), onTertiary = tertiary.tone(100),
            tertiaryContainer = tertiary.tone(90), onTertiaryContainer = tertiary.tone(10),
            background = neutral.tone(98), onBackground = neutral.tone(10),
            surface = neutral.tone(98), onSurface = neutral.tone(10),
            surfaceVariant = variant.tone(90), onSurfaceVariant = variant.tone(30),
            surfaceTint = primary.tone(40),
            inverseSurface = neutral.tone(20), inverseOnSurface = neutral.tone(95),
            outline = variant.tone(50), outlineVariant = variant.tone(80),
            surfaceBright = neutral.tone(98), surfaceDim = neutral.tone(87),
            surfaceContainerLowest = neutral.tone(100), surfaceContainerLow = neutral.tone(96),
            surfaceContainer = neutral.tone(94), surfaceContainerHigh = neutral.tone(92),
            surfaceContainerHighest = neutral.tone(90),
        )
    }
}

/** One hue at a fixed chroma; tones that can't hold that chroma in sRGB get as much as fits. */
private class Palette(private val hue: Double, private val chroma: Double) {
    fun tone(tone: Int): Color {
        val l = tone.toDouble()
        Lab.fromLch(l, chroma, hue).toArgbOrNull()?.let { return Color(it) }
        var inGamut = 0.0
        var outOfGamut = chroma
        repeat(24) {
            val mid = (inGamut + outOfGamut) / 2
            if (Lab.fromLch(l, mid, hue).toArgbOrNull() != null) inGamut = mid else outOfGamut = mid
        }
        return Color(Lab.fromLch(l, inGamut, hue).toArgbOrNull()!!)
    }
}

/** CIELAB under D65. Its lightness is Material's "tone". */
internal data class Lab(val l: Double, val a: Double, val b: Double) {
    /** Hue angle in degrees, 0 until 360. */
    val hue: Double get() = (Math.toDegrees(atan2(b, a)) + 360) % 360
    val chroma: Double get() = sqrt(a * a + b * b)

    /** The sRGB colour, or null when this colour is outside sRGB. */
    fun toArgbOrNull(): Int? {
        val fy = (l + 16) / 116
        val x = WHITE_X * finv(fy + a / 500)
        val y = WHITE_Y * finv(fy)
        val z = WHITE_Z * finv(fy - b / 200)
        val linear = doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
        if (linear.any { it < -GAMUT_EPSILON || it > 1 + GAMUT_EPSILON }) return null
        val (r, g, bl) = linear.map { (delinearize(it.coerceIn(0.0, 1.0)) * 255).roundToInt() }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    companion object {
        fun fromLch(l: Double, chroma: Double, hue: Double): Lab {
            val radians = Math.toRadians(hue)
            return Lab(l, chroma * cos(radians), chroma * sin(radians))
        }

        fun of(argb: Int): Lab {
            val r = linearize((argb shr 16 and 0xFF) / 255.0)
            val g = linearize((argb shr 8 and 0xFF) / 255.0)
            val b = linearize((argb and 0xFF) / 255.0)
            val fx = f((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X)
            val fy = f((0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / WHITE_Y)
            val fz = f((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z)
            return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
        }

        private const val WHITE_X = 0.95047
        private const val WHITE_Y = 1.0
        private const val WHITE_Z = 1.08883
        private const val DELTA = 6.0 / 29
        private const val GAMUT_EPSILON = 1e-4

        private fun f(t: Double) = if (t > DELTA * DELTA * DELTA) cbrt(t) else t / (3 * DELTA * DELTA) + 4.0 / 29
        private fun finv(t: Double) = if (t > DELTA) t * t * t else 3 * DELTA * DELTA * (t - 4.0 / 29)
        private fun linearize(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        private fun delinearize(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
    }
}
