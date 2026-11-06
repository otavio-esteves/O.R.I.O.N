plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.data.memory"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }
}


dependencies {
    implementation(project(":core:commands"))
    implementation(project(":core:queries"))
    implementation(project(":core:skills:metadata"))
    implementation(project(":data:actions"))
    implementation(project(":data:database"))
    implementation(project(":core:security"))
    implementation(project(":core:time"))
}
