package com.myp.sleepplayer.playback

object FullscreenOrientationStateTest {
    @JvmStatic
    fun main(args: Array<String>) {
        inlinePortraitButtonEntersPortraitDirectly()
        ordinaryFullscreenAlwaysStartsLandscape()
        portraitButtonsShrinkOrEnterLandscape()
        landscapeButtonsKeepTheirExistingDestinations()
        continuousPortraitPlaybackWaitsForDimensions()
        nonPortraitMediaEndsThePortraitChoice()
        portraitCanAlwaysShrinkWhileDimensionsLoad()
        backExitsEitherFullscreenDirection()
        unknownAndNonPortraitMediaCannotEnterPortrait()
        media3ProgrammaticSyncDoesNotToggleModes()
        media3BackAndMediaFallbackDoNotReenterFullscreen()
        repeatedFullscreenNotificationsAreIdempotent()
        println("Fullscreen orientation state: all checks passed")
    }

    private fun inlinePortraitButtonEntersPortraitDirectly() {
        val state = FullscreenOrientationState()
        assertState(state, FullscreenMode.INLINE, canUsePortrait = false)
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        // Identifying a portrait video exposes a second entry without entering fullscreen.
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)

        state.setFullscreen(false)
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)
        // Exiting does not remember a direction: each entry still chooses its own mode.
        state.onFullscreenButtonStateChanged(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        state.setFullscreen(false)
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
    }

    private fun ordinaryFullscreenAlwaysStartsLandscape() {
        val state = FullscreenOrientationState()
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)

        state.setFullscreen(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        // Reapplying fullscreen is not a request to abandon the selected direction.
        state.setFullscreen(true)
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)

        state.setFullscreen(false)
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)
        state.setFullscreen(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
    }

    private fun portraitButtonsShrinkOrEnterLandscape() {
        val state = portraitFullscreen()
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)

        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        state.onFullscreenButtonStateChanged(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
    }

    private fun landscapeButtonsKeepTheirExistingDestinations() {
        val state = FullscreenOrientationState()
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        state.onFullscreenButtonStateChanged(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        state.onFullscreenButtonStateChanged(true)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        state.onFullscreenButtonStateChanged(false)
        assertState(state, FullscreenMode.INLINE, canUsePortrait = true)
    }

    private fun continuousPortraitPlaybackWaitsForDimensions() {
        val state = portraitFullscreen()
        state.updateVideoShape(VideoDisplayShape.UNKNOWN)
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        // Another portrait item with unchanged dimensions keeps the mode too.
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        assertState(state, FullscreenMode.PORTRAIT, canUsePortrait = true)
    }

    private fun nonPortraitMediaEndsThePortraitChoice() {
        val state = portraitFullscreen()
        state.updateVideoShape(VideoDisplayShape.UNKNOWN)
        state.updateVideoShape(VideoDisplayShape.NON_PORTRAIT)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = false)
        // A later portrait item offers the button without rotating automatically.
        state.updateVideoShape(VideoDisplayShape.PORTRAIT)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = true)

        state.onPortraitButtonClick()
        state.updateVideoShape(VideoDisplayShape.NON_PORTRAIT)
        assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = false)
    }

    private fun portraitCanAlwaysShrinkWhileDimensionsLoad() {
        val state = portraitFullscreen()
        state.updateVideoShape(VideoDisplayShape.UNKNOWN)
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.INLINE, canUsePortrait = false)
        // The same button cannot re-enter portrait until the new item is identified.
        state.onPortraitButtonClick()
        assertState(state, FullscreenMode.INLINE, canUsePortrait = false)

        val landscapeState = portraitFullscreen()
        landscapeState.updateVideoShape(VideoDisplayShape.UNKNOWN)
        landscapeState.onFullscreenButtonStateChanged(true)
        assertState(landscapeState, FullscreenMode.LANDSCAPE, canUsePortrait = false)
    }

    private fun backExitsEitherFullscreenDirection() {
        for (portrait in listOf(false, true)) {
            val state = FullscreenOrientationState()
            state.updateVideoShape(VideoDisplayShape.PORTRAIT)
            if (portrait) state.onPortraitButtonClick() else state.onFullscreenButtonStateChanged(true)
            state.setFullscreen(false)
            assertState(state, FullscreenMode.INLINE, canUsePortrait = true)

            // Back must also work while the next item's dimensions are still unknown.
            if (portrait) state.onPortraitButtonClick() else state.onFullscreenButtonStateChanged(true)
            state.updateVideoShape(VideoDisplayShape.UNKNOWN)
            state.setFullscreen(false)
            assertState(state, FullscreenMode.INLINE, canUsePortrait = false)
        }
    }

    private fun unknownAndNonPortraitMediaCannotEnterPortrait() {
        for (shape in listOf(VideoDisplayShape.UNKNOWN, VideoDisplayShape.NON_PORTRAIT)) {
            val state = FullscreenOrientationState()
            state.updateVideoShape(shape)
            state.onPortraitButtonClick()
            assertState(state, FullscreenMode.INLINE, canUsePortrait = false)
            state.onFullscreenButtonStateChanged(true)
            state.onPortraitButtonClick()
            assertState(state, FullscreenMode.LANDSCAPE, canUsePortrait = false)
        }
    }

    private fun media3ProgrammaticSyncDoesNotToggleModes() {
        val ui = FullscreenButtonHarness()
        ui.updateVideoShape(VideoDisplayShape.PORTRAIT)
        ui.clickFullscreen()
        assertState(ui.state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        check(ui.callbackCount == 1)

        // Media3 echoes false synchronously when switching its button from
        // landscape exit to portrait's landscape-entry action.
        ui.clickPortrait()
        assertState(ui.state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        check(ui.callbackCount == 2)
        repeat(5) { ui.syncButtonState() }
        check(ui.callbackCount == 2)

        ui.clickPortrait()
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = true)
        ui.clickPortrait()
        assertState(ui.state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        ui.clickFullscreen()
        assertState(ui.state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        ui.clickFullscreen()
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = true)
        check(ui.callbackCount == 4)
        check(ui.transitions == listOf(
            FullscreenMode.LANDSCAPE, FullscreenMode.PORTRAIT, FullscreenMode.INLINE,
            FullscreenMode.PORTRAIT, FullscreenMode.LANDSCAPE, FullscreenMode.INLINE
        )) { "Programmatic button sync caused extra transitions: ${ui.transitions}" }
    }

    private fun media3BackAndMediaFallbackDoNotReenterFullscreen() {
        val ui = FullscreenButtonHarness()
        ui.updateVideoShape(VideoDisplayShape.PORTRAIT)
        ui.clickFullscreen()
        ui.pressBack()
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = true)
        check(ui.callbackCount == 2)

        ui.clickPortrait()
        ui.pressBack()
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = true)
        check(ui.callbackCount == 2)

        ui.clickPortrait()
        ui.updateVideoShape(VideoDisplayShape.NON_PORTRAIT)
        assertState(ui.state, FullscreenMode.LANDSCAPE, canUsePortrait = false)
        check(ui.callbackCount == 3)
        ui.pressBack()
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = false)
        check(ui.callbackCount == 4)
        check(ui.transitions == listOf(
            FullscreenMode.LANDSCAPE, FullscreenMode.INLINE, FullscreenMode.PORTRAIT,
            FullscreenMode.INLINE, FullscreenMode.PORTRAIT, FullscreenMode.LANDSCAPE,
            FullscreenMode.INLINE
        )) { "Back or media fallback caused extra transitions: ${ui.transitions}" }
    }

    private fun repeatedFullscreenNotificationsAreIdempotent() {
        val ui = FullscreenButtonHarness()
        ui.updateVideoShape(VideoDisplayShape.PORTRAIT)
        repeat(3) { ui.deliverFullscreenNotification(false) }
        assertState(ui.state, FullscreenMode.INLINE, canUsePortrait = true)
        ui.clickFullscreen()
        repeat(3) { ui.deliverFullscreenNotification(true) }
        assertState(ui.state, FullscreenMode.LANDSCAPE, canUsePortrait = true)
        ui.clickPortrait()
        repeat(3) { ui.deliverFullscreenNotification(false) }
        assertState(ui.state, FullscreenMode.PORTRAIT, canUsePortrait = true)
        check(ui.transitions == listOf(FullscreenMode.LANDSCAPE, FullscreenMode.PORTRAIT))
    }

    /** Models Media3 1.5.1: changing the native flag synchronously calls its listener. */
    private class FullscreenButtonHarness {
        val state = FullscreenOrientationState()
        val transitions = mutableListOf<FullscreenMode>()
        var callbackCount = 0
            private set
        private var nativeFullscreen = false
        private var callbackDepth = 0

        fun clickFullscreen() = setNativeFullscreen(!nativeFullscreen)
        fun clickPortrait() = changeMode { state.onPortraitButtonClick() }
        fun pressBack() = changeMode { state.setFullscreen(false) }
        fun updateVideoShape(shape: VideoDisplayShape) = changeMode { state.updateVideoShape(shape) }
        fun syncButtonState() = setNativeFullscreen(state.mode == FullscreenMode.LANDSCAPE)

        private fun setNativeFullscreen(fullscreen: Boolean) {
            if (nativeFullscreen == fullscreen) return
            nativeFullscreen = fullscreen
            deliverFullscreenNotification(fullscreen)
        }

        fun deliverFullscreenNotification(fullscreen: Boolean) {
            callbackCount++
            callbackDepth++
            try {
                check(callbackDepth <= 8) {
                    "Fullscreen callback loop while applying $fullscreen: $transitions"
                }
                changeMode { state.onFullscreenButtonStateChanged(fullscreen) }
            } finally {
                callbackDepth--
            }
        }

        private fun changeMode(action: () -> Unit) {
            val previous = state.mode
            action()
            if (previous != state.mode) transitions += state.mode
            syncButtonState()
        }
    }

    private fun portraitFullscreen(): FullscreenOrientationState =
        FullscreenOrientationState().apply {
            updateVideoShape(VideoDisplayShape.PORTRAIT)
            onPortraitButtonClick()
        }

    private fun assertState(
        state: FullscreenOrientationState,
        expectedMode: FullscreenMode,
        canUsePortrait: Boolean
    ) {
        check(state.mode == expectedMode) {
            "Expected mode <$expectedMode>, got <${state.mode}> for ${state.videoShape}"
        }
        check(state.canUsePortraitButton == canUsePortrait) {
            "Expected portrait button available <$canUsePortrait>, got <${state.canUsePortraitButton}> " +
                "in ${state.mode} for ${state.videoShape}"
        }
    }
}
