#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

namespace {
using Clock = std::chrono::steady_clock;
constexpr jsize max_samples = 16'000 * 8;
constexpr size_t max_transcript_bytes = 4096;
std::atomic<jlong> cancelled_through{0};
std::mutex model_lock;

struct Decode {
    jlong id;
    Clock::time_point deadline;
};

bool should_abort(void *context) {
    const auto &decode = *static_cast<Decode *>(context);
    return cancelled_through.load() >= decode.id || Clock::now() >= decode.deadline;
}

void discard_log(enum ggml_log_level, const char *, void *) {}

void filter_synthesis_log(const __android_log_message *message) {
    if (message->tag != nullptr && std::strcmp(message->tag, "sherpa-onnx") == 0) return;
    __android_log_logd_logger(message);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_network_orion_voice_service_OrionTtsNative_beginPrivateSynthesis(JNIEnv *, jobject) {
    // Sherpa may report an unphonemizable input through both logcat and stderr.
    __android_log_set_logger(filter_synthesis_log);
    fflush(stderr);
    const int saved = dup(STDERR_FILENO);
    if (saved < 0 && errno != EBADF) return -1;
    const int discarded = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (discarded < 0) {
        if (saved >= 0) close(saved);
        return -1;
    }
    if (discarded != STDERR_FILENO) {
        const int redirected = dup2(discarded, STDERR_FILENO);
        close(discarded);
        if (redirected < 0) {
            if (saved >= 0) close(saved);
            return -1;
        }
    }
    return saved < 0 ? -2 : saved;
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_voice_service_OrionTtsNative_endPrivateSynthesis(JNIEnv *, jobject, jint saved) {
    fflush(stderr);
    if (saved == -2) close(STDERR_FILENO);
    else if (saved >= 0) {
        dup2(saved, STDERR_FILENO);
        close(saved);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_network_orion_voice_service_OrionWhisper_cancel(JNIEnv *, jobject, jlong id) {
    jlong previous = cancelled_through.load();
    while (previous < id && !cancelled_through.compare_exchange_weak(previous, id)) {}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_network_orion_voice_service_OrionWhisper_transcribe(
        JNIEnv *env, jobject, jlong id, jstring model_path, jshortArray pcm) {
    try {
        std::lock_guard<std::mutex> guard(model_lock);
        Decode decode{id, Clock::now() + std::chrono::seconds(21)};
        if (id <= 0 || model_path == nullptr || pcm == nullptr || should_abort(&decode)) return nullptr;
        const auto length = env->GetArrayLength(pcm);
        if (length <= 0 || length > max_samples) return nullptr;
        std::vector<jshort> raw(length);
        env->GetShortArrayRegion(pcm, 0, length, raw.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> waveform;
        waveform.reserve(length);
        for (const auto sample : raw) waveform.push_back(sample / 32768.0f);

        whisper_log_set(discard_log, nullptr);
        const char *path = env->GetStringUTFChars(model_path, nullptr);
        if (path == nullptr) return nullptr;
        auto model_parameters = whisper_context_default_params();
        model_parameters.use_gpu = false;
        auto *model = whisper_init_from_file_with_params(path, model_parameters);
        env->ReleaseStringUTFChars(model_path, path);
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(model, &whisper_free);
        if (!context || should_abort(&decode)) return nullptr;

        auto parameters = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        parameters.n_threads = 2;
        // Whisper Small needs less encoder work when the context follows the captured audio.
        // One encoder position covers 320 PCM samples. Keep another 2.4 seconds of padding.
        parameters.audio_ctx = whisper_model_n_audio_layer(context.get()) == 12
            ? std::max(320, static_cast<int>(length / 320) + 120) : 768;
        parameters.max_tokens = 64;
        parameters.temperature_inc = 0.0f;
        parameters.language = "pt";
        parameters.no_context = true;
        parameters.no_timestamps = true;
        parameters.print_progress = false;
        parameters.print_realtime = false;
        parameters.print_timestamps = false;
        parameters.abort_callback = should_abort;
        parameters.abort_callback_user_data = &decode;
        if (whisper_full(context.get(), parameters, waveform.data(), length) != 0 || should_abort(&decode)) return nullptr;

        std::string text;
        for (int segment = 0; segment < whisper_full_n_segments(context.get()); ++segment) {
            const char *part = whisper_full_get_segment_text(context.get(), segment);
            if (part == nullptr) return nullptr;
            text += part;
            if (text.size() > max_transcript_bytes) return nullptr;
        }
        if (text.empty()) return nullptr;
        auto output = env->NewByteArray(static_cast<jsize>(text.size()));
        if (output != nullptr) env->SetByteArrayRegion(output, 0, static_cast<jsize>(text.size()),
                                                      reinterpret_cast<const jbyte *>(text.data()));
        return output;
    } catch (...) {
        return nullptr;
    }
}
