#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {
struct ScopedEnv {
    JavaVM *vm; JNIEnv *env = nullptr; bool attached = false;
    explicit ScopedEnv(JavaVM *machine) : vm(machine) {
        const auto status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~ScopedEnv() { if (attached) vm->DetachCurrentThread(); }
};

// CPU abort hooks can run on native worker threads. JNI ownership is explicit on every thread.
struct Callbacks {
    JavaVM *vm = nullptr; jobject target = nullptr; jmethodID cancelMethod = nullptr; jmethodID emitMethod = nullptr;
    std::mutex failureMutex; jthrowable failure = nullptr; std::atomic<bool> failed{false};
    Callbacks(JNIEnv *env, jobject value) {
        env->GetJavaVM(&vm);
        target = env->NewGlobalRef(value);
        if (!target) throw std::runtime_error("callback");
        auto cls = env->GetObjectClass(value);
        if (cls) {
            cancelMethod = env->GetMethodID(cls, "shouldCancel", "()Z");
            if (cancelMethod && !env->ExceptionCheck()) emitMethod = env->GetMethodID(cls, "emit", "([B)Z");
            env->DeleteLocalRef(cls);
        }
        if (!cancelMethod || !emitMethod || env->ExceptionCheck()) {
            env->DeleteGlobalRef(target); target = nullptr;
            throw std::runtime_error("callback");
        }
    }
    ~Callbacks() {
        ScopedEnv scope(vm);
        if (scope.env) { scope.env->DeleteGlobalRef(target); if (failure) scope.env->DeleteGlobalRef(failure); }
    }
    bool capture(JNIEnv *env) {
        if (!env->ExceptionCheck()) return false;
        auto thrown = env->ExceptionOccurred(); env->ExceptionClear();
        { std::lock_guard<std::mutex> lock(failureMutex); if (!failure) failure = static_cast<jthrowable>(env->NewGlobalRef(thrown)); }
        // An OOME while retaining another exception cannot leave a worker-thread
        // JNI exception pending after control returns to ggml.
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(thrown); failed = true; return true;
    }
    bool cancelled() {
        if (failed) return true;
        ScopedEnv scope(vm);
        if (!scope.env) { failed = true; return true; }
        const bool value = scope.env->CallBooleanMethod(target, cancelMethod);
        return capture(scope.env) || value;
    }
    bool emit(const std::string &piece) {
        if (piece.size() > 65536 || cancelled()) return false;
        ScopedEnv scope(vm); if (!scope.env) return false;
        auto bytes = scope.env->NewByteArray(static_cast<jsize>(piece.size()));
        if (!bytes) { capture(scope.env); return false; }
        scope.env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(piece.size()), reinterpret_cast<const jbyte *>(piece.data()));
        if (capture(scope.env)) { scope.env->DeleteLocalRef(bytes); return false; }
        const bool accepted = scope.env->CallBooleanMethod(target, emitMethod, bytes);
        const bool exception = capture(scope.env); scope.env->DeleteLocalRef(bytes);
        return accepted && !exception;
    }
    void propagate(JNIEnv *env) {
        if (failure) env->Throw(failure);
        else if (failed) throw std::runtime_error("callback");
    }
};

