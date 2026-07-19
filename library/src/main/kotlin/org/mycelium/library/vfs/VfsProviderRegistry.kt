package org.mycelium.library.vfs

import java.nio.channels.SeekableByteChannel

// Ordered list of VfsProviders, probed via canOpen in registration order — mirrors
// CompilerRegistry's "built-ins registered up front, `register` open for consumers to add
// more" shape.
class VfsProviderRegistry {
    private val providers = mutableListOf<VfsProvider>()

    init {
        register(ZipVfsProvider())
        register(TarGzVfsProvider())
        // Asar has no magic bytes (see AsarVfsProvider.canOpen), so it's registered last —
        // it's only reached once every provider with a real signature has already declined.
        register(AsarVfsProvider())
    }

    fun register(provider: VfsProvider) {
        providers.add(provider)
    }

    fun findProvider(channel: SeekableByteChannel): VfsProvider? =
        providers.firstOrNull { it.canOpen(channel) }
}
