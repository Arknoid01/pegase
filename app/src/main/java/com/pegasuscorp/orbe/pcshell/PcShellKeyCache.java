package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.text.TextUtils;

/**
 * Clés SSH en mémoire — disponibles uniquement après déverrouillage biométrique.
 */
public final class PcShellKeyCache {

    private static String privateKeyPem;
    private static String publicKeyLine;

    private PcShellKeyCache() {}

    public static synchronized void unlock(Context context) {
        PcShellPrefs.migrateLegacyKeysIfNeeded(context);
        privateKeyPem = PcShellPrefs.readPrivateKeyFromVault(context);
        publicKeyLine = PcShellPrefs.readPublicKeyFromVault(context);
        PcShellPrefs.promotePublicKeyToConfig(context, publicKeyLine);
        if (TextUtils.isEmpty(publicKeyLine)) {
            publicKeyLine = PcShellPrefs.getPublicKeyLine(context);
        }
    }

    public static synchronized void lock() {
        privateKeyPem = null;
        publicKeyLine = null;
    }

    public static synchronized String getPrivateKeyPem() {
        return privateKeyPem != null ? privateKeyPem : "";
    }

    public static synchronized String getPublicKeyLine() {
        return publicKeyLine != null ? publicKeyLine : "";
    }

    public static synchronized boolean isLoaded() {
        return !getPrivateKeyPem().isEmpty() && !getPublicKeyLine().isEmpty();
    }

    /** Met à jour le cache mémoire après écriture coffre. */
    public static synchronized void putCachedKeys(String priv, String pub) {
        privateKeyPem = priv;
        publicKeyLine = pub;
    }

    /** Visible tests — pas de coffre biométrique en unit test. */
    public static synchronized void seedForTests(String priv, String pub) {
        putCachedKeys(priv, pub);
    }

    public static synchronized void resetForTests() {
        privateKeyPem = null;
        publicKeyLine = null;
    }
}
