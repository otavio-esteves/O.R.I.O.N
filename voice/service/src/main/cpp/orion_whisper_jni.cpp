#include <jni.h>
#include <atomic>
#include <chrono>
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
        Decode decode{id, Clock::now() + std::chrono::seconds(15)};
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
