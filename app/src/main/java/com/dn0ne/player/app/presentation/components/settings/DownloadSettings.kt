package com.dn0ne.player.app.presentation.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.dn0ne.player.R
import com.dn0ne.player.app.data.download.YtDlpUpdateResult
import com.dn0ne.player.app.data.download.YtDlpUpdater
import com.dn0ne.player.app.domain.download.DownloadAudioFormat
import com.dn0ne.player.app.domain.download.DownloadAudioQuality
import com.dn0ne.player.app.domain.download.YtDlpUpdateChannel
import com.dn0ne.player.app.presentation.components.topbar.ColumnWithCollapsibleTopBar
import com.dn0ne.player.core.data.Settings
import kotlinx.coroutines.launch

@Composable
fun DownloadSettings(
    settings: Settings,
    ytDlpUpdater: YtDlpUpdater,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var collapseFraction by remember {
        mutableFloatStateOf(0f)
    }

    ColumnWithCollapsibleTopBar(
        topBarContent = {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBackIosNew,
                    contentDescription = context.resources.getString(R.string.back)
                )
            }

            Text(
                text = context.resources.getString(R.string.downloads),
                fontSize = lerp(
                    MaterialTheme.typography.titleLarge.fontSize,
                    MaterialTheme.typography.displaySmall.fontSize,
                    collapseFraction
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
            )
        },
        collapseFraction = {
            collapseFraction = it
        },
        contentPadding = PaddingValues(horizontal = 28.dp),
        contentHorizontalAlignment = Alignment.CenterHorizontally,
        contentVerticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        DownloadSettingsContent(
            settings = settings,
            ytDlpUpdater = ytDlpUpdater
        )
    }
}

