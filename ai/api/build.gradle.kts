plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    api(project(":core:time"))
    api(project(":core:ipc"))
    testImplementation(libs.junit4)
}

android {
    namespace = "network.orion.ai.api"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
