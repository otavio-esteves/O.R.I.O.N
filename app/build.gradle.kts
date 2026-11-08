plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val nativeGateText = providers.fileContents(rootProject.layout.projectDirectory.file(
    "docs/compatibility/native-compatibility-gate.json")).asText.get()
val nativeStatusMatches = Regex("\"status\"\\s*:\\s*\"(PENDING|PASS|FAIL|NOT_APPLICABLE_NO_NATIVE_ARTIFACTS)\"")
    .findAll(nativeGateText).toList()
check(nativeStatusMatches.size == 1) { "Native qualification metadata must have one recognized status." }
val nativeQualificationStatus = nativeStatusMatches.single().groupValues[1]

android {
    namespace = "network.orion.app"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "network.orion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 5
        versionName = "0.4.0-candidate"
        resValue("string", "orion_native_qualification_status", nativeQualificationStatus)
    }

    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
        release { ndk { abiFilters += "arm64-v8a" } }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/data/database/schemas")
}

dependencies {
    // AGP aligns tests with their target APK. Room's schema serializers require the newer default methods.
    debugImplementation(libs.serialization.test.runtime)
    debugImplementation(project(":core:ipc"))
    testImplementation(libs.junit4)
    implementation(project(":core:common"))
    implementation(project(":scheduler"))
    implementation(libs.work.runtime)
    implementation(project(":core:health"))
    implementation(project(":core:time"))
    implementation(project(":core:observability"))
    implementation(project(":core:recovery"))
    implementation(project(":core:ingress"))
    implementation(project(":core:actions"))
    implementation(project(":core:commands"))
    implementation(project(":core:queries"))
    implementation(project(":core:orchestrator"))
    implementation(project(":core:policy"))
    implementation(project(":core:skills:metadata"))
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(project(":core:security"))
    implementation(project(":core:exitinfo"))
    implementation(project(":data:database"))
    implementation(project(":data:datastore"))
    implementation(project(":data:repository"))
    implementation(project(":data:actions"))
    implementation(project(":data:tasks"))
    implementation(project(":data:timers"))
    implementation(project(":skills:tasks"))
    implementation(project(":skills:device"))
    implementation(project(":data:events"))
    implementation(project(":feature:device"))
    implementation(project(":feature:activity"))
    implementation(project(":skills:reminders"))
    implementation(project(":data:reminders"))
    implementation(project(":feature:reminders"))
    implementation(project(":skills:timers"))
    implementation(project(":skills:memory"))
    implementation(project(":data:memory"))
    implementation(project(":feature:memories"))
    implementation(project(":feature:home"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:timers"))
    implementation(project(":skills:notifications"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:settings"))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    // Compose's transitive Espresso 3.5 reflection targets an InputManager method removed in API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(project(":data:actions"))
    androidTestImplementation(project(":data:repository"))
    androidTestImplementation(project(":data:events"))
    androidTestImplementation(project(":data:tasks"))
    androidTestImplementation(project(":core:queries"))
    androidTestImplementation(project(":data:reminders"))
    androidTestImplementation(project(":data:memory"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
}
