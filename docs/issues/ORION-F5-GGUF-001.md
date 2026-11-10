# ORION-F5-GGUF-001 — Bounded GGUF structural validation

OBJECTIVE: Validate a deliberately bounded little-endian GGUF v3 subset in MAIN
before registry publication, without loading native code or tensor data into heap.

FILES ALLOWED: repository/models parser and focused JVM tests; this issue.

CONTRACTS INVOLVED: GGUF trust flow, untrusted model metadata, bounded import.

INVARIANTS INVOLVED: INV-001, INV-011, INV-013, INV-019, INV-033.

TESTS REQUIRED: valid float/quantized files; truncation, UTF8, duplicate keys/names,
unknown version/type, dimensions, alignment, overflow, overlapping/out-of-file
tensor ranges, metadata/string/array budgets and required architecture/context.

ACCEPTANCE CRITERIA: only supported tensor layouts pass; checked tensor bounds and
bounded metadata parsing; immutable minimal metadata; no native execution in MAIN.

OUT OF SCOPE: tensor semantics, native loader compatibility, tokenizer correctness,
hardware/resource qualification, model trust or permission to execute inference.

Sources: [GGUF specification](https://github.com/ggml-org/ggml/blob/master/docs/gguf.md)
and [GGML block layouts](https://github.com/ggml-org/ggml/blob/master/src/ggml-common.h).
Supported layouts are F32/F16/BF16, Q4_0/Q4_1/Q5_0/Q8_0 and Q4_K/Q5_K/Q6_K.
Quantized files require quantization_version=2. Other layouts fail closed.

An explicit optional fixture test checks the downloaded Qwen2.5-0.5B Q4_K_M artifact
identity and parser acceptance. It is excluded unless `-PorionModelFixture` is
specified; an explicitly requested missing, mismatched or invalid artifact fails.
The binary remains an ignored local build fixture, not bundled app content or
qualified hardware evidence. Parent executes serial canonical validation with:

```sh
docker compose run --rm android-build \
  -PorionModelFixture=/workspace/build/model-fixtures/qwen2.5-0.5b-instruct-q4_k_m.gguf \
  :data:repository:testDebugUnitTest --max-workers=2
```

The first explicit fixture check rejected an unsupported tensor layout. A bounded
read-only header inspection identified133 Q5_0 tensors mixed with F32/Q8_0/Q4_K/Q6_K
in this Q4_K_M artifact. Q5_0 support now uses the upstream specified32-element,
22-byte layout (half scale, four high-bit bytes, sixteen quant bytes). Exact length,
row divisibility and truncation regressions were added; unknown layouts still fail
closed. This records the diagnosed negative result, not a claimed successful rerun.

## Consolidated validation

Canonical checks, scoped emulator results and retained failed attempts are recorded
in [F5 Runtime Gate](../qualification/F5-RuntimeGate.md) and its
[evidence record](../qualification/f5-runtime-evidence.json). That report supersedes
implementation-time validation-pending notes without changing this work package's
scope. Final review corrections/reruns, physical qualification and production
resource profiles retain the explicit status recorded there; no per-issue test
count or phase closure is inferred.
