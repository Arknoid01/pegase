package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.session.Channel;
import com.pegasuscorp.orbe.session.PegaseSession;
import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Rotation Groq → Cerebras → OpenRouter (Gemini exclu).
 * Timeout agressif + cool-down santé.
 */
public final class MultiProviderBackend implements ChatBackend, ProviderTraceSink {

    private static final String TAG = "MultiProvider";

    private final Context appContext;
    private final ProviderHealthTracker health;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String lastTraceLabel;
    private final Object pendingTraceLock = new Object();
    private String pendingProviderId;
    private String pendingModelId;
    private long pendingLatencyMs;

    private static volatile boolean lastCallUsedFallback = false;
    private static volatile Boolean fallbackOverrideForTests = null;

    public MultiProviderBackend(Context context) {
        this(context, new ProviderHealthTracker());
    }

    public MultiProviderBackend(Context context, ProviderHealthTracker health) {
        this.appContext = context.getApplicationContext();
        this.health = health != null ? health : new ProviderHealthTracker();
    }

    public static boolean isOnFallbackBackend() {
        if (fallbackOverrideForTests != null) return fallbackOverrideForTests;
        return lastCallUsedFallback;
    }

    public static void setOnFallbackBackendForTests(Boolean value) {
        fallbackOverrideForTests = value;
        if (value == null) lastCallUsedFallback = false;
    }

    private void stageProviderTrace(String providerId, String modelId, long latencyMs) {
        synchronized (pendingTraceLock) {
            pendingProviderId = providerId;
            pendingModelId = modelId;
            pendingLatencyMs = latencyMs;
        }
    }

    @Override
    public void consumePendingProviderTrace() {
        String provider;
        String model;
        long latency;
        synchronized (pendingTraceLock) {
            provider = pendingProviderId;
            model = pendingModelId;
            latency = pendingLatencyMs;
            pendingProviderId = null;
            pendingModelId = null;
            pendingLatencyMs = 0L;
        }
        if (provider != null && model != null) {
            Trace.providerUsed(provider, model, latency);
        }
    }

