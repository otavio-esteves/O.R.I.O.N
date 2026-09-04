# BenchmarkProfile V1

`BenchmarkProfile` is the reproducible evidence envelope for ORION-F0-001. Its
machine contract is `benchmark/schema/benchmark-profile.schema.json`.

The recorder requires these environment variables:

```text
ORION_BENCH_PROFILE_ID
ORION_BENCH_ENVIRONMENT            # CONTAINER, HOST, ANDROID_EMULATOR, PHYSICAL_DEVICE
ORION_BENCH_ANDROID_RUNTIME_EVIDENCE # DEFINITIVE or NON_DEFINITIVE
ORION_BENCH_DEVICE_MANUFACTURER
ORION_BENCH_DEVICE_MODEL
ORION_BENCH_SOC
ORION_BENCH_RAM_MIB
ORION_BENCH_ANDROID_BUILD
ORION_BENCH_API_LEVEL
ORION_BENCH_APP_BUILD_ID
ORION_BENCH_COMMIT_SHA
ORION_BENCH_BUILD_VARIANT
ORION_BENCH_KIND                 # LLM or STT
ORION_BENCH_CANDIDATE
ORION_BENCH_ARTIFACT_SHA256
ORION_BENCH_QUANTIZATION         # use NOT_APPLICABLE for STT when appropriate
ORION_BENCH_INFERENCE_BACKEND
ORION_BENCH_THREADS
ORION_BENCH_CONTEXT_TOKENS       # use 0 when not applicable
ORION_BENCH_GENERATED_TOKENS     # use 0 when not applicable
ORION_BENCH_LOAD_TIME_MS
ORION_BENCH_FIRST_TOKEN_MS       # use 0 when not applicable
ORION_BENCH_TOKENS_PER_SECOND    # use 0 when not applicable
ORION_BENCH_RSS_BASELINE_KIB
ORION_BENCH_PEAK_RSS_KIB
ORION_BENCH_RSS_AFTER_UNLOAD_KIB
ORION_BENCH_BATTERY_START_PERCENT
ORION_BENCH_BATTERY_END_PERCENT
ORION_BENCH_INITIAL_TEMPERATURE_C
ORION_BENCH_THERMAL_STATUS
```

The harness adds the UTC timestamp, command duration, exit reason, and result. It
does not infer unavailable measurements. A zero must mean not applicable or not
measured and must be explained by the run report before evidence is promoted.

Profiles retain the exact `ToolchainProfile` identifier and artifact SHA-256 so
results from different environments or binaries are not compared accidentally.

`DEFINITIVE` Android runtime evidence is accepted only with `PHYSICAL_DEVICE`.
Container, host, and emulator runs must use `NON_DEFINITIVE` for runtime/hardware
claims. This does not prevent those environments from producing conclusive build,
schema, or API-surface validation within their proper scope.
