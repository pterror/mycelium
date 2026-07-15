package org.mycelium.library

import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.max
import kotlin.math.min

class HttpChannel(uri: URI) : SeekableByteChannel {
    private val connection = uri.toURL().openConnection() as HttpURLConnection
    private var buffer = ByteBuffer.allocate(8192)
    private var length = 0

    init {
        // Some websites send special text-only output to `curl`.
        connection.setRequestProperty("User-Agent", "curl/8.9.1")
        connection.connect()
    }

    override fun close() {
        connection.inputStream.close()
    }

    override fun isOpen(): Boolean = false

    private fun ensure(capacity: Int) {
        while (capacity > buffer.capacity()) {
            val oldBuffer = buffer
            buffer = ByteBuffer.allocate(buffer.capacity() * 2)
            buffer.put(oldBuffer.array(), 0, length)
            buffer.position(oldBuffer.position())
        }
    }

    private fun fetchMoreData() {
        if (connection.inputStream.available() == 0) return
        val end = length + connection.inputStream.available()
        if (end > length) {
            ensure(end)
            val position = buffer.position()
            buffer.position(length)
            val bytesRead =
                connection.inputStream.read(
                    buffer.array(),
                    buffer.arrayOffset(),
                    buffer.capacity() - buffer.arrayOffset()
                )
            length += max(0, bytesRead)
            buffer.position(position)
        }
    }

    override fun read(p0: ByteBuffer): Int {
        val end = buffer.position() + p0.limit()
        if (end > length) fetchMoreData()
        val bytesRead = min(p0.limit() - p0.arrayOffset(), length - buffer.position())
        buffer.get(p0.array(), p0.arrayOffset(), bytesRead)
        return if (bytesRead == 0) -1 else bytesRead
    }

    override fun write(p0: ByteBuffer): Int {
        val end = buffer.position() + p0.limit()
        ensure(end)
        val position = buffer.position()
        buffer.position(length)
        val bytesRead = p0.limit() - p0.arrayOffset()
        buffer.put(p0.array(), p0.arrayOffset(), bytesRead)
        length += bytesRead
        buffer.position(position)
        return p0.array().size
    }

    override fun position(): Long = buffer.position().toLong()

    override fun position(p0: Long): SeekableByteChannel {
        buffer.position(p0.toInt())
        return this
    }

    override fun size(): Long {
        // TODO: Module execution fails when split into two reads.
        // Remove `fetchMoreData()` below to see the error.
        if (buffer.position() == length) fetchMoreData()
        return length.toLong()
    }

    override fun truncate(p0: Long): SeekableByteChannel {
        length = p0.toInt()
        buffer.limit(length)
        buffer.compact()
        return this
    }
}

class ReadOnlyBufferChannel(val buffer: ByteBuffer) : SeekableByteChannel {
    override fun close() {}

    override fun isOpen(): Boolean = false

    override fun read(p0: ByteBuffer): Int {
        val bytesRead = min(p0.limit() - p0.arrayOffset(), buffer.limit() - buffer.position())
        buffer.get(p0.array(), p0.arrayOffset(), bytesRead)
        return if (bytesRead == 0) -1 else bytesRead
    }

    override fun write(p0: ByteBuffer): Int {
        val position = buffer.position()
        buffer.position(buffer.limit())
        val bytesRead = p0.limit() - p0.arrayOffset()
        buffer.put(p0.array(), p0.arrayOffset(), bytesRead)
        buffer.position(position)
        return p0.array().size
    }

    override fun position(): Long = buffer.position().toLong()

    override fun position(p0: Long): SeekableByteChannel {
        buffer.position(p0.toInt())
        return this
    }

    override fun size(): Long {
        return buffer.limit().toLong()
    }

    override fun truncate(p0: Long): SeekableByteChannel {
        buffer.limit(p0.toInt())
        buffer.compact()
        return this
    }
}
