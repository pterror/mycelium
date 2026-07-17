# Mixin Integration Evaluation for Mycelium

**Date**: July 2026  
**Status**: Decision Document  
**Recommendation**: **Do not integrate Mixin at this time. Invest in PatchDsl improvements instead.**

---

## Executive Summary

This document evaluates whether SpongePowered's Mixin library is worth integrating into Mycelium's mod loader. The conclusion is that **Mixin offers real benefits (automatic frame computation, declarative annotations, conflict detection) but the costs outweigh those benefits for Mycelium's current scope and constraints**. Instead, targeted PatchDsl improvements can deliver 80% of Mixin's value with 20% of the integration cost.

---

## What Is the Current State?

### PatchDsl Overview

Mycelium has a working, hand-written mod loader (`mod-loader/` module) with a custom **PatchDsl** — a Kotlin DSL that wraps ASM bytecode manipulation to provide common patterns:

- `redirectCallsTo(owner, method).to(newOwner, newName)` — Replace all calls to a method with calls to a different one
- `afterCallsTo(owner, method) { ... }` — Splice bytecode after a method call
- `beforeCallsTo(owner, method) { ... }` — Splice bytecode before a method call
- `atMethodEntry(method) { ... }` — Inject bytecode at the start of a method
- `atMethodReturn(method) { ... }` — Inject bytecode before every return instruction
- `InsnBuilder` — A DSL for constructing individual bytecode instructions without raw ASM

### Existing Mods

Six working mods currently use PatchDsl:

1. **cursed-addition** (`example-mod/`) — Patches `JSAddNodeGen` to add 1 to integer addition results
   - Uses both `redirectCallsTo` (to a replacement method) and `afterCallsTo` (for instruction splicing)
   - ~90 lines of code, straightforward

2. **null-safe** (`null-safe-mod/`) — Makes `null.foo` and `null[index]` return `undefined` instead of throwing
   - Uses `atMethodEntry` with complex control flow and **manual StackMapTable frame construction** (the main pain point)
   - ~265 lines; ~70 lines are frame-related boilerplate
   - Two target classes with different patch strategies

3. **strict-equality** (`strict-equality-mod/`) — Makes `==` behave like `===`
   - Single `redirectCallsTo` call
   - ~30 lines, minimal complexity

4. **integer-division** (`integer-division-mod/`) — Makes `/` truncate toward zero (C-style)
   - Wholesale method replacement via `atMethodEntry`
   - **Heavy use of manual StackMapTable frames** for multiple branch targets
   - ~205 lines; ~90 lines are frame-related

5. **operator-overloading** — Mentioned in recent commits but not examined in detail

6. **PTC** (probable tail calls) — Mentioned in recent commits but not examined in detail

---

## Strengths of the Current PatchDsl

1. **Zero external dependencies beyond ASM** — The mod loader is self-contained (ASM bundled via fat JAR), making it easy to distribute and run without dependency hell.

2. **Fast compilation** — No annotation processor overhead; mods compile and run without build-time analysis.

3. **Simple for straightforward patches** — The `strict-equality` and `cursed-addition` mods demonstrate that simple, high-value patches (redirecting a factory method, splicing bytecode at a call site) are elegant and readable in PatchDsl.

4. **Transparent execution** — What you write is what runs; no annotation processor intermediaries or code generation magic to debug.

5. **Works well for the target use case** — Mycelium's primary goal is patching Truffle/GraalJS runtime classes at class-load time, and PatchDsl does that job adequately for simple patches.

---

## Weaknesses of the Current PatchDsl

### 1. Manual StackMapTable Frame Construction (Major Pain Point)

**The Problem**: When a mod introduces new **branch targets** (labels that are only reachable via a jump, not fall-through), the JVM's bytecode verifier requires explicit StackMapTable entries at those locations. ASM's `ClassWriter` can auto-compute frames (`COMPUTE_FRAMES` mode), but Mycelium uses `COMPUTE_MAXS` mode for performance reasons (it skips frame recomputation). This means mod authors must manually construct `FrameNode` objects.

