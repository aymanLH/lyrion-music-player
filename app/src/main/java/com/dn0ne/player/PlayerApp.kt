package com.dn0ne.player

import android.app.Application
import com.dn0ne.player.app.data.download.YtDlpUpdater
import com.dn0ne.player.app.data.repository.DownloadRepository
import com.dn0ne.player.app.di.playerModule
import com.dn0ne.player.core.di.appModule
import com.dn0ne.player.setup.di.setupModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class PlayerApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@PlayerApp)
            modules(appModule, setupModule, playerModule)
        }

        // YtDlpEngine is a createdAtStart singleton, so YoutubeDL/FFmpeg initialization
        // is already running on Dispatchers.IO by the time this job is scheduled.
        appScope.launch {
            runCatching {
                val koin = GlobalContext.get()
                val downloadRepository = koin.get<DownloadRepository>()
                koin.get<YtDlpUpdater>().autoUpdateIfNeeded {
                    // Only update while the queue is idle.
                    !downloadRepository.hasRunningTasks()
                }
            }
        }
    }
}
