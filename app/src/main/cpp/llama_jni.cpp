#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "PegaseLLM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PegaseLLM", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "PegaseLLM", __VA_ARGS__)

namespace {

std::atomic<bool> g_backend_ready{false};
std::atomic<bool> g_cancel{false};

struct LlmState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    int n_threads = 4;
    int n_ctx = 4096;
    int n_batch = 512;
};

LlmState g_state;

std::string token_im_end() {
    return std::string("<|") + "im_end|>";
}

std::string token_think_close() {
    return std::string("<|") + "/think>";
}

void ensure_backend() {
    if (!g_backend_ready.exchange(true)) {
        llama_backend_init();
        ggml_backend_load_all();
        LOGI("llama backend initialized");
    }
}

void free_sampler() {
    if (g_state.sampler != nullptr) {
        llama_sampler_free(g_state.sampler);
        g_state.sampler = nullptr;
    }
}

void free_context() {
    if (g_state.ctx != nullptr) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
}

void free_model() {
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
}

llama_sampler *build_sampler(float temperature, float top_p) {
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(sparams);
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return chain;
}

std::string strip_chat_artifacts(const std::string &text) {
    std::string out = text;
    const std::string think_close = token_think_close();
    const auto think_pos = out.find(think_close);
    if (think_pos != std::string::npos) {
        out = out.substr(think_pos + think_close.size());
    }
    const std::string im_end = token_im_end();
    const std::string im_start = "<|im_start|>";
    const std::string markers[] = {im_end, im_start};
    for (const auto &marker : markers) {
        const auto pos = out.find(marker);
        if (pos != std::string::npos) {
            out = out.substr(0, pos);
        }
    }
    while (!out.empty() && (out.back() == '\n' || out.back() == ' ')) {
        out.pop_back();
    }
    return out;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pegasuscorp_orbe_llm_LlamaNative_loadModel(
        JNIEnv *env, jclass, jstring path, jint context_size, jint threads) {
    ensure_backend();
    g_cancel.store(false);

    const char *c_path = env->GetStringUTFChars(path, nullptr);
    if (c_path == nullptr) {
        return JNI_FALSE;
    }
    const std::string model_path(c_path);
    env->ReleaseStringUTFChars(path, c_path);

    free_sampler();
    free_context();
    free_model();

    LOGI("Loading model: %s", model_path.c_str());

    llama_model_params model_params = llama_model_default_params();
    g_state.model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_state.model == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    g_state.n_ctx = context_size > 0 ? context_size : 4096;
    g_state.n_threads = threads > 0 ? threads : 4;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx     = g_state.n_ctx;
    ctx_params.n_batch   = 512;
    ctx_params.n_ubatch  = 512;
    ctx_params.n_threads       = g_state.n_threads;
    ctx_params.n_threads_batch = g_state.n_threads;
    g_state.n_batch = 512;   // exposé pour le chunking du prompt

    g_state.ctx = llama_init_from_model(g_state.model, ctx_params);
    if (g_state.ctx == nullptr) {
        LOGE("Failed to create context");
        free_model();
        return JNI_FALSE;
    }

    g_state.sampler = build_sampler(0.75f, 0.9f);
    LOGI("Model loaded (ctx=%d, threads=%d)", g_state.n_ctx, g_state.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pegasuscorp_orbe_llm_LlamaNative_unloadModel(JNIEnv *, jclass) {
    g_cancel.store(true);
    free_sampler();
    free_context();
    free_model();
    LOGI("Model unloaded");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pegasuscorp_orbe_llm_LlamaNative_generate(
        JNIEnv *env, jclass, jstring prompt, jfloat temperature, jfloat top_p, jint max_tokens) {
    if (g_state.model == nullptr || g_state.ctx == nullptr) {
        return env->NewStringUTF("");
    }
    if (g_cancel.load()) {
        return env->NewStringUTF("");
    }
    g_cancel.store(false);

    const char *prompt_c = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_c == nullptr) {
        return env->NewStringUTF("");
    }
    const std::string prompt_str(prompt_c);
    env->ReleaseStringUTFChars(prompt, prompt_c);
    LOGD("STEP 1: prompt string ready, len=%zu", prompt_str.size());

    free_sampler();
    g_state.sampler = build_sampler(temperature, top_p);
    LOGD("STEP 2: sampler built");

    const llama_vocab *vocab = llama_model_get_vocab(g_state.model);
    LOGD("STEP 3: vocab obtained");

    const int n_prompt = -llama_tokenize(
            vocab, prompt_str.c_str(), prompt_str.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        LOGE("Failed to size prompt tokens");
        return env->NewStringUTF("");
    }
    LOGD("STEP 4: n_prompt=%d", n_prompt);

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt_str.c_str(), prompt_str.size(),
                       prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("");
    }
    LOGD("STEP 5: tokenized OK");

    LOGD("STEP 6: about to clear memory...");
    llama_memory_clear(llama_get_memory(g_state.ctx), true);
    LOGD("STEP 7: memory cleared");

    // Decode le prompt par chunks de n_batch tokens pour éviter le deadlock
    // sur Android ARM quand n_prompt > n_batch.
    const int n_batch = g_state.n_batch > 0 ? g_state.n_batch : 512;
    int n_decoded = 0;
    while (n_decoded < (int)prompt_tokens.size()) {
        int chunk = std::min(n_batch, (int)prompt_tokens.size() - n_decoded);
        LOGD("STEP 8: decoding chunk %d..%d of %d",
             n_decoded, n_decoded + chunk, (int)prompt_tokens.size());
        llama_batch batch_chunk = llama_batch_get_one(
                prompt_tokens.data() + n_decoded, chunk);
        if (llama_model_has_encoder(g_state.model) && n_decoded == 0) {
            if (llama_encode(g_state.ctx, batch_chunk) != 0) {
                LOGE("Failed to encode prompt chunk");
                return env->NewStringUTF("");
            }
            llama_token decoder_start = llama_model_decoder_start_token(g_state.model);
            if (decoder_start == LLAMA_TOKEN_NULL) decoder_start = llama_vocab_bos(vocab);
            llama_batch ds_batch = llama_batch_get_one(&decoder_start, 1);
            if (llama_decode(g_state.ctx, ds_batch) != 0) {
                LOGE("Failed to decode decoder start token");
                return env->NewStringUTF("");
            }
            n_decoded = (int)prompt_tokens.size(); // encoder traite tout
            break;
        }
        auto t0 = std::chrono::steady_clock::now();
        if (llama_decode(g_state.ctx, batch_chunk) != 0) {
            LOGE("Failed to decode prompt chunk at %d", n_decoded);
            return env->NewStringUTF("");
        }
        auto t1 = std::chrono::steady_clock::now();
        long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        LOGD("STEP 9: chunk decoded OK in %lldms", ms);
        n_decoded += chunk;
        LOGD("Prompt decode: %d / %d tokens", n_decoded, (int)prompt_tokens.size());
    }

    std::string result;
    const int limit = max_tokens > 0 ? max_tokens : 200;
    llama_batch token_batch;   // batch réutilisé à chaque token généré

    for (int i = 0; i < limit; ++i) {
        if (g_cancel.load()) {
            break;
        }

        llama_token new_token = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char piece[256];
        const int n = llama_token_to_piece(vocab, new_token, piece, sizeof(piece), 0, true);
        if (n > 0) {
            result.append(piece, static_cast<size_t>(n));
            if (result.find(token_im_end()) != std::string::npos
                || result.find("<|im_start|>") != std::string::npos) {
                break;
            }
        }

        token_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_state.ctx, token_batch) != 0) {
            LOGE("Failed to decode token %d", i);
            break;
        }
    }

    result = strip_chat_artifacts(result);
    LOGI("Generated %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pegasuscorp_orbe_llm_LlamaNative_cancelGeneration(JNIEnv *, jclass) {
    g_cancel.store(true);
}
