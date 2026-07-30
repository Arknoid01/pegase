package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.pegasuscorp.orbe.llm.ChatPromptBuilder;
import com.pegasuscorp.orbe.llm.GenerationListener;
import com.pegasuscorp.orbe.llm.GenerationSettings;
import com.pegasuscorp.orbe.llm.LlmEngineManager;
import com.pegasuscorp.orbe.llm.LocalLlmEngine;
import com.pegasuscorp.orbe.llm.ModelConfig;
import com.pegasuscorp.orbe.llm.ModelStore;
import com.pegasuscorp.orbe.llm.PegasePrompt;

import java.util.List;

/**
 * Backend de discussion branché sur le moteur LLM local (llama.cpp).
 */
public class LocalLlmChatBackend implements ChatBackend {

    public interface StreamCallback extends OnReply {
        void onPartial(String text);
    }

    private final Context appContext;
    private final LlmEngineManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LocalLlmChatBackend(Context context) {
        this.appContext = context.getApplicationContext();
        this.manager = LlmEngineManager.getInstance();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        return "Local/" + ModelStore.getActivePreset(appContext);
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        LocalLlmEngine engine = manager.getEngine();
        if (!engine.isModelLoaded()) {
            manager.loadActiveModel(appContext, new LocalLlmEngine.LoadCallback() {
                @Override
                public void onLoaded() {
                    generateReply(history, userMessage, callback);
                }

                @Override
                public void onError(String error) {
                    callback.onError(error);
                }
            });
            return;
        }
        generateReply(history, userMessage, callback);
    }

    private void generateReply(List<Turn> history, String userMessage, OnReply callback) {
        ModelConfig config = manager.getLoadedConfig();
        GenerationSettings settings = config != null
                ? config.toGenerationSettings()
                : GenerationSettings.defaults();

        boolean stream = callback instanceof StreamOnReply
                || callback instanceof StreamCallback;
        final StringBuilder streamAcc = new StringBuilder();
        manager.getEngine().generate(
                ChatPromptBuilder.fromConversation(appContext, history, userMessage),
                settings,
                new GenerationListener() {
                    @Override
                    public void onToken(String token) {
                        if (!stream || token == null) return;
                        streamAcc.append(token);
                        if (callback instanceof StreamOnReply) {
                            ((StreamOnReply) callback).onPartial(streamAcc.toString());
                        } else if (callback instanceof StreamCallback) {
                            ((StreamCallback) callback).onPartial(streamAcc.toString());
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        main.post(() -> callback.onReply(
                                PegasePrompt.sanitizeForDisplay(fullText)));
                    }

                    @Override
                    public void onError(String error) {
                        main.post(() -> callback.onError(error));
                    }
                });
    }
}
