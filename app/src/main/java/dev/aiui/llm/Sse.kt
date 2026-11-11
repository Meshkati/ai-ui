package dev.aiui.llm

data class SseEvent(val event: String?, val data: String)

/** Line-by-line server-sent events parser. Feed every line without its line terminator. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Returns a complete event when [line] is the blank line that ends one. */
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Emits a trailing event when the stream ends without a final blank line. */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        val result = if (hasData) SseEvent(event, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return result
    }
}
