package org.mycelium.app

import org.mycelium.library.Mycelium

fun main() {
  val vm = Mycelium()
  vm.runString("console.log(1)", "js")
}
