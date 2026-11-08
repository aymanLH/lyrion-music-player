package com.dn0ne.player.app.data.download

/** Classification of a yt-dlp failure. */
enum class DownloadErrorKind {
    RATE_LIMITED,
    UNKNOWN,
}

data class DownloadError(
    val kind: DownloadErrorKind,
    /** Short classified error text. The full technical report is only exposed via copy action. */
    val message: String,
    val report: String,
)

/**
 * Detects YouTube's rate limiting / bot check.
 *
 * yt-dlp surfaces these as an exception whose message contains the extractor output, for
 * example:
 *  - `ERROR: [youtube] …: Sign in to confirm you're not a bot.`
 *  - `HTTP Error 429: Too Many Requests`
 *
 * These are treated as one kind of failure on purpose: all three mean "wait a bit and try
 * again", and none of them should ever be retried automatically in a loop.
 */
object DownloadErrorClassifier {
    private val RATE_LIMIT_MARKERS = listOf(
        "429",
        "too many requests",
        "confirm you're not a bot",
        "confirm you’re not a bot",
        "sign in to confirm",
    )

    fun isRateLimited(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return RATE_LIMIT_MARKERS.any { text.contains(it) }
    }

    fun classify(throwable: Throwable): DownloadError {
        val message = throwable.message ?: throwable::class.java.simpleName
        val kind = if (
            throwable is DownloadRateLimitedException || isRateLimited(message)
        ) {
            DownloadErrorKind.RATE_LIMITED
        } else {
            DownloadErrorKind.UNKNOWN
        }

        return DownloadError(
            kind = kind,
            message = message,
            report = throwable.stackTraceToString(),
        )
    }
}

/**
 * Raised instead of starting yt-dlp while the global fetch cooldown is active, or when a
 * previous call already proved the client is being rate limited.
 */
class DownloadRateLimitedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
