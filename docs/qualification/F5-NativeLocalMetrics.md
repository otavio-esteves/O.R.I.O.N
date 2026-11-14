# Native-local generation observations

Work package: [ORION-F5-NATIVE-METRICS-001](../issues/ORION-F5-NATIVE-METRICS-001.md),
[#111](https://github.com/otavio-esteves/O.R.I.O.N/issues/111).
This implements a local measurement source inside the existing JNI generation
call. It supplies no Cortex IPC fields, MAIN/HOST receipt, heavy workload runner,
profile/admission configuration or physical/performance qualification. Production
generation still uses the existing public API without observations.

## Ownership and default behavior

`OrionLlamaRuntime.generate` keeps its signature and finish enum. It uses the same
validation, native handle, exclusive operation, native shared ownership, model,
context, sampler, callbacks and cancellation/cleanup branches. Its optional native
array is null; no measurement clock reads, counter increments or JNI publication
occur. No upstream perf switch, seed, vendor source or logging route changes.

The internal `generateObserved` entry requires one new `OrionLlamaGenerationCapture`
per attempted invocation. Its one-shot claim refuses reuse before another call;
a previous snapshot remains historical data from its single original invocation.
A fresh capture starts NOT_STARTED, becomes IN_PROGRESS and publishes an immutable
typed snapshot after JNI returns/unwinds. It has no Context, native pointer,
model/request identity, prompt, piece bytes, error text, observer callback or global
last-result. Caller-owned holders do not form a retained runtime queue.

Both paths use the same private JNI method. Measurements are fixed stack POD per
call and copied into one fresh 20-long Java array. They are not stored in Runtime.
Concurrent close can erase the registry handle while existing shared ownership
keeps native work alive. Measurement export occurs after `guarded` and its lambda's
RAII unwind, including KV cleanup, without reading the erased handle. Kotlin
parsing follows JNI return and the wrapper's existing operation-finally. None of
this proves that the outer service worker has published its result, released its
slot or completed its own finally; there is no `nativeComplete` or `workerReady`.

## Counts and work definitions

Counts come from native tokenizer/sample/decode events, never text bytes, decoded
characters or transport chunks. Tokenization retains current add-special and
parse-special flags (`true/true`). `promptTokens` is known only when the second
tokenizer call returns its exact successful result; the initial negative capacity
query is not presented as completed tokenization. Early cancellation can therefore
have null prompt count, zero tokenizer calls and no measured tokenizer duration.
Special tokens added by the model configuration are included. See the pinned
[upstream API](https://github.com/ggml-org/llama.cpp/blob/7d7f0287530bc8981690f361d8c1627dbae1c99a/include/llama.h).

| Field | Exact scope |
| --- | --- |
| `sampledTokens` | One per returned `llama_sampler_sample`, including EOG |
| `eogSamples` | Zero or one sample satisfying `llama_vocab_is_eog`; not an output callback |
| `callbacksAttempted` | Calls to the existing Java piece callback; not delivered/accepted tokens |
| `callbacksReturnedNormally` | Callback returned without a pending exception, including `false` (which requests cancellation); not MAIN acceptance or delivery |
| `prefill.callsAttempted` / `tokensSubmitted` | Prompt batch calls and their input tokens passed to `llama_decode` |
| `prefill.callsSucceeded` / `tokensInSuccessfulCalls` | Calls returning code 0 and all their submitted tokens |
| `decode.*` | Same definitions for the existing single-token decode after a non-EOG sample/callback; one submitted token per call |
| `partialWorkUnknown` | A submitted call returned nonzero; unknown internal work cannot be inferred from its submitted token count |

Successful calls are counted **before** the existing cancellation-flag check. A
late cancellation cannot erase a call that already returned code 0. Conversely,
upstream abort/fatal results can leave some processed ubatches; successful-call
tokens describe the known successful prefix, not an exact total including that
partial work. Noncancelled native failures still throw through the original guard
and do not export a fabricated successful observation.

With maxTokens=1, a non-EOG sample can invoke one callback and return TOKEN_LIMIT
without any single-token decode. A sampled EOG can return SUCCEEDED with no output
callback/decode. For TOKEN_LIMIT, last sampled output is not decoded; for EOG after
N callbacks, all those N previous tokens had a successful decode. Cancellation
can stop before tokenization, during prefill, after a callback or after a decode.
No callback return is renamed emitted/accepted/delivered.

## Local monotonic API call durations

`clockSource=STEADY_CLOCK_NS` means `std::chrono::steady_clock` converted to
nanoseconds. Only per-call elapsed sums are exported, never raw timestamps, boot
identifiers, Android elapsedRealtime or persisted deadlines. Negative/regressed,
nonfinite or overflowing duration arithmetic makes the phase duration unavailable.
Zero is possible at clock resolution and is never converted into a rate/division.

`tokenizeApiWallNanos` sums the two tokenizer API call windows; prefill/decode
`apiWallNanos` sum their respective decode call windows (including the small batch
descriptor argument construction). They exclude prompt conversion, vector/model
allocation, sampling, token-piece conversion, Java callbacks, JNI publication,
load, cache clearing and lifetime destruction. An uncalled phase has null duration
and zero calls; a called phase with unavailable clock also has null duration but
positive calls. Neither is a measured successful zero-duration phase.

These are API-return wall times, not synchronized backend compute time. Upstream
may finish computations when result getters synchronize later; this instrumentation
adds no `llama_synchronize` or other barrier and does not charge sampling/getter
waits to decode. Successful-call counts reflect API success, not an internal
ubatch/kernel progress counter. No TTFT, prefill/decode throughput, tokens/second,
worker/service latency or safe model budget is derived here.

## Exception-safe local publication

The original `guarded` still preserves pending callback exceptions and translates
native exceptions to the same fixed diagnostic. After it returns, an original
pending exception causes complete export skip: no ExceptionClear/Describe or
replacement occurs, and no partial counters become a complete measurement.

The diagnostic array is fresh and exact-sized. Capacity is checked independently
before generation; a bad/missing array disables observation without changing
generation. After normal finish 0/1/2 and immediately after a clean ExceptionCheck,
one SetLongArrayRegion copies all slots. No unrelated JNI operation lies between
that precheck, copy and exception check. If **this diagnostic operation creates a
new exception**, only that new failure is cleared and the original finish is
returned without retry or publication flag. The same clean-before/new-only rule
guards a diagnostic capacity-query failure. No generation exception is ever cleared.
The [JNI array-region contract](https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html#setprimitivetypearrayregion-routines)
documents the bounds requirement; exact allocation and validated capacity satisfy
the normal copy path. No primitive critical/pinned buffer spans Java callbacks.

Only after the copy returns without an exception does the private observed JNI
integer include bit 8, PUBLISHED. The low byte retains the original finish 0/1/2.
The default path returns exactly 0/1/2 and no metadata bit. Kotlin ignores the
entire array unless PUBLISHED is set, so partial writes/zeros cannot be accepted.
Unknown metadata bits/format return an unavailable snapshot while a valid low-byte
finish remains unchanged. Parser, allocation and holder-recording failures are
isolated from generation and its original exception. Array publication is a
private local ABI, not a Cortex wire mask or negotiated capability.

| Private array indices | Values |
| --- | --- |
| 0–2 | Schema 1; prompt token count (-1 unknown); tokenizer calls |
| 3–6 | Sampled tokens; EOG samples; callback attempts; normal callback returns |
| 7–10 | Prefill attempted/succeeded calls, submitted/successful-call tokens |
| 11–14 | Decode attempted/succeeded calls, submitted/successful-call tokens |
| 15–16 | Prefill/decode unknown-partial flags (0/1) |
| 17–19 | Tokenize/prefill/decode API wall nanos (-1 unavailable/not called) |

The parser checks exact length/schema/flag/finish, count bounds/relationships,
known prompt plus requested output fitting the existing global context ceiling,
successful versus submitted positive batches, EOG/token-limit behavior and
duration presence/range. It copies only typed scalars, retaining no mutable array.
The states are NOT_STARTED, IN_PROGRESS, LOCAL_JNI_RETURNED,
UNAVAILABLE_GENERATION (exception/rejection before a normal JNI return),
UNAVAILABLE_EXPORT, UNAVAILABLE_FORMAT and UNAVAILABLE_RECORDING. A cancelled
normal return can contain a known partial prefix; exceptions contain no metrics.

## Tests and outstanding evidence

Sixteen JVM tests exercise this exact decoder/holder: EOG, max-one, cancellation,
successful prefix versus unknown partial work, format/flag/type bounds, uncalled
versus zero duration, failure/no stale observation, immutable snapshots and one-shot
concurrent claim. The module adds only the related junit4 test dependency.
The cancellation regression rejects decode work above min(normal callback returns,
maxTokens-1), while preserving a cancelled invocation after a successful decode
when maxTokens is larger. This bound follows the current loop for every finish.

One new Android boundary case covers unloaded/argument rejection and fresh holders.
Five optional real-model Android cases compare greedy observed/default bytes and
finish; tokenizer count versus byte/piece counts; one-token/callback cancellation;
original callback-exception identity and subsequent reuse; EOG when actually
reached; and close during a blocked callback with native shared ownership. Without
the existing explicit verified model fixture they skip. If the EOG fixture does
not reach EOG, that case explicitly skips and supplies no EOG runtime evidence.
These isolated cases retain fresh synthetic emulator-only governor decisions;
they do not configure or approve product inference.

No Gradle/Docker/ADB/device command was executed by the author. Root must perform
canonical JVM/native/R8/packaging and actual optional fixture regressions, retaining
all skips/failures and exact source/artifact/environment identity. This package
does not itself supply integrated MAIN/HOST observations, actual physical/native
compatibility qualification, trusted profiles, production inference, F5 closure,
F6 or real-duration soak.
