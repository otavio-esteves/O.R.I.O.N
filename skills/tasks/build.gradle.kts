plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "network.orion.skills.tasks"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
}

dependencies {
    api(project(":skills:api"))
    implementation(project(":core:commands"))
    implementation(project(":data:tasks"))
    testImplementation(libs.junit4)
}
