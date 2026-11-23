import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

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
val s21CortexSmokeFlag = providers.gradleProperty("orionS21CortexSmoke").orElse("false").get()
check(s21CortexSmokeFlag in setOf("true", "false")) { "orionS21CortexSmoke must be true or false." }
val s21CortexSmokeEnabled = s21CortexSmokeFlag == "true"
check(!s21CortexSmokeEnabled || !qualificationEnabled) { "Build the S21 smoke test as a separate Debug APK." }
val hybridChatFlag = providers.gradleProperty("orionHybridChatQualification").orElse("false").get()
check(hybridChatFlag in setOf("true", "false")) { "orionHybridChatQualification must be true or false." }
val hybridChatEnabled = hybridChatFlag == "true"
check(!hybridChatEnabled || qualificationEnabled) { "Hybrid chat probes require the separate qualification identity." }
val physicalBaselineFlag = providers.gradleProperty("orionPhysicalBaseline").orElse("false").get()
check(physicalBaselineFlag in setOf("true", "false")) { "orionPhysicalBaseline must be true or false." }
val physicalBaselineEnabled = physicalBaselineFlag == "true"
check(!physicalBaselineEnabled || qualificationEnabled) { "Physical baseline requires the separate qualification identity." }
val cortexReadinessFlag = providers.gradleProperty("orionCortexReadinessProbe").orElse("false").get()
check(cortexReadinessFlag in setOf("true", "false")) { "orionCortexReadinessProbe must be true or false." }
val cortexReadinessEnabled = cortexReadinessFlag == "true"
check(!cortexReadinessEnabled || qualificationEnabled) { "Cortex readiness probe requires the separate qualification identity." }
val exitEvidenceCaptureFlag = providers.gradleProperty("orionExitEvidenceCapture").orElse("false").get()
check(exitEvidenceCaptureFlag in setOf("true", "false")) { "orionExitEvidenceCapture must be true or false." }
val exitEvidenceCaptureEnabled = exitEvidenceCaptureFlag == "true"
check(!exitEvidenceCaptureEnabled || qualificationEnabled) { "Exit evidence capture requires the separate qualification identity." }
val cortexSingleAttemptFlag = providers.gradleProperty("orionCortexSingleAttempt").orElse("false").get()
check(cortexSingleAttemptFlag in setOf("true", "false")) { "orionCortexSingleAttempt must be true or false." }
val cortexSingleAttemptEnabled = cortexSingleAttemptFlag == "true"
check(!cortexSingleAttemptEnabled || qualificationEnabled) { "Single attempt requires the separate qualification identity." }
val cortexRuntimeCaptureFlag = providers.gradleProperty("orionCortexRuntimeCapture").orElse("false").get()
check(cortexRuntimeCaptureFlag in setOf("true", "false")) { "orionCortexRuntimeCapture must be true or false." }
val cortexRuntimeCaptureEnabled = cortexRuntimeCaptureFlag == "true"
check(!cortexRuntimeCaptureEnabled || qualificationEnabled) { "Runtime capture requires the separate qualification identity." }
val cortexInstrumentedWorkloadFlag = providers.gradleProperty("orionCortexInstrumentedWorkload").orElse("false").get()
check(cortexInstrumentedWorkloadFlag in setOf("true", "false")) { "orionCortexInstrumentedWorkload must be true or false." }
val cortexInstrumentedWorkloadEnabled = cortexInstrumentedWorkloadFlag == "true"
check(!cortexInstrumentedWorkloadEnabled || qualificationEnabled) { "Instrumented workload requires the separate qualification identity." }
check(listOf(physicalBaselineEnabled, cortexReadinessEnabled, exitEvidenceCaptureEnabled, cortexSingleAttemptEnabled,
    cortexRuntimeCaptureEnabled, cortexInstrumentedWorkloadEnabled, hybridChatEnabled).count { it } <= 1) {
    "Qualification measurement modes are mutually exclusive."
}

// This optional local input makes a test APK usable offline on first launch.
val voiceModelFile = providers.gradleProperty("orionVoiceModelFile").orNull?.let { file(it) }
val bundledVoiceModel = voiceModelFile?.let { model ->
    tasks.register<PrepareOrionDebugVoiceModel>("prepareOrionDebugVoiceModel") {
        source.set(model)
        license.set(layout.projectDirectory.file("licenses/whisper-model-MIT.txt"))
    }
}

abstract class PrepareOrionDebugVoiceModel : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val assets: DirectoryProperty

    @TaskAction
    fun prepare() {
        val model = source.get().asFile
        val sha = MessageDigest.getInstance("SHA-256")
        check(model.length() == 32_152_673L) { "Unexpected ggml-tiny-q5_1.bin size." }
        model.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                sha.update(bytes, 0, count)
            }
        }
        check(sha.digest().joinToString("") { "%02x".format(it) } ==
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7") { "Unexpected ggml-tiny-q5_1.bin SHA-256." }
        val directory = assets.get().asFile.resolve("orion_voice")
        model.copyTo(directory.resolve("ggml-tiny-q5_1.bin"), overwrite = true)
        license.get().asFile.copyTo(directory.resolve("whisper-model-MIT.txt"), overwrite = true)
    }
}

