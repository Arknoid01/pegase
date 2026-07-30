package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.diag.Trace;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backend avec fallback automatique : en cas d'erreur (ex. RPM), essaie le
 * modèle suivant du même fournisseur.
 *
 * Groq  : modèle préféré puis 120B si besoin ; suite = Cerebras / OpenRouter
 *         (toujours GPT-OSS 120B via {@link ProviderChain}).
 * Gemini: gemini-2.5-flash → gemini-2.0-flash → gemini-2.0-flash-lite → gemini-1.5-pro
 */
public final class FallbackChatBackend implements ChatBackend {

    private static final String TAG = "FallbackChat";

    private static final String[][] GEMINI_FALLBACK = {
            {CloudModelStore.GEMINI_25_FLASH,    "Gemini 2.5 Flash"},
            {CloudModelStore.GEMINI_FLASH,       "Gemini 2.0 Flash"},
            {CloudModelStore.GEMINI_FLASH_LITE,  "Gemini 2.0 Flash Lite"},
            {CloudModelStore.GEMINI_15_PRO,      "Gemini 1.5 Pro"},
    };

    private final Context appContext;
    private final String forcedProvider;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String lastTraceLabel;
    private static volatile boolean lastCallUsedFallback = false;
    private static volatile Boolean fallbackOverrideForTests = null;

    public FallbackChatBackend(Context context) {
        this(context, null);
    }

    /** Force un fournisseur (ex. {@link CloudModelStore#PROVIDER_GROQ}) — ignore les réglages. */
    public FallbackChatBackend(Context context, String forcedProvider) {
        this.appContext = context.getApplicationContext();
        this.forcedProvider = forcedProvider;
    }

    /**
     * true si le dernier envoi a utilisé un modèle de repli (≠ préféré),
     * ou override de test.
     */
    public static boolean isOnFallbackBackend() {
        if (fallbackOverrideForTests != null) return fallbackOverrideForTests;
        return lastCallUsedFallback;
    }

    /** Visible pour les tests. */
    public static void setOnFallbackBackendForTests(Boolean value) {
        fallbackOverrideForTests = value;
        if (value == null) lastCallUsedFallback = false;
    }

