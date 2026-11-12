package com.myp.sleepplayer.playback

internal enum class FullscreenMode {
    INLINE,
    LANDSCAPE,
    PORTRAIT
}

/** Fullscreen direction is a session choice, never a saved media preference. */
internal class FullscreenOrientationState {
    var mode: FullscreenMode = FullscreenMode.INLINE
        private set

    var videoShape: VideoDisplayShape = VideoDisplayShape.UNKNOWN
        private set

    val canUsePortraitButton: Boolean
        get() = mode == FullscreenMode.PORTRAIT || videoShape == VideoDisplayShape.PORTRAIT

    fun setFullscreen(fullscreen: Boolean) {
        if (!fullscreen) {
            mode = FullscreenMode.INLINE
        } else if (mode == FullscreenMode.INLINE) {
            mode = FullscreenMode.LANDSCAPE
        }
    }

    fun updateVideoShape(shape: VideoDisplayShape) {
        videoShape = shape
        // Wait in the chosen direction while the next media's dimensions load.
        if (mode == FullscreenMode.PORTRAIT && shape == VideoDisplayShape.NON_PORTRAIT) {
            mode = FullscreenMode.LANDSCAPE
        }
    }

    fun onPortraitButtonClick() {
        if (!canUsePortraitButton) return
        mode = if (mode == FullscreenMode.PORTRAIT) {
            FullscreenMode.INLINE
        } else {
            FullscreenMode.PORTRAIT
        }
    }

    fun onFullscreenButtonStateChanged(fullscreen: Boolean) {
        // Media3 sends this callback for both clicks and programmatic button
        // synchronization. Apply the target state idempotently: a false echo
        // after entering portrait must not leave portrait or start another toggle.
        if (fullscreen) {
            mode = FullscreenMode.LANDSCAPE
        } else if (mode == FullscreenMode.LANDSCAPE) {
            mode = FullscreenMode.INLINE
        }
    }
}
