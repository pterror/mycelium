package org.mycelium.library

/**
 * A friendly, user-facing error from the Mycelium runtime. Wraps errors from the underlying
 * polyglot machinery (missing compilers, unknown extensions, failed compiles, HTTP failures, ...)
 * with a short actionable message instead of a raw Java stack trace.
 */
class MyceliumException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    override fun toString(): String = message ?: "mycelium: unknown error"
}

/**
 * Thrown by a guest script's `process.exit(code)` when Mycelium is constructed with a
 * non-default `exitHandler` (see Mycelium's constructor) — daemon mode uses this so that a
 * script calling `process.exit()` ends only the one in-flight request instead of calling
 * `System.exit` and killing the whole long-running daemon process along with every other
 * client's request. Deliberately not a MyceliumException: it's a control-flow signal, not a
 * user-facing error.
 */
class ProcessExitException(val code: Int) : RuntimeException()
