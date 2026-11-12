package dev.aiui.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStreamTest {

    /** Answers each request with the next scripted reply and remembers what it was asked. */
    private class ScriptedClient(vararg replies: List<StreamEvent>) : LlmClient {
        private val pending = ArrayDeque(replies.toList())
        private val failures = ArrayDeque<LlmException>()
        val requests = mutableListOf<LlmRequest>()

        fun failFirst(error: LlmException) = failures.addLast(error)

        override fun stream(endpoint: Endpoint, request: LlmRequest): Flow<StreamEvent> = flow {
            requests += request
            failures.removeFirstOrNull()?.let { throw it }
            pending.removeFirst().forEach { emit(it) }
        }

        override suspend fun listModels(endpoint: Endpoint): List<RemoteModel> = emptyList()
    }

    private val endpoint = Endpoint(ApiFormat.OPENAI, "http://localhost/v1", apiKey = null)
    private val rememberTool = LlmTool("remember", "Saves a fact.", JsonObject(emptyMap()))

    private fun request() = LlmRequest(
        model = "qwen3",
        system = null,
        messages = listOf(LlmMessage(Role.USER, listOf(LlmPart.Text("I live in Berlin")))),
        maxOutputTokens = 1_000,
        tools = listOf(rememberTool),
    )

    private fun rememberCall(id: String = "call_1") = ToolCall(id, "remember", buildJsonObject { put("text", "Lives in Berlin") })

    private fun callsTool(text: String = "", call: ToolCall = rememberCall()) =
        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { StreamEvent.TextDelta(it) }) +
            StreamEvent.Done("tool_calls", null, listOf(call))

    private fun answers(text: String) = listOf(StreamEvent.TextDelta(text), StreamEvent.Done("stop", null))

    @Test
    fun modelCallsTool_toolRunsAndModelIsAskedAgainWithTheResult() = runTest {
        // given
        val client = ScriptedClient(callsTool(), answers("Noted."))
        val ran = mutableListOf<ToolCall>()
        val reply = ReplyStream(client, endpoint) { call ->
            ran += call
            ToolOutput("Saved as #1")
        }

        // when
        reply.run(request()) {}

        // then
        assertEquals(listOf(rememberCall()), ran)
        val followUp = client.requests[1].messages
        assertEquals(LlmPart.ToolUse(rememberCall()), followUp[1].parts.single())
        assertEquals(LlmPart.ToolResult("call_1", "Saved as #1"), followUp[2].parts.single())
    }

    @Test
    fun textBeforeAndAfterToolCall_isOneReplyWithAParagraphBreak() = runTest {
        // given
        val client = ScriptedClient(callsTool(text = "I'll remember that."), answers("Anything else?"))
        val reply = ReplyStream(client, endpoint) { ToolOutput("Saved as #1") }

        // when
        reply.run(request()) {}

        // then
        assertEquals("I'll remember that.\n\nAnything else?", reply.text)
        assertTrue(reply.usedTools)
    }

    @Test
    fun modelKeepsCallingTools_stopsAfterFourRounds() = runTest {
        // given
        val client = ScriptedClient(*Array(10) { callsTool(call = rememberCall("call_$it")) })
        var runs = 0
        val reply = ReplyStream(client, endpoint) {
            runs++
            ToolOutput("Saved")
        }

        // when
        reply.run(request()) {}

        // then
        assertEquals(ReplyStream.MAX_TOOL_ROUNDS, runs)
        assertEquals(ReplyStream.MAX_TOOL_ROUNDS + 1, client.requests.size)
    }

    @Test
    fun serverRejectsTools_isAskedAgainWithoutThem() = runTest {
        // given
        val client = ScriptedClient(answers("Hi"))
        client.failFirst(LlmException(ErrorKind.BAD_REQUEST, "registry.ollama.ai/library/gemma3 does not support tools", 400))
        val reply = ReplyStream(client, endpoint) { ToolOutput("unused") }

        // when
        reply.run(request()) {}

        // then
        assertEquals("Hi", reply.text)
        assertTrue(client.requests[1].tools.isEmpty())
    }

    @Test
    fun toolThatThrows_givesTheModelAnErrorResult() = runTest {
        // given
        val client = ScriptedClient(callsTool(), answers("Sorry, that failed."))
        val reply = ReplyStream(client, endpoint) { error("disk full") }

        // when
        reply.run(request()) {}

        // then
        val result = client.requests[1].messages.last().parts.single() as LlmPart.ToolResult
        assertTrue(result.isError)
        assertTrue(result.content.contains("disk full"))
    }

    @Test
    fun outputTokensOfEveryRequest_addUp() = runTest {
        // given
        val client = ScriptedClient(
            listOf(StreamEvent.Usage(100, 20)) + callsTool(),
            listOf(StreamEvent.Usage(140, 5)) + answers("Noted."),
        )
        val reply = ReplyStream(client, endpoint) { ToolOutput("Saved") }

        // when
        reply.run(request()) {}

        // then
        assertEquals(25, reply.outputTokens)
        assertEquals(140, reply.inputTokens)
    }

    @Test
    fun replyWithoutToolCalls_isOneRequest() = runTest {
        // given
        val client = ScriptedClient(answers("Hello"))
        val reply = ReplyStream(client, endpoint) { ToolOutput("unused") }

        // when
        val done = reply.run(request()) {}

        // then
        assertEquals("stop", done?.stopReason)
        assertEquals(1, client.requests.size)
        assertFalse(reply.usedTools)
    }
}
