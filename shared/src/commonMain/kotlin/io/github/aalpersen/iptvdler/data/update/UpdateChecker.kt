package io.github.aalpersen.iptvdler.data.update

import io.github.aalpersen.iptvdler.BuildInfo
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.update.AppVersion
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.platform.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer release than the running app; [pageUrl] is its GitHub release page. */
data class AvailableUpdate(val version: String, val pageUrl: String)

/**
 * Asks the GitHub Releases API whether a newer version exists: at most once per [CHECK_INTERVAL_MILLIS],
 * only while the user leaves it on. It never downloads or installs anything; the UI only links to the
 * release page. Pre-releases and drafts are ignored (the `latest` endpoint skips them).
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val settings: AppSettings,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val currentVersion: String = BuildInfo.VERSION_NAME,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The update to offer, or null (none known, check turned off, already current, or dismissed). */
    val available: Flow<AvailableUpdate?> = combine(
        settings.updateCheckEnabled,
        settings.latestRelease,
        settings.dismissedUpdate,
    ) { enabled, latest, dismissed ->
        latest?.takeIf { enabled && it.version != dismissed && AppVersion.isNewer(it.version, currentVersion) }
    }

    /** Runs [checkIfDue] without blocking the caller. */
    fun checkInBackground() {
        scope.launch { checkIfDue() }
    }

    /** Checks if enabled and the last successful check is older than the interval. Never throws. */
    suspend fun checkIfDue() {
        if (!settings.updateCheckEnabled.value) return
        val now = clock.nowMillis()
        val last = settings.lastUpdateCheckMillis
        if (last in (now - CHECK_INTERVAL_MILLIS + 1)..now) return
        try {
            val response = httpClient.get(LATEST_RELEASE_API) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            if (!response.status.isSuccess()) {
                // 404 = no published release yet; still counts as checked.
                if (response.status.value == 404) settings.setLastUpdateCheckMillis(now)
                return
            }
            val release = json.decodeFromString<GitHubRelease>(response.bodyAsText())
            settings.setLastUpdateCheckMillis(now)
            if (release.draft || release.prerelease) return
            val version = release.tagName.removePrefix("v").removePrefix("V")
            if (AppVersion.parse(version) == null) return
            settings.setLatestRelease(AvailableUpdate(version, safePageUrl(release.htmlUrl)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.warn("Update check failed", e)
        }
    }

    /** Hides the banner for this version; a later release shows it again. */
    fun dismiss(update: AvailableUpdate) = settings.setDismissedUpdate(update.version)

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    companion object {
        const val REPO = "aalpersen/iptvdler"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /** Only ever open this repo's release pages, whatever the API returned. */
        internal fun safePageUrl(url: String): String =
            if (url.startsWith("$RELEASES_PAGE/")) url else RELEASES_PAGE
    }
}
