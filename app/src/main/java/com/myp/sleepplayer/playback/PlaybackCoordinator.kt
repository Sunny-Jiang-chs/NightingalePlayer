package com.myp.sleepplayer.playback

import androidx.media3.common.Player
import com.myp.sleepplayer.media.MediaEntry
import com.myp.sleepplayer.media.MediaItemMapper

internal class PlaybackCoordinator(private val stateStore: PlaybackStateStore) {
    private var player: Player? = null

    fun attach(player: Player) {
        this.player = player
    }

    fun applyPlaylist(entries: List<MediaEntry>) {
        val player = player ?: return
        if (entries.isEmpty()) {
            player.pause()
            player.clearMediaItems()
            return
        }

        val mediaItems = entries.map(MediaItemMapper::map)
        // Reopening the page must not interrupt an already playing background queue.
        if (player.mediaItemCount == mediaItems.size &&
            mediaItems.indices.all { player.getMediaItemAt(it) == mediaItems[it] }
        ) return
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val resume = resolvePlaylistResume(
            uris = entries.map { it.uri.toString() },
            currentUri = currentUri,
            currentPositionMs = player.currentPosition,
            currentPlaying = player.playWhenReady,
            savedState = stateStore.read()
        )
        player.setMediaItems(mediaItems, resume.index, resume.positionMs)
        player.playWhenReady = resume.playWhenReady
        player.prepare()
    }

    fun playEntry(index: Int) {
        val player = player ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun togglePlayback() {
        val player = player ?: return
        if (player.mediaItemCount == 0) return
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun savePosition() {
        val player = player ?: return
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        stateStore.write(
            PlaybackSnapshot(
                mediaUri = uri,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                playWhenReady = player.playWhenReady
            )
        )
    }
}
