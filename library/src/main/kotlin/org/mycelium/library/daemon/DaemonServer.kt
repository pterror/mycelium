package org.mycelium.library.daemon

import org.graalvm.polyglot.Engine
import org.json.JSONObject
import org.mycelium.library.Mycelium
import org.mycelium.library.MyceliumException
import org.mycelium.library.ProcessExitException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * The long-running daemon process: listens on a Unix domain socket, and for each
 * connection reads exactly one request (`run`/`stop`/`status`), replies with a stream of
 * response frames, and closes the connection. Requests are handled one at a time on the
 * accept loop's own thread — GraalVM Contexts are inherently single-threaded anyway (see
 * Mycelium's own doc comments), so a second client that connects while a `run` is in
 * flight simply blocks in its OS-level `connect()`/first `write()` until the accept loop
 * gets back around to it; no separate queue is needed.
 *
 * One [Engine] is built once in the constructor and shared across every `run` request's
 * own fresh [Mycelium]/Context (see Mycelium's `engine` constructor parameter) — this is
 * the whole point of the daemon: it amortizes GraalVM's warm-up/compiled-code cost across
 * requests while still giving each request an isolated Context (fresh globals, fresh
 * timers, no state leaking between unrelated scripts).
 */
class DaemonServer(
    private val socketPath: File,
    private val pidPath: File,
    private val idleTimeoutSeconds: Long,
) {
    private val engine: Engine = Engine.newBuilder().build()
    private val startedAtMillis = System.currentTimeMillis()
    @Volatile private var lastActivityMillis = System.currentTimeMillis()
    @Volatile private var running = true
    private var requestCount = 0L
    private lateinit var serverChannel: ServerSocketChannel

    fun start() {
        socketPath.parentFile?.mkdirs()
        // A stale socket file from a previous (crashed or killed) daemon run prevents
        // bind() from succeeding ("address already in use") — the caller (App.kt) is
        // responsible for confirming no live daemon is actually listening before spawning
        // this process, so any leftover file here is safe to remove.
        if (socketPath.exists()) socketPath.delete()

        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath.toPath()))
        pidPath.writeText(ProcessHandle.current().pid().toString())

        Runtime.getRuntime().addShutdownHook(Thread { cleanup() })

        val idleThread = Thread({
            while (running) {
                try {
                    Thread.sleep(15_000)
                } catch (e: InterruptedException) {
                    break
                }
                if (idleTimeoutSeconds > 0 &&
                    System.currentTimeMillis() - lastActivityMillis > idleTimeoutSeconds * 1000
                ) {
                    System.err.println("mycelium daemon: idle for ${idleTimeoutSeconds}s, shutting down")
                    stopListening()
                    break
                }
            }
        }, "mycelium-daemon-idle-check")
        idleThread.isDaemon = true
        idleThread.start()

        try {
            while (running) {
                val client = try {
                    serverChannel.accept()
                } catch (e: ClosedChannelException) {
                    break
                }
                lastActivityMillis = System.currentTimeMillis()
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    System.err.println("mycelium daemon: error handling client — ${e.message}")
                } finally {
                    try { client.close() } catch (e: Exception) { /* ignore */ }
                }
                lastActivityMillis = System.currentTimeMillis()
            }
        } finally {
            cleanup()
        }
    }

    private fun stopListening() {
        running = false
        try { serverChannel.close() } catch (e: Exception) { /* ignore */ }
    }

    private fun cleanup() {
        try { if (socketPath.exists()) socketPath.delete() } catch (e: Exception) { /* ignore */ }
        try { if (pidPath.exists()) pidPath.delete() } catch (e: Exception) { /* ignore */ }
    }

    private fun handleClient(channel: SocketChannel) {
        val input = Channels.newInputStream(channel)
        val output = Channels.newOutputStream(channel)
        val requestJson = FrameIO.readFrame(input) ?: return
        val request = DaemonRequest.fromJson(requestJson)
        requestCount++
        when (request) {
            is DaemonRequest.Run -> handleRun(request, output)
            DaemonRequest.Stop -> {
                FrameIO.writeFrame(output, JSONObject().put("type", "exit").put("code", 0))
                stopListening()
            }
            DaemonRequest.Status -> FrameIO.writeFrame(output, statusJson())
        }
    }

    private fun statusJson(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject()
            .put("type", "status")
            .put("pid", ProcessHandle.current().pid())
            .put("uptimeSeconds", (System.currentTimeMillis() - startedAtMillis) / 1000)
            .put("requestCount", requestCount)
            .put("loadedLanguages", engine.languages.keys.sorted())
            .put("maxMemoryBytes", runtime.maxMemory())
            .put("usedMemoryBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("idleTimeoutSeconds", idleTimeoutSeconds)
    }

    // Runs one file for one client, capturing everything the script (and Mycelium itself)
    // writes to stdout/stderr and re-framing it back over the socket. Swaps the real
    // process-wide System.out/System.err for the duration of the call rather than only
    // configuring the GraalVM Context's own out()/err() streams: PROCESS_INIT_SCRIPT's
    // `process.stdout.write`/`process.stderr.write` shim (see Mycelium.kt) writes straight
    // to `java.lang.System.out`/`.err`, bypassing whatever the Context was configured
    // with, so capturing at the JVM-global System level is the only way to catch both that
    // *and* ordinary console.log output. Safe to mutate the global streams here because
    // the accept loop (see `start`) never runs two requests concurrently.
    private fun handleRun(request: DaemonRequest.Run, output: OutputStream) {
        val stdoutFrames = FrameOutputStream(output, "stdout")
        val stderrFrames = FrameOutputStream(output, "stderr")
        val stdoutPrint = PrintStream(stdoutFrames, true, Charsets.UTF_8)
        val stderrPrint = PrintStream(stderrFrames, true, Charsets.UTF_8)

        val originalOut = System.out
        val originalErr = System.err
        var exitCode = 0
        try {
            System.setOut(stdoutPrint)
            System.setErr(stderrPrint)
            // Per-request `cwd`/`env` can't change the live JVM process's own working
            // directory/environment (there's no supported way to do that), but they don't
            // need to: Mycelium builds a fresh GraalVM Context per request (see the
            // `engine`-sharing doc comment on the class), and Context.Builder exposes both
            // `currentWorkingDirectory(Path)` and `environment(Map)` as per-Context
            // settings — see Mycelium's own `cwd`/`env` constructor params, which apply
            // these to the Context (plus PROCESS_INIT_SCRIPT's `process.cwd()`/
            // `process.env`) without touching the daemon process's real cwd/env at all.
            // `request.env` is only passed through when non-empty so a `Run` request built
            // without one (e.g. an existing test) keeps inheriting the daemon's own
            // environment, matching pre-this-change behavior.
            val vm = Mycelium(
                engine = engine,
                exitHandler = { code -> throw ProcessExitException(code) },
                cwd = request.cwd,
                env = request.env.takeIf { it.isNotEmpty() },
                yolo = request.yolo,
                autoInstall = !request.noInstall,
            )
            vm.runFile(request.file)
        } catch (e: ProcessExitException) {
            exitCode = e.code
        } catch (e: MyceliumException) {
            stderrPrint.println(e.message)
            exitCode = 1
        } catch (e: Exception) {
            stderrPrint.println("mycelium: internal error — ${e.message}")
            exitCode = 1
        } finally {
            stdoutPrint.flush()
            stderrPrint.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        FrameIO.writeFrame(output, JSONObject().put("type", "exit").put("code", exitCode))
    }

    // Buffers bytes written to it and flushes them as one `stdout`/`stderr` response frame
    // per `flush()` call — `PrintStream(..., true, ...)` (autoFlush) calls `flush()` after
    // every `println`/on every '\n' in a `write(byte[])`, so output reaches the client at
    // roughly line granularity rather than one frame per byte.
    private class FrameOutputStream(private val rawOut: OutputStream, private val frameType: String) :
        OutputStream() {
        private val buffer = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) {
            buffer.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            buffer.write(b, off, len)
        }

        @Synchronized
        override fun flush() {
            if (buffer.size() == 0) return
            val data = buffer.toString(Charsets.UTF_8)
            buffer.reset()
            FrameIO.writeFrame(rawOut, JSONObject().put("type", frameType).put("data", data))
        }
    }
}

/** Process entry point for the forked daemon JVM (see App.kt's launcher, which starts this
 * via `ProcessBuilder` against its own classpath). Args: `<socketPath> <pidPath>
 * <idleTimeoutSeconds>`. */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: DaemonServerKt <socketPath> <pidPath> <idleTimeoutSeconds>")
        kotlin.system.exitProcess(1)
    }
    val socketPath = File(args[0])
    val pidPath = File(args[1])
    val idleTimeoutSeconds = args[2].toLongOrNull() ?: 3600L

    // SIGTERM/SIGINT both trigger the JVM's shutdown-hook mechanism, which DaemonServer
    // registers from within `start()` (after socket/pid files exist) to delete them —
    // covers both `mycelium daemon stop`'s SIGTERM and Ctrl-C on a foregrounded daemon.
    DaemonServer(socketPath, pidPath, idleTimeoutSeconds).start()
}
