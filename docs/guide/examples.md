# Examples

[Getting Started](./getting-started) and [Architecture](./architecture) cover how Mycelium
works. This page is about *why* — concrete scenarios you'd actually reach for it, each with
a short, runnable sample grounded in the project's own tests and example mods (not invented
syntax). See [Language support](../reference/languages) for which language pairs actually
work for cross-language import today.

## Modding: make `==` behave like `===`

Say your team has been burned one too many times by JS's loose `==` and wants it banned at
the language level, not just via a linter someone can `// eslint-disable`. `strict-equality-mod`
(shipped in `mods/strict-equality-mod/`) does exactly this — the simplest mod pattern there
is: a single `redirectCallsTo`, rewriting GraalJS's own equality-node factory call so `==`/`!=`
literally construct `JSIdenticalNode` (the `===`/`!==` node) instead of `JSEqualNode`:

```kotlin
// mods/strict-equality-mod/src/main/kotlin/.../StrictEqualityTransformer.kt
class StrictEqualityTransformer : ModTransformer {
    override fun targetClasses() = setOf("com/oracle/truffle/js/nodes/factory/NodeFactory")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        return patchClass(classfileBuffer) {
            redirectCallsTo("com/oracle/truffle/js/nodes/binary/JSEqualNode", "create")
                .to("com/oracle/truffle/js/nodes/binary/JSIdenticalNode", "create")
        }
    }
}
```

Its `mod.json` just lists the transformer and a priority; enable it for a run with `-Pmod`
(mods staged under the run get picked up automatically by the mod-loader javaagent):

```bash
./gradlew :app:jvmRun --args="run path/to/file.mjs" -Pmod
```

Now `0 == "0"` is `false` everywhere in every script that run loads — not a lint warning, an
actual change to what the bytecode does. See the [mod authoring guide](../mod-authoring) for
the full `PatchDsl` (`afterCallsTo`, `atMethodEntry`, ...) if a redirect isn't enough — e.g.
`null-safe-mod` uses `atMethodEntry` to make `null.foo`/`undefined.foo` return `undefined`
instead of throwing, which needs more than a one-line redirect.

## Interop: call a Ruby function from JavaScript

JS is the host language every cross-language import funnels through, so the common shape is
"import a member from a `.rb`/`.c`/`.rs`/`.go` file the same way you'd import a `.mjs` file."
Given `greet.rb`:

```ruby
def greet(name)
  "hello, #{name}!"
end
```

```js
import { greet } from "./greet.rb";

console.log(greet("world")); // "hello, world!"
```

```bash
./gradlew :app:jvmRun --args="run path/to/main.mjs" -Planguages=js,ruby
```

This is a real, passing scenario (`MyceliumIntegrationTest.jsImportsRuby` /
`jsImportsTwoRubyModulesWithSameExportName`, and the same shape shows up for two Ruby
modules exporting the same name, disambiguated via `import { greet as greet1 } from ...`).
Ruby is a good pick for this because it has full, symmetric member-import support — unlike
Lua (whose `LuaTable`/`LuaFunction` don't export `InteropLibrary` yet, so a `.lua` file's
members can't be imported into another guest language today — see
[Language support](../reference/languages)) or Python (guest-`Polyglot.eval` only; running a
`.py` file directly currently crashes GraalPython, a known upstream limitation).

## Interop: offload a hot loop to compiled C

The same `import { x } from "./file.ext"` mechanism works for anything that compiles to LLVM
bitcode — useful when a JS hot path would benefit from a real compiled function instead of
reimplementing it in JS. Given `add.c`:

```c
int add(int a, int b) {
    return a + b;
}
```

```js
import { add } from "./add.c";

console.log(add(2, 3)); // 5
```

```bash
./gradlew :app:jvmRun --args="run path/to/main.mjs" -Planguages=js,llvm
```

No manual `clang` invocation, no build step to wire up yourself — Mycelium compiles `add.c`
to LLVM bitcode on demand (content-hash cached under `$XDG_CACHE_HOME/mycelium/`) the first
time it's imported. The same pattern works for `.rs` (`import { multiply } from
"./multiply.rs"`, `rustc --emit=llvm-bc`) — both are exercised directly in
`MyceliumIntegrationTest` (`jsImportsC`, and the C+Ruby-together case in
`jsImportsCAndRuby`-style tests).

## Auto-import: use an npm package without installing it first

No `npm install`, no `package.json` needed — pass `--yolo` and reference the bare package
name; Mycelium resolves it against the npm registry, downloads and caches it
content-addressed, and vendors it under a local `node_modules` before retrying the import:

```js
// main.mjs — no node_modules directory exists yet
import isOdd from "is-odd";

console.log(isOdd(3)); // true
```

```bash
./gradlew :app:jvmRun --args="run path/to/main.mjs --yolo"
```

This is exactly `MyceliumIntegrationTest.yoloAutoInstallsMissingPackage` (constructs a fresh
temp project with no `node_modules`, `Mycelium(yolo = true)`, imports `is-odd` cold). Without
`--yolo`, Mycelium still auto-installs whatever a `package.json` in the project already
declares — `--yolo` is specifically for a bare specifier that doesn't resolve *at all* yet.

## Auto-import: pull in a RubyGem the same way

The same bare-specifier auto-install exists for RubyGems (and PyPI), gated on `--yolo` and on
Mycelium detecting the call stack is actually inside Ruby (so a missing specifier while
running Ruby tries a gem, not an npm/PyPI guess) — see `InterceptingFileSystem.kt`'s
`yoloInstallRubyGem`/`yoloInstallPyPIPackage`. From a `.rb` file:

```ruby
require "some_gem"
# ... use some_gem ...
```

```bash
./gradlew :app:jvmRun --args="run path/to/script.rb --yolo" -Planguages=js,ruby
```

**Caveat worth flagging:** unlike the npm path, this repo's automated test suite doesn't
currently have an integration test exercising RubyGems/PyPI auto-install end-to-end against
the real registries (the wiring is confirmed by reading `InterceptingFileSystem.kt`
directly, not by a passing test analogous to `yoloAutoInstallsMissingPackage`) — worth a
smoke test yourself before relying on it for something real.

## Orchestrating multiple guest runtimes from one script

Beyond importing members, you can hand a whole snippet to another language and get a value
back with `Polyglot.eval`, including nested — a guest language evaluating another guest
language evaluating another:

```js
// Ruby evaluates a nested JS expression and hands the result back to JS
const rbResult = Polyglot.eval("ruby", 'Polyglot.eval("js", "10 + 20")');
console.log(rbResult); // 30
```

This nests arbitrarily deep (see the chained-`Polyglot.eval` tests in
`MyceliumIntegrationTest` — JS→Ruby→JS, Ruby→JS→Ruby, three levels deep, etc.), which is
handy for e.g. delegating a computation to whichever language already has the library for it,
without leaving the single shared `Context`. Python is deliberately left out of this example:
`Polyglot.eval("python", ...)` works on a bare polyglot `Context`, but currently still crashes
when run through Mycelium's full pipeline (a named `Source` plus its custom `FileSystem`) —
see `PythonInteropExperimentTest` and [Language support](../reference/languages) for the
GraalPython-side bug tracking this.

## What's next

- [Mods](../mods/) for the full list of shipped mods and what each patches.
- [Mod authoring guide](../mod-authoring) for the `PatchDsl` API in depth.
- [Language support](../reference/languages) for the full language matrix and every known gap.
