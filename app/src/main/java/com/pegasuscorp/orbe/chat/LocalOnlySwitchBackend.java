package com.pegasuscorp.orbe.chat;

import android.content.Context;

import com.pegasuscorp.orbe.llm.LocalLlmServer;

import java.util.List;

/**
 * Aiguillage à chaque appel : réglage « Toujours local » ({@link LocalLlmServer#isLocalOnly})
 * → modèle du téléphone ; sinon → chaîne cloud. Bascule sans recréer la conversation, et
 * couvre tous les usages (discussion, résumés, traductions…) qui passent par la fabrique.
 */
public final class LocalOnlySwitchBackend implements ChatBackend, ProviderTraceSink {

    private final Context app;
    private final ChatBackend cloud;
    private final ChatBackend local;

    LocalOnlySwitchBackend(Context app, ChatBackend cloud, ChatBackend local) {
        this.app = app.getApplicationContext();
        this.cloud = cloud;
        this.local = local;
    }

    private ChatBackend pick() {
        return LocalLlmServer.isLocalOnly(app) ? local : cloud;
    }

    /** Le backend cloud sous-jacent (réglages, sondes de clés). */
    public ChatBackend cloud() {
        return cloud;
    }

    @Override
    public void consumePendingProviderTrace() {
        if (cloud instanceof ProviderTraceSink) ((ProviderTraceSink) cloud).consumePendingProviderTrace();
    }

    @Override
    public void discardPendingProviderTrace() {
        if (cloud instanceof ProviderTraceSink) ((ProviderTraceSink) cloud).discardPendingProviderTrace();
    }

    @Override
    public boolean supportsStreaming() {
        return pick().supportsStreaming();
    }

    @Override
    public String traceBackendLabel() {
        return pick().traceBackendLabel();
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        pick().send(history, userMessage, callback);
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        pick().send(history, userMessage, callback, options);
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        pick().sendAgenticContinuation(chain, options, callback);
    }

    @Override
    public void sendAgenticContinuation(AgenticContinuation continuation, OnReply callback) {
        pick().sendAgenticContinuation(continuation, callback);
    }
}
