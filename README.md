# HeliBoard + VibeVoice ASR

> **Disclaimer:** This project is not affiliated with, endorsed by, or sponsored by Microsoft Corporation. VibeVoice is a Microsoft product; this project merely integrates with it as an end user.

> **Note:** This is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard). See the [original HeliBoard README](README-orig.md) for upstream documentation.

---

Voice typing on Android is terrible. It's nowhere near state-of-the-art, and typing on a phone all day genuinely hurts — thumbs, wrists, everything. It shouldn't have to be this way.

This project replaces HeliBoard's voice input with [VibeVoice](https://huggingface.co/microsoft/VibeVoice), Microsoft's state-of-the-art ASR model. You speak, it transcribes — in any language, with real accuracy.

![Voice recording overlay](doc/voice_recording_overlay.jpg)

## How it works

The voice typing is powered by [vibe-voice-vendor](https://github.com/NatanFreeman/vibe-voice-vendor), a self-hosted VibeVoice server by [@NatanFreeman](https://github.com/NatanFreeman). The keyboard records audio on your phone, sends it to your server, and streams back the transcription.

1. Tap the mic icon in the keyboard toolbar
2. Speak — audio is captured as 16 kHz mono WAV
3. Tap anywhere to stop recording
4. The audio is uploaded to your VibeVoice server over HTTPS with Bearer token auth
5. The server streams back the transcription via SSE
6. The transcribed text is inserted into whatever text field you're typing in

## The catch

You need to run your own [vibe-voice-vendor](https://github.com/NatanFreeman/vibe-voice-vendor) server on a machine with a decent GPU (tested on RTX 5090). The server runs the VibeVoice model via vLLM and exposes a simple HTTP API.

Once your server is up, configure it in the app:

![Settings menu — Voice Input](doc/settings_voice_input_menu.jpg)

Enter your server URL and auth token in **Settings > Voice Input**, then tap **Test Connection** to verify:

![Voice Input settings](doc/voice_input_settings.jpg)

## Building from source

Run the included [`build.sh`](build.sh) script from the repository root:

```bash
VIBEVOICE_VERSION="0.1.0" ./build.sh
```

It validates all prerequisites (JDK 17, Android SDK, NDK, etc.) and produces a debug APK at `app/build/outputs/apk/debug/`.

## Installing

Download the latest APK from the [Releases](https://github.com/BigBIueWhale/heliboard-microsoft-vibevoice-asr/releases) page, or [build it from source](#building-from-source), then:

1. Transfer the APK to your phone and install it (enable "Install from unknown sources" if prompted)
2. Go to **Settings > System > Languages & input > On-screen keyboard** and enable **HeliBoard VibeVoice Debug**
3. Open any text field, switch to HeliBoard VibeVoice via the keyboard icon in the nav bar
4. Open **HeliBoard VibeVoice Settings > Voice Input** and enter your server URL and auth token
5. Tap the mic icon in the toolbar — grant the microphone permission on first use

> **Note:** The APK is a debug build (signed with a debug key, app ID `helium314.keyboard.vibevoice.debug`). Minification is enabled so the APK size stays small. There is no release signing keystore configured.

## Fork details

### Upstream source

- **Repository:** https://github.com/Helium314/HeliBoard
- **Tag:** `v3.5`
- **Commit:** [`d8a5842`](https://github.com/Helium314/HeliBoard/commit/d8a5842b7379083f0681484f11b6484919a77eaa)

### Files added

All paths relative to `app/src/main/`:

| File | Description |
|---|---|
| `java/.../voice/AudioRecorder.kt` | Records mic audio to WAV (16 kHz, mono, 16-bit PCM). Reports RMS amplitude for UI visualization. |
| `java/.../voice/VibeVoiceClient.kt` | HTTP client for the VibeVoice server. Multipart upload, SSE streaming response, JWT auth. Handles self-signed TLS certificates. |
| `java/.../voice/VoiceInputController.kt` | Orchestrates recording, transcription, and the overlay UI (mic icon, amplitude bars, status text). Theme-aware. |
| `java/.../voice/VoicePermissionActivity.kt` | Transparent activity to request `RECORD_AUDIO` permission (required because `InputMethodService` can't show permission dialogs). |
| `java/.../settings/screens/VoiceInputScreen.kt` | Settings screen for server URL, auth token, test connection, and setup link. |
| `res/drawable/ic_settings_voice.xml` | Mic icon for the Voice Input settings entry. |
| `res/xml/network_security_config.xml` | Android network security config (self-signed TLS handled programmatically). |

### Files modified

All paths relative to `app/src/main/`:

| File | Changes |
|---|---|
| `AndroidManifest.xml` | Added `RECORD_AUDIO` and `INTERNET` permissions, network security config, `VoicePermissionActivity` registration. |
| `java/.../latin/LatinIME.java` | Replaced `switchToShortcutIme` with `VoiceInputController` integration. Handles start/stop/cancel lifecycle. Voice commit inserts text character by character for RustDesk compatibility (see [RustDesk compatibility](#rustdesk-compatibility)). |
| `java/.../latin/inputlogic/InputLogic.java` | Clipboard paste (`onTextInput`) inserts text character by character for RustDesk compatibility. |
| `java/.../latin/InputAttributes.java` | Removed conditions that hid the mic key when no system voice IME was installed. Voice key now shows on all non-password fields. |
| `java/.../latin/settings/Settings.java` | Added `PREF_VIBEVOICE_SERVER_URL` and `PREF_VIBEVOICE_AUTH_TOKEN` preference keys. |
| `java/.../latin/settings/Defaults.kt` | Added empty-string defaults for VibeVoice preferences. |
| `java/.../settings/SettingsContainer.kt` | Registered Voice Input settings factory. |
| `java/.../settings/SettingsNavHost.kt` | Added `VoiceInput` navigation route. |
| `java/.../settings/screens/MainSettingsScreen.kt` | Added Voice Input entry to the main settings menu. |

---

## RustDesk compatibility

Voice typing and clipboard paste now work when typing into a remote machine via [RustDesk](https://github.com/rustdesk/rustdesk) on Android.

### The problem

Android IMEs insert text via the `InputConnection` interface. Bulk text insertion — a single `commitText` or `setComposingText` call with the full string — fails silently in RustDesk's Flutter-based text field. The text never arrives on the remote machine. This affects both voice transcription and the keyboard's paste action.

RustDesk detects keyboard input by diffing its hidden `TextFormField` value on each `onChanged` callback. Normal character-by-character typing works because each keystroke triggers a separate `onChanged` with a one-character difference. Bulk insertion fires at most one `onChanged` with a multi-character difference, which RustDesk does not reliably process.

### The fix

Both the voice input callback (`LatinIME.java`) and the text input / clipboard paste path (`InputLogic.onTextInput`) now commit text **one character at a time** in a loop, instead of inserting the full string in a single call. Each `commitText` triggers a separate `onChanged` in the target text field, so RustDesk's diff logic sees a one-character addition each time — exactly the same as normal typing.

### Impact on other apps

None. Committing characters individually produces the same end result as a single bulk `commitText`. The loop runs synchronously on the main thread, so there is no visible delay.

---

## Release notes

### vibevoice-v0.5.0

Reliability and correctness overhaul. This release rewrites the core recording, storage, and text insertion paths to eliminate race conditions, resource leaks, and silent data loss that could leave the keyboard in a broken state requiring a phone reboot to recover. The goal: you should be able to speak into this keyboard for as long as you want, in any Android application, and trust that your recording is safe on disk even if the network fails, the app is killed, or the phone's audio subsystem misbehaves.

#### Recording reliability

- **Fix: Android microphone leaked after disk I/O error, requiring phone reboot to recover.** The `AudioRecord` native resource (which holds exclusive access to the smartphone's microphone hardware) is now stopped and released inside a `finally` block on the recording thread. Previously, if the WAV file write failed mid-recording (for example: the smartphone's internal storage is full, or the SD card is unmounted), the `AudioRecord.stop()` and `AudioRecord.release()` calls were skipped entirely because they sat after the write loop, not in a `finally` block. The microphone hardware remained locked by the killed recording session. Every subsequent recording attempt would initialize a new `AudioRecord` that appeared healthy (passing Android's `STATE_INITIALIZED` check), but `AudioRecord.read()` would return zero data because the hardware was still held by the leaked instance. This produced 0-byte WAV files (just a 44-byte header with no audio data). The only recovery was rebooting the Android smartphone to force the operating system to release the microphone hardware. This was the root cause of the "every recording is 0.0 MB until I restart my phone" failure reported in production.

- **Fix: race condition between the recording thread and the main thread could corrupt or leak the `AudioRecord`.** The recording thread now captures the `AudioRecord` instance as a thread-local variable at startup and uses that local reference for the entire recording lifecycle — including the `read()` loop, the `stop()` call, and the `release()` call. Previously, the recording thread read the `audioRecord` field directly from the `AudioRecorder` class, while the main thread's `stop()` method could null that same field after a 5-second join timeout. If the join timed out (for example, because the recording thread was blocked in a slow `AudioRecord.read()` call), two things could go wrong: (1) the thread would read `null` via the safe-call operator, skip `stop()`/`release()`, and leak the microphone; or (2) if `start()` was called immediately after, the thread would call `stop()`/`release()` on the *new* `AudioRecord` instance instead of the one it was recording with, destroying an active recording session.

- **Fix: infinite CPU spin when the Android audio subsystem returns errors.** If `AudioRecord.read()` returns a negative error code (such as `ERROR = -3` or `ERROR_BAD_VALUE = -2`, which can happen when another Android application seizes exclusive microphone access, or when the audio hardware abstraction layer enters a bad state), the recording loop now aborts after 3 consecutive errors instead of spinning indefinitely. Previously, the `if (read > 0)` check would skip writing data, but the `while (isRecording.get())` loop would immediately call `read()` again. Since error returns are instantaneous (no blocking), this was a tight CPU spin that would run at 100% of one core, drain the smartphone's battery, and produce a 0-byte recording — all while the user saw the "Listening..." UI with no indication that anything was wrong. The recording now ends cleanly, and the minimum-size check (see below) discards the empty file and shows a "Recording too short" message.

- **Fix: impossible internal state now crashes instead of silently producing empty files.** If the `AudioRecord` instance is somehow `null` when the recording thread starts (which should be impossible given the happens-before guarantee of `Thread.start()`), the thread now throws an `IllegalStateException` with a stack trace instead of silently returning. Previously, the `?: return` fallback would exit the thread without writing any audio data, producing a 0-byte WAV file that the user would discover only after the transcription attempt failed.

#### Storage and file management

- **Fix: application kill during transcription permanently corrupted the recordings list.** When the Android operating system kills the keyboard process while a transcription is in flight (which can happen during low-memory conditions, or when the user force-stops the application from Android Settings), the `.transcribing` marker file was left on disk with no code to clean it up. On next launch, the recording appeared permanently stuck in "Transcribing..." status in the recordings list. Worse, `enforceStorageCap()` explicitly skipped recordings with `.transcribing` markers to avoid deleting files being actively uploaded, so these zombie recordings could never be automatically deleted. After enough app kills, all recording slots could be occupied by undeletable zombie entries. The `RecordingStore` now clears all `.transcribing` markers on construction, before any transcription can be in flight.

- **Fix: storage cap enforcement could get permanently stuck.** The `enforceStorageCap()` method now deletes recordings in a loop until the count is under the cap, and includes a second pass that force-deletes the oldest recordings regardless of `.transcribing` status if the first pass couldn't free enough space. Previously, the method used a single `.filter { !it.isTranscribing }.drop(MAX - 1)` pattern that deleted nothing when all recordings had `.transcribing` markers, and the fallback only deleted a single recording per invocation.

- **Fix: storage cap enforcement read all transcription text files on the Android main thread before every recording.** The `enforceStorageCap()` method previously called `listRecordings()`, which reads the full text content of every `.txt` transcription file into memory to populate the `RecordingInfo.transcriptionText` field. With 50 recordings, that is 50 filesystem reads on the Android main thread (the UI thread) executed synchronously before every new recording begins. The cap enforcement now directly lists `.wav` files and checks only `.transcribing` marker existence, without reading any transcription text.

- **Fix: `File.delete()` failures were silently ignored, causing the storage cap to believe it freed space when it didn't.** The `delete()` method now returns a `Boolean` indicating whether the WAV file was actually removed from disk, and `enforceStorageCap()` only counts a deletion as successful when the file is confirmed gone. Previously, if `File.delete()` failed (for example, because the file was locked by an in-flight HTTP upload), the cap logic decremented its counter anyway and proceeded as if the space was freed.

- **Fix: empty or too-short recordings were uploaded to the VibeVoice server with 3 retry attempts.** Recordings shorter than approximately 1 second of audio (under 32,044 bytes at 16 kHz 16-bit mono PCM) are now discarded immediately with a "Recording too short" toast message. Previously, a 44-byte WAV file (just the RIFF header, zero audio data) would be sent to the server, fail transcription, retry twice more with delays, and then either be kept on disk as a failed recording or deleted if the server returned an empty result — all with no feedback to the user about what happened.

- **Fix: recording filename collisions on rapid stop-and-start.** Recording filenames now use millisecond-precision timestamps (`recording_YYYYMMdd_HHmmss_SSS.wav`) instead of second-precision (`recording_YYYYMMdd_HHmmss.wav`). Previously, two recordings created within the same calendar second would receive the same filename. Because `RandomAccessFile(file, "rw")` opens existing files without truncating them, the second recording would overwrite the beginning of the first file's audio data while leaving the old file's trailing bytes intact, producing a corrupted WAV file. Recordings created by older versions of the application (using the old second-precision naming) are automatically deleted on upgrade.

- **Storage cap raised from 10 to 50 recordings.** The previous cap of 10 meant that a heavy voice-typing session could burn through the entire backup window in minutes. With 50 recordings and the scrollable recordings list fix (see below), all recordings are visible and manageable from the keyboard overlay.

- **User-facing operations now report failures instead of silently succeeding.** When the user presses "Delete" on a recording (in either the keyboard overlay or the Android Settings screen) and the file cannot be removed from disk, a toast message now says "Failed to delete recording." The "Delete All" button in Android Settings reports how many deletions failed. When a transcription result cannot be saved to the `.txt` file on disk, a toast says "Warning: transcription not saved to disk." When the VibeVoice server returns an empty transcription (no speech detected), a toast says "No speech detected." Previously, all of these cases were handled silently — the UI would update as if the operation succeeded, or simply return to the landing screen with no explanation.

#### Text insertion

- **Fix: the Google Gemini Android application (and other WebView-based text editors) silently dropped the last portion of voice-typed text.** The character-by-character `commitText` insertion loop in `LatinIME.java` is now wrapped in a single `beginBatchEdit()`/`endBatchEdit()` pair, and `finishComposingText()` is called after the loop completes. Previously, the batch edit wrapped only the initial `finishComposingText()` call (which clears pre-existing composing text), and the character loop ran outside any batch edit. After the loop, `restartSuggestionsOnWordTouchedByCursor()` was called, which scans the text around the cursor and marks the last word as composing text (the underlined "in-progress" state that Android's `InputConnection` uses for inline suggestions). WebView-based editors like the Google Gemini Android application treat composing text as provisional — when the user taps "Send," the editor commits only finalized text and discards anything still in composing state. The result: the last word, sentence fragment, or even multiple sentences of voice-typed text would disappear when the message was sent. Manually typing or deleting a single character after voice input would trigger the keyboard's normal composing-text finalization, which is why the workaround of "type one character after voice typing" made the problem go away.

- **Fix: the keyboard's entire user interface disappeared (white screen) when inserting text from the recordings list.** The voice input overlay is now removed from the Android view hierarchy *before* the character-by-character text insertion begins, for both the "Insert" action from the recordings list and the automatic insertion after a fresh transcription completes. Previously, the overlay remained attached during the `commitText` loop. Because each `commitText` call sends a change notification to the target application's text editor, some applications respond by requesting a new input session, which triggers Android's `InputMethodService.onFinishInputView()` callback. With the overlay still attached in the `RECORDINGS_LIST` or `TRANSCRIBING` state, `detachOverlay()` would call `cleanup()`, which removed the overlay and tore down the keyboard view mid-insertion. The Android system then briefly showed a blank (white) keyboard area before recreating the input view. The text insertion itself still completed (because `commitText` calls are buffered by Android's `InputConnection` even after the view is destroyed), but the visual disruption was jarring.

#### Other changes

- **Flat 500ms retry delay instead of exponential backoff.** Transcription retry delay changed from exponential backoff (2 seconds, then 4 seconds, then 8 seconds — 14 seconds total worst case) to a flat 500ms pause between each of the 3 retry attempts (1.5 seconds total worst case). This is a self-hosted VibeVoice server on a private network, not a shared public API — there is no server-side rate limiting to respect, and no other users to be polite to. When the server is temporarily unreachable, the user wants to know as fast as possible whether the transcription will work.

- **Cancellation signal between threads is now `@Volatile`.** The `generation` counter that the transcription background thread checks to detect cancellation from the Android main thread is now marked `@Volatile` in the Java Memory Model sense. Previously, the background thread could read a stale cached value of the counter and miss a cancellation signal, continuing to upload audio to the VibeVoice server and wait for a response even after the user had pressed "Cancel" on the keyboard overlay.

### vibevoice-v0.4.1

- **Fix: text insertion not working.** Transcription results were not being typed into the text field. The overlay was kept visible after auto-insert, which interfered with text commitment. Now the overlay closes immediately after inserting text, matching the working v0.3.0 behavior.
- **Fix: transcription text not saved to disk.** `File.renameTo()` silently fails on Android external storage. Replaced with direct file write so `.txt` files are reliably created.

### vibevoice-v0.4.0

Redesigned voice input UX around a state-driven overlay with a landing menu, persistent recordings, and full cancellability.

- **Landing menu.** Tapping the mic icon opens a landing screen with two options — "New Recording" and "Recordings" — instead of immediately starting recording. Consistent entry point every time.
- **Auto-insert.** Transcription results are automatically typed into the text field when complete. No extra tap required.
- **Recordings are never lost.** Voice recordings are saved to user-accessible storage (`Android/data/<app>/files/vibevoice_recordings/`), browsable in any file manager. Recordings persist after insertion — marked as done but never silently deleted. Storage capped at 10 recordings.
- **Recordings list in the overlay.** Browse, re-transcribe, re-insert, copy, or delete past recordings directly from the keyboard overlay. Re-transcribe reuses the same code path as a fresh transcription.
- **Automatic retry.** Transcription retries up to 3 times with exponential backoff on network failure.
- **Background transcription survives keyboard close.** If the keyboard hides during recording or transcription, work continues in the background. The overlay reattaches when the keyboard reopens.
- **Instant cancellation.** Every state is cancellable. Cancelling during transcription immediately aborts the HTTP connection — no waiting for timeouts. The recording is always preserved on disk.
- **Saved Recordings in Settings.** Settings > Voice Input > Saved Recordings provides a full management screen to transcribe, copy, or delete recordings.
- **Increased timeouts.** HTTP read timeout raised from 60s to 10 minutes to support long recordings without premature disconnection.

### vibevoice-v0.3.0

Internal release (superseded by v0.4.0).

### vibevoice-v0.2.0

- Voice typing and clipboard paste now work in [RustDesk](https://github.com/rustdesk/rustdesk) remote sessions (see [RustDesk compatibility](#rustdesk-compatibility))

### vibevoice-v0.1.0

Initial release. Fork of [HeliBoard v3.5](https://github.com/Helium314/HeliBoard/releases/tag/v3.5) with the stock voice input completely replaced by a custom VibeVoice ASR client.

- Voice typing via a self-hosted [vibe-voice-vendor](https://github.com/NatanFreeman/vibe-voice-vendor) server (Microsoft VibeVoice model)
- Records 16 kHz mono WAV audio on-device, uploads via HTTPS with Bearer token auth
- Server streams back transcription via SSE
- Recording overlay with live amplitude visualization, themed to match keyboard colors
- Settings screen for server URL, auth token (masked), and connection test
- Supports self-signed TLS certificates
- Multilingual — transcription language depends on the VibeVoice model, not the keyboard layout
- Debug APK build with minification enabled for small APK size
- Automated releases via GitHub Actions on `vibevoice-v*` tags
