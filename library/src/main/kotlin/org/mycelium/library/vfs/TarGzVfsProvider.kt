package org.mycelium.library.vfs

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

// Handles gzip-compressed tar archives (.tar.gz, .tgz). Unlike ZipVfsProvider, tar has no
// central directory to seek into — TarArchiveInputStream must walk every entry's header to
// find the next one, and gzip on top means even byte-range seeking within the compressed
// stream isn't possible. Every list/read/attributes call below re-decompresses and re-scans
// from the start; callers that need repeated lookups against the same archive should cache
// results themselves (VfsPathResolver already does path-segment-level caching upstream).
class TarGzVfsProvider : VfsProvider {
    override fun canOpen(channel: SeekableByteChannel): Boolean {
        val originalPosition = channel.position()
        try {
            channel.position(0)
            val header = ByteBuffer.allocate(2)
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) return false
            }
            // 0x1f 0x8b — the gzip magic number. Doesn't distinguish a .tar.gz from any other
            // gzipped file, but VfsProviderRegistry only reaches this provider once the
            // resolver has already decided the path segment names an archive to open.
            return header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
        } finally {
            channel.position(originalPosition)
        }
    }

    override fun list(channel: SeekableByteChannel, internalPath: String): List<VfsEntry> {
        val prefix = normalize(internalPath).let { if (it.isEmpty()) it else "$it/" }
        val children = LinkedHashMap<String, Boolean>()
        withTarStream(channel) { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (name.startsWith(prefix) && name != prefix.trimEnd('/')) {
                    val rest = name.removePrefix(prefix)
                    val segment = rest.substringBefore('/')
                    // Once true (an implicit-directory sighting), never downgrade back to false.
                    children[segment] = children[segment] == true || entry.isDirectory || rest.contains('/')
                }
                entry = tis.nextEntry
            }
        }
        return children.map { (name, isDir) -> VfsEntry(name, isDir) }
    }

    override fun read(channel: SeekableByteChannel, internalPath: String): ByteArray {
        val target = normalize(internalPath)
        var result: ByteArray? = null
        withTarStream(channel) { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.trimEnd('/') == target) {
                    result = tis.readBytes()
                    break
                }
                entry = tis.nextEntry
            }
        }
        return result ?: throw NoSuchFileException(internalPath)
    }

    override fun attributes(channel: SeekableByteChannel, internalPath: String): VfsEntryAttributes? {
        val target = normalize(internalPath)
        if (target.isEmpty()) return VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
        var result: VfsEntryAttributes? = null
        withTarStream(channel) { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                val name = entry.name.trimEnd('/')
                if (name == target) {
                    result = VfsEntryAttributes(
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        lastModified = entry.lastModifiedDate.time,
                    )
                    break
                }
                // Implicit directory: some tars never write an explicit directory entry for
                // a path that files are nonetheless nested under.
                if (result == null && name.startsWith("$target/")) {
                    result = VfsEntryAttributes(isDirectory = true, size = 0, lastModified = 0)
                }
                entry = tis.nextEntry
            }
        }
        return result
    }

    private fun normalize(internalPath: String): String = internalPath.trim('/')

    // Wraps the channel so closing the TarArchiveInputStream/GZIPInputStream chain (needed to
    // release the Inflater) doesn't also close the caller's channel, which may be reused for
    // later calls.
    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() {}
    }

    private fun withTarStream(channel: SeekableByteChannel, block: (TarArchiveInputStream) -> Unit) {
        channel.position(0)
        TarArchiveInputStream(GZIPInputStream(NonClosingInputStream(Channels.newInputStream(channel)))).use(block)
    }
}
