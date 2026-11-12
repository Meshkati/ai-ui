package dev.aiui.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What running one tool call gave back to the model. */
data class ToolOutput(val content: String, val isError: Boolean = false)

/**
 * Streams one assistant reply. When the model stops to call tools, they run and the model is
 * asked again with their results, up to [MAX_TOOL_ROUNDS] times; the text of every request
 * adds up to one reply. What arrived stays readable after a failure, so it can be kept.
 */
class ReplyStream(
    private val client: LlmClient,
    private val endpoint: Endpoint,
    private val runTool: suspend (ToolCall) -> ToolOutput,
) {
    private val textSoFar = StringBuilder()
    val text: String get() = textSoFar.toString()

    var inputTokens: Int? = null
        private set
    var outputTokens: Int? = null
        private set

    /** True once a tool ran; the finished reply's raw blocks then cover only its last request. */
    var usedTools = false
        private set

    suspend fun run(request: LlmRequest, onText: suspend (String) -> Unit): StreamEvent.Done? {
        var current = request
        var outputOfEarlierRequests = 0
        var toolRounds = 0
        while (true) {
            val roundText = StringBuilder()
            val startsAfterText = textSoFar.isNotEmpty()
            var roundOutput = 0
            var attempt = 0
            var done: StreamEvent.Done? = null
            while (true) {
                try {
                    client.stream(endpoint, current).collect { event ->
                        when (event) {
                            is StreamEvent.TextDelta -> {
                                if (roundText.isEmpty() && startsAfterText) textSoFar.append("\n\n")
                                roundText.append(event.text)
                                textSoFar.append(event.text)
                                onText(text)
                            }
                            is StreamEvent.Usage -> {
                                event.inputTokens?.let { inputTokens = it }
                                event.outputTokens?.let {
                                    roundOutput = it
                                    outputTokens = outputOfEarlierRequests + it
                                }
                            }
                            is StreamEvent.Done -> done = event
                        }
                    }
                    break
                } catch (e: LlmException) {
                    if (roundText.isNotEmpty()) throw e
                    if (!usedTools && endpoint.format == ApiFormat.ANTHROPIC && e.isThinkingSignatureError &&
                        current.messages.any { it.rawAnthropicBlocks != null }
                    ) {
                        current = current.withoutRawBlocks()
                        continue
                    }
                    if (!usedTools && current.tools.isNotEmpty() && e.isToolsRejected) {
                        current = current.copy(tools = emptyList())
                        continue
                    }
                    if (e.kind.retryable && attempt < MAX_RETRIES) {
                        attempt++
                        delay(((e.retryAfterSeconds ?: (attempt * 2L - 1)) * 1000).coerceAtMost(30_000))
                        continue
                    }
                    throw e
                }
            }
            outputOfEarlierRequests += roundOutput
            val calls = done?.toolCalls.orEmpty()
            if (calls.isEmpty() || toolRounds == MAX_TOOL_ROUNDS) return done
            toolRounds++
            usedTools = true
            val results = calls.map { call ->
                val output = runSafely(call)
                LlmPart.ToolResult(call.id, output.content, output.isError)
            }
            current = current.copy(
                messages = current.messages +
                    LlmMessage(
                        Role.ASSISTANT,
                        listOfNotNull(roundText.takeIf { it.isNotBlank() }?.let { LlmPart.Text(it.toString()) }) +
                            calls.map { LlmPart.ToolUse(it) },
                        done?.rawBlocks,
                    ) +
                    LlmMessage(Role.USER, results),
            )
        }
    }

    private suspend fun runSafely(call: ToolCall): ToolOutput = try {
        runTool(call)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutput("Error: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 4
        private const val MAX_RETRIES = 2
    }
}
