# Java Bytecode Modding/Patching Systems: Research Findings

Research conducted July 2026 to inform the design of a mod loader for
JVM-based runtimes, specifically for modding Truffle language implementations
(e.g., GraalJS) by swapping/wrapping AST node classes to change language
semantics.

---

## 1. SpongePowered Mixin

The core bytecode-transformation library used by Fabric (and now partially by
Forge). Everything else in the Minecraft modding world either uses Mixin or
predates it.

### How it hooks class loading

Mixin does **not** use `-javaagent` by default. It provides a pluggable
**MixinService** abstraction that sits inside a custom transforming classloader.
Built-in services support:

- **LegacyLauncher** (`LaunchWrapper`) -- deprecated.
- **ModLauncher** (cpw's modern launcher, used by Forge) -- the current default
  service.
- **Java Agent mode** (`org.spongepowered.tools.agent.MixinAgent`) -- uses
  `premain`/`agentmain` and JDK retransformation; added later, used mainly for
  hot-reload/dev workflows.

Whichever classloader loads the target class first asks the Mixin transformer to
weave applicable mixins into the bytecode **before** the class is
defined/linked. The mixed class is therefore patched exactly once, pre-JVM-
verification -- it never exists in an unpatched form at runtime.

Mixin classes themselves are **never classloaded normally**. Their raw `.class`
bytes are parsed directly with ASM's **Tree API** (`ClassNode`/`MethodNode` --
a full in-memory mutable object graph) to get a manipulable representation. The
mixin's members are then merged into the target class's own `ClassNode`, which
is likewise parsed via the Tree API, transformed, and re-serialized with
`ClassWriter`.

Mixins are pumped through the same transformer chain as regular classes, but
positioned so that any deobfuscation/remapping transformers run **first**,
guaranteeing consistent symbolic names between the mixin and its (possibly
obfuscated) target before merging.

