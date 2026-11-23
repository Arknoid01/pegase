// Passerelle JNI vers LiteRT-LM (liblitert-lm.so, livré dans jniLibs).
// La bibliothèque est chargée par dlopen et ses fonctions sont récupérées par leur nom :
// la liaison directe n'est pas nécessaire et le binaire reste petit.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstddef>
#include <mutex>
#include <string>

#define LLOGI(...) __android_log_print(ANDROID_LOG_INFO, "PegaseLiteRT", __VA_ARGS__)
#define LLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PegaseLiteRT", __VA_ARGS__)

namespace {

typedef int (*f_settings_create)(const char*, const char*, const char*, const char*, void**);
typedef int (*f_set_int)(void*, int);
typedef int (*f_set_str)(void*, const char*);
typedef void (*f_del)(void*);
typedef int (*f_create)(const void*, void**);
typedef int (*f_conv_config_create)(void**);
typedef int (*f_conv_set_str)(void*, const char*);
typedef int (*f_conv_create)(void*, void*, void**);
typedef int (*f_opt_create)(void**);
typedef int (*f_opt_set_int)(void*, int);
typedef int (*f_send)(void*, const char*, const char*, const void*, void**);
typedef int (*f_json_get)(const void*, const char**);
typedef int (*f_set_constraint)(void*, int, const char*);
struct StreamChunk;
typedef void (*f_stream_cb)(void*, const StreamChunk*);
typedef int (*f_send_stream)(void*, const char*, const char*, const void*, f_stream_cb, void*);
typedef int (*f_chunk_text)(const StreamChunk*, const char**);
typedef int (*f_chunk_final)(const StreamChunk*, bool*);
typedef int (*f_chunk_error)(const StreamChunk*, const char**);

struct Api {
    void* lib = nullptr;
    f_settings_create settings_create = nullptr;
    f_set_int set_max_tokens = nullptr;
    f_set_str set_cache = nullptr;
    f_del settings_delete = nullptr;
    f_create engine_create = nullptr;
    f_del engine_delete = nullptr;
    f_conv_config_create cc_create = nullptr;
    f_conv_set_str cc_set_system = nullptr;
    f_conv_set_str cc_set_messages = nullptr;
    f_del cc_delete = nullptr;
    f_conv_create conv_create = nullptr;
    f_del conv_delete = nullptr;
    f_opt_create opt_create = nullptr;
    f_opt_set_int opt_set_max = nullptr;
    f_del opt_delete = nullptr;
    f_send send = nullptr;
    f_json_get json_get = nullptr;
    f_del json_delete = nullptr;
    f_set_constraint set_constraint = nullptr;
    f_send_stream send_stream = nullptr;
    f_chunk_text chunk_text = nullptr;
    f_chunk_final chunk_final = nullptr;
    f_chunk_error chunk_error = nullptr;
};

std::mutex g_mutex;          // une seule génération à la fois
std::string g_last_error;
Api g_api;

template <typename T>
bool bind(void* lib, T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(lib, name));
    if (!out) g_last_error = std::string("symbole manquant : ") + name;
    return out != nullptr;
}

