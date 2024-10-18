plugins { id("org.jetbrains.kotlin.jvm") }

repositories { mavenCentral() }

dependencies {
    constraints { implementation("org.apache.commons:commons-text:1.11.0") }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks.named<Test>("test") { useJUnitPlatform() }