Sources:
- [Mixin Wiki -- The Mixin Environment](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---The-Mixin-Environment)
- [SpongePowered/Mixin on GitHub](https://github.com/spongepowered/Mixin)
- [DeepWiki -- SpongePowered/Mixin](https://deepwiki.com/SpongePowered/Mixin)

### Supported operations (annotation-driven)

| Annotation | Operation | Invasiveness |
|---|---|---|
| `@Mixin(Target.class)` | Declares a class as targeting one or more classes (by class literal or string for private/obfuscated targets). | Declaration only |
| `@Shadow` | Aliases a field/method that exists in the target, letting mixin code reference it as if local. | None (reference only) |
| `@Inject` | Inserts a callback at specified bytecode locations (`@At` -- `HEAD`, `TAIL`, `RETURN`, `INVOKE`, `FIELD`, custom slices) inside a target method. Receives `CallbackInfo`/`CallbackInfoReturnable` for cancellation/return-value override. | Low -- additive |
| `@Redirect` | Replaces a single instruction (method call, field access) inside a target method body with a call to the mixin's method. | Medium |
| `@ModifyArg` / `@ModifyArgs` / `@ModifyVariable` | Narrower redirect variants: tweak one argument or a local variable without redirecting the whole call. | Medium |
| `@Overwrite` | Wholesale replacement of a target method's body. | High |
| `@Accessor` / `@Invoker` | Generates synthetic getter/setter/invoker bridges for private fields/methods on the target, backed by direct bytecode access. Defined via interfaces. | Low -- additive |
| Interface injection | `@Mixin` can add an interface to the target's `implements` list. | Medium |

There is **no** clean "swap superclass" operation. Mixin lets you interpose
behavior and add interfaces, but not rewire the class hierarchy.

Sources:
- [Mixin Wiki -- Callback Injectors](https://github.com/SpongePowered/Mixin/wiki/Advanced-Mixin-Usage---Callback-Injectors)
- [Mixin Wiki -- Overwriting Methods](https://github.com/SpongePowered/Mixin/wiki/Introduction-to-Mixins---Overwriting-Methods)
- [Mixin Cheatsheet](https://github.com/dblsaiko/mixin-cheatsheet/blob/master/README.md)
- [Fabric Wiki -- Accessor Mixins](https://wiki.fabricmc.net/tutorial:mixin_accessors)

### How patches declare targets

A `mixins.<modid>.json` config file (referenced from `fabric.mod.json` or
Forge's manifest) lists:

- Target package
- Compatibility level (bytecode/Java version)
- `mixins` / `client` / `server` arrays of mixin class names
- Injection point defaults
- Refmap reference (a JSON mapping mixin method/field references to obfuscated
  names, generated at compile time by the Mixin annotation processor)

Within each mixin class, targets are declared via `@Mixin(value = {Target.class})`
or `@Mixin(targets = "com.example.PrivateTarget")`. Individual injection points
are declared via `@At` annotations on `@Inject`/`@Redirect` methods specifying
the precise bytecode location.

### Conflict resolution

Each `@Mixin` class/config declares a numeric **priority** (default 1000).

- **`@Overwrite`**: Only the highest-priority mixin's overwrite is applied to a
  given method. Lower-priority overwrites are dropped with a warning (or hard
  error under strict config).
- **`@Redirect`** on the same instruction: highest-priority wins; lower-priority
  redirects are skipped with a warning. No automatic composition.
- **`@Inject`**: Multiple injects at the same or different points generally
  **compose without conflict** since they are pure additive callbacks. This is
  why documentation pushes authors toward `@Inject`.
- **`@Accessor`/`@Invoker`**: Conflict-prone if two mods generate the same
  synthetic accessor name. Mitigated only by convention (prefix with mod id),
  not by the framework.

Source:
- [SpongePowered/Mixin Issue #281 -- Conflicting mixin detection](https://github.com/SpongePowered/Mixin/issues/281)

### Performance

Transformation cost is paid once at class-load time (ASM Tree API parse, merge,
re-serialize). No per-invocation overhead after that -- the transformed bytecode
is the only bytecode the JVM ever sees, so JIT compilation treats it identically
to hand-written code. The startup cost scales with the number of mixin configs
and target classes, but in practice Minecraft mod packs with hundreds of mixins
load in seconds on modern hardware.

### GraalVM compatibility

Mixin's standard mode (custom transforming classloader) works on JVM-mode
GraalVM. It does **not** work with GraalVM Native Image, which uses a
closed-world model with no runtime class loading.

---

## 2. Fabric (Minecraft)

### How it hooks class loading

Fabric Loader is a custom classloader-based bootstrap (`KnotClassLoader`). It
discovers mods on the classpath/mods folder, parses each `fabric.mod.json`,
resolves the dependency graph, registers each mod's declared Mixin config JSONs
with the Mixin subsystem, and sets up the transforming classloader. Mixin is the
**sole officially-supported transformation mechanism**.

### Lifecycle

1. Discover mod JARs, parse `fabric.mod.json` for each.
2. Resolve dependency graph (semver ranges; `depends`/`recommends`/`breaks`/
   `conflicts`).
3. Register Mixin configs with the Mixin subsystem.
4. Set up the transforming classloader.
5. Run **entrypoints** in order:
   - `main` (`ModInitializer`)
   - `client` (`ClientModInitializer`) or `server`
     (`DedicatedServerModInitializer`)

Entrypoints can reference classes, static methods, or fields -- not just classes
implementing an interface.

### Descriptor format

`fabric.mod.json` in the jar root:

```json
{
  "schemaVersion": 1,
  "id": "mymod",
  "version": "1.0.0",
  "entrypoints": {
    "main": ["com.example.MyMod"],
    "client": ["com.example.MyModClient"]
  },
  "mixins": ["mymod.mixins.json"],
  "accessWidener": "mymod.accesswidener",
  "depends": {
    "fabricloader": ">=0.14.0",
    "minecraft": "~1.20"
  }
}
```

### Additional mechanism: Access Wideners

A declarative text file listing package-private/private members to widen to
public, applied by the loader's transformer **before** Mixin runs. Narrow,
low-risk, non-Mixin transform.

### Conflict handling

Bytecode conflicts are delegated entirely to Mixin's priority/merge system.
Fabric itself only arbitrates mod dependency/version conflicts at the loader
level (`breaks`/`conflicts` fields -- hard failure on mismatch).

Sources:
- [Fabric Documentation -- Fabric Loader](https://docs.fabricmc.net/develop/loader/)
- [Fabric Documentation -- fabric.mod.json](https://docs.fabricmc.net/develop/loader/fabric-mod-json)
- [Fabric Wiki -- Fabric Loader](https://wiki.fabricmc.net/documentation:fabric_loader)

---

## 3. Forge (Minecraft)

### How it hooks class loading

Forge historically used a custom `LaunchClassLoader` (LegacyLauncher) with
**coremods** -- raw ASM `ClassNode` transformers registered via
`IFMLLoadingPlugin`. It later modernized to **ModLauncher** (its own
transforming service layer) and eventually added Mixin support, but retains
coremods and Access Transformers for legacy reasons.

### What Forge does differently from Fabric

- **Coremods**: Raw ASM with no structure, versioning, or conflict detection.
  Every coremod author reimplemented method-matching/injection logic ad hoc,
  causing widespread incompatibility.
- **Access Transformers**: Declarative config files widening member visibility
  (similar in spirit to Fabric's Access Wideners, but older).
- **Event bus**: Forge bakes large patches into Minecraft source (via its own
  patch/diff system), exposing extension points as Java events. Most mods
  subscribe to events rather than doing bytecode surgery -- the event bus is
  the primary mod API, not Mixin.
- **Heavier distribution**: Forge's approach of baking patched Minecraft source
  into each Forge build made it slower to update to new Minecraft versions.

### Technical limitations that motivated Fabric

1. No standardized declarative "target this method, do X" model -- Mixin
   (adopted wholesale by Fabric) filled this gap.
2. Coremods had no conflict detection -- two coremods patching the same method
   would silently produce broken bytecode.
3. Forge's monolithic patch-and-event-bus model was tightly coupled to specific
   Minecraft versions, making updates slow.
4. The transformation model is heterogeneous (coremods + ATs + event bus +
   later Mixin) rather than Fabric's clean Mixin-first design.

### Mutual incompatibility

Forge and Fabric mods are mutually incompatible without translation layers
(e.g., Sinytra Connector) because their classloaders, transformer chains, and
ordering guarantees differ enough that co-loading is unsafe without a mediator.

Sources:
- [Forge-Extended-ASM on GitHub](https://github.com/LudoCrypt/Forge-Extended-ASM)
- [Fabric-ASM on GitHub](https://github.com/Chocohead/Fabric-ASM)
- [Forge vs Fabric comparison (generalistprogrammer.com)](https://generalistprogrammer.com/tutorials/minecraft-forge-vs-fabric-complete-mod-loader-comparison)

---

## 4. ByteBuddy

### How it hooks class loading

Works both offline (generate/save `.class` files, or a build-time
Gradle/Maven plugin) and at runtime via the standard JDK `Instrumentation` API.
`AgentBuilder` wraps `-javaagent`/dynamic attach to intercept class loading and
apply transformations. For already-loaded classes, uses
`Instrumentation.redefineClasses` / `retransformClasses`.

### What transformations it supports

- Method interception/replacement (fluent DSL:
  `new ByteBuddy().redefine(Foo.class).method(named("bar")).intercept(...)`)
- `Advice` API: compiles annotated Java method bodies into inlined bytecode
  (before/after/around advice), avoiding the raw ASM learning curve
- Field addition
- Interface implementation
- Subclass generation (dynamic proxies)
- Class redefinition/rebasing

### How patches declare targets

Programmatic API only -- no annotation-driven declarative system like Mixin.
`AgentBuilder` provides matchers (`ElementMatchers.named(...)`,
`.isAnnotatedWith(...)`, etc.) to select classes and methods for transformation.

### Conflict resolution

ByteBuddy has **no concept of mods, priorities, or conflict arbitration**.
It assumes a single authority owns the transformation pipeline. Not designed for
multi-party adversarial modding.

### Performance

Typically cited as one of the fastest bytecode libraries for both generation time
and runtime invocation. Benchmarks commonly show it beating CGLIB and vastly
beating Javassist and raw reflection for proxy/interception dispatch, because it
compiles direct bytecode rather than relying on reflective dispatch.

### GraalVM compatibility

Works on JVM-mode GraalVM. Does not work with Native Image (depends on
`java.lang.instrument` and/or runtime class loading, neither of which is
available in AOT-compiled binaries).

Sources:
- [ByteBuddy website](https://bytebuddy.net/)
- [ByteBuddy tutorial](http://bytebuddy.net/partial/tutorial.partial.html)
- [Baeldung -- Guide to ByteBuddy](https://www.baeldung.com/byte-buddy)

---

## 5. ASM (ObjectWeb)

The foundation that both Mixin and ByteBuddy (and CGLIB) build on.

### Two API layers

**Core (event/visitor) API**: `ClassReader` drives a
`ClassVisitor`/`MethodVisitor`/`FieldVisitor` chain via a strict, documented
call order. This is a streaming, low-memory, single-pass model -- ideal for
simple transformations (adding one call, renaming, filtering).

**Tree API** (`org.objectweb.asm.tree`): Builds a mutable, in-memory object
graph (`ClassNode` containing `List<MethodNode>` containing `InsnList`) from a
`ClassReader`. Code can randomly access, insert, and reorder instructions before
re-serializing with `ClassWriter`. This is what Mixin uses for merging, because
merging two independently-written class bodies requires random access and
multiple passes.

### Capabilities and limitations

ASM operates at raw JVM instruction/constant-pool level. No method-body-as-
source, no "target this named method" abstraction. All higher-level libraries
exist to spare users from writing raw bytecode by hand. ASM itself has no
concept of mods, conflicts, priorities, or declarative targeting.

### How it hooks class loading

ASM itself does not hook class loading -- it is a pure bytecode read/write
library. Consumers (Mixin, ByteBuddy, custom classloaders, Java agents) are
responsible for the hooking; ASM provides the byte-manipulation primitives.

### Performance

Extremely fast and small. The core API is allocation-light (streaming). The Tree
API allocates an object graph proportional to class complexity. Both are used
pervasively in the JVM ecosystem (the JDK itself bundles an internal copy of
ASM).

### GraalVM compatibility

ASM itself is just a library with no runtime requirements beyond standard Java.
It works everywhere Java runs, including Native Image (since it just manipulates
byte arrays -- no class loading or instrumentation dependency). The question is
whether the *consumer* of ASM can perform its transforms at the right time.

Sources:
- [Baeldung -- Guide to Java Bytecode Manipulation with ASM](https://www.baeldung.com/java-asm)
- [ASM Javadoc -- ClassVisitor](https://asm.ow2.io/javadoc/org/objectweb/asm/ClassVisitor.html)
- [ASM Javadoc -- MethodVisitor](https://asm.ow2.io/javadoc/org/objectweb/asm/MethodVisitor.html)

---

## 6. Java Agents / `java.lang.instrument`

### Mechanism

- **Static agent** (`-javaagent:agent.jar`): JVM calls agent's `premain` at
  launch. Agent receives an `Instrumentation` object and registers
  `ClassFileTransformer`s via `addTransformer`.
- **Dynamic attach** (`agentmain`): Another JVM process attaches at runtime via
  `com.sun.tools.attach`.

### Retransformation of already-loaded classes

Supported via `Instrumentation.retransformClasses(Class<?>...)`, but only if
`isRetransformClassesSupported()` is true and the transformer was registered
with `canRetransform = true`. Re-invokes all registered retransformation-capable
transformers on the class's **original** bytes each time (not previously-
transformed bytes), so transformers must be idempotent/re-derivable.

### Hard limitations on redefinition/retransformation

The following restrictions apply to `retransformClasses` and `redefineClasses`:

- **Cannot** add, remove, or rename fields or methods.
- **Cannot** change method signatures.
- **Cannot** change the superclass or implemented interfaces.
- Primitive and array classes are never modifiable.
- Frames on the stack of currently-executing methods keep running old bytecode;
  only new invocations see new bytecode.

These restrictions apply to **already-loaded** classes. At first-load time
(before the class is ever defined), the "shape" restriction does not apply --
a `ClassFileTransformer` can make arbitrary changes to the class bytes before
they are defined.

This distinction is critical: for modding, the pre-load path (where arbitrary
changes are possible) is functionally equivalent to Mixin's approach; the
retransform path (for already-loaded classes) is fundamentally weaker and rules
out many mod patterns (adding fields, adding interfaces for "Duck typing" state,
etc.).

### GraalVM Native Image incompatibility

Java Agents are **not supported on GraalVM Native Image**. Native Image's
closed-world/AOT model requires all code to be known at image build time.
`java.lang.instrument`/JVMTI-based bytecode transformation has no meaning at
native-image run time since there is no bytecode being loaded.

Documented workaround: run the agent at **build time**
(`-J-javaagent:agent.jar` passed to `native-image`), transforming classes once
during AOT compilation.

Sources:
- [JDK 17 -- ClassFileTransformer Javadoc](https://docs.oracle.com/en/java/javase/17/docs/api/java.instrument/java/lang/instrument/ClassFileTransformer.html)
- [JDK 8 -- Instrumentation Javadoc](https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html)
- [Baeldung -- Guide to Java Instrumentation](https://www.baeldung.com/java-instrumentation)
- [GraalVM -- Native Image Compatibility Guide](https://www.graalvm.org/latest/reference-manual/native-image/metadata/Compatibility/)
- [GraalVM Discussion #6693 -- Native Image support for Java Agent](https://github.com/oracle/graal/discussions/6693)

---

## 7. Javassist

### How it hooks class loading

Can be used with a Java agent (`ClassFileTransformer`) or with a custom
classloader. Also supports offline/compile-time transformation.

### API model

Two API levels:

- **Source-level API**: Pass Java-syntax source strings (e.g.,
  `"{ $1 = $1 * 2; }"`) that Javassist compiles into bytecode. Avoids raw
  instruction knowledge entirely.
- **Low-level bytecode API**: Similar in spirit to ASM's Tree API.

The source-level API is Javassist's distinguishing feature and its main draw --
much easier to learn than ASM or ByteBuddy.

### Performance

Significantly slower than ASM/ByteBuddy/CGLIB at both generation time and
runtime dispatch. One commonly-cited benchmark set: ~1048ms for Javassist vs.
~13ms ByteBuddy / ~15ms CGLIB / ~281ms ASM for 1,000,000 interceptor
invocations on Java 8 (the ASM number reflects an unoptimized hand-written
dispatch, not a fundamental ASM limitation).

### Conflict resolution

None. Single-owner model like ByteBuddy.

### GraalVM compatibility

Works on JVM-mode GraalVM. Not compatible with Native Image (depends on runtime
class generation/loading).

Source:
- [Comparison of Java ASM with other bytecode manipulation libraries](https://colinchjava.github.io/2023-10-20/08-27-41-534338-comparison-of-java-asm-library-with-other-bytecode-manipulation-libraries/)

---

## 8. CGLIB

### What it does

Built on top of ASM. Specializes narrowly in generating dynamic **subclasses**
for proxying (method interception). Unlike JDK dynamic proxies, it can proxy
concrete classes, not just interfaces. Used heavily by Spring, Hibernate, and
Mockito historically.

### What it does not do

Not designed for general "patch an arbitrary existing class" use. It generates
*new* subclasses rather than rewriting a target class in place. Not suitable for
modding-style use cases where the goal is to alter the behavior of an existing
class without controlling who instantiates it.

### GraalVM compatibility

Limited. Native Image has specific support for some Spring/Hibernate CGLIB
proxies via build-time generation, but general dynamic CGLIB proxy generation
does not work at Native Image runtime.

---

## 9. Truffle Instrumentation API (existing extension point)

Truffle has its own built-in instrumentation framework, separate from JVM-level
bytecode manipulation. This is the sanctioned extension point for tools
(debuggers, profilers, tracers) interacting with Truffle language
implementations.

### Key components

- **`TruffleInstrument`**: Interface that instrument implementations must
  implement. Registered via `@Registration` annotation.
- **`Instrumenter`**: Entry point for attaching event listeners/nodes to AST
  locations matching a filter.
- **`InstrumentableNode`**: AST nodes that may be instrumented. Required to
  create a wrapper via `createWrapper(ProbeNode)`.
- **`ExecutionEventNode`**: Event node inserted at specific AST locations to
  listen to execution events (before/after/on-return/on-exception).
- **`ProbeNode`** / **`@GenerateWrapper`**: Wrappers are generated
  automatically via annotation processor; they delegate to the original node
  while allowing interposition.

### What it can and cannot do

**Can**: Observe/wrap AST node execution with before/after hooks; intercept
return values and exceptions; attach per-node state; filter nodes by tag,
source section, or root name.

**Cannot**: Replace a node's fundamental logic; swap one node class for another
in the AST; change the semantics of an operation (e.g., change what `+` does);
add new node types to the AST. It is observational/interposition-oriented, not
a modding framework.

Sources:
- [GraalVM -- Getting Started with Instruments](https://www.graalvm.org/latest/graalvm-as-a-platform/implement-instrument/)
- [Truffle Javadoc -- TruffleInstrument](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/TruffleInstrument.html)
- [Truffle Javadoc -- ExecutionEventNode](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/ExecutionEventNode.html)
- [Truffle Javadoc -- InstrumentableNode](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/instrumentation/InstrumentableNode.html)

---

## 10. Existing Truffle/GraalJS modding projects

**No prior art found.** Searches for combinations of "Truffle", "mod loader",
"AST node swap/patch", "GraalJS plugin/fork" turned up no projects doing
Mixin-equivalent bytecode weaving against Truffle/GraalJS internals (e.g.,
patching `JSFunctionCallNode`, `JSAdditionNode`, etc.).

The closest conceptual analogs found:

1. **Generic AOP via `TruffleInstrument`** -- weaker than a real mod loader
   since it cannot change a node's fundamental logic or replace it in the tree.
2. **Forking GraalJS source directly** -- the only current path to semantic
   changes, with obvious maintenance burden.
3. **Truffle DSL's `@GenerateNodeFactory` / `NodeFactory<T>`** and
   `@Specialization(replaces=...)` -- compile-time, language-author-controlled
   mechanisms, not third-party runtime patching.

This appears to be an open/unaddressed niche.

---

## Cross-cutting implications for a Truffle mod loader

### Pre-load weaving maps naturally

Mixin's "weave-before-first-load" model maps onto Truffle: language classes
(node classes, builtins) are ordinary JVM classes loaded by a classloader before
any AST is built. A Mixin-style ASM Tree API transform intercepting class
loading for `com.oracle.truffle.js.nodes.*` would work the same way it works for
Minecraft -- assuming GraalJS runs on the JVM (not Native Image).

### Native Image is the critical constraint

The Native Image incompatibility of both `-javaagent` and any dynamic-
classloader-hook approach is the single biggest constraint. GraalVM/Truffle
deployments frequently target Native Image for startup/footprint. A mod loader
depending on runtime class transformation would need a build-time
(image-build) transformation path as a fallback, mirroring the
`-J-javaagent` pattern Native Image documents.

### DSL-generated classes are a different target surface

Truffle's `@Specialization`-generated node classes and `NodeFactory`-based
instantiation add a layer Minecraft mods don't deal with. DSL-generated
subclasses (e.g., `FooNodeGen`) mean the "real" bytecode a mod would need to
target is compiler-generated, not what's visible in the language's own source.
This is a meaningfully different problem from Minecraft's obfuscated-but-stable-
per-version bytecode. Mixin's refmap system solves a related but not identical
problem (obfuscation mapping vs. generated-class-structure mapping).

### Self-rewriting AST nodes are a novel concern

Truffle's aggressive node self-rewriting at runtime (nodes replace themselves
with specialized variants during execution, per the AST-interpretation model)
means any bytecode-level class patch must survive/interact correctly with
Truffle's own node-replacement machinery. A mod that patches `FooNodeGen` must
account for the possibility that Truffle will replace an instance of
`FooNodeGen` with a different specialization variant at runtime. This concern
has no analog in Minecraft modding systems.

### NodeFactory as a potential interception point

Rather than (or in addition to) patching node class bytecode, intercepting
`NodeFactory.createNode(...)` calls could allow swapping the returned node
instance for a modded variant. This is analogous to a factory/provider
interception pattern and would avoid some of the DSL-generated-class complexity,
but would require either bytecode-patching the factory itself or controlling the
language's builtin-registration code path.

---

## Key design decisions that fall out of the research

The following decisions are surfaced by the research as requiring explicit
resolution. These are listed as findings, not recommendations.

1. **Hooking mechanism**: Custom classloader (Fabric/Mixin model) vs.
   `-javaagent` (ByteBuddy/standard model) vs. build-time transform (Native
   Image compatibility). These are not mutually exclusive -- Mixin itself
   supports multiple via its MixinService abstraction.

2. **Transformation granularity**: Method-body injection (`@Inject`-style,
   composable, low-conflict) vs. method replacement (`@Overwrite`-style, simple
   but conflict-prone) vs. instruction-level redirect (`@Redirect`-style,
   precise but fragile). The Mixin ecosystem's strong preference for `@Inject`
   over `@Overwrite` is a direct result of multi-mod conflict experience.

3. **Conflict resolution model**: Priority-based (Mixin's approach -- simple,
   deterministic, but lossy: lower-priority patches to the same target are
   silently dropped) vs. composition-based (multiple patches compose
   automatically, harder to implement correctly) vs. explicit (modders must
   declare compatibility/ordering, highest friction but most correct).

4. **Target declaration model**: Annotation-driven (Mixin -- declarative,
   statically analyzable, but requires a compile-time annotation processor) vs.
   programmatic (ByteBuddy -- flexible, no build tooling required, but harder to
   analyze for conflicts statically) vs. config-file-driven (Forge ATs/Fabric
   AWs -- simple but limited to narrow transform types).

5. **DSL-generated class targeting**: Whether to target the hand-written node
   classes or the generated `*Gen` subclasses. Targeting generated classes is
   more precise but couples to the DSL's output format (which may change across
   Truffle versions). Targeting the source classes is more stable but may miss
   the actual runtime behavior if the generated class overrides it.

6. **Interaction with Truffle's node replacement**: Whether patched nodes must
   be "replacement-aware" (preserving Truffle's ability to replace them with
   specialized variants) or whether the mod loader should also intercept
   Truffle's node-replacement mechanism to ensure modded behavior persists
   across specialization transitions.

7. **Native Image story**: Whether Native Image support is a hard requirement
   (constraining the design to build-time-only transforms), a future goal
   (design for it but don't implement yet), or out of scope (JVM-mode only).
