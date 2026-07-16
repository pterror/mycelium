package org.mycelium.library.registry

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import org.mycelium.library.MyceliumException

// Global, ecosystem-agnostic on-disk package cache — downloads a package's release archive
// (npm tarball today; any future PackageIndex implementation's own archive format
// tomorrow — PyPI wheels/sdists, RubyGems .gem files, crates.io .crate files, the Go module
// proxy's .zip files all follow the exact same "GET a URL, verify a checksum, unpack" shape)
// once, verifies it, and extracts it to a stable path other code can `require()`/import
// straight out of or hardlink from. Mirrors rhizone's on-disk cache layout
// (~/.cache/normalize/...) but keyed by ecosystem/name/version rather than by index name,
// since here the cache holds *unpacked packages* rather than *index documents* (that part
// is HttpMetadataCache's job).
object PackageCache {
    private val baseCacheDir = File(
        System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
        "mycelium/packages"
    )

    // --- System-aware performance tuning -----------------------------------------------
    // Sized from what the JVM actually has available rather than a fixed constant, so a
    // small container doesn't get starved trying to run 64 concurrent downloads/extractions
    // (each of which holds open sockets + native gzip/tar buffers) while a beefy dev machine
    // doesn't leave throughput on the table capped at some conservative default.
    private val availableProcessors = Runtime.getRuntime().availableProcessors()

    // I/O buffer size for the direct ByteBuffer used to shuttle bytes from the tar entry
    // stream to disk (see writeEntry below): bigger buffers mean fewer read()/write() calls
    // per file, which matters more when there's headroom to spare it.
    private val ioBufferSize: Int = run {
        val maxMemory = Runtime.getRuntime().maxMemory()
        when {
            maxMemory >= 2L * 1024 * 1024 * 1024 -> 1 shl 20 // 1 MiB — generous heap
            maxMemory >= 512L * 1024 * 1024 -> 256 * 1024
            else -> 64 * 1024 // conservative default for constrained heaps
        }
    }

    // Upper bound on simultaneous downloads+extractions: virtual threads (cheap, JDK 21+)
    // handle the *scheduling* fine even at high counts, but each in-flight operation still
    // holds a live HTTP connection and its own ioBufferSize-sized direct buffer, so the cap
    // exists to bound total off-heap memory and outbound-connection pressure, not thread
    // count. Scales with core count (more cores -> the machine can usefully drive more
    // concurrent I/O) but is halved when the heap is small, since a tiny heap usually means
    // a resource-constrained environment overall.
    private val maxConcurrentOperations: Int = run {
        val base = (availableProcessors * 4).coerceIn(4, 64)
        if (Runtime.getRuntime().maxMemory() < 512L * 1024 * 1024) (base / 2).coerceAtLeast(2) else base
    }

    private val downloadSemaphore = Semaphore(maxConcurrentOperations)

    // One shared virtual-thread-per-task executor for parallel ensurePackage() calls
    // (ensurePackages below, or any other concurrent caller) — virtual threads (stable since
    // JDK 21, which is this project's toolchain target) park cheaply while blocked on
    // network I/O, so there's no need for a bounded platform-thread pool the way
    // CompletableFuture.supplyAsync's default ForkJoinPool would require; downloadSemaphore
    // above is what actually bounds concurrency.
    private val executor: Executor = Executors.newVirtualThreadPerTaskExecutor()

    private val httpClient: HttpClient = NpmRegistryClient.defaultHttpClient()

    private fun safePathSegment(name: String): String =
        name.replace("..", "__")

    // Where a resolved (ecosystem, name, version) triple lives on disk once extracted — the
    // directory that directly contains the package's own root files (e.g. package.json for
    // npm), i.e. with the tarball's own top-level "package/" directory already stripped.
    // Scoped npm names (@scope/pkg) naturally become nested directories (@scope/pkg/version),
    // matching how node_modules itself lays out scoped packages.
    fun installDir(ecosystem: String, name: String, version: String): Path =
        File(baseCacheDir, "$ecosystem/${safePathSegment(name)}/${safePathSegment(version)}").toPath()

    private fun metaSidecarFile(dir: Path): File = File(dir.parent.toFile(), "${dir.fileName}.meta.json")

    fun isCached(ecosystem: String, name: String, version: String): Boolean =
        Files.isDirectory(installDir(ecosystem, name, version))

