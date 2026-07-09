// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based storage for voice recordings and their transcriptions.
 *
 * The filesystem is the source of truth:
 *   - `recording_YYYYMMdd_HHmmss_SSS.wav`         → audio file
 *   - `recording_YYYYMMdd_HHmmss_SSS.txt`          → transcription result
 *   - `recording_YYYYMMdd_HHmmss_SSS.transcribing` → marker (present = in-flight)
 *   - `recording_YYYYMMdd_HHmmss_SSS.done`         → marker (present = user has inserted/copied)
 *
 * Stale .transcribing markers from previous sessions are cleaned up on construction.
 */
class RecordingStore(context: Context) {

    companion object {
        private const val TAG = "RecordingStore"
        private const val DIR_NAME = "vibevoice_recordings"
        private const val MAX_RECORDINGS = 50
        private const val PREFIX = "recording_"
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }

    // Use external files dir so recordings are browsable in file managers at:
    // /storage/emulated/0/Android/data/<app-id>/files/vibevoice_recordings/
    // Falls back to internal storage if external is unavailable.
    val recordingsDir: File = (context.getExternalFilesDir(null) ?: context.filesDir).let { base ->
        File(base, DIR_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    init {
        clearStaleMarkers()
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

    /** Save a transcription result to disk. Returns true on success. */
    fun saveTranscription(wavFile: File, text: String): Boolean {
        val txtFile = txtFileFor(wavFile)
        return try {
            txtFile.writeText(text)
            true
        } catch (e: Exception) {
            false
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

    /** Mark a recording as done (user has inserted or copied the transcription). */
    fun markDone(wavFile: File) {
        doneFileFor(wavFile).createNewFile()
    }

    /** Check if a recording has been handled (inserted/copied). */
    fun isDone(wavFile: File): Boolean {
        return doneFileFor(wavFile).exists()
    }

    /**
     * Delete a recording and all associated files (WAV + TXT + markers).
     * Returns true if the WAV file was successfully removed from disk.
     */
    fun delete(wavFile: File): Boolean {
        wavFile.delete()
        txtFileFor(wavFile).delete()
        transcribingFileFor(wavFile).delete()
        doneFileFor(wavFile).delete()
        File(wavFile.parentFile, txtFileFor(wavFile).name + ".tmp").delete()
        return !wavFile.exists()
    }

    /** Enforce the storage cap by deleting the oldest recordings beyond MAX_RECORDINGS. */
    fun enforceStorageCap() {
        val wavFiles = recordingsDir.listFiles { f -> f.extension == "wav" } ?: return
        if (wavFiles.size < MAX_RECORDINGS) return

        // Sort oldest-first for deletion. Only check marker files, not transcription text.
        val sorted = wavFiles
            .map { wav ->
                val ts = parseTimestamp(wav.nameWithoutExtension)
                val transcribing = transcribingFileFor(wav).exists()
                Triple(wav, ts, transcribing)
            }
            .sortedBy { it.second } // oldest first

        var remaining = sorted.size
        // First pass: delete oldest non-transcribing recordings
        for ((wav, _, transcribing) in sorted) {
            if (remaining < MAX_RECORDINGS) break
            if (!transcribing && delete(wav)) {
                remaining--
            }
        }
        // Second pass: if still over cap, force-delete oldest regardless
        for ((wav, _, _) in sorted) {
            if (remaining < MAX_RECORDINGS) break
            if (wav.exists() && delete(wav)) {
                remaining--
            }
        }
    }

    /** Clear .transcribing markers left over from a previous app session. */
    private fun clearStaleMarkers() {
        val markers = recordingsDir.listFiles { f -> f.extension == "transcribing" } ?: return
        for (marker in markers) {
            Log.i(TAG, "Clearing stale transcribing marker: ${marker.name}")
            marker.delete()
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

    private fun doneFileFor(wavFile: File): File {
        return File(wavFile.parentFile, wavFile.nameWithoutExtension + ".done")
    }

    private fun buildRecordingInfo(wavFile: File): RecordingInfo {
        val txtFile = txtFileFor(wavFile)
        val transcribingFile = transcribingFileFor(wavFile)
        val doneFile = doneFileFor(wavFile)
        val timestamp = parseTimestamp(wavFile.nameWithoutExtension)
        return RecordingInfo(
            wavFile = wavFile,
            timestamp = timestamp,
            sizeBytes = wavFile.length(),
            hasTranscription = txtFile.exists(),
            isTranscribing = transcribingFile.exists(),
            isDone = doneFile.exists(),
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
    val isDone: Boolean,
    val transcriptionText: String?,
)
