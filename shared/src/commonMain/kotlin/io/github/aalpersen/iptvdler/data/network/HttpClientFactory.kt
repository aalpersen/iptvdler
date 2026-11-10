package io.github.aalpersen.iptvdler.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

const val DEFAULT_USER_AGENT = "iptvdler/0.1"

/** Shared client; the engine (OkHttp on Android and desktop) is picked from the classpath. */
fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = false
    install(UserAgent) { agent = DEFAULT_USER_AGENT }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
        // No overall request timeout: large playlists and downloads can legitimately take minutes.
    }
}
