// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.painterResource
import helium314.keyboard.latin.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.voice.RecordingInfo
import helium314.keyboard.latin.voice.RecordingStore
import helium314.keyboard.latin.voice.VibeVoiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRecordingsScreen(onClickBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { RecordingStore(context) }
    // Use a revision counter to force recomposition when recordings change
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val recordings = remember(revision) { store.listRecordings() }
    val totalMb = remember(revision) {
        String.format("%.1f", store.totalSizeBytes() / (1024.0 * 1024.0))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Recordings") },
                navigationIcon = {
                    IconButton(onClick = onClickBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    if (recordings.isNotEmpty()) {
                        TextButton(onClick = {
                            for (r in recordings) store.delete(r.wavFile)
                            revision++
                            Toast.makeText(context, "All recordings deleted", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Delete All")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (recordings.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No saved recordings", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                item {
                    Text(
                        "${recordings.size} recording${if (recordings.size != 1) "s" else ""} ($totalMb MB)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(recordings, key = { it.wavFile.absolutePath }) { info ->
                    RecordingCard(
                        info = info,
                        context = context,
                        store = store,
                        onChanged = { revision++ }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    info: RecordingInfo,
    context: Context,
    store: RecordingStore,
    onChanged: () -> Unit
) {
    var transcribing by rememberSaveable { mutableStateOf(false) }

    val ago = remember(info.timestamp) {
        DateUtils.getRelativeTimeSpanString(
            info.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    val sizeMb = remember(info.sizeBytes) {
        String.format("%.1f MB", info.sizeBytes / (1024.0 * 1024.0))
    }
    val status = when {
        transcribing || info.isTranscribing -> "Transcribing..."
        info.hasTranscription -> "Transcribed"
        else -> "Pending"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(ago, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$sizeMb \u2022 $status",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (info.hasTranscription && info.transcriptionText != null) {
                val preview = info.transcriptionText.take(200) +
                    if (info.transcriptionText.length > 200) "..." else ""
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                // Transcribe button (if pending)
                if (!info.hasTranscription && !info.isTranscribing && !transcribing) {
                    TextButton(onClick = {
                        val client = VibeVoiceClient.fromPreferences(context)
                        if (client == null) {
                            Toast.makeText(context, "Voice input not configured", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        transcribing = true
                        store.markTranscribing(info.wavFile)
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = client.transcribe(info.wavFile)
                            store.clearTranscribing(info.wavFile)
                            if (result != null && result.text.isNotBlank()) {
                                store.saveTranscription(info.wavFile, result.text)
                            }
                            withContext(Dispatchers.Main) {
                                transcribing = false
                                onChanged()
                                val msg = if (result != null && result.text.isNotBlank())
                                    "Transcription complete"
                                else "Transcription failed"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("Transcribe")
                    }
                }
                // Copy button (if transcribed)
                if (info.hasTranscription && info.transcriptionText != null) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("VibeVoice Transcription", info.transcriptionText)
                        )
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy")
                    }
                }
                // Delete button
                TextButton(onClick = {
                    store.delete(info.wavFile)
                    onChanged()
                }) {
                    Text("Delete")
                }
            }
        }
    }
}
