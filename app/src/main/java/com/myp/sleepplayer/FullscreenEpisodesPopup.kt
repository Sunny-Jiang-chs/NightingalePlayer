package com.myp.sleepplayer

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.myp.sleepplayer.media.MediaEntry
import kotlin.math.roundToInt

/** A fullscreen queue browser. Previews share the library's visible-thumbnail cache. */
internal class FullscreenEpisodesPopup(
    private val activity: Activity,
    private val entries: List<MediaEntry>,
    currentUri: String?,
    private val createPreview: (MediaEntry) -> View,
    private val onPlay: (Int) -> Unit
) {
    private var playingUri = currentUri
    private val adapter = EpisodesAdapter()
    private var popup: Dialog? = null

    fun show(onDismiss: () -> Unit) {
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(80, 0, 0, 0))
            setOnClickListener { dismiss() }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            setBackgroundColor(activity.getColor(R.color.player_background))
            isClickable = true
            elevation = dp(12).toFloat()
        }
        ViewCompat.setAccessibilityPaneTitle(panel, "选集")
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(activity).apply {
            text = "选集 · ${entries.size} 项"
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(activity.getColor(R.color.player_text))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(activity.getColor(R.color.player_text_muted))
            setBackgroundResource(R.drawable.player_button_background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "关闭选集"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(header)
        if (entries.isEmpty()) {
            panel.addView(TextView(activity).apply {
                text = "当前没有可播放的媒体"
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.player_text_muted))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            panel.addView(RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = this@FullscreenEpisodesPopup.adapter
                itemAnimator = null
                isVerticalScrollBarEnabled = true
                scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
                setPadding(0, dp(8), dp(4), 0)
                scrollToPosition(entries.indexOfFirst { it.uri.toString() == playingUri }.coerceAtLeast(0))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        overlay.addView(panel, FrameLayout.LayoutParams(
            dp(360),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        ))
        // Measure inside the actual fullscreen window instead of positioning a popup
        // from PlayerView screen coordinates, which OEM popup windows can clip.
        fun fitPanel() {
            val availableWidth = overlay.width - overlay.paddingLeft - overlay.paddingRight
            if (availableWidth <= 0) return
            val width = minOf(dp(360), (availableWidth * 0.55f).roundToInt())
            if (panel.layoutParams.width != width) {
                panel.layoutParams = panel.layoutParams.apply { this.width = width }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            fitPanel()
            insets
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPanel() }
        popup = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(overlay)
            setOnDismissListener {
                popup = null
                onDismiss()
            }
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                WindowInsetsControllerCompat(this, decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            ViewCompat.requestApplyInsets(overlay)
        }
    }

    fun updateCurrent(uri: String?) {
        if (playingUri == uri) return
        val previous = entries.indexOfFirst { it.uri.toString() == playingUri }
        playingUri = uri
        val current = entries.indexOfFirst { it.uri.toString() == playingUri }
        if (previous >= 0) adapter.notifyItemChanged(previous)
        if (current >= 0) adapter.notifyItemChanged(current)
    }

    fun dismiss() { popup?.dismiss() }

    private inner class EpisodesAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder =
            EpisodeHolder(FrameLayout(activity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80))
                setPadding(dp(4), dp(3), dp(4), dp(3))
            })

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val entry = entries[position]
            val selected = entry.uri.toString() == playingUri
            holder.card.apply {
                removeAllViews()
                background = GradientDrawable().apply {
                    setColor(activity.getColor(if (selected) R.color.player_selected else R.color.player_background))
                    if (selected) setStroke(dp(1), activity.getColor(R.color.player_accent))
                    cornerRadius = dp(7).toFloat()
                }
                addView(createPreview(entry), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                contentDescription = "选集第 ${position + 1} 项，${entry.name}" + if (selected) "，当前播放" else ""
                isSelected = selected
                foreground = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(40, 128, 220, 200)),
                    null,
                    ColorDrawable(Color.WHITE)
                )
                setOnClickListener {
                    onPlay(position)
                    dismiss()
                }
            }
        }
    }

    private class EpisodeHolder(val card: FrameLayout) : RecyclerView.ViewHolder(card)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
