package com.myp.sleepplayer

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myp.sleepplayer.media.MediaEntry
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The visible playlist owns its gestures; completed changes are saved by the page. */
internal class PlaybackQueueView(
    private val activity: Activity,
    entries: List<MediaEntry>,
    currentUri: String?,
    addMediaAction: View,
    private val createPreview: (MediaEntry) -> View,
    private val onChange: (List<MediaEntry>) -> Unit,
    private val onPlay: (MediaEntry) -> Unit
) : LinearLayout(activity) {
    private val items = entries.distinctBy { it.uri.toString() }.toMutableList()
    private var playingUri = currentUri
    private var removalMode = false
    private var dragging = false
    private var dragStartOrder: List<String>? = null
    private val adapter = QueueAdapter()
    private val modeHint = TextView(activity).apply {
        textSize = 12f
        setTextColor(activity.getColor(R.color.player_text_muted))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val rows = object : RecyclerView(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // The playlist scrolls inside the page. Keep the outer ScrollView out of
            // this gesture, including touches that start on a removal button.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            val handled = super.dispatchTouchEvent(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) && !dragging) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
    }.apply {
        layoutManager = LinearLayoutManager(activity)
        adapter = this@PlaybackQueueView.adapter
        isNestedScrollingEnabled = false
        clipToPadding = false
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onMove(list: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = source.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in items.indices || to !in items.indices) return false
            items.add(to, items.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                dragging = true
                dragStartOrder = items.map { it.uri.toString() }
                holder?.itemView?.apply { alpha = 0.88f; scaleX = 1.02f; scaleY = 1.02f }
            } else if (state == ItemTouchHelper.ACTION_STATE_IDLE && dragging) {
                dragging = false
                rows.parent?.requestDisallowInterceptTouchEvent(false)
                val previousOrder = dragStartOrder
                dragStartOrder = null
                if (previousOrder != items.map { it.uri.toString() }) onChange(items.toList())
            }
        }

        override fun clearView(list: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(list, holder)
            holder.itemView.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
            refreshControls()
        }
    })

    init {
        orientation = VERTICAL
        addView(LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modeHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addMediaAction)
        })
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewportHeight()))
        updateModeHint()
        dragHelper.attachToRecyclerView(rows)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rows.layoutParams.height = viewportHeight()
        rows.requestLayout()
    }

    fun updateCurrentUri(uri: String?) {
        if (playingUri == uri) return
        playingUri = uri
        refreshControls()
    }

    private fun refreshControls() {
        if (rows.isComputingLayout) rows.post { adapter.notifyItemRangeChanged(0, items.size, true) }
        else adapter.notifyItemRangeChanged(0, items.size, true)
    }

    private fun updateModeHint() {
        modeHint.text = if (items.isEmpty()) "从当前目录添加媒体"
            else if (removalMode) "点 × 移除 · 点卡片退出"
            else "拖动排序 · 长按移除"
    }

    private fun setRemovalMode(enabled: Boolean) {
        if (removalMode == enabled) return
        removalMode = enabled
        updateModeHint()
        refreshControls()
    }

    private fun remove(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, items.size - position, true)
        rows.layoutParams.height = viewportHeight()
        rows.requestLayout()
        onChange(items.toList())
    }

    private fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
        adapter.notifyItemMoved(from, to)
        refreshControls()
        onChange(items.toList())
    }

    private inner class QueueHolder(val card: FrameLayout) : RecyclerView.ViewHolder(card) {
        val number = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 12f
        }
        val preview = FrameLayout(activity).apply {
            // Media previews are decorative here; the whole card owns taps and drags.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val removeButton = ImageButton(activity).apply {
            background = activity.getDrawable(R.drawable.player_remove_button_background)
            setImageResource(R.drawable.ic_close)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragStarted = false
        private var longPressHandled = false

        init {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = false
                isFocusable = false
            }
            content.addView(number, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
            content.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            card.addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            card.addView(removeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            card.setOnLongClickListener {
                longPressHandled = true
                setRemovalMode(true)
                true
            }
            card.setOnClickListener {
                if (removalMode) setRemovalMode(false)
                else items.getOrNull(bindingAdapterPosition)?.let(onPlay)
            }
            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        dragStarted = false
                        longPressHandled = false
                        // Keep RecyclerView from claiming a scroll before the card can
                        // turn movement into a drag; a still hold remains a long press.
                        if (!removalMode) rows.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop
                        if (!removalMode && !longPressHandled && !dragStarted && moved &&
                            bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            dragStarted = true
                            card.cancelLongPress()
                            card.isPressed = false
                            dragHelper.startDrag(this)
                            // ItemTouchHelper must receive subsequent events while the
                            // outer page must continue to leave the gesture alone.
                            rows.requestDisallowInterceptTouchEvent(false)
                            rows.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        dragStarted
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val consumed = dragStarted || longPressHandled
                        dragStarted = false
                        longPressHandled = false
                        if (consumed) {
                            card.cancelLongPress()
                            card.isPressed = false
                        }
                        if (!dragging) rows.requestDisallowInterceptTouchEvent(false)
                        consumed
                    }
                    else -> dragStarted
                }
            }
            removeButton.setOnClickListener {
                if (removalMode) remove(bindingAdapterPosition)
            }
            ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val position = bindingAdapterPosition
                    info.isSelected = position in items.indices && items[position].uri.toString() == playingUri
                    if (position > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_move_up, "上移一位"))
                    if (position in 0 until items.lastIndex) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_move_down, "下移一位"))
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_remove, "进入移除模式"))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) when (action) {
                        R.id.queue_move_up -> { move(position, position - 1); return true }
                        R.id.queue_move_down -> { move(position, position + 1); return true }
                        R.id.queue_remove -> { setRemovalMode(true); return true }
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = QueueHolder(FrameLayout(activity).apply {
            clipToOutline = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
                bottomMargin = dp(8)
            }
        })

        override fun onBindViewHolder(holder: QueueHolder, position: Int) {
            holder.preview.removeAllViews()
            holder.preview.addView(createPreview(items[position]).apply {
                background = null
                isClickable = false
                isLongClickable = false
                isFocusable = false
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            bindControls(holder, position)
        }

        override fun onBindViewHolder(holder: QueueHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindControls(holder, position)
        }

        private fun bindControls(holder: QueueHolder, position: Int) {
            val entry = items[position]
            val isCurrent = entry.uri.toString() == playingUri
            holder.number.text = "${position + 1}"
            holder.number.setTextColor(activity.getColor(if (isCurrent) R.color.player_accent else R.color.player_text_muted))
            holder.card.isSelected = isCurrent
            holder.card.background = if (isCurrent) RippleDrawable(
                ColorStateList.valueOf(0x2580DCC8),
                GradientDrawable().apply {
                    setColor(activity.getColor(R.color.player_selected))
                    setStroke(dp(1), activity.getColor(R.color.player_accent))
                    cornerRadius = dp(8).toFloat()
                }, null
            ) else activity.getDrawable(R.drawable.player_button_background)
            holder.card.contentDescription = "播放列表第 ${position + 1} 项，${entry.name}" +
                (if (entry.subtitles.isNotEmpty()) "，${entry.subtitles.size} 条字幕" else "") +
                (if (isCurrent) "，当前播放" else "")
            holder.removeButton.visibility = if (removalMode) View.VISIBLE else View.GONE
            holder.removeButton.contentDescription = "从播放列表移除 ${entry.name}"
            holder.preview.setPadding(0, 0, dp(if (removalMode) 44 else 0), 0)
        }
    }

    private fun viewportHeight(): Int {
        val maxHeight = (activity.resources.configuration.screenHeightDp * 0.42f).roundToInt().coerceIn(80, 320)
        return dp((items.size * 80 + 4).coerceAtMost(maxHeight).coerceAtLeast(1))
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
