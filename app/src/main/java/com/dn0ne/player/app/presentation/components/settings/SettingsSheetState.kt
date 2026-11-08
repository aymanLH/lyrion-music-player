package com.dn0ne.player.app.presentation.components.settings

import com.dn0ne.player.EqualizerController
import com.dn0ne.player.app.data.download.YtDlpUpdater
import com.dn0ne.player.core.data.MusicScanner
import com.dn0ne.player.core.data.Settings

data class SettingsSheetState(
    val settings: Settings,
    val musicScanner: MusicScanner,
    val isShown: Boolean = false,
    val equalizerController: EqualizerController,
    val ytDlpUpdater: YtDlpUpdater,
    val foldersWithAudio: Set<String> = emptySet(),
    val lyricsFetchProgress: LyricsFetchProgress = LyricsFetchProgress()
)

data class LyricsFetchProgress(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val stoppedEarly: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val found: Int = 0,
    val stopReason: LyricsFetchStopReason? = null
)

enum class LyricsFetchStopReason {
    Network,
    RateLimited,
    Other
}
