package org.mycelium.library.daemon

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Requests a client can send the daemon, one per connection (see DaemonServer.handleClient).
 * `run` executes a file the same way `mycelium run <file>` would in-process; `stop` asks the
 * daemon to shut down after replying; `status` asks for a snapshot of daemon health.
 */
sealed class DaemonRequest {
    data class Run(
        val file: String,
        val args: List<String> = emptyList(),
        val cwd: String? = null,
        val env: Map<String, String> = emptyMap(),
        // Mirrors App.kt's `--yolo`/`--no-install` CLI flags — see DaemonClient.runFile and
        // DaemonServer.handleRun, which thread these straight into the per-request
        // Mycelium's own `yolo`/`autoInstall` constructor params so a flagged invocation
        // gets identical behavior whether it runs via the daemon or in-process.
        val yolo: Boolean = false,
        val noInstall: Boolean = false,
    ) : DaemonRequest()

    object Stop : DaemonRequest()
    object Status : DaemonRequest()

    fun toJson(): JSONObject = when (this) {
        is Run -> JSONObject()
            .put("command", "run")
            .put("file", file)
            .put("args", JSONArray(args))
            .also { if (cwd != null) it.put("cwd", cwd) }
            .put("env", JSONObject(env as Map<String, Any?>))
            .put("yolo", yolo)
            .put("noInstall", noInstall)
        Stop -> JSONObject().put("command", "stop")
        Status -> JSONObject().put("command", "status")
    }

    companion object {
        fun fromJson(obj: JSONObject): DaemonRequest = when (obj.optString("command")) {
            "run" -> Run(
                file = obj.getString("file"),
                args = obj.optJSONArray("args")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList(),
                cwd = if (obj.has("cwd") && !obj.isNull("cwd")) obj.optString("cwd") else null,
                env = obj.optJSONObject("env")?.let { o -> o.keySet().associateWith { k -> o.optString(k) } }
                    ?: emptyMap(),
                yolo = obj.optBoolean("yolo", false),
                noInstall = obj.optBoolean("noInstall", false),
            )
            "stop" -> Stop
            "status" -> Status
            else -> throw IOException("mycelium daemon: unknown command '${obj.optString("command")}'")
        }
    }
}

/** Typed snapshot of a `status` response's fields — DaemonClient parses the raw JSON frame
 * into this so callers (e.g. App.kt's `daemon status`) don't need an org.json dependency of
 * their own just to read a few fields. */
data class DaemonStatus(
    val pid: Long,
    val uptimeSeconds: Long,
    val requestCount: Long,
    val loadedLanguages: List<String>,
    val maxMemoryBytes: Long,
    val usedMemoryBytes: Long,
    val idleTimeoutSeconds: Long,
) {
    companion object {
        fun fromJson(obj: JSONObject): DaemonStatus = DaemonStatus(
            pid = obj.optLong("pid", 0),
            uptimeSeconds = obj.optLong("uptimeSeconds", 0),
            requestCount = obj.optLong("requestCount", 0),
            loadedLanguages = obj.optJSONArray("loadedLanguages")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            maxMemoryBytes = obj.optLong("maxMemoryBytes", 0),
            usedMemoryBytes = obj.optLong("usedMemoryBytes", 0),
            idleTimeoutSeconds = obj.optLong("idleTimeoutSeconds", 0),
        )
    }
}

/** Length-prefixed JSON framing shared by DaemonServer and DaemonClient: a 4-byte
 * big-endian uint32 byte length followed by that many bytes of UTF-8 JSON. Used both for
 * the single request frame a client sends and for the stream of response frames
 * (`stdout`/`stderr`/`exit`/`status`) the daemon sends back.
 */
object FrameIO {
    fun writeFrame(out: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        )
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /** Returns null on a clean EOF before any bytes of a new frame arrive (peer closed the
     * connection between frames); throws EOFException if the connection dies mid-frame. */
    fun readFrame(input: InputStream): JSONObject? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(header, read, 4 - read)
            if (n == -1) {
                if (read == 0) return null
                throw EOFException("mycelium daemon: connection closed while reading frame header")
            }
            read += n
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        val body = ByteArray(length)
        read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) throw EOFException("mycelium daemon: connection closed while reading frame body")
            read += n
        }
        return JSONObject(String(body, Charsets.UTF_8))
    }
}
