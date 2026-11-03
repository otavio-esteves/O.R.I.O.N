plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.core.resources"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    api(project(":core:exitinfo"))

    testImplementation(libs.junit4)
}