**Evidence**: 
- `null-safe-mod`'s `getValue` patch manually builds two `FrameNode` instances to guard its `returnDefault` and `skip` labels (lines 100-142)
- `null-safe-mod`'s `executeWithTargetAndIndex` patch has a helper function `frameLocalsFor()` that computes method-signature-specific frames (lines 240-263)
- `integer-division-mod`'s `doInt1` patch has an `F_NEW` frame for the unreachable dead code following its `ireturn` (lines 105-117)
- `integer-division-mod`'s `doDouble` patch has **three explicit frame constructions**: one for dead code, one for the floor branch, one for the ceil branch (lines 143-195)

**Impact**: ~30-40% of the code in complex patches is frame scaffolding. This is tedious, error-prone (easy to get local variable counts wrong), and not beginner-friendly.

### 2. Requires Understanding Low-Level ASM Concepts

Mod authors need to know:
- What opcodes exist and when to use them (`ICONST_0` vs `BIPUSH` vs `SIPUSH` vs `LDC`)
- How local variable slots work (different for different method signatures)
- How the operand stack works and frame states
- What a StackMapTable is and why it matters
- How to reason about bytecode control flow

This is a high barrier to entry for mod authors who aren't familiar with bytecode. The mod-authoring guide has a section (lines 383–435 in `/home/me/git/mycelium/docs/mod-authoring.md`) on debugging patches because this complexity is real.

### 3. No Composition or Conflict Detection

If two mods target the same method:
- Both patches apply in priority order (lower priority first), but
- There is **no checking for conflicts** — if both mods redirect the same call site, the second one's redirect wins and the first is silently overwritten
- **No automatic composition** — there's no way to declare "I want both mod A and mod B's patches to apply to the same method in a predictable order"

The mod-authoring guide (line 290) acknowledges this: "If two mods target the same class, both patches apply... Plan your patch order carefully if mods compose."

### 4. Purely Programmatic, No Declarative Intent

Every mod must implement the `ModTransformer` interface and write imperative Kotlin code. This means:
- **No build-time validation** — You can't catch typos in class/method names until runtime
- **No static analysis** — Can't answer "which classes does this mod patch?" without running the code
- **No standard patterns** — Each mod author has to understand the full DSL; there's no way to express "I want to inject a callback here" in a standard, library-understood way

### 5. Limited to What the DSL Supports

The PatchDsl provides shortcuts for common instructions (`iadd()`, `invokestatic()`, etc.), but for anything not covered, mod authors must use `raw(InsnNode(...))`. For complex patches like `integer-division`'s `doDouble`, this means mixing high-level DSL with raw ASM, reducing readability.

### 6. No Built-in Mod Dependency Resolution

The `mod.json` descriptor has `depends` and `breaks` fields, but the current mod loader doesn't enforce them. If mod B depends on mod A, and mod A isn't loaded, mod B still tries to load (and likely fails). No version checking, no topological sorting of dependencies.

---

## What Would Mixin Add?

Based on the research in `/home/me/git/mycelium/docs/research/java-modding-systems.md` and Mixin's own documentation:

### 1. Automatic StackMapTable Frame Computation

Mixin computes frames automatically using ASM's `COMPUTE_FRAMES` mode. Mod authors don't manually construct `FrameNode` objects — Mixin handles it.

**Impact**: The ~70 lines of frame boilerplate in `null-safe-mod` and ~90 lines in `integer-division-mod` disappear entirely. Patches become more readable and less error-prone.

**Proof**: Mixin has been used in thousands of Minecraft mods (Fabric, Forge) for years with no known widespread frame-computation bugs.

### 2. Declarative Annotations

Instead of imperative code, mod authors write annotations:

```kotlin
@Mixin(PropertyGetNode::class)  // or @Mixin(targets = "com.oracle.truffle.js.nodes.access.PropertyGetNode")
abstract class PropertyGetNodeMixin {
    @Inject(method = "getValue", at = @At("HEAD"))
    private void onGetValue(CallbackInfo ci) {
        // Injection code here
    }
}
```

**Benefits**:
- Expresses intent declaratively — "I want to inject at the HEAD of this method"
- Build-time annotation processor can validate that target methods exist (if configured)
- Easier for novices to understand the pattern
- Standardized across all Mixin-using projects (Minecraft, etc.)

**Downside**: Requires a build-time annotation processor in every mod's `build.gradle.kts`.

### 3. Conflict Detection and Priority

