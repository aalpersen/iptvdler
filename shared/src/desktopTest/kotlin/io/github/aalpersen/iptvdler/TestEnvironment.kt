package io.github.aalpersen.iptvdler

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.playlist.LocalFileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Properties
import okio.Buffer

/** Fake time, in-memory database, fixture-backed files and a mock HTTP server for JVM tests. */
class TestEnvironment {
    var now = 1_700_000_000_000L
    val clock = EpochClock { now }

    val database = IptvdlerDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), IptvdlerDatabase.Schema))

    /** In-memory "files" by name; anything else is read from the test fixtures. */
    val files = mutableMapOf<String, String>()

    val localFiles = LocalFileSource { name -> Buffer().writeUtf8(files[name] ?: readFixture(name)) }

    /** Path -> (status, body) served by [httpClient]. */
    val responses = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    /** Path -> binary body (served with 200 and Content-Length). */
    val binaryResponses = mutableMapOf<String, ByteArray>()

    /** Every requested URL, in order. */
    val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())

    val httpClient = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requestedUrls += request.url.toString()
                binaryResponses[request.url.encodedPath]?.let { bytes ->
                    return@addHandler respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
                }
                // API endpoints (Xtream's player_api.php) are told apart by their "action" parameter.
                val action = request.url.parameters["action"]
                val (status, body) = action?.let { responses["${request.url.encodedPath}?action=$it"] }
                    ?: responses[request.url.encodedPath]
                    ?: (HttpStatusCode.NotFound to "")
                respond(body, status)
            }
        }
    }

    companion object {
        const val BASE_URL = "https://lists.example.test"
    }
}
