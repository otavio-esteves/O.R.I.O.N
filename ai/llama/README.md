# O.R.I.O.N. llama.cpp boundary

This module contains an actual CPU-only llama.cpp JNI wrapper. It is not a
qualified inference profile and does not grant permission to load a model. The
MAIN-owned client must obtain a fresh ResourceGovernor decision before load and
each generation; only the private `:ai` service may own this wrapper in product.

The upstream archive is pinned to
`7d7f0287530bc8981690f361d8c1627dbae1c99a`, SHA256
`2f15b173875f53cb2a3d099b65875a24824859b0b9a7ede276d58ec2881ae085`.
CMake verifies the archive before extraction in the ignored module `.cxx` tree.
No upstream source tree or binary is committed. A first native configure requires
network access to the pinned source archive; the resulting APK does not. Upstream
MIT attribution is packaged as `assets/orion_licenses/llama.cpp-MIT.txt`.

The implementation follows the pinned upstream
[Android build guidance](https://github.com/ggml-org/llama.cpp/blob/7d7f0287530bc8981690f361d8c1627dbae1c99a/docs/android.md),
[C API](https://github.com/ggml-org/llama.cpp/blob/7d7f0287530bc8981690f361d8c1627dbae1c99a/include/llama.h), and
[license](https://github.com/ggml-org/llama.cpp/blob/7d7f0287530bc8981690f361d8c1627dbae1c99a/LICENSE).
The pinned NDK r29/CMake 4.0.2 compile arm64-v8a and x86_64 with CPU baseline
instructions, static llama/ggml libraries and one JNI shared object plus the NDK
C++ runtime. No OpenMP, GPU, RPC, TLS/network model loader or dynamic backend
discovery is enabled. Final ELF/APK/AAB checks remain mandatory.

`OrionLlamaRuntime` retains only an opaque registry ID. Native shared ownership
keeps allocations alive while cancellation/close races with an operation; context
destruction precedes model destruction. One runtime may exist in the process,
including a closed runtime whose operation has not yet unwound. No finalizer or
Activity reference exists. Owners must close explicitly. Backend process-global
state lasts until process exit; model/context/sampler state has explicit RAII.

Load supports a read-only `/proc/self/fd/N` path. The service retains the owning FD
through unload. Metadata validation is distinct from full hash/tensor validation:
`validateModel()` only parses GGUF metadata and confirms tensor presence;
`load()` performs upstream tensor checks and accepts decoder-only models. Registry
format/hash/import validation is still required before either operation.

Generation has one sequence, clears the KV cache at entry/exit, bounds UTF-8 prompt
bytes, tokens, context and token-piece bytes, and emits raw UTF-8 byte pieces on the
calling worker thread. The service must use an incremental UTF-8 decoder and bound
its callback/transport queues. Callback `false` and `cancel()` cooperatively abort.
Cancellation during context allocation can only complete once that upstream call
returns; the service must enforce its timeout/process escalation separately. No
native-created thread calls Java, so no thread attach/detach is necessary. Java
callback exceptions stay pending while C++ RAII unwinds; other native exceptions
are translated to a fixed content-free exception. Upstream diagnostic callbacks
are suppressed rather than exposing paths or content. Fatal native failure still
requires process isolation and Binder-death handling.

The library supplies JNI R8 keep rules and full release native symbols. Static
linker alignment and compilation do not establish release load, physical-device
performance, API compatibility or restart-loop qualification.

## Explicit emulator fixture

`test-fixtures/model-manifest.json` pins an official Qwen Apache-2.0 model and
upstream LFS SHA256. `python3 ai/llama/test-fixtures/download-model.py` explicitly
downloads it into ignored `build/model-fixtures/`, verifies size/hash and atomically
replaces its temporary download. Gradle and APK runtime never download this model.
The license is retained beside its manifest. This fixture is not a production
model choice, resource calibration, benchmark or qualification.

Build the library and its instrumentation APK canonically. Run the four boundary
smoke cases separately from `OrionLlamaModelTest`. Real-model tests require the
verified fixture in the test application's private files directory, passed through
instrumentation arguments `orionModelPath=/data/user/0/<test-package>/files/<file>`
and `orionEnvironment=ANDROID_EMULATOR`. Without explicit arguments they skip;
skips are never evidence of inference. These isolated test-APK cases use a fresh,
explicitly labelled synthetic fixture governor decision for every actual load and
inference. They cannot activate or qualify product inference. Service/Core process
ownership and physical qualification require their independent tests.
