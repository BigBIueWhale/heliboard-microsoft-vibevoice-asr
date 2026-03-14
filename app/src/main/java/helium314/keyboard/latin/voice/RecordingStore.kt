// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based storage for voice recordings and their transcriptions.
 *
 * The filesystem is the source of truth:
 *   - `recording_YYYYMMdd_HHmmss.wav`         → audio file
 *   - `recording_YYYYMMdd_HHmmss.txt`          → transcription result (present = done)
 *   - `recording_YYYYMMdd_HHmmss.transcribing` → marker (present = in-flight)
 *
 * Thread safety: transcription writes use atomic rename (write .tmp, rename to .txt).
 */
class RecordingStore(context: Context) {

    companion object {
        private const val TAG = "RecordingStore"
        private const val DIR_NAME = "vibevoice_recordings"
        private const val MAX_RECORDINGS = 10
        private const val PREFIX = "recording_"
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    val recordingsDir: File = File(DeviceProtectedUtils.getFilesDir(context), DIR_NAME).also {
        if (!it.exists()) it.mkdirs()
    }

    /** Create a new timestamped WAV file path. Enforces the storage cap first. */
    fun newRecordingFile(): File {
        enforceStorageCap()
        val timestamp = TIMESTAMP_FORMAT.format(Date())
        return File(recordingsDir, "${PREFIX}${timestamp}.wav")
    }

    /** List all recordings, newest first. */
    fun listRecordings(): List<RecordingInfo> {
        val wavFiles = recordingsDir.listFiles { f -> f.extension == "wav" } ?: return emptyList()
        return wavFiles
            .map { wav -> buildRecordingInfo(wav) }
            .sortedByDescending { it.timestamp }
    }

    /** Read the transcription text for a recording, or null if not yet transcribed. */
    fun getTranscription(wavFile: File): String? {
        val txtFile = txtFileFor(wavFile)
        return if (txtFile.exists()) txtFile.readText() else null
    }

    /** Atomically save a transcription result. Writes to .tmp then renames. */
    fun saveTranscription(wavFile: File, text: String) {
        val txtFile = txtFileFor(wavFile)
        val tmpFile = File(txtFile.parentFile, txtFile.name + ".tmp")
        try {
            tmpFile.writeText(text)
            tmpFile.renameTo(txtFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transcription for ${wavFile.name}", e)
            tmpFile.delete()
        }
    }

    /** Mark a recording as currently being transcribed. */
    fun markTranscribing(wavFile: File) {
        transcribingFileFor(wavFile).createNewFile()
    }

    /** Clear the transcribing marker. */
    fun clearTranscribing(wavFile: File) {
        transcribingFileFor(wavFile).delete()
    }

    /** Check if a recording is currently being transcribed. */
    fun isTranscribing(wavFile: File): Boolean {
        return transcribingFileFor(wavFile).exists()
    }

    /** Delete a recording and all associated files (WAV + TXT + .transcribing). */
    fun delete(wavFile: File) {
        wavFile.delete()
        txtFileFor(wavFile).delete()
        transcribingFileFor(wavFile).delete()
        // Also clean up any leftover .tmp
        File(wavFile.parentFile, txtFileFor(wavFile).name + ".tmp").delete()
    }

    /** Enforce the storage cap by deleting the oldest recordings beyond MAX_RECORDINGS. */
    fun enforceStorageCap() {
        val recordings = listRecordings()
        if (recordings.size >= MAX_RECORDINGS) {
            // Delete oldest recordings to make room, but skip any that are currently transcribing
            val toDelete = recordings
                .filter { !it.isTranscribing }
                .drop(MAX_RECORDINGS - 1) // keep MAX_RECORDINGS - 1 to make room for the new one
            for (info in toDelete) {
                Log.i(TAG, "Storage cap: deleting ${info.wavFile.name}")
                delete(info.wavFile)
            }
        }
    }

    /** Total size of all recordings in bytes. */
    fun totalSizeBytes(): Long {
        return recordingsDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun txtFileFor(wavFile: File): File {
        return File(wavFile.parentFile, wavFile.nameWithoutExtension + ".txt")
    }

    private fun transcribingFileFor(wavFile: File): File {
        return File(wavFile.parentFile, wavFile.nameWithoutExtension + ".transcribing")
    }

    private fun buildRecordingInfo(wavFile: File): RecordingInfo {
        val txtFile = txtFileFor(wavFile)
        val transcribingFile = transcribingFileFor(wavFile)
        val timestamp = parseTimestamp(wavFile.nameWithoutExtension)
        return RecordingInfo(
            wavFile = wavFile,
            timestamp = timestamp,
            sizeBytes = wavFile.length(),
            hasTranscription = txtFile.exists(),
            isTranscribing = transcribingFile.exists(),
            transcriptionText = if (txtFile.exists()) txtFile.readText() else null
        )
    }

    private fun parseTimestamp(name: String): Long {
        return try {
            val dateStr = name.removePrefix(PREFIX)
            TIMESTAMP_FORMAT.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

data class RecordingInfo(
    val wavFile: File,
    val timestamp: Long,
    val sizeBytes: Long,
    val hasTranscription: Boolean,
    val isTranscribing: Boolean,
    val transcriptionText: String?,
)
