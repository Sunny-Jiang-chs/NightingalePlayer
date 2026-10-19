package com.myp.sleepplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
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
    private var popup: PopupWindow? = null

    fun show(anchor: View, onDismiss: () -> Unit) {
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
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(activity.getColor(R.color.player_text_muted))
            setBackgroundResource(R.drawable.player_button_background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "关闭选集"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
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
            minOf(dp(360), (anchor.width * 0.55f).roundToInt()),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        ))
        popup = PopupWindow(overlay, anchor.width, anchor.height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 0f
            setOnDismissListener {
                popup = null
                onDismiss()
            }
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, location[0], location[1])
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
