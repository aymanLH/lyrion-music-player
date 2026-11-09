package com.dn0ne.player.app.data.remote.youtube

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.client.plugins.timeout
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse

/** Synchronous NewPipe Downloader backed by the app's pooled Ktor client. */
internal class KtorNewPipeDownloader(
    private val client: HttpClient,
) : Downloader() {
    override fun execute(request: NewPipeRequest): NewPipeResponse = runBlocking(Dispatchers.IO) {
        val requestHeaders = headersFor(request)
        val bodyContentType = requestHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.let(ContentType::parse)
            ?: ContentType.Application.OctetStream

        val response = client.request {
            method = HttpMethod(request.httpMethod())
            url { takeFrom(request.url()) }
            headers {
                requestHeaders.forEach { (name, values) ->
                    values.forEach { value -> append(name, value) }
                }
            }
            request.dataToSend()?.let { data ->
                contentType(bodyContentType)
                setBody(data)
            }
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }

        NewPipeResponse(
            response.status.value,
            response.status.description,
            response.headers.entries().associate { it.key to it.value },
            response.bodyAsText(),
            response.call.request.url.toString(),
        )
    }

    private fun headersFor(request: NewPipeRequest): MutableMap<String, List<String>> {
        val headers = NewPipeRequest.getHeadersFromLocalization(request.localization()).toMutableMap()
        request.headers().forEach { (name, values) ->
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach(headers::remove)
            headers[name] = values
        }
        return headers
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

internal object NewPipeRuntime {
    @Volatile
    private var initialized = false

    fun initialize(client: HttpClient) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                org.schabi.newpipe.extractor.NewPipe.init(KtorNewPipeDownloader(client))
                initialized = true
            }
        }
    }
}
