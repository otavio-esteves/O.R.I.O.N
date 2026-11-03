plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "network.orion.feature.diagnostics"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    buildFeatures { compose = true }

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    implementation(project(":core:ipc"))
    implementation(project(":core:health"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}
