package com.emil.jarvis

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0B0F17)
private val Card = Color(0xFF161B26)
private val Cyan = Color(0xFF4DD8F0)
private val Muted = Color(0xFF8A94A8)

class MainActivity : ComponentActivity() {
    private val vm: JarvisVM by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Card, onBackground = Color.White, onSurface = Color.White)) {
                JarvisApp(vm)
            }
        }
    }
}

@Composable
fun JarvisApp(vm: JarvisVM) {
    var tab by remember { mutableIntStateOf(0) }
    val confirm by vm.confirm.collectAsState()

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Card) {
                listOf(
                    Triple("Home", Icons.Filled.Home, 0),
                    Triple("Automations", Icons.Filled.Star, 1),
                    Triple("Activity", Icons.AutoMirrored.Filled.List, 2),
                    Triple("Settings", Icons.Filled.Settings, 3),
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm)
                1 -> AutomationsScreen()
                2 -> ActivityScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { c.answer.complete(false) },
            title = { Text(c.title) },
            text = { Text(c.detail) },
            confirmButton = { TextButton({ c.answer.complete(true) }) { Text("Allow") } },
            dismissButton = { TextButton({ c.answer.complete(false) }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Orb(state: Orb) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        1f, if (state == Orb.Standby) 1.03f else 1.14f,
        infiniteRepeatable(tween(if (state == Orb.Speaking) 500 else 1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(Modifier.size(260.dp).scale(pulse)) {
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.18f), Color.Transparent), c, size.width / 2), radius = size.width / 2)
        drawCircle(
            Brush.linearGradient(listOf(Color(0xFFE88CC8), Color(0xFF8A7BEA), Color(0xFF4DB8E8)), Offset(0f, 0f), Offset(size.width, size.height)),
            radius = size.width * 0.30f, center = c
        )
    }
}

@Composable
fun HomeScreen(vm: JarvisVM) {
    val orb by vm.orb.collectAsState()
    val status by vm.status.collectAsState()
    val recent by vm.recent.collectAsState()
    var typed by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleLive() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("JARVIS", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Orb(orb) }
        Text(orb.title, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(status, color = Muted, fontSize = 16.sp, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Torch" to "flashlight", "Battery" to "battery", "Time" to "what time is it", "Pause" to "pause").forEach { (l, cmd) ->
                OutlinedButton({ vm.command(cmd) }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text(l, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("RECENT", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(if (recent.isEmpty()) "Nothing yet. What you ask shows up here." else recent.take(2).joinToString("\n"), color = Muted, maxLines = 3)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), placeholder = { Text("Type a command") }, singleLine = true)
            TextButton({ vm.command(typed); typed = "" }) { Text("Go") }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = {
                    if (orb != Orb.Standby) vm.toggleLive()
                    else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                },
                containerColor = if (orb != Orb.Standby) Color(0xFFE5484D) else Cyan, contentColor = Bg,
                shape = RoundedCornerShape(50)
            ) { Icon(Icons.Filled.Mic, "Talk") }
        }
    }
}

@Composable
private fun Section(title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(Card, RoundedCornerShape(20.dp)).padding(18.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Cmd(name: String, examples: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(name, fontWeight = FontWeight.SemiBold)
        Text(examples, color = Muted, fontSize = 13.sp)
    }
}

@Composable
fun AutomationsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Automations", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Talk in any language. These work with no internet at all when typed or tapped.", color = Muted)
        Section("On this phone", "Instant, offline, no Gemini key used.") {
            Cmd("Volume", "\"volume 30 percent kar do\" · \"volume badhao\" · \"mute\"")
            Cmd("Music", "\"pause\" · \"agla gaana\" · \"resume\"")
            Cmd("Flashlight", "\"torch on karo\" · \"flashlight band karo\"")
            Cmd("Open apps", "\"open YouTube\" · \"camera chalu karo\"")
            Cmd("Time, date, battery", "\"kitne baje hai\" · \"battery kitni hai\"")
            Cmd("Timers and alarms", "\"5 minute timer\" · \"6:30 baje alarm\"")
        }
        Section("With your OK", "Prepared for you. You tap the final button.") {
            Cmd("WhatsApp", "\"Rahul ko WhatsApp karo main 7 baje aaunga\"")
            Cmd("Calls", "\"Mom ko call karo\"")
            Cmd("Delete files", "Always asks first")
        }
        Section("With Gemini", "Needs internet and your key.") {
            Cmd("Anything, in any language", "Questions, translation, writing, planning")
            Cmd("Your screen", "\"what's on my screen?\" · \"tap Send\" (needs Phone control)")
            Cmd("Files", "\"list my Downloads\" · \"make a note file\"")
            Cmd("Settings and system", "\"open Wi-Fi settings\" · \"lock the screen\" · \"power menu\"")
        }
    }
}

