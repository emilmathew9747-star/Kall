package com.emil.jarvis

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

private const val PERSONA = """
You are JARVIS, a warm, quick, natural voice assistant on the user's Android phone.
Speak like a friendly person, not a robot: short sentences, no lists, no markdown, no reading out symbols.
Understand any language the user speaks, including Hindi, Malayalam, Tamil, Telugu, Kannada, Bengali, Marathi,
Gujarati, Punjabi, Urdu, English, Hinglish and mixed speech, and ALWAYS reply in the language the user just used.
Use the provided tools to act on the phone. Never say an action is done unless the tool result says ok.
Sending messages, calling, deleting and paying always need the user's tap, so say what you prepared and let them finish.
You cannot restart or power off the phone; offer the power menu instead.
Before tapping on screen, read the screen first. If unsure which contact or app, ask one short question.
"""

class JarvisVM(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val tools = PhoneTools(app)
    private val audio = AudioIO(app)
    private var live: GeminiLive? = null
    private var speaking = false

    private val tts: TextToSpeech = TextToSpeech(app) { }

    val orb = AppState.orb
    val status = AppState.status
    val recent = AppState.recent
    val activity = AppState.activity
    val confirm = AppState.confirm

    val isLive get() = live != null

    fun toggleLive() { if (live == null) startLive() else stopLive() }

    private fun startLive() {
        val key = prefs.apiKey
        if (key.isBlank()) { AppState.setStatus("Add your Gemini key in Settings."); return }
        AppState.setOrb(Orb.Connecting); AppState.setStatus("Connecting…")

        val cb = object : GeminiLive.Callbacks {
            override fun onReady() {
                AppState.setOrb(Orb.Listening); AppState.setStatus("Listening. Just talk.")
                audio.start { chunk ->
                    // With echo cancellation on, keep the mic open so you can talk over JARVIS.
                    if (!speaking || prefs.talkOver) live?.sendAudio(chunk)
                }
            }
            override fun onAudio(pcm24k: ByteArray) {
                if (!speaking) { speaking = true; AppState.setOrb(Orb.Speaking) }
                audio.play(pcm24k)
            }
            override fun onInterrupted() { audio.flush(); speaking = false; AppState.setOrb(Orb.Listening) }
            override fun onTurnComplete() { speaking = false; AppState.setOrb(Orb.Listening); AppState.setStatus("Listening. Just talk.") }
            override fun onToolCall(id: String, name: String, args: JSONObject) {
                AppState.setOrb(Orb.Thinking)
                viewModelScope.launch(Dispatchers.Default) {
                    val r = tools.run(name, args)
                    live?.sendToolResponse(id, name, r)
                }
            }
            override fun onUserText(t: String) { AppState.addRecent(t); AppState.setStatus(t) }
            override fun onModelText(t: String) {}
            override fun onClosed(reason: String?) {
                viewModelScope.launch { stopLive(reason?.let { "Disconnected: $it" } ?: "Standby") }
            }
        }
        live = GeminiLive(key, prefs.model, prefs.voice, PERSONA, PhoneTools.declarations(), cb).also { it.connect() }
    }

    fun stopLive(message: String = "Tap the mic to talk.") {
        audio.stop()
        live?.close(); live = null
        speaking = false
        AppState.setOrb(Orb.Standby); AppState.setStatus(message)
    }

    /** Typed text or a chip tap. Local commands first (instant, offline), then Gemini if a session is open. */
    fun command(text: String) {
        if (text.isBlank()) return
        AppState.addRecent(text)
        val local = if (prefs.localFirst) LocalCommands.match(text) else null
        if (local != null) {
            viewModelScope.launch(Dispatchers.Default) {
                val r = tools.run(local.tool, local.args)
                val say = if (r.optBoolean("ok")) local.say(r) else r.optString("message")
                AppState.setStatus(say)
                if (!isLive) speakLocal(say)
            }
        } else if (live?.ready == true) {
            live?.sendText(text)
        } else {
            AppState.setStatus("Tap the mic to start Gemini for that one.")
        }
    }

    /** Built-in Android TTS for local replies. Works offline, picks Hindi/Malayalam by script. */
    private fun speakLocal(text: String) {
        val loc = when {
            text.any { it in 'ऀ'..'ॿ' } -> Locale("hi", "IN")
            text.any { it in 'ഀ'..'ൿ' } -> Locale("ml", "IN")
            else -> Locale("en", "IN")
        }
        tts.language = loc
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "local")
    }

    override fun onCleared() { stopLive(); tts.shutdown() }
}
