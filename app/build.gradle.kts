plugins {
    id("buildlogic.kotlin-application-conventions")
    id("org.graalvm.buildtools.native") version "0.10.4"
}

dependencies {
    implementation(project(":library"))
}

application {
    mainClass = "org.mycelium.app.AppKt"
    applicationName = "mycelium"
}

// Run via: ./gradlew :app:run --args="run path/to/file.mjs"
// (the `application` plugin forwards --args to main(args) automatically)
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("org.mycelium.app.AppKt")
            imageName.set("mycelium")
            buildArgs.add("--initialize-at-build-time")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-J-Xmx8g")
        }
    }
}
