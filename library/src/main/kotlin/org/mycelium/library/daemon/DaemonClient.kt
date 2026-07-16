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
     * on `stderr` if the connection drops before an `exit` frame arrives. Throws IOException
     * if the daemon can't be reached at all, so the caller can decide whether to fall back
     * to running in-process.
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
        stdout: OutputStream = System.out,
        stderr: OutputStream = System.err,
    ): Int {
        connect(socketPath).use { channel ->
            val input = Channels.newInputStream(channel)
            val output = Channels.newOutputStream(channel)
            FrameIO.writeFrame(output, DaemonRequest.Run(file, args, cwd, env, yolo, noInstall).toJson())
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
                }
            }
        }
    }

    /** Asks the daemon to stop; returns false if it couldn't be reached (already down). */
    fun stop(socketPath: File): Boolean = try {
        connect(socketPath).use { channel ->
            FrameIO.writeFrame(Channels.newOutputStream(channel), DaemonRequest.Stop.toJson())
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
            FrameIO.writeFrame(output, DaemonRequest.Status.toJson())
            FrameIO.readFrame(input)?.let { DaemonStatus.fromJson(it) }
        }
    } catch (e: IOException) {
        null
    }
}