@Composable
fun DownloadSettingsContent(
    settings: Settings,
    ytDlpUpdater: YtDlpUpdater,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val autoUpdate by settings.ytDlpAutoUpdate.collectAsState()
    val updateChannel by settings.ytDlpUpdateChannel.collectAsState()
    val downloadFormat by settings.downloadFormat.collectAsState()
    val downloadQuality by settings.downloadQuality.collectAsState()
    val embedMetadata by settings.embedMetadata.collectAsState()
    val embedThumbnail by settings.embedThumbnail.collectAsState()
    val notificationsEnabled by settings.notificationsEnabled.collectAsState()

    var version by remember { mutableStateOf(settings.ytDlpVersion) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
    ) {
        SettingTextOptionsRow(
            title = context.resources.getString(R.string.download_format),
            options = listOf(
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_format_original),
                    onSelection = { settings.updateDownloadFormat(DownloadAudioFormat.ORIGINAL) }
                ),
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_format_opus),
                    onSelection = { settings.updateDownloadFormat(DownloadAudioFormat.OPUS) }
                ),
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_format_m4a),
                    onSelection = { settings.updateDownloadFormat(DownloadAudioFormat.M4A) }
                ),
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_format_mp3),
                    onSelection = { settings.updateDownloadFormat(DownloadAudioFormat.MP3) }
                ),
            ),
            selectedTitle = downloadFormat.displayName(context)
        )

        Text(
            text = context.resources.getString(R.string.download_format_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        SettingTextOptionsRow(
            title = context.resources.getString(R.string.download_quality),
            options = listOf(
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_quality_high),
                    onSelection = { settings.updateDownloadQuality(DownloadAudioQuality.HIGH) }
                ),
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_quality_medium),
                    onSelection = { settings.updateDownloadQuality(DownloadAudioQuality.MEDIUM) }
                ),
                DownloadTextOption(
                    title = context.resources.getString(R.string.download_quality_low),
                    onSelection = { settings.updateDownloadQuality(DownloadAudioQuality.LOW) }
                ),
            ),
            selectedTitle = downloadQuality.displayName(context)
        )

        SettingSwitch(
            title = context.resources.getString(R.string.embed_metadata),
            supportingText = context.resources.getString(R.string.embed_metadata_explain),
            icon = Icons.Rounded.MusicNote,
            isChecked = embedMetadata,
            onCheckedChange = settings::updateEmbedMetadata,
            modifier = Modifier.fillMaxWidth()
        )

        SettingSwitch(
            title = context.resources.getString(R.string.embed_thumbnail),
            supportingText = context.resources.getString(R.string.embed_thumbnail_explain),
            icon = Icons.Rounded.Image,
            isChecked = embedThumbnail,
            onCheckedChange = settings::updateEmbedThumbnail,
            modifier = Modifier.fillMaxWidth()
        )

        SettingSwitch(
            title = context.resources.getString(R.string.download_notifications),
            supportingText = context.resources.getString(R.string.download_notifications_explain),
            icon = Icons.Rounded.NotificationsActive,
            isChecked = notificationsEnabled,
            onCheckedChange = settings::updateNotificationsEnabled,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SettingIconButton(
            title = context.resources.getString(R.string.update_yt_dlp),
            supportingText = if (version.isBlank()) {
                context.resources.getString(R.string.yt_dlp_version_unknown)
            } else {
                context.resources.getString(R.string.yt_dlp_version, version)
            },
            icon = Icons.Rounded.Download,
            buttonIcon = Icons.Rounded.Refresh,
            buttonContentDescription = context.resources.getString(R.string.update_yt_dlp),
            onButtonClick = {
                if (isUpdating) return@SettingIconButton

                isUpdating = true
                updateStatus = null
                coroutineScope.launch {
                    val result = ytDlpUpdater.updateNow()
                    version = settings.ytDlpVersion
                    updateStatus = when (result) {
                        is YtDlpUpdateResult.Updated -> context.resources.getString(
                            R.string.yt_dlp_update_updated,
                            result.version
                        )

                        is YtDlpUpdateResult.AlreadyUpToDate -> context.resources.getString(
                            R.string.yt_dlp_update_already_up_to_date,
                            result.version
                        )

                        is YtDlpUpdateResult.Failed -> context.resources.getString(
                            R.string.yt_dlp_update_failed,
                            result.message
                        )

                        YtDlpUpdateResult.Skipped -> context.resources.getString(
                            R.string.yt_dlp_update_skipped
                        )
                    }
                    isUpdating = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (isUpdating) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = context.resources.getString(R.string.yt_dlp_updating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        updateStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingSwitch(
            title = context.resources.getString(R.string.auto_update_yt_dlp),
            supportingText = context.resources.getString(R.string.auto_update_yt_dlp_explain),
            icon = Icons.Rounded.Autorenew,
            isChecked = autoUpdate,
            onCheckedChange = settings::updateYtDlpAutoUpdate,
            modifier = Modifier.fillMaxWidth()
        )

        val channelOptions = remember {
            listOf(
                SettingSegmentOption(
                    icon = Icons.Rounded.Check,
                    contentDescription = context.resources.getString(R.string.update_channel_stable),
                    onClick = {
                        settings.updateYtDlpUpdateChannel(YtDlpUpdateChannel.STABLE)
                    }
                ),
                SettingSegmentOption(
                    icon = Icons.Rounded.NewReleases,
                    contentDescription = context.resources.getString(R.string.update_channel_nightly),
                    onClick = {
                        settings.updateYtDlpUpdateChannel(YtDlpUpdateChannel.NIGHTLY)
                    }
                )
            )
        }

        SettingSegmentOptions(
            title = context.resources.getString(R.string.update_channel),
            supportingText = when (updateChannel) {
                YtDlpUpdateChannel.STABLE ->
                    context.resources.getString(R.string.update_channel_stable)

                YtDlpUpdateChannel.NIGHTLY ->
                    context.resources.getString(R.string.update_channel_nightly)
            },
            icon = Icons.Rounded.NewReleases,
            options = channelOptions,
            selectedOptionIndex = if (updateChannel == YtDlpUpdateChannel.STABLE) 0 else 1
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun SettingTextOptionsRow(
    title: String,
    options: List<DownloadTextOption>,
    selectedTitle: String,
) {
    SettingOptionsRow(
        title = title,
        options = options,
        modifier = Modifier.fillMaxWidth()
    ) { option ->
        val isSelected = option.title == selectedTitle
        Text(
            text = option.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(ShapeDefaults.Large)
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .clickable { option.onSelection() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

private fun DownloadAudioFormat.displayName(
    context: android.content.Context,
): String = when (this) {
    DownloadAudioFormat.ORIGINAL -> context.resources.getString(R.string.download_format_original)
    DownloadAudioFormat.OPUS -> context.resources.getString(R.string.download_format_opus)
    DownloadAudioFormat.M4A -> context.resources.getString(R.string.download_format_m4a)
    DownloadAudioFormat.MP3 -> context.resources.getString(R.string.download_format_mp3)
}

private fun DownloadAudioQuality.displayName(
    context: android.content.Context,
): String = when (this) {
    DownloadAudioQuality.HIGH -> context.resources.getString(R.string.download_quality_high)
    DownloadAudioQuality.MEDIUM -> context.resources.getString(R.string.download_quality_medium)
    DownloadAudioQuality.LOW -> context.resources.getString(R.string.download_quality_low)
}

class DownloadTextOption(
    title: String,
    onSelection: () -> Unit,
) : SettingOption(title, onSelection)
