package org.mycelium.library

/**
 * A friendly, user-facing error from the Mycelium runtime. Wraps errors from the underlying
 * polyglot machinery (missing compilers, unknown extensions, failed compiles, HTTP failures, ...)
 * with a short actionable message instead of a raw Java stack trace.
 */
class MyceliumException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    override fun toString(): String = message ?: "mycelium: unknown error"
}
