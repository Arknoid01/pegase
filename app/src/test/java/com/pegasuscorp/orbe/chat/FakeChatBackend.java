package com.pegasuscorp.orbe.chat;

import java.util.ArrayList;
import java.util.List;

/** Backend synchrone pour tests — pas de thread, pas de réseau. */
public final class FakeChatBackend implements ChatBackend {

    public String nextReply = "D'accord.";
    public String nextSynthesisReply = "Voilà, c'est fait.";
    public String nextError;
    public int sendCount;
    public int agenticSendCount;
    public boolean streaming;
    /** Partiels émis avant la réponse finale (mode stream). */
    public String[] streamPartials;
    /** Function calling natif simulé (canal TEXT). */
    public List<NativeToolCall> nextNativeToolCalls;
    /** tool_calls renvoyés lors d'une étape agentique (multi-hop). */
    public List<NativeToolCall> nextAgenticToolCalls;
    /** Si true, {@link #send} retient le callback jusqu'à {@link #flushDeferredSend()}. */
    public boolean deferSend;
    public OnReply deferredSendCallback;
    /** Si true, {@link #sendAgenticContinuation} retient le callback jusqu'à {@link #flushDeferredAgentic()}. */
    public boolean deferAgentic;
    public OnReply deferredAgenticCallback;

    public List<Turn> lastHistory = new ArrayList<>();
    String lastUserMessage;
    public ChatSendOptions lastOptions = ChatSendOptions.legacy();
    public AgenticChain lastAgenticChain;
    public ChatSendOptions lastAgenticOptions;

    @Override
    public boolean supportsStreaming() {
        return streaming;
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        send(history, userMessage, callback, ChatSendOptions.legacy());
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback,
            ChatSendOptions options) {
        sendCount++;
        lastHistory = history != null ? new ArrayList<>(history) : new ArrayList<>();
        lastUserMessage = userMessage;
        lastOptions = options != null ? options : ChatSendOptions.legacy();
        if (deferSend) {
            deferredSendCallback = callback;
            return;
        }
        deliverSend(callback);
    }

    /** Complète un envoi différé (tests callbacks en retard). */
    public void flushDeferredSend() {
        if (deferredSendCallback != null) {
            OnReply cb = deferredSendCallback;
            deferredSendCallback = null;
            deliverSend(cb);
        }
    }

    private void deliverSend(OnReply callback) {
        if (nextError != null) {
            callback.onError(nextError);
            return;
        }
        if (lastOptions.nativeTools && nextNativeToolCalls != null && !nextNativeToolCalls.isEmpty()) {
            callback.onLlmReply(LlmReply.withNativeToolCalls("", nextNativeToolCalls));
            return;
        }
        if (streaming && callback instanceof StreamOnReply) {
            StreamOnReply streamCb = (StreamOnReply) callback;
            if (streamPartials != null) {
                for (String partial : streamPartials) {
                    streamCb.onPartial(partial);
                }
            }
            streamCb.onPartial(nextReply);
            streamCb.onReply(nextReply);
        } else {
            callback.onLlmReply(LlmReply.text(nextReply));
        }
    }

    @Override
    public void sendAgenticContinuation(AgenticChain chain, ChatSendOptions options,
            OnReply callback) {
        agenticSendCount++;
        lastAgenticChain = chain;
        lastAgenticOptions = options;
        if (deferAgentic) {
            deferredAgenticCallback = callback;
            return;
        }
        deliverAgentic(callback);
    }

    /** Complète une synthèse agentique différée (tests callbacks en retard). */
    public void flushDeferredAgentic() {
        if (deferredAgenticCallback != null) {
            OnReply cb = deferredAgenticCallback;
            deferredAgenticCallback = null;
            deliverAgentic(cb);
        }
    }

    private void deliverAgentic(OnReply callback) {
        if (nextError != null) {
            callback.onError(nextError);
            return;
        }
        if (nextAgenticToolCalls != null && !nextAgenticToolCalls.isEmpty()) {
            callback.onLlmReply(LlmReply.withNativeToolCalls("", nextAgenticToolCalls));
            nextAgenticToolCalls = null;
            return;
        }
        callback.onLlmReply(LlmReply.text(nextSynthesisReply));
    }
}
