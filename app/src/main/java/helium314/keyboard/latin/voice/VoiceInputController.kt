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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
 * Orchestrates the full voice input lifecycle with a state-driven overlay UI.
 *
 * The overlay is a single surface that renders whatever the current state dictates.
 * There is no navigation stack — the state machine IS the UI truth.
 *
 * States: IDLE → LANDING → RECORDING → TRANSCRIBING → (back to LANDING)
 *                       └→ RECORDINGS_LIST → (back to LANDING)
 *
 * Recordings persist on disk (never auto-deleted). The storage cap handles cleanup.
 */
class VoiceInputController(
    private val context: Context,
    private val commitText: (String) -> Unit,
    private val isEditorConnected: () -> Boolean,
) {

    companion object {
        private const val TAG = "VoiceInputController"
        private const val TRANSCRIPTION_TIMEOUT_MS = 600_000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 2_000L
    }

    private val recorder = AudioRecorder()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    val store = RecordingStore(context)

    private var overlayView: VoiceOverlayView? = null
    private var hostContainer: ViewGroup? = null
    private var recordingFile: File? = null
    private var generation = 0
    private var timeoutRunnable: Runnable? = null
    private var backgroundMode = false
    private var activeClient: VibeVoiceClient? = null

    enum class State { IDLE, LANDING, RECORDING, TRANSCRIBING, RECORDINGS_LIST }
    var state = State.IDLE
        private set

    // ── Public API (called from LatinIME) ────────────────────────────

    /** Open the voice input overlay showing the landing menu. */
    @JvmOverloads
    fun start(container: ViewGroup, skipChecks: Boolean = false) {
        if (state != State.IDLE) return

        if (!skipChecks) {
            if (!VibeVoiceClient.isConfigured(context)) {
                Toast.makeText(context, R.string.vibevoice_not_configured, Toast.LENGTH_LONG).show()
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(context,
                    "Microphone permission required.\nGrant it in Settings \u2192 Apps \u2192 HeliBoard \u2192 Permissions.",
                    Toast.LENGTH_LONG).show()
                return
            }
        }

        hostContainer = container
        state = State.LANDING

        val overlay = VoiceOverlayView(context)
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView = overlay
        container.addView(overlay)
        overlay.showLanding()
    }

    /** Stop recording and begin transcription. */
    fun stop() {
        if (state != State.RECORDING) return
        state = State.TRANSCRIBING
        recorder.stop()
        overlayView?.showTranscribing()
        startTranscription()
    }

    /** Universal dismiss/cancel. Files always stay on disk. */
    fun cancel() {
        if (state == State.RECORDING) recorder.stop()
        activeClient?.abort()
        cleanup()
    }

    /** Keyboard view is being hidden. */
    fun detachOverlay() {
        when (state) {
            State.IDLE -> return
            State.LANDING, State.RECORDINGS_LIST -> cleanup()
            State.RECORDING -> {
                recorder.stop()
                backgroundMode = true
                removeOverlayViews()
                state = State.TRANSCRIBING
                startTranscription()
            }
            State.TRANSCRIBING -> {
                backgroundMode = true
                removeOverlayViews()
            }
        }
    }

    /** Keyboard view became visible again. */
    fun reattachIfNeeded(container: ViewGroup) {
        if (state == State.TRANSCRIBING && backgroundMode) {
            backgroundMode = false
            hostContainer = container
            val overlay = VoiceOverlayView(context)
            overlay.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            overlayView = overlay
            container.addView(overlay)
            overlay.showTranscribing()
        }
    }

    // ── Internal: recording ──────────────────────────────────────────

    internal fun beginRecording() {
        state = State.RECORDING
        try {
            val outputFile = store.newRecordingFile()
            recordingFile = outputFile
            overlayView?.showRecording()
            recorder.start(outputFile) { amplitude ->
                mainHandler.post { overlayView?.updateAmplitude(amplitude) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
            transitionToLanding()
        }
    }

    // ── Internal: transcription ──────────────────────────────────────

    private fun startTranscription() {
        val audioFile = recordingFile ?: run { transitionToLanding(); return }

        store.markTranscribing(audioFile)

        val currentGen = generation
        val timeout = Runnable {
            if (currentGen == generation && state == State.TRANSCRIBING) {
                Toast.makeText(context, "Transcription timed out", Toast.LENGTH_SHORT).show()
                store.clearTranscribing(audioFile)
                transitionToLanding()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, TRANSCRIPTION_TIMEOUT_MS)

        val client = VibeVoiceClient.fromPreferences(context)
        if (client == null) {
            Toast.makeText(context, R.string.vibevoice_not_configured, Toast.LENGTH_SHORT).show()
            store.clearTranscribing(audioFile)
            transitionToLanding()
            return
        }
        activeClient = client

        executor.execute {
            var result: VibeVoiceClient.TranscriptionResult? = null

            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                if (generation != currentGen) {
                    store.clearTranscribing(audioFile)
                    return@execute
                }
                result = client.transcribe(audioFile)
                if (result != null) break
                if (attempt < MAX_RETRY_ATTEMPTS) {
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
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                timeoutRunnable = null
                activeClient = null

                if (result != null && result.text.isNotBlank()) {
                    store.saveTranscription(audioFile, result.text)
                    store.markDone(audioFile)

                    if (!backgroundMode) {
                        if (isEditorConnected()) {
                            commitText(result.text)
                            Log.i(TAG, "Transcription auto-inserted (${result.text.length} chars)")
                        } else {
                            copyToClipboard(result.text)
                            Toast.makeText(context, "Copied to clipboard (editor disconnected)", Toast.LENGTH_LONG).show()
                        }
                        cleanup()
                    } else {
                        Toast.makeText(context, "Transcription ready — tap mic to insert", Toast.LENGTH_LONG).show()
                        cleanup()
                    }
                } else if (result != null) {
                    Log.i(TAG, "Transcription was empty/silence")
                    store.delete(audioFile)
                    if (!backgroundMode) transitionToLanding() else cleanup()
                } else {
                    Toast.makeText(context, "Transcription failed — recording saved", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "All $MAX_RETRY_ATTEMPTS attempts failed")
                    if (!backgroundMode) transitionToLanding() else cleanup()
                }
            }
        }
    }

    // ── Internal: recordings list actions ─────────────────────────────

    internal fun onListInsert(info: RecordingInfo) {
        val text = info.transcriptionText ?: return
        if (!info.isDone) store.markDone(info.wavFile)
        if (isEditorConnected()) {
            commitText(text)
            Log.i(TAG, "Inserted from recordings list (${text.length} chars)")
        } else {
            copyToClipboard(text)
            Toast.makeText(context, "Copied to clipboard (editor disconnected)", Toast.LENGTH_SHORT).show()
        }
        cleanup()
    }

    internal fun onListCopy(info: RecordingInfo) {
        val text = info.transcriptionText ?: return
        copyToClipboard(text)
        if (!info.isDone) store.markDone(info.wavFile)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        overlayView?.refreshRecordingsList()
    }

    internal fun onListTranscribe(info: RecordingInfo) {
        recordingFile = info.wavFile
        state = State.TRANSCRIBING
        overlayView?.showTranscribing()
        startTranscription()
    }

    internal fun onListDelete(info: RecordingInfo) {
        store.delete(info.wavFile)
        overlayView?.refreshRecordingsList()
    }

    internal fun onListBack() {
        state = State.LANDING
        overlayView?.showLanding()
    }

    // ── Internal: state transitions ──────────────────────────────────

    private fun transitionToLanding() {
        state = State.LANDING
        recordingFile = null
        backgroundMode = false
        activeClient = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        overlayView?.showLanding()
    }

    private fun cleanup() {
        generation++
        state = State.IDLE
        backgroundMode = false
        recordingFile = null
        activeClient = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        removeOverlayViews()
    }

    private fun removeOverlayViews() {
        overlayView?.let { hostContainer?.removeView(it) }
        overlayView = null
        hostContainer = null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VibeVoice Transcription", text))
    }

    // ════════════════════════════════════════════════════════════════════
    // Overlay View — state-driven, single surface
    // ════════════════════════════════════════════════════════════════════

    private inner class VoiceOverlayView(context: Context) : FrameLayout(context) {

        private val colors = Settings.getValues().mColors
        private val keyColor = colors.get(ColorType.KEY_TEXT)
        private val bgColor = colors.get(ColorType.MAIN_BACKGROUND)
        private val dimColor = Color.argb(120, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor))

        private val contentFrame: FrameLayout

        init {
            setBackgroundColor(Color.argb(230, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor)))
            contentFrame = FrameLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }
            addView(contentFrame)
        }

        // ── Landing ─────────────────────────────────────────────

        fun showLanding() {
            contentFrame.removeAllViews()
            val count = this@VoiceInputController.store.listRecordings().size

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            // Mic emoji
            layout.addView(TextView(context).apply {
                text = "\uD83C\uDF99"
                textSize = 48f
                gravity = Gravity.CENTER
                layoutParams = linParams(bottomMargin = 20)
            })

            // "New Recording" button
            layout.addView(makeStyledButton("New Recording", primary = true).apply {
                layoutParams = linParams(width = dpToPx(220), bottomMargin = 12)
                setOnClickListener { this@VoiceInputController.beginRecording() }
            })

            // "Recordings (N)" button
            val recLabel = if (count > 0) "Recordings ($count)" else "Recordings"
            layout.addView(makeStyledButton(recLabel, primary = false).apply {
                layoutParams = linParams(width = dpToPx(220))
                setOnClickListener {
                    this@VoiceInputController.state = State.RECORDINGS_LIST
                    showRecordingsList()
                }
            })

            contentFrame.addView(layout)
        }

        // ── Recording ───────────────────────────────────────────

        private var amplitudeView: AmplitudeBarsView? = null
        private var pulseAnimator: ValueAnimator? = null

        fun showRecording() {
            contentFrame.removeAllViews()
            stopPulse()

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            layout.addView(TextView(context).apply {
                text = "\uD83C\uDF99"
                textSize = 48f
                gravity = Gravity.CENTER
                layoutParams = linParams(bottomMargin = 16)
            })

            val amp = AmplitudeBarsView(context).apply {
                layoutParams = linParams(
                    width = LinearLayout.LayoutParams.MATCH_PARENT,
                    height = dpToPx(40),
                    leftMargin = 40, rightMargin = 40, bottomMargin = 12
                )
            }
            amplitudeView = amp
            layout.addView(amp)

            layout.addView(TextView(context).apply {
                text = "Listening..."
                textSize = 16f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                layoutParams = linParams(bottomMargin = 4)
            })

            layout.addView(TextView(context).apply {
                text = "Tap to stop"
                textSize = 12f
                setTextColor(dimColor)
                gravity = Gravity.CENTER
            })

            contentFrame.addView(layout)
            setOnClickListener { this@VoiceInputController.stop() }
            startPulse()
        }

        fun updateAmplitude(amplitude: Float) {
            amplitudeView?.setAmplitude(amplitude)
        }

        // ── Transcribing ────────────────────────────────────────

        fun showTranscribing() {
            contentFrame.removeAllViews()
            stopPulse()
            setOnClickListener(null)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            layout.addView(TextView(context).apply {
                text = "\uD83C\uDF99"
                textSize = 48f
                gravity = Gravity.CENTER
                layoutParams = linParams(bottomMargin = 16)
            })

            val dots = PulsingDotsView(context).apply {
                layoutParams = linParams(width = dpToPx(80), height = dpToPx(40), bottomMargin = 12)
            }
            layout.addView(dots)
            dots.startAnimation()

            layout.addView(TextView(context).apply {
                text = "Transcribing..."
                textSize = 16f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                layoutParams = linParams(bottomMargin = 12)
            })

            layout.addView(makeStyledButton("Cancel", primary = false).apply {
                setOnClickListener { this@VoiceInputController.cancel() }
            })

            contentFrame.addView(layout)
        }

        // ── Recordings List ─────────────────────────────────────

        fun showRecordingsList() {
            contentFrame.removeAllViews()
            stopPulse()
            setOnClickListener(null)

            val recordings = this@VoiceInputController.store.listRecordings()

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            // Header
            val header = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }
            header.addView(makeStyledButton("\u2190 Back", primary = false).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
                setOnClickListener { this@VoiceInputController.onListBack() }
            })
            header.addView(TextView(context).apply {
                text = "Recordings"
                textSize = 16f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
            root.addView(header)

            // Divider
            root.addView(View(context).apply {
                setBackgroundColor(Color.argb(40, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })

            if (recordings.isEmpty()) {
                root.addView(TextView(context).apply {
                    text = "No recordings yet"
                    textSize = 14f
                    setTextColor(dimColor)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                })
            } else {
                val scroll = ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                    isVerticalScrollBarEnabled = true
                }
                val list = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                }
                for (info in recordings) {
                    list.addView(buildRecordingCard(info))
                }
                scroll.addView(list)
                root.addView(scroll)
            }

            contentFrame.addView(root)
        }

        fun refreshRecordingsList() {
            if (this@VoiceInputController.state == State.RECORDINGS_LIST) {
                showRecordingsList()
            }
        }

        private fun buildRecordingCard(info: RecordingInfo): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dpToPx(6)
                layoutParams = lp
                background = GradientDrawable().apply {
                    setColor(Color.argb(20, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                    cornerRadius = dpToPx(8).toFloat()
                }
            }

            // Row 1: timestamp + size
            val ago = DateUtils.getRelativeTimeSpanString(
                info.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val sizeMb = String.format("%.1f MB", info.sizeBytes / (1024.0 * 1024.0))
            card.addView(TextView(context).apply {
                text = "$ago  \u2022  $sizeMb"
                textSize = 13f
                setTextColor(keyColor)
            })

            // Row 2: status
            val status = when {
                info.isTranscribing -> "Transcribing..."
                info.isDone -> "Done \u2713"
                info.hasTranscription -> "Ready to insert"
                else -> "Pending"
            }
            val statusColor = when {
                info.isDone -> Color.argb(200, 80, 180, 80)
                info.hasTranscription -> Color.argb(200, 80, 140, 220)
                info.isTranscribing -> dimColor
                else -> Color.argb(200, 220, 180, 60)
            }
            card.addView(TextView(context).apply {
                text = status
                textSize = 12f
                setTextColor(statusColor)
                layoutParams = linParams(topMargin = 2)
            })

            // Row 3: preview
            if (info.transcriptionText != null) {
                card.addView(TextView(context).apply {
                    text = info.transcriptionText
                    textSize = 11f
                    setTextColor(dimColor)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = linParams(topMargin = 4)
                })
            }

            // Row 4: action buttons
            if (!info.isTranscribing) {
                val buttons = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams = linParams(topMargin = 6, width = LinearLayout.LayoutParams.MATCH_PARENT)
                }

                if (info.hasTranscription) {
                    buttons.addView(makeSmallButton("Insert").apply {
                        setOnClickListener { this@VoiceInputController.onListInsert(info) }
                    })
                    buttons.addView(makeSmallButton("Copy").apply {
                        setOnClickListener { this@VoiceInputController.onListCopy(info) }
                    })
                } else {
                    buttons.addView(makeSmallButton("Transcribe").apply {
                        setOnClickListener { this@VoiceInputController.onListTranscribe(info) }
                    })
                }
                buttons.addView(makeSmallButton("Delete").apply {
                    setOnClickListener { this@VoiceInputController.onListDelete(info) }
                })

                card.addView(buttons)
            }

            return card
        }

        // ── Button helpers ──────────────────────────────────────

        private fun makeStyledButton(label: String, primary: Boolean): TextView {
            return TextView(context).apply {
                text = label
                textSize = if (primary) 16f else 14f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
                background = makeButtonBg(if (primary) 35 else 20)
                isClickable = true
                isFocusable = true
            }
        }

        private fun makeSmallButton(label: String): TextView {
            return TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                setPadding(dpToPx(10), dpToPx(5), dpToPx(10), dpToPx(5))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginStart = dpToPx(4)
                layoutParams = lp
                background = makeButtonBg(20)
                isClickable = true
                isFocusable = true
            }
        }

        private fun makeButtonBg(normalAlpha: Int): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(Color.argb(normalAlpha, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                cornerRadius = dpToPx(8).toFloat()
            }
            val pressed = GradientDrawable().apply {
                setColor(Color.argb(normalAlpha + 30, Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)))
                cornerRadius = dpToPx(8).toFloat()
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
        }

        // ── Layout helpers ──────────────────────────────────────

        private fun linParams(
            width: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
            height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
            topMargin: Int = 0, bottomMargin: Int = 0,
            leftMargin: Int = 0, rightMargin: Int = 0,
        ): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(width, height).apply {
                this.topMargin = dpToPx(topMargin)
                this.bottomMargin = dpToPx(bottomMargin)
                this.leftMargin = dpToPx(leftMargin)
                this.rightMargin = dpToPx(rightMargin)
            }
        }

        // ── Animations ──────────────────────────────────────────

        private fun startPulse() {
            pulseAnimator = ValueAnimator.ofFloat(1f, 1.08f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val s = it.animatedValue as Float
                    scaleX = s; scaleY = s
                }
                start()
            }
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            scaleX = 1f; scaleY = 1f
        }

        private fun dpToPx(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
    }

    // ════════════════════════════════════════════════════════════════════
    // Amplitude bars
    // ════════════════════════════════════════════════════════════════════

    private class AmplitudeBarsView(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
            alpha = 180
            style = Paint.Style.FILL
        }
        private val barCount = 24
        private val barWidthFraction = 0.6f
        private val history = FloatArray(barCount)
        private var idx = 0
        private val r = RectF()

        fun setAmplitude(a: Float) { history[idx++ % barCount] = a; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val slot = w / barCount; val bw = slot * barWidthFraction
            val gap = (slot - bw) / 2f; val minH = h * 0.08f
            for (i in 0 until barCount) {
                val amp = history[(idx + i) % barCount]
                val bh = minH + amp * (h - minH)
                val x = i * slot + gap; val top = (h - bh) / 2f
                r.set(x, top, x + bw, top + bh)
                c.drawRoundRect(r, bw / 2, bw / 2, barPaint)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Pulsing dots
    // ════════════════════════════════════════════════════════════════════

    private class PulsingDotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
            style = Paint.Style.FILL
        }
        private var anim: ValueAnimator? = null
        private var phase = 0f

        fun startAnimation() {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = h * 0.15f; val sp = w / 4
            for (i in 0..2) {
                val t = ((phase + i * 0.2f) % 1f) * Math.PI.toFloat() * 2f
                val s = 0.5f + 0.5f * kotlin.math.sin(t)
                paint.alpha = (100 + 155 * s).toInt()
                c.drawCircle(sp * (i + 1), h / 2, r * (0.7f + 0.3f * s), paint)
            }
        }
    }
}
