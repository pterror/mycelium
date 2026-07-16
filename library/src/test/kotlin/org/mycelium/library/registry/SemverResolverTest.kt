package org.mycelium.library.registry

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SemverResolverTest {

    private val versions = listOf(
        "0.9.0", "1.0.0", "1.0.1", "1.1.0", "1.2.3", "1.2.9", "1.3.0",
        "1.9.9", "2.0.0", "2.0.1", "2.5.0", "3.0.0", "1.3.0-beta.1",
    )

    @Test fun exactVersion() {
        assertEquals("1.2.3", SemverResolver.resolve("1.2.3", versions))
    }

    @Test fun caretRange() {
        assertEquals("1.9.9", SemverResolver.resolve("^1.2.3", versions))
        assertEquals("0.9.0", SemverResolver.resolve("^0.9.0", listOf("0.9.0", "0.10.0")))
    }

    @Test fun caretZeroMajor() {
        // ^0.2.3 := >=0.2.3 <0.3.0
        assertEquals("0.2.9", SemverResolver.resolve("^0.2.3", listOf("0.2.3", "0.2.9", "0.3.0")))
        // ^0.0.3 := >=0.0.3 <0.0.4
        assertEquals("0.0.3", SemverResolver.resolve("^0.0.3", listOf("0.0.2", "0.0.3", "0.0.4")))
    }

    @Test fun tildeRange() {
        // ~1.2.3 := >=1.2.3 <1.3.0
        assertEquals("1.2.9", SemverResolver.resolve("~1.2.3", versions))
    }

    @Test fun comparators() {
        assertEquals("3.0.0", SemverResolver.resolve(">=1.0.0 <2.0.0 || >=2.5.0", versions))
        assertEquals("1.9.9", SemverResolver.resolve(">=1.0.0 <2.0.0", versions))
    }

    @Test fun xRange() {
        assertEquals("1.9.9", SemverResolver.resolve("1.x", versions))
        assertEquals("3.0.0", SemverResolver.resolve("*", versions))
        assertEquals("1.2.9", SemverResolver.resolve("1.2.x", versions))
    }

    @Test fun hyphenRange() {
        assertEquals("2.0.1", SemverResolver.resolve("1.0.0 - 2.0.1", versions))
        assertEquals("1.9.9", SemverResolver.resolve("1.0.0 - 1.9", versions))
    }

    @Test fun orRanges() {
        assertEquals("2.5.0", SemverResolver.resolve("1.x || 2.5.0", versions))
    }

    @Test fun prereleaseExcludedByDefault() {
        // 1.3.0-beta.1 should not satisfy ^1.2.3 unless explicitly requested via a
        // same-tuple prerelease comparator.
        assertEquals("1.9.9", SemverResolver.resolve("^1.2.3", versions))
        // A prerelease-carrying comparator opts prereleases of the *same tuple* into
        // eligibility, but a same-or-higher release version still outranks them.
        assertEquals("1.3.0", SemverResolver.resolve("^1.3.0-beta.0", listOf("1.3.0-beta.1", "1.3.0")))
        // With no non-prerelease candidate at all, the highest eligible prerelease wins.
        assertEquals("1.3.0-beta.1", SemverResolver.resolve("^1.3.0-beta.0", listOf("1.3.0-beta.0", "1.3.0-beta.1")))
    }

    @Test fun notARangeReturnsNull() {
        assertNull(SemverResolver.parseRange("workspace:*"))
        assertNull(SemverResolver.resolve("next", versions))
    }

    @Test fun distTagLikeSpecDoesNotParseAsRange() {
        assertNull(SemverResolver.parseRange("git+https://example.com/repo.git"))
    }
}
