plugins { id("buildlogic.kotlin-application-conventions") }

dependencies {
    implementation(project(":library"))
}

application { mainClass = "org.mycelium.app.AppKt" }
