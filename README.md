> **Note:** This is a fork of HeliBoard with VibeVoice ASR integration. See the [original HeliBoard README](README-orig.md) for upstream documentation.

> **Disclaimer:** This project is not affiliated with, endorsed by, or sponsored by Microsoft Corporation. VibeVoice is a Microsoft product; this project merely integrates with it as an end user.

---

Hey! I set up a speech-to-text server on my machine - want to try it out?

It runs VibeVoice, Microsoft's new ASR model. You send it an audio file and it streams back a transcription with timestamps and speaker detection.

To test that it's up:
curl -sk YOUR_SERVER_URL/health

Supports wav, mp3, flac, etc. Works best on speech - music/sound effects can confuse it.

curl -sk -N -H "Authorization: Bearer YOUR_TOKEN_HERE" -F "audio=@your_file.wav" YOUR_SERVER_URL/v1/transcribe

To transcribe an audio file, just replace "your_file.wav" with your file.

It works perfectly from my phone (tested with Termux), from anywhere in the world.

---

## HeliBoard + VibeVoice Integration

### What this is

A modified [HeliBoard v3.5](https://github.com/Helium314/HeliBoard/releases/tag/v3.5) (open-source Android keyboard, AOSP-based) with the voice typing replaced by a configurable VibeVoice ASR server. The stock voice input (which delegates to Google's dictation service via Android's "shortcut IME" system) has been completely replaced with a custom implementation that records audio on-device and sends it directly to your VibeVoice server.

Server URL and auth token are configured in-app via **Settings > Voice Input** — no secrets are hardcoded in the source.

### Exact upstream source

- **Repository:** https://github.com/Helium314/HeliBoard
- **Tag:** `v3.5`
- **Commit:** `d8a5842b7379083f0681484f11b6484919a77eaa`
- **Cloned with:** `git clone --depth 1 --branch v3.5 https://github.com/Helium314/HeliBoard.git heliboard`

### How it works

1. Tap the mic icon in the keyboard toolbar
2. A recording overlay appears over the keyboard area with live amplitude bar visualization
3. Speak — audio is captured at 16 kHz sample rate, 16-bit PCM, mono channel (WAV format)
4. Tap anywhere on the overlay to stop recording
5. The WAV file is uploaded via HTTP multipart/form-data POST to your configured server URL with Bearer JWT auth
6. The server uses a self-signed TLS certificate — the client configures a per-connection `TrustManager` that accepts it
7. The server streams back Server-Sent Events (SSE): each line is `data: {"text":"<chunk>"}`, terminated by `event: done`
8. The accumulated text chunks form a JSON array of segments: `[{"Start":0,"End":2.5,"Content":"Hello world"}]`
9. Content from non-silence segments (filtering out markers like `[Silence]`) is concatenated and committed to the active text field via `InputConnection.commitText()`
10. The overlay is removed and the keyboard returns to normal

### Files created

All paths relative to `app/src/main/`:

```
java/helium314/keyboard/latin/voice/AudioRecorder.kt
```
Records microphone audio to a WAV file using Android's `AudioRecord` API. Configured for 16000 Hz sample rate, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`. Writes raw PCM to a `RandomAccessFile`, then patches the 44-byte WAV header with correct sizes when recording stops. Reports RMS amplitude (0.0–1.0) via a callback for UI visualization.

```
java/helium314/keyboard/latin/voice/VibeVoiceClient.kt
```
HTTP client for the VibeVoice server. Takes server URL and auth token as constructor params (read from shared preferences via `fromPreferences()` factory). Uses `javax.net.ssl.HttpsURLConnection` with chunked streaming mode. Constructs a multipart/form-data body with the audio file. Reads the SSE response line by line, extracts the `"text"` field from each `data:` line using `kotlinx.serialization.json.JsonObject`, accumulates the chunks, then deserializes the final result as `List<Segment>` using `kotlinx.serialization`. Segments whose `Content` matches `[...]` patterns (silence/noise markers) are excluded from the output text.

```
java/helium314/keyboard/latin/voice/VoiceInputController.kt
```
Orchestrates the full voice input lifecycle. Manages three states: `IDLE`, `RECORDING`, `TRANSCRIBING`. Creates a `VoiceOverlayView` (programmatic — no XML layout) that is added to the `KeyboardWrapperView` (FrameLayout) as a child. The overlay shows a microphone emoji, an `AmplitudeBarsView` (24 rounded-rect bars whose heights track RMS amplitude), a status label ("Listening..." / "Transcribing..."), animated pulsing dots during transcription, a cancel button, and a "Tap to stop" hint. All colors are read from HeliBoard's `Settings.getValues().mColors` so the overlay matches the user's keyboard theme. Uses a generation counter to discard stale callbacks after cancellation. Transcription runs on a single-thread `ExecutorService`; results are posted back to the main thread via `Handler(Looper.getMainLooper())`. Includes a 65-second safety timeout and specific error messages for connection/timeout failures.

```
java/helium314/keyboard/latin/voice/VoicePermissionActivity.kt
```
Transparent Activity (`Theme.Translucent.NoTitleBar`) that requests `android.permission.RECORD_AUDIO` at runtime. Needed because `InputMethodService` cannot directly trigger the permission dialog. Launched with `FLAG_ACTIVITY_NEW_TASK`. Finishes itself immediately after the user grants or denies.

```
java/helium314/keyboard/settings/screens/VoiceInputScreen.kt
```
Settings screen for configuring VibeVoice: server URL, auth token (masked), test connection button, and a link to the vibe-voice-vendor setup project.

```
res/drawable/ic_settings_voice.xml
```
Mic icon drawable for the Voice Input settings entry.

```
res/xml/network_security_config.xml
```
Minimal Android network security configuration. The VibeVoice client handles self-signed TLS certificates programmatically via a per-connection `TrustManager`. Referenced from `AndroidManifest.xml` via `android:networkSecurityConfig`.

### Files modified

All paths relative to `app/src/main/`:

**`AndroidManifest.xml`**
- Added `<uses-permission android:name="android.permission.RECORD_AUDIO" />`
- Added `<uses-permission android:name="android.permission.INTERNET" />`
- Added `android:networkSecurityConfig="@xml/network_security_config"` to the `<application>` tag
- Added `<activity android:name=".voice.VoicePermissionActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" />`

**`java/helium314/keyboard/latin/LatinIME.java`**
- Added field: `private helium314.keyboard.latin.voice.VoiceInputController mVoiceInputController;`
- In `onCreate()`: initializes `mVoiceInputController` with a lambda that calls `mInputLogic.mConnection.commitText(text, 1)`, `restartSuggestionsOnWordTouchedByCursor()`, and `requestUpdatingShiftState()` — the lambda returns `kotlin.Unit.INSTANCE` for Java/Kotlin interop
- In `onEvent()`: changed from `mRichImm.switchToShortcutIme(this)` to `handleVibeVoiceInput(); return;` when `KeyCode.VOICE_INPUT` is detected
- Added `handleVibeVoiceInput()` method: checks configuration and mic permission, then toggles between `start()`/`stop()`/`cancel()` based on `VoiceInputController.state`
- In `onFinishInputViewInternal()`: added `mVoiceInputController.cancel()` to stop recording when the keyboard hides
- In `onDestroy()`: added `mVoiceInputController.cancel()` for cleanup

**`java/helium314/keyboard/latin/InputAttributes.java`**
- Removed conditions `InputTypeUtils.isEmailVariation(variation)`, `!RichInputMethodManager.isInitialized()`, and `!RichInputMethodManager.getInstance().isShortcutImeReady()` from the `noMicrophone` check
- Voice key now shows on all text input fields except password fields and fields that explicitly set the `NO_MICROPHONE` private IME option
- This means voice input works without any system voice IME being installed

**`java/helium314/keyboard/latin/settings/Settings.java`**
- Added `PREF_VIBEVOICE_SERVER_URL` and `PREF_VIBEVOICE_AUTH_TOKEN` preference keys

**`java/helium314/keyboard/latin/settings/Defaults.kt`**
- Added empty-string defaults for both VibeVoice preferences

**`java/helium314/keyboard/settings/SettingsContainer.kt`**
- Registered `createVoiceInputSettings` factory; added `VIBEVOICE_TEST_CONNECTION` and `VIBEVOICE_SETUP_LINK` to `SettingsWithoutKey`

**`java/helium314/keyboard/settings/SettingsNavHost.kt`**
- Added `VoiceInput` navigation route

**`java/helium314/keyboard/settings/screens/MainSettingsScreen.kt`**
- Added Voice Input item to the main settings menu (before Advanced)

### Exact build environment

| Component | Version |
|---|---|
| HeliBoard | v3.5, commit `d8a5842b7379083f0681484f11b6484919a77eaa` |
| Kotlin | 2.2.10 |
| Kotlin Compose plugin | 2.0.0 |
| Android Gradle Plugin | 8.10.1 |
| Gradle | 8.14 (via wrapper, SHA-256: `61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa`) |
| JDK | OpenJDK 17.0.18+8-Ubuntu |
| Android SDK Platform | 35 (revision 2) |
| Android Build Tools | 35.0.0 |
| Android NDK | 28.0.13004108 |
| compileSdk | 35 |
| minSdk | 21 |
| targetSdk | 35 |
| kotlinx-serialization-json | 1.9.0 |
| Compose BOM | 2025.08.00 |
| Build OS | Ubuntu 24.04.3 LTS, kernel 6.14.0-37-generic, x86_64 |

### Building

Run the included build script from the repo root:

```bash
./build.sh
```

It validates your entire environment (JDK 17, Android SDK, NDK, platform, disk space, etc.) and fails with a detailed error message on the first problem it finds. On success it prints the APK path.

Or build manually:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/HeliBoard-VibeVoice_3.5-debug.apk
```

### Installing on your phone

1. Transfer the APK to your phone (USB, `adb install`, cloud, etc.)
2. Install it — enable "Install from unknown sources" for your file manager if prompted
3. Go to **Settings > System > Languages & input > On-screen keyboard** > enable **"HeliBoard VibeVoice Debug"**
4. Open any text field, tap the keyboard icon in the nav bar, and switch to **HeliBoard VibeVoice Debug**
5. Open **HeliBoard VibeVoice Settings > Voice Input** and enter your server URL and auth token
6. Tap the **mic icon** in the toolbar — on first use, grant the microphone permission when prompted
7. The keyboard needs internet access to reach your VibeVoice server — ensure the phone has connectivity

To set up your own VibeVoice server, see [vibe-voice-vendor](https://github.com/NatanFreeman/vibe-voice-vendor) (requires a machine with a GPU).
