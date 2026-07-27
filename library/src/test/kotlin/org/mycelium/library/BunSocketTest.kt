package org.mycelium.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Networking slice of the Bun-compat shim: `Bun.connect` (TCP client), `Bun.listen` (TCP
// server), `Bun.udpSocket` (UDP), and WebSocket support in `Bun.serve({ websocket })` — all
// implemented in BUN_INIT_SCRIPT in Mycelium.kt on top of BunCompatTest.kt's/
// BunWriteSpawnTest.kt's existing Bun.file/.serve/.env/.argv/.write/.spawn coverage. Kept as
// its own file (rather than appended to BunCompatTest.kt) to avoid merge friction with other
// concurrent worktrees also touching the shim — see docs/reference/bun-compat.md for the
// authoritative, checked-against-upstream description of what's implemented and its known
// limitations.
//
// IMPORTANT (read before adding a test here): unlike BunCompatTest.kt's `Bun.serve` tests —
// which are backed by `com.sun.net.httpserver.HttpServer`'s own accept thread calling
// straight into the shared GraalJS Context, and therefore can never safely touch `mc` again
// afterward (see that file's own "Bun.serve()" section comment) — every networking primitive
// implemented for THIS file (Bun.connect/Bun.listen/Bun.udpSocket/the WebSocket path in
// Bun.serve) is driven entirely by `globalThis.setTimeout` ticks on the SAME thread that
// calls `runFile`/`eval` (see Mycelium.kt's own doc comment on this, and
// node-stdlib/child_process.mjs's `_startIoThread` doc comment for why a dedicated
// `java.lang.Thread` calling back into the Context was deliberately avoided). That means:
// after `mc.runFile(...)` returns, the Context is in exactly the same clean, single-thread-
// owned state it would be after any other script — `mc.eval("js", "globalThis.__x")` afterward
// is exactly as safe here as it already is in BunWriteSpawnTest.kt's `await Bun.write(...)`
// tests, NOT unsafe the way it is after BunCompatTest.kt's HTTP-serving tests. Every test
// below relies on this: a script sets `globalThis.__result` (or similar) and calls whatever
// `.stop()`/`.close()`/`.terminate()` is needed so the timer queue actually idles and
// `runFile` returns, then the test reads `globalThis.__result` back out via `mc.eval`.
//
// A plain `java.net.Socket`/`ServerSocket`/background `Thread` used directly from Kotlin
// (rather than through the shim) never touches the GraalJS Context at all, so it's always
// safe to run concurrently with a blocked `mc.runFile(...)` call on another Kotlin thread —
// several tests below use exactly that shape (a background Kotlin thread acting as the other
// end of a connection while the main test thread's `runFile` call pumps the shim's ticks).
//
// Live-parity discipline matches BunCompatTest.kt/BunWriteSpawnTest.kt: `bun` is a version-
// pinned nixpkgs package in this repo's flake.nix devShell, not a flaky network dependency,
// so wire-protocol compatibility is checked against the REAL `bun` binary in both directions
// (shim client -> real bun server, real bun client -> shim server) wherever feasible, rather
// than only asserted against this shim's own understanding of the protocol.
class BunSocketTest {
    companion object {
        private var resolvedBunCommand: List<String>? = null
        private var bunCommandResolved = false

        private fun isCommandAvailable(command: List<String>): Boolean =
            try {
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.readAllBytes(); it.waitFor() }
                    .exitValue() == 0
            } catch (e: java.io.IOException) {
                false
            }

        fun bunCommand(): List<String>? {
            if (bunCommandResolved) return resolvedBunCommand
            bunCommandResolved = true
            resolvedBunCommand = when {
                isCommandAvailable(listOf("bun", "--version")) -> listOf("bun")
                isCommandAvailable(listOf("nix", "shell", "nixpkgs#bun", "--command", "bun", "--version")) ->
                    listOf("nix", "shell", "nixpkgs#bun", "--command", "bun")
                else -> null
            }
            return resolvedBunCommand
        }

        // Runs a real `bun` script as a long-running background process (for a TCP/WS
        // server the shim will connect out to), returning it un-waited. The caller owns the
        // returned Process and must destroy it (`destroyForcibly()`) when done. Unlike
        // BunCompatTest's `startBunServerProcess` (HTTP, expects "PORT:" on the very first
        // stdout line), this one is generic — callers read whatever lines they need off
        // `process.inputStream` themselves.
        fun startRealBunProcess(scriptContent: String, args: List<String> = emptyList()): Process {
            val scriptFile = File.createTempFile("mycelium-bun-socket-", ".mjs")
            scriptFile.deleteOnExit()
            scriptFile.writeText(scriptContent)
            val command = bunCommand() ?: error("bun not available")
            return ProcessBuilder(command + listOf("run", scriptFile.absolutePath) + args)
                .redirectErrorStream(false)
                .start()
        }
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("mycelium-bun-socket-test-").toFile()

    private fun markBunProject(dir: File) {
        File(dir, "bunfig.toml").writeText("")
    }

    private fun runJsInDir(mc: Mycelium, dir: File, script: String) {
        val file = File(dir, "entry.mjs")
        file.writeText(script)
        mc.runFile(file.absolutePath)
    }

    private fun String.jsQuoted(): String =
        "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Reserves an ephemeral TCP port by binding then immediately closing a plain
    // ServerSocket — used so a script that must be embedded as source text up-front (rather
    // than reporting its port back out afterward — see this class' own top comment for why
    // `Bun.listen({ port: 0 })` alone can't be used when a SEPARATE process/thread needs the
    // port number *before* the shim's own script returns) can bind to a known, likely-free
    // port. Small unavoidable TOCTOU race (something else could grab the port between
    // release and reuse) — acceptable for a test.
    private fun reserveFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    // ---- WebSocket (RFC 6455) helpers — a from-scratch, independent client implementation
    // (NOT sharing any code with BUN_INIT_SCRIPT's own frame codec) so these tests are a
    // genuine external check of the shim's wire behavior rather than "the shim agreeing with
    // itself". ----

    private fun sha1Base64(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private data class WsHandshakeResult(val socket: Socket, val acceptHeader: String, val expectedAccept: String)

    // Performs the RFC 6455 opening handshake as a real client would: a random 16-byte
    // Sec-WebSocket-Key, verifying the server's own computed Sec-WebSocket-Accept matches
    // the RFC 6455 formula (SHA-1(key + the RFC's fixed GUID), base64) independently computed
    // here — this is the actual protocol-correctness check, not just "got a 101".
    private fun wsHandshake(host: String, port: Int, path: String = "/"): WsHandshakeResult {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 2000)
        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val request = "GET $path HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()

        val statusLine = readLineFromSocket(socket.getInputStream())
        assertTrue(statusLine.contains("101"), "expected HTTP 101 Switching Protocols, got: $statusLine")
        var acceptHeader = ""
        while (true) {
            val line = readLineFromSocket(socket.getInputStream())
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx == -1) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.equals("Sec-WebSocket-Accept", ignoreCase = true)) acceptHeader = v
        }
        val expected = sha1Base64(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
        return WsHandshakeResult(socket, acceptHeader, expected)
    }

    private fun readLineFromSocket(inStream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inStream.read()
            if (b == -1 || b == '\n'.code) break
            if (b == '\r'.code) continue
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    // Encodes one CLIENT-to-server frame (always masked per RFC 6455 §5.3 — a real server
    // rejects/misbehaves on an unmasked client frame, so masking here is itself part of what
    // makes this a faithful protocol check, not just "send some bytes").
    private fun writeWsFrame(out: OutputStream, opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val out2 = DataOutputStream(out)
        val b0 = (if (fin) 0x80 else 0x00) or (opcode and 0x0F)
        out2.writeByte(b0)
        val maskKey = ByteArray(4)
        SecureRandom().nextBytes(maskKey)
        val len = payload.size
        when {
            len < 126 -> out2.writeByte(0x80 or len)
            len <= 0xFFFF -> {
                out2.writeByte(0x80 or 126)
                out2.writeByte((len shr 8) and 0xFF)
                out2.writeByte(len and 0xFF)
            }
            else -> {
                out2.writeByte(0x80 or 127)
                for (i in 7 downTo 0) out2.writeByte(((len.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        out2.write(maskKey)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
        out2.write(masked)
        out2.flush()
    }

    private data class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    // Decodes one SERVER-to-client frame — per RFC 6455 §5.1 these are never masked.
    private fun readWsFrame(inStream: InputStream): WsFrame? {
        val b0 = inStream.read()
        if (b0 == -1) return null
        val b1 = inStream.read()
        if (b1 == -1) throw java.io.EOFException("EOF reading WS frame header")
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            val a = inStream.read(); val b = inStream.read()
            len = ((a and 0xFF).toLong() shl 8) or (b and 0xFF).toLong()
        } else if (len == 127L) {
            var l = 0L
            repeat(8) { l = (l shl 8) or inStream.read().toLong() }
            len = l
        }
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val n = inStream.read(payload, off, payload.size - off)
            if (n == -1) throw java.io.EOFException("EOF reading WS frame payload")
            off += n
        }
        return WsFrame(fin, opcode, payload)
    }

    // --- Bun.connect() ---

    @Test fun bunConnectHappyPathAgainstPlainJavaServer() {
        val serverSocket = ServerSocket(0)
        val received = StringBuilder()
        val serverThread = Thread {
            serverSocket.accept().use { client ->
                val buf = ByteArray(1024)
                val n = client.getInputStream().read(buf)
                if (n > 0) received.append(String(buf, 0, n, StandardCharsets.UTF_8))
                client.getOutputStream().write("ack".toByteArray(StandardCharsets.UTF_8))
                client.getOutputStream().flush()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__data = undefined;
            const socket = await Bun.connect({
                hostname: "127.0.0.1",
                port: ${serverSocket.localPort},
                socket: {
                    open(s) { s.write("hello-from-shim"); },
                    data(s, buf) { globalThis.__data = buf.toString(); s.end(); },
                    error(s, e) { globalThis.__error = String(e && e.message ? e.message : e); },
                },
            });
            for (let i = 0; i < 150 && globalThis.__data === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            """.trimIndent()
        )
        serverThread.join(5000)
        assertEquals("ack", mc.eval("js", "globalThis.__data").asString())
        assertEquals("hello-from-shim", received.toString())
        serverSocket.close()
    }

    @Test fun bunConnectConnectionRefusedRejects() {
        // A closed-then-reserved port: nothing listens there, so the OS should refuse the
        // connection immediately (ECONNREFUSED) rather than timing out.
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__rejected = false;
            globalThis.__connectErrorFired = false;
            try {
                await Bun.connect({
                    hostname: "127.0.0.1",
                    port: $port,
                    socket: {
                        connectError(s, e) { globalThis.__connectErrorFired = true; },
                        open(s) { globalThis.__openedUnexpectedly = true; },
                    },
                });
            } catch (e) {
                globalThis.__rejected = true;
            }
            """.trimIndent()
        )
        assertTrue(mc.eval("js", "globalThis.__rejected").asBoolean(), "Bun.connect() to a refused port should reject")
        assertTrue(mc.eval("js", "globalThis.__connectErrorFired").asBoolean(), "socket.connectError should fire")
        assertFalse(mc.eval("js", "typeof globalThis.__openedUnexpectedly !== 'undefined'").asBoolean())
    }

    @Test fun bunConnectLargeMessageRoundTrip() {
        // Exercises many setTimeout-tick reads assembling one large logical message (the
        // shim's socket reader delivers whatever a single `read()` call returns per tick,
        // not necessarily the whole payload at once) — the JS side concatenates chunks
        // itself, same as real Node/Bun's own `data` event semantics (no message framing at
        // the TCP level).
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() } // 2 MiB, non-trivial content
        val serverSocket = ServerSocket(0)
        val serverThread = Thread {
            serverSocket.accept().use { client ->
                client.getOutputStream().write(payload)
                client.getOutputStream().flush()
                client.shutdownOutput()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__totalBytes = 0;
            globalThis.__firstByte = -1;
            globalThis.__lastByte = -1;
            globalThis.__ended = false;
            await Bun.connect({
                hostname: "127.0.0.1",
                port: ${serverSocket.localPort},
                socket: {
                    data(s, buf) {
                        if (globalThis.__firstByte === -1 && buf.length > 0) globalThis.__firstByte = buf[0];
                        if (buf.length > 0) globalThis.__lastByte = buf[buf.length - 1];
                        globalThis.__totalBytes += buf.length;
                    },
                    end(s) { globalThis.__ended = true; },
                },
            });
            for (let i = 0; i < 300 && !globalThis.__ended; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            """.trimIndent()
        )
        serverThread.join(10000)
        assertEquals(payload.size.toLong(), mc.eval("js", "globalThis.__totalBytes").asLong())
        assertEquals(payload[0].toInt() and 0xFF, mc.eval("js", "globalThis.__firstByte").asInt())
        assertEquals(payload[payload.size - 1].toInt() and 0xFF, mc.eval("js", "globalThis.__lastByte").asInt())
        assertTrue(mc.eval("js", "globalThis.__ended").asBoolean())
        serverSocket.close()
    }

    // --- Bun.listen() ---

    @Test fun bunListenHappyPathWithPlainJavaClient() {
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val clientResult = StringBuilder()
        val clientThread = Thread {
            var connected = false
            for (attempt in 0 until 100) {
                try {
                    Socket().use { client ->
                        client.connect(InetSocketAddress("127.0.0.1", port), 500)
                        connected = true
                        client.getOutputStream().write("hello-server".toByteArray(StandardCharsets.UTF_8))
                        client.getOutputStream().flush()
                        client.shutdownOutput()
                        val buf = ByteArray(1024)
                        val n = client.getInputStream().read(buf)
                        if (n > 0) clientResult.append(String(buf, 0, n, StandardCharsets.UTF_8))
                    }
                    break
                } catch (e: java.io.IOException) {
                    if (connected) break
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__received = undefined;
            const listener = Bun.listen({
                hostname: "127.0.0.1",
                port: $port,
                socket: {
                    data(socket, buf) {
                        globalThis.__received = buf.toString();
                        socket.end("echo:" + buf.toString());
                    },
                },
            });
            globalThis.__port = listener.port;
            for (let i = 0; i < 150 && globalThis.__received === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            listener.stop();
            """.trimIndent()
        )
        clientThread.join(5000)
        assertEquals(port, mc.eval("js", "globalThis.__port").asInt())
        assertEquals("hello-server", mc.eval("js", "globalThis.__received").asString())
        assertEquals("echo:hello-server", clientResult.toString())
    }

    @Test fun bunListenAbruptClientDisconnect() {
        // The client connects then closes immediately without sending or reading anything —
        // the server's `close`/`error` handling must not throw or hang; `open` must still
        // have fired before the abrupt close.
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val clientThread = Thread {
            for (attempt in 0 until 100) {
                try {
                    Socket().use { client ->
                        client.connect(InetSocketAddress("127.0.0.1", port), 500)
                        // Abrupt: SO_LINGER(0) forces an RST rather than a clean FIN.
                        client.setSoLinger(true, 0)
                    }
                    return@Thread
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__opened = false;
            globalThis.__closedOrErrored = false;
            const listener = Bun.listen({
                hostname: "127.0.0.1",
                port: $port,
                socket: {
                    open(socket) { globalThis.__opened = true; },
                    close(socket) { globalThis.__closedOrErrored = true; },
                    error(socket, e) { globalThis.__closedOrErrored = true; },
                },
            });
            for (let i = 0; i < 150 && !globalThis.__closedOrErrored; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            listener.stop();
            """.trimIndent()
        )
        clientThread.join(5000)
        assertTrue(mc.eval("js", "globalThis.__opened").asBoolean(), "socket.open should fire before the abrupt disconnect")
        assertTrue(mc.eval("js", "globalThis.__closedOrErrored").asBoolean(), "socket.close or socket.error should fire after an abrupt disconnect")
    }

    // --- Bun.udpSocket() ---

    @Test fun bunUdpSocketConnectedPairRoundTrip() {
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__serverGot = undefined;
            globalThis.__clientGot = undefined;
            const server = await Bun.udpSocket({
                hostname: "127.0.0.1",
                port: 0,
                socket: {
                    data(socket, buf, port, address) {
                        globalThis.__serverGot = buf.toString();
                        socket.send("pong", port, address);
                    },
                },
            });
            const client = await Bun.udpSocket({
                connect: { hostname: "127.0.0.1", port: server.port },
                socket: {
                    data(socket, buf) { globalThis.__clientGot = buf.toString(); },
                },
            });
            client.send("ping");
            for (let i = 0; i < 150 && globalThis.__clientGot === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            client.close();
            server.close();
            """.trimIndent()
        )
        assertEquals("ping", mc.eval("js", "globalThis.__serverGot").asString())
        assertEquals("pong", mc.eval("js", "globalThis.__clientGot").asString())
    }

    // --- Live parity: TCP, both directions against a real `bun` binary ---

    @Test fun bunConnectLiveAgainstRealBunListenServer() {
        assumeTrue(bunCommand() != null, "bun not available in this environment — skipping live-parity check")
        val serverScript = """
            const listener = Bun.listen({
                hostname: "127.0.0.1",
                port: 0,
                socket: {
                    data(socket, buf) { socket.write(buf); },
                },
            });
            console.log("PORT:" + listener.port);
            await new Promise(() => {}); // keep the real bun process alive until killed
        """.trimIndent()
        val process = startRealBunProcess(serverScript)
        try {
            val portLine = process.inputStream.bufferedReader().readLine()
                ?: error("real bun TCP server produced no output (stderr: ${process.errorStream.bufferedReader().readText()})")
            val port = portLine.removePrefix("PORT:").trim().toInt()

            val mc = Mycelium()
            val dir = createTempDirectory()
            markBunProject(dir)
            runJsInDir(
                mc, dir,
                """
                globalThis.__echo = undefined;
                await Bun.connect({
                    hostname: "127.0.0.1",
                    port: $port,
                    socket: {
                        open(s) { s.write("shim-to-real-bun"); },
                        data(s, buf) { globalThis.__echo = buf.toString(); s.end(); },
                    },
                });
                for (let i = 0; i < 150 && globalThis.__echo === undefined; i++) {
                    await new Promise(r => setTimeout(r, 20));
                }
                """.trimIndent()
            )
            assertEquals("shim-to-real-bun", mc.eval("js", "globalThis.__echo").asString())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun bunListenLiveAgainstRealBunConnectClient() {
        assumeTrue(bunCommand() != null, "bun not available in this environment — skipping live-parity check")
        val port = reserveFreePort()
        val resultFile = File.createTempFile("mycelium-bun-listen-live-", ".txt")
        resultFile.deleteOnExit()
        val clientScript = """
            const port = Number(Bun.argv[2]);
            const resultPath = ${resultFile.absolutePath.jsQuoted()};
            for (let attempt = 0; attempt < 100; attempt++) {
                try {
                    await new Promise((resolve, reject) => {
                        Bun.connect({
                            hostname: "127.0.0.1",
                            port: port,
                            socket: {
                                open(s) { s.write("real-bun-to-shim"); },
                                data(s, buf) {
                                    require("fs").writeFileSync(resultPath, buf.toString());
                                    s.end();
                                    resolve();
                                },
                                connectError(s, e) { reject(e); },
                            },
                        }).catch(reject);
                    });
                    break;
                } catch (e) {
                    await new Promise(r => setTimeout(r, 30));
                }
            }
        """.trimIndent()
        val clientScriptFile = File.createTempFile("mycelium-bun-listen-live-client-", ".mjs")
        clientScriptFile.deleteOnExit()
        clientScriptFile.writeText(clientScript)
        val realClientProcess = ProcessBuilder(
            (bunCommand() ?: error("bun not available")) + listOf("run", clientScriptFile.absolutePath, port.toString())
        ).redirectErrorStream(true).start()

        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        try {
            runJsInDir(
                mc, dir,
                """
                globalThis.__received = undefined;
                const listener = Bun.listen({
                    hostname: "127.0.0.1",
                    port: $port,
                    socket: {
                        data(socket, buf) {
                            globalThis.__received = buf.toString();
                            socket.write("shim-echo:" + buf.toString());
                        },
                    },
                });
                for (let i = 0; i < 200 && globalThis.__received === undefined; i++) {
                    await new Promise(r => setTimeout(r, 20));
                }
                await new Promise(r => setTimeout(r, 100)); // give the client a moment to read the echo
                listener.stop();
                """.trimIndent()
            )
            realClientProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("real-bun-to-shim", mc.eval("js", "globalThis.__received").asString())
            assertTrue(resultFile.exists() && resultFile.readText().isNotEmpty(), "real bun client never recorded a result")
            assertEquals("shim-echo:real-bun-to-shim", resultFile.readText())
        } finally {
            realClientProcess.destroyForcibly()
        }
    }

    // --- Bun.serve({ websocket }) ---

    @Test fun bunServeWebSocketHandshakeAndEcho() {
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        var acceptResultOk = false
        val clientThread = Thread {
            for (attempt in 0 until 150) {
                try {
                    val (socket, acceptHeader, expected) = wsHandshake("127.0.0.1", port)
                    acceptResultOk = acceptHeader == expected
                    socket.use {
                        writeWsFrame(socket.getOutputStream(), 0x1, "hello-ws".toByteArray(StandardCharsets.UTF_8))
                        val frame = readWsFrame(socket.getInputStream())
                        assertNotNull(frame)
                        assertEquals(0x1, frame!!.opcode)
                        assertEquals("echo:hello-ws", String(frame.payload, StandardCharsets.UTF_8))
                        writeWsFrame(socket.getOutputStream(), 0x8, byteArrayOf(0x03, 0xE8.toByte())) // close, code 1000
                    }
                    return@Thread
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__opened = false;
            globalThis.__messageText = undefined;
            globalThis.__closeCode = undefined;
            const server = Bun.serve({
                hostname: "127.0.0.1",
                port: $port,
                fetch(req, srv) {
                    if (srv.upgrade(req)) return;
                    return new Response("not a websocket request", { status: 400 });
                },
                websocket: {
                    open(ws) { globalThis.__opened = true; },
                    message(ws, msg) {
                        globalThis.__messageText = msg;
                        ws.send("echo:" + msg);
                    },
                    close(ws, code, reason) { globalThis.__closeCode = code; },
                },
            });
            for (let i = 0; i < 150 && globalThis.__closeCode === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            server.stop();
            """.trimIndent()
        )
        clientThread.join(5000)
        assertTrue(acceptResultOk, "Sec-WebSocket-Accept must match the RFC 6455 formula")
        assertTrue(mc.eval("js", "globalThis.__opened").asBoolean())
        assertEquals("hello-ws", mc.eval("js", "globalThis.__messageText").asString())
        assertEquals(1000, mc.eval("js", "globalThis.__closeCode").asInt())
    }

    @Test fun bunServeWebSocketFragmentedFrames() {
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val clientThread = Thread {
            for (attempt in 0 until 150) {
                try {
                    val (socket, _, _) = wsHandshake("127.0.0.1", port)
                    socket.use {
                        // "foobar" split across three frames: TEXT("foo", fin=false),
                        // CONTINUATION("ba", fin=false), CONTINUATION("r", fin=true).
                        writeWsFrame(socket.getOutputStream(), 0x1, "foo".toByteArray(StandardCharsets.UTF_8), fin = false)
                        writeWsFrame(socket.getOutputStream(), 0x0, "ba".toByteArray(StandardCharsets.UTF_8), fin = false)
                        writeWsFrame(socket.getOutputStream(), 0x0, "r".toByteArray(StandardCharsets.UTF_8), fin = true)
                        val frame = readWsFrame(socket.getInputStream())
                        assertEquals("got:foobar", String(frame!!.payload, StandardCharsets.UTF_8))
                        writeWsFrame(socket.getOutputStream(), 0x8, byteArrayOf(0x03, 0xE8.toByte()))
                    }
                    return@Thread
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__message = undefined;
            const server = Bun.serve({
                hostname: "127.0.0.1", port: $port,
                fetch(req, srv) { if (srv.upgrade(req)) return; return new Response("no", { status: 400 }); },
                websocket: {
                    message(ws, msg) { globalThis.__message = msg; ws.send("got:" + msg); },
                },
            });
            for (let i = 0; i < 150 && globalThis.__message === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            await new Promise(r => setTimeout(r, 100));
            server.stop();
            """.trimIndent()
        )
        clientThread.join(5000)
        assertEquals("foobar", mc.eval("js", "globalThis.__message").asString())
    }

    @Test fun bunServeWebSocketLargeMessage() {
        // 200 KiB — comfortably past the 16-bit extended-length threshold (65535 bytes) on
        // both the incoming (client -> server) and outgoing (server -> client) frame, and
        // large enough that the underlying TCP read/write will very likely be split across
        // multiple setTimeout ticks on the shim side.
        val size = 200 * 1024
        val payload = ByteArray(size) { (it % 256).toByte() }
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        var echoMatches = false
        val clientThread = Thread {
            for (attempt in 0 until 150) {
                try {
                    val (socket, _, _) = wsHandshake("127.0.0.1", port)
                    socket.use {
                        writeWsFrame(socket.getOutputStream(), 0x2, payload) // binary
                        val frame = readWsFrame(socket.getInputStream())
                        echoMatches = frame != null && frame.opcode == 0x2 && frame.payload.contentEquals(payload)
                        writeWsFrame(socket.getOutputStream(), 0x8, byteArrayOf(0x03, 0xE8.toByte()))
                    }
                    return@Thread
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__len = undefined;
            const server = Bun.serve({
                hostname: "127.0.0.1", port: $port,
                fetch(req, srv) { if (srv.upgrade(req)) return; return new Response("no", { status: 400 }); },
                websocket: {
                    message(ws, msg) { globalThis.__len = msg.length; ws.sendBinary(msg); },
                },
            });
            for (let i = 0; i < 200 && globalThis.__len === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            await new Promise(r => setTimeout(r, 200));
            server.stop();
            """.trimIndent()
        )
        clientThread.join(10000)
        assertEquals(size.toLong(), mc.eval("js", "globalThis.__len").asLong())
        assertTrue(echoMatches, "large binary message must round-trip byte-for-byte")
    }

    @Test fun bunServeWebSocketPingPong() {
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        var pongPayloadMatches = false
        val clientThread = Thread {
            for (attempt in 0 until 150) {
                try {
                    val (socket, _, _) = wsHandshake("127.0.0.1", port)
                    socket.use {
                        writeWsFrame(socket.getOutputStream(), 0x9, "ping-data".toByteArray(StandardCharsets.UTF_8)) // ping
                        val frame = readWsFrame(socket.getInputStream())
                        pongPayloadMatches = frame != null && frame.opcode == 0xA &&
                            String(frame.payload, StandardCharsets.UTF_8) == "ping-data"
                        writeWsFrame(socket.getOutputStream(), 0x8, byteArrayOf(0x03, 0xE8.toByte()))
                    }
                    return@Thread
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        clientThread.isDaemon = true
        clientThread.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__pingSeen = false;
            const server = Bun.serve({
                hostname: "127.0.0.1", port: $port,
                fetch(req, srv) { if (srv.upgrade(req)) return; return new Response("no", { status: 400 }); },
                websocket: {
                    message(ws, msg) {},
                    ping(ws, data) { globalThis.__pingSeen = true; },
                },
            });
            for (let i = 0; i < 150 && !globalThis.__pingSeen; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            await new Promise(r => setTimeout(r, 100));
            server.stop();
            """.trimIndent()
        )
        clientThread.join(5000)
        assertTrue(mc.eval("js", "globalThis.__pingSeen").asBoolean(), "websocket.ping handler should fire")
        assertTrue(pongPayloadMatches, "the shim should auto-reply to a Ping with a Pong carrying the same payload")
    }

    @Test fun bunServeWebSocketConcurrentConnections() {
        // Two independent WebSocket clients, connecting and exchanging messages one after
        // another (not perfectly simultaneously — see this file's own top comment on the
        // pre-existing, documented single-Context-thread constraint) — verifies the server
        // keeps per-connection state (e.g. `ws.data`) separate rather than leaking between
        // connections.
        val port = reserveFreePort()
        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)

        val results = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun runClient(name: String) {
            for (attempt in 0 until 150) {
                try {
                    val (socket, _, _) = wsHandshake("127.0.0.1", port, "/?name=$name")
                    socket.use {
                        writeWsFrame(socket.getOutputStream(), 0x1, name.toByteArray(StandardCharsets.UTF_8))
                        val frame = readWsFrame(socket.getInputStream())
                        results[name] = String(frame!!.payload, StandardCharsets.UTF_8)
                        writeWsFrame(socket.getOutputStream(), 0x8, byteArrayOf(0x03, 0xE8.toByte()))
                    }
                    return
                } catch (e: java.io.IOException) {
                    Thread.sleep(20)
                }
            }
        }
        // Both clients must be started BEFORE (or concurrently with) the server below, not
        // joined first — `runClient`'s own retry loop is what tolerates the race against
        // `runJsInDir` actually binding the listener; joining one client to completion here
        // first would just make it retry-and-give-up against a server that doesn't exist yet.
        val t1 = Thread { runClient("alice") }
        val t2 = Thread { runClient("bob") }
        t1.isDaemon = true; t2.isDaemon = true
        t1.start()
        t2.start()

        runJsInDir(
            mc, dir,
            """
            globalThis.__doneCount = 0;
            const server = Bun.serve({
                hostname: "127.0.0.1", port: $port,
                fetch(req, srv) {
                    const name = new URL(req.url).searchParams.get("name");
                    if (srv.upgrade(req, { data: { name } })) return;
                    return new Response("no", { status: 400 });
                },
                websocket: {
                    message(ws, msg) {
                        ws.send("hello-" + ws.data.name + ":" + msg);
                        globalThis.__doneCount++;
                    },
                },
            });
            for (let i = 0; i < 250 && globalThis.__doneCount < 2; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            server.stop();
            """.trimIndent()
        )
        t1.join(5000)
        t2.join(5000)
        assertEquals(2L, mc.eval("js", "globalThis.__doneCount").asLong())
        assertEquals("hello-alice:alice", results["alice"])
        assertEquals("hello-bob:bob", results["bob"])
    }

    @Test fun bunServeWebSocketLiveAgainstRealBunClient() {
        // Orchestrated entirely from WITHIN the shim's own script via the already-implemented
        // `Bun.spawn` (async, non-blocking — see this file's own top comment: a *blocking*
        // `Bun.spawnSync` here would starve the shim's own accept/read setTimeout ticks for
        // the spawned process' whole lifetime, deadlocking against the very client it just
        // launched). The real bun client uses `new WebSocket(url)` — this is the strongest
        // available live-parity evidence for the server's wire behavior: the spec's
        // reference client implementation, not a hand-rolled one.
        assumeTrue(bunCommand() != null, "bun not available in this environment — skipping live-parity check")
        val port = reserveFreePort()
        val resultFile = File.createTempFile("mycelium-bun-ws-live-", ".txt")
        resultFile.deleteOnExit()
        val clientScriptFile = File.createTempFile("mycelium-bun-ws-live-client-", ".mjs")
        clientScriptFile.deleteOnExit()
        clientScriptFile.writeText(
            """
            const url = Bun.argv[2];
            const resultPath = Bun.argv[3];
            const ws = new WebSocket(url);
            ws.onopen = () => { ws.send("hi-from-real-bun-ws-client"); };
            ws.onmessage = (ev) => {
                require("fs").writeFileSync(resultPath, String(ev.data));
                ws.close();
            };
            await new Promise(r => setTimeout(r, 4000));
            """.trimIndent()
        )
        val bunCmd = bunCommand() ?: error("bun not available")

        val mc = Mycelium()
        val dir = createTempDirectory()
        markBunProject(dir)
        runJsInDir(
            mc, dir,
            """
            globalThis.__serverGotMessage = undefined;
            const server = Bun.serve({
                hostname: "127.0.0.1", port: $port,
                fetch(req, srv) { if (srv.upgrade(req)) return; return new Response("no", { status: 400 }); },
                websocket: {
                    message(ws, msg) { globalThis.__serverGotMessage = msg; ws.send("server-ack:" + msg); },
                },
            });
            const child = Bun.spawn(
                ${bunCmd.map { it.jsQuoted() }.joinToString(", ", "[", "]")}.concat([
                    "run", ${clientScriptFile.absolutePath.jsQuoted()},
                    "ws://127.0.0.1:$port/", ${resultFile.absolutePath.jsQuoted()},
                ]),
                { stdout: "ignore", stderr: "ignore" }
            );
            for (let i = 0; i < 250 && globalThis.__serverGotMessage === undefined; i++) {
                await new Promise(r => setTimeout(r, 20));
            }
            await new Promise(r => setTimeout(r, 200)); // let the real-bun client read the reply and write its result file
            try { child.kill(); } catch (e) {}
            server.stop();
            """.trimIndent()
        )
        assertEquals("hi-from-real-bun-ws-client", mc.eval("js", "globalThis.__serverGotMessage").asString())
        assertTrue(resultFile.exists() && resultFile.readText().isNotEmpty(), "real bun WebSocket client never recorded the server's reply")
        assertEquals("server-ack:hi-from-real-bun-ws-client", resultFile.readText())
    }
}
