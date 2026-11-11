package dev.aiui.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun blankLine_completesEventWithItsNameAndData() {
        // given
        val parser = SseParser()
        parser.feed("event: content_block_delta")
        parser.feed("data: {\"x\":1}")

        // when
        val event = parser.feed("")

        // then
        assertEquals(SseEvent("content_block_delta", "{\"x\":1}"), event)
    }

    @Test
    fun multipleDataLines_areJoinedWithNewlines() {
        // given
        val parser = SseParser()
        parser.feed("data: first")
        parser.feed("data: second")

        // when
        val event = parser.feed("")

        // then
        assertEquals("first\nsecond", event?.data)
    }

    @Test
    fun commentLines_produceNoEvent() {
        // given
        val parser = SseParser()
        parser.feed(": keep-alive")

        // when
        val event = parser.feed("")

        // then
        assertNull(event)
    }

    @Test
    fun streamEndingWithoutBlankLine_stillEmitsLastEventOnFinish() {
        // given
        val parser = SseParser()
        parser.feed("data: [DONE]")

        // when
        val event = parser.finish()

        // then
        assertEquals("[DONE]", event?.data)
    }
}