bool ensure_api() {
    if (g_api.lib) return true;
    g_api.lib = dlopen("liblitert-lm.so", RTLD_NOW);
    if (!g_api.lib) {
        g_last_error = std::string("dlopen : ") + (dlerror() ? dlerror() : "?");
        LLOGE("%s", g_last_error.c_str());
        return false;
    }
    Api& a = g_api;
    void* l = a.lib;
    bool ok = bind(l, a.settings_create, "litert_lm_engine_settings_create")
        && bind(l, a.set_max_tokens, "litert_lm_engine_settings_set_max_num_tokens")
        && bind(l, a.set_cache, "litert_lm_engine_settings_set_cache_dir")
        && bind(l, a.settings_delete, "litert_lm_engine_settings_delete")
        && bind(l, a.engine_create, "litert_lm_engine_create")
        && bind(l, a.engine_delete, "litert_lm_engine_delete")
        && bind(l, a.cc_create, "litert_lm_conversation_config_create")
        && bind(l, a.cc_set_system, "litert_lm_conversation_config_set_system_message")
        && bind(l, a.cc_set_messages, "litert_lm_conversation_config_set_messages")
        && bind(l, a.cc_delete, "litert_lm_conversation_config_delete")
        && bind(l, a.conv_create, "litert_lm_conversation_create")
        && bind(l, a.conv_delete, "litert_lm_conversation_delete")
        && bind(l, a.opt_create, "litert_lm_conversation_optional_args_create")
        && bind(l, a.opt_set_max, "litert_lm_conversation_optional_args_set_max_output_tokens")
        && bind(l, a.opt_delete, "litert_lm_conversation_optional_args_delete")
        && bind(l, a.send, "litert_lm_conversation_send_message")
        && bind(l, a.json_get, "litert_lm_json_response_get_string")
        && bind(l, a.json_delete, "litert_lm_json_response_delete")
        && bind(l, a.set_constraint, "litert_lm_conversation_optional_args_set_constraint")
        && bind(l, a.send_stream, "litert_lm_conversation_send_message_stream")
        && bind(l, a.chunk_text, "litert_lm_stream_chunk_get_text")
        && bind(l, a.chunk_final, "litert_lm_stream_chunk_is_final")
        && bind(l, a.chunk_error, "litert_lm_stream_chunk_get_error");
    if (!ok) LLOGE("%s", g_last_error.c_str());
    return ok;
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Flux : la conversation et les options vivent jusqu'à nativeStreamFinish.
struct StreamState {
    void* conv = nullptr;
    void* opt = nullptr;
    std::mutex m;
    std::string text;
    std::string error;
    bool done = false;
};

// Extrait les champs "text" d'un morceau JSON (ex. {"role":"assistant","content":[{"type":"text","text":"Le"}]}).
// Si le morceau n'est pas du JSON, il est gardé tel quel.
std::string extract_text(const std::string& raw) {
    static const std::string key = "\"text\":\"";
    if (raw.find(key) == std::string::npos) return raw;
    std::string out;
    size_t pos = 0;
    while ((pos = raw.find(key, pos)) != std::string::npos) {
        pos += key.size();
        while (pos < raw.size() && raw[pos] != '"') {
            char c = raw[pos];
            if (c == '\\' && pos + 1 < raw.size()) {
                char e = raw[pos + 1];
                pos += 2;
                switch (e) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case 'u': {
                        unsigned code = 0;
                        if (pos + 4 <= raw.size()) code = std::stoul(raw.substr(pos, 4), nullptr, 16);
                        pos += 4;
                        if (code < 0x80) out += static_cast<char>(code);
                        else if (code < 0x800) {
                            out += static_cast<char>(0xC0 | (code >> 6));
                            out += static_cast<char>(0x80 | (code & 0x3F));
                        } else {
                            out += static_cast<char>(0xE0 | (code >> 12));
                            out += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
                            out += static_cast<char>(0x80 | (code & 0x3F));
                        }
                        break;
                    }
                    default: out += e; break;
                }
            } else {
                out += c;
                pos++;
            }
        }
    }
    return out;
}

void on_chunk(void* data, const StreamChunk* chunk) {
    StreamState* st = static_cast<StreamState*>(data);
    const char* t = nullptr;
    bool fin = false;
    const char* err = nullptr;
    std::lock_guard<std::mutex> lock(st->m);
    if (g_api.chunk_error && g_api.chunk_error(chunk, &err) == 0 && err && *err) st->error = err;
    if (g_api.chunk_text && g_api.chunk_text(chunk, &t) == 0 && t) st->text += extract_text(t);
    if (g_api.chunk_final && g_api.chunk_final(chunk, &fin) == 0 && fin) st->done = true;
}

}  // namespace

extern "C" {

// Retourne un handle (0 en cas d'échec ; le message est dans nativeLastError).
JNIEXPORT jlong JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeLoad(JNIEnv* env, jclass, jstring model,
                                                      jstring cacheDir, jstring backend) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensure_api()) return 0;
    std::string m = jstr(env, model), cache = jstr(env, cacheDir), be = jstr(env, backend);
    void* settings = nullptr;
    if (g_api.settings_create(m.c_str(), be.c_str(), nullptr, nullptr, &settings) != 0) {
        g_last_error = "settings_create a échoué";
        return 0;
    }
    g_api.set_max_tokens(settings, 4096);
    if (!cache.empty()) g_api.set_cache(settings, cache.c_str());
    void* engine = nullptr;
    int st = g_api.engine_create(settings, &engine);
    g_api.settings_delete(settings);
    if (st != 0 || !engine) {
        g_last_error = "engine_create a échoué (code " + std::to_string(st) + ")";
        LLOGE("%s", g_last_error.c_str());
        return 0;
    }
    LLOGI("moteur LiteRT chargé (%s)", be.c_str());
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeUnload(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (handle && g_api.engine_delete) g_api.engine_delete(reinterpret_cast<void*>(handle));
}

