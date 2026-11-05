plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "network.orion.app"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "network.orion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
        release { ndk { abiFilters += "arm64-v8a" } }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/data/database/schemas")
}

dependencies {
    // AGP aligns tests with their target APK. Room's schema serializers require the newer default methods.
    debugImplementation(libs.serialization.test.runtime)
    debugImplementation(project(":core:ipc"))
    testImplementation(libs.junit4)
    implementation(project(":core:common"))
    implementation(project(":core:health"))
    implementation(project(":core:time"))
    implementation(project(":core:observability"))
    implementation(project(":core:recovery"))
    implementation(project(":core:ingress"))
    implementation(project(":core:actions"))
    implementation(project(":core:commands"))
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
    implementation(project(":skills:tasks"))
    implementation(project(":feature:home"))
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
