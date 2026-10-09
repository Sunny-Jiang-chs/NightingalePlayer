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
        if (entries.isEmpty()) return

        val mediaItems = entries.map(MediaItemMapper::map)
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val currentPosition = player.currentPosition
        val currentPlaying = player.playWhenReady
        val savedState = stateStore.read()
        val resumeUri = currentUri ?: savedState.mediaUri
        val resumeIndex = resumeUri?.let { uri ->
            mediaItems.indexOfFirst { it.localConfiguration?.uri?.toString() == uri }
        } ?: -1
        val resumePosition = if (currentUri != null) currentPosition else savedState.positionMs
        val resumePlaying = if (currentUri != null) currentPlaying else savedState.playWhenReady

        player.setMediaItems(mediaItems, true)
        player.prepare()
        if (resumeIndex >= 0) {
            player.seekTo(resumeIndex, resumePosition.coerceAtLeast(0L))
            player.playWhenReady = resumePlaying
        }
    }

    fun playEntry(index: Int) {
        player?.seekToDefaultPosition(index)
        player?.play()
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
