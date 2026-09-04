#!/bin/sh
set -eu

test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM

export ORION_BENCH_PROFILE_ID=orion_benchmark_recorder_test
export ORION_BENCH_ENVIRONMENT=CONTAINER
export ORION_BENCH_ANDROID_RUNTIME_EVIDENCE=NON_DEFINITIVE
export ORION_BENCH_DEVICE_MANUFACTURER=Samsung
export ORION_BENCH_DEVICE_MODEL=Galaxy-S21
export ORION_BENCH_SOC=Exynos-2100
export ORION_BENCH_RAM_MIB=8192
export ORION_BENCH_ANDROID_BUILD=test-build
export ORION_BENCH_API_LEVEL=31
export ORION_BENCH_APP_BUILD_ID=test-app
export ORION_BENCH_COMMIT_SHA=0000000000000000000000000000000000000000
export ORION_BENCH_BUILD_VARIANT=benchmark
export ORION_BENCH_KIND=LLM
export ORION_BENCH_CANDIDATE=test-model
export ORION_BENCH_ARTIFACT_SHA256=0000000000000000000000000000000000000000000000000000000000000000
export ORION_BENCH_QUANTIZATION=Q4
export ORION_BENCH_INFERENCE_BACKEND=CPU
export ORION_BENCH_THREADS=2
export ORION_BENCH_CONTEXT_TOKENS=2048
export ORION_BENCH_GENERATED_TOKENS=128
export ORION_BENCH_LOAD_TIME_MS=10
export ORION_BENCH_FIRST_TOKEN_MS=20
export ORION_BENCH_TOKENS_PER_SECOND=5.5
export ORION_BENCH_RSS_BASELINE_KIB=100
export ORION_BENCH_PEAK_RSS_KIB=200
export ORION_BENCH_RSS_AFTER_UNLOAD_KIB=110
export ORION_BENCH_BATTERY_START_PERCENT=90
export ORION_BENCH_BATTERY_END_PERCENT=89
export ORION_BENCH_INITIAL_TEMPERATURE_C=30.5
export ORION_BENCH_THERMAL_STATUS=NONE

scripts/benchmark/record-profile.sh "$test_dir/valid.json" -- true
perl scripts/benchmark/validate-profiles.pl "$test_dir/valid.json"

export ORION_BENCH_ANDROID_RUNTIME_EVIDENCE=DEFINITIVE
if scripts/benchmark/record-profile.sh "$test_dir/non-physical-definitive.json" -- true 2>/dev/null; then
    echo 'non-physical runtime evidence was accepted as definitive' >&2
    exit 1
fi
export ORION_BENCH_ANDROID_RUNTIME_EVIDENCE=NON_DEFINITIVE

ORION_BENCH_PROFILE_ID='$(touch '"$test_dir"'/injection)'
export ORION_BENCH_PROFILE_ID
if scripts/benchmark/record-profile.sh "$test_dir/unsafe.json" -- true 2>/dev/null; then
    echo 'unsafe recorder input was accepted' >&2
    exit 1
fi
if [ -e "$test_dir/injection" ]; then
    echo 'unsafe recorder input was executed' >&2
    exit 1
fi

echo 'benchmark recorder tests passed'
