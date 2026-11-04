plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.data.database"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Both variants export the same versioned schema. Avoid concurrent writes to its JSON file.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}

dependencies {
    implementation(project(":core:common"))
    api(project(":core:actions"))
    api(project(":core:model"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
}
