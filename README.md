# JARVIS (starter project)

Original Android voice assistant: Kotlin + Jetpack Compose, streaming Gemini Live voice, offline local commands, accessibility phone control.

## Build
1. Install Android Studio. File > Open > this `jarvis` folder. Let Gradle sync (it creates the wrapper).
2. Plug in your phone (USB debugging on) and press Run, or Build > Build APK(s).
3. In the app: Settings > paste your Gemini API key (from aistudio.google.com/apikey). It is stored encrypted in Android Keystore.
4. Settings > Phone control > turn JARVIS on in Accessibility. Settings > All files access if you want file tools.
5. Tap the mic and talk in any language.

If the connection is rejected, change "Live model" in Settings to the current Live model name from
https://ai.google.dev/gemini-api/docs/live (model names change often).

## Files
- `GeminiLive.kt` streaming WebSocket session (audio up, audio down, tool calls, barge-in)
- `AudioIO.kt` 16 kHz mic with echo canceller, 24 kHz playback
- `LocalCommands.kt` offline English/Hindi/Hinglish command matching
- `PhoneTools.kt` every action + the tool list Gemini sees
- `JarvisAccessibilityService.kt` read screen, tap, type, scroll, back/home/lock/power menu
- `Prefs.kt` Keystore-encrypted key storage
- `MainActivity.kt` Home / Automations / Activity / Settings UI

## Not included yet
Wake word ("Hey Jarvis"), offline speech recognition (Vosk), floating orb, notification summary,
foreground service so it keeps listening with the screen off, screenshot understanding, reboot (needs root / Shizuku).
