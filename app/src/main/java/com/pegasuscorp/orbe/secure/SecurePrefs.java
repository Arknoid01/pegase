package com.pegasuscorp.orbe.secure;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.pegasuscorp.orbe.diag.Trace;

import java.util.Map;

/**
 * Préférences chiffrées (Keystore, AES-256) pour les secrets : clés API, jetons.
 *
 * <p>Les valeurs d'un ancien fichier en clair de même nom de base sont migrées au premier accès
 * puis effacées. Sans Keystore (tests JVM), l'ancien fichier en clair reste utilisé.
 */
public final class SecurePrefs {

    private SecurePrefs() {}

    /**
     * @param plainName nom de l'ancien fichier en clair (source de migration)
     * @param encryptedName nom du fichier chiffré
     */
    public static SharedPreferences open(Context context, String plainName, String encryptedName) {
        Context app = context.getApplicationContext();
        SharedPreferences legacy = app.getSharedPreferences(plainName, Context.MODE_PRIVATE);
        SharedPreferences enc = createEncrypted(app, encryptedName);
        if (enc == null) return legacy;
        migrate(legacy, enc);
        return enc;
    }

    static void migrate(SharedPreferences from, SharedPreferences to) {
        Map<String, ?> old = from.getAll();
        if (old.isEmpty()) return;
        SharedPreferences.Editor ed = to.edit();
        for (Map.Entry<String, ?> e : old.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) ed.putString(e.getKey(), (String) v);
            else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
            else if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
        }
        // l'ancien fichier n'est effacé qu'une fois la copie chiffrée écrite
        if (ed.commit()) from.edit().clear().apply();
    }

    private static SharedPreferences createEncrypted(Context app, String name) {
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(app, name, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception | Error e) {
            Trace.error("secure_prefs", "stockage chiffré indisponible ("
                    + e.getClass().getSimpleName() + ") : " + name);
            return null;
        }
    }
}
