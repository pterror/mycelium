plugins { id("buildlogic.kotlin-application-conventions") }

dependencies {
    implementation(project(":library"))
}

application { mainClass = "org.mycelium.app.AppKt" }

// Run via: ./gradlew :app:run --args="run path/to/file.mjs"
// (the `application` plugin forwards --args to main(args) automatically)
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