// systemJson : chaîne JSON du texte système ; historyJson : tableau JSON des messages précédents ;
// userJson : objet JSON du message courant. Retourne la réponse JSON brute, ou null.
JNIEXPORT jstring JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeGenerate(JNIEnv* env, jclass, jlong handle,
                                                          jstring systemJson, jstring historyJson,
                                                          jstring userJson, jint maxOutput,
                                                          jstring constraintRegex) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!handle || !ensure_api()) return nullptr;
    std::string sys = jstr(env, systemJson), hist = jstr(env, historyJson), user = jstr(env, userJson);
    std::string regex = jstr(env, constraintRegex);
    Api& a = g_api;

    void* cc = nullptr;
    a.cc_create(&cc);
    a.cc_set_system(cc, sys.c_str());
    a.cc_set_messages(cc, hist.c_str());
    void* conv = nullptr;
    int st = a.conv_create(reinterpret_cast<void*>(handle), cc, &conv);
    a.cc_delete(cc);
    if (st != 0 || !conv) {
        g_last_error = "conversation_create a échoué (code " + std::to_string(st) + ")";
        return nullptr;
    }

    void* opt = nullptr;
    a.opt_create(&opt);
    a.opt_set_max(opt, maxOutput);
    if (!regex.empty()) {
        // kLiteRtLmConstraintTypeRegex = 1
        int cs = a.set_constraint(opt, 1, regex.c_str());
        if (cs != 0) LLOGE("set_constraint a échoué (code %d)", cs);
    }
    void* resp = nullptr;
    st = a.send(conv, user.c_str(), nullptr, opt, &resp);
    a.opt_delete(opt);
    jstring result = nullptr;
    if (st == 0 && resp) {
        const char* json = nullptr;
        a.json_get(resp, &json);
        if (json) result = env->NewStringUTF(json);
        a.json_delete(resp);
    } else {
        g_last_error = "send_message a échoué (code " + std::to_string(st) + ")";
        LLOGE("%s", g_last_error.c_str());
    }
    a.conv_delete(conv);
    return result;
}

// Démarre une génération en flux. Retourne un identifiant de flux (0 en cas d'échec).
JNIEXPORT jlong JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeStreamStart(JNIEnv* env, jclass, jlong handle,
        jstring systemJson, jstring historyJson, jstring userJson, jint maxOutput,
        jstring constraintRegex) {
    if (!handle || !ensure_api()) return 0;
    std::string sys = jstr(env, systemJson), hist = jstr(env, historyJson), user = jstr(env, userJson);
    std::string regex = jstr(env, constraintRegex);
    Api& a = g_api;
    void* cc = nullptr;
    a.cc_create(&cc);
    a.cc_set_system(cc, sys.c_str());
    a.cc_set_messages(cc, hist.c_str());
    void* conv = nullptr;
    int st = a.conv_create(reinterpret_cast<void*>(handle), cc, &conv);
    a.cc_delete(cc);
    if (st != 0 || !conv) {
        g_last_error = "conversation_create a échoué (code " + std::to_string(st) + ")";
        return 0;
    }
    StreamState* state = new StreamState();
    state->conv = conv;
    a.opt_create(&state->opt);
    a.opt_set_max(state->opt, maxOutput);
    if (!regex.empty()) a.set_constraint(state->opt, 1, regex.c_str());
    st = a.send_stream(conv, user.c_str(), nullptr, state->opt, on_chunk, state);
    if (st != 0) {
        g_last_error = "send_message_stream a échoué (code " + std::to_string(st) + ")";
        LLOGE("%s", g_last_error.c_str());
        a.opt_delete(state->opt);
        a.conv_delete(conv);
        delete state;
        return 0;
    }
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jstring JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeStreamText(JNIEnv* env, jclass, jlong id) {
    StreamState* st = reinterpret_cast<StreamState*>(id);
    if (!st) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(st->m);
    return env->NewStringUTF(st->text.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeStreamDone(JNIEnv*, jclass, jlong id) {
    StreamState* st = reinterpret_cast<StreamState*>(id);
    if (!st) return JNI_TRUE;
    std::lock_guard<std::mutex> lock(st->m);
    return st->done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeStreamError(JNIEnv* env, jclass, jlong id) {
    StreamState* st = reinterpret_cast<StreamState*>(id);
    if (!st) return nullptr;
    std::lock_guard<std::mutex> lock(st->m);
    return st->error.empty() ? nullptr : env->NewStringUTF(st->error.c_str());
}

// Libère le flux. Ne pas l'appeler avant que done soit vrai ou qu'une erreur soit signalée.
JNIEXPORT void JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeStreamFinish(JNIEnv*, jclass, jlong id) {
    StreamState* st = reinterpret_cast<StreamState*>(id);
    if (!st) return;
    std::lock_guard<std::mutex> g(g_mutex);
    if (g_api.conv_delete) g_api.conv_delete(st->conv);
    if (g_api.opt_delete && st->opt) g_api.opt_delete(st->opt);
    delete st;
}

JNIEXPORT jstring JNICALL
Java_com_pegasuscorp_orbe_llm_LiteRtNative_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
