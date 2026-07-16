package org.mycelium.library.registry

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
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
import java.nio.file.FileAlreadyExistsException
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
    private val xdgCacheHome = System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path

    private val baseCacheDir = File(xdgCacheHome, "mycelium/packages")

    // Content-addressable store: every regular file extracted from any package, of any
    // ecosystem/name/version, ends up here exactly once, named by its own SHA-256 — the same
    // trick pnpm's store uses. baseCacheDir above still holds one directory per (ecosystem,
    // name, version) so existing lookups (installDir/isCached/the .meta.json sidecar) keep
    // working unchanged; after extraction each file in that directory is replaced by a
    // hardlink into casBaseDir, so identical file contents shared across versions/packages
    // (lodash@4.17.21 vs 4.17.20, or the same package depended on from two projects) are
    // stored on disk exactly once.
    private val casBaseDir = File(xdgCacheHome, "mycelium/cas")

    // Sidecar recorded alongside a package's extracted files (inside its own install
    // directory, unlike the .meta.json sidecar which lives beside it) mapping every relative
    // path back to the CAS entry (by hash) it was replaced with, plus whether it needs the
    // executable bit set on install (see CasManifestEntry below for why permissions can't
    // just be read off the CAS file's own inode).
    private const val CAS_MANIFEST_FILENAME = ".cas-manifest.json"

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
            downloadAndExtract(ecosystem, meta, dir)
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

    // Which unpacking strategy a downloaded archive needs, keyed off the tarball/dist URL's
    // own file extension (the one detail every PackageIndex implementation's `tarballUrl`
    // reliably encodes) plus, for the two ecosystems that both use ZIP containers but lay
    // them out differently, the ecosystem string itself:
    //   - npm .tgz, crates.io .crate, and a PyPI sdist .tar.gz are all a gzip'd tarball
    //     rooted at a single top-level directory ("package/", "{name}-{version}/", ...) —
    //     TAR_GZ, stripping that one shared ancestor (extractTarGz already does this
    //     generically, keyed off "everything before the first '/'" rather than any
    //     hardcoded npm-specific name).
    //   - A PyPI wheel (.whl) is a plain ZIP with no shared top-level directory at all — its
    //     entries are already rooted at the final layout PackageCache.installInto should
    //     produce (e.g. "requests/api.py", "requests-2.31.0.dist-info/METADATA") — so ZIP
    //     with no stripping.
    //   - A Go module .zip (from the module proxy) is a ZIP *with* one shared top-level
    //     directory ("example.com/foo@v1.2.3/") to strip, same shape as TAR_GZ just under a
    //     different container format.
    //   - A RubyGems .gem is a plain (uncompressed) outer tar containing metadata.gz,
    //     checksums.yaml.gz, and — the part PackageCache actually needs — a nested
    //     data.tar.gz whose own entries are already rooted at the gem's real layout
    //     ("lib/foo.rb", "exe/foo", ...) with no wrapping directory of their own.
    private enum class ArchiveFormat { TAR_GZ, ZIP, ZIP_STRIP_TOP, GEM }

    private fun archiveFormatFor(ecosystem: String, url: String): ArchiveFormat = when {
        url.endsWith(".gem") -> ArchiveFormat.GEM
        url.endsWith(".whl") -> ArchiveFormat.ZIP
        url.endsWith(".zip") && ecosystem == "go" -> ArchiveFormat.ZIP_STRIP_TOP
        url.endsWith(".zip") -> ArchiveFormat.ZIP
        else -> ArchiveFormat.TAR_GZ
    }

    private fun downloadAndExtract(ecosystem: String, meta: PackageMeta, dir: Path) {
        val parentDir = dir.parent
        Files.createDirectories(parentDir)
        val tempDir = Files.createTempDirectory(parentDir, "download-${safePathSegment(meta.version)}-")
        try {
            val request = HttpRequest.newBuilder(URI.create(meta.tarballUrl))
                .header("User-Agent", "mycelium (pure-kotlin package registry client)")
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
            // Wrap the *raw* stream (before any unpacking) in the digest, exactly matching
            // what each registry's own checksum hashes — the downloaded archive's raw bytes,
            // not its extracted contents.
            val rawStream: InputStream =
                if (digest != null) DigestInputStream(response.body(), digest) else response.body()

            val format = archiveFormatFor(ecosystem, meta.tarballUrl)
            rawStream.use {
                when (format) {
                    ArchiveFormat.TAR_GZ -> extractTarGz(it, tempDir)
                    ArchiveFormat.ZIP -> extractZip(it, tempDir, stripTopLevel = false)
                    ArchiveFormat.ZIP_STRIP_TOP -> extractZip(it, tempDir, stripTopLevel = true)
                    ArchiveFormat.GEM -> extractGem(it, tempDir)
                }

                // Drain any bytes the reader didn't need (end-of-archive padding, gzip
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

            // Fold every extracted file into the content-addressable store *before* tempDir
            // becomes visible as `dir` — this way a concurrent reader that only ever sees
            // fully-extracted, atomically-renamed package directories (the same invariant
            // downloadAndExtract already gave callers pre-CAS) also only ever sees packages
            // whose CAS manifest is already complete.
            populateCas(tempDir)

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
    // tarball's own top-level directory (npm tarballs are rooted at "package/", PyPI sdists
    // and crates.io .crate files at "{name}-{version}/") so `dir` ends up containing the
    // package's files directly.
    private fun extractTarGz(rawStream: InputStream, destDir: Path) {
        GzipCompressorInputStream(UncloseableInputStream(rawStream)).use { gzip ->
            extractTarEntries(gzip, destDir, stripTopLevel = true)
        }
    }

    // Shared tar-entry-walking loop, factored out so extractTarGz (npm/PyPI-sdist/crates.io,
    // where every entry is rooted under one shared top-level directory to strip) and
    // extractGem's inner data.tar.gz (whose entries are already rooted at the gem's real
    // "lib/", "exe/", ... layout, nothing to strip) can share it.
    private fun extractTarEntries(tarStream: InputStream, destDir: Path, stripTopLevel: Boolean) {
        TarArchiveInputStream(tarStream).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry as TarArchiveEntry?
            while (entry != null) {
                val name = if (stripTopLevel) {
                    entry.name.substringAfter('/', missingDelimiterValue = "")
                } else {
                    entry.name
                }
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

    // Streams a ZIP archive (a PyPI wheel, or a Go module proxy source archive) straight
    // from the network into extracted files on disk. `stripTopLevel` mirrors extractTarGz's
    // own parameter: false for a wheel (already rooted at its real layout, e.g.
    // "requests/api.py", "requests-2.31.0.dist-info/METADATA" — no shared wrapping
    // directory), true for a Go module zip (every entry rooted under one shared
    // "example.com/foo@v1.2.3/" directory the module proxy always adds).
    private fun extractZip(rawStream: InputStream, destDir: Path, stripTopLevel: Boolean) {
        ZipArchiveInputStream(rawStream).use { zip ->
            var entry: ZipArchiveEntry? = zip.nextEntry as ZipArchiveEntry?
            while (entry != null) {
                val name = if (stripTopLevel) {
                    entry.name.substringAfter('/', missingDelimiterValue = "")
                } else {
                    entry.name
                }
                if (name.isNotEmpty() && !entry.isDirectory) {
                    val dest = destDir.resolve(name).normalize()
                    if (!dest.startsWith(destDir)) {
                        throw MyceliumException("mycelium: zip entry escapes extraction dir: ${entry.name}")
                    }
                    Files.createDirectories(dest.parent)
                    writeEntry(zip, dest)
                    // ZipArchiveEntry's unix mode is only populated when the archive was
                    // created on a POSIX system (the "unix" external attributes field) —
                    // wheels built on Windows won't have it, which is fine: falling back to
                    // "not executable" is the same default a fresh file gets anyway.
                    if (entry.unixMode and 0b001_000_000 != 0) {
                        dest.toFile().setExecutable(true)
                    }
                } else if (name.isNotEmpty()) {
                    Files.createDirectories(destDir.resolve(name).normalize())
                }
                entry = zip.nextEntry as ZipArchiveEntry?
            }
        }
    }

    // A `.gem` file is itself a plain (uncompressed) tar containing metadata.gz,
    // checksums.yaml.gz, and data.tar.gz — only the last of which PackageCache needs: it's
    // the gem's actual installable payload ("lib/foo.rb", "exe/foo", ...), gzip-compressed
    // but — unlike npm/PyPI/crates.io tarballs — not wrapped in any shared top-level
    // directory of its own, so it's extracted with stripTopLevel = false.
    private fun extractGem(rawStream: InputStream, destDir: Path) {
        TarArchiveInputStream(UncloseableInputStream(rawStream)).use { outerTar ->
            var entry: TarArchiveEntry? = outerTar.nextEntry as TarArchiveEntry?
            while (entry != null) {
                if (entry.name == "data.tar.gz") {
                    GzipCompressorInputStream(UncloseableInputStream(outerTar)).use { gzip ->
                        extractTarEntries(gzip, destDir, stripTopLevel = false)
                    }
                    return
                }
                entry = outerTar.nextEntry as TarArchiveEntry?
            }
            throw MyceliumException("mycelium: .gem archive has no data.tar.gz entry")
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

    // One CAS manifest entry: the SHA-256 (hex) of a file's contents, plus whether the
    // extracted file had the executable bit set. The executable bit can't simply be read off
    // whatever CAS entry ends up holding this content at install time, because a hardlink
    // shares its *inode* — and therefore its permission bits — with every other hardlink to
    // the same inode. If an executable script and a non-executable file elsewhere happened to
    // have byte-identical contents, storing them under the same CAS path and later chmod'ing
    // one consumer's copy would silently flip permissions for every other consumer sharing
    // that inode. casPathFor sidesteps this by keying the executable and non-executable
    // variants of the same content to *different* CAS paths (an ".exec" suffix) rather than
    // mutating shared inodes after the fact, and this manifest records which variant each
    // relative path needs so install-time linking picks the right one.
    private data class CasManifestEntry(val hash: String, val executable: Boolean)

    // cas/<hash[0:2]>/<hash[2:4]>/<hash>[.exec] — two levels of sharding (256 * 256 possible
    // subdirectories) keeps any single directory from accumulating enough entries to slow
    // down filesystem lookups even once the store holds millions of files.
    private fun casPathFor(hash: String, executable: Boolean): Path {
        val suffix = if (executable) ".exec" else ""
        return File(casBaseDir, "${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash$suffix").toPath()
    }

    private fun sha256Hex(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(ioBufferSize)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // Walks every regular file under a freshly-extracted package directory, stores each one's
    // content in the CAS (deduplicating against whatever's already there, from this package
    // version, a different version, or an entirely different package), replaces the file in
    // place with a hardlink to its CAS entry, and records a manifest of relative-path -> CAS
    // entry so a later installInto (of this package, or a fresh process that never re-extracts
    // it) can hardlink straight from the CAS rather than needing to re-walk/re-hash anything.
    private fun populateCas(extractedDir: Path) {
        val manifest = sortedMapOf<String, CasManifestEntry>()
        Files.walk(extractedDir).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                val relative = extractedDir.relativize(file).toString()
                val executable = file.toFile().canExecute()
                val hash = storeInCas(file, executable)
                manifest[relative] = CasManifestEntry(hash, executable)
            }
        }
        writeManifest(extractedDir, manifest)
    }

    // Computes `file`'s content hash, ensures the CAS holds a copy of it (writing one — via a
    // temp file that's hashed/finalized before an atomic rename into place — only if no other
    // thread/process has already done so for this exact hash+executable-bit combination), and
    // then replaces `file` in place with a hardlink to that CAS entry. Returns the hash for the
    // manifest.
    private fun storeInCas(file: Path, executable: Boolean): String {
        val hash = sha256Hex(file)
        val casTarget = casPathFor(hash, executable)
        if (!Files.exists(casTarget)) {
            Files.createDirectories(casTarget.parent)
            val temp = Files.createTempFile(casTarget.parent, "cas-", ".tmp")
            try {
                Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING)
                if (executable) temp.toFile().setExecutable(true)
                try {
                    // Atomic rename into its final content-addressed name — this is the one
                    // moment a partially-written file could otherwise be observed by a
                    // concurrent reader, and ATOMIC_MOVE rules that out entirely.
                    Files.move(temp, casTarget, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: FileAlreadyExistsException) {
                    // Another thread/process finished writing this exact hash first —
                    // its content is byte-identical (same hash), so ours is redundant.
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        // Replace `file` with a hardlink to the CAS entry without ever leaving `file` missing
        // if linking fails partway: link into a sibling temp path first, then atomically rename
        // that temp path over `file`. Falls back to leaving `file`'s own (already-correct)
        // content in place — un-deduplicated, but correct — if hardlinks aren't supported
        // between the package cache and the CAS (e.g. they end up on different filesystems).
        val tempLink = file.resolveSibling("${file.fileName}.caslink.tmp")
        try {
            Files.deleteIfExists(tempLink)
            Files.createLink(tempLink, casTarget)
            Files.move(tempLink, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            Files.deleteIfExists(tempLink)
        }
        return hash
    }

    private fun manifestFile(packageDir: Path): File = File(packageDir.toFile(), CAS_MANIFEST_FILENAME)

    private fun writeManifest(packageDir: Path, manifest: Map<String, CasManifestEntry>) {
        val filesJson = JSONObject()
        for ((relative, entry) in manifest) {
            val entryJson = JSONObject()
            entryJson.put("hash", entry.hash)
            entryJson.put("executable", entry.executable)
            filesJson.put(relative, entryJson)
        }
        val root = JSONObject()
        root.put("files", filesJson)
        manifestFile(packageDir).writeText(root.toString())
    }

    private fun readManifest(packageDir: Path): Map<String, CasManifestEntry>? {
        val file = manifestFile(packageDir)
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val filesJson = root.optJSONObject("files") ?: return emptyMap()
        val manifest = linkedMapOf<String, CasManifestEntry>()
        for (relative in filesJson.keySet()) {
            val entryJson = filesJson.getJSONObject(relative)
            manifest[relative] = CasManifestEntry(
                hash = entryJson.getString("hash"),
                executable = entryJson.optBoolean("executable", false),
            )
        }
        return manifest
    }

    // Installs a cached package into a project's own dependency directory (node_modules for
    // npm, a vendor/gems-style directory for the other ecosystems — see PackageInstaller) by
    // hardlinking every file from the cache rather than copying — the same "content-addressed
    // store + hardlinks/reflinks into node_modules" trick bun and pnpm use to make repeated
    // installs of the same version across projects nearly free. Falls back to a plain copy
    // per-file if Files.createLink fails (e.g. the cache and the target directory live on
    // different filesystems/devices, where hardlinks are impossible).
    fun installInto(cacheDir: Path, targetDir: Path, packageName: String) {
        val dest = targetDir.resolve(packageName)
        Files.createDirectories(dest)

        // Packages extracted since the CAS was introduced carry a manifest recording each
        // file's content hash, so install straight from the CAS (one hop) rather than through
        // cacheDir's own copy (which is itself just a hardlink to the same CAS entry — going
        // via the manifest also means a fresh process never needs to re-walk/re-stat cacheDir
        // to find files, just read one small JSON sidecar). Packages already sitting in an
        // older cache from before this feature existed won't have a manifest; for those, fall
        // back to the original walk-and-hardlink-from-cacheDir behavior unchanged.
        val manifest = readManifest(cacheDir)
        if (manifest != null) {
            installFromManifest(manifest, dest)
            return
        }

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

    private fun installFromManifest(manifest: Map<String, CasManifestEntry>, dest: Path) {
        for ((relative, entry) in manifest) {
            val target = dest.resolve(relative)
            if (Files.exists(target)) continue
            Files.createDirectories(target.parent)
            val casSource = casPathFor(entry.hash, entry.executable)
            try {
                Files.createLink(target, casSource)
            } catch (e: java.io.IOException) {
                // Cross-device (cache and target on different filesystems) or the CAS entry
                // is somehow missing (e.g. hand-edited cache) — fall back to a plain copy.
                Files.copy(casSource, target, StandardCopyOption.COPY_ATTRIBUTES)
                if (entry.executable) target.toFile().setExecutable(true)
            }
        }
    }
}