android {
    namespace = "network.orion.app"
    ndkVersion = libs.versions.ndk.get()
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "network.orion"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        testInstrumentationRunner = when {
            hybridChatEnabled -> "androidx.test.runner.AndroidJUnitRunner"
            cortexInstrumentedWorkloadEnabled -> "network.orion.app.compatibility.OrionCortexInstrumentedWorkloadInstrumentation"
            cortexRuntimeCaptureEnabled -> "network.orion.app.compatibility.OrionCortexRuntimeCaptureInstrumentation"
            cortexSingleAttemptEnabled -> "network.orion.app.compatibility.OrionCortexSingleAttemptInstrumentation"
            exitEvidenceCaptureEnabled -> "network.orion.app.compatibility.OrionExitEvidenceCaptureInstrumentation"
            cortexReadinessEnabled -> "network.orion.app.compatibility.OrionCortexReadinessInstrumentation"
            physicalBaselineEnabled -> "network.orion.app.compatibility.OrionPhysicalBaselineInstrumentation"
            qualificationEnabled -> "network.orion.app.compatibility.OrionQualificationInstrumentation"
            else -> "androidx.test.runner.AndroidJUnitRunner"
        }
        versionCode = 13
        versionName = "0.5.0-development"
        resValue("string", "orion_native_qualification_status", nativeQualificationStatus)
        // A normal APK never gains this observer, even with qualification properties supplied.
        buildConfigField("boolean", "ORION_CORTEX_SINGLE_ATTEMPT", "false")
        buildConfigField("boolean", "ORION_CORTEX_RUNTIME_CAPTURE", "false")
        buildConfigField("boolean", "ORION_CORTEX_INSTRUMENTED_WORKLOAD", "false")
    }

    buildTypes {
        debug {
            ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
            if (s21CortexSmokeEnabled) applicationIdSuffix = ".cortexlab"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            ndk {
                abiFilters += "arm64-v8a"
                debugSymbolLevel = "FULL"
            }
        }
        create("qualificationRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qualification"
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            ndk.abiFilters.clear()
            ndk.abiFilters += setOf("arm64-v8a", "x86_64")
            buildConfigField("boolean", "ORION_CORTEX_SINGLE_ATTEMPT", cortexSingleAttemptEnabled.toString())
            buildConfigField("boolean", "ORION_CORTEX_RUNTIME_CAPTURE", cortexRuntimeCaptureEnabled.toString())
            buildConfigField("boolean", "ORION_CORTEX_INSTRUMENTED_WORKLOAD", cortexInstrumentedWorkloadEnabled.toString())
        }
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/data/database/schemas")
    if (qualificationEnabled) {
        testBuildType = "qualificationRelease"
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/qualificationAndroidTest/java")
    }
    if (hybridChatEnabled) {
        // Only the explicit probe APK retains test entry APIs. Ordinary Release remains unchanged.
        buildTypes.getByName("qualificationRelease").proguardFiles("src/hybridChatQualification/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.setSrcDirs(emptyList<String>())
        sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("src/androidTest/kotlin/network/orion/app/chat"))
    }
    if (physicalBaselineEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/physicalBaselineTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/physicalBaselineTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/physicalBaselineAndroidTest/java")
    }
    if (cortexReadinessEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/cortexReadinessTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/cortexReadinessTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/cortexReadinessAndroidTest/java")
    }
    if (exitEvidenceCaptureEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/exitEvidenceCaptureTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/exitEvidenceCaptureTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/exitEvidenceCaptureAndroidTest/java")
    }
    if (cortexRuntimeCaptureEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/cortexRuntimeCaptureTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/cortexRuntimeCaptureTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/cortexRuntimeCaptureAndroidTest/java")
    }
    if (cortexInstrumentedWorkloadEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/cortexInstrumentedWorkloadTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/cortexInstrumentedWorkloadTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/cortexInstrumentedWorkloadAndroidTest/java")
    }
    if (cortexSingleAttemptEnabled) {
        sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/cortexSingleAttemptTarget/kotlin")
        buildTypes.getByName("qualificationRelease").proguardFiles("src/cortexSingleAttemptTarget/proguard-rules.pro")
        sourceSets.getByName("androidTest").java.directories.clear()
        sourceSets.getByName("androidTest").kotlin.directories.clear()
        sourceSets.getByName("androidTest").java.directories.add("src/cortexSingleAttemptAndroidTest/java")
    }
}

