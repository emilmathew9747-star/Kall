package com.emil.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything JARVIS can do on the phone. The same code serves local commands and Gemini tool calls. */
class PhoneTools(private val ctx: Context) {

    private val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var torchOn = false

    private fun ok(msg: String) = JSONObject().put("ok", true).put("message", msg)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("message", msg)

    private fun launch(i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }

    suspend fun run(name: String, a: JSONObject): JSONObject {
        AppState.log(name + (a.optString("app").ifBlank { a.optString("query") }.let { if (it.isBlank()) "" else ": $it" }))
        return try { dispatch(name, a) } catch (e: Exception) { fail(e.message ?: "failed") }
    }

    private suspend fun dispatch(name: String, a: JSONObject): JSONObject = when (name) {
        "flashlight" -> {
            val on = a.optBoolean("on", !torchOn)
            val id = cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) fail("No flashlight on this phone") else {
                cam.setTorchMode(id, on); torchOn = on; ok(if (on) "Flashlight on" else "Flashlight off")
            }
        }
        "set_volume" -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = when {
                a.has("percent") -> a.getInt("percent").coerceIn(0, 100) * max / 100
                a.optString("change") == "up" -> (cur + maxOf(1, max / 5)).coerceAtMost(max)
                a.optString("change") == "down" -> (cur - maxOf(1, max / 5)).coerceAtLeast(0)
                a.optString("change") == "mute" -> 0
                else -> cur
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            ok("Volume ${target * 100 / max} percent")
        }
        "media_control" -> {
            val code = when (a.optString("action")) {
                "pause", "play", "resume", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> -1
            }
            if (code < 0) fail("Unknown media action") else {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                ok("Done")
            }
        }
        "open_app" -> openApp(a.getString("app"))
        "get_battery" -> {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            ok("Battery is ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent")
        }
        "get_time" -> ok(SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date()))
        "set_timer" -> {
            val s = a.getInt("seconds")
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (launch(i)) ok("Timer set for $s seconds") else fail("No clock app found")
        }
        "set_alarm" -> {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (launch(i)) ok("Alarm set") else fail("No clock app found")
        }
        "search_web" -> if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.getString("query")))))) ok("Opened search") else fail("No browser")
        "find_contact" -> findContact(a.getString("name"))
        "whatsapp_message" -> {
            val phone = a.getString("phone").filter { it.isDigit() }
            val u = Uri.parse("https://wa.me/$phone?text=" + Uri.encode(a.optString("text")))
            if (launch(Intent(Intent.ACTION_VIEW, u))) ok("Chat opened with the message typed. The person must tap Send.") else fail("WhatsApp not available")
        }
        "make_call" -> {
            val num = a.getString("phone")
            if (!AppState.ask("Call", num)) fail("The person said no")
            else if (launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))))) ok("Dialer opened. The person must tap Call.") else fail("No dialer")
        }
        "open_settings" -> openSettings(a.optString("page"))
        "system_button" -> needA11y { if (it.system(a.getString("action"))) ok("Done") else fail("Could not do that") }
        "read_screen" -> needA11y { ok(it.screenText().ifBlank { "Screen has no readable text" }) }
        "tap_text" -> needA11y { if (it.clickText(a.getString("text"))) ok("Tapped") else fail("Nothing with that text on screen") }
        "type_text" -> needA11y { if (it.typeText(a.getString("text"))) ok("Typed") else fail("No text field found") }
        "scroll" -> needA11y { if (it.scroll(a.optString("direction", "down") == "down")) ok("Scrolled") else fail("Nothing to scroll") }
        "list_files" -> {
            val dir = resolve(a.optString("path"))
            val items = dir.listFiles()?.sortedBy { it.name }?.take(60)?.joinToString("\n") { (if (it.isDirectory) "[dir] " else "") + it.name }
            if (items == null) fail("Cannot open that folder") else ok(items.ifBlank { "Empty folder" })
        }
        "read_file" -> {
            val f = resolve(a.getString("path"))
            if (!f.isFile) fail("Not a file") else ok(f.readText().take(8000))
        }
        "create_file" -> {
            val f = resolve(a.getString("path"))
            f.parentFile?.mkdirs(); f.writeText(a.optString("content")); ok("Created ${f.name}")
        }
        "delete_file" -> {
            val f = resolve(a.getString("path"))
            if (!f.exists()) fail("File not found")
            else if (!AppState.ask("Delete", f.absolutePath)) fail("The person said no")
            else if (f.deleteRecursively()) ok("Deleted ${f.name}") else fail("Could not delete")
        }
        "power_action" -> fail(
            "Normal apps cannot restart or switch off the phone. I can open the power menu with system_button power_menu."
        )
        else -> fail("Unknown tool $name")
    }

    private inline fun needA11y(block: (JarvisAccessibilityService) -> JSONObject): JSONObject {
        val s = JarvisAccessibilityService.instance
            ?: return fail("Phone control is off. Turn it on in JARVIS Settings > Phone control.")
        return block(s)
    }

    private fun openApp(query: String): JSONObject {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = query.lowercase().trim()
        val apps = pm.queryIntentActivities(main, 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
        val i = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        return if (i != null && launch(i)) ok("Opened ${hit.loadLabel(pm)}") else fail("No app called $query")
    }

    private fun openSettings(page: String): JSONObject {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (launch(Intent(action))) ok("Opened ${page.ifBlank { "Settings" }}. Toggling needs Phone control: use tap_text.") else fail("Cannot open")
    }

    private fun findContact(name: String): JSONObject {
        val out = JSONArray()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
        )?.use { c ->
            while (c.moveToNext() && out.length() < 5) {
                out.put(JSONObject().put("name", c.getString(0)).put("phone", c.getString(1)))
            }
        }
        return if (out.length() == 0) fail("No contact matches $name (or Contacts permission is off)")
        else ok("Matches").put("contacts", out)
    }

    /** Files live under shared storage when All files access is granted, else the app's own folder. */
    private fun resolve(path: String): File {
        val base = if (Environment.isExternalStorageManager()) Environment.getExternalStorageDirectory()
        else ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(base, path.trimStart('/'))
        require(f.canonicalPath.startsWith(base.canonicalPath)) { "Path outside allowed storage" }
        return f
    }

    companion object {
        private fun fn(name: String, desc: String, vararg props: Triple<String, String, String>, required: List<String> = props.map { it.first }): JSONObject {
            val p = JSONObject()
            props.forEach { p.put(it.first, JSONObject().put("type", it.second).put("description", it.third)) }
            return JSONObject().put("name", name).put("description", desc).apply {
                if (props.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", JSONArray(required)))
            }
        }
        private fun s(n: String, d: String) = Triple(n, "STRING", d)
        private fun i(n: String, d: String) = Triple(n, "INTEGER", d)
        private fun b(n: String, d: String) = Triple(n, "BOOLEAN", d)

        /** Tool list sent to Gemini in the session setup. */
        fun declarations(): JSONArray {
            val list = listOf(
                fn("flashlight", "Turn the flashlight on or off.", b("on", "true = on")),
                fn("set_volume", "Set or change media volume.", i("percent", "0-100"), s("change", "up, down or mute"), required = emptyList()),
                fn("media_control", "Control music playback.", s("action", "pause, play, next or previous")),
                fn("open_app", "Open an installed app by its name.", s("app", "App name, e.g. YouTube")),
                fn("get_battery", "Read battery percentage."),
                fn("get_time", "Read current date and time."),
                fn("set_timer", "Start a countdown timer.", i("seconds", "Length in seconds")),
                fn("set_alarm", "Set an alarm.", i("hour", "0-23"), i("minute", "0-59"), required = listOf("hour")),
                fn("search_web", "Search Google for something.", s("query", "Search text")),
                fn("find_contact", "Look up a contact's phone number by name. Ask the person if several match.", s("name", "Contact name")),
                fn("whatsapp_message", "Open a WhatsApp chat with the message typed in. The person taps Send.", s("phone", "Number with country code"), s("text", "Message"), required = listOf("phone", "text")),
                fn("make_call", "Open the dialer with a number after the person approves. The person taps Call.", s("phone", "Number")),
                fn("open_settings", "Open a Settings page: wifi, bluetooth, display, sound, battery, apps, location, airplane, storage.", s("page", "Page name")),
                fn("system_button", "Press a system control.", s("action", "back, home, recents, notifications, quick_settings, lock_screen or power_menu")),
                fn("read_screen", "Read the text currently on screen. Use before tapping or when asked what is on screen."),
                fn("tap_text", "Tap the on-screen element with this text.", s("text", "Label to tap")),
                fn("type_text", "Type into the focused text field.", s("text", "Text")),
                fn("scroll", "Scroll the current screen.", s("direction", "up or down")),
                fn("list_files", "List files in a folder.", s("path", "Folder path, empty for the root"), required = emptyList()),
                fn("read_file", "Read a text file.", s("path", "File path")),
                fn("create_file", "Create or overwrite a text file.", s("path", "File path"), s("content", "Text content")),
                fn("delete_file", "Delete a file or folder after the person approves.", s("path", "Path")),
                fn("power_action", "Use when asked to restart or switch off the phone."),
            )
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            return JSONArray().put(JSONObject().put("functionDeclarations", arr))
        }
    }
}