    public static FallbackChatBackend groqOnly(Context context) {
        return new FallbackChatBackend(context, CloudModelStore.PROVIDER_GROQ);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        String label = lastTraceLabel;
        if (label != null) return label;
        String provider = forcedProvider != null
                ? forcedProvider
                : CloudModelStore.getActiveProvider(appContext);
        boolean isGemini = CloudModelStore.PROVIDER_GEMINI.equals(provider);
        String modelId = isGemini
                ? CloudModelStore.getGeminiModelId(appContext)
                : CloudModelStore.getGroqModelId(appContext);
        return providerLabel(isGemini) + "/" + modelId;
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        if (callback instanceof StreamOnReply && !opts.nativeTools) {
            String provider = forcedProvider != null
                    ? forcedProvider
                    : CloudModelStore.getActiveProvider(appContext);
            boolean isGemini = CloudModelStore.PROVIDER_GEMINI.equals(provider);
            String modelId = isGemini
                    ? CloudModelStore.getGeminiModelId(appContext)
                    : CloudModelStore.getGroqModelId(appContext);
            lastTraceLabel = providerLabel(isGemini) + "/" + modelId;
            lastCallUsedFallback = false;
            ChatBackend backend = createBackend(isGemini, modelId);
            if (backend.supportsStreaming()) {
                backend.send(history, userMessage, callback, optsForModel(modelId, opts));
                return;
            }
        }
        io.execute(() -> {
            String provider = forcedProvider != null
                    ? forcedProvider
                    : CloudModelStore.getActiveProvider(appContext);
            boolean isGemini = CloudModelStore.PROVIDER_GEMINI.equals(provider);
            String preferredModel = isGemini
                    ? CloudModelStore.getGeminiModelId(appContext)
                    : CloudModelStore.getGroqModelId(appContext);

            String[][] ordered = isGemini
                    ? reorder(GEMINI_FALLBACK, preferredModel)
                    : CloudModelStore.groqFallbackChain(preferredModel);

            Exception lastError = null;
            for (int i = 0; i < ordered.length; i++) {
                String[] entry = ordered[i];
                String modelId = entry[0];
                String modelName = entry[1];
                try {
                    Log.d(TAG, "Essai modèle : " + modelName);
                    ChatBackend backend = createBackend(isGemini, modelId);
                    LlmReply reply = sendSync(backend, history, userMessage,
                            optsForModel(modelId, opts));
                    if (reply != null && (reply.hasNativeToolCalls()
                            || (reply.content != null && !reply.content.isEmpty()))) {
                        lastTraceLabel = providerLabel(isGemini) + "/" + modelId;
                        lastCallUsedFallback = !modelId.equals(preferredModel);
                        if (lastCallUsedFallback) {
                            Log.i(TAG, "Fallback réussi sur : " + modelName);
                        }
                        main.post(() -> callback.onLlmReply(reply));
                        return;
                    }
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Modèle " + modelName + " échoué : " + e.getMessage());
                    if (i + 1 < ordered.length) {
                        String from = providerLabel(isGemini) + "/" + modelId;
                        String to = providerLabel(isGemini) + "/" + ordered[i + 1][0];
                        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        Trace.error("fallback", from + " → " + to + " : " + reason);
                        if (ChatSpokenErrors.isRateLimit(e.getMessage())) {
                            Log.w(TAG, "Rate limit — essai du modèle de repli suivant");
                        }
                    }
                }
            }

            String errMsg = lastError != null
                    ? ChatSpokenErrors.toUserMessage(lastError.getMessage())
                    : ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
            if (lastError == null
                    || ChatSpokenErrors.isAllModelsFailed(lastError.getMessage())
                    || "Tous les modèles ont échoué.".equals(errMsg)) {
                errMsg = ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
            }
            final String spoken = errMsg;
            Log.e(TAG, "Tous les modèles épuisés : " + spoken);
            main.post(() -> callback.onError(spoken));
        });
    }

    private static ChatSendOptions optsForModel(String modelId, ChatSendOptions opts) {
        if (opts == null) return ChatSendOptions.legacy();
        if (!opts.nativeTools) return opts;
        if (CloudModelStore.isGroqToolModel(modelId)) return opts;
        return opts.withoutNativeTools();
    }

    private static String providerLabel(boolean isGemini) {
        return isGemini ? CloudModelStore.PROVIDER_GEMINI : CloudModelStore.PROVIDER_GROQ;
    }

