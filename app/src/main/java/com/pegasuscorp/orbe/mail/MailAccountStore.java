package com.pegasuscorp.orbe.mail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Comptes Gmail de la partie Mails : adresse + mot de passe d'application, chiffrés sur le
 * téléphone (EncryptedSharedPreferences). Le mot de passe ne sort que vers imap.gmail.com.
 */
public final class MailAccountStore {

    private static final String PREFS = "pegase_mail_accounts";
    private static final String KEY_LIST = "accounts";
    private static final String PASS_PREFIX = "pass_";

    private MailAccountStore() {}

    public static List<String> accounts(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String password(Context ctx, String email) {
        return prefs(ctx).getString(PASS_PREFIX + email, null);
    }

    public static void add(Context ctx, String email, String appPassword) {
        List<String> list = accounts(ctx);
        if (!list.contains(email)) list.add(email);
        prefs(ctx).edit()
                .putString(KEY_LIST, new JSONArray(list).toString())
                .putString(PASS_PREFIX + email, appPassword)
                .apply();
    }

    public static void remove(Context ctx, String email) {
        List<String> list = accounts(ctx);
        list.remove(email);
        prefs(ctx).edit()
                .putString(KEY_LIST, new JSONArray(list).toString())
                .remove(PASS_PREFIX + email)
                .apply();
        MailCache.clear(ctx, email);
    }

    /** Google affiche le code par blocs de 4 (« abcd efgh ijkl mnop »). */
    public static String normalizePassword(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "");
    }

    private static SharedPreferences prefs(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            MasterKey key = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(app, PREFS, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Pas de repli en clair : sans coffre chiffré, pas de compte mail.
            throw new IllegalStateException("Stockage chiffré indisponible", e);
        }
    }
}
