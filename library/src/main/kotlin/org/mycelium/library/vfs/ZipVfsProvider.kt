package org.mycelium.library.vfs

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.util.zip.ZipInputStream

// Handles ordinary ZIP archives (and anything ZIP-compatible: .jar, .asar-like formats that
// are just ZIPs under a different extension). Reads are a full sequential scan via
// ZipInputStream rather than java.util.zip.ZipFile's random access, since ZipFile needs a
// real File on disk — a SeekableByteChannel backed by e.g. an already-extracted nested
// archive's in-memory bytes doesn't have one.
class ZipVfsProvider : VfsProvider {
    override fun canOpen(channel: SeekableByteChannel): Boolean {
        val originalPosition = channel.position()
        try {
            channel.position(0)
            val header = ByteBuffer.allocate(4)
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) return false
            }
            // "PK\x03\x04" (local file header) — the signature ZipInputStream itself scans
            // for. The rarer "PK\x05\x06"/"PK\x07\x08" (empty/spanned archive) signatures
            // aren't handled by ZipInputStream and are skipped.
            return header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 3.toByte() && header[3] == 4.toByte()
        } finally {
            channel.position(originalPosition)
        }
    }

    override fun list(channel: SeekableByteChannel, internalPath: String): List<VfsEntry> {
        val prefix = normalize(internalPath).let { if (it.isEmpty()) it else "$it/" }
        val children = LinkedHashMap<String, Boolean>()
        withZipStream(channel) { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (name.startsWith(prefix) && name != prefix.trimEnd('/')) {
                    val rest = name.removePrefix(prefix)
                    val segment = rest.substringBefore('/')
                    val isDir = entry.isDirectory || rest.contains('/')
                    // Once true (an implicit-directory sighting), never downgrade back to false.
                    children[segment] = children[segment] == true || isDir
                }
                entry = zis.nextEntry
            }
        }
        return children.map { (name, isDir) -> VfsEntry(name, isDir) }
    }

    override fun read(channel: SeekableByteChannel, internalPath: String): ByteArray {
        val target = normalize(internalPath)
        var result: ByteArray? = null
        withZipStream(channel) { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.trimEnd('/') == target) {
                    result = zis.readBytes()
                    break
                }
                entry = zis.nextEntry
            }
        }
        return result ?: throw NoSuchFileException(internalPath)
    }

    override fun attributes(channel: SeekableByteChannel, internalPath: String): VfsEntryAttributes? {
        val target = normalize(internalPath)
        if (target.isEmpty()) return VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
        var result: VfsEntryAttributes? = null
        withZipStream(channel) { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (name == target) {
                    result = VfsEntryAttributes(
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        lastModified = entry.time,
                    )
                    break
                }
                // Implicit directory: some zips never write an explicit directory entry for
                // a path that files are nonetheless nested under.
                if (result == null && name.startsWith("$target/")) {
                    result = VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    private fun normalize(internalPath: String): String = internalPath.trim('/')

    // Wraps the channel so closing the ZipInputStream (needed to release its Inflater)
    // doesn't also close the caller's channel, which may be reused for later calls.
    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {}
    }

    private fun withZipStream(channel: SeekableByteChannel, block: (ZipInputStream) -> Unit) {
        channel.position(0)
        ZipInputStream(NonClosingInputStream(Channels.newInputStream(channel))).use(block)
    }
}
