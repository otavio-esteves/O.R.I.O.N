#!/bin/sh
set -eu

usage() {
    echo "usage: $0 OUTPUT.json -- COMMAND [ARG ...]" >&2
    exit 64
}

[ "$#" -ge 3 ] || usage
output=$1
shift
[ "$1" = "--" ] || usage
shift

[ ! -e "$output" ] || { echo "$output already exists; refusing to overwrite benchmark evidence" >&2; exit 73; }

required_vars='PROFILE_ID ENVIRONMENT ANDROID_RUNTIME_EVIDENCE DEVICE_MANUFACTURER DEVICE_MODEL SOC RAM_MIB ANDROID_BUILD API_LEVEL APP_BUILD_ID COMMIT_SHA BUILD_VARIANT KIND CANDIDATE ARTIFACT_SHA256 QUANTIZATION INFERENCE_BACKEND THREADS CONTEXT_TOKENS GENERATED_TOKENS LOAD_TIME_MS FIRST_TOKEN_MS TOKENS_PER_SECOND RSS_BASELINE_KIB PEAK_RSS_KIB RSS_AFTER_UNLOAD_KIB BATTERY_START_PERCENT BATTERY_END_PERCENT INITIAL_TEMPERATURE_C THERMAL_STATUS'
for suffix in $required_vars; do
    value=$(printenv "ORION_BENCH_${suffix}" || true)
    [ -n "$value" ] || { echo "missing ORION_BENCH_${suffix}" >&2; exit 64; }
done

safe_text_vars='PROFILE_ID DEVICE_MANUFACTURER DEVICE_MODEL SOC ANDROID_BUILD APP_BUILD_ID BUILD_VARIANT CANDIDATE QUANTIZATION INFERENCE_BACKEND THERMAL_STATUS'
for suffix in $safe_text_vars; do
    value=$(printenv "ORION_BENCH_${suffix}")
    case "$value" in *[!A-Za-z0-9._:/\ -]*) echo "ORION_BENCH_${suffix} contains unsupported characters" >&2; exit 64;; esac
done

integer_vars='RAM_MIB API_LEVEL THREADS CONTEXT_TOKENS GENERATED_TOKENS LOAD_TIME_MS FIRST_TOKEN_MS RSS_BASELINE_KIB PEAK_RSS_KIB RSS_AFTER_UNLOAD_KIB'
for suffix in $integer_vars; do
    value=$(printenv "ORION_BENCH_${suffix}")
    case "$value" in *[!0-9]*|'') echo "ORION_BENCH_${suffix} must be an unsigned integer" >&2; exit 64;; esac
done

decimal_vars='TOKENS_PER_SECOND BATTERY_START_PERCENT BATTERY_END_PERCENT INITIAL_TEMPERATURE_C'
for suffix in $decimal_vars; do
    value=$(printenv "ORION_BENCH_${suffix}")
    case "$value" in *[!0-9.]*|'') echo "ORION_BENCH_${suffix} must be an unsigned number" >&2; exit 64;; esac
done

case "$ORION_BENCH_KIND" in LLM|STT|TTS) ;; *) echo 'ORION_BENCH_KIND must be LLM, STT, or TTS' >&2; exit 64;; esac
case "$ORION_BENCH_ENVIRONMENT" in CONTAINER|HOST|ANDROID_EMULATOR|PHYSICAL_DEVICE) ;; *) echo 'invalid ORION_BENCH_ENVIRONMENT' >&2; exit 64;; esac
case "$ORION_BENCH_ANDROID_RUNTIME_EVIDENCE" in DEFINITIVE|NON_DEFINITIVE) ;; *) echo 'invalid ORION_BENCH_ANDROID_RUNTIME_EVIDENCE' >&2; exit 64;; esac
if [ "$ORION_BENCH_ANDROID_RUNTIME_EVIDENCE" = DEFINITIVE ] && [ "$ORION_BENCH_ENVIRONMENT" != PHYSICAL_DEVICE ]; then
    echo 'DEFINITIVE Android runtime evidence requires PHYSICAL_DEVICE' >&2
    exit 64
