package io.github.aalpersen.iptvdler.core.hls

/**
 * RFC 3986 §5.2 reference resolution, in common code (no java.net):
 * resolves segment/key/variant URIs against the playlist URL.
 */
object UrlResolver {

    private class Parts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    private val uriPattern = Regex("""^(?:([a-zA-Z][a-zA-Z0-9+.\-]*):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""")

    private fun parse(uri: String): Parts {
        val m = uriPattern.matchEntire(uri) ?: return Parts(null, null, uri, null, null)
        fun group(i: Int) = m.groups[i]?.value
        return Parts(group(1), group(2), group(3).orEmpty(), group(4), group(5))
    }

    fun resolve(base: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return base.substringBefore('#')
        val r = parse(ref)
        val b = parse(base)

        val scheme: String?
        val authority: String?
        val path: String
        val query: String?
        if (r.scheme != null) {
            scheme = r.scheme
            authority = r.authority
            path = removeDotSegments(r.path)
            query = r.query
        } else {
            scheme = b.scheme
            if (r.authority != null) {
                authority = r.authority
                path = removeDotSegments(r.path)
                query = r.query
            } else {
                authority = b.authority
                if (r.path.isEmpty()) {
                    path = b.path
                    query = r.query ?: b.query
                } else {
                    path = if (r.path.startsWith("/")) removeDotSegments(r.path) else removeDotSegments(merge(b, r.path))
                    query = r.query
                }
            }
        }

        return buildString {
            if (scheme != null) append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (r.fragment != null) append('#').append(r.fragment)
        }
    }

    /** RFC 3986 §5.2.3 */
    private fun merge(base: Parts, refPath: String): String =
        when {
            base.authority != null && base.path.isEmpty() -> "/$refPath"
            '/' in base.path -> base.path.substringBeforeLast('/') + "/" + refPath
            else -> refPath
        }

    /** RFC 3986 §5.2.4, literally. */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()
        fun dropLastOutputSegment() {
            val i = output.lastIndexOf("/")
            output.setLength(if (i < 0) 0 else i)
        }
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = input.substring(3)
                    dropLastOutputSegment()
                }
                input == "/.." -> {
                    input = "/"
                    dropLastOutputSegment()
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val next = input.indexOf('/', startIndex = if (input.startsWith("/")) 1 else 0)
                    if (next < 0) {
                        output.append(input)
                        input = ""
                    } else {
                        output.append(input, 0, next)
                        input = input.substring(next)
                    }
                }
            }
        }
        return output.toString()
    }
}