val releaseDependencyReport = layout.buildDirectory.file("reports/orion/release-dependencies.tsv")
val reportReleaseDependencies = tasks.register("reportReleaseDependencies") {
    group = "reporting"
    description = "Records the resolved release runtime modules and declared Maven POM licenses."
    notCompatibleWithConfigurationCache("Queries Maven POM artifacts for the resolved release graph.")
    doNotTrackState("Maven POM availability is part of the report.")
    outputs.file(releaseDependencyReport)
    doLast {
        val modules = project.configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents.mapNotNull {
            it.id as? ModuleComponentIdentifier
        }.sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
        check(modules.isNotEmpty()) { "The release dependency graph is empty." }
        val poms = dependencies.createArtifactResolutionQuery().forComponents(modules)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
            .resolvedComponents.associate { component ->
                component.id to component.getArtifacts(MavenPomArtifact::class.java)
                    .filterIsInstance<ResolvedArtifactResult>().singleOrNull()?.file
            }
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder()
        val output = releaseDependencyReport.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter().use { report ->
            report.appendLine("dependency\tlicense\tmetadata")
            for (module in modules) {
                val pom = poms[module]
                val declared = pom?.let { xml.parse(it).documentElement.directElements("licenses").singleOrNull() }
                val names = declared?.directElements("license").orEmpty().mapNotNull { license ->
                    license.directElements("name").singleOrNull()?.textContent?.trim()?.replace(Regex("\\s+"), " ")
                        ?.takeIf(String::isNotBlank)
                }
                val metadata = when {
                    pom == null -> "POM_UNAVAILABLE"
                    names.isEmpty() -> "POM_LICENSE_UNDECLARED"
                    else -> "MAVEN_POM"
                }
                report.appendLine("${module.group}:${module.module}:${module.version}\t${names.joinToString(" | ")}\t$metadata")
            }
        }
    }
}

fun Element.directElements(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }

val stageReleaseEvidence = tasks.register<Exec>("stageReleaseEvidence") {
    group = "verification"
    description = "Verifies release symbolication and stages checksummed release evidence."
    dependsOn("assembleRelease", "bundleRelease", reportReleaseDependencies)
    environment("ORION_NDK_VERSION", libs.versions.ndk.get())
    val output = layout.buildDirectory.dir("outputs/release-evidence").get().asFile
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk").get().asFile
    val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt").get().asFile
    val symbols = layout.buildDirectory.file("outputs/native-debug-symbols/release/native-debug-symbols.zip").get().asFile
    val script = rootProject.file("scripts/benchmark/stage-release-evidence.sh")
    inputs.files(script, apk, bundle, mapping, symbols, releaseDependencyReport,
        rootProject.file("ai/llama/src/main/cpp/CMakeLists.txt"),
        rootProject.file("voice/service/src/main/cpp/CMakeLists.txt"))
    outputs.dir(output)
    workingDir(rootProject.projectDir)
    commandLine("sh", script, apk, bundle, mapping, symbols, releaseDependencyReport.get().asFile, output)
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("qualificationRelease")) {
    it.enable = qualificationEnabled
}

if (bundledVoiceModel != null) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) {
        it.sources.assets?.addGeneratedSourceDirectory(bundledVoiceModel, PrepareOrionDebugVoiceModel::assets)
    }
}

if (qualificationEnabled) {
    val qualificationApk = layout.buildDirectory.file("outputs/apk/qualificationRelease/app-qualificationRelease.apk").get().asFile
    val mapping = layout.buildDirectory.file("outputs/mapping/qualificationRelease/mapping.txt").get().asFile
    tasks.register<Exec>("verifyQualificationNativePackage") {
        group = "verification"
        description = "Checks explicit minified qualification APK, never the production release ABI policy."
        dependsOn("assembleQualificationRelease")
        inputs.files(qualificationApk, mapping, rootProject.file("scripts/benchmark/check-native-packages.sh"))
        environment("ORION_NDK_VERSION", libs.versions.ndk.get())
        doFirst { check(mapping.isFile && mapping.useLines { lines -> lines.any { it == "# compiler: R8" } }) { "R8 qualification mapping is missing." } }
        commandLine("sh", rootProject.file("scripts/benchmark/check-native-packages.sh"), "--qualification", qualificationApk)
    }
}

dependencies {
    implementation(project(":ai:api"))
    implementation(project(":ai:service"))
    // AGP aligns tests with their target APK. Room's schema serializers require the newer default methods.
    debugImplementation(libs.serialization.test.runtime)
    implementation(project(":voice:ipc"))
    implementation(project(":voice:service"))
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
    implementation(project(":core:sentinel"))
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
    implementation(project(":feature:chat"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:timers"))
    implementation(project(":skills:notifications"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:settings"))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    if (hybridChatEnabled) androidTestCompileOnly(libs.errorprone.annotations)
    if (!qualificationEnabled || hybridChatEnabled) {
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

if (hybridChatEnabled) {
    // Compiler-only meta-annotations refer to JDK javax.lang.model, absent on Android.
    // Keep them as compileOnly libraries, never runtime payload; preserve R8 missing-class checks.
    configurations.matching { it.name.endsWith("AndroidTestRuntimeClasspath") }.configureEach {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
}
