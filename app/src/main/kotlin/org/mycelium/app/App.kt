package org.mycelium.app

import org.mycelium.library.Mycelium
import org.mycelium.library.MyceliumException

// Parsed command line: the `--yolo` flag can appear anywhere (`mycelium --yolo file.mjs`
// or `mycelium run --yolo file.mjs`), so it's stripped out of `rest` before the
// remaining positional arguments are dispatched on below. Exposed (internal, not
// private) so it can be exercised directly by a parse-only test without spinning up a
// Mycelium/GraalVM Context.
internal data class ParsedArgs(val yolo: Boolean, val rest: List<String>)

internal fun parseArgs(args: Array<String>): ParsedArgs {
    val yolo = args.contains("--yolo")
    val rest = args.filterNot { it == "--yolo" }
    return ParsedArgs(yolo, rest)
}

fun main(args: Array<String>) {
    val (yolo, rest) = parseArgs(args)

    if (rest.isEmpty()) {
        printUsage()
        return
    }

    when (rest[0]) {
        "run" -> {
            if (rest.size < 2) {
                System.err.println("mycelium: missing file argument")
                System.err.println("usage: mycelium run <file>")
                System.exit(1)
                return
            }
            runFile(rest[1], yolo)
        }
        "version" -> println("mycelium 0.1.0-dev")
        "help", "--help", "-h" -> printUsage()
        else -> {
            // If it looks like a file path, treat it as implicit `run`
            if (rest[0].contains('.')) {
                runFile(rest[0], yolo)
            } else {
                System.err.println("mycelium: unknown command '${rest[0]}'")
                printUsage()
                System.exit(1)
            }
        }
    }
}

private fun runFile(path: String, yolo: Boolean) {
    try {
        val vm = Mycelium(yolo = yolo)
        vm.runFile(path)
    } catch (e: MyceliumException) {
        System.err.println(e.message)
        System.exit(1)
    }
}

private fun printUsage() {
    println("""
        mycelium — run any file, import any language

        usage:
          mycelium run <file>    Run a file (js, mjs, rb, c, rs, py)
          mycelium <file>        Shorthand for 'mycelium run <file>'
          mycelium version       Show version
          mycelium help          Show this help

        flags:
          --yolo                 Auto-install missing npm packages on import
    """.trimIndent())
}