fi
case "$ORION_BENCH_ARTIFACT_SHA256" in *[!0-9a-f]*|'') echo 'ORION_BENCH_ARTIFACT_SHA256 must be lowercase hexadecimal' >&2; exit 64;; esac
[ "${#ORION_BENCH_ARTIFACT_SHA256}" -eq 64 ] || { echo 'ORION_BENCH_ARTIFACT_SHA256 must have 64 characters' >&2; exit 64; }
case "$ORION_BENCH_COMMIT_SHA" in *[!0-9a-f]*|'') echo 'ORION_BENCH_COMMIT_SHA must be lowercase hexadecimal' >&2; exit 64;; esac
commit_length=${#ORION_BENCH_COMMIT_SHA}
[ "$commit_length" -ge 40 ] && [ "$commit_length" -le 64 ] || { echo 'ORION_BENCH_COMMIT_SHA must have 40 to 64 characters' >&2; exit 64; }

started=$(date +%s%3N)
set +e
"$@"
command_status=$?
set -e
finished=$(date +%s%3N)
duration=$((finished - started))
recorded_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
if [ "$command_status" -eq 0 ]; then
    execution_result=PASS
    exit_reason=NORMAL
else
    execution_result=FAIL
    exit_reason=COMMAND_EXIT_$command_status
fi

mkdir -p "$(dirname "$output")"
temporary=$(mktemp "${output}.tmp.XXXXXX")
cleanup() {
    [ -z "${temporary:-}" ] || rm -f "$temporary"
}
trap cleanup EXIT HUP INT TERM
sed \
    -e "s|@PROFILE_ID@|$ORION_BENCH_PROFILE_ID|g" \
    -e "s|@RECORDED_AT@|$recorded_at|g" \
    -e "s|@ENVIRONMENT@|$ORION_BENCH_ENVIRONMENT|g" \
    -e "s|@ANDROID_RUNTIME_EVIDENCE@|$ORION_BENCH_ANDROID_RUNTIME_EVIDENCE|g" \
    -e "s|@DEVICE_MANUFACTURER@|$ORION_BENCH_DEVICE_MANUFACTURER|g" \
    -e "s|@DEVICE_MODEL@|$ORION_BENCH_DEVICE_MODEL|g" \
    -e "s|@SOC@|$ORION_BENCH_SOC|g" \
    -e "s|@RAM_MIB@|$ORION_BENCH_RAM_MIB|g" \
    -e "s|@ANDROID_BUILD@|$ORION_BENCH_ANDROID_BUILD|g" \
    -e "s|@API_LEVEL@|$ORION_BENCH_API_LEVEL|g" \
    -e "s|@APP_BUILD_ID@|$ORION_BENCH_APP_BUILD_ID|g" \
    -e "s|@COMMIT_SHA@|$ORION_BENCH_COMMIT_SHA|g" \
    -e "s|@BUILD_VARIANT@|$ORION_BENCH_BUILD_VARIANT|g" \
    -e "s|@KIND@|$ORION_BENCH_KIND|g" \
    -e "s|@CANDIDATE@|$ORION_BENCH_CANDIDATE|g" \
    -e "s|@ARTIFACT_SHA256@|$ORION_BENCH_ARTIFACT_SHA256|g" \
    -e "s|@QUANTIZATION@|$ORION_BENCH_QUANTIZATION|g" \
    -e "s|@INFERENCE_BACKEND@|$ORION_BENCH_INFERENCE_BACKEND|g" \
    -e "s|@THREADS@|$ORION_BENCH_THREADS|g" \
    -e "s|@CONTEXT_TOKENS@|$ORION_BENCH_CONTEXT_TOKENS|g" \
    -e "s|@GENERATED_TOKENS@|$ORION_BENCH_GENERATED_TOKENS|g" \
    -e "s|@COMMAND_DURATION_MS@|$duration|g" \
    -e "s|@LOAD_TIME_MS@|$ORION_BENCH_LOAD_TIME_MS|g" \
    -e "s|@FIRST_TOKEN_MS@|$ORION_BENCH_FIRST_TOKEN_MS|g" \
    -e "s|@TOKENS_PER_SECOND@|$ORION_BENCH_TOKENS_PER_SECOND|g" \
    -e "s|@RSS_BASELINE_KIB@|$ORION_BENCH_RSS_BASELINE_KIB|g" \
    -e "s|@PEAK_RSS_KIB@|$ORION_BENCH_PEAK_RSS_KIB|g" \
    -e "s|@RSS_AFTER_UNLOAD_KIB@|$ORION_BENCH_RSS_AFTER_UNLOAD_KIB|g" \
    -e "s|@BATTERY_START_PERCENT@|$ORION_BENCH_BATTERY_START_PERCENT|g" \
    -e "s|@BATTERY_END_PERCENT@|$ORION_BENCH_BATTERY_END_PERCENT|g" \
    -e "s|@INITIAL_TEMPERATURE_C@|$ORION_BENCH_INITIAL_TEMPERATURE_C|g" \
    -e "s|@THERMAL_STATUS@|$ORION_BENCH_THERMAL_STATUS|g" \
    -e "s|@EXIT_REASON@|$exit_reason|g" \
    -e "s|@EXECUTION_RESULT@|$execution_result|g" \
    benchmark/schema/benchmark-profile.template.json > "$temporary"

perl scripts/benchmark/validate-profiles.pl "$temporary"

# GNU mv -n performs the same-filesystem rename without replacing evidence that
# may have appeared since the initial collision check. A remaining temporary
# file means promotion was refused.
mv -n "$temporary" "$output"
if [ -e "$temporary" ]; then
    echo "$output already exists; refusing to overwrite benchmark evidence" >&2
    exit 73
fi
temporary=

echo "wrote $output"
exit "$command_status"
