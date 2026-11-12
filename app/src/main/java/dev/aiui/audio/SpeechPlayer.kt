package dev.aiui.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plays synthesized speech, holding transient audio focus so music ducks or pauses meanwhile. */
class SpeechPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    fun acquireFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    fun releaseFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    /** Plays [audio] to the end. Cancelling stops playback at once. */
    suspend fun play(audio: ByteArray) {
        val player = MediaPlayer()
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { player.stop() } }
                player.setAudioAttributes(attributes)
                player.setDataSource(ByteArraySource(audio))
                player.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Couldn't play the audio ($what, $extra)."))
                    true
                }
                player.setOnPreparedListener { it.start() }
                player.prepareAsync()
            }
        } finally {
            player.release()
        }
    }

    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = bytes.size.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun close() = Unit
    }
}
