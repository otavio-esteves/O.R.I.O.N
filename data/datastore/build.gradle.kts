plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.data.datastore"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
}