    @Override
    public void discardPendingProviderTrace() {
        synchronized (pendingTraceLock) {
            pendingProviderId = null;
            pendingModelId = null;
            pendingLatencyMs = 0L;
        }
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        String label = lastTraceLabel;
        if (label != null) return label;
        return "multi/" + CloudModelStore.getGroqModelId(appContext);
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        boolean agenticOrBureau = opts.agenticStep || opts.channel == Channel.BUREAU
                || opts.channel == Channel.ORION;

        // Streaming : premier provider sain uniquement (pas de bascule mid-stream)
        if (callback instanceof StreamOnReply && !opts.nativeTools) {
            List<LlmProvider> chain = ProviderChain.build(appContext, agenticOrBureau);
            for (LlmProvider p : chain) {
                if (health.isUnhealthy(p)) continue;
                lastTraceLabel = p.displayName + "/" + p.modelId;
                lastCallUsedFallback = false;
                OpenAiCompatibleChatBackend backend =
                        new OpenAiCompatibleChatBackend(appContext, p, null, 1);
                backend.send(history, userMessage, callback, optsFor(p, p.modelId, opts));
                return;
            }
            callback.onError(ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE);
            return;
        }

        io.execute(() -> {
            Exception lastError = null;
            String lastProvider = null;
            List<LlmProvider> chain = ProviderChain.build(appContext, agenticOrBureau);
            if (chain.isEmpty()) {
                main.post(() -> callback.onError(
                        "Aucune clé LLM. Ajoute Groq, Cerebras ou OpenRouter dans Réglages."));
                return;
            }

            boolean firstAttempt = true;
            boolean toolsExpandedOnce = false;
            ChatSendOptions currentOpts = opts;
            for (LlmProvider p : chain) {
                if (health.isUnhealthy(p)) {
                    Log.d(TAG, "Skip unhealthy : " + p.id);
                    continue;
                }
                String[][] models = ProviderChain.modelsFor(p);
                boolean providerFailedHard = false;
                for (int i = 0; i < models.length; i++) {
                    String modelId = models[i][0];
                    String modelName = models[i][1];
                    long t0 = System.currentTimeMillis();
                    try {
                        Log.d(TAG, "Essai " + p.id + " / " + modelName);
                        LlmProvider attempt = withModel(p, modelId);
                        OpenAiCompatibleChatBackend backend =
                                new OpenAiCompatibleChatBackend(appContext, attempt, modelId, 0);
                        LlmReply reply = backend.sendBlocking(history, userMessage,
                                optsFor(p, modelId, currentOpts));
                        if (reply != null && (reply.hasNativeToolCalls()
                                || (reply.content != null && !reply.content.isEmpty()))) {
                            long latency = System.currentTimeMillis() - t0;
                            health.markSuccess(p);
                            lastTraceLabel = p.displayName + "/" + modelId;
                            lastCallUsedFallback = !firstAttempt
                                    || !modelId.equals(chain.get(0).modelId);
                            stageProviderTrace(p.id, modelId, latency);
                            main.post(() -> callback.onLlmReply(reply));
                            return;
                        }
                    } catch (LlmRateLimitException e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        long latency = System.currentTimeMillis() - t0;
                        health.markRateLimit(p, e.retryAfterMs);
                        Trace.providerRateLimit(p.id, e.retryAfterMs);
                        Trace.error("fallback", p.id + "/" + modelId + " → next : 429");
                        Log.w(TAG, "429 " + p.id + " after " + latency + "ms");
                        providerFailedHard = true;
                        break;
                    } catch (TimeoutException e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        health.markTimeout(p);
                        Trace.providerTimeout(p.id, p.readTimeoutMs);
                        Trace.error("fallback", p.id + "/" + modelId + " → next : timeout");
                        Log.w(TAG, "Timeout " + p.id + "/" + modelName);
                        // essai modèle suivant Groq, sinon provider suivant
                        if (i + 1 >= models.length) providerFailedHard = true;
                    } catch (Exception e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        Log.w(TAG, p.id + "/" + modelName + " échoué : " + e.getMessage());
                        // Même provider/modèle : élargir tools une fois si outil inventé
                        if (!toolsExpandedOnce) {
                            ChatSendOptions expanded = expandToolsForMissingCall(
                                    currentOpts, e.getMessage());
                            if (expanded != null) {
                                toolsExpandedOnce = true;
                                currentOpts = expanded;
                                Trace.error("tool_expand_retry",
                                        p.id + "/" + modelId + " : "
                                                + ChatSpokenErrors.parseMissingToolName(
                                                        e.getMessage()));
                                Log.i(TAG, "Retry avec tools élargis après : "
                                        + e.getMessage());
                                i--; // rejouer le même modèle
                                continue;
                            }
                        }
                        Trace.error("fallback", p.id + "/" + modelId + " → next : "
                                + (e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName()));
                        if (i + 1 >= models.length) {
                            health.markError(p);
                            providerFailedHard = true;
                        }
                    }
                    firstAttempt = false;
                }
                if (!providerFailedHard) firstAttempt = false;
            }

            String errMsg;
            if (lastError == null) {
                errMsg = ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
            } else {
                errMsg = ChatSpokenErrors.toUserMessage(
                        lastProvider, lastError.getMessage());
                if (ChatSpokenErrors.isAllModelsFailed(lastError.getMessage())
                        || ChatSpokenErrors.isAllModelsFailed(errMsg)) {
                    errMsg = ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
                }
            }
            final String spoken = errMsg;
            Log.e(TAG, "Tous les providers épuisés : " + spoken);
            main.post(() -> callback.onError(spoken));
        });
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        ChatSendOptions opts = options != null ? options : ChatSendOptions.legacy();
        io.execute(() -> {
            Exception lastError = null;
            String lastProvider = null;
            List<LlmProvider> chainProviders = ProviderChain.build(appContext, true);
            boolean firstAttempt = true;
            boolean toolsExpandedOnce = false;
            ChatSendOptions currentOpts = opts;
            for (LlmProvider p : chainProviders) {
                if (health.isUnhealthy(p)) continue;
                String[][] models = ProviderChain.modelsFor(p);
                for (int i = 0; i < models.length; i++) {
                    String modelId = models[i][0];
                    long t0 = System.currentTimeMillis();
                    try {
                        LlmProvider attempt = withModel(p, modelId);
                        OpenAiCompatibleChatBackend backend =
                                new OpenAiCompatibleChatBackend(appContext, attempt, modelId, 0);
                        LlmReply reply = backend.sendAgenticBlocking(chain,
                                optsFor(p, modelId, currentOpts));
                        long latency = System.currentTimeMillis() - t0;
                        health.markSuccess(p);
                        lastTraceLabel = p.displayName + "/" + modelId;
                        lastCallUsedFallback = !firstAttempt;
                        stageProviderTrace(p.id, modelId, latency);
                        main.post(() -> {
                            if (reply != null && reply.hasNativeToolCalls()) {
                                callback.onLlmReply(reply);
                            } else {
                                callback.onReply(reply != null && reply.content != null
                                        ? reply.content : "");
                            }
                        });
                        return;
                    } catch (LlmRateLimitException e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        health.markRateLimit(p, e.retryAfterMs);
                        Trace.providerRateLimit(p.id, e.retryAfterMs);
                        break;
                    } catch (TimeoutException e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        health.markTimeout(p);
                        Trace.providerTimeout(p.id, p.readTimeoutMs);
                        if (i + 1 >= models.length) break;
                    } catch (Exception e) {
                        lastError = e;
                        lastProvider = p.displayName;
                        String errMsg = e.getMessage();
                        // Trace le 400 même si Cerebras/OpenRouter rattrape ensuite
                        if (PegaseSession.isHttp400ToolValidation(errMsg)) {
                            Trace.toolFailureContext(
                                    "llm",
                                    "http_400_tool_validation",
                                    p.id + "/" + modelId + " : "
                                            + PegaseSession.summarizeToolValidationError(errMsg),
                                    null);
                        }
                        if (!toolsExpandedOnce) {
                            ChatSendOptions expanded = expandToolsForMissingCall(
                                    currentOpts, errMsg);
                            if (expanded != null) {
                                toolsExpandedOnce = true;
                                currentOpts = expanded;
                                Trace.error("tool_expand_retry",
                                        p.id + "/" + modelId + " : "
                                                + ChatSpokenErrors.parseMissingToolName(
                                                        errMsg));
                                i--;
                                continue;
                            }
                        }
                        if (i + 1 >= models.length) {
                            health.markError(p);
                            break;
                        }
                    }
                    firstAttempt = false;
                }
                firstAttempt = false;
            }
            String spoken = lastError != null
                    ? ChatSpokenErrors.toUserMessage(lastProvider, lastError.getMessage())
                    : ChatSpokenErrors.ALL_MODELS_FAILED_USER_MESSAGE;
            main.post(() -> callback.onError(spoken));
        });
    }

