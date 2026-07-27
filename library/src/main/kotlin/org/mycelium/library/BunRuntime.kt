package org.mycelium.library

import java.io.File

// Bun runtime detection — the first piece of "Bun/Deno Compatibility Strategy" in TODO.md.
// Node-compat globals (process, Buffer, URL, ...) are injected into every JS Context
// unconditionally (see Mycelium.kt's PROCESS_INIT_SCRIPT et al. — this predates any runtime
// detection at all and is left that way here, since disabling it conditionally risks
// regressing every existing Node-compat test). Bun's *additional* globals (the `Bun` object
// itself — see BUN_INIT_SCRIPT in Mycelium.kt) are layered on top, injected only when this
// object's `detect` says the script being run/imported looks like Bun code — never
// unconditionally, and never in a way that removes/hides the Node globals (a Bun script can
// still see `process`/`Buffer`/etc., matching real Bun, which also implements most of
// Node's global surface for compat).
//
// Deno detection/globals are explicitly out of scope here (separate follow-up task per the
// instructions this was implemented under) — this object only ever returns true/false for
// "is this Bun", never attempts to distinguish Deno from "neither".
object BunRuntime {
    // Per TODO.md's "Bun/Deno Compatibility Strategy": Node lockfile/config signals are
    // checked first and win outright — a directory with both a Bun signal and a Node signal
    // is treated as Node (the best-tested path), never Bun. Deno signals are deliberately
    // not checked here at all (out of scope).
    private val NODE_SIGNALS = listOf("package-lock.json", "pnpm-lock.yaml", "yarn.lock")

    // `bun.lockb` is Bun's binary lockfile format — presence is enough of a signal on its
    // own (per the task scope, this deliberately does not parse the binary format).
    private val BUN_SIGNALS = listOf("bun.lockb", "bunfig.toml")

    // Matches a `bun:`-prefixed import/require specifier anywhere in the source — e.g.
    // `import { Database } from "bun:sqlite"`, `import { serve } from "bun:test"`,
    // `require("bun:ffi")`, or the dynamic-import form `import("bun:jsc")`. Deliberately
    // narrow (only the `bun:`-prefixed core-module form, not a bare `from "bun"` import of
    // the `bun` npm shim package some cross-runtime libraries ship) — matches the task's
    // literal description ("a `bun:` import specifier prefix in the source being
    // evaluated"). A bare `"bun"` specifier is a much weaker/ambiguous signal (plenty of
    // packages named similarly), so it's intentionally not treated as a detection signal.
    private val BUN_IMPORT_SPECIFIER_REGEX =
        Regex("""(?:from\s+|require\(\s*|import\(\s*)['"]bun:[^'"]*['"]""")

    fun hasBunImportSpecifier(source: String): Boolean = BUN_IMPORT_SPECIFIER_REGEX.containsMatchIn(source)

    // Walks upward from `startDir` (inclusive), stopping at the first ancestor directory
    // that contains *any* recognized signal (Node or Bun). Node signals are checked before
    // Bun signals at each level, so a directory containing both is decided as Node without
    // even looking further up — matching TODO.md's explicit "a project with both
    // bunfig.toml and pnpm-lock.yaml is Node, not Bun" example. If the closest directory
    // with a signal has only a Bun signal, that's authoritative — this does not keep
    // walking upward looking for a Node signal at some more-distant ancestor (lockfiles are
    // conventionally at the project root closest to the code that uses them; a project
    // nested inside some unrelated ancestor Node checkout is not a realistic case this
    // needs to handle specially).
    fun isBunProject(startDir: File?): Boolean {
        var dir = startDir?.absoluteFile
        while (dir != null) {
            if (NODE_SIGNALS.any { File(dir, it).isFile }) return false
            if (BUN_SIGNALS.any { File(dir, it).isFile }) return true
            dir = dir.parentFile
        }
        return false
    }

    // The single entry point Mycelium.kt calls before running/importing a JS script:
    // true means "inject Bun's globals for this evaluation", false means "leave the
    // Context as plain Node-compat" (the existing, pre-Bun-support default). An explicit
    // `bun:`-prefixed import is checked first since it's an unambiguous signal straight
    // from the source being run, independent of whatever's on disk around it; the
    // lockfile/config-based project check is the fallback for scripts that don't happen to
    // import a `bun:`-prefixed core module directly (e.g. a plain `index.js` that only uses
    // the ambient `Bun` global, never an explicit `bun:` import).
    fun detect(startDir: File?, source: String): Boolean =
        hasBunImportSpecifier(source) || isBunProject(startDir)
}
