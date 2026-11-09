package com.dn0ne.player.app.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Result of publishing a downloaded file into the shared music library. */
data class PublishedAudio(
    val uri: String,
    val displayName: String,
    val size: Long,
)

/**
 * Publishes a downloaded audio file into the shared library.
 *
 * API 29+ uses `MediaStore.Audio` with `RELATIVE_PATH = Music/Lyrion` and the
 * `IS_PENDING` handshake, so no storage permission is required. Older releases copy the
 * file into the same public folder and run a media scan.
 *
 * On success the staging file is removed from `getExternalFilesDir("downloads")`.
 */
class MediaStorePublisher(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun publish(
        file: File,
        title: String,
        taskId: String,
    ): Result<PublishedAudio> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = buildDisplayName(file, title, taskId)
            val size = file.length()

            val published = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(file, displayName, title, size)
            } else {
                publishLegacy(file, displayName, size)
            }

            // The shared-library copy is authoritative now.
            file.delete()
            published
        }.onFailure {
            Log.e(TAG, "Failed to publish $file", it)
        }
    }

    private fun publishToMediaStore(
        file: File,
        displayName: String,
        title: String,
        size: Long,
    ): PublishedAudio {
        val collection = MediaStore.Audio.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )

        val existing = findExistingMediaStoreEntry(collection, displayName)
        if (existing != null) {
            if (existing.isPending) {
                copyIntoMediaStore(file, existing.uri)
                resolver.update(
                    existing.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                Log.i(TAG, "Completed interrupted MediaStore publish uri=${existing.uri}")
            } else {
                Log.i(TAG, "Reused existing MediaStore publish uri=${existing.uri}")
            }
            return PublishedAudio(existing.uri.toString(), displayName, size)
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeTypeFor(file.extension))
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/$ALBUM_FOLDER"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: error("MediaStore rejected the audio insert")

        try {
            copyIntoMediaStore(file, uri)

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (throwable: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw throwable
        }

        return PublishedAudio(
            uri = uri.toString(),
            displayName = displayName,
            size = size,
        )
    }

    private fun findExistingMediaStoreEntry(
        collection: Uri,
        displayName: String,
    ): ExistingMediaStoreEntry? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.IS_PENDING,
        )
        return resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(displayName, "${Environment.DIRECTORY_MUSIC}/$ALBUM_FOLDER%"),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val pendingIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
            val isPending = pendingIndex >= 0 && cursor.getInt(pendingIndex) != 0
            ExistingMediaStoreEntry(
                uri = Uri.withAppendedPath(collection, id.toString()),
                isPending = isPending,
            )
        }
    }

    private fun copyIntoMediaStore(file: File, uri: Uri) {
        resolver.openOutputStream(uri, "w")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("MediaStore returned no output stream")
    }

    private suspend fun publishLegacy(
        file: File,
        displayName: String,
        size: Long,
    ): PublishedAudio {
        val musicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            ALBUM_FOLDER,
        )
        musicDir.mkdirs()

        val destination = File(musicDir, displayName)
        file.inputStream().use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val scannedUri = scanFile(destination.absolutePath)

        return PublishedAudio(
            uri = (scannedUri ?: Uri.fromFile(destination)).toString(),
            displayName = destination.name,
            size = size,
        )
    }

    private suspend fun scanFile(path: String): Uri? = withTimeoutOrNull(SCAN_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(path),
                arrayOf("audio/*"),
            ) { _, uri ->
                if (continuation.isActive) {
                    continuation.resume(uri)
                }
            }
        }
    }

    private fun buildDisplayName(file: File, title: String, taskId: String): String {
        val extension = file.extension.ifBlank { "opus" }
        val taskToken = MessageDigest.getInstance("SHA-256")
            .digest(taskId.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { byte -> "%02x".format(byte) }
        val safeTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .ifBlank { file.nameWithoutExtension }
            .take(MAX_TITLE_LENGTH - taskToken.length - 3)

        return "$safeTitle [$taskToken].$extension"
    }

    private fun mimeTypeFor(extension: String): String {
        val normalized = extension.lowercase()
        return when (normalized) {
            "opus" -> "audio/opus"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/x-wav"
            "aac" -> "audio/aac"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(normalized)
                ?: "audio/$normalized"
        }
    }

    private companion object {
        const val TAG = "MediaStorePublisher"
        const val ALBUM_FOLDER = "Lyrion"
        const val MAX_TITLE_LENGTH = 200
        const val SCAN_TIMEOUT_MILLIS = 15_000L
    }

    private data class ExistingMediaStoreEntry(
        val uri: Uri,
        val isPending: Boolean,
    )
}
