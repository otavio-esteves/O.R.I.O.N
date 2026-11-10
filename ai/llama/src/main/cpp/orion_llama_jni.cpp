#include <jni.h>
#include "llama.h"
#include "gguf.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct PendingJavaException {};
struct Runtime {
    std::atomic<bool> cancelled{false};
    std::mutex operation;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Runtime>> registry;
std::weak_ptr<Runtime> active_runtime;
jlong next_id = 1;
std::once_flag backend_once;

void discard_log(ggml_log_level, const char *, void *) {}
bool abort_decode(void * data) { return static_cast<Runtime *>(data)->cancelled.load(); }
bool load_progress(float, void * data) { return !abort_decode(data); }

void java_check(JNIEnv * env) { if (env->ExceptionCheck()) throw PendingJavaException{}; }

template<typename T, typename F> T guarded(JNIEnv * env, T fallback, F work) {
    try { return work(); }
    catch (const PendingJavaException &) { return fallback; }
    catch (...) {
        if (!env->ExceptionCheck()) {
            jclass error = env->FindClass("java/lang/IllegalStateException");
            if (error) env->ThrowNew(error, "Native inference operation failed.");
        }
        return fallback;
    }
}

std::shared_ptr<Runtime> runtime(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto found = registry.find(id);
    if (found == registry.end()) throw std::runtime_error("closed");
    return found->second;
}

std::unique_lock<std::mutex> operation(Runtime & value) {
    std::unique_lock<std::mutex> lock(value.operation, std::try_to_lock);
    if (!lock.owns_lock()) throw std::runtime_error("busy");
    return lock;
}

std::string bytes(JNIEnv * env, jbyteArray input, int limit, bool path = false) {
    if (!input) throw std::invalid_argument("input");
    const auto size = env->GetArrayLength(input);
    java_check(env);
    if (size <= 0 || size > limit) throw std::invalid_argument("size");
    std::string result(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(result.data()));
    java_check(env);
    if (path && (result.front() != '/' || result.find('\0') != std::string::npos))
        throw std::invalid_argument("path");
    return result;
}

struct ClearMemory {
    llama_memory_t memory;
    ~ClearMemory() { if (memory) llama_memory_clear(memory, true); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeCreate(JNIEnv * env, jobject) {
    return guarded<jlong>(env, 0, [] {
        std::call_once(backend_once, [] {
            // Upstream diagnostics can contain paths and prompt fragments. No raw upstream logs.
            llama_log_set(discard_log, nullptr);
            ggml_log_set(discard_log, nullptr);
            llama_backend_init();
        });
        auto value = std::make_shared<Runtime>();
        std::lock_guard<std::mutex> lock(registry_mutex);
        if (next_id == std::numeric_limits<jlong>::max() || !active_runtime.expired())
            throw std::runtime_error("runtime limit");
        const jlong id = next_id++;
        active_runtime = value;
        registry.emplace(id, std::move(value));
        return id;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeDestroy(JNIEnv * env, jobject, jlong id) {
    guarded<int>(env, 0, [id] {
        // Shared ownership retains model/context until a concurrent operation unwinds.
        std::shared_ptr<Runtime> retained;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            auto found = registry.find(id);
            if (found == registry.end()) return 0;
            retained = found->second;
            retained->cancelled.store(true);
            registry.erase(found);
        }
        return 0;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeCancel(JNIEnv * env, jobject, jlong id) {
    guarded<int>(env, 0, [id] { runtime(id)->cancelled.store(true); return 0; });
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeResetCancellation(JNIEnv * env, jobject, jlong id) {
    guarded<int>(env, 0, [id] { runtime(id)->cancelled.store(false); return 0; });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeValidate(JNIEnv * env, jobject, jlong id, jbyteArray path) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        auto value = runtime(id);
        auto lock = operation(*value);
        const auto name = bytes(env, path, 4096, true);
        gguf_init_params params{};
        params.no_alloc = true;
        std::unique_ptr<gguf_context, decltype(&gguf_free)> metadata(
            gguf_init_from_file(name.c_str(), params), gguf_free);
        return static_cast<jboolean>(metadata && gguf_get_n_tensors(metadata.get()) > 0 && !value->cancelled.load());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeLoad(JNIEnv * env, jobject, jlong id,
        jbyteArray path, jint context_tokens, jint threads) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (context_tokens < 32 || context_tokens > 32768 || threads < 1 || threads > 32)
            throw std::invalid_argument("limits");
        auto value = runtime(id);
        auto lock = operation(*value);
        const auto name = bytes(env, path, 4096, true);
        value->context.reset();
        value->model.reset();
        if (value->cancelled.load()) return static_cast<jboolean>(JNI_FALSE);
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.lazy_mode = LLAMA_LAZY_MODE_OFF;
        model_params.check_tensors = true;
        model_params.progress_callback = load_progress;
        model_params.progress_callback_user_data = value.get();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(name.c_str(), model_params), llama_model_free);
        if (value->cancelled.load()) return static_cast<jboolean>(JNI_FALSE);
        if (!model || llama_model_has_encoder(model.get()) || !llama_model_has_decoder(model.get()))
            throw std::runtime_error("unsupported model");
        auto params = llama_context_default_params();
        params.n_ctx = static_cast<uint32_t>(context_tokens);
        params.n_batch = static_cast<uint32_t>(std::min(context_tokens, 512));
        params.n_ubatch = static_cast<uint32_t>(std::min(context_tokens, 128));
        params.n_seq_max = 1;
        params.n_outputs_max = 1;
        params.n_outputs_max_per_seq = 1;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.offload_kqv = false;
        params.op_offload = false;
        params.no_perf = true;
        params.abort_callback = abort_decode;
        params.abort_callback_data = value.get();
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), params), llama_free);
        if (value->cancelled.load()) return static_cast<jboolean>(JNI_FALSE);
        if (!context || llama_n_ctx(context.get()) > static_cast<uint32_t>(context_tokens))
            throw std::runtime_error("context unavailable");
        value->model = std::move(model);
        value->context = std::move(context);
        return static_cast<jboolean>(JNI_TRUE);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeGenerate(JNIEnv * env, jobject, jlong id,
        jbyteArray input, jint max_tokens, jfloat temperature, jobject callback) {
    return guarded<jint>(env, -1, [&] {
        if (max_tokens < 1 || max_tokens > 32768 || !std::isfinite(temperature) ||
                temperature < 0 || temperature > 2 || !callback) throw std::invalid_argument("limits");
        auto value = runtime(id);
        auto lock = operation(*value);
        if (!value->context || !value->model) throw std::runtime_error("unloaded");
        if (value->cancelled.load()) return 1;
        auto prompt = bytes(env, input, 1024 * 1024);
        auto * context = value->context.get();
        auto memory = llama_get_memory(context);
        if (!memory) throw std::runtime_error("memory unavailable");
        llama_memory_clear(memory, true);
        ClearMemory cleanup{memory};
        const auto * vocab = llama_model_get_vocab(value->model.get());
        const auto measured = llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
        if (measured >= 0 || measured == std::numeric_limits<int32_t>::min())
            throw std::runtime_error("tokenization unavailable");
        const auto count = -measured;
        const auto available = llama_n_ctx(context);
        if (static_cast<uint32_t>(count) > available || static_cast<uint32_t>(max_tokens) > available - count)
            throw std::invalid_argument("context limit");
        std::vector<llama_token> tokens(static_cast<size_t>(count));
        if (llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), tokens.data(), count, true, true) != count)
            throw std::runtime_error("tokenization mismatch");
        auto sampler_params = llama_sampler_chain_default_params();
        sampler_params.no_perf = true;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(sampler_params), llama_sampler_free);
        if (!sampler) throw std::bad_alloc();
        if (temperature == 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        jclass callback_type = env->GetObjectClass(callback);
        java_check(env);
        jmethodID on_piece = env->GetMethodID(callback_type, "onPiece", "([B)Z");
        env->DeleteLocalRef(callback_type);
        java_check(env);
        for (int32_t offset = 0; offset < count;) {
            if (value->cancelled.load()) return 1;
            const auto batch_size = std::min(count - offset, static_cast<int32_t>(llama_n_batch(context)));
            const auto code = llama_decode(context, llama_batch_get_one(tokens.data() + offset, batch_size));
            if (value->cancelled.load()) return 1;
            if (code != 0) throw std::runtime_error("prefill failed");
            offset += batch_size;
        }
        for (int32_t generated = 0; generated < max_tokens; ++generated) {
            if (value->cancelled.load()) return 1;
            llama_token token = llama_sampler_sample(sampler.get(), context, -1);
            if (llama_vocab_is_eog(vocab, token)) return 0;
            char initial[256];
            auto size = llama_token_to_piece(vocab, token, initial, sizeof(initial), 0, false);
            if (size == std::numeric_limits<int32_t>::min() || size < -65536 || size > 65536)
                throw std::runtime_error("piece limit");
            std::vector<char> piece;
            const char * data = initial;
            if (size < 0) {
                piece.resize(static_cast<size_t>(-size));
                size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
                if (size < 0 || static_cast<size_t>(size) > piece.size()) throw std::runtime_error("piece unavailable");
                data = piece.data();
            }
            jbyteArray delta = env->NewByteArray(size);
            java_check(env);
            env->SetByteArrayRegion(delta, 0, size, reinterpret_cast<const jbyte *>(data));
            java_check(env);
            const bool keep_going = env->CallBooleanMethod(callback, on_piece, delta);
            env->DeleteLocalRef(delta);
            java_check(env);
            if (!keep_going || value->cancelled.load()) { value->cancelled.store(true); return 1; }
            if (generated + 1 < max_tokens) {
                const auto code = llama_decode(context, llama_batch_get_one(&token, 1));
                if (value->cancelled.load()) return 1;
                if (code != 0) throw std::runtime_error("decode failed");
            }
        }
        return 2;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaRuntime_nativeUnload(JNIEnv * env, jobject, jlong id) {
    guarded<int>(env, 0, [id] {
        auto value = runtime(id);
        auto lock = operation(*value);
        value->context.reset();
        value->model.reset();
        return 0;
    });
}
