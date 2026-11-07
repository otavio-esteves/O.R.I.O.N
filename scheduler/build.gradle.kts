plugins {
    alias(libs.plugins.android.library)
}

dependencies {
    testImplementation(libs.junit4)
}

android {
    namespace = "network.orion.scheduler"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
