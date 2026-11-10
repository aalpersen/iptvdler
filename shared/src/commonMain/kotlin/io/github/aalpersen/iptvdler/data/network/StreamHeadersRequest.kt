package io.github.aalpersen.iptvdler.data.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders

/** Applies a channel's headers, replacing defaults such as the client-wide User-Agent. */
fun HttpRequestBuilder.streamHeaders(headers: Map<String, String>) {
    headers.forEach { (name, value) ->
        this.headers.remove(name)
        this.headers.append(name, value)
    }
    if (headers.keys.none { it.equals(HttpHeaders.UserAgent, ignoreCase = true) }) {
        this.headers.remove(HttpHeaders.UserAgent)
        this.headers.append(HttpHeaders.UserAgent, DEFAULT_USER_AGENT)
    }
}
