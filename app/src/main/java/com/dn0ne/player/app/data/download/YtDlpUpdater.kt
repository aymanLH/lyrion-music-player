package com.dn0ne.player.app.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dn0ne.player.app.domain.download.YtDlpUpdateChannel
import com.dn0ne.player.core.data.Settings
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Port of Seal's `UpdateUtil.updateYtDlp`.
 *
 * Automatic updates are gated by:
 *  - the auto-update setting (or an empty stored version),
 *  - the download queue being idle,
 *  - the active network not being metered,
 *  - the configured interval having elapsed.
 *
 * The gate runs from an app-start job, never from a composable.
 */
class YtDlpUpdater(
    context: Context,
    private val engine: YtDlpEngine,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext

    suspend fun autoUpdateIfNeeded(
        isDownloaderIdle: () -> Boolean = { true },
    ): YtDlpUpdateResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val intervalMs = settings.ytDlpUpdateIntervalDays.value
            .coerceAtLeast(1)
            .toLong() * DAY_MILLIS
        val isDue = settings.ytDlpLastUpdate <= 0L ||
                now - settings.ytDlpLastUpdate >= intervalMs
        val isEnabled = settings.ytDlpAutoUpdate.value || settings.ytDlpVersion.isBlank()

        if (!isEnabled) return@withContext YtDlpUpdateResult.Skipped
        if (!isDue) return@withContext YtDlpUpdateResult.Skipped
        if (!isDownloaderIdle()) return@withContext YtDlpUpdateResult.Skipped
        if (isActiveNetworkMetered()) return@withContext YtDlpUpdateResult.Skipped

        performUpdate()
    }

    /** Manual "Update yt-dlp" action; ignores the interval/network gates. */
    suspend fun updateNow(): YtDlpUpdateResult = withContext(Dispatchers.IO) {
        performUpdate()
    }

    private suspend fun performUpdate(): YtDlpUpdateResult {
        return try {
            val status = engine.update(settings.ytDlpUpdateChannel.value.toYoutubeDlChannel())
            if (status == YoutubeDL.UpdateStatus.DONE) {
                engine.version()?.let { settings.ytDlpVersion = it }
            } else if (settings.ytDlpVersion.isBlank()) {
                engine.version()?.let { settings.ytDlpVersion = it }
            }
            settings.ytDlpLastUpdate = System.currentTimeMillis()

            when (status) {
                YoutubeDL.UpdateStatus.DONE ->
                    YtDlpUpdateResult.Updated(settings.ytDlpVersion)
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                    YtDlpUpdateResult.AlreadyUpToDate(settings.ytDlpVersion)
                else -> YtDlpUpdateResult.AlreadyUpToDate(settings.ytDlpVersion)
            }
        } catch (throwable: Throwable) {
            YtDlpUpdateResult.Failed(throwable.message ?: throwable::class.java.simpleName)
        }
    }

    private fun isActiveNetworkMetered(): Boolean {
        return try {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return false
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (throwable: Throwable) {
            false
        }
    }

    private fun YtDlpUpdateChannel.toYoutubeDlChannel(): YoutubeDL.UpdateChannel =
        when (this) {
            YtDlpUpdateChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
            YtDlpUpdateChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
        }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

sealed interface YtDlpUpdateResult {
    data class Updated(val version: String) : YtDlpUpdateResult
    data class AlreadyUpToDate(val version: String) : YtDlpUpdateResult
    data class Failed(val message: String) : YtDlpUpdateResult
    data object Skipped : YtDlpUpdateResult
}
