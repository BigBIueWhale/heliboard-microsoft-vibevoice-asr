// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Orchestrates the full voice input lifecycle:
 *   1. Check microphone permission
 *   2. Show recording UI overlay on the keyboard
 *   3. Record audio via [AudioRecorder]
 *   4. Send to VibeVoice server via [VibeVoiceClient]
 *   5. Commit transcribed text to the input field
 *   6. Restore the keyboard
 */
class VoiceInputController(
    private val context: Context,
    private val commitText: (String) -> Unit,
) {

    companion object {
        private const val TAG = "VoiceInputController"
        private const val TRANSCRIPTION_TIMEOUT_MS = 65_000L
    }

    private val recorder = AudioRecorder()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: VoiceOverlayView? = null
    private var hostContainer: ViewGroup? = null
    private var recordingFile: File? = null
    private var generation = 0  // Incremented on each session to discard stale callbacks
    private var timeoutRunnable: Runnable? = null

    enum class State { IDLE, RECORDING, TRANSCRIBING }
    var state = State.IDLE
        private set

    /** Start voice input. Requires a ViewGroup to attach the overlay to (the keyboard frame). */
    fun start(container: ViewGroup) {
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

        hostContainer = container
        state = State.RECORDING

        // Show the recording overlay
        val overlay = VoiceOverlayView(context, onCancel = { cancel() }).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { stop() }
        }
        overlayView = overlay
        container.addView(overlay)
        overlay.setState(State.RECORDING)

        // Start recording
        try {
            val cacheDir = context.cacheDir
            recordingFile = recorder.start(cacheDir) { amplitude ->
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
        overlayView?.setState(State.TRANSCRIBING)

        val audioFile = recordingFile ?: run {
            cleanup()
            return
        }

        // Safety timeout for transcription
        val currentGen = generation
        val timeout = Runnable {
            if (currentGen == generation && state == State.TRANSCRIBING) {
                Toast.makeText(context, "Transcription timed out", Toast.LENGTH_SHORT).show()
                cancel()
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, TRANSCRIPTION_TIMEOUT_MS)

        val client = VibeVoiceClient.fromPreferences(context)
        if (client == null) {
            Toast.makeText(context, R.string.vibevoice_not_configured, Toast.LENGTH_SHORT).show()
            cleanup()
            return
        }

        executor.execute {
            try {
                val result = client.transcribe(audioFile)

                mainHandler.post {
                    // Discard result if this session was cancelled
                    if (currentGen != generation) return@post

                    if (result != null && result.text.isNotBlank()) {
                        commitText(result.text)
                        Log.i(TAG, "Transcription committed: \"${result.text}\"")
                    } else if (result != null) {
                        Log.i(TAG, "Transcription was empty/silence")
                    } else {
                        Toast.makeText(context, "Transcription failed \u2014 server unreachable?", Toast.LENGTH_SHORT).show()
                    }
                    cleanup()
                }
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Transcription timed out", e)
                mainHandler.post {
                    if (currentGen != generation) return@post
                    Toast.makeText(context, "Transcription timed out", Toast.LENGTH_SHORT).show()
                    cleanup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                mainHandler.post {
                    if (currentGen != generation) return@post
                    val msg = when (e) {
                        is ConnectException, is UnknownHostException -> "Cannot reach server"
                        else -> "Voice input error: ${e.message}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    cleanup()
                }
            } finally {
                audioFile.delete()
            }
        }
    }

    /** Cancel voice input without committing any text. */
    fun cancel() {
        if (state == State.RECORDING) {
            recorder.stop()
        }
        recordingFile?.delete()
        cleanup()
    }

    private fun cleanup() {
        generation++
        state = State.IDLE
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        overlayView?.let { overlay ->
            hostContainer?.removeView(overlay)
        }
        overlayView = null
        hostContainer = null
        recordingFile = null
    }

    /**
     * Custom View that overlays the keyboard during voice input.
     * Shows a mic icon, recording indicator, amplitude bars, and status text.
     * Tap anywhere to stop recording. Cancel button visible during transcription.
     */
    private class VoiceOverlayView(
        context: Context,
        private val onCancel: () -> Unit
    ) : FrameLayout(context) {

        private val statusText: TextView
        private val amplitudeView: AmplitudeBarsView
        private val pulsingDotsView: PulsingDotsView
        private val cancelButton: TextView
        private val hintText: TextView
        private var pulseAnimator: ValueAnimator? = null

        init {
            // Semi-transparent background over the keyboard
            val colors = Settings.getValues().mColors
            val bgColor = colors.get(ColorType.MAIN_BACKGROUND)
            // Use the keyboard's background color with high opacity
            setBackgroundColor(
                Color.argb(230, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
            )

            val keyColor = colors.get(ColorType.KEY_TEXT)

            val innerLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            }

            // Mic icon (using a simple Unicode character — works everywhere)
            val micText = TextView(context).apply {
                text = "\uD83C\uDF99"  // studio microphone emoji
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

            // Pulsing dots for transcription state
            pulsingDotsView = PulsingDotsView(context).apply {
                val lp = LinearLayout.LayoutParams(
                    dpToPx(80),
                    dpToPx(40)
                )
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
                setTextColor(Color.argb(
                    150,
                    Color.red(keyColor), Color.green(keyColor), Color.blue(keyColor)
                ))
                gravity = Gravity.CENTER
            }

            // Cancel button — visible during transcription
            cancelButton = TextView(context).apply {
                text = "Cancel"
                textSize = 14f
                setTextColor(keyColor)
                gravity = Gravity.CENTER
                setPadding(dpToPx(24), dpToPx(8), dpToPx(24), dpToPx(8))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dpToPx(8)
                layoutParams = lp
                visibility = View.GONE
                setOnClickListener { onCancel() }
            }

            innerLayout.addView(micText)
            innerLayout.addView(amplitudeView)
            innerLayout.addView(pulsingDotsView)
            innerLayout.addView(statusText)
            innerLayout.addView(hintText)
            innerLayout.addView(cancelButton)
            addView(innerLayout)
        }

        fun setState(state: State) {
            when (state) {
                State.RECORDING -> {
                    statusText.text = "Listening..."
                    hintText.text = "Tap to stop"
                    amplitudeView.visibility = View.VISIBLE
                    pulsingDotsView.visibility = View.GONE
                    cancelButton.visibility = View.GONE
                    startPulse()
                }
                State.TRANSCRIBING -> {
                    statusText.text = "Transcribing..."
                    hintText.text = ""
                    amplitudeView.visibility = View.GONE
                    pulsingDotsView.visibility = View.VISIBLE
                    pulsingDotsView.startAnimation()
                    cancelButton.visibility = View.VISIBLE
                    stopPulse()
                    // Disable the overlay click-to-stop during transcription
                    setOnClickListener(null)
                }
                State.IDLE -> {
                    pulsingDotsView.stopAnimation()
                    stopPulse()
                }
            }
        }

        fun updateAmplitude(amplitude: Float) {
            amplitudeView.setAmplitude(amplitude)
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
        private val barWidthFraction = 0.6f  // fraction of slot used by bar
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
                // Read bars in order, oldest first
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
        private var phase = 0f  // 0..1

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
