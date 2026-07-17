# Mycelium Mod Authoring Guide

Mycelium is a mod loader for GraalJS that patches Truffle runtime classes at JVM class-load time via bytecode manipulation. This guide walks you through creating a mod.

## Quick Start

A mod is a JAR file containing:
1. A `mod.json` descriptor at the root
2. One or more `ModTransformer` implementation classes
3. A `build.gradle.kts` that assembles the JAR

### File Structure

```
my-mod/
├── build.gradle.kts
└── src/main/kotlin/com/example/
    └── MyTransformer.kt
```

### Minimal mod.json

```json
{
  "id": "my-mod",
  "name": "My Mod",
  "version": "1.0.0",
  "description": "Brief description of what this mod does",
  "targetRuntime": "graaljs",
  "targetVersion": "25.0.0",
  "transformers": ["com.example.MyTransformer"],
  "priority": 1000,
  "depends": {},
  "breaks": {}
}
```

### Minimal Transformer

```kotlin
package com.example

import com.oracle.graalvm.modloader.ModTransformer
import com.oracle.graalvm.modloader.patchClass

class MyTransformer : ModTransformer {
    override fun targetClasses(): Set<String> =
        setOf("com/oracle/truffle/js/nodes/MyTargetClass")

    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        return patchClass(classfileBuffer) {
            // Patch operations here
        }
    }
}
```

### Minimal build.gradle.kts

```kotlin
plugins { id("org.jetbrains.kotlin.jvm") }

repositories { mavenCentral() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    compileOnly(project(":mod-loader"))
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-tree:9.7")
}

tasks.jar {
    archiveBaseName.set("my-mod")
}
```

## Discovering Classes to Patch

Patching the right class is half the battle. The workflow:

### 1. Find the GraalJS JAR

```bash
find ~/.gradle -name "js-language-25.0.0.jar"
```

### 2. List classes in the JAR

```bash
jar tf js-language-25.0.0.jar | grep YourClassName
```

For example, if you want to intercept property access:
```bash
jar tf js-language-25.0.0.jar | grep PropertyGetNode
```

### 3. Disassemble to understand the class

```bash
javap -c -p -cp js-language-25.0.0.jar com.oracle.truffle.js.nodes.PropertyGetNode
```

The `-c` flag shows bytecode, `-p` shows private members. Study the methods you want to patch to understand their structure and calling patterns.

### 4. Look for call sites you can intercept

Search for `invokestatic`, `invokevirtual`, `invokeinterface` instructions. These are often good patch points. For example, if a method calls `doInt(a, b)`, you can:
- Redirect calls to `doInt` to your own implementation
- Splice code before or after the call
- Wrap the method with entry/exit hooks

## The PatchDsl API

Inside a `patchClass(bytecode) { ... }` block, you have these operations:

### afterCallsTo — Inject code after calls

```kotlin
patchClass(classfileBuffer) {
    afterCallsTo("com/example/MyClass", "doSomething", "doOtherThing") {
        // This block runs after each matching call
        // Stack has the return value at the top
        iconst(42)
        iadd()  // Add 42 to the return value
    }
}
```

Useful when you want to transform a method's result or add side effects after a call.

### beforeCallsTo — Inject code before calls

```kotlin
patchClass(classfileBuffer) {
    beforeCallsTo("com/oracle/truffle/js/runtime/JavaScriptException", "create") {
        // This runs before the call
        // Can manipulate arguments on the stack
        dup()  // Duplicate the first argument
    }
}
```

Use this to inspect or modify arguments before they're passed to a method.

### redirectCallsTo — Replace a method with another

```kotlin
patchClass(classfileBuffer) {
    redirectCallsTo("com/oracle/truffle/js/nodes/JSEqualNode", "create")
        .to("com/oracle/truffle/js/nodes/JSIdenticalNode", "create")
}
```

Every call to `JSEqualNode.create(...)` becomes `JSIdenticalNode.create(...)`. The simplest and most reliable patch pattern.

### atMethodEntry — Inject at method start

```kotlin
patchClass(classfileBuffer) {
    atMethodEntry("getValue") {
        // Runs before any code in the method
        // Useful for entry guards or logging
        invokestatic("java/lang/System", "out", "()Ljava/io/PrintStream;")
        ldc("Entering getValue")
        invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
    }
}
```

Complex patches (like `null-safe-mod`) use this with careful StackMapTable management.

