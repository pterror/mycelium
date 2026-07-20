plugins {
    id("buildlogic.kotlin-application-conventions")
}

// Forces :library's build.gradle.kts to be evaluated before this file finishes configuring
// — needed so the `project(":library").configurations.getByName("rubyLang")` (etc.) lookups
// in the `jvmRun` task below can find the per-language-pack Configurations :library declares,
// rather than racing Gradle's default (unspecified) cross-project configuration order.
evaluationDependsOn(":library")

dependencies {
    implementation(project(":library"))
}

application {
    mainClass = "org.mycelium.app.AppKt"
    applicationName = "mycelium"
}

// Run via: ./gradlew :app:run --args="run path/to/file.mjs"
// (the `application` plugin forwards --args to main(args) automatically)
//
// Since library/build.gradle.kts moved ALL GraalVM language packs (including JS) off
// `implementation` and onto `compileOnly` + dedicated per-language Configurations, the
// plain `application` plugin's `run` task's classpath — the main source set's
// runtimeClasspath — now carries only the language-agnostic Truffle/polyglot framework
// with NO languages at all. This task is therefore the "bare core" execution path;
// use `jvmRun` below (which defaults to JS) for actual script execution.
//
// `jvmRun` (JVM mode) is the primary way to run Mycelium — see its doc comment below.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// JVM-mode language packs available to add to `jvmRun`'s classpath, keyed by the `-P`
// property value a caller passes (e.g. `-Planguages=js,ruby`) to the Configuration name
// declared in library/build.gradle.kts.
val languagePackConfigNames = mapOf(
    "js" to "jsLang",
    "ruby" to "rubyLang",
    "python" to "pythonLang",
    "java" to "javaLang",
    "llvm" to "llvmLang",
    "wasm" to "wasmLang",
    "lua" to "luaLang",
)

// `./gradlew :app:jvmRun --args="run path/to/file.mjs"`
// `./gradlew :app:jvmRun --args="run path/to/file.rb" -Planguages=js,ruby`
//
// Runs Mycelium in JVM mode with the core runtime plus whichever Truffle language packs
// are named in the `-Planguages` property (comma-separated: `js`, `ruby`, `python`,
// `java`, `llvm`, `wasm`, `lua`). **Defaults to `js` when no `-Planguages` is specified**, so
// existing usage (`./gradlew :app:jvmRun --args="run file.mjs"`) keeps working without
// having to spell out `-Planguages=js` every time. Pass `-Planguages=` (empty) to run
// with NO languages at all (useful only for probing — Mycelium will report an error
// since it has no supported extensions).
val jvmRun by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Run Mycelium in JVM mode. Pass -Planguages=js,ruby,python,java,llvm,wasm,lua to select language packs (default: js)."
    mainClass.set("org.mycelium.app.AppKt")
    workingDir = rootDir
    // insightTool is added unconditionally (not gated behind -Planguages, since it's an
    // instrument rather than a language pack) so the GraalVM Insight instrument is always
    // on jvmRun's classpath — needed for Mycelium.insightHandle's Engine#instruments["insight"]
    // lookup to find anything when a caller passes --insight=<script>.
    classpath = sourceSets.main.get().runtimeClasspath + project(":library").configurations.getByName("insightTool")

    val languageProp = project.findProperty("languages") as String?
    val requested = when {
        // Explicit `-Planguages=...` (including `-Planguages=` for empty)
        languageProp != null -> languageProp.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // No `-Planguages` at all — default to JS so existing usage doesn't break.
        else -> listOf("js")
    }
    for (pack in requested) {
        val configName = languagePackConfigNames[pack]
            ?: throw GradleException(
                "mycelium: unknown language pack '$pack' — available: " +
                    languagePackConfigNames.keys.sorted().joinToString(", ")
            )
        classpath += project(":library").configurations.getByName(configName)
    }

    // `-Pmod`: attaches the mod-loader jar as a `-javaagent:` and points it at a mods
    // directory containing example-mod's, null-safe-mod's, strict-equality-mod's,
    // integer-division-mod's, operator-overloading-mod's, and ptc-mod's jars. example-mod,
    // via ASM, patches GraalJS's JSAddNodeGen at class-load time so int addition returns one
    // extra — e.g. `1 + 1 === 3`. null-safe-mod patches
    // PropertyGetNode$TypeErrorPropertyGetNode.getValue so that
    // `null.foo`/`undefined.foo` return `undefined` instead of throwing a TypeError.
    // strict-equality-mod patches NodeFactory.createBinaryEqual so `==`/`!=` behave like
    // `===`/`!==`. integer-division-mod patches JSDivideNode so `/` truncates to integer,
    // C-style. operator-overloading-mod patches JSContextOptions.isOperatorOverloading
    // (and, belt-and-suspenders, OperatorsBuiltins.checkOverloadedOperatorsAllowed) so
    // GraalJS's built-in (but normally opt-in) `Operators(...)` overloading API is always
    // exposed as a JS global. ptc-mod patches ReturnNode$TerminalPositionReturnNode,
    // JSFunctionCallNode, and FunctionRootNode to trampoline self tail-recursive calls
    // (`return f(...)` inside `f`) so they run in a loop instead of growing the stack. See
    // mod-loader/ for the generic loader, example-mod/, null-safe-mod/,
    // strict-equality-mod/, integer-division-mod/, operator-overloading-mod/, and ptc-mod/
    // for those mods. Pass `-PmodsDir=<path>` to point at a
    // different mods directory instead of the default (a `mods/` staging directory under
    // the build dir, populated with all mods' jars).
    if (project.hasProperty("mod")) {
        dependsOn(
            ":mod-loader:jar",
            ":example-mod:jar",
            ":null-safe-mod:jar",
            ":strict-equality-mod:jar",
            ":integer-division-mod:jar",
            ":operator-overloading-mod:jar",
            ":ptc-mod:jar",
        )
        val agentJar = project(":mod-loader").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val exampleModJar = project(":example-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val nullSafeModJar = project(":null-safe-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val strictEqualityModJar = project(":strict-equality-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val integerDivisionModJar = project(":integer-division-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val operatorOverloadingModJar = project(":operator-overloading-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val ptcModJar = project(":ptc-mod").tasks.named<Jar>("jar").flatMap { it.archiveFile }
        val modsDirProp = project.findProperty("modsDir") as String?
        val stagedModsDir = layout.buildDirectory.dir("mods")
        doFirst {
            val modsDir = if (modsDirProp != null) {
                File(modsDirProp)
            } else {
                val dir = stagedModsDir.get().asFile
                dir.mkdirs()
                exampleModJar.get().asFile.copyTo(File(dir, "example-mod.jar"), overwrite = true)
                nullSafeModJar.get().asFile.copyTo(File(dir, "null-safe-mod.jar"), overwrite = true)
                strictEqualityModJar.get().asFile.copyTo(File(dir, "strict-equality-mod.jar"), overwrite = true)
                integerDivisionModJar.get().asFile.copyTo(File(dir, "integer-division-mod.jar"), overwrite = true)
                operatorOverloadingModJar.get().asFile.copyTo(File(dir, "operator-overloading-mod.jar"), overwrite = true)
                ptcModJar.get().asFile.copyTo(File(dir, "ptc-mod.jar"), overwrite = true)
                dir
            }
            jvmArgs(
                "-javaagent:${agentJar.get().asFile.absolutePath}",
                "-Dmycelium.mods.dir=${modsDir.absolutePath}",
            )
        }
    }
}

tasks.register("printJvmRunClasspath") {
    dependsOn(jvmRun)
    doLast {
        println(jvmRun.get().classpath.asPath)
    }
}
