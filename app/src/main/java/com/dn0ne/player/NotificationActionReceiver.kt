package com.dn0ne.player

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.dn0ne.player.app.data.repository.DownloadRepository
import org.koin.core.context.GlobalContext

/**
 * Handles the notification actions: cancel a running download and copy an error report.
 * Declared as `exported=false`; only app-created `PendingIntent`s can reach it.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                GlobalContext.get().get<DownloadRepository>().cancel(taskId)
            }

            ACTION_COPY_REPORT -> {
                val report = intent.getStringExtra(EXTRA_REPORT) ?: return
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
                    ?: return
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("yt-dlp report", report)
                )
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.dn0ne.player.action.CANCEL_DOWNLOAD"
        const val ACTION_COPY_REPORT = "com.dn0ne.player.action.COPY_DOWNLOAD_REPORT"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_REPORT = "report"
    }
}
