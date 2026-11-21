package com.emil.jarvis

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Streaming voice-to-voice session with the Gemini Live API (WebSocket).
 * Audio goes up as it is spoken and comes back as it is generated, which is
 * what makes replies feel instant and lets you interrupt mid-sentence.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val tools: JSONArray,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun onReady()
        fun onAudio(pcm24k: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onToolCall(id: String, name: String, args: JSONObject)
        fun onUserText(t: String)
        fun onModelText(t: String)
        fun onClosed(reason: String?)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile var ready = false
        private set

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val req = Request.Builder().url(url).header("x-goog-api-key", apiKey).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage().toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                val code = response?.code
                cb.onClosed(if (code == 400 || code == 401 || code == 403) "API key or model rejected ($code)" else t.message)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                cb.onClosed(reason.ifBlank { null })
            }
        })
    }

    private fun setupMessage(): JSONObject = JSONObject().put(
        "setup", JSONObject()
            .put("model", model)
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                            )
                        )
                    )
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("tools", tools)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
    )

    private fun handle(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (m.has("setupComplete")) { ready = true; cb.onReady(); return }

        m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                cb.onToolCall(c.optString("id"), c.getString("name"), c.optJSONObject("args") ?: JSONObject())
            }
        }

        val sc = m.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) cb.onInterrupted()
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let {
                    cb.onAudio(Base64.decode(it, Base64.DEFAULT))
                }
            }
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(cb::onUserText)
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let(cb::onModelText)
        if (sc.optBoolean("turnComplete")) cb.onTurnComplete()
    }

    fun sendAudio(pcm16k: ByteArray) {
        if (!ready) return
        val msg = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject()
                    .put("data", Base64.encodeToString(pcm16k, Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=16000")
            )
        )
        ws?.send(msg.toString())
    }

    fun sendText(text: String) {
        if (!ready) return
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        val fr = JSONObject().put("id", id).put("name", name).put("response", result)
        ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr))).toString())
    }

    fun close() {
        ready = false
        ws?.close(1000, "bye")
        ws = null
    }
}
