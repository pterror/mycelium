package org.mycelium.library.remote

import java.net.URI

// Extension point for protocol-based remote access (S3, GCS, WebDAV, ...) — distinct from
// vfs/VfsProvider, which presents *a local file's bytes* as a traversable tree (zip, tar,
// asar). A RemoteFileSystem instead owns the whole "fetch bytes/listing given a URI" round
// trip itself, since a remote store has no local file to open a channel on in the first
// place. Mirrors VfsProvider/LanguageSupport/PackageIndex's "typed registry of pluggable
// implementations" shape elsewhere in this module — register with
// RemoteFileSystemRegistry the same way to add a new provider without touching this
// interface. No built-in implementation ships yet; InterceptingFileSystem's existing
// http(s) import handling (HttpImportCache) predates this extension point and is left as
// its own special case rather than folded in here.
interface RemoteFileSystem {
    val schemes: List<String>

    fun read(uri: URI): ByteArray

    fun list(uri: URI): List<RemoteEntry>

    fun attributes(uri: URI): RemoteEntryAttributes?
}

data class RemoteEntry(val name: String, val isDirectory: Boolean)

data class RemoteEntryAttributes(val isDirectory: Boolean, val size: Long, val lastModified: Long)
