package com.pegasuscorp.orbe.tools.orion;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.ApiKeyStore;
import com.pegasuscorp.orbe.diag.Trace;
import com.pegasuscorp.orbe.orion.OrionOllamaClient;
import com.pegasuscorp.orbe.orion.OrionPromptBuilder;
import com.pegasuscorp.orbe.orion.OrionStateStore;
import com.pegasuscorp.orbe.orion.OrionStatus;
import com.pegasuscorp.orbe.orion.OrionStreamCallback;
import com.pegasuscorp.orbe.orion.OrionToolLoop;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Appels code à Orion (qwen3-coder:30b via Ollama sur RunPod).
 * Channel ORION : prompt auto-construit (RAG + projet actif + historique récent).
 */
public final class OrionCodeTool implements Tool {

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    @Override
    public String id() {
        return "orion_code";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.ORION_CODE;
    }

    @Override
    public String description() {
        return "orion_code(prompt:str, context?:str) — "
                + "Envoie un prompt à Orion (qwen3-coder:30b sur RunPod). "
                + "Utilise pour « demande à Orion », « code moi », « génère », "
                + "« écris une fonction », « explique ce code ». "
                + "NE PAS utiliser si Orion est hors ligne — proposer de le démarrer "
                + "via orion_manager start. Injecte contextes chargés + RAG.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        String prompt = params != null ? params.optString("prompt", "").trim() : "";
        if (prompt.isEmpty() && params != null) {
            prompt = params.optString("query", params.optString("text", "")).trim();
        }
        if (TextUtils.isEmpty(prompt)) {
            cb.onError("Dis-moi ce que tu veux que je demande à Orion.");
            return;
        }

        OrionStateStore store = OrionStateStore.get();
        store.attach(ctx);
        OrionStatus status = store.getStatus();
        if (status != OrionStatus.READY && status != OrionStatus.BUSY) {
            cb.onSuccess(ToolResult.text(
                    "Orion est hors ligne. Tu veux que je le lance ?"));
            return;
        }
        if (TextUtils.isEmpty(store.getOllamaUrl())) {
            cb.onSuccess(ToolResult.text(
                    "Orion est hors ligne. Tu veux que je le lance ?"));
            return;
        }

        final String userPrompt = prompt;
        final JSONObject safeParams = params != null ? params : new JSONObject();
        BG.execute(() -> runGenerate(ctx, safeParams, userPrompt, cb));
    }

    private static void runGenerate(Context ctx, JSONObject params, String userPrompt,
            ToolCallback cb) {
        OrionStateStore store = OrionStateStore.get();
        store.attach(ctx);
        store.markBusy();
        long t0 = System.currentTimeMillis();
        AtomicBoolean finished = new AtomicBoolean(false);

        try {
            OrionPromptBuilder.BuiltPrompt built = OrionPromptBuilder.build(ctx, params);
            String ollamaUrl = store.getOllamaUrl();
            String token = ApiKeyStore.getOrionToken(ctx);

            try {
                boolean ok = OrionOllamaClient.ensurePreferredModel(ollamaUrl, token, msg -> {
                    if (cb != null && msg != null) cb.onProgress(msg);
                });
                if (!ok) {
                    if (finished.compareAndSet(false, true)) {
                        store.markReady();
                        cb.onError("Impossible de télécharger " + OrionOllamaClient.MODEL
                                + " sur le pod. Vérifie setup.sh / le réseau RunPod.");
                    }
                    return;
                }
            } catch (Exception pullEx) {
                if (finished.compareAndSet(false, true)) {
                    store.markReady();
                    cb.onError("Pull modèle : " + (pullEx.getMessage() == null
                            ? "échec" : pullEx.getMessage()));
                }
                return;
            }

            StringBuilder streamed = new StringBuilder();
            OrionStreamCallback streamCb = new OrionStreamCallback() {
                @Override
                public void onToken(String tokenPart) {
                    if (tokenPart != null) streamed.append(tokenPart);
                    if (cb != null && tokenPart != null && !tokenPart.isEmpty()) {
                        cb.onProgress(tokenPart);
                    }
                }

                @Override
                public void onComplete(String full) {
                    if (!finished.compareAndSet(false, true)) return;
                    String response = full != null ? full : streamed.toString();
                    long wall = System.currentTimeMillis() - t0;
                    Trace.orionCall(built.prompt.length(), response.length(), wall,
                            built.contextChunksUsed);
                    store.markReady();
                    store.pingActivity();
                    try {
                        com.pegasuscorp.orbe.orion.OrionFileStore.get()
                                .ingestOrionOutput(ctx, response, null);
                    } catch (Exception ignored) {
                    }
                    cb.onSuccess(ToolResult.text(
                            "Orion a généré le code — voir l'onglet Orion.",
                            response));
                }

                @Override
                public void onError(String message) {
                    if (!finished.compareAndSet(false, true)) return;
                    if (store.getStatus() == OrionStatus.BUSY) {
                        store.markReady();
                        store.pingActivity();
                    }
                    cb.onError(message == null ? "Orion n'a pas répondu." : message);
                }
            };

            // Tool calling (/api/chat) pour les demandes code — fallback /api/generate
            boolean wantCode = OrionPromptBuilder.looksLikeCodeRequest(userPrompt)
                    || OrionPromptBuilder.looksLikeGreenfieldRequest(userPrompt);
            if (wantCode) {
                if (cb != null) cb.onProgress("⚙️ Orion (tools)…\n");
                OrionToolLoop.Result toolResult = OrionToolLoop.run(
                        ctx, ollamaUrl, token, built.prompt, streamCb);
                if (toolResult != null && (toolResult.usedTools
                        || !TextUtils.isEmpty(toolResult.assistantText)
                        || !toolResult.writes.isEmpty())) {
                    streamCb.onComplete(toolResult.displayAndIngest());
                    return;
                }
                if (cb != null) cb.onProgress("⚙️ Fallback génération texte…\n");
            }

            OrionOllamaClient.generate(ollamaUrl, token, built.prompt, streamCb);

            // Si le transport a appelé onComplete/onError en sync, finished=true.
            // Si transport oubliait onComplete (tests mal formés), on sécurise.
            if (!finished.get() && streamed.length() > 0) {
                // ne devrait pas arriver avec generateHttp
            }
        } catch (Exception e) {
            if (finished.compareAndSet(false, true)) {
                if (store.getStatus() == OrionStatus.BUSY) {
                    store.markReady();
                }
                cb.onError(e.getMessage() == null ? "Échec Orion" : e.getMessage());
            }
        }
    }
}
