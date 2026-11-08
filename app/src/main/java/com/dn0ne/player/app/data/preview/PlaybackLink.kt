package com.dn0ne.player.app.data.preview

import androidx.media3.common.Player

/**
 * Small bridge between the preview player and the app's main Media3 controller.
 *
 * `PlayerViewModel` fills [mainPlayer] when it receives the `MediaController`.
 * The preview uses it only to pause the main player when a preview starts.
 */
class PlaybackLink {
    @Volatile
    var mainPlayer: Player? = null

    fun pauseMain() {
        mainPlayer?.pause()
    }
}
