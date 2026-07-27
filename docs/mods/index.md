# Mods

Mycelium's mod system changes guest-language semantics itself, at the JVM class-loading
level — not something achievable through the polyglot API's own configuration surface. This
page covers what it does mechanically and indexes the six mods shipped in the repo today.
For the how-to (writing your own mod, the `PatchDsl` API, finding classes to patch), see
the [mod authoring guide](../mod-authoring).

## How it works

`mods/mod-loader/` is a generic Java agent, attached via `-javaagent:` at JVM startup. It
uses ASM (a bytecode-manipulation library) to patch Truffle/GraalJS runtime classes'
bytecode *at class-load time*, before those classes are ever linked — so the patch is
already baked in by the time GraalJS or Truffle starts using them. This is bytecode
patching, not a runtime hook, proxy, or AOP framework layered on top: the agent intercepts
`ClassFileTransformer` callbacks and rewrites the raw `.class` bytes for a small set of
target classes named in each mod's manifest.

Each mod is a JAR containing:

- a `mod.json` descriptor (id, version, target GraalJS version, list of `ModTransformer`
  classes, load priority, and unenforced-for-now `depends`/`breaks` metadata),
- one or more `ModTransformer` implementations that declare which classes they target and
  return patched bytecode for them.

Mods are topologically sorted by declared dependency, then by `priority` (lower runs
first); if two mods target the same class, both patches apply in order, each patching the
bytecode the previous one already produced.

Enable mods for a run with `-Pmod` (see [Getting Started](../guide/getting-started)) —
`app/build.gradle.kts`'s `jvmRun` task stages every mod's jar, attaches the mod-loader as a
`-javaagent`, and points it at the staged directory via `-Dmycelium.mods.dir=...`. Loading
mods this way always bypasses the background daemon (a `-javaagent` requires process-level
control the daemon's shared `Context` can't provide), so mods are guaranteed to actually
take effect rather than silently dropping onto a stale daemon process.

Mixin (the class-patching framework Minecraft's modding ecosystem uses) was evaluated as a
possible base for this and rejected — see
[the mixin evaluation research note](../research/mixin-evaluation) — in favor of extending
Mycelium's own `PatchDsl`.

## The mods

| Mod | Directory | What it does |
|---|---|---|
| example-mod (cursed-addition) | `mods/example-mod/` | Patches `JSAddNodeGen` so integer addition returns one extra — `a + b` becomes `a + b + 1` for variable operands. A demo/reference mod, not meant for real use; also the vehicle for the "constant folding" gotcha documented in the mod authoring guide (literal arithmetic like `1 + 1` is folded by GraalJS's parser before this patch point ever runs). |
| null-safe-mod | `mods/null-safe-mod/` | Patches `PropertyGetNode$TypeErrorPropertyGetNode.getValue` so `null.foo`/`undefined.foo` return `undefined` instead of throwing a `TypeError` — and separately patches `ReadElementNode`'s Object-returning overloads so bracket/computed access (`null[x]`, `undefined["key"]`) gets the same treatment. |
| strict-equality-mod | `mods/strict-equality-mod/` | Redirects `NodeFactory`'s equality-node construction from `JSEqualNode.create` to `JSIdenticalNode.create`, so `==`/`!=` behave like `===`/`!==`. The simplest pattern a mod can use — a single `redirectCallsTo`. |
| integer-division-mod | `mods/integer-division-mod/` | Patches `JSDivideNode` so `/` truncates to an integer, C-style, instead of GraalJS's normal floating-point division. |
| operator-overloading-mod | `mods/operator-overloading-mod/` | Patches `JSContextOptions.isOperatorOverloading` (plus, belt-and-suspenders, `OperatorsBuiltins.checkOverloadedOperatorsAllowed`) so GraalJS's built-in `Operators(...)` overloading API — normally opt-in and experimental — is always exposed as a JS global, without needing the `--operator-overloading` CLI flag. |
| ptc-mod | `mods/ptc-mod/` | Patches `ReturnNode$TerminalPositionReturnNode`, `JSFunctionCallNode`, and `FunctionRootNode` to trampoline self-recursive tail calls (`return f(...)` inside `f`) — proper tail calls, so a self-recursive function in tail position runs in a loop instead of growing the JVM stack. |

All six are working, tested mods with their own Gradle module (`mods/<name>/`,
`build.gradle.kts` + `src/`), included in `settings.gradle.kts` and built/staged
automatically by `jvmRun -Pmod`.
