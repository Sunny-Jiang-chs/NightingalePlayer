package com.myp.sleepplayer

import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Keeps full time labels readable without reducing the bottom buttons' touch targets. */
@UnstableApi
internal class PlayerTimeLayout(
    private val playerView: PlayerView,
    private val playbackButton: View
) {
    private val timeView = playerView.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_time)
    private val timeSlot = playerView.findViewById<FrameLayout>(R.id.player_time_slot)
    private val compactTime = playerView.findViewById<FrameLayout>(R.id.compact_player_time)
    private val bottomBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
    private val orientationButton = playerView.findViewById<View>(R.id.portrait_adaptation)
    private val controlView = playerView.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
    private val progress = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
    private val originalTimeParams = timeView?.layoutParams
    private val originalPlaybackParams = playbackButton.layoutParams
    private val originalPlaybackIndex = timeView?.indexOfChild(playbackButton) ?: -1
    private val originalTimeAlpha = timeView?.alpha ?: 1f
    private val labels = timeView?.let { group ->
        (0 until group.childCount).mapNotNull { group.getChildAt(it) as? TextView }
    }.orEmpty()
    private var compact = false
    private var refreshPosted = false
    private val refreshTask = Runnable {
        refreshPosted = false
        refresh()
    }

    init {
        if (timeView != null && timeSlot != null && compactTime != null && bottomBar != null &&
            orientationButton != null && controlView != null && originalPlaybackIndex >= 0
        ) {
            val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                scheduleRefresh()
            }
            playerView.addOnLayoutChangeListener(layoutListener)
            bottomBar.addOnLayoutChangeListener(layoutListener)
            timeSlot.addOnLayoutChangeListener(layoutListener)
            val textWatcher = object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    scheduleRefresh()
                }
                override fun afterTextChanged(text: Editable?) = Unit
            }
            labels.forEach { it.addTextChangedListener(textWatcher) }
            playerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = scheduleRefresh()
                override fun onViewDetachedFromWindow(view: View) {
                    playerView.removeCallbacks(refreshTask)
                    refreshPosted = false
                }
            })
            controlView.addVisibilityListener {
                if (compact) resetBarTranslation()
            }
            scheduleRefresh()
        }
    }

    fun refresh() {
        val timeView = timeView ?: return
        val timeSlot = timeSlot ?: return
        val compactTime = compactTime ?: return
        val orientationButton = orientationButton ?: return
        val controlView = controlView ?: return
        val originalTimeParams = originalTimeParams ?: return
        val originalPlaybackParams = originalPlaybackParams ?: return
        if (originalPlaybackIndex < 0 || labels.size != 3 || timeSlot.width <= 0) return

        val availableWidth = timeSlot.width - timeSlot.paddingLeft - timeSlot.paddingRight
        val buttonWidth = dp(48) + horizontalMargins(originalPlaybackParams)
        val timeWidth = labels.sumOf { label ->
            val paint = TextPaint(label.paint).apply {
                // Auto-size may already have reduced the current paint. Always compare
                // against the normal 12sp size so moving the labels cannot cause flapping.
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 12f, playerView.resources.displayMetrics
                )
            }
            val text = label.transformationMethod?.getTransformation(label.text, label) ?: label.text
            ceil(paint.measureText(text.toString()).toDouble()).toInt() +
                label.compoundPaddingLeft + label.compoundPaddingRight + horizontalMargins(label.layoutParams)
        } + timeView.paddingLeft + timeView.paddingRight + horizontalMargins(originalTimeParams)
        val needsCompact = orientationButton.visibility == View.VISIBLE &&
            buttonWidth + timeWidth + dp(8) > availableWidth
        if (needsCompact == compact) return

        compact = needsCompact
        (timeView.parent as? ViewGroup)?.removeView(timeView)
        (playbackButton.parent as? ViewGroup)?.removeView(playbackButton)
        if (compact) {
            timeSlot.addView(playbackButton, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
            compactTime.addView(timeView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })
            timeView.alpha = 0.65f
            compactTime.visibility = View.VISIBLE
            controlView.setAnimationEnabled(false)
            // Media3 stores the slide translation from its previous animated state.
            // Disabling animation alone would leave the controls below the window.
            resetBarTranslation()
        } else {
            timeView.addView(
                playbackButton, originalPlaybackIndex.coerceAtMost(timeView.childCount), originalPlaybackParams
            )
            timeSlot.addView(timeView, originalTimeParams)
            timeView.alpha = originalTimeAlpha
            compactTime.visibility = View.GONE
            controlView.setAnimationEnabled(true)
        }
        // Keep the configured timeout; only refresh the hide strategy on a layout
        // transition, never on each time update (which would prevent auto-hiding).
        controlView.showTimeoutMs = controlView.showTimeoutMs
    }

    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        playerView.post(refreshTask)
    }

    private fun resetBarTranslation() {
        bottomBar?.translationY = 0f
        progress?.translationY = 0f
    }

    private fun horizontalMargins(params: ViewGroup.LayoutParams?): Int =
        (params as? ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0

    private fun dp(value: Int): Int = (value * playerView.resources.displayMetrics.density).roundToInt()
}