### atMethodReturn — Inject before returns

```kotlin
patchClass(classfileBuffer) {
    atMethodReturn("getValue") {
        // Runs before each ARETURN or IRETURN
        // Value to be returned is at stack top
        dup()  // Duplicate it for inspection
    }
}
```

Use to transform return values or add post-processing.

### InsnBuilder — Building bytecode inside a block

The `InsnBuilder` DSL provides shortcuts for common instructions:

```kotlin
patchClass(classfileBuffer) {
    afterCallsTo("com/example/Math", "add") {
        // Stack operations
        dup()           // Duplicate top of stack
        pop()           // Remove top of stack
        swap()          // Swap top two stack values
        
        // Constants
        iconst(5)       // Push int constant 5
        ldc("string")   // Push String or any constant
        
        // Local variable access
        iload(0)        // Load int local 0
        aload(1)        // Load object reference local 1
        istore(2)       // Store int to local 2
        astore(3)       // Store reference to local 3
        
        // Arithmetic (only works on stack values)
        iadd()          // Pop two ints, push sum
        isub()          // Pop two ints, push difference
        imul()          // Multiply
        idiv()          // Divide
        ineg()          // Negate
        
        // Method calls
        invokestatic("java/lang/Math", "abs", "(I)I")
        invokevirtual("java/lang/String", "length", "()I")
        
        // Field access
        getstatic("com/example/Config", "DEBUG", "Z")  // Get boolean
        putstatic("com/example/Config", "DEBUG", "Z")  // Set boolean
        getfield("com/example/MyClass", "field", "I")
        putfield("com/example/MyClass", "field", "I")
        
        // Returns
        ireturn()       // Return int at top of stack
        areturn()       // Return reference at top of stack
        
        // Escape hatch for anything not in the DSL
        raw(InsnNode(ICONST_0))
    }
}
```

The DSL handles method descriptor strings automatically for common cases. For complex descriptors, build the instruction directly and pass to `raw()`.

## mod.json Descriptor Reference

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | string | yes | Unique identifier for this mod (no spaces) |
| `name` | string | yes | Human-readable name |
| `version` | string | yes | Semantic versioning (e.g., "1.0.0") |
| `description` | string | yes | What the mod does |
| `targetRuntime` | string | yes | Must be `"graaljs"` for now |
| `targetVersion` | string | yes | GraalJS version (e.g., `"25.0.0"`) |
| `transformers` | array | yes | Fully qualified class names of ModTransformer impls |
| `priority` | number | no | Lower values run first (default: 1000) |
| `depends` | object | no | Map of mod IDs to version constraints (not yet enforced) |
| `breaks` | object | no | Map of mod IDs that conflict with this one (not yet enforced) |

### Example with dependencies:

```json
{
  "id": "my-mod",
  "name": "My Mod",
  "version": "1.0.0",
  "description": "Depends on another mod",
  "targetRuntime": "graaljs",
  "targetVersion": "25.0.0",
  "transformers": ["com.example.MyTransformer"],
  "priority": 900,
  "depends": {
    "base-mod": "1.0.0"
  },
  "breaks": {
    "conflicting-mod": "*"
  }
}
```

## Priority and Load Order

Mods are topologically sorted by dependency, then by priority. Lower priority values run first:

```
priority=100  ← runs first
priority=500
priority=1000 ← default
priority=5000 ← runs last
```

If two mods target the same class, both patches apply — the lower-priority mod patches the bytecode first, then the higher-priority mod patches the result. Plan your patch order carefully if mods compose.

## Real Examples

Mycelium includes three example mods in the codebase:

### cursed-addition (example-mod/)

Patches `JSAddNodeGen` to make integer addition return one extra:

```kotlin
class CursedAdditionTransformer : ModTransformer {
    override fun targetClasses() = setOf("com/oracle/truffle/js/nodes/binary/JSAddNode")
    
    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        return patchClass(classfileBuffer) {
            redirectCallsTo("com/oracle/truffle/js/nodes/binary/JSAddNode", "doInt")
                .to("org/mycelium/example/CursedAdditionTransformer", "cursedDoInt")
            
            afterCallsTo("com/oracle/truffle/js/nodes/binary/JSAddNode", "doIntTruncate") {
                iconst(1)
                iadd()  // Add 1 to the result
            }
        }
    }
}
```

This demonstrates both `redirectCallsTo` (routing to a replacement method) and `afterCallsTo` (splicing bytecode).

