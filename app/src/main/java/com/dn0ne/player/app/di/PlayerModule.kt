package com.dn0ne.player.app.di

import com.dn0ne.player.EqualizerController
import com.dn0ne.player.app.data.LyricsReader
import com.dn0ne.player.app.data.LyricsReaderImpl
import com.dn0ne.player.app.data.MetadataWriter
import com.dn0ne.player.app.data.MetadataWriterImpl
import com.dn0ne.player.app.data.SavedPlayerState
import com.dn0ne.player.app.data.download.YtDlpEngine
import com.dn0ne.player.app.data.download.YtDlpEngineImpl
import com.dn0ne.player.app.data.download.YtDlpUpdater
import com.dn0ne.player.app.data.download.MediaStorePublisher
import com.dn0ne.player.app.data.local.history.AppDatabase
import com.dn0ne.player.app.data.local.history.DownloadHistoryDao
import com.dn0ne.player.app.data.notification.DownloadNotifications
import com.dn0ne.player.app.data.preview.PlaybackLink
import com.dn0ne.player.app.data.preview.PreviewPlayer
import com.dn0ne.player.app.data.remote.lyrics.LrclibLyricsProvider
import com.dn0ne.player.app.data.remote.lyrics.LyricsProvider
import com.dn0ne.player.app.data.remote.metadata.MetadataProvider
import com.dn0ne.player.app.data.remote.metadata.MusicBrainzMetadataProvider
import com.dn0ne.player.app.data.remote.youtube.YoutubeProvider
import com.dn0ne.player.app.data.remote.youtube.YoutubeProviderImpl
import com.dn0ne.player.app.data.repository.LyricsJson
import com.dn0ne.player.app.data.repository.LyricsRepository
import com.dn0ne.player.app.data.repository.PlaylistJson
import com.dn0ne.player.app.data.repository.PlaylistRepository
import com.dn0ne.player.app.data.repository.RealmLyricsRepository
import com.dn0ne.player.app.data.repository.RealmPlaylistRepository
import com.dn0ne.player.app.data.repository.DownloadRepository
import com.dn0ne.player.app.data.repository.DownloadRepositoryImpl
import com.dn0ne.player.app.data.repository.DownloadHistoryRepository
import com.dn0ne.player.app.data.repository.RoomDownloadHistoryRepository
import com.dn0ne.player.app.data.repository.TrackRepository
import com.dn0ne.player.app.data.repository.TrackRepositoryImpl
import com.dn0ne.player.app.presentation.PlayerViewModel
import com.dn0ne.player.app.presentation.downloads.DownloadsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import androidx.room.Room
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val playerModule = module {

    single<YtDlpEngine>(createdAtStart = true) {
        YtDlpEngineImpl(context = androidContext())
    }

    single {
        YtDlpUpdater(
            context = androidContext(),
            engine = get(),
            settings = get()
        )
    }

    single<YoutubeProvider> {
        YoutubeProviderImpl(engine = get(), httpClient = get())
    }

    single<DownloadRepository>(createdAtStart = true) {
        DownloadRepositoryImpl(
            context = androidContext(),
            engine = get(),
            youtubeProvider = get(),
            mediaStorePublisher = get(),
            notifications = get(),
            historyRepository = get(),
            settings = get(),
        )
    }

    single { MediaStorePublisher(context = androidContext()) }

    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            AppDatabase.NAME,
        ).build()
    }

    single<DownloadHistoryDao> { get<AppDatabase>().downloadHistoryDao() }

    single<DownloadHistoryRepository> {
        RoomDownloadHistoryRepository(
            context = androidContext(),
            dao = get(),
        )
    }

    single {
        DownloadNotifications(
            context = androidContext(),
            settings = get(),
        )
    }

    single { PlaybackLink() }

    single {
        PreviewPlayer(
            context = androidContext(),
            youtubeProvider = get(),
            playbackLink = get()
        )
    }

    single<TrackRepository> {
        TrackRepositoryImpl(
            context = androidContext(),
            settings = get()
        )
    }

    single<SavedPlayerState> {
        SavedPlayerState(
            context = androidContext()
        )
    }

    single<HttpClient> {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 180000
            }
        }
    }

    single<MetadataProvider> {
        MusicBrainzMetadataProvider(
            context = androidContext(),
            client = get()
        )
    }

    single<MetadataWriter> {
        MetadataWriterImpl(context = androidContext())
    }

    single<LyricsProvider> {
        LrclibLyricsProvider(
            context = androidContext(),
            client = get()
        )
    }

    single<Realm> {
        val configuration = RealmConfiguration.create(
            schema = setOf(LyricsJson::class, PlaylistJson::class)
        )

        Realm.open(configuration)
    }

    single<LyricsRepository> {
        RealmLyricsRepository(
            realm = get()
        )
    }

    single<PlaylistRepository> {
        RealmPlaylistRepository(
            realm = get()
        )
    }

    single<EqualizerController> {
        EqualizerController(
            context = androidContext()
        )
    }

    single<LyricsReader> {
        LyricsReaderImpl(
            context = androidContext()
        )
    }

    viewModel<PlayerViewModel> {
        PlayerViewModel(
            savedPlayerState = get(),
            trackRepository = get(),
            metadataProvider = get(),
            lyricsProvider = get(),
            lyricsRepository = get(),
            lyricsReader = get(),
            playlistRepository = get(),
            unsupportedArtworkEditFormats = get<MetadataWriter>().unsupportedArtworkEditFormats,
            settings = get(),
            musicScanner = get(),
            equalizerController = get(),
            ytDlpUpdater = get(),
            playbackLink = get(),
            previewPlayer = get()
        )
    }

    viewModel<DownloadsViewModel> {
        DownloadsViewModel(
            context = androidContext(),
            youtubeProvider = get(),
            downloadRepository = get(),
            historyRepository = get(),
            previewPlayer = get(),
        )
    }
}
