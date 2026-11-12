package dev.aiui.ui.chat

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingDirectionTest {

    @Test
    fun persianText_readsRightToLeft() {
        // when
        val direction = readingDirection("سلام، حالت چطوره؟")

        // then
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun englishText_readsLeftToRight() {
        // when
        val direction = readingDirection("How are you today?")

        // then
        assertEquals(LayoutDirection.Ltr, direction)
    }

    @Test
    fun persianSentenceOpeningWithEnglishTerm_stillReadsRightToLeft() {
        // when
        val direction = readingDirection("Jetpack Compose یک ابزار مدرن برای ساخت رابط کاربری است")

        // then
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun englishSentenceQuotingPersianWord_stillReadsLeftToRight() {
        // when
        val direction = readingDirection("The word سلام means hello in Persian")

        // then
        assertEquals(LayoutDirection.Ltr, direction)
    }

    @Test
    fun evenlyMixedText_followsItsFirstWord() {
        // when
        val direction = readingDirection("مدل Opus")

        // then
        assertEquals(LayoutDirection.Rtl, direction)
    }

    @Test
    fun textWithoutLetters_hasNoDirection() {
        // when
        val direction = readingDirection("1. 2024 — 42%")

        // then
        assertNull(direction)
    }
}
