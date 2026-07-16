package org.mycelium.library.compiler

import java.nio.file.Path

// A pluggable compile-to-JS backend: given the source text of a file written in some
// compile-to-JS language (TypeScript, CoffeeScript, Civet, ...), produces plain JS that
// GraalJS can evaluate directly. Implementations are free to ignore `source` and re-read
// `sourcePath` themselves (e.g. EsbuildBackend, whose underlying tool takes a file path as
// its entry point) — both are provided since some backends need one, some the other, and
// some (an npm-package-backed backend evaluating source through GraalJS itself) need both.
interface CompileToJsBackend {
    fun compile(source: String, sourcePath: Path, options: Map<String, Any> = emptyMap()): String
}
