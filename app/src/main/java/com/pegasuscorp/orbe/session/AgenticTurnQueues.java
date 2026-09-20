package com.pegasuscorp.orbe.session;

import com.pegasuscorp.orbe.chat.AgenticChain;
import com.pegasuscorp.orbe.chat.LlmReply;
import com.pegasuscorp.orbe.chat.NativeToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Files d'un tour agentique : appels restants d'un lot (exécutés sans aller-retour LLM) et
 * consignes de steering reçues pendant le tour. Sans dépendance Android : testable seule.
 */
final class AgenticTurnQueues {

    /** Au-delà, un tour agentique est considéré bloqué : un nouveau message le remplace. */
    static final long STEERING_MAX_IDLE_MS = 90_000L;

    private final ArrayDeque<NativeToolCall> batch = new ArrayDeque<>();
    private final List<String> steering = new ArrayList<>();
    private long lastActivityMs;

    /** Marque une activité du tour (étape LLM, résultat d'outil). */
    void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    boolean isIdleTooLong() {
        return System.currentTimeMillis() - lastActivityMs > STEERING_MAX_IDLE_MS;
    }

    // --- lot d'appels ---

    /** Met en file les appels 2..N d'un message assistant (le premier est exécuté à part). */
    void stageBatchRemainder(LlmReply reply) {
        batch.clear();
        if (reply == null) return;
        for (int i = 1; i < reply.toolCalls.size(); i++) batch.add(reply.toolCalls.get(i));
    }

    /** Prochain appel du lot, ou null s'il n'en reste pas. */
    NativeToolCall pollBatch() {
        return batch.pollFirst();
    }

    int batchSize() {
        return batch.size();
    }

    void clearBatch() {
        batch.clear();
    }

    // --- steering ---

    /** @return nombre de consignes en attente après ajout */
    synchronized int addSteering(String text) {
        steering.add(text);
        return steering.size();
    }

    synchronized boolean hasSteering() {
        return !steering.isEmpty();
    }

    /** Verse les consignes en attente dans la chaîne et vide la file. */
    synchronized void drainSteeringInto(AgenticChain chain) {
        if (chain == null || steering.isEmpty()) return;
        for (String note : steering) chain.addSteering(note);
        steering.clear();
    }

    /** Fin ou annulation du tour : tout est vidé. */
    synchronized void clear() {
        batch.clear();
        steering.clear();
    }
}
