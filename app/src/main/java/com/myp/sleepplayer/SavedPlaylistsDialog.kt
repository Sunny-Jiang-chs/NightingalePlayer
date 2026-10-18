package com.myp.sleepplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myp.sleepplayer.playback.PlaybackQueueStore
import com.myp.sleepplayer.playback.SavedPlaylist
import kotlin.math.roundToInt

internal class SavedPlaylistsDialog(
    private val activity: Activity,
    private val store: PlaybackQueueStore,
    private val activeId: () -> String?,
    private val onOpen: (SavedPlaylist) -> Unit,
    private val onCreate: () -> Unit,
    private val onChanged: () -> Unit
) {
    private var playlists = emptyList<SavedPlaylist>()
    private lateinit var dialog: AlertDialog
    private val adapter = PlaylistAdapter()
    private val empty = TextView(activity).apply {
        text = "还没有歌单\n新建一个，把喜欢的媒体放在一起。"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(activity.getColor(R.color.player_text_muted))
        setPadding(dp(16), dp(32), dp(16), dp(32))
    }
    private val rows = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        adapter = this@SavedPlaylistsDialog.adapter
        clipToPadding = false
    }

    fun show() {
        if (!::dialog.isInitialized) {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), 0)
                addView(empty, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
            }
            dialog = AlertDialog.Builder(activity)
                .setTitle("我的歌单")
                .setView(content)
                .setNeutralButton("新建歌单", null)
                .setNegativeButton("关闭", null)
                .create()
        }
        refresh()
        dialog.show()
        dialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            onCreate()
        }
    }

    private fun refresh() {
        playlists = store.savedPlaylists()
        empty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        rows.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).roundToInt()
        rows.layoutParams?.height = minOf(playlists.size * dp(88), maxHeight)
        rows.requestLayout()
        adapter.notifyDataSetChanged()
    }

    private fun showMenu(anchor: View, playlist: SavedPlaylist) {
        PopupMenu(activity, anchor).apply {
            menu.add("重命名").setOnMenuItemClickListener {
                rename(playlist)
                true
            }
            menu.add("删除歌单").setOnMenuItemClickListener {
                delete(playlist)
                true
            }
            show()
        }
    }

    private fun rename(playlist: SavedPlaylist) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "歌单名称"
            contentDescription = "歌单名称"
            setText(playlist.name)
            selectAll()
        }
        val panel = LinearLayout(activity).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        val renameDialog = AlertDialog.Builder(activity)
            .setTitle("重命名歌单")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        renameDialog.show()
        renameDialog.window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        renameDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.error = "请输入歌单名称"
            } else {
                if (store.renamePlaylist(playlist.id, name)) onChanged()
                refresh()
                renameDialog.dismiss()
            }
        }
        input.requestFocus()
        renameDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun delete(playlist: SavedPlaylist) {
        AlertDialog.Builder(activity)
            .setTitle("删除歌单？")
            .setMessage("将删除“${playlist.name}”。\n只删除歌单，不删除媒体文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (store.deletePlaylist(playlist.id)) onChanged()
                refresh()
            }
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(activity.getColor(R.color.player_danger))
    }

    private inner class PlaylistHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        val cover = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.player_accent))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.player_surface))
                cornerRadius = dp(8).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val name = TextView(activity).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(activity.getColor(R.color.player_text))
        }
        val detail = TextView(activity).apply {
            textSize = 12f
            setTextColor(activity.getColor(R.color.player_text_muted))
            setPadding(0, dp(4), 0, 0)
        }
        val more = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_more_vert)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val backgroundAttr = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundAttr, true)
            setBackgroundResource(backgroundAttr.resourceId)
        }

        init {
            card.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)))
            card.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
                addView(name)
                addView(detail)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistHolder>() {
        override fun getItemCount() = playlists.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PlaylistHolder(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(8), dp(4), dp(8))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)).apply {
                bottomMargin = dp(8)
            }
        })

        override fun onBindViewHolder(holder: PlaylistHolder, position: Int) {
            val playlist = playlists[position]
            val current = activeId() == playlist.id
            holder.cover.text = "${position + 1}"
            holder.name.text = playlist.name
            holder.detail.text = "${playlist.entries.size} 项" + if (current) " · 当前歌单" else ""
            holder.detail.setTextColor(activity.getColor(if (current) R.color.player_accent else R.color.player_text_muted))
            holder.card.background = if (current) RippleDrawable(
                ColorStateList.valueOf(0x2580DCC8),
                GradientDrawable().apply {
                    setColor(activity.getColor(R.color.player_selected))
                    setStroke(dp(1), activity.getColor(R.color.player_accent))
                    cornerRadius = dp(8).toFloat()
                }, null
            ) else activity.getDrawable(R.drawable.player_button_background)
            holder.card.contentDescription = "${playlist.name}，${playlist.entries.size} 项" +
                if (current) "，当前歌单，打开" else "，打开"
            holder.card.setOnClickListener {
                store.playlist(playlist.id)?.let { latest ->
                    dialog.dismiss()
                    onOpen(latest)
                }
            }
            holder.more.contentDescription = "${playlist.name}，更多操作"
            holder.more.setOnClickListener {
                store.playlist(playlist.id)?.let { showMenu(holder.more, it) }
            }
        }
    }

    private fun dialogWidth() = minOf(activity.resources.displayMetrics.widthPixels - dp(24), dp(560))
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
