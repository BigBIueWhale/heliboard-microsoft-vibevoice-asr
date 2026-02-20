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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
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
        Settings.PREF_VIBEVOICE_SERVER_URL,
        Settings.PREF_VIBEVOICE_AUTH_TOKEN,
        SettingsWithoutKey.VIBEVOICE_TEST_CONNECTION,
        SettingsWithoutKey.VIBEVOICE_SETUP_LINK,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice_input),
        settings = items
    )
}

fun createVoiceInputSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_VIBEVOICE_SERVER_URL,
        R.string.vibevoice_server_url_title, R.string.vibevoice_server_url_description)
    { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val prefs = LocalContext.current.prefs()
        val currentUrl = prefs.getString(setting.key, Defaults.PREF_VIBEVOICE_SERVER_URL) ?: ""
        Preference(
            name = setting.title,
            description = currentUrl.ifBlank { stringResource(R.string.vibevoice_not_configured_short) },
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.vibevoice_server_url_title)) },
                textInputLabel = { Text(stringResource(R.string.vibevoice_server_url_description)) },
                initialText = currentUrl,
                onConfirmed = { prefs.edit().putString(setting.key, it.trim()).apply() },
                checkTextValid = { it.isBlank() || it.trim().startsWith("https://") }
            )
        }
    },
    Setting(context, Settings.PREF_VIBEVOICE_AUTH_TOKEN,
        R.string.vibevoice_auth_token_title, R.string.vibevoice_auth_token_description)
    { setting ->
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val prefs = LocalContext.current.prefs()
        val currentToken = prefs.getString(setting.key, Defaults.PREF_VIBEVOICE_AUTH_TOKEN) ?: ""
        val maskedPreview = if (currentToken.length > 8)
            currentToken.take(4) + "..." + currentToken.takeLast(4)
        else if (currentToken.isNotBlank()) "****"
        else stringResource(R.string.vibevoice_not_configured_short)
        Preference(
            name = setting.title,
            description = maskedPreview,
            onClick = { showDialog = true }
        )
        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.vibevoice_auth_token_title)) },
                textInputLabel = { Text(stringResource(R.string.vibevoice_auth_token_description)) },
                initialText = currentToken,
                onConfirmed = { prefs.edit().putString(setting.key, it.trim()).apply() },
                keyboardType = KeyboardType.Password,
            )
        }
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
    Setting(context, SettingsWithoutKey.VIBEVOICE_SETUP_LINK,
        R.string.vibevoice_setup_link_title, R.string.vibevoice_setup_link_description)
    {
        val ctx = LocalContext.current
        Preference(
            name = it.title,
            description = it.description,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/NatanFreeman/vibe-voice-vendor"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        )
    },
)

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
