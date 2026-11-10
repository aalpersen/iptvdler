package io.github.aalpersen.iptvdler.data.playlist

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** An http:// playlist that the server (or a network in between) redirects to https on a plain-HTTP port. */
class RedirectToHttpsTest {

    private val env = TestEnvironment()

    private val client = HttpClient(MockEngine) {
        expectSuccess = false
        engine {
            addHandler { request ->
                if (request.url.protocol == URLProtocol.HTTPS) {
                    // What Android reports when the other side answers TLS with plain HTTP.
                    throw SSLHandshakeException("Unable to parse TLS packet header")
                }
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://iptv.example.test:8080/get.php?username=u&password=p"))
            }
        }
    }

    @Test
    fun explainsTheTlsErrorAndShowsWhereItWasSent() = runBlocking {
        val repository = PlaylistRepository(env.database, Dispatchers.IO, env.clock)
        val refresher = PlaylistRefresher(env.database, client, env.localFiles, Dispatchers.IO, CoroutineScope(Dispatchers.IO), env.clock)
        val id = repository.addPlaylist(PlaylistInput("P", "http://iptv.example.test:8080/get.php?username=u&password=p", PlaylistSourceType.URL, null))

        val result = assertIs<RefreshResult.Failed>(refresher.refreshNow(id))
        assertEquals(
            strings.readPlaylistFailed(UserMessages.NOT_TLS) + " " +
                strings.redirectedTo("https://iptv.example.test:8080/get.php?username=***&password=***"),
            result.message,
        )
    }
}
