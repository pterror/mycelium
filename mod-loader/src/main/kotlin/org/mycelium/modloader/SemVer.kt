package org.mycelium.modloader

/**
 * Minimal semver comparison sufficient for `depends`/`breaks` range matching (see
 * [ModDescriptor]) and `targetVersion` compatibility checks. Supports:
 *  - `*`        matches any version
 *  - `>=X.Y.Z`  version >= X.Y.Z
 *  - `<=X.Y.Z`  version <= X.Y.Z
 *  - `X.Y.Z`    exact match
 *
 * Deliberately not a general-purpose semver implementation (no pre-release/build
 * metadata, no `^`/`~` ranges, no comma-separated range lists, no `>`/`<`) — the
 * mod-loader jar has to be self-contained (see [ModDescriptor]'s doc comment on why it
 * hand-rolls its own JSON parser instead of pulling in a dependency), and the spec this
 * was built from only asked for these four forms.
 */
object SemVer {
    /** Parses "X.Y.Z" (or "X.Y" / "X", missing/non-numeric trailing parts treated as 0). */
    private fun parse(version: String): Triple<Int, Int, Int> {
        val parts = version.trim().split(".")
        fun part(i: Int): Int = parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(part(0), part(1), part(2))
    }

    private fun compare(a: String, b: String): Int {
        val (a1, a2, a3) = parse(a)
        val (b1, b2, b3) = parse(b)
        val major = a1.compareTo(b1)
        if (major != 0) return major
        val minor = a2.compareTo(b2)
        if (minor != 0) return minor
        return a3.compareTo(b3)
    }

    /**
     * Returns whether [version] satisfies [range]. See the class doc comment for the
     * supported range syntax. Unrecognized range strings are treated as an exact-match
     * requirement (i.e. same as passing a bare version string).
     */
    fun matches(version: String, range: String): Boolean {
        val trimmed = range.trim()
        return when {
            trimmed == "*" -> true
            trimmed.startsWith(">=") -> compare(version, trimmed.removePrefix(">=").trim()) >= 0
            trimmed.startsWith("<=") -> compare(version, trimmed.removePrefix("<=").trim()) <= 0
            else -> compare(version, trimmed) == 0
        }
    }
}