### null-safe (null-safe-mod/)

Patches property access to return `undefined` instead of throwing when accessing null/undefined:

```kotlin
class NullSafeTransformer : ModTransformer {
    override fun targetClasses() = setOf(
        "com/oracle/truffle/js/nodes/access/PropertyGetNode\$TypeErrorPropertyGetNode"
    )
    
    override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
        return patchClass(classfileBuffer) {
            atMethodEntry("getValue") {
                // Inject null check and return undefined if needed
                aload(1)  // receiver
                // ... complex bytecode with StackMapTable
            }
        }
    }
}
```

This shows a more complex patch using `atMethodEntry` with careful stack frame management.

### strict-equality (strict-equality-mod/)

Makes `==` behave like `===` by redirecting the factory method:

```kotlin
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

The simplest pattern — a single redirect. Most mods should aim for this level of simplicity.

## Important Gotcha: Constant Folding

GraalJS constant-folds literal arithmetic at parse time. For example:

```javascript
let x = 1 + 1;  // Parser folds this to 2 before any node exists
let y = a + 1;  // This creates a node at runtime
```

Mod patches on arithmetic nodes only affect the second case. Your patch will intercept `a + 1` but not `1 + 1` because the constant is eliminated before your bytecode patch point.

**Implications:**
- Demos like cursed-addition work as a reference, but don't patch every `+` in real code
- If you need to intercept literal arithmetic, you'd need to patch the parser itself (much harder)
- Variable arithmetic is the common case anyway

Plan your mods with this in mind. Test with real code, not just contrived constant examples.

## Tips and Patterns

### Debugging patches

Print the bytecode you're patching:

```kotlin
override fun transform(className: String, classfileBuffer: ByteArray): ByteArray? {
    System.err.println("Patching $className")
    System.err.println("Bytecode size: ${classfileBuffer.size}")
    return patchClass(classfileBuffer) { ... }
}
```

### Testing your mod locally

1. Build: `./gradlew :my-mod:jar`
2. Place the JAR in your mods directory
3. Run GraalJS with `-javaagent:mod-loader.jar`
4. Check logs for load errors

### Working with method signatures

Method descriptors in Java bytecode use a specific format:

```
(ParamType1ParamType2)ReturnType

Examples:
(II)I        — two ints, returns int
(Ljava/lang/String;)V  — String, returns void
()Ljava/lang/Object;   — no params, returns Object
```

Common type abbreviations:
- `I` = int
- `J` = long
- `Z` = boolean
- `V` = void
- `L...;` = object reference

### Redirecting methods

When using `redirectCallsTo`, the replacement method must have a compatible signature:

```kotlin
// Original: public static int doInt(int a, int b)
// Replacement must also return int and take two ints
fun doIntPlusOne(a: Int, b: Int): Int = a + b + 1
```

The redirect is purely bytecode-level — use `invokestatic` for static methods, `invokevirtual` for instance methods.

### Handling complex patches

If your patch gets complex, break it into multiple methods and call them:

```kotlin
patchClass(classfileBuffer) {
    afterCallsTo("com/example/Math", "add") {
        invokestatic("com/example/Hooks", "logAddition", "(I)I")
    }
}
```

Then implement `Hooks.logAddition` in your mod JAR. Keeps the bytecode manipulation simple.

### Testing with example inputs

Create a small Kotlin test that calls your target classes with the mod enabled:

```kotlin
// In your test
fun testModWorks() {
    val result = 5 + 3  // Should be intercepted by your mod
    assertEquals(expected, result)
}
```

Run with the agent: `java -javaagent:mod-loader.jar YourTest`

## Building and Distributing Mods

1. **Build**: `./gradlew :my-mod:jar`
2. **Output**: `my-mod/build/libs/my-mod.jar`
3. **Install**: Place in your configured mods directory
4. **Load**: GraalJS automatically discovers and loads mods on startup

The mod loader validates:
- `mod.json` is well-formed
- All transformer classes are found in the JAR
- Version compatibility with the target runtime
- Dependency resolution (if implemented)

Mods are loaded in priority order before any user code runs.

## Next Steps

1. Explore the example mods in the codebase
2. Use `javap` to find the classes you need to patch
3. Start with simple redirects (`redirectCallsTo`)
4. Graduate to `afterCallsTo` and `beforeCallsTo` for more control
5. Only use `atMethodEntry` if you need complex control flow
