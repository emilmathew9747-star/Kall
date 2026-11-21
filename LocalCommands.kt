package com.emil.jarvis

import org.json.JSONObject

data class Local(val tool: String, val args: JSONObject, val say: (JSONObject) -> String)

/**
 * Simple commands handled on the phone: no internet, no API key, instant.
 * English, Hindi and Hinglish phrasing. Anything that does not match goes to Gemini.
 */
object LocalCommands {
    private val num = Regex("(\\d{1,3})")

    private fun has(t: String, vararg w: String) = w.any { t.contains(it) }

    fun match(raw: String): Local? {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null

        if (t in setOf("stop", "bas", "never mind", "nevermind", "ruko", "बस", "रुको")) {
            return Local("media_control", JSONObject().put("action", "pause")) { "Okay." }
        }

        if (has(t, "flashlight", "torch", "टॉर्च")) {
            val off = has(t, "off", "band", "bujha", "बंद")
            return Local("flashlight", JSONObject().put("on", !off)) { it.optString("message") }
        }

        if (has(t, "volume", "awaaz", "awaz", "आवाज")) {
            val a = JSONObject()
            val n = num.find(t)?.value?.toInt()
            when {
                has(t, "mute", "band", "chup") -> a.put("change", "mute")
                n != null -> a.put("percent", n)
                has(t, "badha", "increase", "up", "zyada", "बढ़ा") -> a.put("change", "up")
                has(t, "kam", "ghata", "decrease", "down", "lower", "कम") -> a.put("change", "down")
                else -> return null
            }
            return Local("set_volume", a) { it.optString("message") }
        }

        if (has(t, "gaana", "gana", "song", "music") || t in setOf("resume", "pause", "next", "play")) {
            val act = when {
                has(t, "agla", "next", "अगला") -> "next"
                has(t, "pichla", "previous", "back", "पिछला") -> "previous"
                has(t, "pause", "ruk", "roko", "band") -> "pause"
                has(t, "resume", "play", "chalu", "chala") -> "play"
                else -> return null
            }
            return Local("media_control", JSONObject().put("action", act)) { "Okay." }
        }

        if (has(t, "battery", "बैटरी")) return Local("get_battery", JSONObject()) { it.optString("message") }
        if (has(t, "kitne baje", "what time", "time kya", "समय", "date", "tareekh", "aaj ki date", "what day")) {
            return Local("get_time", JSONObject()) { it.optString("message") }
        }

        if (has(t, "timer")) {
            val n = num.find(t)?.value?.toIntOrNull() ?: return null
            val secs = when {
                has(t, "second", "sec") -> n
                has(t, "ghant", "hour") -> n * 3600
                else -> n * 60
            }
            return Local("set_timer", JSONObject().put("seconds", secs)) { it.optString("message") }
        }

        if (has(t, "alarm")) {
            val m = Regex("(\\d{1,2})(?::(\\d{2}))?").find(t) ?: return null
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (has(t, "pm", "shaam", "sham", "raat") && h < 12) h += 12
            return Local("set_alarm", JSONObject().put("hour", h).put("minute", min)) { it.optString("message") }
        }

        // "open YouTube", "YouTube kholo", "camera chalu karo"
        Regex("^(?:open|launch|start)\\s+(.+)$").find(t)?.let {
            return Local("open_app", JSONObject().put("app", it.groupValues[1])) { r -> r.optString("message") }
        }
        Regex("^(.+?)\\s+(?:kholo|khol do|khol|chalu karo|chalu kar do|open karo|start karo)$").find(t)?.let {
            return Local("open_app", JSONObject().put("app", it.groupValues[1])) { r -> r.optString("message") }
        }
        return null
    }
}