    /**
     * Si le modèle a appelé un outil connu absent du filtre du tour,
     * élargit {@code allowedTools} une fois (même provider). Synthèse agentique
     * ({@code allowMoreTools=false}) : pas d'élargissement.
     */
    static ChatSendOptions expandToolsForMissingCall(ChatSendOptions opts, String error) {
        if (opts == null || error == null) return null;
        boolean toolsInRequest = opts.nativeTools
                || (opts.agenticStep && opts.allowMoreTools);
        if (!toolsInRequest) return null;
        if (!ChatSpokenErrors.isToolChoiceConflict(error)) return null;
        // tool_choice=none (fin de boucle) — pas un filtre trop étroit
        if (error.toLowerCase().contains("tool choice is none")) return null;

        EnumSet<ToolTag> current = EnumSet.copyOf(opts.allowedTools);
        if (current.size() >= ToolTag.values().length) return null;

        String missing = ChatSpokenErrors.parseMissingToolName(error);
        EnumSet<ToolTag> expanded = EnumSet.copyOf(current);
        if (missing != null && !missing.isEmpty()) {
            Tool t = new ToolRegistry().findById(missing);
            if (t == null) return null; // outil inventé hors catalogue
            expanded.add(t.tag());
        } else {
            expanded = EnumSet.allOf(ToolTag.class);
        }
        if (expanded.equals(current)) return null;
        return opts.withAllowedTools(expanded);
    }

    private static ChatSendOptions optsFor(LlmProvider p, String modelId, ChatSendOptions opts) {
        if (opts == null) return ChatSendOptions.legacy();
        if (!opts.nativeTools) return opts;
        if (p.nativeFc && CloudModelStore.isToolCapableModel(modelId)) return opts;
        return opts.withoutNativeTools();
    }

    private static LlmProvider withModel(LlmProvider base, String modelId) {
        return new LlmProvider(
                base.id, base.displayName, modelId, base.apiKey, base.chatCompletionsUrl,
                base.nativeFc, base.priority, base.connectTimeoutMs, base.readTimeoutMs,
                base.extraHeaders);
    }
}
