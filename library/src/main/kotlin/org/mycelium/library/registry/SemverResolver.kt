package org.mycelium.library.registry

// npm-compatible semver parsing + range resolution, used by PackageInstaller and
// CompilerDownloader to turn a package.json-style version spec ("^1.2.3", "~2.3.0",
// ">=1.0.0 <2.0.0", "1.x", "1.0.0 - 2.0.0", "1.x || >=2.5.0", an exact "1.2.3", ...) into
// the single best-matching version out of whatever NpmRegistryClient.fetchVersions(name)
// reports as actually published, mirroring node-semver's `maxSatisfying` closely enough for
// real-world dependency trees (this is deliberately not a byte-for-byte port: some of
// node-semver's more obscure edge cases around mixed operator/x-range combinations are
// simplified, since they're vanishingly rare in real package.json files).
//
// A spec that isn't a semver range at all — a dist-tag like "latest"/"next"/"beta", a git
// URL, a "workspace:*" protocol, etc. — fails to parse (parseRange returns null) rather than
// throwing; callers are expected to fall back to treating the raw spec as an opaque string
// handed straight to the registry (which is exactly how npm dist-tags resolve: GET
// /{name}/{tag} works the same as GET /{name}/{version} on the npm registry).
object SemverResolver {

    // A single parsed semver version. Comparison follows the semver.org spec: numeric core
    // fields compare numerically; a version with no prerelease outranks one with a
    // prerelease that otherwise has an identical [major, minor, patch]; prerelease
    // identifiers compare pairwise (numeric identifiers compare numerically and always sort
    // below alphanumeric ones, per spec) with a shorter identifier list losing ties. Build
    // metadata (the `+...` suffix) never affects comparison or precedence.
    data class Semver(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val prerelease: List<String> = emptyList(),
        val build: String? = null,
        // The exact string this was parsed from — what gets returned to the caller so
        // "resolved version" round-trips whatever the registry actually published (rather
        // than a reformatted/normalized rendering that might not match a real version).
        val raw: String,
    ) : Comparable<Semver> {
        override fun compareTo(other: Semver): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            return comparePrerelease(prerelease, other.prerelease)
        }

