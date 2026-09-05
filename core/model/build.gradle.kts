plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.core.model"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    testImplementation(libs.junit4)
}