    // Ensures `name`@`version` (or, when `version` is null, whatever the index resolves as
    // default) is downloaded, verified, and extracted, returning its install directory.
    // Idempotent and safe under concurrent callers (including other processes): extraction
    // happens in a sibling temp dir that's only renamed into place — atomically — once fully
    // populated and checksum-verified, so a killed/crashed process can never leave behind a
    // partial extraction that a later call mistakes for complete.
    fun ensurePackage(index: PackageIndex, name: String, version: String? = null): Path {
        val meta = index.fetchMeta(name, version)
        return ensurePackage(index.ecosystem, meta)
    }

    // Same as above, but for callers that already have a resolved PackageMeta in hand (e.g.
    // dependency-tree installation, where the meta was already fetched once to discover its
    // own `dependencies` and re-fetching it here would be a wasted round trip).
    fun ensurePackage(ecosystem: String, meta: PackageMeta): Path {
        val dir = installDir(ecosystem, meta.name, meta.version)
        if (isCached(ecosystem, meta.name, meta.version)) return dir

        downloadSemaphore.acquire()
        try {
            // Another thread/process may have finished populating `dir` while we were
            // waiting on the semaphore — recheck before paying for a redundant download.
            if (isCached(ecosystem, meta.name, meta.version)) return dir
            downloadAndExtract(meta, dir)
        } finally {
            downloadSemaphore.release()
        }
        return dir
    }

    // Parallel variant: resolves and downloads several packages concurrently (bounded by
    // downloadSemaphore/maxConcurrentOperations above, not by the number of requests), for
    // callers that already know they need N packages up front — e.g. a compiler package
    // plus its direct dependencies — rather than serializing N round trips.
    fun ensurePackages(index: PackageIndex, requests: List<Pair<String, String?>>): List<Path> {
        val futures = requests.map { (name, version) ->
            CompletableFuture.supplyAsync({ ensurePackage(index, name, version) }, executor)
        }
        return futures.map { it.join() }
    }

    private fun downloadAndExtract(meta: PackageMeta, dir: Path) {
        val parentDir = dir.parent
        Files.createDirectories(parentDir)
        val tempDir = Files.createTempDirectory(parentDir, "download-${safePathSegment(meta.version)}-")
        try {
            val request = HttpRequest.newBuilder(URI.create(meta.tarballUrl))
                .header("User-Agent", "mycelium (pure-kotlin npm client)")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                throw MyceliumException(
                    "mycelium: failed to download ${meta.name}@${meta.version} " +
                        "(HTTP ${response.statusCode()} from ${meta.tarballUrl})"
                )
            }

            val digestAlgorithm = meta.checksum?.substringBefore(':')?.let(::algorithmName)
            val digest = digestAlgorithm?.let { MessageDigest.getInstance(it) }
            // Wrap the *raw compressed* stream (before gunzip/untar) in the digest, exactly
            // matching what npm's own dist.shasum/dist.integrity hash — the .tgz file's raw
            // bytes, not its extracted contents.
            val rawStream: InputStream =
                if (digest != null) DigestInputStream(response.body(), digest) else response.body()

            rawStream.use {
                extractTarGz(it, tempDir)

                // Drain any bytes the tar reader didn't need (end-of-archive padding, gzip
                // trailer) so the digest covers the entire downloaded file, matching how the
                // registry computed it in the first place.
                it.readAllBytes()
            }

            if (digest != null) {
                val expected = meta.checksum!!.substringAfter(':')
                if (!digestMatches(digest.digest(), expected)) {
                    throw MyceliumException(
                        "mycelium: checksum mismatch downloading ${meta.name}@${meta.version} " +
                            "(expected ${meta.checksum})"
                    )
                }
            }

            // If another process/thread won the race and already populated `dir` while we
            // were downloading, prefer its result over ours (both are equally valid — same
            // verified content) rather than erroring.
            if (!Files.isDirectory(dir)) {
                Files.move(tempDir, dir, StandardCopyOption.ATOMIC_MOVE)
            }
            writeMetaSidecar(dir, meta)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun algorithmName(prefix: String): String? = when (prefix.lowercase()) {
        "sha1" -> "SHA-1"
        "sha256" -> "SHA-256"
        "sha512" -> "SHA-512"
        else -> null
    }

    // npm's `dist.shasum` is hex-encoded; `dist.integrity` (SRI) hashes are base64-encoded.
    // NpmRegistryClient.parseVersion doesn't tag which encoding a given checksum string
    // uses, so detect it from the alphabet instead: SRI base64 payloads almost always
    // contain characters outside [0-9a-f] (mixed case, '+', '/', '='), whereas legacy hex
    // shasums are exactly 40 lowercase hex characters.
    private fun digestMatches(actual: ByteArray, expected: String): Boolean {
        val expectedBytes = if (expected.matches(Regex("^[0-9a-fA-F]+$")) && expected.length % 2 == 0) {
            expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            try {
                Base64.getDecoder().decode(expected)
            } catch (e: IllegalArgumentException) {
                return false
            }
        }
        return actual.contentEquals(expectedBytes)
    }

    // Wraps a stream so closing the wrapper (which GzipCompressorInputStream/
    // TarArchiveInputStream do automatically once their own `.use{}` block exits) doesn't
    // close the underlying stream — needed so the caller can keep reading from the raw
    // digest-wrapped stream afterwards (to drain trailing bytes the tar reader didn't need,
    // so the digest covers the whole downloaded file) without it having been closed out
    // from under it.
    private class UncloseableInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {}
    }

