package dev.aiui.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AccentColorSchemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = max(a.luminance(), b.luminance()) to min(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun hueDistance(a: Double, b: Double): Double = abs(((a - b + 540) % 360) - 180)

    @Test
    fun everyAccentInLightMode_keepsTextReadable() {
        Accents.forEach { accent ->
            // when
            val colors = accentColorScheme(accent.seed, dark = false)

            // then
            assertTrue(accent.name, contrast(colors.primary, colors.onPrimary) >= 4.5f)
            assertTrue(accent.name, contrast(colors.primaryContainer, colors.onPrimaryContainer) >= 4.5f)
            assertTrue(accent.name, contrast(colors.surface, colors.onSurface) >= 4.5f)
            assertTrue(accent.name, contrast(colors.surface, colors.primary) >= 4.5f)
        }
    }

    @Test
    fun everyAccentInDarkMode_keepsTextReadable() {
        Accents.forEach { accent ->
            // when
            val colors = accentColorScheme(accent.seed, dark = true)

            // then
            assertTrue(accent.name, contrast(colors.primary, colors.onPrimary) >= 4.5f)
            assertTrue(accent.name, contrast(colors.primaryContainer, colors.onPrimaryContainer) >= 4.5f)
            assertTrue(accent.name, contrast(colors.surface, colors.onSurface) >= 4.5f)
            assertTrue(accent.name, contrast(colors.surface, colors.primary) >= 4.5f)
        }
    }

    @Test
    fun darkMode_hasDarkSurfacesAndLightAccent() {
        // given
        val blue = Accents.first { it.name == "Blue" }.seed

        // when
        val colors = accentColorScheme(blue, dark = true)

        // then
        assertTrue(colors.isDark)
        assertTrue(colors.primary.luminance() > colors.surface.luminance())
    }

    @Test
    fun lightMode_isNotDark() {
        // given
        val blue = Accents.first { it.name == "Blue" }.seed

        // when
        val colors = accentColorScheme(blue, dark = false)

        // then
        assertFalse(colors.isDark)
    }

    @Test
    fun anyAccent_keepsTheHueOfItsSeed() {
        Accents.forEach { accent ->
            // when
            val primary = accentColorScheme(accent.seed, dark = false).primary

            // then
            val distance = hueDistance(Lab.of(primary.toArgb()).hue, Lab.of(accent.seed).hue)
            assertTrue("${accent.name} is $distance degrees off", distance < 5)
        }
    }

    @Test
    fun differentAccents_giveDifferentPrimaries() {
        // when
        val primaries = Accents.map { accentColorScheme(it.seed, dark = false).primary }

        // then
        assertEquals(Accents.size, primaries.toSet().size)
    }

    @Test
    fun colourInsideSrgb_survivesTheRoundTripThroughLab() {
        // given
        val orange = 0xFFE8710A.toInt()

        // when
        val back = Lab.of(orange).toArgbOrNull()

        // then
        assertEquals(orange, back)
    }
}
