package io.github.aalpersen.iptvdler.core.update

/**
 * A release version like `1.2.0`, `v1.2.0` or `1.2.0-beta1`. Numeric parts are compared one by one
 * (missing parts count as 0); a version with a pre-release suffix is older than the same version without one.
 */
data class AppVersion(val numbers: List<Int>, val preRelease: String?) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val diff = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?$""")

        /** null if [text] isn't a version this app understands. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers, match.groupValues[2].ifEmpty { null })
        }

        /** True only when both parse and [candidate] is strictly newer than [current]. */
        fun isNewer(candidate: String, current: String): Boolean {
            val c = parse(candidate) ?: return false
            val cur = parse(current) ?: return false
            return c > cur
        }
    }
}
