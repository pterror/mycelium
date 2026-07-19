package org.mycelium.library.registry

import org.mycelium.library.PackageEcosystem

// Maps each PackageEcosystem to the PackageInstaller (itself wrapping a PackageIndex —
// NpmRegistryClient/RubyGemsClient/PyPIClient in practice) that resolves and installs
// packages from it. Mirrors CompilerRegistry/VfsProviderRegistry's own register/dispatch
// shape: Mycelium (the composition root) constructs one PackageInstaller per ecosystem's
// PackageIndex and registers it here, rather than InterceptingFileSystem holding a
// separate nullable field per ecosystem. Not every ecosystem need be registered — a test
// exercising only the npm/JS path (e.g. PythonInteropExperimentTest) can construct a
// registry with just PackageEcosystem.NPM, the same way `get` returning null lets a
// caller treat an unregistered ecosystem as "no auto-install available" rather than a
// required dependency.
class PackageInstallerRegistry {
    private val installers = mutableMapOf<PackageEcosystem, PackageInstaller>()

    fun register(ecosystem: PackageEcosystem, installer: PackageInstaller) {
        installers[ecosystem] = installer
    }

    operator fun get(ecosystem: PackageEcosystem): PackageInstaller? = installers[ecosystem]
}
