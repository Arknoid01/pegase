package com.pegasuscorp.orbe.chat;

import android.content.Context;

import java.util.List;

/**
 * Façade Groq — délègue à {@link OpenAiCompatibleChatBackend}.
 * Conservée pour FallbackChatBackend / messages diag.
 */
public class GroqChatBackend implements ChatBackend {

    private final OpenAiCompatibleChatBackend delegate;

    public GroqChatBackend(Context context, String apiKey) {
        this(context, apiKey, null);
    }

    public GroqChatBackend(Context context, String apiKey, String modelOverride) {
        String model = modelOverride != null
                ? modelOverride
                : CloudModelStore.getGroqModelId(context);
        LlmProvider provider = new LlmProvider(
                LlmProvider.ID_GROQ,
                "Groq",
                model,
                apiKey,
                ProviderChain.GROQ_URL,
                true,
                1,
                LlmProvider.connectTimeoutMs(),
                LlmProvider.readTimeoutMsFor(LlmProvider.ID_GROQ, false),
                null);
        // Retries internes pour usage standalone (Fallback) ; MultiProvider utilise max=0
        this.delegate = new OpenAiCompatibleChatBackend(context, provider, modelOverride, 2);
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    @Override
    public String traceBackendLabel() {
        return delegate.traceBackendLabel();
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        delegate.send(history, userMessage, callback);
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        delegate.send(history, userMessage, callback, options);
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        delegate.sendAgenticContinuation(chain, options, callback);
    }

    @Override
    public void sendAgenticContinuation(AgenticContinuation continuation, OnReply callback) {
        delegate.sendAgenticContinuation(continuation, callback);
    }
}
