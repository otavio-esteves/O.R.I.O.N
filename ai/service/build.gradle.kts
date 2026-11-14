plugins { alias(libs.plugins.android.library) }

val metricsFlag = providers.gradleProperty("orionCortexGenerationMetrics").orElse("false").get()
check(metricsFlag in setOf("true", "false")) { "orionCortexGenerationMetrics must be true or false." }
val metricsEnabled = metricsFlag == "true"
check(!metricsEnabled || providers.gradleProperty("orionQualificationRelease").orElse("false").get() == "true") {
    "Optional Cortex generation metrics require the separate qualification identity."
}

android {
    namespace = "network.orion.ai.service"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "ORION_GENERATION_METRICS", metricsEnabled.toString())
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":ai:api"))
    implementation(project(":ai:llama"))
    implementation(project(":core:time"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
