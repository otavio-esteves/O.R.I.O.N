plugins {
    base
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

val validateBenchmarkProfiles = tasks.register<Exec>("validateBenchmarkProfiles") {
    group = "verification"
    description = "Validates committed O.R.I.O.N. benchmark profiles."
    val profiles = fileTree("benchmark/profiles") { include("*.json") }
    inputs.files(profiles)
    inputs.property("profileOverride", benchmarkProfileOverride.orElse(""))
    commandLine("perl", "scripts/benchmark/validate-profiles.pl")
    args(benchmarkProfileOverride.orNull?.let(::listOf) ?: profiles.files.sorted().map(File::getPath))
}

val testBenchmarkProfileValidation = tasks.register<Exec>("testBenchmarkProfileValidation") {
    group = "verification"
    description = "Proves invalid benchmark profiles are rejected deterministically."
    val invalidProfiles = fileTree("benchmark/testdata") { include("invalid-*.json") }
    inputs.files(invalidProfiles)
    commandLine("perl", "scripts/benchmark/validate-profiles.pl", "--expect-invalid")
    args(invalidProfiles.files.sorted().map(File::getPath))
}

val testBenchmarkRecorder = tasks.register<Exec>("testBenchmarkRecorder") {
    group = "verification"
    description = "Tests benchmark recording and rejects unsafe input."
    inputs.files(
        "scripts/benchmark/record-profile.sh",
        "scripts/benchmark/test-record-profile.sh",
        "scripts/benchmark/validate-profiles.pl",
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
}

tasks.named("check") {
    dependsOn(
        validateBenchmarkProfiles,
        testBenchmarkProfileValidation,
        testBenchmarkRecorder,
        validateNativeCompatibilityGate,
    )
}
