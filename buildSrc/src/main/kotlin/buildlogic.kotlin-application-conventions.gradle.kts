plugins {
    id("buildlogic.kotlin-common-conventions")
    application
}

application {
    applicationDefaultJvmArgs = listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI")
}
