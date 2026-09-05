plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.core.exitinfo"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