    private ChatBackend createBackend(boolean isGemini, String modelId) {
        if (isGemini) {
            return new GeminiChatBackend(appContext,
                    ApiKeyStore.getGeminiKey(appContext), modelId);
        }
        return new GroqChatBackend(appContext,
                ApiKeyStore.getGroqKey(appContext), modelId);
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        String provider = forcedProvider != null
                ? forcedProvider
                : CloudModelStore.getActiveProvider(appContext);
        boolean isGemini = CloudModelStore.PROVIDER_GEMINI.equals(provider);
        if (isGemini) {
            ChatBackend.super.sendAgenticContinuation(chain, options, callback);
            return;
        }
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        String preferred = CloudModelStore.getGroqModelId(appContext);
        String[][] ordered = CloudModelStore.groqFallbackChain(preferred);
        io.execute(() -> {
            Exception lastError = null;
            for (int i = 0; i < ordered.length; i++) {
                String modelId = ordered[i][0];
                String modelName = ordered[i][1];
                try {
                    Log.d(TAG, "Agentic essai : " + modelName);
                    final LlmReply[] result = {null};
                    final Exception[] error = {null};
                    final Object lock = new Object();
                    createBackend(false, modelId).sendAgenticContinuation(chain,
                            optsForModel(modelId, opts), new OnReply() {
                                @Override public void onLlmReply(LlmReply reply) {
                                    synchronized (lock) { result[0] = reply; lock.notifyAll(); }
                                }
                                @Override public void onReply(String text) {
                                    synchronized (lock) {
                                        result[0] = LlmReply.text(text);
                                        lock.notifyAll();
                                    }
                                }
                                @Override public void onError(String e) {
                                    synchronized (lock) {
                                        error[0] = new RuntimeException(e);
                                        lock.notifyAll();
                                    }
                                }
                            });
                    synchronized (lock) {
                        long deadline = System.currentTimeMillis() + 70_000;
                        while (result[0] == null && error[0] == null) {
                            long remaining = deadline - System.currentTimeMillis();
                            if (remaining <= 0) throw new RuntimeException("Timeout modèle");
                            lock.wait(remaining);
                        }
                    }
                    if (error[0] != null) throw error[0];
                    lastTraceLabel = providerLabel(false) + "/" + modelId;
                    lastCallUsedFallback = !modelId.equals(preferred);
                    if (lastCallUsedFallback) {
                        Log.i(TAG, "Agentic fallback réussi sur : " + modelName);
                    }
                    final LlmReply reply = result[0];
                    main.post(() -> {
                        if (reply != null && reply.hasNativeToolCalls()) {
                            callback.onLlmReply(reply);
                        } else {
                            callback.onReply(reply != null && reply.content != null
                                    ? reply.content : "");
                        }
                    });
                    return;
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Agentic " + modelName + " échoué : " + e.getMessage());
                    if (i + 1 < ordered.length) {
                        Trace.error("fallback",
                                "Groq/" + modelId + " → Groq/" + ordered[i + 1][0]
                                        + " : " + (e.getMessage() != null
                                        ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }
            }
            String errMsg = lastError != null
                    ? ChatSpokenErrors.toUserMessage(lastError.getMessage())
                    : ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
            final String spoken = errMsg;
            main.post(() -> callback.onError(spoken));
        });
    }

    @Override
    public void sendAgenticContinuation(AgenticContinuation continuation, OnReply callback) {
        sendAgenticContinuation(new AgenticChain(continuation),
                ChatSendOptions.agenticStep(ChatSendOptions.legacy().allowedTools, false),
                callback);
    }

    /** Appel synchrone — à exécuter sur le thread IO uniquement. */
    private LlmReply sendSync(ChatBackend backend, List<Turn> history, String userMessage,
            ChatSendOptions options) throws Exception {
        final LlmReply[] result = {null};
        final Exception[] error = {null};
        final Object lock = new Object();

        backend.send(history, userMessage, new OnReply() {
            @Override public void onLlmReply(LlmReply reply) {
                synchronized (lock) { result[0] = reply; lock.notifyAll(); }
            }
            @Override public void onReply(String text) {
                synchronized (lock) { result[0] = LlmReply.text(text); lock.notifyAll(); }
            }
            @Override public void onError(String e) {
                synchronized (lock) { error[0] = new RuntimeException(e); lock.notifyAll(); }
            }
        }, options);

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 70_000;
            while (result[0] == null && error[0] == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new RuntimeException("Timeout modèle");
                lock.wait(remaining);
            }
        }
        if (error[0] != null) throw error[0];
        return result[0];
    }

    /** Place le modèle préféré en tête de liste, garde l'ordre des autres. */
    private static String[][] reorder(String[][] list, String preferredId) {
        String[][] ordered = new String[list.length][];
        int idx = 0;
        for (String[] entry : list) {
            if (entry[0].equals(preferredId)) { ordered[idx++] = entry; break; }
        }
        for (String[] entry : list) {
            if (!entry[0].equals(preferredId)) ordered[idx++] = entry;
        }
        return ordered;
    }
}
