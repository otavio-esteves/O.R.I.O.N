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
val qualificationFlag = providers.gradleProperty("orionQualificationRelease").orElse("false").get()
check(qualificationFlag in setOf("true", "false")) { "orionQualificationRelease must be true or false." }
val qualificationEnabled = qualificationFlag == "true"
val nativeSmokeFlag = providers.gradleProperty("orionNativeSmoke").orElse("false").get()
check(nativeSmokeFlag in setOf("true", "false"))
val nativeSmokeEnabled = nativeSmokeFlag == "true"
check(!qualificationEnabled || !nativeSmokeEnabled) { "Choose one instrumentation scope." }

android {
    namespace = "network.orion.app"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "network.orion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = if (qualificationEnabled)
            "network.orion.app.compatibility.OrionQualificationInstrumentation"
        else "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 6
        versionName = "0.5.0-candidate"
        resValue("string", "orion_native_qualification_status", nativeQualificationStatus)
    }

    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }
        release { ndk { abiFilters += "arm64-v8a" } }
        create("qualificationRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qualification"
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            ndk.abiFilters.clear()
            ndk.abiFilters += setOf("arm64-v8a", "x86_64")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/data/database/schemas")
    if (qualificationEnabled) {
        testBuildType = "qualificationRelease"
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/qualificationAndroidTest/java")
    }
    if (nativeSmokeEnabled) {
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.add("src/nativeSmokeAndroidTest/kotlin")
        sourceSets.getByName("androidTest").assets.directories.add("$buildDir/generated/nativeSmokeAssets")
    }
}

if (nativeSmokeEnabled) {
    val smokeModel = layout.buildDirectory.file("generated/nativeSmokeAssets/orion_stories260K.gguf")
    val prepareSmokeModel = tasks.register<Exec>("prepareNativeSmokeModel") {
        inputs.file(rootProject.file("scripts/benchmark/prepare-native-smoke-model.sh"))
        outputs.file(smokeModel)
        commandLine("sh", rootProject.file("scripts/benchmark/prepare-native-smoke-model.sh"), smokeModel.get().asFile)
    }
    tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(prepareSmokeModel) }
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("qualificationRelease")) {
    it.enable = qualificationEnabled
}

if (qualificationEnabled) {
    val qualificationApk = layout.buildDirectory.file("outputs/apk/qualificationRelease/app-qualificationRelease.apk").get().asFile
    val mapping = layout.buildDirectory.file("outputs/mapping/qualificationRelease/mapping.txt").get().asFile
    val verifyQualificationJniSymbols = tasks.register<Exec>("verifyQualificationJniSymbols") {
        group = "verification"
        description = "Checks exact JNI classes, method descriptors and access in the minified DEX."
        dependsOn("assembleQualificationRelease")
        inputs.files(qualificationApk, rootProject.file("scripts/benchmark/verify-jni-contract.sh"))
        commandLine("sh", rootProject.file("scripts/benchmark/verify-jni-contract.sh"), qualificationApk)
    }
    tasks.register<Exec>("verifyQualificationNativePackage") {
        group = "verification"
        description = "Checks explicit minified qualification APK, never the production release ABI policy."
        dependsOn("assembleQualificationRelease")
        dependsOn(verifyQualificationJniSymbols)
        inputs.files(qualificationApk, mapping, rootProject.file("scripts/benchmark/check-native-packages.sh"))
        environment("ORION_NDK_VERSION", libs.versions.ndk.get())
        doFirst {
            check(mapping.isFile && mapping.useLines { lines -> lines.any { it == "# compiler: R8" } }) { "R8 qualification mapping is missing." }
            val text = mapping.readText()
            for (name in listOf("OrionLlamaEngine", "OrionNativeCallbacks")) {
                val type = "network.orion.ai.llama.$name"
                check(text.contains("$type -> $type:")) { "JNI class identity was removed or renamed." }
            }
            // Unrenamed native methods need not appear in R8's mapping. Their
            // signatures/access are checked against the actual DEX above.
            for (method in listOf("shouldCancel", "emit")) {
                check(Regex("\\b$method\\([^\\n]*\\)(?::[0-9:]+)? -> $method(?:\\r?\\n|$)").containsMatchIn(text)) {
                    "JNI method identity was removed or renamed."
                }
            }
        }
        commandLine("sh", rootProject.file("scripts/benchmark/check-native-packages.sh"), "--qualification", qualificationApk)
    }
}

dependencies {
    implementation(project(":ai:api"))
    implementation(project(":ai:cortex"))
    implementation(project(":ai:service"))
    implementation(project(":ai:llama"))
    implementation(project(":data:modelregistry"))
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
    if (!qualificationEnabled) {
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
    }
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
}
