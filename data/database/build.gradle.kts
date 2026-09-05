plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.data.database"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
