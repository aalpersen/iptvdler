package io.github.aalpersen.iptvdler.data.update

import com.russhwolf.settings.MapSettings
import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckerTest {

    private val env = TestEnvironment()
    private val settings = AppSettings(MapSettings())
    private val checker = UpdateChecker(env.httpClient, settings, env.clock, CoroutineScope(Dispatchers.Unconfined), currentVersion = "0.1.0")
    private val path = "/repos/aalpersen/iptvdler/releases/latest"

    private fun release(tag: String, url: String = "https://github.com/aalpersen/iptvdler/releases/tag/$tag", pre: Boolean = false) =
        HttpStatusCode.OK to """{"tag_name":"$tag","html_url":"$url","draft":false,"prerelease":$pre,"assets":[]}"""

    private fun apiCalls() = env.requestedUrls.count { it.endsWith(path) }

    @Test
    fun offersNewerReleaseWithItsPage() = runBlocking {
        env.responses[path] = release("v0.2.0")
        checker.checkIfDue()
        assertEquals(
            AvailableUpdate("0.2.0", "https://github.com/aalpersen/iptvdler/releases/tag/v0.2.0"),
            checker.available.first(),
        )
    }

    @Test
    fun sameOrOlderReleaseOffersNothing() = runBlocking {
        env.responses[path] = release("v0.1.0")
        checker.checkIfDue()
        assertNull(checker.available.first())
    }

    @Test
    fun checksAtMostOncePerInterval() = runBlocking {
        env.responses[path] = release("v0.2.0")
        checker.checkIfDue()
        checker.checkIfDue()
        assertEquals(1, apiCalls())
        env.now += UpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkIfDue()
        assertEquals(2, apiCalls())
    }

    @Test
    fun turnedOffMeansNoRequestAndNoBanner() = runBlocking {
        env.responses[path] = release("v0.2.0")
        checker.checkIfDue()
        settings.setUpdateCheckEnabled(false)
        assertNull(checker.available.first())
        env.now += UpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkIfDue()
        assertEquals(1, apiCalls())
    }

    @Test
    fun dismissHidesOnlyThatVersion() = runBlocking {
        env.responses[path] = release("v0.2.0")
        checker.checkIfDue()
        checker.dismiss(checker.available.first()!!)
        assertNull(checker.available.first())

        env.responses[path] = release("v0.3.0")
        env.now += UpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkIfDue()
        assertEquals("0.3.0", checker.available.first()?.version)
    }

    @Test
    fun ignoresPreReleasesAndForeignPageUrls() = runBlocking {
        env.responses[path] = release("v0.2.0-test", pre = true)
        checker.checkIfDue()
        assertNull(checker.available.first())

        env.responses[path] = release("v0.2.0", url = "https://evil.example/download")
        env.now += UpdateChecker.CHECK_INTERVAL_MILLIS
        checker.checkIfDue()
        assertEquals(UpdateChecker.RELEASES_PAGE, checker.available.first()?.pageUrl)
    }

    @Test
    fun networkErrorsAndGarbageAreIgnored() = runBlocking {
        env.responses[path] = HttpStatusCode.InternalServerError to "oops"
        checker.checkIfDue()
        env.responses[path] = HttpStatusCode.OK to "<html>"
        checker.checkIfDue()
        assertNull(checker.available.first())
        assertEquals(2, apiCalls()) // failures don't count as a completed check
    }
}
