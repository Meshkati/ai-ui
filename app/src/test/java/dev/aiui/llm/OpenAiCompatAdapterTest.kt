package dev.aiui.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OpenAiCompatAdapterTest {
    private val server = MockWebServer()
    private val adapter = OpenAiCompatAdapter(buildHttpClient())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun endpoint(key: String? = "sk-local") =
        Endpoint(ApiFormat.OPENAI, server.url("/v1").toString(), apiKey = key)

    private fun request() = LlmRequest(
        model = "qwen2.5-coder:14b",
        system = "Be brief.",
        messages = listOf(LlmMessage(Role.USER, listOf(LlmPart.Text("Hi")))),
        maxOutputTokens = 16_000,
    )

    private fun chunks(vararg data: String) = MockResponse.Builder()
        .addHeader("content-type", "text/event-stream")
        .body(data.joinToString("") { "data: $it\n\n" })
        .build()

    @Test
    fun streamedChunks_emitTextThenDoneWithFinishReason() = runBlocking {
        // given
        server.enqueue(
            chunks(
                """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )

        // when
        val events = adapter.stream(endpoint(), request()).toList()

        // then
        assertEquals("Hello", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("stop", (events.last() as StreamEvent.Done).stopReason)
    }

    @Test
    fun serverThatOmitsDone_stillCompletesAfterFinishReason() = runBlocking {
        // given
        server.enqueue(chunks("""{"choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}]}"""))

        // when
        val last = adapter.stream(endpoint(), request()).toList().last()

        // then
        assertEquals(StreamEvent.Done("stop", null), last)
    }

    @Test
    fun request_usesBearerKeyAndPutsSystemPromptFirst() = runBlocking {
        // given
        server.enqueue(chunks("[DONE]"))

        // when
        adapter.stream(endpoint(), request()).toList()

        // then
        val recorded = server.takeRequest()
        val messages = LenientJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject["messages"]!!.jsonArray
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-local", recorded.headers["Authorization"])
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun localServerWithoutKey_sendsNoAuthorizationHeader() = runBlocking {
        // given
        server.enqueue(chunks("[DONE]"))

        // when
        adapter.stream(endpoint(key = null), request()).toList()

        // then
        assertEquals(null, server.takeRequest().headers["Authorization"])
    }

    @Test
    fun imageAttachment_isSentAsDataUrl() {
        // given
        val withImage = request().copy(
            messages = listOf(LlmMessage(Role.USER, listOf(LlmPart.Image("image/jpeg", "QUJD"), LlmPart.Text("What is this?")))),
        )

        // when
        val body = OpenAiCompatAdapter.buildBody(withImage, JsonObject(emptyMap()))

        // then
        val parts = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        val url = parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,QUJD", url)
    }

    @Test
    fun toolCallSplitAcrossChunks_endsWithOneCallAndParsedArguments() = runBlocking {
        // given
        server.enqueue(
            chunks(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"remember","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Vegetarian\"}"}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )

        // when
        val done = adapter.stream(endpoint(), request()).toList().last() as StreamEvent.Done

        // then
        val call = done.toolCalls.single()
        assertEquals("call_9", call.id)
        assertEquals("remember", call.name)
        assertEquals("Vegetarian", call.arguments["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestWithTools_sendsThemAsFunctions() {
        // given
        val schema = buildJsonObject { put("type", "object") }
        val withTools = request().copy(tools = listOf(LlmTool("remember", "Saves a fact.", schema)))

        // when
        val body = OpenAiCompatAdapter.buildBody(withTools, JsonObject(emptyMap()))

        // then
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("remember", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(schema, tool["function"]!!.jsonObject["parameters"])
    }

    @Test
    fun toolCallAndItsResult_areSentAsToolCallsAndAToolMessage() {
        // given
        val call = ToolCall("call_9", "forget", buildJsonObject { put("id", 3) })
        val history = request().copy(
            messages = listOf(
                LlmMessage(Role.USER, listOf(LlmPart.Text("Forget my city"))),
                LlmMessage(Role.ASSISTANT, listOf(LlmPart.Text("Sure."), LlmPart.ToolUse(call))),
                LlmMessage(Role.USER, listOf(LlmPart.ToolResult("call_9", "Forgotten"))),
            ),
        )

        // when
        val messages = OpenAiCompatAdapter.buildBody(history, JsonObject(emptyMap()))["messages"]!!.jsonArray

        // then
        val assistant = messages[2].jsonObject
        val sentCall = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        val result = messages[3].jsonObject
        assertEquals("Sure.", assistant["content"]!!.jsonPrimitive.content)
        assertEquals("""{"id":3}""", sentCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("tool", result["role"]!!.jsonPrimitive.content)
        assertEquals("call_9", result["tool_call_id"]!!.jsonPrimitive.content)
    }
}
