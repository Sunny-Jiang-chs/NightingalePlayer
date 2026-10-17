package com.myp.sleepplayer

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myp.sleepplayer.media.MediaEntry
import com.myp.sleepplayer.playback.PlaybackQueue

/** Edits a draft only. Playback and preferences change only after Save / Loop. */
internal class PlaybackQueueDialog(
    private val activity: Activity,
    private val available: List<MediaEntry>,
    private val savedSelection: List<String>?,
    private val currentUri: String?,
    private val loopEnabled: Boolean,
    private val createPreview: (MediaEntry, String) -> View,
    private val onSave: (selection: List<String>?, loopEnabled: Boolean, startPlayback: Boolean) -> Unit
) {
    private val byUri = available.associateBy { it.uri.toString() }
    private val allUris = available.map { it.uri.toString() }
    private var draft = PlaybackQueue.resolve(allUris, savedSelection)
    private var followsDirectory = savedSelection == null
    private lateinit var rows: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var summary: TextView
    private lateinit var modeHint: TextView
    private lateinit var loopToggle: CheckBox
    private lateinit var dialog: AlertDialog
    private val adapter = QueueAdapter()
    private var removalMode = false
    private val compact = activity.resources.configuration.screenHeightDp < 480
    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun onMove(list: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = source.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            draft = PlaybackQueue.move(draft, from, to)
            followsDirectory = false
            adapter.notifyItemMoved(from, to)
            updateSummary()
            return true
        }
        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                holder?.itemView?.apply { alpha = 0.88f; scaleX = 1.02f; scaleY = 1.02f }
            }
        }
        override fun clearView(list: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(list, holder)
            holder.itemView.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
            adapter.notifyItemRangeChanged(0, draft.size, true)
        }
    })

    fun show() {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        summary = TextView(activity).apply { textSize = 13f }
        if (!compact) panel.addView(summary)
        loopToggle = CheckBox(activity).apply {
            text = "循环播放"
            isChecked = loopEnabled
            minHeight = dp(48)
        }
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (compact) actions.addView(loopToggle) else panel.addView(loopToggle)
        actions.addView(button("选择媒体") { selectMedia() }, weightedButtonParams())
        actions.addView(button("全部媒体") {
            draft = allUris
            followsDirectory = true
            render()
        }, weightedButtonParams())
        panel.addView(actions)
        modeHint = TextView(activity).apply {
            text = "拖动卡片排序 · 长按进入移除模式"
            textSize = 12f
            setTextColor(activity.getColor(R.color.player_text_muted))
            setPadding(0, dp(if (compact) 4 else 12), 0, dp(if (compact) 4 else 10))
        }
        panel.addView(modeHint)
        emptyView = TextView(activity).apply {
            text = "尚未选择媒体，点“选择媒体”添加。"
            textSize = 14f
            setPadding(0, dp(20), 0, dp(20))
        }
        panel.addView(emptyView)
        rows = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@PlaybackQueueDialog.adapter
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        dragHelper.attachToRecyclerView(rows)
        panel.addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        dialog = AlertDialog.Builder(activity)
            .setTitle("循环列表")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> save(false) }
            .setNeutralButton("开始循环") { _, _ -> save(true) }
            .create()
        dialog.show()
        dialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        render()
    }

    private fun updateSummary() {
        val unavailable = savedSelection.orEmpty().count { it !in byUri }
        summary.text = buildString {
            append(if (followsDirectory) "全部媒体 · ${draft.size} 项" else "已选 ${draft.size} 项")
            append(" · 一项或多项均可循环")
            if (unavailable > 0) append("\n原列表有 $unavailable 项不在当前目录，已跳过。")
        }
        if (compact) dialog.setTitle("循环列表 · ${draft.size} 项")
    }

    private fun render() {
        updateSummary()
        removalMode = false
        updateModeHint()
        rows.layoutParams.height = listHeight(draft.size, if (compact) 180 else 320, 80)
        rows.requestLayout()
        rows.visibility = if (draft.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (draft.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateDialogButtons()
    }

    private fun updateDialogButtons() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = draft.isNotEmpty()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = draft.isNotEmpty()
    }

    private fun updateModeHint() {
        modeHint.text = if (removalMode) "点击 × 移除 · 点卡片中间恢复拖动"
            else "拖动卡片排序 · 长按进入移除模式"
    }

    private fun setRemovalMode(enabled: Boolean) {
        removalMode = enabled
        updateModeHint()
        adapter.notifyItemRangeChanged(0, draft.size, true)
    }

    private fun remove(uri: String) {
        val position = draft.indexOf(uri)
        if (position < 0) return
        draft = draft.filter { it != uri }
        followsDirectory = false
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, draft.size - position, true)
        updateSummary()
        if (draft.isEmpty()) {
            rows.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        }
        updateDialogButtons()
    }

    private inner class QueueHolder(val card: FrameLayout) : RecyclerView.ViewHolder(card) {
        val number = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(activity.getColor(R.color.player_text_muted))
        }
        val preview = FrameLayout(activity)
        val removeButton = ImageButton(activity).apply {
            background = activity.getDrawable(R.drawable.player_remove_button_background)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragStarted = false
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            card.addView(removeButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
            card.setOnLongClickListener {
                if (!removalMode) setRemovalMode(true)
                true
            }
            card.setOnClickListener {
                if (removalMode) setRemovalMode(false)
            }
            card.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        dragStarted = false
                        // Let the card distinguish movement from a stationary long press
                        // before RecyclerView starts scrolling.
                        if (!removalMode) rows.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moved = kotlin.math.hypot(
                            (event.x - downX).toDouble(),
                            (event.y - downY).toDouble()
                        ) > touchSlop
                        if (!removalMode && !dragStarted && moved) {
                            dragStarted = true
                            card.cancelLongPress()
                            card.isPressed = false
                            dragHelper.startDrag(this)
                            rows.requestDisallowInterceptTouchEvent(false)
                        }
                        dragStarted
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragStarted
                        dragStarted = false
                        rows.requestDisallowInterceptTouchEvent(false)
                        wasDragging
                    }
                    else -> dragStarted
                }
            }
            removeButton.setOnClickListener {
                val uri = draft.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                if (removalMode) remove(uri)
            }
            // TalkBack and keyboard users can reorder without a drag gesture.
            ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val position = bindingAdapterPosition
                    if (position > 0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_move_up, "上移一位"))
                    if (position in 0 until draft.lastIndex) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_move_down, "下移一位"))
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.queue_remove, "进入移除模式"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) when (action) {
                        R.id.queue_move_up -> { move(position, position - 1); return true }
                        R.id.queue_move_down -> { move(position, position + 1); return true }
                        R.id.queue_remove -> return card.performLongClick()
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueHolder>() {
        override fun getItemCount() = draft.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = QueueHolder(FrameLayout(activity).apply {
            background = activity.getDrawable(R.drawable.player_button_background)
            clipToOutline = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply {
                bottomMargin = dp(8)
            }
        })
        override fun onBindViewHolder(holder: QueueHolder, position: Int) {
            val entry = byUri.getValue(draft[position])
            holder.preview.removeAllViews()
            holder.preview.addView(createPreview(entry, entry.name).apply { background = null },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            bindControls(holder, position)
        }
        override fun onBindViewHolder(holder: QueueHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position) else bindControls(holder, position)
        }
        private fun bindControls(holder: QueueHolder, position: Int) {
            val uri = draft[position]
            val entry = byUri.getValue(uri)
            holder.number.text = "${position + 1}"
            holder.number.setTextColor(activity.getColor(if (uri == currentUri) R.color.player_accent else R.color.player_text_muted))
            holder.card.contentDescription = "第 ${position + 1} 项，${entry.name}" +
                if (uri == currentUri) "，当前播放" else ""
            holder.removeButton.visibility = if (removalMode) View.VISIBLE else View.GONE
            holder.preview.getChildAt(0)?.setPadding(0, 0, dp(if (removalMode) 48 else 4), 0)
            holder.removeButton.setImageResource(R.drawable.ic_close)
            holder.removeButton.contentDescription = "从列表移除 ${entry.name}"
        }
    }

    private fun selectMedia() {
        val checked = BooleanArray(allUris.size) { allUris[it] in draft }
        val checks = mutableListOf<CheckBox>()
        val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        available.forEachIndexed { index, entry ->
            val check = CheckBox(activity).apply {
                isChecked = checked[index]
                contentDescription = "选择 ${entry.name}"
                setOnCheckedChangeListener { _, selected -> checked[index] = selected }
            }
            checks += check
            choices.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(check, LinearLayout.LayoutParams(dp(48), dp(72)))
                addView(createPreview(entry, entry.name), LinearLayout.LayoutParams(0, dp(72), 1f))
                isFocusable = true
                setOnClickListener { check.toggle() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        }
        val pickerScroll = ScrollView(activity).apply {
            setPadding(dp(12), 0, dp(12), 0)
            isVerticalScrollBarEnabled = false
            addView(choices)
        }
        val picker = AlertDialog.Builder(activity)
            .setTitle("选择媒体")
            .setView(pickerScroll)
            .setNegativeButton("取消", null)
            .setNeutralButton("全不选", null)
            .setPositiveButton("确定") { _, _ ->
                draft = PlaybackQueue.updateSelection(draft, allUris.filterIndexed { i, _ -> checked[i] })
                followsDirectory = false
                render()
            }.create()
        picker.show()
        picker.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        pickerScroll.layoutParams = pickerScroll.layoutParams.apply { height = listHeight(available.size, 180) }
        picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            checks.forEach { it.isChecked = false }
        }
    }

    private fun move(from: Int, to: Int) {
        draft = PlaybackQueue.move(draft, from, to)
        followsDirectory = false
        render()
    }

    private fun save(startPlayback: Boolean) {
        if (draft.isNotEmpty()) onSave(
            if (followsDirectory) null else draft, startPlayback || loopToggle.isChecked, startPlayback
        )
    }

    private fun listHeight(count: Int, chromeDp: Int, rowDp: Int = 80): Int {
        val availableHeight = (activity.resources.displayMetrics.heightPixels * 0.92f - dp(chromeDp))
            .toInt().coerceAtLeast(dp(48))
        return (dp(rowDp) * count.coerceAtLeast(1) + dp(4)).coerceAtMost(availableHeight)
    }

    private fun dialogWidth() = minOf(activity.resources.displayMetrics.widthPixels - dp(24), dp(560))

    private fun button(label: String, action: (android.view.View) -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(48)
            minimumWidth = dp(48)
            setOnClickListener(action)
        }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
