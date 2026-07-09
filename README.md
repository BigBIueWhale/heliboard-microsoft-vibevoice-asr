# HeliBoard + VibeVoice ASR

> **Disclaimer:** This project is not affiliated with, endorsed by, or sponsored by Microsoft Corporation. VibeVoice is a Microsoft product; this project merely integrates with it as an end user.

> **Note:** This is a fork of [HeliBoard](https://github.com/Helium314/HeliBoard). See the [original HeliBoard README](README-orig.md) for upstream documentation.

---

Voice typing on Android is terrible. It's nowhere near state-of-the-art, and typing on a phone all day genuinely hurts — thumbs, wrists, everything. It shouldn't have to be this way.

This project replaces HeliBoard's voice input with [VibeVoice](https://huggingface.co/microsoft/VibeVoice), Microsoft's state-of-the-art ASR model. You speak, it transcribes — in any language, with real accuracy.

The core promise: **you can speak into this keyboard for as long as you want and trust that your recording is safe on disk** — even if the network fails, the server is down, the application is killed, or the phone's audio system misbehaves. Every recording is saved as a WAV file before transcription begins. If transcription fails, the recording is kept so you can retry later. You never lose what you said.

![Voice recording overlay](doc/voice_recording_overlay.jpg)

## How it works

The voice typing is powered by [vibe-voice-vendor-1](https://github.com/BigBIueWhale/vibe-voice-vendor-1), a hardened self-hosted VVV server. The keyboard records audio on your Android smartphone, sends it to your server through the authenticated public proxy, and streams back the transcription.

1. Tap the mic icon in the keyboard toolbar — a landing menu appears
2. Tap **New Recording** and speak — audio is captured as a 16 kHz mono WAV file and saved to disk immediately
3. Tap anywhere to stop recording
4. The audio is uploaded to your VVV server over TLS 1.3 with exact pinned server-public-key trust and mTLS client authentication
5. The server streams back the transcription via Server-Sent Events
6. The transcribed text is automatically typed into whatever text field you're using

If transcription fails, the keyboard retries up to 3 times. If all retries fail, the recording stays on disk — tap **Recordings** from the landing menu to browse, re-transcribe, copy, or delete past recordings. Up to 50 recordings are kept.

If the keyboard is dismissed while recording or transcribing, the work continues in the background. When the keyboard reappears, the overlay reattaches.

## The catch

You need to run your own [vibe-voice-vendor-1](https://github.com/BigBIueWhale/vibe-voice-vendor-1) server on a machine with a decent GPU (tested on NVIDIA GeForce RTX 5090). The server runs the VibeVoice model via vLLM and exposes one authenticated public API: TLS 1.3, exact pinned server public-key identity, and mandatory mTLS client certificate authentication.

Because the public server is TLS 1.3-only, VibeVoice voice input requires Android 10 or newer for the ASR connection. The keyboard can still run on older Android versions, but the hardened VVV voice client cannot connect from a platform TLS stack without TLS 1.3 support.

Once your server is up, configure it in the app:

![Settings menu — Voice Input](doc/settings_voice_input_menu.jpg)

Import the VVV client bundle in **Settings > Voice Input > Import Client Bundle**, then tap **Test Connection** to verify the full authenticated transport path:

![Voice Input settings](doc/voice_input_settings.jpg)

## Building from source

Run the included [`build.sh`](build.sh) script from the repository root:

```bash
VIBEVOICE_VERSION="0.6.3" ./build.sh
```

It validates all prerequisites (JDK 17, Android SDK, NDK, etc.) and produces a debug APK at `app/build/outputs/apk/debug/`.

## Installing

Download the latest APK from the [Releases](https://github.com/BigBIueWhale/heliboard-microsoft-vibevoice-asr/releases) page, or [build it from source](#building-from-source), then:

1. Transfer the APK to your Android smartphone and install it (enable "Install from unknown sources" if prompted)
2. Go to **Settings > System > Languages & input > On-screen keyboard** and enable **HeliBoard VibeVoice Debug**
3. Open any text field, switch to HeliBoard VibeVoice via the keyboard icon in the navigation bar
4. On the server, create `keys/client-bundle.vvv.json` with `uv run python -m scripts.generate_client_bundle --server-url https://HOST:42862 --output keys/client-bundle.vvv.json`, replacing `HOST` with the IPv4-reachable DNS name or IPv4 address
5. Open **HeliBoard VibeVoice Settings > Voice Input > Import Client Bundle** and paste the bundle JSON
6. Tap the mic icon in the toolbar — grant the microphone permission on first use

> **Note:** The APK is a debug build (signed with a debug key, app ID `helium314.keyboard.vibevoice.debug`). Minification is enabled so the APK size stays small. There is no release signing keystore configured.

## App compatibility

### RustDesk remote desktop

Voice typing and clipboard paste work when typing into a remote machine via [RustDesk](https://github.com/rustdesk/rustdesk) on Android. Text is inserted character by character so that RustDesk's Flutter-based text field (which detects input by diffing its value on each change callback) sees each character as a separate keystroke — the same as normal typing. This applies to both voice transcription and the keyboard's paste action.

### Google Gemini and other WebView-based editors

Voice-typed text is fully committed (not left in Android's composing/underline state) so that WebView-based applications like the Google Gemini Android application do not discard the last portion of text when the user presses Send.

## Fork details

### Upstream source

- **Repository:** https://github.com/Helium314/HeliBoard
- **Tag:** `v3.5`
- **Commit:** [`d8a5842`](https://github.com/Helium314/HeliBoard/commit/d8a5842b7379083f0681484f11b6484919a77eaa)

### Files added

All paths relative to `app/src/main/`:

| File | Description |
|---|---|
| `java/.../voice/AudioRecorder.kt` | Records microphone audio to a WAV file (16 kHz, mono, 16-bit PCM). Reports RMS amplitude for the UI visualization. Releases the microphone in a `finally` block so it is never leaked. |
| `java/.../voice/VibeVoiceClient.kt` | VVV API client. Multipart WAV upload and Server-Sent Events streaming over TLS 1.3 with exact server SPKI pin verification and mandatory mTLS client certificate/key. URL hostnames are routing inputs, not trust authority. Bounds SSE line and transcript sizes so a server cannot force unbounded client memory growth. |
| `java/.../voice/VoiceInputController.kt` | Orchestrates the full voice input lifecycle: recording, transcription with retry, overlay UI (landing menu, amplitude bars, recordings list, status text). Theme-aware. |
| `java/.../voice/RecordingStore.kt` | File-based storage for WAV recordings and their transcriptions. Filesystem is the source of truth — marker files track transcription state. Cleans up stale state on startup. |
| `java/.../voice/VoicePermissionActivity.kt` | Transparent activity to request `RECORD_AUDIO` permission (required because `InputMethodService` cannot show permission dialogs directly). |
| `java/.../settings/screens/VoiceInputScreen.kt` | Settings screen for importing the VVV client bundle, inspecting server URL, server public key pin, mTLS client certificate/key, authenticated test connection, saved recordings, and setup link. |
| `java/.../settings/screens/SavedRecordingsScreen.kt` | Full management screen for saved recordings — transcribe, copy, delete individual or all recordings. |
| `res/drawable/ic_settings_voice.xml` | Mic icon for the Voice Input settings entry. |
| `res/xml/network_security_config.xml` | Empty Android network security configuration. The VVV client builds its own fail-closed pinned TLS/mTLS context in code. |

### Files modified

All paths relative to `app/src/main/`:

| File | Changes |
|---|---|
| `AndroidManifest.xml` | Added `RECORD_AUDIO` and `INTERNET` permissions, network security configuration, `VoicePermissionActivity` registration. |
| `java/.../latin/LatinIME.java` | Replaced `switchToShortcutIme` with `VoiceInputController` integration. Handles start/stop/cancel lifecycle. Voice text insertion uses a single composition session (`setComposingText` per character, then `commitText` with the full string) for compatibility with both remote desktop applications (RustDesk, TeamViewer) and WebView-based editors (Google Gemini). |
| `java/.../latin/inputlogic/InputLogic.java` | Clipboard paste (`onTextInput`) inserts text character by character for RustDesk compatibility. |
| `java/.../latin/InputAttributes.java` | Removed conditions that hid the mic key when no system voice IME was installed. Voice key now shows on all non-password fields. |
| `java/.../latin/settings/Settings.java` | Added VVV server URL, server public key pin, client certificate, and client private key preference keys. |
| `java/.../latin/settings/Defaults.kt` | Added empty-string defaults for VVV preferences. |
| `java/.../settings/SettingsContainer.kt` | Registered Voice Input settings factory. |
| `java/.../settings/SettingsNavHost.kt` | Added `VoiceInput` navigation route. |
| `java/.../settings/screens/MainSettingsScreen.kt` | Added Voice Input entry to the main settings menu. |

---

## Release notes

### vibevoice-v0.6.3

- **One-file VVV client import.** Voice Input now has an `Import Client Bundle` action that accepts the server-generated `keys/client-bundle.vvv.json` payload containing the server URL, server public key pin, client certificate, and client private key.
- **Bundle validation is fail-closed.** Import rejects malformed JSON, unknown fields, wrong bundle type/version, non-HTTPS origins, IPv6 literals, malformed server pins, CA certificates, certificates without `clientAuth`, invalid private keys, and certificate/key mismatches before writing preferences.
- **The transport model is unchanged.** The bundle is only a configuration carrier; runtime access still has exactly one mode: TLS 1.3, exact server SPKI pinning, mandatory mTLS client authentication, and no application bearer secret.

### vibevoice-v0.6.2

- **VVV public API v3.** Voice input now uses exactly four configured fields: server URL, server public key pin, client certificate, and client private key.
- **mTLS is the application identity.** The keyboard does not store, validate, or send an application auth secret. The server derives client identity from the client certificate used in the TLS handshake.
- **Fresh settings namespace.** Current VVV settings use `vvv_public_api_v3_*` keys and older VVV public API settings are purged from both credential- and device-protected preference stores.

### vibevoice-v0.6.1

- **Fix: server identity is now exact pinned public key, not hostname/SAN authority.** The VVV client treats the Server URL only as a routing locator and accepts a TLS peer only when the leaf certificate public key matches the configured `sha256/...` SPKI pin. No public CA, DNS name, certificate SAN, or Android hostname verifier result can authorize a server.
- **Fix: fresh installs/updates cannot reuse older VVV app data.** The client uses a dedicated VVV public API preference namespace and purges older certificate-based settings from both credential- and device-protected preference stores.

### vibevoice-v0.6.0

Security/API redesign for the hardened VVV public server.

- **Breaking change: the keyboard now supports only the hardened VVV API.** Voice input requires the server URL, server public key pin, mTLS client certificate, and unencrypted PKCS#8 EC client private key.
- **TLS is fail-closed.** The client uses TLS 1.3 only, exact server SPKI pin verification, and a client certificate/key presented through Android's TLS stack. There is no permissive trust manager, WebPKI fallback, hostname-as-authority mode, or compatibility fallback.
- **Health checks and transcription use the same authentication path.** `Test Connection` now verifies the real transport model: TLS 1.3, server public key pinning, and mTLS.
- **Server-controlled SSE input is bounded.** The client limits SSE line size, per-event data size, and accumulated transcription size so a compromised or buggy server cannot force unbounded client memory growth.
- **Configuration is validated at entry and at use.** Settings reject malformed HTTPS origins, malformed server pins, certificates, and EC private keys. Invalid stored values are treated as not configured.
- **VVV secrets moved to credential-protected preferences.** Server URL, pin, certificate, and key material are not stored in the direct-boot preference store.
- **Build defaults are self-validating.** The default hand-run build version now produces a valid Android `versionCode`; malformed `VIBEVOICE_VERSION` values fail clearly, and the build script rejects reintroduced permissive TLS patterns.

### vibevoice-v0.5.3

- **Fix: pressing backspace right after voice input crashed the keyboard.** If the word committed immediately before voice input was an autocorrection (or other revertable commit), the first backspace after voice transcription would trigger `revertCommit` on text that was no longer adjacent to the cursor, raising `RuntimeException: revertCommit check failed`. Voice input now resets the keyboard's internal state model after committing, so backspace after voice behaves like backspace after any other text — it just deletes one character. This latent bug was unmasked in v0.5.1 when `restartSuggestionsOnWordTouchedByCursor` was removed to fix Gemini compatibility; that call had been incidentally clearing the stale state as a side effect.
- **Fix: Android system crashes (reboot, framework OOM) were misattributed to the keyboard.** When the Android `system_server` process dies, any keyboard IPC into it throws `DeadSystemException`/`DeadSystemRuntimeException`. The uncaught-exception handler now recognises these and skips the crash report — the OS is going to kill every running app anyway, so reporting it as a keyboard bug was noise.

### vibevoice-v0.5.2

- **Fix: voice-typed text was silently truncated in the Google Gemini Android application and other WebView-based editors.** Text insertion now uses a single Android composition session (`setComposingText` per character, then `commitText` with the full string) — the same pattern that Google's Gboard keyboard uses for normal English typing. Previous versions used per-character `commitText`, which is not how Android keyboards are supposed to insert text and caused WebView-based editors to lose data. Voice typing into RustDesk and TeamViewer remote desktop sessions continues to work.

### vibevoice-v0.5.1

- **Fix: voice typing into RustDesk remote sessions stopped working in v0.5.0.** Reverted an incorrect change from v0.5.0 that broke per-character text insertion. Superseded by the correct fix in v0.5.2.

### vibevoice-v0.5.0

Reliability overhaul for voice recording, storage, and text insertion.

- **Fix: the Android smartphone's microphone could get permanently locked, producing 0-byte recordings until the phone was rebooted.** The microphone is now always released, regardless of what errors occur during recording.
- **Fix: a race condition between stopping and starting recordings could lock the microphone or corrupt the new recording.** The recording thread now holds its own private reference to the microphone hardware.
- **Fix: the keyboard would spin silently and drain the battery if the Android audio system returned errors.** The recording now aborts after 3 consecutive errors instead of spinning indefinitely.
- **Fix: empty 0-byte recordings were sent to the server for transcription with no feedback to the user.** Recordings shorter than about 1 second are now discarded with a "Recording too short" message.
- **Fix: force-stopping the keyboard during transcription permanently corrupted the recordings list.** Stale transcription markers are now cleaned up on startup.
- **Fix: the storage cap could get permanently stuck.** The cap now always frees space, even in edge cases where previous cleanup attempts failed.
- **Fix: failed recording deletions were silently ignored.** Failed deletions are now reported to the user, and the storage cap only counts deletions that actually succeeded.
- **Fix: the keyboard disappeared (white screen) when inserting text from the recordings list.** The overlay is now removed before text insertion begins.
- **Storage cap raised from 10 to 50 recordings.** All 50 are visible and manageable from the keyboard overlay.
- **Recording filenames now use millisecond precision** to prevent filename collisions on rapid stop-start.
- **Flat 500ms retry delay** instead of exponential backoff (14 seconds worst case → 1.5 seconds).
- **The recordings list no longer grows beyond the keyboard height.** It now scrolls within the normal keyboard area.
- **Pressing "Cancel" during transcription now takes effect immediately.**
- **All silent failures now show a message.** "No speech detected," "Recording too short," "Failed to delete recording," "Warning: transcription not saved to disk."

### vibevoice-v0.4.1

- **Fix: text insertion not working.** Transcription results were not being typed into the text field. The overlay was kept visible after auto-insert, which interfered with text commitment.
- **Fix: transcription text not saved to disk.** The file save method silently failed on Android external storage. Replaced with a method that works reliably.

### vibevoice-v0.4.0

Redesigned voice input UX around a state-driven overlay with a landing menu, persistent recordings, and full cancellability.

- **Landing menu.** Tapping the mic icon opens a landing screen with "New Recording" and "Recordings" options instead of immediately starting recording.
- **Auto-insert.** Transcription results are automatically typed into the text field when complete. No extra tap required.
- **Recordings are never lost.** Voice recordings are saved to user-accessible storage (`Android/data/<app>/files/vibevoice_recordings/`), browsable in any file manager.
- **Recordings list in the overlay.** Browse, re-transcribe, re-insert, copy, or delete past recordings directly from the keyboard overlay.
- **Automatic retry.** Transcription retries up to 3 times on network failure.
- **Background transcription survives keyboard close.** If the keyboard hides during recording or transcription, work continues in the background. The overlay reattaches when the keyboard reopens.
- **Instant cancellation.** Every state is cancellable. Cancelling during transcription immediately aborts the HTTP connection. The recording is always preserved on disk.
- **Saved Recordings in Settings.** Settings > Voice Input > Saved Recordings provides a full management screen.
- **Increased timeouts.** HTTP read timeout raised to 10 minutes to support long recordings.

### vibevoice-v0.3.0

Internal release (superseded by v0.4.0).

### vibevoice-v0.2.0

- Voice typing and clipboard paste now work in [RustDesk](https://github.com/rustdesk/rustdesk) remote sessions on Android smartphones.

### vibevoice-v0.1.0

Initial release. Fork of [HeliBoard v3.5](https://github.com/Helium314/HeliBoard/releases/tag/v3.5) with the stock voice input completely replaced by a custom VibeVoice ASR client.

- Voice typing via a self-hosted [vibe-voice-vendor-1](https://github.com/BigBIueWhale/vibe-voice-vendor-1) server (Microsoft VibeVoice model)
- Records 16 kHz mono WAV audio on-device and uploads via TLS 1.3 with pinned server public-key identity and mTLS client certificate/key
- Server streams back transcription via Server-Sent Events
- Recording overlay with live amplitude visualization, themed to match keyboard colors
- Settings screen for server URL, server public key pin, client certificate, client private key, and authenticated connection test
- Supports the hardened VVV public proxy security model without trust-all TLS or hostname-verifier bypasses
- Multilingual — transcription language depends on the VibeVoice model, not the keyboard layout
- Debug APK build with minification enabled for small APK size
- Automated releases via GitHub Actions on `vibevoice-v*` tags
