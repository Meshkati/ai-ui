package dev.aiui.audio

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Records the microphone to a small AAC file, the format transcription servers commonly accept. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        stopAndDiscard()
        val output = File(File(context.cacheDir, "voice").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.m4a")
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(output)
            prepare()
            start()
        }
        file = output
    }

    /** The finished recording, or null when nothing usable was recorded. The caller deletes the file. */
    fun stop(): File? {
        val active = recorder ?: return null
        val output = file
        recorder = null
        file = null
        // stop() throws when it is called before any audio was captured.
        val ok = runCatching { active.stop() }.isSuccess
        active.release()
        if (!ok) output?.delete()
        return output.takeIf { ok }
    }

    fun stopAndDiscard() {
        stop()?.delete()
    }
}
