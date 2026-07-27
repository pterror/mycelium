plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "mycelium"

include("app", "library", "mod-loader", "example-mod", "null-safe-mod", "strict-equality-mod", "integer-division-mod", "operator-overloading-mod", "ptc-mod")

project(":mod-loader").projectDir = file("mods/mod-loader")
project(":example-mod").projectDir = file("mods/example-mod")
project(":null-safe-mod").projectDir = file("mods/null-safe-mod")
project(":strict-equality-mod").projectDir = file("mods/strict-equality-mod")
project(":integer-division-mod").projectDir = file("mods/integer-division-mod")
project(":operator-overloading-mod").projectDir = file("mods/operator-overloading-mod")
project(":ptc-mod").projectDir = file("mods/ptc-mod")