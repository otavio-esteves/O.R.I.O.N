plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.voice.ipc"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    api(project(":core:ipc"))
    testImplementation(libs.junit4)
}