    // Streams the gzip'd tarball straight from the network into extracted files on disk —
    // never buffers the whole archive (compressed or extracted) in memory. Strips the
    // tarball's own top-level directory (npm tarballs are always rooted at "package/") so
    // `dir` ends up containing the package's files directly.
    private fun extractTarGz(rawStream: InputStream, destDir: Path) {
        GzipCompressorInputStream(UncloseableInputStream(rawStream)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry as TarArchiveEntry?
                while (entry != null) {
                    val name = entry.name.substringAfter('/', missingDelimiterValue = "")
                    if (name.isNotEmpty()) {
                        val dest = destDir.resolve(name).normalize()
                        if (!dest.startsWith(destDir)) {
                            throw MyceliumException("mycelium: tar entry escapes extraction dir: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            Files.createDirectories(dest)
                        } else {
                            Files.createDirectories(dest.parent)
                            writeEntry(tar, dest)
                            if (entry.mode and 0b001_000_000 != 0) {
                                dest.toFile().setExecutable(true)
                            }
                        }
                    }
                    entry = tar.nextEntry as TarArchiveEntry?
                }
            }
        }
    }

    // Copies one tar entry's content to `dest` using a direct ByteBuffer for the
    // FileChannel write — avoids an extra on-heap copy the JVM would otherwise make when
    // handing a heap byte[] to a native write() syscall (direct buffers can be handed to
    // the OS as-is).
    private fun writeEntry(input: InputStream, dest: Path) {
        FileChannel.open(
            dest,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val heapBuf = ByteArray(ioBufferSize)
            val direct = ByteBuffer.allocateDirect(ioBufferSize)
            while (true) {
                val n = input.read(heapBuf)
                if (n < 0) break
                direct.clear()
                direct.put(heapBuf, 0, n)
                direct.flip()
                while (direct.hasRemaining()) channel.write(direct)
            }
        }
    }

    private fun writeMetaSidecar(dir: Path, meta: PackageMeta) {
        val json = JSONObject()
        json.put("name", meta.name)
        json.put("version", meta.version)
        meta.checksum?.let { json.put("checksum", it) }
        json.put("tarballUrl", meta.tarballUrl)
        json.put("cachedAt", Instant.now().epochSecond)
        try {
            metaSidecarFile(dir).writeText(json.toString())
        } catch (e: java.io.IOException) {
            // The sidecar is purely informational (nothing reads it back yet); losing it
            // shouldn't fail an otherwise-successful install.
        }
    }

    // Installs a cached package into a project's node_modules by hardlinking every file from
    // the cache rather than copying — the same "content-addressed store + hardlinks/reflinks
    // into node_modules" trick bun and pnpm use to make repeated installs of the same
    // version across projects nearly free. Falls back to a plain copy per-file if
    // Files.createLink fails (e.g. the cache and the target node_modules live on different
    // filesystems/devices, where hardlinks are impossible).
    fun installInto(cacheDir: Path, targetNodeModulesDir: Path, packageName: String) {
        val dest = targetNodeModulesDir.resolve(packageName)
        Files.createDirectories(dest)
        Files.walk(cacheDir).use { paths ->
            paths.sorted().forEach { source ->
                val relative = cacheDir.relativize(source)
                val target = dest.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    if (!Files.exists(target)) {
                        try {
                            Files.createLink(target, source)
                        } catch (e: java.io.IOException) {
                            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
            }
        }
    }
}
