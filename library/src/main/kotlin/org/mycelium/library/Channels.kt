package org.mycelium.library

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.min

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
