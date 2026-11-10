package com.pegasuscorp.orbe.sms;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.Collections;

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
        SmsSpamLlm.classify(getContext(), Collections.singletonList(c));
        SmsSpamRules.Verdict parsed = c.verdict;
        String name = parsed == SmsSpamRules.Verdict.SPAM ? "spam"
                : parsed == SmsSpamRules.Verdict.LEGIT ? "legit" : "unsure";
        Log.i(TAG, "verdict " + name);
        if (SmsSpamRules.isSureToDelete(parsed) && SmsInbox.isAutoCleanEnabled(getContext())) {
            int n = SmsInbox.deleteSureSpamByAddress(getContext(), address);
            Log.i(TAG, "auto-delete " + n + " thread(s) for " + address);
        }
        return verdict(out, name, c.reason);
    }

    private static Bundle verdict(Bundle out, String verdict, String reason) {
        out.putString("verdict", verdict);
        out.putString("reason", reason != null ? reason : "");
        return out;
    }

    // Pas de données tabulaires : seul call() sert.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
