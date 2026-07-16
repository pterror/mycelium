package org.mycelium.library.daemon

import java.io.File

/**
 * Resolves where the daemon's Unix domain socket and pid file live: `socket_dir` from
 * config if set, else `$XDG_RUNTIME_DIR/mycelium`, else a per-user fallback under the
 * system temp directory. The spec names the fallback `/tmp/mycelium-$UID`; plain Java has
 * no portable, JNI-free way to read the numeric UID, so `user.name` is used instead — still
 * unique per user and collision-free, just spelled with a name instead of a number.
 */
object DaemonPaths {
    fun runtimeDir(overrideDir: String? = null): File {
        val dir = when {
            !overrideDir.isNullOrBlank() -> File(overrideDir)
            !System.getenv("XDG_RUNTIME_DIR").isNullOrBlank() -> File(System.getenv("XDG_RUNTIME_DIR"), "mycelium")
            else -> File(System.getProperty("java.io.tmpdir", "/tmp"), "mycelium-${System.getProperty("user.name", "unknown")}")
        }
        dir.mkdirs()
        return dir
    }

    fun socketFile(overrideDir: String? = null): File = File(runtimeDir(overrideDir), "daemon.sock")

    fun pidFile(overrideDir: String? = null): File = File(runtimeDir(overrideDir), "daemon.pid")
}
