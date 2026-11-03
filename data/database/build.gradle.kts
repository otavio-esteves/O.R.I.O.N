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

dependencies {
    implementation(project(":core:common"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
}
