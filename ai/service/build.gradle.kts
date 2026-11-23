plugins { alias(libs.plugins.android.library) }

val metricsFlag = providers.gradleProperty("orionCortexGenerationMetrics").orElse("false").get()
check(metricsFlag in setOf("true", "false")) { "orionCortexGenerationMetrics must be true or false." }
val metricsEnabled = metricsFlag == "true"
val qualificationFlag = providers.gradleProperty("orionQualificationRelease").orElse("false").get()
check(qualificationFlag in setOf("true", "false")) { "orionQualificationRelease must be true or false." }
val qualificationEnabled = qualificationFlag == "true"
check(!metricsEnabled || qualificationEnabled) {
    "Optional Cortex generation metrics require the separate qualification identity."
}

android {
    namespace = "network.orion.ai.service"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ordinary Debug/Release remain disabled even when both opt-in properties are true.
        buildConfigField("boolean", "ORION_GENERATION_METRICS", "false")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        create("qualificationRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ORION_GENERATION_METRICS", metricsEnabled.toString())
        }
    }
}

// Match the separate application identity; its normal variants never select this source.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("qualificationRelease")) {
    it.enable = qualificationEnabled
}

dependencies {
    implementation(project(":ai:api"))
    implementation(project(":ai:llama"))
    implementation(project(":core:ipc"))
    implementation(project(":core:time"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
