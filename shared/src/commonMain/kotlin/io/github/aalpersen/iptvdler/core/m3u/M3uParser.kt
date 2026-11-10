package io.github.aalpersen.iptvdler.core.m3u

/**
 * Streaming, push-style parser for IPTV M3U/M3U8 channel lists.
 *
 * Feed lines one at a time with [feedLine]; it returns an [M3uEntry] whenever a stream URL line
 * completes an item. Nothing but the pending item is kept in memory, so arbitrarily large
 * playlists can be read straight from a network or file stream.
 *
 * Tolerates a UTF-8 BOM, CRLF, blank lines, missing/unquoted/single-quoted attributes, commas
 * inside quoted values, orphan `#EXTINF` lines and URLs without any `#EXTINF`.
 */
class M3uParser {

    /** The `#EXTM3U` header, or null if the input had none (yet). */
    var header: M3uHeader? = null
        private set

    /**
     * False when the input had neither an `#EXTM3U` header nor any `#EXTINF` line,
     * e.g. an HTML error page served instead of a playlist.
     */
    val looksLikeM3u: Boolean get() = header != null || sawExtInf

    private var sawExtInf = false
    private var firstLine = true
    private var pendingInfo: ExtInf? = null
    private var pendingGroup: String? = null
    private val pendingHeaders = LinkedHashMap<String, String>()
    private val pendingKodiProps = LinkedHashMap<String, String>()

    fun feedLine(rawLine: String): M3uEntry? {
        var line = rawLine
        if (firstLine) {
            firstLine = false
            line = line.removePrefix(BOM)
        }
        line = line.trim()
        if (line.isEmpty()) return null
        if (line[0] == '#') {
            onDirective(line)
            return null
        }
        return onUrl(line)
    }

    private fun onDirective(line: String) {
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                if (header == null) header = M3uHeader(scanAttributes(line, "#EXTM3U".length).attributes)
            }
            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                // An #EXTINF without a URL is dropped together with its options.
                if (pendingInfo != null) resetPending()
                pendingInfo = parseExtInf(line.substring("#EXTINF:".length))
                sawExtInf = true
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                pendingGroup = line.substring("#EXTGRP:".length).trim().ifEmpty { null }
            }
            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                val option = line.substring("#EXTVLCOPT:".length)
                val eq = option.indexOf('=')
                if (eq > 0) {
                    val name = StreamHeaders.fromVlcOption(option.substring(0, eq))
                    val value = option.substring(eq + 1).trim()
                    if (name != null && value.isNotEmpty()) pendingHeaders[name] = value
                }
            }
            line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                val prop = line.substring("#KODIPROP:".length)
                val eq = prop.indexOf('=')
                if (eq > 0) {
                    val key = prop.substring(0, eq).trim().lowercase()
                    val value = prop.substring(eq + 1).trim()
                    pendingKodiProps[key] = value
                    if (key.endsWith(".stream_headers") || key.endsWith(".common_headers")) {
                        pendingHeaders.putAll(StreamHeaders.parseHeaderString(value))
                    }
                }
            }
        }
    }

    private fun onUrl(line: String): M3uEntry {
        val pipe = line.indexOf('|')
        val url = if (pipe > 0) line.substring(0, pipe).trim() else line
        val headers = LinkedHashMap(pendingHeaders)
        if (pipe > 0) headers.putAll(StreamHeaders.parseHeaderString(line.substring(pipe + 1)))

        val info = pendingInfo
        val attributes = info?.attributes ?: emptyMap()
        val title = info?.title?.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: titleFromUrl(url)
        val group = attributes["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup

        val entry = M3uEntry(
            title = title,
            url = url,
            durationSeconds = info?.duration,
            attributes = attributes,
            group = group,
            headers = headers,
            kodiProps = LinkedHashMap(pendingKodiProps),
        )
        resetPending()
        return entry
    }

    private fun resetPending() {
        pendingInfo = null
        pendingGroup = null
        pendingHeaders.clear()
        pendingKodiProps.clear()
    }

    private fun titleFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }

    private class ExtInf(val duration: Double?, val attributes: Map<String, String>, val title: String)

    private fun parseExtInf(body: String): ExtInf {
        var i = 0
        while (i < body.length && !body[i].isWhitespace() && body[i] != ',') i++
        val duration = body.substring(0, i).trim().toDoubleOrNull()
        val scan = scanAttributes(body, i)
        val title = if (scan.titleStart >= 0) body.substring(scan.titleStart).trim() else ""
        return ExtInf(duration, scan.attributes, title)
    }

    private class AttributeScan(val attributes: Map<String, String>, val titleStart: Int)

    /**
     * Reads `key="value"` / `key='value'` / `key=value` pairs from [start] until the first
     * comma outside quotes. [AttributeScan.titleStart] is the index after that comma, or -1.
     */
    private fun scanAttributes(text: String, start: Int): AttributeScan {
        val attributes = LinkedHashMap<String, String>()
        val n = text.length
        var i = start
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == ',' -> return AttributeScan(attributes, i + 1)
                else -> {
                    val keyStart = i
                    while (i < n && text[i] != '=' && text[i] != ',' && !text[i].isWhitespace()) i++
                    val key = text.substring(keyStart, i).lowercase()
                    if (i < n && text[i] == '=') {
                        i++
                        val value: String
                        if (i < n && (text[i] == '"' || text[i] == '\'')) {
                            val close = text.indexOf(text[i], startIndex = i + 1)
                            if (close >= 0) {
                                value = text.substring(i + 1, close)
                                i = close + 1
                            } else {
                                // Unterminated quote: the value runs to the next comma.
                                val end = text.indexOf(',', startIndex = i + 1).let { if (it < 0) n else it }
                                value = text.substring(i + 1, end)
                                i = end
                            }
                        } else {
                            val valueStart = i
                            while (i < n && text[i] != ',' && !text[i].isWhitespace()) i++
                            value = text.substring(valueStart, i)
                        }
                        if (key.isNotEmpty()) attributes[key] = value.trim()
                    }
                }
            }
        }
        return AttributeScan(attributes, -1)
    }

    private companion object {
        const val BOM = "﻿"
    }
}
