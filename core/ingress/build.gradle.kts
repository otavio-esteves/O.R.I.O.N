plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.core.ingress"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}
