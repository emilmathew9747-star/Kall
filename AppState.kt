package com.emil.jarvis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Orb(val title: String) {
    Standby("Standby"), Connecting("Connecting"), Listening("Listening"),
    Thinking("Thinking"), Speaking("Speaking")
}

data class Confirm(val title: String, val detail: String, val answer: CompletableDeferred<Boolean>)

data class ActivityItem(val time: String, val text: String)

/** In-memory only. Activity is cleared when the app closes. */
object AppState {
    private val _orb = MutableStateFlow(Orb.Standby)
    val orb: StateFlow<Orb> = _orb.asStateFlow()

    private val _status = MutableStateFlow("Tap the mic to talk.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val _activity = MutableStateFlow<List<ActivityItem>>(emptyList())
    val activity: StateFlow<List<ActivityItem>> = _activity.asStateFlow()

    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm.asStateFlow()

    fun setOrb(o: Orb) { _orb.value = o }
    fun setStatus(s: String) { _status.value = s }

    fun addRecent(s: String) { _recent.value = (listOf(s) + _recent.value).take(20) }

    fun log(what: String) {
        val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        _activity.value = (listOf(ActivityItem(t, what)) + _activity.value).take(100)
    }

    /** Send, call, delete and pay all wait here for the person's tap. */
    suspend fun ask(title: String, detail: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        _confirm.value = Confirm(title, detail, d)
        val ok = d.await()
        _confirm.value = null
        return ok
    }
}
