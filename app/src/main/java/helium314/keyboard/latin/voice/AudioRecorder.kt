// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records audio from the microphone and writes a valid WAV file.
 * 16-bit PCM mono at 16 kHz — the standard for speech recognition.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAV_HEADER_SIZE = 44
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var outputFile: File? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null

    /** Start recording to a temporary WAV file. Returns the File that will contain the recording. */
    fun start(cacheDir: File, onAmplitude: ((Float) -> Unit)? = null): File {
        if (isRecording.get()) throw IllegalStateException("Already recording")

        amplitudeCallback = onAmplitude
        val file = File(cacheDir, "vibevoice_recording.wav")
        outputFile = file

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            4096
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        ).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) {
                it.release()
                throw IllegalStateException("AudioRecord failed to initialize")
            }
        }

        isRecording.set(true)
        audioRecord!!.startRecording()

        recordingThread = Thread({
            writeWavFile(file, bufferSize)
        }, "AudioRecorder").apply { start() }

        Log.i(TAG, "Recording started → ${file.absolutePath}")
        return file
    }

    /** Stop recording. The WAV file is finalized and ready for upload. */
    fun stop() {
        if (!isRecording.getAndSet(false)) return
        recordingThread?.join(2000)
        recordingThread = null
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Recording stopped, file size: ${outputFile?.length() ?: 0} bytes")
    }

    val isActive get() = isRecording.get()

    private fun writeWavFile(file: File, bufferSize: Int) {
        val raf = RandomAccessFile(file, "rw")
        try {
            // Write placeholder header — will be patched when done
            raf.write(ByteArray(WAV_HEADER_SIZE))

            val buffer = ShortArray(bufferSize / 2)
            var totalSamples = 0L

            while (isRecording.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    // Write PCM data as little-endian 16-bit
                    val byteBuffer = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    raf.write(byteBuffer)
                    totalSamples += read

                    // Report RMS amplitude (0.0–1.0) for UI visualization
                    amplitudeCallback?.let { cb ->
                        var sum = 0.0
                        for (i in 0 until read) {
                            val s = buffer[i].toDouble()
                            sum += s * s
                        }
                        val rms = Math.sqrt(sum / read) / Short.MAX_VALUE
                        cb(rms.toFloat().coerceIn(0f, 1f))
                    }
                }
            }

            // Patch the WAV header with correct sizes
            val dataSize = totalSamples * 2  // 16-bit = 2 bytes per sample
            raf.seek(0)
            raf.write(buildWavHeader(dataSize))
        } finally {
            raf.close()
        }
    }

    private fun buildWavHeader(dataSize: Long): ByteArray {
        val totalSize = dataSize + WAV_HEADER_SIZE - 8
        val byteRate = SAMPLE_RATE * 1 * 2  // sampleRate * channels * bytesPerSample
        val blockAlign = 1 * 2              // channels * bytesPerSample

        return ByteArray(WAV_HEADER_SIZE).also { h ->
            // RIFF header
            h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte()
            h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
            writeInt32LE(h, 4, totalSize.toInt())
            h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte()
            h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
            // fmt sub-chunk
            h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte()
            h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
            writeInt32LE(h, 16, 16)          // sub-chunk size (PCM = 16)
            writeInt16LE(h, 20, 1)           // audio format (PCM = 1)
            writeInt16LE(h, 22, 1)           // channels
            writeInt32LE(h, 24, SAMPLE_RATE) // sample rate
            writeInt32LE(h, 28, byteRate)    // byte rate
            writeInt16LE(h, 32, blockAlign)  // block align
            writeInt16LE(h, 34, 16)          // bits per sample
            // data sub-chunk
            h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte()
            h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
            writeInt32LE(h, 40, dataSize.toInt())
        }
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
        buf[offset + 2] = (value shr 16 and 0xFF).toByte()
        buf[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = (value shr 8 and 0xFF).toByte()
    }
}
