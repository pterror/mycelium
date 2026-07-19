package org.mycelium.library.vfs

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import org.mycelium.library.ReadOnlyBufferChannel

data class PathStat(val exists: Boolean, val isDirectory: Boolean)

// Abstracts "stat a path" / "open a byte channel to a path" so VfsPathResolver can run
// the same algorithm against the real filesystem and, recursively, against an already-open
// provider's own internal paths (a zip nested inside a zip) — see resolveBoundaryAt.
interface VfsDelegate {
    fun stat(path: String): PathStat
    fun openChannel(path: String): SeekableByteChannel
}

data class VfsResolution(
    val vfsRootPath: String,
    val internalPath: String,
    val provider: VfsProvider,
    val channel: SeekableByteChannel,
)

// Finds where a real path stops and a provider's own internal path begins — e.g.
// "/proj/assets/bundle.zip/icons/logo.png" splits into vfsRootPath "/proj/assets/bundle.zip"
// and internalPath "icons/logo.png".
class VfsPathResolver(private val registry: VfsProviderRegistry) {
    fun resolve(fullPath: String, delegate: VfsDelegate): VfsResolution? {
        // Step 1: the whole path already resolves normally — no provider involved.
        if (delegate.stat(fullPath).exists) return null

        val segments = fullPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val prefix = if (fullPath.startsWith("/")) "/" else ""
        fun pathOf(count: Int): String = prefix + segments.take(count).joinToString("/")

        // Step 2: heuristic — the first segment containing a dot is very likely an archive
        // file (bundle.zip, whatever.jar) rather than a directory; probe it directly before
        // falling back to a full binary search.
        val dotIndex = segments.indexOfFirst { it.contains('.') }
        if (dotIndex >= 0) {
            val candidate = pathOf(dotIndex + 1)
            val stat = delegate.stat(candidate)
            if (stat.exists && !stat.isDirectory) {
                resolveBoundaryAt(candidate, segments, dotIndex + 1, delegate)?.let { return it }
            }
        }

        // Step 3: heuristic missed (not found, or a directory) — binary search the segment
        // boundary. Invariant going in: pathOf(0) (the vacuous root) always "exists";
        // pathOf(segments.size) (the full path) never does, per step 1 above.
        var lo = 0
        var hi = segments.size
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            val stat = delegate.stat(pathOf(mid))
            when {
                !stat.exists -> hi = mid
                stat.isDirectory -> lo = mid
                else -> return resolveBoundaryAt(pathOf(mid), segments, mid, delegate)
            }
        }
        if (lo == 0) return null
        return resolveBoundaryAt(pathOf(lo), segments, lo, delegate)
    }

    private fun resolveBoundaryAt(
        vfsRootPath: String,
        segments: List<String>,
        boundarySegmentCount: Int,
        delegate: VfsDelegate,
    ): VfsResolution? {
        val channel = delegate.openChannel(vfsRootPath)
        val provider = registry.findProvider(channel) ?: return null
        val internalPath = segments.drop(boundarySegmentCount).joinToString("/")

        // Step 5: nested archives — recurse over the remaining internalPath using the
        // just-resolved provider's own contents as the delegate for a second pass.
        val nestedDelegate = object : VfsDelegate {
            override fun stat(path: String): PathStat {
                val attrs = provider.attributes(channel, path.trim('/'))
                return if (attrs == null) PathStat(exists = false, isDirectory = false)
                else PathStat(exists = true, isDirectory = attrs.isDirectory)
            }

            override fun openChannel(path: String): SeekableByteChannel =
                ReadOnlyBufferChannel(ByteBuffer.wrap(provider.read(channel, path.trim('/'))))
        }
        val nested = resolve(internalPath, nestedDelegate)
        return if (nested != null) {
            VfsResolution(
                vfsRootPath = "$vfsRootPath/${nested.vfsRootPath.trimStart('/')}",
                internalPath = nested.internalPath,
                provider = nested.provider,
                channel = nested.channel,
            )
        } else {
            VfsResolution(vfsRootPath, internalPath, provider, channel)
        }
    }
}
