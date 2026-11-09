plugins { alias(libs.plugins.android.library) }
android {
    namespace = "network.orion.ai.service"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(project(":ai:api"))
    implementation(project(":ai:cortex"))
    implementation(project(":core:common"))
    implementation(project(":core:time"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