@Composable
fun ActivityScreen(vm: JarvisVM) {
    val items by vm.activity.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Activity", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("What was done, not what was said. Cleared when the app closes.", color = Muted)
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) Text("Nothing yet.", color = Muted)
        LazyColumn { items(items) { Text("${it.time}  ${it.text}", Modifier.padding(vertical = 6.dp)) } }
    }
}

@Composable
private fun Row2(title: String, sub: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
        action()
    }
}

@Composable
fun SettingsScreen(vm: JarvisVM) {
    val ctx = LocalContext.current
    val p = vm.prefs
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(p.apiKey.isNotBlank()) }
    var model by remember { mutableStateOf(p.model) }
    var voice by remember { mutableStateOf(p.voice) }
    var talkOver by remember { mutableStateOf(p.talkOver) }
    var localFirst by remember { mutableStateOf(p.localFirst) }
    fun open(i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Section("Gemini", "Your own key, encrypted with Android Keystore on this phone. Never inside the APK.") {
            OutlinedTextField(
                keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (hasKey) "Key saved. Paste a new one to replace" else "Paste Gemini API key") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row {
                TextButton({ if (keyInput.isNotBlank()) { p.apiKey = keyInput; keyInput = ""; hasKey = true } }) { Text(if (hasKey) "Replace" else "Save") }
                if (hasKey) TextButton({ p.apiKey = ""; hasKey = false }) { Text("Remove") }
            }
            OutlinedTextField(model, { model = it; p.model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Live model") })
            Spacer(Modifier.height(8.dp))
            Text("Voice", color = Muted, fontSize = 13.sp)
            FlowChips(Prefs.VOICES, voice) { voice = it; p.voice = it }
        }

        Section("Conversation") {
            Row2("Talk over JARVIS", "Speak while it answers to cut it off. Echo cancellation removes its own voice first. Turn off if it cuts itself off.") {
                Switch(talkOver, { talkOver = it; p.talkOver = it })
            }
            Row2("Handle simple commands on the phone", "Volume, music, flashlight, apps, time, timers and alarms run here without Gemini.") {
                Switch(localFirst, { localFirst = it; p.localFirst = it })
            }
        }

        Section("Permissions") {
            Row2("Phone control", if (JarvisAccessibilityService.enabled) "On. Send, call, delete and pay still wait for your Allow." else "Off. Needed to read the screen, tap and type.") {
                TextButton({ open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open") }
            }
            Row2("All files access", "Lets JARVIS list, read, create and delete files.") {
                TextButton({ open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) }) { Text("Open") }
            }
            Row2("Background running", "Allow JARVIS under Battery → Unrestricted.") {
                TextButton({ open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open") }
            }
            Row2("Default assistant", "Long-press Home or swipe from a corner to talk.") {
                TextButton({ open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) { Text("Open") }
            }
        }

        Section("Privacy") {
            Text("Local commands stay on this phone. While talking, your voice streams to Gemini using your key. Nothing is recorded by JARVIS. Activity keeps actions, never your words.", color = Muted, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { FilterChip(selected == it, { onPick(it) }, { Text(it) }) }
    }
}
