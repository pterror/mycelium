package org.mycelium.app

import org.mycelium.library.Mycelium
import org.mycelium.library.MyceliumException

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "run" -> {
            if (args.size < 2) {
                System.err.println("mycelium: missing file argument")
                System.err.println("usage: mycelium run <file>")
                System.exit(1)
                return
            }
            runFile(args[1])
        }
        "version" -> println("mycelium 0.1.0-dev")
        "help", "--help", "-h" -> printUsage()
        else -> {
            // If it looks like a file path, treat it as implicit `run`
            if (args[0].contains('.')) {
                runFile(args[0])
            } else {
                System.err.println("mycelium: unknown command '${args[0]}'")
                printUsage()
                System.exit(1)
            }
        }
    }
}

private fun runFile(path: String) {
    try {
        val vm = Mycelium()
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
    """.trimIndent())
}
