plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":core:ipc"))
    api(project(":core:time"))
    testImplementation(libs.junit4)
}

android {
    namespace = "network.orion.ai.api"
    buildFeatures { aidl = true }
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