        companion object {
            private fun comparePrerelease(a: List<String>, b: List<String>): Int {
                if (a.isEmpty() && b.isEmpty()) return 0
                // No prerelease outranks having one (1.0.0 > 1.0.0-rc.1).
                if (a.isEmpty()) return 1
                if (b.isEmpty()) return -1
                val len = minOf(a.size, b.size)
                for (i in 0 until len) {
                    val cmp = compareIdentifier(a[i], b[i])
                    if (cmp != 0) return cmp
                }
                return a.size.compareTo(b.size)
            }

            private fun compareIdentifier(a: String, b: String): Int {
                val aNum = a.toLongOrNull()
                val bNum = b.toLongOrNull()
                return when {
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    aNum != null -> -1 // numeric identifiers always sort lower
                    bNum != null -> 1
                    else -> a.compareTo(b)
                }
            }
        }
    }

    private val fullVersionRegex = Regex(
        "^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$"
    )

    // Parses a fully-specified version string ("1.2.3", "1.2.3-beta.1", "v1.2.3+build5").
    // Returns null for anything partial (missing minor/patch) or otherwise not a plain
    // version — those are the caller's job to handle as a range (parseRange) instead.
    fun parseVersion(version: String): Semver? {
        val m = fullVersionRegex.matchEntire(version.trim()) ?: return null
        val (majorS, minorS, patchS, pre, build) = m.destructured
        return Semver(
            major = majorS.toLong(),
            minor = minorS.toLong(),
            patch = patchS.toLong(),
            prerelease = if (pre.isEmpty()) emptyList() else pre.split('.'),
            build = build.ifEmpty { null },
            raw = version.trim(),
        )
    }

    // A version tuple that may have missing (x-range/wildcard) trailing components, e.g.
    // "1.2.x" -> (1, 2, null), "1" -> (1, null, null), "*"/""/"x" -> (null, null, null).
    private data class Partial(
        val major: Long?,
        val minor: Long?,
        val patch: Long?,
        val prerelease: List<String>,
    )

    private val wildcard = Regex("[xX*]")
    private val partialRegex = Regex(
        "^v?(\\d+|[xX*])?(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
    )

    private fun parsePartial(spec: String): Partial? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty() || wildcard.matches(trimmed)) return Partial(null, null, null, emptyList())
        val m = partialRegex.matchEntire(trimmed) ?: return null
        val (majorG, minorG, patchG, pre) = m.destructured
        fun component(s: String): Long? = if (s.isEmpty() || wildcard.matches(s)) null else s.toLong()
        val major = component(majorG)
        val minor = component(minorG)
        val patch = component(patchG)
        // A missing major with a present minor/patch ("x.2.3") isn't a sensible partial;
        // reject it rather than silently treating it as "any version".
        if (major == null && (minor != null || patch != null)) return null
        if (minor == null && patch != null) return null
        return Partial(
            major = major,
            minor = minor,
            patch = patch,
            prerelease = if (pre.isEmpty()) emptyList() else pre.split('.'),
        )
    }

    enum class Op { LT, LTE, GT, GTE, EQ, NEQ }

    data class RangeComparator(val op: Op, val version: Semver) {
        fun satisfies(v: Semver): Boolean {
            val cmp = v.compareTo(version)
            return when (op) {
                Op.LT -> cmp < 0
                Op.LTE -> cmp <= 0
                Op.GT -> cmp > 0
                Op.GTE -> cmp >= 0
                Op.EQ -> cmp == 0
                Op.NEQ -> cmp != 0
            }
        }
    }

    // A fully-parsed range: an OR of AND-clauses (node-semver's own internal shape), e.g.
    // "1.x || >=2.5.0 <3.0.0" -> [[>=1.0.0, <2.0.0], [>=2.5.0, <3.0.0]]. An empty AND-clause
    // means "matches any version" (the "*"/""/"x" range).
    class Range(private val orClauses: List<List<RangeComparator>>) {
        fun satisfies(v: Semver, includePrerelease: Boolean = false): Boolean {
            if (v.prerelease.isNotEmpty() && !includePrerelease && !allowsPrerelease(v)) return false
            return orClauses.any { clause -> clause.all { it.satisfies(v) } }
        }

        // npm rule: a prerelease version only satisfies a range if some comparator
        // *anywhere* in the range shares that version's [major, minor, patch] tuple and is
        // itself a prerelease of that same tuple — i.e. prereleases are opt-in per-tuple,
        // not globally comparable to release versions.
        private fun allowsPrerelease(v: Semver): Boolean =
            orClauses.any { clause ->
                clause.any { comp ->
                    comp.version.prerelease.isNotEmpty() &&
                        comp.version.major == v.major &&
                        comp.version.minor == v.minor &&
                        comp.version.patch == v.patch
                }
            }

        fun satisfies(version: String, includePrerelease: Boolean = false): Boolean {
            val v = parseVersion(version) ?: return false
            return satisfies(v, includePrerelease)
        }

        // The highest published version (by semver precedence) that satisfies this range,
        // or null if none of `versions` do (including: none of them parse as semver at
        // all — malformed/garbage version strings are silently skipped rather than
        // failing the whole resolution).
        fun maxSatisfying(versions: Collection<String>, includePrerelease: Boolean = false): String? =
            versions
                .mapNotNull { raw -> parseVersion(raw)?.let { raw to it } }
                .filter { (_, v) -> satisfies(v, includePrerelease) }
                .maxByOrNull { (_, v) -> v }
                ?.first
    }

    private fun floorOf(p: Partial): Semver =
        Semver(p.major ?: 0, p.minor ?: 0, p.patch ?: 0, p.prerelease, raw = "")

    // Expands a bare x-range/partial version (no ^/~/comparator prefix) into >= floor
    // < ceiling, per npm's "Partial Version Ranges" table: "1.2.x"/"1.2" := >=1.2.0 <1.3.0,
    // "1.x"/"1" := >=1.0.0 <2.0.0, "*"/""/"x" := any.
    private fun expandXRange(p: Partial): List<RangeComparator> {
        val major = p.major ?: return emptyList() // "*" - matches anything
        if (p.minor == null) {
            return listOf(
                RangeComparator(Op.GTE, Semver(major, 0, 0, raw = "")),
                RangeComparator(Op.LT, Semver(major + 1, 0, 0, raw = "")),
            )
        }
        if (p.patch == null) {
            return listOf(
                RangeComparator(Op.GTE, Semver(major, p.minor, 0, raw = "")),
                RangeComparator(Op.LT, Semver(major, p.minor + 1, 0, raw = "")),
            )
        }
        // Fully specified, no wildcard at all: exact match (with prerelease, if given).
        return listOf(RangeComparator(Op.EQ, Semver(major, p.minor, p.patch, p.prerelease, raw = "")))
    }

    // ^1.2.3 := >=1.2.3 <2.0.0; ^0.2.3 := >=0.2.3 <0.3.0; ^0.0.3 := >=0.0.3 <0.0.4;
    // ^1.2.x := >=1.2.0 <2.0.0; ^0.0.x := >=0.0.0 <0.1.0; ^1.x := >=1.0.0 <2.0.0;
    // ^0.x := >=0.0.0 <1.0.0. The unifying rule (matches node-semver): bump the
    // left-most non-zero of [major, minor, patch] (falling back to the position right
    // after the last specified one when everything specified so far is zero).
    private fun caretCeiling(p: Partial): Semver {
        val major = p.major ?: 0
        if (major > 0) return Semver(major + 1, 0, 0, raw = "")
        val minor = p.minor
        if (minor != null && minor > 0) return Semver(0, minor + 1, 0, raw = "")
        if (minor == null) return Semver(1, 0, 0, raw = "") // ^0.x / ^0
        // minor == 0
        val patch = p.patch
        if (patch != null) return Semver(0, 0, patch + 1, raw = "")
        return Semver(0, 1, 0, raw = "") // ^0.0.x / ^0.0
    }

    private fun caretRange(p: Partial): List<RangeComparator> =
        listOf(
            RangeComparator(Op.GTE, floorOf(p)),
            RangeComparator(Op.LT, caretCeiling(p)),
        )

    // ~1.2.3 := >=1.2.3 <1.3.0; ~1.2 := >=1.2.0 <1.3.0; ~1 := >=1.0.0 <2.0.0;
    // ~0.2.3 := >=0.2.3 <0.3.0.
    private fun tildeRange(p: Partial): List<RangeComparator> {
        val major = p.major ?: 0
        val ceiling = if (p.minor != null) Semver(major, p.minor + 1, 0, raw = "") else Semver(major + 1, 0, 0, raw = "")
        return listOf(RangeComparator(Op.GTE, floorOf(p)), RangeComparator(Op.LT, ceiling))
    }

    private val comparatorRegex = Regex("^(<=|>=|<|>|=|!=)?(.*)$")

    // Parses one whitespace-delimited token of a single AND-clause (e.g. "^1.2.3", "~2.3",
    // ">=1.0.0", "1.x", "1.2.3") into the RangeComparator(s) it expands to. Returns null if
    // the token isn't any recognized shape (signals "this whole spec isn't a semver range"
    // to the caller, who then treats it as a dist-tag/opaque string instead).
    private fun parseToken(token: String): List<RangeComparator>? {
        if (token.isEmpty()) return emptyList()
        if (token[0] == '^') return parsePartial(token.substring(1))?.let { caretRange(it) }
        if (token[0] == '~') return parsePartial(token.substring(1))?.let { tildeRange(it) }
        val m = comparatorRegex.matchEntire(token) ?: return null
        val (opStr, verStr) = m.destructured
        val partial = parsePartial(verStr) ?: return null
        return when (opStr) {
            "" -> expandXRange(partial)
            ">=" -> listOf(RangeComparator(Op.GTE, floorOf(partial)))
            "<" -> listOf(RangeComparator(Op.LT, floorOf(partial)))
            "=" -> expandXRange(partial)
            "!=" -> {
                if (partial.major != null && partial.minor != null && partial.patch != null) {
                    listOf(RangeComparator(Op.NEQ, floorOf(partial)))
                } else {
                    // Negating a wildcard range ("!=1.2.x") isn't expressible as a single
                    // AND-able comparator; approximate by excluding the floor version only,
                    // which is the common real-world case (excluding one exact release).
                    listOf(RangeComparator(Op.NEQ, floorOf(partial)))
                }
            }
            ">" -> {
                // ">1.2" (partial) must exceed *every* 1.2.x, i.e. behaves like >=1.3.0;
                // ">1.2.3" (full) is a plain strict-greater-than.
                if (partial.patch == null) {
                    val bumped = if (partial.minor == null) {
                        Semver((partial.major ?: 0) + 1, 0, 0, raw = "")
                    } else {
                        Semver(partial.major ?: 0, partial.minor + 1, 0, raw = "")
                    }
                    listOf(RangeComparator(Op.GTE, bumped))
                } else {
                    listOf(RangeComparator(Op.GT, floorOf(partial)))
                }
            }
            "<=" -> {
                // "<=1.2" (partial) allows all of 1.2.x, i.e. behaves like <1.3.0;
                // "<=1.2.3" (full) is a plain less-than-or-equal.
                if (partial.patch == null) {
                    val bumped = if (partial.minor == null) {
                        Semver((partial.major ?: 0) + 1, 0, 0, raw = "")
                    } else {
                        Semver(partial.major ?: 0, partial.minor + 1, 0, raw = "")
                    }
                    listOf(RangeComparator(Op.LT, bumped))
                } else {
                    listOf(RangeComparator(Op.LTE, floorOf(partial)))
                }
            }
            else -> null
        }
    }

    private val hyphenRegex = Regex("^(\\S+)\\s+-\\s+(\\S+)$")

    // "1.0.0 - 2.0.0" := >=1.0.0 <=2.0.0; a partial lower bound fills with zeros, a partial
    // upper bound expands to "less than the next unit" (e.g. "1.0.0 - 2.0" := >=1.0.0
    // <2.1.0), matching node-semver's hyphenReplace.
    private fun parseHyphenRange(clause: String): List<RangeComparator>? {
        val m = hyphenRegex.matchEntire(clause.trim()) ?: return null
        val (fromS, toS) = m.destructured
        val from = parsePartial(fromS) ?: return null
        val to = parsePartial(toS) ?: return null
        val lower = RangeComparator(Op.GTE, floorOf(from))
        val upper = if (to.patch != null) {
            RangeComparator(Op.LTE, Semver(to.major ?: 0, to.minor ?: 0, to.patch, to.prerelease, raw = ""))
        } else if (to.minor != null) {
            RangeComparator(Op.LT, Semver(to.major ?: 0, to.minor + 1, 0, raw = ""))
        } else if (to.major != null) {
            RangeComparator(Op.LT, Semver(to.major + 1, 0, 0, raw = ""))
        } else {
            null // upper bound is "*" - no upper bound at all
        }
        return if (upper == null) listOf(lower) else listOf(lower, upper)
    }

    private val whitespace = Regex("\\s+")

    // Parses one AND-clause (no "||" inside it) into its list of comparators. Returns null
    // if any token in it fails to parse.
    private fun parseAndClause(clause: String): List<RangeComparator>? {
        val trimmed = clause.trim()
        if (trimmed.isEmpty()) return emptyList()
        parseHyphenRange(trimmed)?.let { return it }
        val tokens = trimmed.split(whitespace).filter { it.isNotEmpty() }
        val comparators = mutableListOf<RangeComparator>()
        for (token in tokens) {
            val parsed = parseToken(token) ?: return null
            comparators.addAll(parsed)
        }
        return comparators
    }

    // Parses a full range spec, e.g. "1.x || >=2.5.0 <3.0.0". Returns null if `spec` isn't a
    // semver range at all (a dist-tag like "latest"/"next", a git URL, "workspace:*", etc.)
    // — the caller's cue to fall back to treating it as an opaque string handed straight to
    // the registry instead.
    fun parseRange(spec: String): Range? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return Range(listOf(emptyList()))
        val orParts = trimmed.split("||")
        val clauses = orParts.map { parseAndClause(it) ?: return null }
        return Range(clauses)
    }

    // Convenience one-shot: parse `rangeSpec` as a semver range and return the highest of
    // `versions` that satisfies it, or null if `rangeSpec` isn't a parseable range at all
    // (dist-tag/opaque) or none of `versions` satisfy it.
    fun resolve(rangeSpec: String, versions: Collection<String>, includePrerelease: Boolean = false): String? =
        parseRange(rangeSpec)?.maxSatisfying(versions, includePrerelease)
}
