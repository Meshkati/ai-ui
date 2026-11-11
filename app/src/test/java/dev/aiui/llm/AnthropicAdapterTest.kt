package dev.aiui.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicAdapterTest {
    private val server = MockWebServer()
    private val adapter = AnthropicAdapter(buildHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun endpoint() = Endpoint(ApiFormat.ANTHROPIC, server.url("/").toString(), apiKey = "sk-test")

    private fun request(vararg messages: LlmMessage) = LlmRequest(
        model = "claude-opus-5-5",
        system = "Be brief.",
        messages = messages.toList().ifEmpty { listOf(LlmMessage(Role.USER, listOf(LlmPart.Text("Hi")))) },
        maxOutputTokens = 16_000,
    )

    private fun sse(vararg events: Pair<String, String>) = MockResponse.Builder()
        .addHeader("content-type", "text/event-stream")
        .body(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })
        .build()

    private val thinkingReply = arrayOf(
        "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "ping" to """{"type":"ping"}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" there"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun streamedReply_emitsTextDeltasInOrderThenDoneWithStopReason() = runBlocking {
        // given
        server.enqueue(sse(*thinkingReply))

        // when
        val events = adapter.stream(endpoint(), request()).toList()

        // then
        val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello there", text)
        assertEquals("end_turn", (events.last() as StreamEvent.Done).stopReason)
    }

    @Test
    fun finishedReply_keepsThinkingBlockAndSignatureForReplay() = runBlocking {
        // given
        server.enqueue(sse(*thinkingReply))

        // when
        val done = adapter.stream(endpoint(), request()).toList().last() as StreamEvent.Done

        // then
        val blocks = done.rawBlocks!!
        assertEquals("thinking", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-abc", blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("Hello there", blocks[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun request_carriesKeyVersionAndOnlyPortableFields() = runBlocking {
        // given
        server.enqueue(sse(*thinkingReply))

        // when
        adapter.stream(endpoint(), request()).toList()

        // then
        val recorded = server.takeRequest()
        val body = LenientJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("sk-test", recorded.headers["x-api-key"])
        assertEquals("2026-10-21", recorded.headers["anthropic-version"])
        assertEquals("Be brief.", body["system"]!!.jsonPrimitive.content)
        assertEquals("16000", body["max_tokens"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun assistantTurnWithRawBlocks_isSentBackVerbatim() {
        // given
        val raw = buildJsonArray {
            add(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "sig-abc") })
            add(buildJsonObject { put("type", "text"); put("text", "Hello there") })
        }
        val history = request(
            LlmMessage(Role.USER, listOf(LlmPart.Text("Hi"))),
            LlmMessage(Role.ASSISTANT, listOf(LlmPart.Text("Hello there")), raw),
            LlmMessage(Role.USER, listOf(LlmPart.Text("And again"))),
        )

        // when
        val body = AnthropicAdapter.buildBody(history, JsonObject(emptyMap()))

        // then
        val assistant = body["messages"]!!.jsonArray[1].jsonObject
        assertEquals(raw, assistant["content"])
    }

    @Test
    fun errorEventMidStream_failsWithTheServersMessage() = runBlocking {
        // given
        server.enqueue(
            sse(
                "message_start" to """{"type":"message_start","message":{}}""",
                "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            ),
        )

        // when
        val error = runCatching { adapter.stream(endpoint(), request()).toList() }.exceptionOrNull()

        // then
        assertTrue(error is LlmException && error.kind == ErrorKind.SERVER && error.message!!.contains("Overloaded"))
    }

    @Test
    fun rejectedKey_failsAsAuthErrorWithTheServersMessage() = runBlocking {
        // given
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
                .build(),
        )

        // when
        val error = runCatching { adapter.stream(endpoint(), request()).toList() }.exceptionOrNull() as? LlmException

        // then
        assertEquals(ErrorKind.AUTH, error?.kind)
        assertTrue(error!!.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun connectionClosingBeforeMessageStop_isAStreamError() = runBlocking {
        // given
        server.enqueue(sse(*thinkingReply.copyOfRange(0, 7)))

        // when
        val error = runCatching { adapter.stream(endpoint(), request()).toList() }.exceptionOrNull() as? LlmException

        // then
        assertEquals(ErrorKind.STREAM, error?.kind)
    }

    @Test
    fun baseUrlEndingInV1_isNotDoubled() {
        // when
        val url = AnthropicAdapter.messagesUrl("https://gateway.example.com/v1/")

        // then
        assertEquals("https://gateway.example.com/v1/messages", url)
    }

    @Test
    fun modelList_isReadWithDisplayNamesAndOutputLimits() = runBlocking {
        // given
        server.enqueue(
            MockResponse.Builder()
                .body("""{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_tokens":128000}],"has_more":false}""")
                .build(),
        )

        // when
        val models = adapter.listModels(endpoint())

        // then
        assertEquals(listOf(RemoteModel("claude-opus-5-5", "Claude Opus 5.5", 128_000)), models)
    }
}
