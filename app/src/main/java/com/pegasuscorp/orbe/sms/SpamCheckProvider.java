package com.pegasuscorp.orbe.sms;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pegasuscorp.orbe.chat.ChatBackend;
import com.pegasuscorp.orbe.chat.ChatBackendFactory;
import com.pegasuscorp.orbe.chat.ProviderTraceSink;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guichet « spam ou pas ? » pour Pégase SMS (fork de QUIK).
 *
 * QUIK appelle {@code call("classify", extras{address, body, known_contact, thread_count})}
 * depuis son worker de réception (thread de fond) ; on répond
 * {@code {verdict: spam|legit|unsure, reason}}. Protégé par une permission de signature :
 * seules les apps signées avec la même clé peuvent l'interroger.
 *
 * Prudence : sans verdict « spam » explicite du LLM, on ne bloque jamais — un vrai
 * premier message d'un inconnu ne doit pas finir dans « Bloqués ».
 */
public class SpamCheckProvider extends ContentProvider {

    private static final String TAG = "SpamCheck";
    private static final long LLM_TIMEOUT_S = 12;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"classify".equals(method) || extras == null) {
            out.putString("verdict", "unsure");
            return out;
        }
        String address = extras.getString("address", "");
        String body = extras.getString("body", "");
        boolean knownContact = extras.getBoolean("known_contact", false);
        int threadCount = extras.getInt("thread_count", 1);

        // Contact ou conversation déjà vivante : jamais de spam, pas d'appel LLM.
        if (knownContact || threadCount > 1) {
            return verdict(out, "legit", "");
        }

        SmsSpamRules.Candidate c = new SmsSpamRules.Candidate(0, address, body,
                System.currentTimeMillis());
        AtomicReference<String> reply = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ChatBackend backend = ChatBackendFactory.create(getContext());
        // Les callbacks arrivent sur le thread principal : on attend ici, sur le thread binder.
        new Handler(Looper.getMainLooper()).post(() -> backend.send(Collections.emptyList(),
                SmsSpamRules.buildLlmPrompt(Collections.singletonList(c)),
                new ChatBackend.OnReply() {
                    @Override
                    public void onReply(String text) {
                        discardTrace(backend);
                        reply.set(text);
                        done.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        discardTrace(backend);
                        Log.w(TAG, "LLM indisponible : " + error);
                        done.countDown();
                    }
                }));
        try {
            if (!done.await(LLM_TIMEOUT_S, TimeUnit.SECONDS)) Log.w(TAG, "LLM trop lent");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<Long, String[]> verdicts = SmsSpamRules.parseLlmVerdicts(reply.get());
        String[] v = verdicts.get(0L);
        if (v == null) return verdict(out, "unsure", "");
        SmsSpamRules.Verdict parsed = SmsSpamRules.verdictOf(v[0]);
        String name = parsed == SmsSpamRules.Verdict.SPAM ? "spam"
                : parsed == SmsSpamRules.Verdict.LEGIT ? "legit" : "unsure";
        Log.i(TAG, "verdict " + name);
        return verdict(out, name, v[1]);
    }

    private static Bundle verdict(Bundle out, String verdict, String reason) {
        out.putString("verdict", verdict);
        out.putString("reason", reason != null ? reason : "");
        return out;
    }

    /** Le contenu des SMS ne doit pas finir dans la trace de diagnostic. */
    private static void discardTrace(ChatBackend backend) {
        if (backend instanceof ProviderTraceSink) {
            ((ProviderTraceSink) backend).discardPendingProviderTrace();
        }
    }

    // Pas de données tabulaires : seul call() sert.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
