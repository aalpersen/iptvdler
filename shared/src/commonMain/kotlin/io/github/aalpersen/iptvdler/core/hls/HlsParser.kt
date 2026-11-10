package io.github.aalpersen.iptvdler.core.hls

/**
 * Parses HLS master and media playlists (RFC 8216). Unknown tags are ignored; relative URIs are
 * resolved against [baseUri] (the playlist URL after redirects).
 */
object HlsParser {

    private val masterOnlyTags = listOf("#EXT-X-STREAM-INF", "#EXT-X-MEDIA:", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-SESSION-KEY")
    private val mediaOnlyTags = listOf("#EXTINF", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-ENDLIST")

    fun parse(text: String, baseUri: String): HlsPlaylist {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) {
            throw HlsParseException("Not an HLS playlist (missing #EXTM3U)")
        }
        val isMaster = lines.any { line -> masterOnlyTags.any { line.startsWith(it) } }
        val isMedia = lines.any { line -> mediaOnlyTags.any { line.startsWith(it) } }
        return when {
            isMaster -> parseMaster(lines, baseUri)
            isMedia -> parseMedia(lines, baseUri)
            else -> throw HlsParseException("Playlist has neither variants nor segments")
        }
    }

    fun parseMaster(text: String, baseUri: String): MasterPlaylist =
        parse(text, baseUri) as? MasterPlaylist ?: throw HlsParseException("Expected a master playlist")

    fun parseMedia(text: String, baseUri: String): MediaPlaylist =
        parse(text, baseUri) as? MediaPlaylist ?: throw HlsParseException("Expected a media playlist")

    private fun parseMaster(lines: List<String>, baseUri: String): MasterPlaylist {
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        val sessionKeys = ArrayList<HlsKey>()
        var pendingStreamInf: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingStreamInf = parseAttributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> parseRendition(parseAttributes(line.substringAfter(':')), baseUri)?.let(renditions::add)
                line.startsWith("#EXT-X-SESSION-KEY:") -> sessionKeys += parseKey(parseAttributes(line.substringAfter(':')), baseUri)
                line.startsWith("#") -> Unit
                else -> {
                    val attrs = pendingStreamInf ?: continue
                    pendingStreamInf = null
                    variants += Variant(
                        uri = UrlResolver.resolve(baseUri, line),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        resolution = attrs["RESOLUTION"]?.let(::parseResolution),
                        codecs = attrs["CODECS"],
                        frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                        audioGroup = attrs["AUDIO"],
                        videoGroup = attrs["VIDEO"],
                        subtitlesGroup = attrs["SUBTITLES"],
                        closedCaptionsGroup = attrs["CLOSED-CAPTIONS"]?.takeUnless { it == "NONE" },
                    )
                }
            }
        }
        return MasterPlaylist(baseUri, variants, renditions, sessionKeys)
    }

    private fun parseRendition(attrs: Map<String, String>, baseUri: String): Rendition? {
        val type = when (attrs["TYPE"]) {
            "AUDIO" -> RenditionType.AUDIO
            "VIDEO" -> RenditionType.VIDEO
            "SUBTITLES" -> RenditionType.SUBTITLES
            "CLOSED-CAPTIONS" -> RenditionType.CLOSED_CAPTIONS
            else -> return null
        }
        val groupId = attrs["GROUP-ID"] ?: return null
        return Rendition(
            type = type,
            groupId = groupId,
            name = attrs["NAME"] ?: attrs["LANGUAGE"] ?: groupId,
            language = attrs["LANGUAGE"],
            uri = attrs["URI"]?.let { UrlResolver.resolve(baseUri, it) },
            isDefault = attrs["DEFAULT"] == "YES",
            autoSelect = attrs["AUTOSELECT"] == "YES",
            forced = attrs["FORCED"] == "YES",
            instreamId = attrs["INSTREAM-ID"],
            channels = attrs["CHANNELS"],
            characteristics = attrs["CHARACTERISTICS"],
        )
    }

    private fun parseMedia(lines: List<String>, baseUri: String): MediaPlaylist {
        var targetDuration = 0.0
        var mediaSequence = 0L
        var discontinuitySequence = 0L
        var playlistType: PlaylistType? = null
        var endList = false
        var version: Int? = null
        var iFramesOnly = false

        val segments = ArrayList<Segment>()
        var keys: List<HlsKey> = emptyList()
        var initSegment: InitSegment? = null

        // Pending state for the next URI line.
        var duration: Double? = null
        var title: String? = null
        var byteRangeSpec: Pair<Long, Long?>? = null
        var discontinuity = false
        var programDateTime: String? = null
        var gap = false

        // For BYTERANGE without an offset: the next byte after the previous sub-range of the same resource.
        var lastRangeUri: String? = null
        var lastRangeEnd = 0L
        var currentDiscontinuitySequence: Long? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetDuration = line.substringAfter(':').toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") ->
                    discontinuitySequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> playlistType = when (line.substringAfter(':').trim()) {
                    "VOD" -> PlaylistType.VOD
                    "EVENT" -> PlaylistType.EVENT
                    else -> null
                }
                line.startsWith("#EXT-X-VERSION:") -> version = line.substringAfter(':').trim().toIntOrNull()
                line == "#EXT-X-I-FRAMES-ONLY" -> iFramesOnly = true
                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#EXTINF:") -> {
                    val value = line.substringAfter(':')
                    duration = value.substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    title = value.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> byteRangeSpec = parseByteRangeSpec(line.substringAfter(':'))
                line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> programDateTime = line.substringAfter(':').trim()
                line == "#EXT-X-GAP" -> gap = true
                line.startsWith("#EXT-X-KEY:") -> {
                    val key = parseKey(parseAttributes(line.substringAfter(':')), baseUri)
                    keys = if (key.method == KeyMethod.NONE) {
                        emptyList()
                    } else {
                        // A new key replaces the previous key of the same KEYFORMAT; other formats stay.
                        keys.filterNot { it.keyFormat.equals(key.keyFormat, ignoreCase = true) } + key
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val uri = attrs["URI"] ?: throw HlsParseException("#EXT-X-MAP without URI")
                    initSegment = InitSegment(
                        uri = UrlResolver.resolve(baseUri, uri),
                        byteRange = attrs["BYTERANGE"]?.let { spec ->
                            val (length, offset) = parseByteRangeSpec(spec)
                            ByteRange(length, offset ?: 0)
                        },
                        keys = keys,
                    )
                }
                line.startsWith("#") -> Unit
                else -> {
                    val uri = UrlResolver.resolve(baseUri, line)
                    if (discontinuity) {
                        currentDiscontinuitySequence = (currentDiscontinuitySequence ?: discontinuitySequence) + 1
                    }
                    val byteRange = byteRangeSpec?.let { (length, offset) ->
                        val start = offset ?: if (uri == lastRangeUri) lastRangeEnd else {
                            throw HlsParseException("#EXT-X-BYTERANGE without offset must follow a range of the same resource")
                        }
                        ByteRange(length, start).also {
                            lastRangeUri = uri
                            lastRangeEnd = it.endExclusive
                        }
                    }
                    segments += Segment(
                        uri = uri,
                        durationSeconds = duration ?: 0.0,
                        mediaSequence = mediaSequence + segments.size,
                        title = title,
                        byteRange = byteRange,
                        keys = keys,
                        initSegment = initSegment,
                        discontinuity = discontinuity,
                        discontinuitySequence = currentDiscontinuitySequence ?: discontinuitySequence,
                        programDateTime = programDateTime,
                        gap = gap,
                    )
                    duration = null
                    title = null
                    byteRangeSpec = null
                    discontinuity = false
                    programDateTime = null
                    gap = false
                }
            }
        }

        return MediaPlaylist(
            uri = baseUri,
            targetDurationSeconds = targetDuration,
            mediaSequence = mediaSequence,
            discontinuitySequence = discontinuitySequence,
            playlistType = playlistType,
            hasEndList = endList,
            segments = segments,
            version = version,
            iFramesOnly = iFramesOnly,
        )
    }

    private fun parseKey(attrs: Map<String, String>, baseUri: String): HlsKey {
        val rawMethod = attrs["METHOD"] ?: "NONE"
        val method = when (rawMethod) {
            "NONE" -> KeyMethod.NONE
            "AES-128" -> KeyMethod.AES_128
            "SAMPLE-AES" -> KeyMethod.SAMPLE_AES
            "SAMPLE-AES-CTR" -> KeyMethod.SAMPLE_AES_CTR
            else -> KeyMethod.UNKNOWN
        }
        val iv = attrs["IV"]?.let { raw ->
            val hex = raw.removePrefix("0x").removePrefix("0X")
            if (hex.isEmpty() || hex.length > 32 || !hex.all { it.isHexDigit() }) throw HlsParseException("Invalid IV: $raw")
            hex.padStart(32, '0').lowercase()
        }
        return HlsKey(
            method = method,
            uri = attrs["URI"]?.let { uri -> if (uri.startsWith("data:") || uri.startsWith("skd:")) uri else UrlResolver.resolve(baseUri, uri) },
            ivHex = iv,
            keyFormat = attrs["KEYFORMAT"] ?: HlsKey.IDENTITY_KEY_FORMAT,
            keyFormatVersions = attrs["KEYFORMATVERSIONS"],
            rawMethod = rawMethod,
        )
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun parseResolution(value: String): Resolution? {
        val (w, h) = value.lowercase().split('x').takeIf { it.size == 2 } ?: return null
        return Resolution(w.trim().toIntOrNull() ?: return null, h.trim().toIntOrNull() ?: return null)
    }

    /** `<length>[@<offset>]` */
    private fun parseByteRangeSpec(spec: String): Pair<Long, Long?> {
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: throw HlsParseException("Invalid byte range: $spec")
        val offset = if ('@' in spec) {
            spec.substringAfter('@').trim().toLongOrNull() ?: throw HlsParseException("Invalid byte range: $spec")
        } else {
            null
        }
        return length to offset
    }

    /** RFC 8216 §4.2 attribute list: `KEY=value,KEY="quoted, value",KEY=0x1A`. Quotes are stripped. */
    internal fun parseAttributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i] == ',' || text[i].isWhitespace())) i++
            val keyStart = i
            while (i < n && text[i] != '=' && text[i] != ',') i++
            val key = text.substring(keyStart, i).trim()
            if (i >= n || text[i] != '=') continue
            i++ // '='
            val value: String
            if (i < n && text[i] == '"') {
                val close = text.indexOf('"', startIndex = i + 1).let { if (it < 0) n else it }
                value = text.substring(i + 1, close)
                i = close + 1
            } else {
                val end = text.indexOf(',', startIndex = i).let { if (it < 0) n else it }
                value = text.substring(i, end).trim()
                i = end
            }
            if (key.isNotEmpty()) result[key.uppercase()] = value
        }
        return result
    }
}
