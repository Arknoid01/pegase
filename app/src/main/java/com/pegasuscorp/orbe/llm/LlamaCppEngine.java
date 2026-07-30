package com.pegasuscorp.orbe.llm;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implémentation LocalLlmEngine via llama.cpp (JNI).
 */
public class LlamaCppEngine implements LocalLlmEngine {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean generating = new AtomicBoolean(false);

    private ModelConfig loadedConfig;

    @Override
    public boolean isModelLoaded() {
        return loadedConfig != null;
    }

    @Override
    public void loadModel(ModelConfig config, LoadCallback callback) {
        io.execute(() -> {
            try {
                if (!LlamaNative.isAvailable()) {
                    String detail = LlamaNative.getLoadError();
                    postError(callback, "Moteur natif non disponible : "
                            + (detail != null ? detail : "pegase_llm introuvable"));
                    return;
                }
                File file = new File(config.path);
                if (!file.exists()) {
                    postError(callback, "Fichier modèle introuvable : " + config.path);
                    return;
                }
                unloadInternal();
                boolean ok = LlamaNative.loadModel(config.path, config.contextSize, config.threads);
                if (!ok) {
                    postError(callback, "Échec du chargement du modèle GGUF.");
                    return;
                }
                loadedConfig = config;
                main.post(callback::onLoaded);
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    @Override
    public void generate(List<ChatMessage> messages, GenerationSettings settings,
                         GenerationListener listener) {
        if (loadedConfig == null) {
            main.post(() -> listener.onError("Aucun modèle chargé."));
            return;
        }
        generating.set(true);
        io.execute(() -> {
            try {
                String prompt = PromptFormatter.format(messages);

                // Timeout 45 s : si le natif ne répond pas, on annule et on prévient.
                final String[] result = {null};
                final String[] error  = {null};
                Thread genThread = new Thread(() -> {
                    try {
                        result[0] = LlamaNative.generate(
                                prompt, settings.temperature,
                                settings.topP, settings.maxTokens);
                    } catch (Exception e) {
                        error[0] = e.getMessage();
                    }
                });
                genThread.start();
                genThread.join(300_000);   // 5 min : prefill froid ~2min + génération
                if (genThread.isAlive()) {
                    LlamaNative.cancelGeneration();
                    genThread.join(3_000);
                    main.post(() -> listener.onError("Trop long — réessaie."));
                    return;
                }
                if (error[0] != null) throw new RuntimeException(error[0]);
                if (!generating.get()) return;
                String raw     = result[0];
                String cleaned = PegasePrompt.sanitizeForSpeech(raw);
                if (settings.streaming && cleaned != null && !cleaned.isEmpty()) {
                    streamFakeTokens(listener, cleaned);
                } else {
                    main.post(() -> listener.onComplete(cleaned));
                }
            } catch (Exception e) {
                if (generating.get()) {
                    main.post(() -> listener.onError(e.getMessage()));
                }
            } finally {
                generating.set(false);
            }
        });
    }

    /** En attendant le streaming token-par-token natif, on émet par mots. */
    private void streamFakeTokens(GenerationListener listener, String text) {
        String[] words = text.split("(?<=\\s)");
        StringBuilder acc = new StringBuilder();
        for (String word : words) {
            if (!generating.get()) return;
            acc.append(word);
            String partial = acc.toString();
            main.post(() -> listener.onToken(word));
            try {
                Thread.sleep(35);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        String finalText = acc.toString().trim();
        main.post(() -> listener.onComplete(finalText));
    }

    @Override
    public void stopGeneration() {
        generating.set(false);
        if (LlamaNative.isAvailable()) {
            LlamaNative.cancelGeneration();
        }
    }

    @Override
    public void unloadModel() {
        io.execute(this::unloadInternal);
    }

    private void unloadInternal() {
        if (LlamaNative.isAvailable()) {
            LlamaNative.unloadModel();
        }
        loadedConfig = null;
    }

    private void postError(LoadCallback callback, String message) {
        main.post(() -> callback.onError(message));
    }
}
