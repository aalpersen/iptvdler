package io.github.aalpersen.iptvdler.data.playlist

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Stores per-channel HTTP headers as a JSON object in a TEXT column. */
internal object HeaderCodec {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(headers: Map<String, String>): String? =
        if (headers.isEmpty()) null else json.encodeToString(serializer, headers)

    fun decode(text: String?): Map<String, String> =
        if (text.isNullOrEmpty()) emptyMap() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyMap())
}
