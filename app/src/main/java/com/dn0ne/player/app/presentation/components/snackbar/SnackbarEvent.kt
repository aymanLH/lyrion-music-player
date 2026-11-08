package com.dn0ne.player.app.presentation.components.snackbar

import androidx.annotation.StringRes

data class SnackbarEvent(
    @StringRes val message: Int,
    val action: SnackbarAction? = null,
    /**
     * Already-resolved text that takes precedence over [message]. Used for yt-dlp output,
     * which is not a translatable resource.
     */
    val rawMessage: String? = null,
)