Mixin's priority system (`priority` field in `@Mixin` or mixin config) determines load order. If two mods `@Overwrite` the same method, the highest-priority one wins (lower-priority ones are skipped with a warning). If two mods `@Inject` at the same point, they compose automatically (multiple injections at the same point generally work).

**Impact**: Better diagnostics when mods conflict; clearer semantics for composition.

### 4. Refmap System

Mixin uses a compile-time-generated **refmap** (reference mapping) to track which mixin members target which obfuscated classes/methods. At runtime, Mixin remaps based on the refmap, allowing mods to reference clear names in the source while targeting obfuscated bytecode.

**Relevance to Mycelium**: Low. GraalJS isn't typically obfuscated, and Truffle DSL-generated classes aren't stable across versions anyway. Refmaps solve a Minecraft-specific problem (dealing with multiple obfuscation schemes across versions). However, we could use refmaps for tracking through Truffle DSL-generated class names if needed (less critical).

### 5. Standard Configuration Format

Mixin configs are JSON files (`mixins.example.json`) that list mixin classes and injection points. This is standard across all Mixin users.

**Relevance**: Mycelium already has `mod.json`; adding Mixin configs would add an extra layer of configuration that mod authors must understand.

---

## Integration Costs and Challenges

### 1. Dependency Size

**Mixin Core JAR size**: ~400 KB (as of 2026).  
**Transitive dependencies**: Mixin itself depends only on ASM and SLF4J (both lightweight); SLF4J is ~400 KB.

**Current PatchDsl**: ASM (9.7) is ~350 KB.

**Net addition**: ~400 KB for Mixin + ~400 KB for SLF4J if not already present = ~800 KB total. For a mod loader, this is noticeable but not prohibitive.

### 2. Custom MixinService Implementation

Mixin provides several built-in services:
- **LegacyLauncher** (LaunchWrapper) — deprecated
- **ModLauncher** — Forge's launcher; requires Forge infrastructure
- **Java Agent Mode** (org.spongepowered.tools.agent.MixinAgent) — uses premain/agentmain

Mycelium's current architecture is a `-javaagent` that loads mods from a directory and applies them via `Instrumentation.addTransformer`. Mixin's built-in services don't match this exactly, so we'd need a **custom MixinService** implementation.

This is doable — Mixin's SPI is well-documented — but it's a non-trivial implementation. We'd need to:
1. Implement `MixinService` interface
2. Hook into the classloader at the right points to intercept class loads
3. Parse and register mixin configs
4. Handle mixin loading order and conflict resolution

**Estimated effort**: 500–1000 lines of Kotlin, 1–2 weeks of development/testing.

### 3. Mixin Configuration Per Mod

Every mod that wants to use Mixin annotations needs:
1. A `mixins.modid.json` config file listing mixin classes
2. Build-time annotation processor configuration in `build.gradle.kts`

**Mycelium's current state**: Mods only need a `mod.json` and a class implementing `ModTransformer`.

**Migration path**: 
- Existing mods using PatchDsl would stay as-is (still work)
- New mods could opt into Mixin
- But supporting both means maintaining two parallel transformation paths

### 4. Incompatibility with Truffle DSL-Generated Classes

Mixin targets are expected to be stable bytecode across versions. However, **Truffle DSL generates new classes (`*Gen`) on every build**, based on the DSL annotations in the source. The generated class bytecode can change significantly across Truffle versions.

**Example**: If a mod targets `JSAddNodeGen` via `@Mixin`, it works for GraalJS 25.0.0 (which has Truffle X.Y.Z). In GraalJS 26.0.0, the generated class bytecode might change, breaking the mixin.

**Mitigation**: 
- Target the hand-written base classes (e.g., `JSAddNode`) instead of generated ones
- But then the mod can't intercept the actual specialized node logic that lives in generated subclasses

This is solvable but requires careful documentation and isn't a blocker.

### 5. Maintenance and Version Lock-In

Once Mixin is integrated:
- We depend on SpongePowered to maintain Mixin
- Any breaking changes in Mixin force us to adapt
- We become responsible for any bugs/issues specific to Mycelium's use of Mixin (we're the first non-Minecraft consumer)
- If Mixin development stalls or the project is abandoned, we own the problem

**Current state**: Mycelium owns PatchDsl entirely; it's simple enough to maintain forever.

### 6. Learning Curve for Mod Authors

