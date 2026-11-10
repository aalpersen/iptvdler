package io.github.aalpersen.iptvdler.data.xtream

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Login problems (wrong password, expired or banned account); the message is for the user. */
class XtreamAuthException(message: String) : Exception(message)

/** The server answered, but not like an Xtream Codes API. */
class XtreamFormatException(message: String) : Exception(message)

data class XtreamAccount(
    /** "Active", "Expired", "Banned", "Disabled"… as the panel reports it. */
    val status: String,
    /** Epoch millis, null = no expiry. */
    val expiresAt: Long?,
    val maxConnections: Int?,
    /** Stream formats the account may use ("ts", "m3u8", …); empty = not reported. */
    val allowedFormats: List<String>,
) {
    val isActive: Boolean get() = status.equals("Active", ignoreCase = true)

    /** Container for live streams: MPEG-TS when allowed (what IPTV servers serve best), else HLS. */
    val liveExtension: String
        get() = if (allowedFormats.isEmpty() || "ts" in allowedFormats) "ts" else if ("m3u8" in allowedFormats) "m3u8" else allowedFormats.first()
}

data class XtreamCategory(val id: String, val name: String)

/** A live channel, movie or series from the stream lists. */
data class XtreamStream(
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    /** Movies only: "mkv", "mp4", … */
    val containerExtension: String?,
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val containerExtension: String,
    val durationSeconds: Long?,
    val image: String?,
)

data class XtreamSeriesInfo(val name: String?, val cover: String?, val episodes: List<XtreamEpisode>) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
}

/**
 * Minimal Xtream Codes `player_api.php` client. Panels differ in the details (numbers as strings,
 * nulls, objects vs. arrays), so responses are read leniently from the JSON tree.
 */
class XtreamClient(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Logs in; throws [XtreamAuthException] for a wrong login or an account that isn't active. */
    suspend fun account(credentials: XtreamCredentials): XtreamAccount {
        val root = request(credentials.apiUrl()) as? JsonObject
            ?: throw XtreamFormatException(strings.xtreamNotAServer)
        val user = root["user_info"] as? JsonObject ?: throw XtreamFormatException(strings.xtreamNotAServer)
        if (user.int("auth") != 1) throw XtreamAuthException(strings.xtreamAuthFailed)
        val account = XtreamAccount(
            status = user.string("status") ?: "Active",
            expiresAt = user.long("exp_date")?.takeIf { it > 0 }?.times(1000),
            maxConnections = user.int("max_connections"),
            allowedFormats = (user["allowed_output_formats"] as? JsonArray)?.mapNotNull { it.primitiveOrNull() }.orEmpty(),
        )
        if (!account.isActive) throw XtreamAuthException(strings.xtreamAccountNotActive(account.status))
        return account
    }

    suspend fun liveCategories(c: XtreamCredentials) = categories(c, "get_live_categories")
    suspend fun vodCategories(c: XtreamCredentials) = categories(c, "get_vod_categories")
    suspend fun seriesCategories(c: XtreamCredentials) = categories(c, "get_series_categories")

    suspend fun liveStreams(c: XtreamCredentials) = streams(c, "get_live_streams", idKey = "stream_id")
    suspend fun vodStreams(c: XtreamCredentials) = streams(c, "get_vod_streams", idKey = "stream_id")
    suspend fun series(c: XtreamCredentials) = streams(c, "get_series", idKey = "series_id", iconKey = "cover")

    suspend fun seriesInfo(c: XtreamCredentials, seriesId: String): XtreamSeriesInfo {
        val root = request(c.apiUrl("get_series_info", "series_id" to seriesId)) as? JsonObject
            ?: throw XtreamFormatException(strings.xtreamNotAServer)
        val info = root["info"] as? JsonObject
        // {"1": [...], "2": [...]} on most panels, a plain array (of arrays) on some.
        val lists: List<JsonElement> = when (val episodes = root["episodes"]) {
            is JsonObject -> episodes.values.toList()
            is JsonArray -> episodes.toList()
            else -> emptyList()
        }
        val parsed = lists.flatMap { list -> (list as? JsonArray ?: JsonArray(listOf(list))).mapNotNull { it as? JsonObject } }
            .mapNotNull { e ->
                val id = e.string("id") ?: return@mapNotNull null
                val details = e["info"] as? JsonObject
                XtreamEpisode(
                    id = id,
                    season = e.int("season") ?: 1,
                    episode = e.int("episode_num") ?: 0,
                    title = e.string("title").orEmpty(),
                    containerExtension = e.string("container_extension") ?: "mp4",
                    durationSeconds = details?.long("duration_secs"),
                    image = details?.string("movie_image"),
                )
            }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        return XtreamSeriesInfo(info?.string("name"), info?.string("cover"), parsed)
    }

    private suspend fun categories(c: XtreamCredentials, action: String): List<XtreamCategory> =
        list(c.apiUrl(action)).mapNotNull { o ->
            val id = o.string("category_id") ?: return@mapNotNull null
            XtreamCategory(id, o.string("category_name").orEmpty())
        }

    private suspend fun streams(c: XtreamCredentials, action: String, idKey: String, iconKey: String = "stream_icon") =
        list(c.apiUrl(action)).mapNotNull { o ->
            val id = o.string(idKey) ?: return@mapNotNull null
            XtreamStream(
                id = id,
                name = o.string("name")?.trim().orEmpty().ifEmpty { id },
                icon = o.string(iconKey)?.takeIf { it.startsWith("http", ignoreCase = true) },
                categoryId = o.string("category_id"),
                epgChannelId = o.string("epg_channel_id")?.takeIf { it.isNotBlank() },
                containerExtension = o.string("container_extension"),
            )
        }

    /** A JSON array of objects; panels answer an empty list as `[]`, `{}` or nothing at all. */
    private suspend fun list(url: String): List<JsonObject> = when (val e = request(url)) {
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        is JsonObject -> e.values.mapNotNull { it as? JsonObject } // some panels key rows by id
        else -> emptyList()
    }

    private suspend fun request(url: String): JsonElement {
        val response = httpClient.get(url)
        if (response.status.value == 401 || response.status.value == 403) throw XtreamAuthException(strings.xtreamAuthFailed)
        if (!response.status.isSuccess()) throw XtreamFormatException(UserMessages.httpStatus(response.status.value))
        val text = response.bodyAsText().trim()
        if (text.isEmpty() || text == "null") return JsonNull
        return runCatching { json.parseToJsonElement(text) }.getOrElse { throw XtreamFormatException(strings.xtreamNotAServer) }
    }
}

private fun JsonElement.primitiveOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.content

private fun JsonObject.string(key: String): String? = this[key]?.primitiveOrNull()?.takeIf { it.isNotEmpty() }

private fun JsonObject.long(key: String): Long? = string(key)?.toDoubleOrNull()?.toLong()

private fun JsonObject.int(key: String): Int? = long(key)?.toInt()

