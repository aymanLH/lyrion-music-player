package com.dn0ne.player.core.data

import android.content.Context
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dn0ne.player.app.domain.sort.PlaylistSort
import com.dn0ne.player.app.domain.sort.SortOrder
import com.dn0ne.player.app.domain.sort.TrackSort
import com.dn0ne.player.app.domain.download.DownloadAudioFormat
import com.dn0ne.player.app.domain.download.DownloadAudioQuality
import com.dn0ne.player.app.domain.download.YtDlpUpdateChannel
import com.dn0ne.player.app.presentation.components.settings.Theme
import com.dn0ne.player.app.presentation.components.topbar.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class Settings(context: Context) {
    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val handleAudioFocusKey = "audio-focus"

    private val replaceSearchWithFilterKey = "replace-search-with-filter"

    private val appearanceKey = "appearance"
    private val useDynamicColorKey = "use-dynamic-color"
    private val useAlbumArtColorKey = "use-album-art-color"
    private val albumArtColorIntensityKey = "album-art-color-intensity"
    private val paletteStyleKey = "palette-style"
    private val amoledDarkThemeKey = "amoled-dark-theme"
    private val albumArtThemeSeedKey = "album-art-theme-seed"
    private val albumArtThemeTrackIdKey = "album-art-theme-track-id"

    private val lyricsFontSizeKey = "lyrics-font-size"
    private val lyricsFontWeightKey = "lyrics-font-weight"
    private val lyricsLineHeightKey = "lyrics-line-height"
    private val lyricsLetterSpacingKey = "lyrics-letter-spacing"
    private val lyricsAlignmentKey = "lyrics-alignment"
    private val useDarkPaletteOnLyricsSheetKey = "dark-palette-on-lyrics-sheet"

    private val areRisksOfMetadataEditingAcceptedKey = "metadata-editing-dialog"

    private val matchDurationWhenSearchMetadataKey = "match-duration"

    private val trackSortKey = "track-sort-key"
    private val trackSortOrderKey = "track-sort-order-key"
    private val playlistSortKey = "playlist-sort-key"
    private val playlistSortOrderKey = "playlist-sort-order-key"

    private val isScanModeInclusiveKey = "is-scan-mode-inclusive"
    private val ignoreShortTracksKey = "exclude-short-tracks"
    private val scanMusicFolderKey = "scan-music-in-music"
    private val extraScanFoldersKey = "extra-scan-folders"
    private val excludedScanFoldersKey = "excluded-scan-folders"
    private val scanOnAppLaunchKey = "scan-on-app-launch"

    private val tabOrderKey = "tab-order"
    private val defaultTabKey = "default-tab"

    private val jumpToBeginningKey = "jump-to-beginning"
    private val floatingMiniPlayerKey = "floating-mini-player"
    private val floatingMiniPlayerHeightKey = "floating-mini-player-height"

    private val gridPlaylistsKey = "grid-playlists"

    private val ytDlpAutoUpdateKey = "yt-dlp-auto-update"
    private val ytDlpUpdateChannelKey = "yt-dlp-update-channel"
    private val ytDlpUpdateIntervalDaysKey = "yt-dlp-update-interval-days"
    private val ytDlpVersionKey = "yt-dlp-version"
    private val ytDlpLastUpdateKey = "yt-dlp-last-update"

    private val downloadFormatKey = "download-format"
    private val downloadQualityKey = "download-quality"
    private val embedMetadataKey = "embed-metadata"
    private val embedThumbnailKey = "embed-thumbnail"
    private val notificationsEnabledKey = "notifications-enabled"

    var handleAudioFocus: Boolean
        get() = sharedPreferences.getBoolean(handleAudioFocusKey, true)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(handleAudioFocusKey, value)
                apply()
            }
        }

    private val _replaceSearchWithFilter = MutableStateFlow(
        sharedPreferences.getBoolean(replaceSearchWithFilterKey, false)
    )
    val replaceSearchWithFilter = _replaceSearchWithFilter.asStateFlow()
    fun updateReplaceSearchWithFilter(value: Boolean) {
        _replaceSearchWithFilter.update { value  }
        with(sharedPreferences.edit()) {
            putBoolean(replaceSearchWithFilterKey, value)
            apply()
        }
    }

    private val _appearance = MutableStateFlow(
        Theme.Appearance.entries[sharedPreferences.getInt(appearanceKey, 0)]
    )
    val appearance = _appearance.asStateFlow()
    fun updateAppearance(appearance: Theme.Appearance) {
        _appearance.update {
            appearance
        }
        with(sharedPreferences.edit()) {
            putInt(appearanceKey, appearance.ordinal)
            apply()
        }
    }

    private val _useDynamicColor = MutableStateFlow(
        sharedPreferences.getBoolean(useDynamicColorKey, true)
    )
    val useDynamicColor = _useDynamicColor.asStateFlow()
    fun updateUseDynamicColor(value: Boolean) {
        _useDynamicColor.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(useDynamicColorKey, value)
            apply()
        }
    }

    private val _useAlbumArtColor = MutableStateFlow(
        sharedPreferences.getBoolean(useAlbumArtColorKey, true)
    )
    val useAlbumArtColor = _useAlbumArtColor.asStateFlow()
    fun updateUseAlbumArtColor(value: Boolean) {
        _useAlbumArtColor.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(useAlbumArtColorKey, value)
            apply()
        }
    }

    private val _albumArtThemeSeed = MutableStateFlow(
        sharedPreferences.getInt(albumArtThemeSeedKey, 0).takeIf {
            sharedPreferences.contains(albumArtThemeSeedKey)
        }
    )
    val albumArtThemeSeed = _albumArtThemeSeed.asStateFlow()

    private val _albumArtThemeTrackId = MutableStateFlow(
        sharedPreferences.getString(albumArtThemeTrackIdKey, null)
    )
    val albumArtThemeTrackId = _albumArtThemeTrackId.asStateFlow()

    fun updateAlbumArtTheme(seedColor: Int, trackId: String) {
        if (_albumArtThemeSeed.value == seedColor && _albumArtThemeTrackId.value == trackId) return
        _albumArtThemeSeed.value = seedColor
        _albumArtThemeTrackId.value = trackId
        sharedPreferences.edit()
            .putInt(albumArtThemeSeedKey, seedColor)
            .putString(albumArtThemeTrackIdKey, trackId)
            .apply()
    }

    private val _albumArtColorIntensity = MutableStateFlow(
        sharedPreferences.getFloat(albumArtColorIntensityKey, 0.7f)
    )
    val albumArtColorIntensity = _albumArtColorIntensity.asStateFlow()
    fun updateAlbumArtColorIntensity(value: Float) {
        _albumArtColorIntensity.update { value }
        with(sharedPreferences.edit()) {
            putFloat(albumArtColorIntensityKey, value)
            apply()
        }
    }

    private val _paletteStyle = MutableStateFlow(
        Theme.PaletteStyle.entries.getOrElse(
            sharedPreferences.getInt(paletteStyleKey, Theme.PaletteStyle.AlbumCover.ordinal)
        ) { Theme.PaletteStyle.AlbumCover }
    )
    val paletteStyle = _paletteStyle.asStateFlow()
    fun updatePaletteStyle(paletteStyle: Theme.PaletteStyle) {
        _paletteStyle.update {
            paletteStyle
        }
        with(sharedPreferences.edit()) {
            putInt(paletteStyleKey, paletteStyle.ordinal)
            apply()
        }
    }

    private val _amoledDarkTheme = MutableStateFlow(
        sharedPreferences.getBoolean(amoledDarkThemeKey, false)
    )
    val amoledDarkTheme = _amoledDarkTheme.asStateFlow()
    fun updateAmoledDarkTheme(value: Boolean) {
        _amoledDarkTheme.update {
            value
        }
        with(sharedPreferences.edit()) {
            putBoolean(amoledDarkThemeKey, value)
            apply()
        }
    }

    var lyricsFontSize: TextUnit
        get() = sharedPreferences.getFloat(lyricsFontSizeKey, 28f).sp
        set(value) {
            with(sharedPreferences.edit()) {
                putFloat(lyricsFontSizeKey, value.value)
                apply()
            }
        }

    var lyricsFontWeight: Int
        get() = sharedPreferences.getInt(lyricsFontWeightKey, 600)
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(lyricsFontWeightKey, value)
                apply()
            }
        }

    var lyricsLineHeight: TextUnit
        get() = sharedPreferences.getFloat(lyricsLineHeightKey, 32f).sp
        set(value) {
            with(sharedPreferences.edit()) {
                putFloat(lyricsLineHeightKey, value.value)
                apply()
            }
        }

    var lyricsLetterSpacing: TextUnit
        get() = sharedPreferences.getFloat(lyricsLetterSpacingKey, 0f).sp
        set(value) {
            with(sharedPreferences.edit()) {
                putFloat(lyricsLetterSpacingKey, value.value)
                apply()
            }
        }

    var lyricsAlignment: TextAlign
        get() = when (sharedPreferences.getInt(lyricsAlignmentKey, 0)) {
            1 -> TextAlign.Center
            2 -> TextAlign.End
            else -> TextAlign.Start
        }
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(
                    lyricsAlignmentKey,
                    when (value) {
                        TextAlign.Center -> 1
                        TextAlign.End -> 2
                        else -> 0
                    }
                )
                apply()
            }
        }

    fun resetLyricsStyle() {
        with(sharedPreferences.edit()) {
            remove(lyricsFontSizeKey)
            remove(lyricsFontWeightKey)
            remove(lyricsLineHeightKey)
            remove(lyricsLetterSpacingKey)
            remove(lyricsAlignmentKey)
            apply()
        }
    }

    var useDarkPaletteOnLyricsSheet: Boolean
        get() = sharedPreferences.getBoolean(useDarkPaletteOnLyricsSheetKey, true)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(useDarkPaletteOnLyricsSheetKey, value)
                apply()
            }
        }

    var areRisksOfMetadataEditingAccepted: Boolean
        get() = sharedPreferences.getBoolean(areRisksOfMetadataEditingAcceptedKey, false)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(areRisksOfMetadataEditingAcceptedKey, value)
                apply()
            }
        }

    var matchDurationWhenSearchMetadata: Boolean
        get() = sharedPreferences.getBoolean(matchDurationWhenSearchMetadataKey, true)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(matchDurationWhenSearchMetadataKey, value)
                apply()
            }
        }

    var trackSort: TrackSort
        get() = TrackSort.entries[sharedPreferences.getInt(trackSortKey, 0)]
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(trackSortKey, value.ordinal)
                apply()
            }
        }

    var trackSortOrder: SortOrder
        get() = SortOrder.entries[sharedPreferences.getInt(trackSortOrderKey, 0)]
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(trackSortOrderKey, value.ordinal)
                apply()
            }
        }

    var playlistSort: PlaylistSort
        get() = PlaylistSort.entries[sharedPreferences.getInt(playlistSortKey, 0)]
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(playlistSortKey, value.ordinal)
                apply()
            }
        }

    var playlistSortOrder: SortOrder
        get() = SortOrder.entries[sharedPreferences.getInt(playlistSortOrderKey, 0)]
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(playlistSortOrderKey, value.ordinal)
                apply()
            }
        }

    private val _isScanModeInclusive = MutableStateFlow(
        sharedPreferences.getBoolean(isScanModeInclusiveKey, false)
    )
    val isScanModeInclusive = _isScanModeInclusive.asStateFlow()
    fun updateIsScanModeInclusive(value: Boolean) {
        _isScanModeInclusive.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(isScanModeInclusiveKey, value)
            apply()
        }
    }

    var ignoreShortTracks: Boolean
        get() = sharedPreferences.getBoolean(ignoreShortTracksKey, true)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(ignoreShortTracksKey, value)
                apply()
            }
        }

    private val _scanMusicFolder = MutableStateFlow(
        sharedPreferences.getBoolean(scanMusicFolderKey, true)
    )
    val scanMusicFolder = _scanMusicFolder.asStateFlow()
    fun updateScanMusicFolder(value: Boolean) {
        _scanMusicFolder.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(scanMusicFolderKey, value)
            apply()
        }
    }

    private val _extraScanFolders = MutableStateFlow(
        sharedPreferences.getStringSet(extraScanFoldersKey, setOf<String>()) ?: setOf<String>()
    )
    val extraScanFolders = _extraScanFolders.asStateFlow()
    fun updateExtraScanFolders(value: Set<String>) {
        _extraScanFolders.update { value }
        with(sharedPreferences.edit()) {
            putStringSet(extraScanFoldersKey, value)
            apply()
        }
    }

    private val _excludedScanFolders = MutableStateFlow(
        sharedPreferences.getStringSet(excludedScanFoldersKey, setOf<String>()) ?: setOf<String>()
    )
    val excludedScanFolders = _excludedScanFolders.asStateFlow()
    fun updateExcludedScanFolders(value: Set<String>) {
        _excludedScanFolders.update { value }
        with(sharedPreferences.edit()) {
            putStringSet(excludedScanFoldersKey, value)
            apply()
        }
    }

    private val _scanOnAppLaunch = MutableStateFlow(
        sharedPreferences.getBoolean(scanOnAppLaunchKey, true)
    )
    val scanOnAppLaunch = _scanOnAppLaunch.asStateFlow()
    fun updateScanOnAppLaunch(value: Boolean) {
        _scanOnAppLaunch.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(scanOnAppLaunchKey, value)
            apply()
        }
    }

    private val _tabs = MutableStateFlow(
        sharedPreferences.getString(tabOrderKey, null)?.let {
            it.split(";").map {
                Tab.valueOf(it)
            }
        } ?: Tab.entries.toList()
    )
    val tabs = _tabs.asStateFlow()
    fun updateTabOrder(tabs: List<Tab>) {
        _tabs.update { tabs }
        with(sharedPreferences.edit()) {
            putString(tabOrderKey, tabs.map { it.name }.joinToString(";"))
            apply()
        }
    }

    var defaultTab: Tab
        get() = Tab.entries[sharedPreferences.getInt(defaultTabKey, 1)]
        set(value) {
            with(sharedPreferences.edit()) {
                putInt(defaultTabKey, value.ordinal)
                apply()
            }
        }

    var jumpToBeginning: Boolean
        get() = sharedPreferences.getBoolean(jumpToBeginningKey, true)
        set(value) {
            with(sharedPreferences.edit()) {
                putBoolean(jumpToBeginningKey, value)
                apply()
            }
        }

    private val _floatingMiniPlayer = MutableStateFlow(
        sharedPreferences.getBoolean(floatingMiniPlayerKey, false)
    )
    val floatingMiniPlayer = _floatingMiniPlayer.asStateFlow()
    fun updateFloatingMiniPlayer(value: Boolean) {
        _floatingMiniPlayer.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(floatingMiniPlayerKey, value)
            apply()
        }
    }

    private val _floatingMiniPlayerHeight = MutableStateFlow(
        sharedPreferences.getInt(floatingMiniPlayerHeightKey, 88)
    )
    val floatingMiniPlayerHeight = _floatingMiniPlayerHeight.asStateFlow()
    fun updateFloatingMiniPlayerHeight(value: Int) {
        _floatingMiniPlayerHeight.update { value }
        with(sharedPreferences.edit()) {
            putInt(floatingMiniPlayerHeightKey, value)
            apply()
        }
    }

    private val _gridPlaylist = MutableStateFlow(
        sharedPreferences.getBoolean(gridPlaylistsKey, true)
    )
    val gridPlaylists = _gridPlaylist.asStateFlow()
    fun updateGridPlaylists(value: Boolean) {
        _gridPlaylist.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(gridPlaylistsKey, value)
            apply()
        }
    }

    private val _ytDlpAutoUpdate = MutableStateFlow(
        sharedPreferences.getBoolean(ytDlpAutoUpdateKey, true)
    )
    val ytDlpAutoUpdate = _ytDlpAutoUpdate.asStateFlow()
    fun updateYtDlpAutoUpdate(value: Boolean) {
        _ytDlpAutoUpdate.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(ytDlpAutoUpdateKey, value)
            apply()
        }
    }

    private val _ytDlpUpdateChannel = MutableStateFlow(
        YtDlpUpdateChannel.entries.getOrElse(
            sharedPreferences.getInt(ytDlpUpdateChannelKey, YtDlpUpdateChannel.NIGHTLY.ordinal)
        ) { YtDlpUpdateChannel.NIGHTLY }
    )
    val ytDlpUpdateChannel = _ytDlpUpdateChannel.asStateFlow()
    fun updateYtDlpUpdateChannel(value: YtDlpUpdateChannel) {
        _ytDlpUpdateChannel.update { value }
        with(sharedPreferences.edit()) {
            putInt(ytDlpUpdateChannelKey, value.ordinal)
            apply()
        }
    }

    private val _ytDlpUpdateIntervalDays = MutableStateFlow(
        sharedPreferences.getInt(ytDlpUpdateIntervalDaysKey, 7)
    )
    val ytDlpUpdateIntervalDays = _ytDlpUpdateIntervalDays.asStateFlow()
    fun updateYtDlpUpdateIntervalDays(value: Int) {
        _ytDlpUpdateIntervalDays.update { value }
        with(sharedPreferences.edit()) {
            putInt(ytDlpUpdateIntervalDaysKey, value)
            apply()
        }
    }

    var ytDlpVersion: String
        get() = sharedPreferences.getString(ytDlpVersionKey, "").orEmpty()
        set(value) {
            with(sharedPreferences.edit()) {
                putString(ytDlpVersionKey, value)
                apply()
            }
        }

    var ytDlpLastUpdate: Long
        get() = sharedPreferences.getLong(ytDlpLastUpdateKey, 0L)
        set(value) {
            with(sharedPreferences.edit()) {
                putLong(ytDlpLastUpdateKey, value)
                apply()
            }
        }

    private val _downloadFormat = MutableStateFlow(
        DownloadAudioFormat.entries.getOrElse(
            sharedPreferences.getInt(downloadFormatKey, DownloadAudioFormat.OPUS.ordinal)
        ) { DownloadAudioFormat.OPUS }
    )
    val downloadFormat = _downloadFormat.asStateFlow()
    fun updateDownloadFormat(value: DownloadAudioFormat) {
        _downloadFormat.update { value }
        with(sharedPreferences.edit()) {
            putInt(downloadFormatKey, value.ordinal)
            apply()
        }
    }

    private val _downloadQuality = MutableStateFlow(
        DownloadAudioQuality.entries.getOrElse(
            sharedPreferences.getInt(downloadQualityKey, DownloadAudioQuality.HIGH.ordinal)
        ) { DownloadAudioQuality.HIGH }
    )
    val downloadQuality = _downloadQuality.asStateFlow()
    fun updateDownloadQuality(value: DownloadAudioQuality) {
        _downloadQuality.update { value }
        with(sharedPreferences.edit()) {
            putInt(downloadQualityKey, value.ordinal)
            apply()
        }
    }

    private val _embedMetadata = MutableStateFlow(
        sharedPreferences.getBoolean(embedMetadataKey, true)
    )
    val embedMetadata = _embedMetadata.asStateFlow()
    fun updateEmbedMetadata(value: Boolean) {
        _embedMetadata.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(embedMetadataKey, value)
            apply()
        }
    }

    private val _embedThumbnail = MutableStateFlow(
        sharedPreferences.getBoolean(embedThumbnailKey, true)
    )
    val embedThumbnail = _embedThumbnail.asStateFlow()
    fun updateEmbedThumbnail(value: Boolean) {
        _embedThumbnail.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(embedThumbnailKey, value)
            apply()
        }
    }

    private val _notificationsEnabled = MutableStateFlow(
        sharedPreferences.getBoolean(notificationsEnabledKey, true)
    )
    val notificationsEnabled = _notificationsEnabled.asStateFlow()
    fun updateNotificationsEnabled(value: Boolean) {
        _notificationsEnabled.update { value }
        with(sharedPreferences.edit()) {
            putBoolean(notificationsEnabledKey, value)
            apply()
        }
    }
}
