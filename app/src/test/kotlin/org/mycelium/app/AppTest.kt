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
        assertFalse(parsed.noInstall)
        assertTrue(parsed.rest.isEmpty())
    }

    @Test fun noInstallFlagBeforeCommand() {
        val parsed = parseArgs(arrayOf("--no-install", "run", "file.mjs"))
        assertTrue(parsed.noInstall)
        assertEquals(listOf("run", "file.mjs"), parsed.rest)
    }

    @Test fun noInstallFlagAfterRunCommand() {
        val parsed = parseArgs(arrayOf("run", "--no-install", "file.mjs"))
        assertTrue(parsed.noInstall)
        assertEquals(listOf("run", "file.mjs"), parsed.rest)
    }

    @Test fun noInstallFlagDefaultsToFalse() {
        val parsed = parseArgs(arrayOf("run", "file.mjs"))
        assertFalse(parsed.noInstall)
    }

    @Test fun yoloAndNoInstallFlagsCanCombine() {
        val parsed = parseArgs(arrayOf("--yolo", "--no-install", "file.mjs"))
        assertTrue(parsed.yolo)
        assertTrue(parsed.noInstall)
        assertEquals(listOf("file.mjs"), parsed.rest)
    }
}
