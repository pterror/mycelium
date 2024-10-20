package org.mycelium.app

import org.mycelium.library.Mycelium

fun main() {
  val vm = Mycelium()
  vm.runFile("modules-test/test.mjs")
}
