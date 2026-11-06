plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "network.orion.feature.timers"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    buildFeatures { compose = true }

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    implementation(project(":core:orchestrator"))
    implementation(libs.coroutines.android)
    // Same pinned lifecycle version already selected by Activity Compose 1.13.0.
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
}
