// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.voice.RecordingStore
import helium314.keyboard.latin.voice.VibeVoiceClient
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsContainer
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.Theme
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.previewDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceInputScreen(
    onClickBack: () -> Unit,
) {
    val items = listOf(
        SettingsWithoutKey.VIBEVOICE_IMPORT_BUNDLE,
        Settings.PREF_VIBEVOICE_SERVER_URL,
        Settings.PREF_VIBEVOICE_SERVER_SPKI_PIN,
        Settings.PREF_VIBEVOICE_CLIENT_CERTIFICATE,
        Settings.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY,
        SettingsWithoutKey.VIBEVOICE_TEST_CONNECTION,
        SettingsWithoutKey.VIBEVOICE_SAVED_RECORDINGS,
        SettingsWithoutKey.VIBEVOICE_SETUP_LINK,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice_input),
        settings = items
    )
}

fun createVoiceInputSettings(context: Context): List<Setting> {
    return listOf(
        Setting(context, SettingsWithoutKey.VIBEVOICE_IMPORT_BUNDLE,
            R.string.vibevoice_import_bundle_title, R.string.vibevoice_import_bundle_description)
        { setting ->
            val ctx = LocalContext.current
            var showDialog by rememberSaveable { mutableStateOf(false) }
            Preference(
                name = setting.title,
                description = setting.description,
                onClick = { showDialog = true }
            )
            if (showDialog) {
                TextInputDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text(stringResource(R.string.vibevoice_import_bundle_title)) },
                    textInputLabel = { Text(stringResource(R.string.vibevoice_import_bundle_description)) },
                    initialText = "",
                    singleLine = false,
                    keyboardType = KeyboardType.Password,
                    onConfirmed = {
                        val imported = VibeVoiceClient.importClientBundle(ctx, it)
                        val msgRes = if (imported) R.string.vibevoice_import_bundle_success
                            else R.string.vibevoice_import_bundle_failure
                        Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                        if (imported) showDialog = false
                    },
                    checkTextValid = VibeVoiceClient::isClientImportBundle,
                )
            }
        },
        Setting(context, Settings.PREF_VIBEVOICE_SERVER_URL,
        R.string.vibevoice_server_url_title, R.string.vibevoice_server_url_description)
    { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val prefs = LocalContext.current.protectedPrefs()
        val currentUrl = prefs.getString(setting.key, Defaults.PREF_VIBEVOICE_SERVER_URL) ?: ""
        Preference(
            name = setting.title,
            description = if (VibeVoiceClient.normalizeServerUrl(currentUrl) == null) {
                stringResource(R.string.vibevoice_not_configured_short)
            } else {
                currentUrl
            },
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.vibevoice_server_url_title)) },
                textInputLabel = { Text(stringResource(R.string.vibevoice_server_url_description)) },
                initialText = currentUrl,
                onConfirmed = {
                    val normalized = VibeVoiceClient.normalizeServerUrl(it) ?: ""
                    prefs.edit().putString(setting.key, normalized).apply()
                },
                checkTextValid = { it.isBlank() || VibeVoiceClient.normalizeServerUrl(it) != null }
            )
        }
    },
    Setting(context, Settings.PREF_VIBEVOICE_SERVER_SPKI_PIN,
        R.string.vibevoice_server_spki_pin_title, R.string.vibevoice_server_spki_pin_description)
    { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val prefs = LocalContext.current.protectedPrefs()
        val currentPin = prefs.getString(setting.key, Defaults.PREF_VIBEVOICE_SERVER_SPKI_PIN) ?: ""
        Preference(
            name = setting.title,
            description = if (VibeVoiceClient.isServerSpkiPin(currentPin)) {
                stringResource(R.string.vibevoice_configured_short)
            } else {
                stringResource(R.string.vibevoice_not_configured_short)
            },
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.vibevoice_server_spki_pin_title)) },
                textInputLabel = { Text(stringResource(R.string.vibevoice_server_spki_pin_description)) },
                initialText = currentPin,
                onConfirmed = {
                    val normalized = VibeVoiceClient.normalizeServerSpkiPin(it) ?: ""
                    prefs.edit().putString(setting.key, normalized).apply()
                },
                keyboardType = KeyboardType.Password,
                checkTextValid = { it.isBlank() || VibeVoiceClient.isServerSpkiPin(it) },
            )
        }
    },
    Setting(context, Settings.PREF_VIBEVOICE_CLIENT_CERTIFICATE,
        R.string.vibevoice_client_certificate_title, R.string.vibevoice_client_certificate_description)
    { setting ->
        PemPreference(
            setting = setting,
            default = Defaults.PREF_VIBEVOICE_CLIENT_CERTIFICATE,
            validate = VibeVoiceClient::isCertificatePem,
        )
    },
    Setting(context, Settings.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY,
        R.string.vibevoice_client_private_key_title, R.string.vibevoice_client_private_key_description)
    { setting ->
        PemPreference(
            setting = setting,
            default = Defaults.PREF_VIBEVOICE_CLIENT_PRIVATE_KEY,
            validate = VibeVoiceClient::isPrivateKeyPem,
        )
    },
    Setting(context, SettingsWithoutKey.VIBEVOICE_TEST_CONNECTION,
        R.string.vibevoice_test_connection, R.string.vibevoice_test_connection_description)
    { setting ->
        val ctx = LocalContext.current
        var testing by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = setting.title,
            description = if (testing) stringResource(R.string.vibevoice_testing) else setting.description,
            onClick = {
                if (testing) return@Preference
                val client = VibeVoiceClient.fromPreferences(ctx)
                if (client == null) {
                    Toast.makeText(ctx, R.string.vibevoice_not_configured, Toast.LENGTH_SHORT).show()
                    return@Preference
                }
                testing = true
                CoroutineScope(Dispatchers.IO).launch {
                    val healthy = client.isHealthy()
                    withContext(Dispatchers.Main) {
                        testing = false
                        val msgRes = if (healthy) R.string.vibevoice_connection_success
                            else R.string.vibevoice_connection_failure
                        Toast.makeText(ctx, msgRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    },
    Setting(context, SettingsWithoutKey.VIBEVOICE_SAVED_RECORDINGS,
        R.string.vibevoice_saved_recordings_title, R.string.vibevoice_saved_recordings_description)
    {
        val ctx = LocalContext.current
        val store = remember { RecordingStore(ctx) }
        val recordings = remember { store.listRecordings() }
        val count = recordings.size
        val totalMb = String.format("%.1f", store.totalSizeBytes() / (1024.0 * 1024.0))
        val description = if (count > 0) "$count recording${if (count != 1) "s" else ""} ($totalMb MB)"
            else "No saved recordings"
        Preference(
            name = it.title,
            description = description,
            onClick = {
                // Navigate via SettingsDestination
                helium314.keyboard.settings.SettingsDestination.navigateTo(
                    helium314.keyboard.settings.SettingsDestination.SavedRecordings
                )
            }
        )
    },
    Setting(context, SettingsWithoutKey.VIBEVOICE_SETUP_LINK,
        R.string.vibevoice_setup_link_title, R.string.vibevoice_setup_link_description)
    {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BigBIueWhale/vibe-voice-vendor-1"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        )
    },
    )
}

@Composable
private fun PemPreference(
    setting: Setting,
    default: String,
    validate: (String) -> Boolean,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.protectedPrefs()
    val currentValue = prefs.getString(setting.key, default) ?: ""
    val configured = currentValue.isNotBlank() && validate(currentValue)
    Preference(
        name = setting.title,
        description = if (configured) {
            stringResource(R.string.vibevoice_configured_short)
        } else {
            stringResource(R.string.vibevoice_not_configured_short)
        },
        onClick = { showDialog = true }
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(setting.title) },
            textInputLabel = { Text(setting.description ?: "") },
            initialText = currentValue,
            singleLine = false,
            onConfirmed = {
                val normalized = if (it.isBlank()) "" else VibeVoiceClient.normalizePem(it)
                prefs.edit().putString(setting.key, normalized).apply()
            },
            keyboardType = KeyboardType.Password,
            checkTextValid = { it.isBlank() || validate(it) },
        )
    }
}

@Preview
@Composable
private fun Preview() {
    SettingsActivity.settingsContainer = SettingsContainer(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceInputScreen { }
        }
    }
}
