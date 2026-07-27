---
layout: home

hero:
  name: mycelium
  text: A polyglot runtime on GraalVM
  tagline: One shared Context for JS, Ruby, Lua, and compiled/compile-to-JS languages, with npm/PyPI/RubyGems import and a JVM-level mod loader for changing language semantics itself.
  actions:
    - theme: brand
      text: Getting started
      link: /guide/getting-started
    - theme: alt
      text: Language support
      link: /reference/languages
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

**Pre-alpha, actively evolving, not published anywhere** — there is no release yet; you run
Mycelium from source via Gradle. See [Getting Started](/guide/getting-started) to build and
run it, [Architecture](/guide/architecture) for how the runtime is put together,
[Language support](/reference/languages) for what each of the 18 importable languages can
and can't do today, and [Mods](/mods/) for the JVM-level mod system. The
[README](https://github.com/pterror/mycelium#readme) has the full feature list; `TODO.md`
and `ROADMAP.md` in the repo root are the project's ground-truth session log and
forward-looking plan, respectively, and take precedence over this site if anything drifts.
