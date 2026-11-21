package com.emil.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread

/**
 * Mic in at 16 kHz, voice out at 24 kHz (Gemini Live formats).
 * VOICE_COMMUNICATION source + echo canceller lets you talk over JARVIS on the loudspeaker.
 */
class AudioIO(private val ctx: Context) {
    private val inRate = 16000
    private val outRate = 24000

    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    @Volatile private var running = false
    private var prevMode = AudioManager.MODE_NORMAL

    @Suppress("MissingPermission")
    fun start(onMic: (ByteArray) -> Unit) {
        if (running) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        val minIn = AudioRecord.getMinBufferSize(
            inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minIn * 2
        )
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        rec = r

        val minOut = AudioTrack.getMinBufferSize(
            outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(outRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(minOut * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }

        running = true
        r.startRecording()
        thread(name = "jarvis-mic", isDaemon = true) {
            val buf = ByteArray(1280) // 40 ms
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) onMic(buf.copyOf(n))
            }
        }
    }

    fun play(pcm: ByteArray) {
        track?.write(pcm, 0, pcm.size)
    }

    /** Barge-in: drop whatever is queued so JARVIS stops instantly. */
    fun flush() {
        track?.apply { pause(); flush(); play() }
    }

    fun stop() {
        running = false
        runCatching { rec?.stop() }
        rec?.release(); rec = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        runCatching { track?.stop() }
        track?.release(); track = null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = prevMode
    }
}
