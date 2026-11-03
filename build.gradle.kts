import java.util.zip.ZipFile

plugins {
    base
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("toolchainInfo") {
    group = "verification"
    description = "Prints the versioned O.R.I.O.N. build toolchain."
    commandLine("sh", "scripts/toolchain-info.sh", gradle.gradleVersion)
}

val benchmarkProfileOverride = providers.gradleProperty("orionBenchmarkProfile")

val verifyModuleBoundaries = tasks.register<Exec>("verifyModuleBoundaries") {
    group = "verification"
    description = "Rejects missing modules, forbidden dependencies, and Gradle project cycles."
    inputs.files(
        "settings.gradle.kts",
        subprojects.map { it.buildFile },
        "scripts/validate-module-boundaries.pl",
    )
    commandLine("perl", "scripts/validate-module-boundaries.pl")
}

val testModuleBoundaries = tasks.register<Exec>("testModuleBoundaries") {
    group = "verification"
    description = "Tests the foundation inventory and direct/transitive dependency boundaries."
    inputs.files("scripts/validate-module-boundaries.pl", "scripts/test-module-boundaries.pl")
    commandLine("perl", "scripts/test-module-boundaries.pl")
}

val validateBenchmarkProfiles = tasks.register<Exec>("validateBenchmarkProfiles") {
    group = "verification"
    description = "Validates committed O.R.I.O.N. benchmark profiles."
    val profiles = fileTree("benchmark/profiles") { include("*.json") }
    inputs.files(profiles, "benchmark/schema/benchmark-profile.schema.json")
    inputs.property("profileOverride", benchmarkProfileOverride.orElse(""))
    commandLine("perl", "scripts/benchmark/validate-profiles.pl")
    args(benchmarkProfileOverride.orNull?.let(::listOf) ?: profiles.files.sorted().map(File::getPath))
}

val testBenchmarkProfileValidation = tasks.register<Exec>("testBenchmarkProfileValidation") {
    group = "verification"
    description = "Proves valid and invalid benchmark profile states against the canonical schema."
    inputs.files(
        fileTree("benchmark/testdata") { include("valid-*.json", "invalid-*.json") },
        "benchmark/schema/benchmark-profile.schema.json",
        "scripts/benchmark/test-profile-validation.sh",
        "scripts/benchmark/validate-profiles.pl",
    )
    commandLine("sh", "scripts/benchmark/test-profile-validation.sh")
}

val testBenchmarkRecorder = tasks.register<Exec>("testBenchmarkRecorder") {
    group = "verification"
    description = "Tests benchmark recording and rejects unsafe input."
    inputs.files(
        "scripts/benchmark/record-profile.sh",
        "scripts/benchmark/test-record-profile.sh",
        "scripts/benchmark/validate-profiles.pl",
        "benchmark/schema/benchmark-profile.schema.json",
        "benchmark/schema/benchmark-profile.template.json",
    )
    commandLine("sh", "scripts/benchmark/test-record-profile.sh")
}

val validateNativeCompatibilityGate = tasks.register<Exec>("validateNativeCompatibilityGate") {
    group = "verification"
    description = "Validates the current F0 native compatibility gate."
    inputs.files(
        "docs/compatibility/native-compatibility-gate.json",
        "scripts/benchmark/validate-native-gate.pl",
    )
    commandLine(
        "perl",
        "scripts/benchmark/validate-native-gate.pl",
        "docs/compatibility/native-compatibility-gate.json",
    )
    dependsOn(":app:assembleDebug", ":app:assembleRelease")
}

val testNativeCompatibilityGate = tasks.register<Exec>("testNativeCompatibilityGate") {
    group = "verification"
    description = "Proves native artifacts cannot retain the not-applicable gate state."
    inputs.files(
        "docs/compatibility/native-compatibility-gate.json",
        "scripts/benchmark/test-native-gate.sh",
        "scripts/benchmark/validate-native-gate.pl",
    )
    commandLine("sh", "scripts/benchmark/test-native-gate.sh")
    dependsOn(":app:assembleDebug", ":app:assembleRelease")
}

tasks.named("check") {
    dependsOn(
        verifyModuleBoundaries,
        testModuleBoundaries,
        validateBenchmarkProfiles,
        testBenchmarkProfileValidation,
        testBenchmarkRecorder,
        validateNativeCompatibilityGate,
        testNativeCompatibilityGate,
    )
}


val releaseBundleArtifact = layout.projectDirectory.file("app/build/outputs/bundle/release/app-release.aab").asFile
abstract class OrionVerifyBundlePageAlignment : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleFile: RegularFileProperty

    @TaskAction
    fun verify() {
        ZipFile(bundleFile.get().asFile).use { archive ->
            val config = archive.getInputStream(checkNotNull(archive.getEntry("BundleConfig.pb"))).use {
                com.android.bundle.Config.BundleConfig.parseFrom(it)
            }
            val native = config.optimizations.uncompressNativeLibraries
            check(native.enabled && native.alignment.name == "PAGE_ALIGNMENT_16K") {
                "Release bundle must request uncompressed native libraries with 16 KiB page alignment."
            }
        }
        logger.lifecycle("Release AAB native page alignment: PASS")
    }
}

val verifyBundlePageAlignment = tasks.register<OrionVerifyBundlePageAlignment>("verifyBundlePageAlignment") {
    group = "verification"
    description = "Checks that release AAB requests 16 KiB native library page alignment."
    dependsOn(":app:bundleRelease")
    bundleFile.set(releaseBundleArtifact)
}

val verifyNativePackageArtifacts = tasks.register<Exec>("verifyNativePackageArtifacts") {
    group = "verification"
    description = "Checks native ELF alignment, ABI and packaging in debug/release APK and release AAB."
    dependsOn(":app:assembleDebug", ":app:assembleRelease", ":app:bundleRelease")
    inputs.files(
        "scripts/benchmark/check-native-packages.sh",
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/release/app-release-unsigned.apk",
        releaseBundleArtifact,
    )
    environment("ORION_NDK_VERSION", libs.versions.ndk.get())
    commandLine("sh", "scripts/benchmark/check-native-packages.sh",
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/release/app-release-unsigned.apk",
        releaseBundleArtifact.path)
}

tasks.named("check") { dependsOn(verifyBundlePageAlignment, verifyNativePackageArtifacts) }

val testNativePackageArtifacts = tasks.register<Exec>("testNativePackageArtifacts") {
    group = "verification"
    description = "Rejects 4 KiB ELF, unaligned APK and invalid ABI fixtures using the pinned NDK."
    inputs.files("scripts/benchmark/check-native-packages.sh", "scripts/benchmark/test-native-packages.sh")
    environment("ORION_NDK_VERSION", libs.versions.ndk.get())
    commandLine("sh", "scripts/benchmark/test-native-packages.sh")
}

tasks.named("check") { dependsOn(testNativePackageArtifacts) }
