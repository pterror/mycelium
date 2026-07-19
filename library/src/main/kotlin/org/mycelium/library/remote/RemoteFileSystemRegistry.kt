package org.mycelium.library.remote

// Simple scheme -> provider lookup, mirroring VfsProviderRegistry's "built-ins registered
// up front, `register` open for consumers to add more" shape — except keyed by URI scheme
// (known up front from the import specifier itself) rather than probed via magic bytes, so
// a plain map lookup suffices instead of VfsProviderRegistry's ordered canOpen probing.
// Empty by default: no built-in RemoteFileSystem ships yet (see RemoteFileSystem's own doc
// comment) — this registry exists so a provider (e.g. a future S3 implementation) can be
// registered without InterceptingFileSystem itself needing to know about it.
class RemoteFileSystemRegistry {
    private val providersByScheme = mutableMapOf<String, RemoteFileSystem>()

    fun register(provider: RemoteFileSystem) {
        for (scheme in provider.schemes) {
            providersByScheme[scheme] = provider
        }
    }

    fun forScheme(scheme: String): RemoteFileSystem? = providersByScheme[scheme]
}
