package org.mycelium.library.daemon

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/**
 * Talks to a running DaemonServer over its Unix domain socket. Every method here connects
 * fresh, sends exactly one request, and closes the connection once the daemon's response is
 * fully read — matching DaemonServer.handleClient's one-request-per-connection protocol.
 */
object DaemonClient {
    // Sent as a top-level "version" field on every request frame so the daemon can detect a
    // client/daemon mismatch (e.g. the CLI was upgraded but an old daemon is still running
    // from before the upgrade) — see DaemonServer.handleClient for the check. Matches the
    // string App.kt's `version` command prints; kept as a constant (not derived at runtime)
    // so client and server always compare the same literal.
    const val VERSION = "0.1.0-dev"

    private fun connect(socketPath: File): SocketChannel =
        SocketChannel.open(UnixDomainSocketAddress.of(socketPath.toPath()))

    /** True if a daemon is actually listening (not just that the socket file exists — a
     * stale file left behind by a crashed daemon exists but refuses connections). */
    fun isRunning(socketPath: File): Boolean {
        if (!socketPath.exists()) return false
        return try {
            connect(socketPath).close()
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Runs `file` via the daemon, writing `stdout`/`stderr` response frames to the given
     * streams as they arrive. Returns the exit code the daemon reports, or 1 with a message
     * on `stderr` if the connection drops before an `exit` frame arrives, or null if the
     * daemon rejected the request over a version mismatch or announced it's shutting down —
     * in both of those cases the caller (see App.kt's `tryRunViaDaemon`) should fall back to
     * running in-process. Throws IOException if the daemon can't be reached at all, so the
     * caller can decide whether to fall back to running in-process.
     *
     * `cwd`/`env` default to this (client) process's own working directory/environment —
     * i.e. the caller's, not the daemon's — so a plain call here already makes the request
     * transparent to whichever directory/shell the CLI was invoked from; callers only need
     * to override them explicitly (e.g. tests) when they want something else. */
    fun runFile(
        socketPath: File,
        file: String,
        args: List<String> = emptyList(),
        cwd: String? = System.getProperty("user.dir"),
        env: Map<String, String> = System.getenv(),
        yolo: Boolean = false,
        noInstall: Boolean = false,
        operatorOverloading: Boolean = false,
        stdout: OutputStream = System.out,
        stderr: OutputStream = System.err,
    ): Int? {
        connect(socketPath).use { channel ->
            val input = Channels.newInputStream(channel)
            val output = Channels.newOutputStream(channel)
            val requestJson = DaemonRequest.Run(file, args, cwd, env, yolo, noInstall, operatorOverloading).toJson()
                .put("version", VERSION)
            FrameIO.writeFrame(output, requestJson)
            while (true) {
                val frame = FrameIO.readFrame(input) ?: run {
                    stderr.write("mycelium: daemon closed connection unexpectedly\n".toByteArray(Charsets.UTF_8))
                    return 1
                }
                when (frame.optString("type")) {
                    "stdout" -> {
                        stdout.write(frame.getString("data").toByteArray(Charsets.UTF_8))
                        stdout.flush()
                    }
                    "stderr" -> {
                        stderr.write(frame.getString("data").toByteArray(Charsets.UTF_8))
                        stderr.flush()
                    }
                    "exit" -> return frame.optInt("code", 0)
                    "error" -> {
                        stderr.write(
                            "mycelium: ${frame.optString("message", "daemon reported an error")}\n"
                                .toByteArray(Charsets.UTF_8),
                        )
                        return null
                    }
                    "shutdown" -> {
                        System.err.println(
                            "mycelium: daemon is shutting down (${frame.optString("reason")}), falling back",
                        )
                        return null
                    }
                }
            }
        }
    }

    /** Asks the daemon to stop; returns false if it couldn't be reached (already down). */
    fun stop(socketPath: File): Boolean = try {
        connect(socketPath).use { channel ->
            FrameIO.writeFrame(
                Channels.newOutputStream(channel),
                DaemonRequest.Stop.toJson().put("version", VERSION),
            )
        }
        true
    } catch (e: IOException) {
        false
    }

    /** Returns the daemon's status, or null if it couldn't be reached. */
    fun status(socketPath: File): DaemonStatus? = try {
        connect(socketPath).use { channel ->
            val input = Channels.newInputStream(channel)
            val output = Channels.newOutputStream(channel)
            FrameIO.writeFrame(output, DaemonRequest.Status.toJson().put("version", VERSION))
            FrameIO.readFrame(input)?.let { DaemonStatus.fromJson(it) }
        }
    } catch (e: IOException) {
        null
    }
}
