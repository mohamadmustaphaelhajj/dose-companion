#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

// No prompts, pixels or generated medical text are logged or written to files.
static void quiet(enum ggml_log_level, const char *, void *) {}
struct Engine {
    llama_model * model = nullptr;
    mtmd_context * vision = nullptr;
    std::atomic<bool> cancelled{false};
    ~Engine() { if (vision) mtmd_free(vision); if (model) llama_model_free(model); }
};
static void require(bool value, const char * message) { if (!value) throw std::runtime_error(message); }
static void fail(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
static std::string utf(JNIEnv * env, jstring value) {
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
static bool aborted(void * data) { return static_cast<Engine *>(data)->cancelled.load(); }

extern "C" JNIEXPORT jlong JNICALL Java_com_dosecompanion_app_ExtractRuntime_create(
        JNIEnv * env, jclass, jstring model_path, jstring vision_path) {
    try {
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_log_set(quiet, nullptr); mtmd_helper_log_set(quiet, nullptr); llama_backend_init(); });
        auto engine = std::make_unique<Engine>();
        auto params = llama_model_default_params(); params.n_gpu_layers = 0;
        engine->model = llama_model_load_from_file(utf(env, model_path).c_str(), params);
        require(engine->model, "The local language model could not be loaded.");
        auto vision = mtmd_context_params_default(); vision.use_gpu = false; vision.n_threads = 4;
        vision.warmup = false; vision.print_timings = false; vision.image_max_tokens = 256;
        engine->vision = mtmd_init_from_file(utf(env, vision_path).c_str(), engine->model, vision);
        require(engine->vision && mtmd_support_vision(engine->vision), "The local vision model could not be loaded.");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &) { fail(env, "Local model initialization failed. Check model files and available memory."); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_dosecompanion_app_ExtractRuntime_generate(
        JNIEnv * env, jclass, jlong handle, jbyteArray jpeg, jstring system_prompt) {
    try {
        auto * engine = reinterpret_cast<Engine *>(handle);
        require(engine && !engine->cancelled, "Reading cancelled.");
        auto params = llama_context_default_params(); params.n_ctx = 4096; params.n_batch = 512; params.n_ubatch = 512;
        params.n_threads = 4; params.n_threads_batch = 4;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(engine->model, params), llama_free);
        require(ctx != nullptr, "Not enough memory for extraction.");
        llama_set_abort_callback(ctx.get(), aborted, engine);
        jsize size = env->GetArrayLength(jpeg); require(size > 0 && size <= 16 * 1024 * 1024, "Invalid image size.");
        std::vector<unsigned char> data(size);
        env->GetByteArrayRegion(jpeg, 0, size, reinterpret_cast<jbyte *>(data.data()));
        auto decoded = mtmd_helper_bitmap_init_from_buf(engine->vision, data.data(), data.size(), false, mtmd_helper_init_opt_default());
        std::fill(data.begin(), data.end(), 0);
        std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(decoded.bitmap, mtmd_bitmap_free);
        require(bitmap != nullptr && !decoded.video_ctx, "The image could not be decoded.");
        require(mtmd_bitmap_get_nx(bitmap.get()) <= 640 && mtmd_bitmap_get_ny(bitmap.get()) <= 640,
                "The image exceeds the CPU extraction size limit.");
        std::string system = utf(env, system_prompt);
        std::string user = std::string(mtmd_default_marker()) + "\nExtract the visible label fields. Do not guess missing text.";
        llama_chat_message messages[] = {{"system", system.c_str()}, {"user", user.c_str()}};
        int length = llama_chat_apply_template("chatml", messages, 2, true, nullptr, 0);
        require(length > 0 && length < 16384, "Prompt format unavailable.");
        std::vector<char> prompt(length + 1);
        llama_chat_apply_template("chatml", messages, 2, true, prompt.data(), prompt.size());
        mtmd_input_text input{}; input.text = prompt.data(); input.text_len = length;
        input.add_special = true; input.parse_special = true;
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
        const mtmd_bitmap * image = bitmap.get();
        require(mtmd_tokenize(engine->vision, chunks.get(), &input, &image, 1) == 0, "Vision tokenization failed.");
        require(mtmd_helper_get_n_tokens(chunks.get()) < 3500 && !engine->cancelled, "Image exceeds the local context budget.");
        llama_pos past = 0;
        require(mtmd_helper_eval_chunks(engine->vision, ctx.get(), chunks.get(), 0, 0, 512, true, &past) == 0,
                "Vision evaluation failed or was cancelled.");
        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string output; bool ended = false;
        for (int i = 0; i < 256 && past < 4000; i++) {
            require(!engine->cancelled, "Reading cancelled.");
            llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            char piece[256]; int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            require(n >= 0, "Unexpected token size."); output.append(piece, n);
            require(output.size() <= 8192, "Generated answer exceeded the size limit.");
            require(llama_decode(ctx.get(), llama_batch_get_one(&token, 1)) == 0, "Decoding failed or was cancelled."); past++;
        }
        require(ended && !engine->cancelled, "The model answer was truncated or cancelled.");
        jbyteArray result = env->NewByteArray(output.size());
        env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<const jbyte *>(output.data()));
        std::fill(output.begin(), output.end(), 0); return result;
    } catch (const std::exception & error) { fail(env, error.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_dosecompanion_app_ExtractRuntime_cancelNative(JNIEnv *, jclass, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL Java_com_dosecompanion_app_ExtractRuntime_destroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}
