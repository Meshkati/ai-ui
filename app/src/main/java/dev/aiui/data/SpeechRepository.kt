package dev.aiui.data

import dev.aiui.llm.ApiFormat
import dev.aiui.llm.Endpoint
import dev.aiui.llm.ErrorKind
import dev.aiui.llm.LlmException
import dev.aiui.llm.OpenAiCompatAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import java.io.File

/** Only OpenAI-compatible servers have an audio API to send to. */
val ProfileWithModels.hasAudioApi: Boolean get() = ApiFormat.fromId(profile.format) == ApiFormat.OPENAI

/** The profile [choice] points at, while it still exists and has an audio API. */
fun List<ProfileWithModels>.audioProfileFor(choice: ModelChoice?): ProfileWithModels? =
    choice?.let { picked -> firstOrNull { it.profile.id == picked.profileId } }?.takeIf { it.hasAudioApi }

class SpeechRepository(
    private val profiles: ProfileRepository,
    private val prefs: PrefsRepository,
    private val openAi: OpenAiCompatAdapter,
) {
    suspend fun transcribe(audio: File): String {
        val (endpoint, model) = endpointFor(prefs.current().speechToText)
        return openAi.transcribe(endpoint, model, audio)
    }

    /** Audio for [text], one piece per chunk; the next piece is fetched while the previous one plays. */
    fun speak(text: String): Flow<ByteArray> = flow {
        val (endpoint, model) = endpointFor(prefs.current().textToSpeech)
        for (chunk in speechChunks(text)) emit(openAi.synthesizeSpeech(endpoint, model, chunk))
    }.buffer(1)

    private suspend fun endpointFor(choice: ModelChoice?): Pair<Endpoint, String> {
        val profile = choice?.let { profiles.get(it.profileId) }
            ?: throw LlmException(ErrorKind.NOT_FOUND, "Choose a profile for voice in Settings.")
        if (ApiFormat.fromId(profile.format) != ApiFormat.OPENAI) {
            throw LlmException(ErrorKind.BAD_REQUEST, "${profile.name} has no audio API. Choose an OpenAI-compatible profile in Settings.")
        }
        return profiles.endpointFor(profile) to choice.modelId
    }

    companion object {
        /** OpenAI's speech endpoint takes at most 4,096 characters per request. */
        const val MAX_SPEECH_CHARS = 4_000

        /** Splits [text] into pieces of at most [maxChars], at a sentence end where there is one, else between words. */
        fun speechChunks(text: String, maxChars: Int = MAX_SPEECH_CHARS): List<String> {
            val chunks = mutableListOf<String>()
            var rest = text.trim()
            while (rest.length > maxChars) {
                val cut = lastSentenceEnd(rest, maxChars)?.plus(1)
                    ?: rest.lastIndexOf(' ', maxChars).takeIf { it > 0 }
                    ?: maxChars
                chunks += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) chunks += rest
            return chunks
        }

        private fun lastSentenceEnd(text: String, maxChars: Int): Int? = (maxChars - 1 downTo 1).firstOrNull { i ->
            text[i] == '\n' || (text[i] in ".?!" && text[i + 1].isWhitespace())
        }
    }
}