Mixin's annotation-based approach is easier for very simple mods but adds complexity for advanced use cases:
- Understanding `@At` syntax and injection points
- Reasoning about `CallbackInfo` and how injections compose
- Debugging mixin transformations (stack traces point to generated bytecode, not source)

However, Mixin has a large, active community (Minecraft modding), so documentation and examples are abundant.

---

## What Would Enhanced PatchDsl Achieve?

Instead of integrating Mixin wholesale, we could invest in PatchDsl improvements to solve the main pain points:

### 1. Automatic Frame Computation

Add a mode to `patchClass()` that enables `COMPUTE_FRAMES`:

```kotlin
fun patchClass(classfileBuffer: ByteArray, computeFrames: Boolean = false, block: ClassPatcher.() -> Unit): ByteArray? {
    val classWriter = ClassWriter(
        if (computeFrames) ClassWriter.COMPUTE_FRAMES else ClassWriter.COMPUTE_MAXS
    )
    // ... rest unchanged
}
```

**Impact**: Solves the StackMapTable boilerplate problem. Mod authors using `atMethodEntry` with branch targets no longer need to manually construct frames.

**Cost**: ~5 lines of code change. One caveat: `COMPUTE_FRAMES` mode is slower than `COMPUTE_MAXS`, but this is negligible at class-load time (frames are computed once per class, not per invocation).

**Remaining problem**: Still requires understanding of `atMethodEntry` / branch targets / frames; just shifts the work from manual construction to letting ASM compute it.

### 2. High-Level Injection API

Add `@Inject` / `@Redirect` style methods to the DSL:

```kotlin
patchClass(classfileBuffer) {
    injectAtMethodEntry("getValue") {
        // This becomes:
        // if (receiver == Null.instance) return defaultValue;
        checkReceiverNullAndReturn(aload(1), ldc(...), ldc(...))
    }
}
```

This is more complex — essentially embedding Mixin's annotation semantics as DSL methods. The benefit is small for PatchDsl's current scope.

### 3. Build-Time Validation

Add a Gradle plugin that validates `ModTransformer` implementations at build time:
- Check that target classes exist (by scanning GraalJS JAR if provided)
- Check that target methods exist
- Report missing or misspelled class/method names early

**Cost**: 200–500 lines of Gradle plugin code.

**Impact**: Catches typos and mismatch errors at compile time instead of runtime.

### 4. Mod Dependency Resolution

Implement the `depends` / `breaks` fields in `mod.json`:
- Topological sort of mods by dependency
- Version checking
- Fail cleanly if dependencies are unmet

**Cost**: ~200 lines of code.

**Impact**: Better error messages; clearer semantics for mod interactions.

---

## Is Mycelium a Suitable First Non-Minecraft Use Case for Mixin?

**No**, for these reasons:

1. **Mycelium is small-scale** — Only ~6 existing mods, all maintained by the same team or trusted contributors. Conflict detection is nice-to-have, not critical.

2. **Truffle's DSL-generated classes are a mismatch** — Mixin was built for Minecraft's stable bytecode. Truffle invalidates that assumption.

3. **Integration cost is non-trivial** — Custom MixinService, config files per mod, annotation processor setup, all for modest gains in a small ecosystem.

4. **PatchDsl works well for the scope** — Simple redirects and splicing are clean and easy to understand. The only real pain is frame computation, which is a solvable problem without Mixin.

5. **Being a beta/novel user of Mixin is a liability** — Any issues we hit become our responsibility to work around or patch. The Minecraft community is vast, but Truffle modding is specialized.

---

## Comparison Table

| Criterion | PatchDsl (Current) | PatchDsl (Enhanced) | Mixin |
|-----------|-------------------|-------------------|-------|
| **Frame Computation** | Manual (painful) | Automatic (proposed) | Automatic (built-in) |
| **Build-Time Validation** | None | Possible (gradle plugin) | Built-in (annotation processor) |
| **Conflict Detection** | None | Possible (enhancement) | Priority-based (built-in) |
| **Dependencies** | ASM only (~350 KB) | ASM only | ASM + Mixin + SLF4J (~800 KB) |
| **Mod Author Learning Curve** | Medium (DSL + bytecode) | Medium (same) | Medium-High (annotations + bytecode) |
| **Integration Effort** | N/A (done) | Low (2–3 weeks) | High (3–4 weeks) |
| **Maintenance Burden** | Low (ours to own) | Low (ours to own) | Medium (dependent on SpongePowered) |
| **Community/Documentation** | Internal only | Internal only | Large (Minecraft) but not Truffle-focused |
| **Ecosystem Compatibility** | Minecraft incompatible | Minecraft incompatible | Minecraft compatible (but not useful here) |

