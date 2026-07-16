package org.mycelium.library.config

import java.io.File

data class DaemonJvmConfig(
    val maxHeap: String = "2g",
    val extraArgs: List<String> = emptyList(),
)

data class DaemonConfig(
    // Auto-start the daemon on first `mycelium run`/implicit-run invocation when none is
    // already listening. Set false to always run in-process (the pre-daemon behavior).
    val enabled: Boolean = true,
    // Seconds of no requests before the daemon shuts itself down. 0 (or negative) disables
    // the idle timeout entirely.
    val idleTimeout: Long = 3600,
    // Overrides the directory the daemon's socket/pid files live in. Empty/unset falls
    // back to $XDG_RUNTIME_DIR/mycelium, then /tmp/mycelium-$USER — see DaemonPaths.
    val socketDir: String? = null,
    val jvm: DaemonJvmConfig = DaemonJvmConfig(),
)

data class MyceliumConfig(val daemon: DaemonConfig = DaemonConfig())

/**
 * A hand-rolled parser for the small TOML subset Mycelium's config actually needs:
 * `[section]` / `[section.sub]` headers, `key = value` pairs, `#` comments (only
 * recognized outside of strings), double-quoted strings, bare integers, booleans, and
 * single-line arrays of strings (`["a", "b"]`). Not a general TOML parser — multi-line
 * values, inline tables, non-string array elements, and TOML's various numeric/date
 * literals are all out of scope; nothing in config.toml's own schema (see DaemonConfig)
 * needs them.
 */
object TomlParser {
    fun parse(text: String): Map<String, Map<String, Any>> {
        val sections = LinkedHashMap<String, LinkedHashMap<String, Any>>()
        var currentSection = ""
        for (rawLine in text.lines()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim()
                sections.getOrPut(currentSection) { LinkedHashMap() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val rawValue = line.substring(eq + 1).trim()
            sections.getOrPut(currentSection) { LinkedHashMap() }[key] = parseValue(rawValue)
        }
        return sections
    }

    // Strips a trailing `# ...` comment, but only outside of a double-quoted string, so a
    // `#` character inside a config string value (unlikely for this schema, but cheap to
    // get right) doesn't truncate the line early.
    private fun stripComment(line: String): String {
        var inString = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inString = !inString
                '#' -> if (!inString) return line.substring(0, i)
            }
        }
        return line
    }

    private fun parseValue(raw: String): Any = when {
        raw == "true" -> true
        raw == "false" -> false
        raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
        raw.startsWith("[") && raw.endsWith("]") -> {
            val inner = raw.substring(1, raw.length - 1).trim()
            if (inner.isEmpty()) emptyList<String>()
            else inner.split(",").map { it.trim().removeSurrounding("\"") }
        }
        raw.toLongOrNull() != null -> raw.toLong()
        else -> raw
    }
}

/**
 * Loads and merges Mycelium's XDG-style config: the global file
 * (`$XDG_CONFIG_HOME/mycelium/config.toml`, default `~/.config/mycelium/config.toml`) first,
 * then the nearest `.mycelium/config.toml` found walking up from the working directory —
 * same ancestor-walk shape as Mycelium.findPackageJsonDir — with keys from the local file
 * overriding same-named keys from the global one. Either or both files may be absent, in
 * which case the documented defaults (see DaemonConfig) apply.
 */
object Config {
    fun xdgConfigHome(): File =
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".config")

    fun globalConfigFile(): File = File(xdgConfigHome(), "mycelium/config.toml")

    fun localConfigFile(startDir: File = File(".")): File? {
        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            val candidate = File(dir, ".mycelium/config.toml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    fun load(startDir: File = File(".")): MyceliumConfig {
        val merged = LinkedHashMap<String, LinkedHashMap<String, Any>>()
        for (file in listOfNotNull(globalConfigFile().takeIf { it.isFile }, localConfigFile(startDir))) {
            val parsed = try {
                TomlParser.parse(file.readText())
            } catch (e: java.io.IOException) {
                continue
            }
            for ((section, kv) in parsed) {
                merged.getOrPut(section) { LinkedHashMap() }.putAll(kv)
            }
        }
        val daemonSection = merged["daemon"] ?: emptyMap()
        val jvmSection = merged["daemon.jvm"] ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        return MyceliumConfig(
            daemon = DaemonConfig(
                enabled = daemonSection["enabled"] as? Boolean ?: true,
                idleTimeout = (daemonSection["idle_timeout"] as? Long) ?: 3600L,
                socketDir = (daemonSection["socket_dir"] as? String)?.takeIf { it.isNotBlank() },
                jvm = DaemonJvmConfig(
                    maxHeap = (jvmSection["max_heap"] as? String) ?: "2g",
                    extraArgs = (jvmSection["extra_args"] as? List<String>) ?: emptyList(),
                ),
            ),
        )
    }
}