struct FileCloser { void operator()(FILE *file) const { if (file) fclose(file); } };
struct ModelCloser { void operator()(llama_model *model) const { if (model) llama_model_free(model); } };
struct ContextCloser { void operator()(llama_context *context) const { if (context) llama_free(context); } };
struct SamplerCloser { void operator()(llama_sampler *sampler) const { if (sampler) llama_sampler_free(sampler); } };
struct Engine {
    std::unique_ptr<FILE, FileCloser> file;
    std::unique_ptr<llama_model, ModelCloser> model;
    std::unique_ptr<llama_context, ContextCloser> context;
    int capacity = 0;
};
std::mutex enginesMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> engines;
std::atomic<jlong> nextId{1};
std::once_flag backend;
void quietLog(enum ggml_log_level, const char *, void *) {}
void quietAbort(const char *) {} // ggml still terminates :ai; discard model-controlled abort text.
bool abortDecode(void *value) { return static_cast<Callbacks *>(value)->cancelled(); }
bool progressLoad(float, void *value) { return !static_cast<Callbacks *>(value)->cancelled(); }
void throwFailure(JNIEnv *env) {
    if (!env->ExceptionCheck()) {
        auto cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) env->ThrowNew(cls, "Cortex native operation failed.");
    }
}
std::shared_ptr<Engine> find(jlong handle) {
    std::lock_guard<std::mutex> lock(enginesMutex);
    auto it = engines.find(handle);
    if (it == engines.end()) throw std::runtime_error("handle");
    return it->second;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_network_orion_ai_llama_OrionLlamaEngine_nativeLoad(JNIEnv *env, jobject, jint fd, jint context, jint threads, jobject callback) {
    try {
        if (context < 32 || context > 131072 || threads < 1 || threads > 64) throw std::runtime_error("bounds");
        struct stat info{};
        const auto flags = fcntl(fd, F_GETFL);
        if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY || fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_size < 24)
            throw std::runtime_error("descriptor");
        Callbacks callbacks(env, callback);
        if (callbacks.cancelled()) { callbacks.propagate(env); return 0; }
        std::call_once(backend, [] { llama_log_set(quietLog, nullptr); ggml_log_set(quietLog, nullptr); ggml_set_abort_callback(quietAbort); llama_backend_init(); });
        auto engine = std::make_shared<Engine>();
        const int copy = dup(fd); if (copy < 0) throw std::runtime_error("descriptor");
        FILE *file = fdopen(copy, "rb");
        if (!file) { close(copy); throw std::runtime_error("descriptor"); }
        engine->file.reset(file);
        if (fseek(file, 0, SEEK_SET)) throw std::runtime_error("offset");
        auto parameters = llama_model_default_params();
        parameters.n_gpu_layers = 0;
        parameters.lazy_mode = LLAMA_LAZY_MODE_OFF;
        parameters.check_tensors = true;
        parameters.progress_callback = progressLoad;
        parameters.progress_callback_user_data = &callbacks;
        engine->model.reset(llama_model_load_from_file_ptr(file, parameters));
        if (callbacks.cancelled()) { callbacks.propagate(env); return 0; }
        if (!engine->model || llama_model_has_encoder(engine->model.get())) throw std::runtime_error("model");
        auto limits = llama_context_default_params();
        limits.n_ctx = static_cast<uint32_t>(context);
        limits.n_batch = static_cast<uint32_t>(std::min(context, 256));
        limits.n_ubatch = limits.n_batch;
        limits.n_threads = threads; limits.n_threads_batch = threads;
        limits.no_perf = true; limits.offload_kqv = false; limits.op_offload = false;
        engine->context.reset(llama_init_from_model(engine->model.get(), limits));
        if (callbacks.cancelled()) { callbacks.propagate(env); return 0; }
        if (!engine->context) throw std::runtime_error("context");
        engine->capacity = context;
        const jlong id = nextId++;
        if (id <= 0) throw std::runtime_error("handle");
        std::lock_guard<std::mutex> lock(enginesMutex); engines.emplace(id, std::move(engine));
        return id;
    } catch (...) { throwFailure(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaEngine_nativeInfer(JNIEnv *env, jobject, jlong handle, jbyteArray input,
        jint maxTokens, jfloat temperature, jobject callback) {
    try {
        const auto length = env->GetArrayLength(input);
        if (length < 1 || length > 131072 || maxTokens < 1 || maxTokens > 131072 || !(temperature >= 0 && temperature <= 2))
            throw std::runtime_error("bounds");
        auto engine = find(handle);
        Callbacks callbacks(env, callback);
        std::string prompt(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(prompt.data()));
        if (env->ExceptionCheck()) return;
        const auto vocabulary = llama_model_get_vocab(engine->model.get());
        const int required = -llama_tokenize(vocabulary, prompt.data(), length, nullptr, 0, true, true);
        if (required <= 0 || required > engine->capacity || maxTokens > engine->capacity - required) throw std::runtime_error("context");
        std::vector<llama_token> tokens(static_cast<size_t>(required));
        const auto actual = llama_tokenize(vocabulary, prompt.data(), length, tokens.data(), required, true, true);
        std::fill(prompt.begin(), prompt.end(), '\0');
        if (actual != required) throw std::runtime_error("tokens");
        auto ctx = engine->context.get();
        llama_memory_clear(llama_get_memory(ctx), true);
        llama_set_abort_callback(ctx, abortDecode, &callbacks);
        // Always detach the stack-owned abort callback, including failures/cancellation.
        struct Reset { llama_context *ctx; ~Reset() { llama_set_abort_callback(ctx, nullptr, nullptr); llama_memory_clear(llama_get_memory(ctx), true); } } reset{ctx};
        auto samplerParams = llama_sampler_chain_default_params(); samplerParams.no_perf = true;
        std::unique_ptr<llama_sampler, SamplerCloser> sampler(llama_sampler_chain_init(samplerParams));
        if (!sampler) throw std::runtime_error("sampler");
        if (temperature == 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else { llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature)); llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED)); }
        for (int offset = 0; offset < required;) {
            if (callbacks.cancelled()) { callbacks.propagate(env); return; }
            const int count = std::min(required - offset, 256);
            if (llama_decode(ctx, llama_batch_get_one(tokens.data() + offset, count))) {
                if (callbacks.cancelled()) { callbacks.propagate(env); return; }
                throw std::runtime_error("decode");
            }
            offset += count;
        }
        for (int count = 0; count < maxTokens; ++count) {
            if (callbacks.cancelled()) { callbacks.propagate(env); return; }
            auto token = llama_sampler_sample(sampler.get(), ctx, -1);
            if (llama_vocab_is_eog(vocabulary, token)) break;
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocabulary, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) {
                if (size < -65536) throw std::runtime_error("piece");
                piece.resize(static_cast<size_t>(-size));
                size = llama_token_to_piece(vocabulary, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (size < 0) throw std::runtime_error("piece");
            if (!callbacks.emit(std::string(piece.data(), static_cast<size_t>(size)))) { callbacks.propagate(env); return; }
            if (count + 1 < maxTokens && llama_decode(ctx, llama_batch_get_one(&token, 1))) {
                if (callbacks.cancelled()) { callbacks.propagate(env); return; }
                throw std::runtime_error("decode");
            }
        }
        callbacks.propagate(env);
    } catch (...) { throwFailure(env); }
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_ai_llama_OrionLlamaEngine_nativeDestroy(JNIEnv *env, jobject, jlong handle) {
    try { std::lock_guard<std::mutex> lock(enginesMutex); engines.erase(handle); }
    catch (...) { throwFailure(env); }
}
