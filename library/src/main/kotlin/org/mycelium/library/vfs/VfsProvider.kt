package org.mycelium.library.vfs

import java.nio.channels.SeekableByteChannel

// Extension point for anything that can present a file as a traversable filesystem tree —
// archive formats InterceptingFileSystem can transparently look inside are the case in point
// (e.g. "read a file that lives at /project/assets/bundle.zip/icons/logo.png" without the
// caller needing to know bundle.zip is a zip rather than a directory) — mirrors
// LanguageSupport/CompilerRegistry/PackageIndex's "typed registry of pluggable
// implementations" shape elsewhere in this module. ZipVfsProvider is the only built-in
// implementation today; more (tar, 7z, ...) register with VfsProviderRegistry the same
// way without touching this interface.
interface VfsProvider {
    // Probe whether this provider can open the file backing `channel` — must check magic
    // bytes rather than trusting a file extension, since VfsPathResolver doesn't know
    // the file's extension ahead of time (it finds the boundary by stat-ing path
    // segments, not by name).
    fun canOpen(channel: SeekableByteChannel): Boolean

    // Lists the direct children of `internalPath` (a provider-relative path, "" for the
    // root, no leading slash).
    fun list(channel: SeekableByteChannel, internalPath: String): List<VfsEntry>

    // Reads the full contents of the file at `internalPath`.
    fun read(channel: SeekableByteChannel, internalPath: String): ByteArray

    // Attributes for `internalPath`, or null if it doesn't exist within the tree.
    fun attributes(channel: SeekableByteChannel, internalPath: String): VfsEntryAttributes?
}

data class VfsEntry(val name: String, val isDirectory: Boolean)

data class VfsEntryAttributes(val isDirectory: Boolean, val size: Long, val lastModified: Long)
