---
layout: home

hero:
  name: mycelium
  text: A polyglot runtime on GraalVM
  tagline: One shared Context for JS, Ruby, Lua, and compiled/compile-to-JS languages, with npm/PyPI/RubyGems import and a JVM-level mod loader for changing language semantics itself.
  actions:
    - theme: brand
      text: Mod authoring guide
      link: /mod-authoring
    - theme: alt
      text: View on GitHub
      link: https://github.com/pterror/mycelium

features:
  - title: Cross-language imports
    details: import a .rb, .py, .c, .rs, .go, or compile-to-JS source file directly from JavaScript. A custom GraalVM FileSystem intercepts unrecognized extensions and hands them to the right compiler/shim generator.
  - title: Native Lua 5.1
    details: A from-scratch Truffle Lua implementation — parser, tables/metatables, coroutines, and the string/math/table/io/os/debug standard libraries, acid-tested against real Lua libraries (luaunit, penlight, middleclass).
  - title: Package ecosystems, unified
    details: Registry clients and installers for npm, PyPI, RubyGems, and crates.io/Go-proxy, with a pure-Kotlin semver-aware resolver and a content-addressable package cache.
  - title: A JVM-level mod loader
    details: Bytecode-patches Truffle/GraalJS runtime classes at class-load time to change language semantics — null-safe access, strict equality, C-style integer division, proper tail calls, operator overloading.
---

See the [README](https://github.com/pterror/mycelium#readme) for the full feature list, build/run instructions, and architecture overview. `TODO.md` and `ROADMAP.md` in the repo root are the project's ground-truth session log and forward-looking plan.
