package com.pegasuscorp.orbe.sms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.chat.ChatSendOptions;
import com.pegasuscorp.orbe.chat.ProviderTraceSink;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demande au LLM de classer des SMS (spam / legit / doute) et applique les verdicts.
 * Bloquant : à appeler hors du thread UI.
 */
public final class SmsSpamLlm {

    private static final String TAG = "SmsSpamLlm";
    static final int BATCH = 40;
    /** gpt-oss raisonne avant de répondre : 12 s ne suffisaient pas toujours pour 40 SMS. */
    static final long TIMEOUT_S = 60;
    /** Gemma local sur le Nothing : 45-61 s pour un lot → 60 s faisait échouer le tri. */
    static final long TIMEOUT_LOCAL_S = 180;
    /** Place pour ~40 verdicts JSON + le raisonnement du modèle (500 tronquait la liste). */
    static final int MAX_TOKENS = 4000;

    private SmsSpamLlm() {}

    /** Appel nu : ni persona ni outils, température basse. */
    public static ChatSendOptions options() {
        return ChatSendOptions.bare(MAX_TOKENS);
    }

    /** Classe tous les candidats. Renvoie false si un lot a échoué (timeout / erreur). */
    public static boolean classify(Context ctx, List<SmsSpamRules.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return true;
        boolean ok = true;
        for (int from = 0; from < candidates.size(); from += BATCH) {
            int to = Math.min(from + BATCH, candidates.size());
            if (!classifyBatch(ctx, candidates.subList(from, to))) ok = false;
        }
        return ok;
    }

    private static boolean classifyBatch(Context ctx, List<SmsSpamRules.Candidate> batch) {
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ChatBackend backend = ChatBackendFactory.create(ctx);
        new Handler(Looper.getMainLooper()).post(() -> backend.send(Collections.emptyList(),
                SmsSpamRules.buildLlmPrompt(batch),
                new ChatBackend.OnReply() {
                    @Override
                    public void onReply(String text) {
                        discardTrace(backend);
                        reply.set(text);
                        done.countDown();
                    }

                    @Override
                    public void onError(String err) {
                        discardTrace(backend);
                        error.set(err);
                        done.countDown();
                    }
                }, options()));
        try {
            long timeout = com.pegasuscorp.orbe.llm.LocalLlmServer.isLocalOnly(ctx)
                    ? TIMEOUT_LOCAL_S : TIMEOUT_S;
            if (!done.await(timeout, TimeUnit.SECONDS)) {
                Log.w(TAG, "LLM trop lent");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (error.get() != null) {
            Log.w(TAG, "LLM indisponible : " + error.get());
            return false;
        }
        Map<Long, String[]> verdicts = SmsSpamRules.parseLlmVerdicts(reply.get());
        if (verdicts.isEmpty()) {
            String r = reply.get();
            Log.w(TAG, "réponse illisible (" + (r != null ? r.length() : 0) + " car.) : "
                    + (r != null ? r.substring(0, Math.min(200, r.length())) : "null"));
            return false;
        }
        int spam = 0;
        for (String[] v : verdicts.values()) {
            if (SmsSpamRules.verdictOf(v[0]) == SmsSpamRules.Verdict.SPAM) spam++;
        }
        Log.i(TAG, verdicts.size() + " verdicts / " + batch.size() + " SMS, dont " + spam + " spam");
        for (SmsSpamRules.Candidate c : batch) {
            String[] v = verdicts.get(c.threadId);
            if (v != null) {
                SmsSpamRules.applyVerdict(c, SmsSpamRules.verdictOf(v[0]), v[1]);
            }
        }
        return true;
    }

    private static void discardTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).discardPendingProviderTrace();
        }
    }
}
