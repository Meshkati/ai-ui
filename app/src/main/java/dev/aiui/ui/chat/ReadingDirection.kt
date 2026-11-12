package dev.aiui.ui.chat

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * The direction most of [text]'s words read in, so a Persian sentence that opens with an
 * English term still reads right to left. Null when no word has a letter to tell by.
 */
fun readingDirection(text: String): LayoutDirection? {
    val wordDirections = text.split(whitespace).mapNotNull { word -> word.firstNotNullOfOrNull(::strongDirection) }
    val rtlWords = wordDirections.count { it == LayoutDirection.Rtl }
    val ltrWords = wordDirections.size - rtlWords
    return when {
        rtlWords > ltrWords -> LayoutDirection.Rtl
        ltrWords > rtlWords -> LayoutDirection.Ltr
        else -> wordDirections.firstOrNull()
    }
}

/** [readingDirection] as a text direction, leaving text with no letters to the platform. */
fun textDirectionOf(text: String): TextDirection = when (readingDirection(text)) {
    LayoutDirection.Rtl -> TextDirection.Rtl
    LayoutDirection.Ltr -> TextDirection.Ltr
    null -> TextDirection.Content
}

fun LayoutDirection.asTextDirection(): TextDirection =
    if (this == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr

private val whitespace = Regex("\\s+")

private fun strongDirection(c: Char): LayoutDirection? = when (Character.getDirectionality(c)) {
    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> LayoutDirection.Ltr
    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> LayoutDirection.Rtl
    else -> null
}