---

## Recommendation

**Do NOT integrate Mixin at this time.**

Instead, **invest in PatchDsl improvements**:

### Phase 1: Solve Frame Computation (Immediate)
- Implement `COMPUTE_FRAMES` mode toggle in `patchClass()`
- Update `null-safe-mod` and `integer-division-mod` to use it
- Eliminate ~160 lines of frame boilerplate

**Effort**: 1–2 days | **Impact**: Solves the biggest pain point

### Phase 2: Add Mod Dependency Resolution (Short-term)
- Implement `depends` / `breaks` enforcement in mod loader
- Better error messages for unmet dependencies

**Effort**: 3–5 days | **Impact**: Better mod compatibility and error messages

### Phase 3: Consider Build-Time Validation (Future)
- Add Gradle plugin for validating transformers
- Optional: add annotation processor for declarative mod intent (lighter-weight than Mixin)

**Effort**: 1–2 weeks | **Impact**: Catch errors early; foundation for larger mod ecosystem

### Phase 4: Revisit Mixin (Only if needed)
- After Mycelium scales to 20+ mods with active incompatibilities, revisit Mixin integration
- At that point, the conflict detection and composition semantics become valuable
- The integration cost is still high but may be justified by ecosystem growth

---

## Risks of This Decision

1. **Mod authors hitting frame complexity** — If we don't implement `COMPUTE_FRAMES` mode, new complex mods will face the same boilerplate burden. Mitigation: Phase 1 must happen soon.

2. **Conflict detection gap** — If many mods start targeting the same methods, lack of conflict detection becomes painful. Mitigation: Phase 2 helps here; Phase 3 (future) could add annotation-based mod intent for static analysis.

3. **Mycelium staying niche** — Without Mixin's ecosystem compatibility, Mycelium never attracts Minecraft modding community members. But that's not a stated goal, and Mycelium targets Truffle/GraalJS, not Minecraft.

---

## Appendix: What the Research Document Already Concluded

The existing research in `java-modding-systems.md` identified several key design decisions:

1. **Hooking mechanism**: Custom classloader (our current approach) vs. `-javaagent` vs. build-time. We use `-javaagent`, which is fine.

2. **Transformation granularity**: Method-body injection (Mixin's `@Inject`, similar to PatchDsl's `afterCallsTo`) vs. method replacement vs. instruction redirect. We support all three.

3. **Conflict resolution**: Priority-based (Mixin's approach) vs. composition vs. explicit. We have none; Mixin would add priority-based.

4. **Target declaration**: Annotation-driven (Mixin) vs. programmatic (PatchDsl) vs. config-file (Forge ATs). PatchDsl is programmatic; Mixin is annotation-driven.

5. **DSL-generated class targeting**: Whether to target hand-written classes or generated `*Gen` subclasses. Not Mixin's responsibility; up to mod authors. We should document this.

6. **Node replacement interaction**: Whether patched nodes survive Truffle's node-replacement machinery. Not Mixin's responsibility; relevant to our mod design, not Mixin integration.

7. **Native Image story**: Mixin doesn't work with Native Image (requires runtime class loading). If Mycelium ever targets Native Image, bytecode patching at runtime is impossible; would need build-time transform.

---

## Conclusion

Mycelium's hand-written PatchDsl is **appropriate for its current scale and use case**. The main pain point (StackMapTable frame management) is solvable with a targeted enhancement (COMPUTE_FRAMES mode), delivering 80% of Mixin's benefit without the integration burden, dependency bloat, or lock-in to Mixin's maintenance.

Mixin would be valuable if Mycelium:
- Attracted dozens of third-party mod authors (requiring conflict detection)
- Became part of a larger modding ecosystem (where Mixin's standardization matters)
- Had strong community-driven demand for Mixin compatibility

None of these are true today. Revisit this decision when Mycelium's mod ecosystem grows materially.
