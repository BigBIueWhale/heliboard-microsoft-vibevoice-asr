// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Orchestrates the full voice input lifecycle:
 *   1. Check microphone permission
 *   2. Show recording UI overlay on the keyboard
 *   3. Record audio via [AudioRecorder]
 *   4. Send to VibeVoice server via [VibeVoiceClient] (with retry)
 *   5. Show transcription result for user review
 *   6. Commit text only when user taps Insert
 *   7. Persist recordings to filesystem — never lose a recording
 */
class VoiceInputController(
    private val context: Context,
    private val commitText: (String) -> Unit,
    private val isEditorConnected: () -> Boolean,
) {

    companion object {
        private const val TAG = "VoiceInputController"
        private const val TRANSCRIPTION_TIMEOUT_MS = 600_000L  // 10 min for long recordings
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 2_000L
    }

    private val recorder = AudioRecorder()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = RecordingStore(context)

    private var overlayView: VoiceOverlayView? = null
    private var hostContainer: ViewGroup? = null
    private var recordingFile: File? = null
    private var generation = 0  // Incremented on each session to discard stale callbacks
    private var timeoutRunnable: Runnable? = null
    private var backgroundMode = false
    private var pendingResultText: String? = null
    private var activeClient: VibeVoiceClient? = null

    enum class State { IDLE, RECORDING, TRANSCRIBING, RESULT_READY }
    var state = State.IDLE
        private set

    /**
     * Start voice input. If there's a pending recording/transcription, show it
     * instead of starting a new recording. Pass [skipPendingCheck] = true to
     * bypass this and always start a fresh recording.
     */
    @JvmOverloads
    fun start(container: ViewGroup, skipPendingCheck: Boolean = false) {
        if (state != State.IDLE) {
            Log.w(TAG, "Voice input already active (state=$state)")
            return
        }

        // Check that VibeVoice is configured
        if (!VibeVoiceClient.isConfigured(context)) {
            Toast.makeText(context, R.string.vibevoice_not_configured, Toast.LENGTH_LONG).show()
            return
        }

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                context,
                "Microphone permission required for voice input.\nGrant it in Settings \u2192 Apps \u2192 HeliBoard \u2192 Permissions.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check for pending recordings before starting a new one
        if (!skipPendingCheck) {
            val pending = store.listRecordings().firstOrNull()
            if (pending != null) {
                if (pending.hasTranscription && pending.transcriptionText != null) {
                    // Result ready — show it for insertion
                    showResultOverlay(container, pending.wavFile, pending.transcriptionText)
                    return
                } else if (!pending.isTranscribing) {
                    // WAV exists but no transcription — offer retry
                    showPendingRetryOverlay(container, pending)
                    return
                }
                // If currently transcribing, fall through to show that state
                // (handled by reattachIfNeeded)
            }
        }

        hostContainer = container
        state = State.RECORDING

        // Show the recording overlay
        val overlay = VoiceOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { stop() }
        }
        overlayView = overlay
        container.addView(overlay)
        overlay.showRecording()

        // Start recording
        try {
            val outputFile = store.newRecordingFile()
            recordingFile = outputFile
            recorder.start(outputFile) { amplitude ->
                mainHandler.post { overlay.updateAmplitude(amplitude) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Stop recording and begin transcription. */
    fun stop() {
        if (state != State.RECORDING) return
        state = State.TRANSCRIBING

        recorder.stop()
        overlayView?.showTranscribing()
        startTranscription()
    }

    /** Cancel voice input. Resets controller state but does NOT delete files. */
    fun cancel() {
        if (state == State.RECORDING) {
            recorder.stop()
        }
        // Abort any in-flight HTTP request immediately
        activeClient?.abort()
        // Files stay on disk — user can access via mic button or Settings
        cleanup()
    }

    /**
     * Called when the keyboard view is being hidden (onFinishInputView).
     * Detaches the overlay but lets background work continue.
     */
    fun detachOverlay() {
        when (state) {
            State.IDLE -> return
            State.RECORDING -> {
                // Auto-stop recording and continue to transcription in background
                recorder.stop()
                backgroundMode = true
                removeOverlayViews()
                state = State.TRANSCRIBING
                startTranscription()
            }
            State.TRANSCRIBING -> {
                // Transcription continues on executor thread
                backgroundMode = true
                removeOverlayViews()
            }
            State.RESULT_READY -> {
                // Result is on filesystem, nothing more to do
                removeOverlayViews()
                state = State.IDLE
                backgroundMode = false
                pendingResultText = null
                recordingFile = null
            }
        }
    }

    /**
     * Called when the keyboard view becomes visible (onStartInputView).
     * Re-shows the transcribing overlay if work is in progress.
     */
    fun reattachIfNeeded(container: ViewGroup) {
        if (state == State.TRANSCRIBING && backgroundMode) {
            backgroundMode = false
            hostContainer = container
            val overlay = VoiceOverlayView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            overlayView = overlay
            container.addView(overlay)
            overlay.showTranscribing()
        }
        // Don't auto-show RESULT_READY — wait for mic button tap
    }

    // ---- User action handlers (called from overlay buttons) ----

    /** Insert transcription into the current text field and clean up files. */
    fun insertResult() {
        val text = pendingResultText ?: return
        if (isEditorConnected()) {
            commitText(text)
            Log.i(TAG, "Transcription inserted (${text.length} chars)")
        } else {
            // Editor not connected — fall back to clipboard
            copyToClipboard(text)
            Toast.makeText(context, "Editor disconnected — copied to clipboard", Toast.LENGTH_LONG).show()
            Log.i(TAG, "Editor disconnected, copied to clipboard instead")
        }
        recordingFile?.let { store.delete(it) }
        cleanup()
    }

    /** Copy transcription to clipboard and clean up files. */
    fun copyResult() {
        val text = pendingResultText ?: return
        copyToClipboard(text)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        recordingFile?.let { store.delete(it) }
        cleanup()
    }

    /** Discard the recording and transcription. */
    fun discardResult() {
        recordingFile?.let { store.delete(it) }
        cleanup()
    }

    /** Retry transcription of the current recording. */
    fun retryTranscription() {
        if (recordingFile == null) return
        state = State.TRANSCRIBING
        overlayView?.showTranscribing()
        startTranscription()
    }

    /** Start a new recording, leaving any old files on disk. */
    fun startNewRecording(container: ViewGroup) {
        cleanup()
        start(container, skipPendingCheck = true)
    }

    // ---- Private implementation ----

    private fun startTranscription() {
        val audioFile = recordingFile ?: run {
            cleanup()
            return
        }

        store.markTranscribing(audioFile)

        val currentGen = generation
        val timeout = Runnable {
            if (currentGen == generation && state == State.TRANSCRIBING) {
                Toast.makeText(context, "Transcription timed out", Toast.LENGTH_SHORT).show()
                store.clearTranscribing(audioFile)
                cleanup()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, TRANSCRIPTION_TIMEOUT_MS)

        val client = VibeVoiceClient.fromPreferences(context)
        if (client == null) {
            Toast.makeText(context, R.string.vibevoice_not_configured, Toast.LENGTH_SHORT).show()
            store.clearTranscribing(audioFile)
            cleanup()
            return
        }
        activeClient = client

        executor.execute {
            var result: VibeVoiceClient.TranscriptionResult? = null

            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                if (generation != currentGen) {
                    // Cancelled
                    store.clearTranscribing(audioFile)
                    return@execute
                }
                result = client.transcribe(audioFile)
                if (result != null) break
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    // Check cancellation before sleeping to avoid unnecessary delay
                    if (generation != currentGen) {
                        store.clearTranscribing(audioFile)
                        return@execute
                    }
                    Log.w(TAG, "Transcription attempt $attempt/$MAX_RETRY_ATTEMPTS failed, retrying...")
                    Thread.sleep(RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
                }
            }

            store.clearTranscribing(audioFile)

            mainHandler.post {
                if (currentGen != generation) return@post

                if (result != null && result.text.isNotBlank()) {
                    // Save transcription to filesystem
                    store.saveTranscription(audioFile, result.text)

                    if (!backgroundMode) {
                        // Overlay is attached — show result for user review
                        pendingResultText = result.text
                        state = State.RESULT_READY
                        overlayView?.showResult(result.text)
                        Log.i(TAG, "Transcription ready for review (${result.text.length} chars)")
                    } else {
                        // Keyboard is hidden — result saved to filesystem
                        Toast.makeText(context, "Transcription ready — tap mic to insert", Toast.LENGTH_LONG).show()
                        Log.i(TAG, "Transcription saved to filesystem (background)")
                        cleanup()
                    }
                } else if (result != null) {
                    // Empty/silence — nothing to save
                    Log.i(TAG, "Transcription was empty/silence")
                    store.delete(audioFile)
                    cleanup()
                } else {
                    // All retries failed — keep the WAV for future retry
                    Toast.makeText(context, "Transcription failed — recording saved", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "All $MAX_RETRY_ATTEMPTS attempts failed, recording kept: ${audioFile.absolutePath}")
                    cleanup()
                }
            }
        }
    }

    private fun showResultOverlay(container: ViewGroup, wavFile: File, text: String) {
        hostContainer = container
        recordingFile = wavFile
        pendingResultText = text
        state = State.RESULT_READY

        val overlay = VoiceOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlayView = overlay
        container.addView(overlay)
        overlay.showResult(text)
    }

    private fun showPendingRetryOverlay(container: ViewGroup, info: RecordingInfo) {
        hostContainer = container
        recordingFile = info.wavFile
        state = State.RESULT_READY  // Use RESULT_READY to block new recordings via state check

        val overlay = VoiceOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlayView = overlay
        container.addView(overlay)
        overlay.showPendingRetry(info)
    }

    private fun cleanup() {
        generation++
        state = State.IDLE
        backgroundMode = false
        pendingResultText = null
        activeClient = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        removeOverlayViews()
        recordingFile = null
    }

    private fun removeOverlayViews() {
        overlayView?.let { overlay ->
            hostContainer?.removeView(overlay)
        }
        overlayView = null
        hostContainer = null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VibeVoice Transcription", text))
    }

    // ============================================================================
    // Overlay View
    // ============================================================================

    /**
     * Custom View that overlays the keyboard during voice input.
     * Supports multiple states: recording, transcribing, result ready, pending retry.
     */
    private inner class VoiceOverlayView(
        context: Context,
    ) : FrameLayout(context) {

        private val colors = Settings.getValues().mColors
        private val keyColor = colors.get(ColorType.KEY_TEXT)
        private val bgColor = colors.get(ColorType.MAIN_BACKGROUND)

        private val innerLayout: LinearLayout
        private val micText: TextView
        private val amplitudeView: AmplitudeBarsView
        private val pulsingDotsView: PulsingDotsView
        private val statusText: TextView
        private val hintText: TextView

        // Result-ready views
        private val resultScroll: ScrollView
        private val resultText: TextView
        private val resultWordCount: TextView
        private val resultButtonRow: LinearLayout
        private val insertButton: TextView
        private val copyButton: TextView
        private val discardButton: TextView

        // Pending-retry views
        private val pendingInfoText: TextView
        private val pendingButtonRow: LinearLayout
        private val retryButton: TextView
        private val newRecordingButton: TextView
        private val pendingDiscardButton: TextView

        private var pulseAnimator: ValueAnimator? = null

        init {
            setBackgroundColor(
                Color.argb(230, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
            )

            innerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            }

            // Mic icon
            micText = TextView(context).apply {
                text = "\uD83C\uDF99"
                textSize = 48f
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 16
                layoutParams = lp
            }

            // Amplitude visualization
            amplitudeView = AmplitudeBarsView(context).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(40)
                )
                lp.leftMargin = dpToPx(40)
                lp.rightMargin = dpToPx(40)
                lp.bottomMargin = dpToPx(12)
                layoutParams = lp
            }

            // Pulsing dots
            pulsingDotsView = PulsingDotsView(context).apply {
                val lp = LinearLayout.LayoutParams(dpToPx(80), dpToPx(40))
                lp.bottomMargin = dpToPx(12)
                layoutParams = lp
                visibility = View.GONE
            }

            // Status label
            statusText = TextView(context).apply {
                text = "Listening..."
                textSize = 16f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(4)
                layoutParams = lp
            }

            hintText = TextView(context).apply {
                text = "Tap to stop"
                textSize = 12f
                setTextColor(Color.argb(150, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                gravity = Gravity.CENTER
            }

            // ---- Result-ready views ----

            resultText = TextView(context).apply {
                textSize = 14f
                setTextColor(keyColor)
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }

            resultScroll = ScrollView(context).apply {
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f  // weight = 1 to fill available space
                )
                lp.bottomMargin = dpToPx(8)
                layoutParams = lp
                addView(resultText)
                visibility = View.GONE
            }

            resultWordCount = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.argb(150, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(8)
                layoutParams = lp
                visibility = View.GONE
            }

            insertButton = makeButton("Insert")
            copyButton = makeButton("Copy")
            discardButton = makeButton("Discard")

            resultButtonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
                addView(insertButton)
                addView(copyButton)
                addView(discardButton)
                visibility = View.GONE
            }

            insertButton.setOnClickListener { this@VoiceInputController.insertResult() }
            copyButton.setOnClickListener { this@VoiceInputController.copyResult() }
            discardButton.setOnClickListener { this@VoiceInputController.discardResult() }

            // ---- Pending-retry views ----

            pendingInfoText = TextView(context).apply {
                textSize = 14f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(12)
                layoutParams = lp
                visibility = View.GONE
            }

            retryButton = makeButton("Retry")
            newRecordingButton = makeButton("New Recording")
            pendingDiscardButton = makeButton("Discard")

            pendingButtonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
                addView(retryButton)
                addView(newRecordingButton)
                addView(pendingDiscardButton)
                visibility = View.GONE
            }

            retryButton.setOnClickListener { this@VoiceInputController.retryTranscription() }
            newRecordingButton.setOnClickListener {
                val container = hostContainer ?: return@setOnClickListener
                this@VoiceInputController.startNewRecording(container)
            }
            pendingDiscardButton.setOnClickListener { this@VoiceInputController.discardResult() }

            // Assemble layout
            innerLayout.addView(micText)
            innerLayout.addView(amplitudeView)
            innerLayout.addView(pulsingDotsView)
            innerLayout.addView(statusText)
            innerLayout.addView(hintText)
            innerLayout.addView(resultScroll)
            innerLayout.addView(resultWordCount)
            innerLayout.addView(resultButtonRow)
            innerLayout.addView(pendingInfoText)
            innerLayout.addView(pendingButtonRow)
            addView(innerLayout)
        }

        fun showRecording() {
            micText.visibility = View.VISIBLE
            amplitudeView.visibility = View.VISIBLE
            pulsingDotsView.visibility = View.GONE
            statusText.text = "Listening..."
            statusText.visibility = View.VISIBLE
            hintText.text = "Tap to stop"
            hintText.visibility = View.VISIBLE
            resultScroll.visibility = View.GONE
            resultWordCount.visibility = View.GONE
            resultButtonRow.visibility = View.GONE
            pendingInfoText.visibility = View.GONE
            pendingButtonRow.visibility = View.GONE
            startPulse()
            setOnClickListener { this@VoiceInputController.stop() }
        }

        fun showTranscribing() {
            micText.visibility = View.VISIBLE
            amplitudeView.visibility = View.GONE
            pulsingDotsView.visibility = View.VISIBLE
            pulsingDotsView.startAnimation()
            statusText.text = "Transcribing..."
            statusText.visibility = View.VISIBLE
            hintText.text = ""
            hintText.visibility = View.GONE
            resultScroll.visibility = View.GONE
            resultWordCount.visibility = View.GONE
            resultButtonRow.visibility = View.GONE
            pendingInfoText.visibility = View.GONE
            pendingButtonRow.visibility = View.GONE
            stopPulse()
            // Show cancel as a single button
            setOnClickListener(null)
            // Repurpose discard button row as cancel
            discardButton.text = "Cancel"
            discardButton.setOnClickListener { this@VoiceInputController.cancel() }
            resultButtonRow.removeAllViews()
            resultButtonRow.addView(discardButton)
            resultButtonRow.visibility = View.VISIBLE
        }

        fun showResult(text: String) {
            micText.visibility = View.GONE
            amplitudeView.visibility = View.GONE
            pulsingDotsView.visibility = View.GONE
            pulsingDotsView.stopAnimation()
            statusText.text = "Transcription ready"
            statusText.visibility = View.VISIBLE
            hintText.visibility = View.GONE
            stopPulse()

            resultText.text = text
            resultScroll.visibility = View.VISIBLE

            val wordCount = text.trim().split("\\s+".toRegex()).size
            resultWordCount.text = "$wordCount words"
            resultWordCount.visibility = View.VISIBLE

            // Reset button row to result buttons
            resultButtonRow.removeAllViews()
            insertButton.text = "Insert"
            insertButton.setOnClickListener { this@VoiceInputController.insertResult() }
            copyButton.text = "Copy"
            copyButton.setOnClickListener { this@VoiceInputController.copyResult() }
            discardButton.text = "Discard"
            discardButton.setOnClickListener { this@VoiceInputController.discardResult() }
            resultButtonRow.addView(insertButton)
            resultButtonRow.addView(copyButton)
            resultButtonRow.addView(discardButton)
            resultButtonRow.visibility = View.VISIBLE

            pendingInfoText.visibility = View.GONE
            pendingButtonRow.visibility = View.GONE
            setOnClickListener(null)
        }

        fun showPendingRetry(info: RecordingInfo) {
            micText.visibility = View.VISIBLE
            amplitudeView.visibility = View.GONE
            pulsingDotsView.visibility = View.GONE
            statusText.text = "Unsent recording"
            statusText.visibility = View.VISIBLE
            hintText.visibility = View.GONE
            resultScroll.visibility = View.GONE
            resultWordCount.visibility = View.GONE
            resultButtonRow.visibility = View.GONE
            stopPulse()

            val ago = DateUtils.getRelativeTimeSpanString(
                info.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val sizeMb = String.format("%.1f", info.sizeBytes / (1024.0 * 1024.0))
            pendingInfoText.text = "Recorded $ago ($sizeMb MB)"
            pendingInfoText.visibility = View.VISIBLE
            pendingButtonRow.visibility = View.VISIBLE
            setOnClickListener(null)
        }

        fun updateAmplitude(amplitude: Float) {
            amplitudeView.setAmplitude(amplitude)
        }

        private fun makeButton(label: String): TextView {
            return TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dpToPx(4)
                lp.marginEnd = dpToPx(4)
                layoutParams = lp
            }
        }

        private fun startPulse() {
            pulseAnimator = ValueAnimator.ofFloat(1f, 1.1f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val scale = it.animatedValue as Float
                    scaleX = scale
                    scaleY = scale
                }
                start()
            }
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            scaleX = 1f
            scaleY = 1f
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * A simple amplitude bar visualizer — draws vertical bars whose height
     * responds to the current microphone amplitude.
     */
    private class AmplitudeBarsView(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val colors = Settings.getValues().mColors
            color = colors.get(ColorType.KEY_TEXT)
            alpha = 180
            style = Paint.Style.FILL
        }
        private val barCount = 24
        private val barWidthFraction = 0.6f
        private val amplitudeHistory = FloatArray(barCount)
        private var writeIndex = 0
        private val barRect = RectF()

        fun setAmplitude(amplitude: Float) {
            amplitudeHistory[writeIndex % barCount] = amplitude
            writeIndex++
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val slotWidth = w / barCount
            val barWidth = slotWidth * barWidthFraction
            val gap = (slotWidth - barWidth) / 2f
            val minBarHeight = h * 0.08f

            for (i in 0 until barCount) {
                val idx = (writeIndex + i) % barCount
                val amp = amplitudeHistory[idx]
                val barHeight = minBarHeight + amp * (h - minBarHeight)
                val x = i * slotWidth + gap
                val top = (h - barHeight) / 2f
                barRect.set(x, top, x + barWidth, top + barHeight)
                canvas.drawRoundRect(barRect, barWidth / 2, barWidth / 2, barPaint)
            }
        }
    }

    /**
     * Draws 3 pulsing dots with staggered sine-wave scale/alpha animation.
     * Shown during the TRANSCRIBING state.
     */
    private class PulsingDotsView(context: Context) : View(context) {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val colors = Settings.getValues().mColors
            color = colors.get(ColorType.KEY_TEXT)
            style = Paint.Style.FILL
        }
        private val dotCount = 3
        private val cycleDuration = 1200L
        private var animator: ValueAnimator? = null
        private var phase = 0f

        fun startAnimation() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = cycleDuration
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val baseRadius = h * 0.15f
            val spacing = w / (dotCount + 1)

            for (i in 0 until dotCount) {
                val stagger = i * 0.2f
                val t = ((phase + stagger) % 1f) * Math.PI.toFloat() * 2f
                val scale = 0.5f + 0.5f * kotlin.math.sin(t)
                val alpha = (100 + 155 * scale).toInt()

                dotPaint.alpha = alpha
                val cx = spacing * (i + 1)
                val cy = h / 2f
                canvas.drawCircle(cx, cy, baseRadius * (0.7f + 0.3f * scale), dotPaint)
            }
        }
    }
}
