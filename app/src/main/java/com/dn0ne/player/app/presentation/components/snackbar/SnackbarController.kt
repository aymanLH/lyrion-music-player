package com.dn0ne.player.app.presentation.components.snackbar

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object SnackbarController {

    private val _events = Channel<SnackbarEvent>()
    val events = _events.receiveAsFlow()

    suspend fun sendEvent(event: SnackbarEvent) {
        _events.send(event)
    }

    /**
     * Non-suspending variant for background work. Returns false when no UI is collecting,
     * in which case the event is dropped instead of being delivered long after the fact.
     */
    fun trySendEvent(event: SnackbarEvent): Boolean = _events.trySend(event).isSuccess
}