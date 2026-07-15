package org.mycelium.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

// Parse-only tests for `--yolo` CLI flag handling — these exercise parseArgs directly
// rather than main(), since main() spins up a real Mycelium/GraalVM Context (or calls
// System.exit) and isn't suited to unit testing.
class AppTest {
    @Test fun yoloFlagBeforeCommand() {
        val parsed = parseArgs(arrayOf("--yolo", "run", "file.mjs"))
        assertTrue(parsed.yolo)
        assertEquals(listOf("run", "file.mjs"), parsed.rest)
    }

    @Test fun yoloFlagAfterRunCommand() {
        val parsed = parseArgs(arrayOf("run", "--yolo", "file.mjs"))
        assertTrue(parsed.yolo)
        assertEquals(listOf("run", "file.mjs"), parsed.rest)
    }

    @Test fun yoloFlagWithImplicitRun() {
        val parsed = parseArgs(arrayOf("--yolo", "file.mjs"))
        assertTrue(parsed.yolo)
        assertEquals(listOf("file.mjs"), parsed.rest)
    }

    @Test fun noYoloFlagDefaultsToFalse() {
        val parsed = parseArgs(arrayOf("run", "file.mjs"))
        assertFalse(parsed.yolo)
        assertEquals(listOf("run", "file.mjs"), parsed.rest)
    }

    @Test fun emptyArgsHasNoYoloAndEmptyRest() {
        val parsed = parseArgs(arrayOf())
        assertFalse(parsed.yolo)
        assertTrue(parsed.rest.isEmpty())
    }
}
