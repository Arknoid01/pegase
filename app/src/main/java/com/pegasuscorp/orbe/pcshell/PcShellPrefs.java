package com.pegasuscorp.orbe.pcshell;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Réglages SSH PC — config en clair chiffré ; clés SSH dans un coffre biométrique (Keystore).
 */
public final class PcShellPrefs {

    public static final String DEFAULT_USER = "pegase-remote";
    public static final int DEFAULT_PORT = 22;
    /** Timeout SSH par défaut (commandes courtes). */
    public static final int DEFAULT_TIMEOUT_SEC = 180;
    /** Timeout pour pacman / mises à jour système. */
    public static final int PACMAN_TIMEOUT_SEC = 600;
    /** Timeout pour sync GitHub + gradlew assembleDebug + copie Drive. */
    public static final int BUILD_APK_TIMEOUT_SEC = 1800;
    /** Plafond timeout par commande enregistrée. */
    public static final int MAX_TIMEOUT_SEC = 3600;
    /** Sortie max stdout/stderr par exécution (Phase 1.5). */
    public static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private static final String PREFS_CONFIG = "pc_shell_config";
    /** Ancien fichier unique — migration vers config + coffre clés. */
    private static final String PREFS_LEGACY = "pc_shell_secure";
    private static final String PREFS_KEYS = "pc_shell_keys";
    private static final String PREFS_KEYS_TEST = "pc_shell_keys_test";

    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USER = "user";
    private static final String KEY_HAS_KEY_PAIR = "has_key_pair";
    private static final String KEY_PRIVATE_PEM = "private_key_pem";
    private static final String KEY_PUBLIC_LINE = "public_key_line";

    private static volatile boolean bypassForTests;

    private PcShellPrefs() {}

    public static boolean isBypassForTests() {
        return bypassForTests;
    }

    public static void setBypassForTests(boolean bypass) {
        bypassForTests = bypass;
    }

    private static SharedPreferences prefsConfig(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_CONFIG,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS_CONFIG + "_fallback", Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences prefsLegacy(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_LEGACY,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS_LEGACY + "_fallback", Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences prefsKeyVault(Context context) throws Exception {
        if (bypassForTests) {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS_KEYS_TEST, Context.MODE_PRIVATE);
        }
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setUserAuthenticationRequired(true, (int) (PcShellSession.SESSION_MS / 1000L))
                .build();
        return EncryptedSharedPreferences.create(
                context,
                PREFS_KEYS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /** Migration config + clés depuis l'ancien fichier unique (post-auth). */
    public static synchronized void migrateLegacyKeysIfNeeded(Context context) {
        SharedPreferences legacy = prefsLegacy(context);
        SharedPreferences config = prefsConfig(context);

        if (TextUtils.isEmpty(config.getString(KEY_HOST, ""))) {
            String host = legacy.getString(KEY_HOST, "");
            if (!TextUtils.isEmpty(host)) {
                config.edit().putString(KEY_HOST, host).apply();
            }
        }
        if (!config.contains(KEY_PORT) && legacy.contains(KEY_PORT)) {
            config.edit().putInt(KEY_PORT, legacy.getInt(KEY_PORT, DEFAULT_PORT)).apply();
        }
        if (TextUtils.isEmpty(config.getString(KEY_USER, ""))) {
            String user = legacy.getString(KEY_USER, "");
            if (!TextUtils.isEmpty(user)) {
                config.edit().putString(KEY_USER, user).apply();
            }
        }

        String legacyPriv = legacy.getString(KEY_PRIVATE_PEM, "");
        String legacyPub = legacy.getString(KEY_PUBLIC_LINE, "");
        if (TextUtils.isEmpty(legacyPriv) || TextUtils.isEmpty(legacyPub)) {
            return;
        }
        try {
            SharedPreferences vault = prefsKeyVault(context);
            if (!vault.contains(KEY_PRIVATE_PEM)) {
                vault.edit()
                        .putString(KEY_PRIVATE_PEM, legacyPriv)
                        .putString(KEY_PUBLIC_LINE, legacyPub)
                        .apply();
                config.edit().putBoolean(KEY_HAS_KEY_PAIR, true).apply();
            }
            legacy.edit()
                    .remove(KEY_PRIVATE_PEM)
                    .remove(KEY_PUBLIC_LINE)
                    .apply();
        } catch (Exception ignored) {
            // Coffre biométrique indisponible — clés restent en legacy jusqu'à prochain essai.
        }
    }

    static String readPrivateKeyFromVault(Context context) {
        if (PcShellKeyCache.isLoaded() && !TextUtils.isEmpty(PcShellKeyCache.getPrivateKeyPem())) {
            return PcShellKeyCache.getPrivateKeyPem();
        }
        try {
            return prefsKeyVault(context).getString(KEY_PRIVATE_PEM, "");
        } catch (Exception e) {
            return "";
        }
    }

    static String readPublicKeyFromVault(Context context) {
        if (PcShellKeyCache.isLoaded() && !TextUtils.isEmpty(PcShellKeyCache.getPublicKeyLine())) {
            return PcShellKeyCache.getPublicKeyLine();
        }
        try {
            return prefsKeyVault(context).getString(KEY_PUBLIC_LINE, "");
        } catch (Exception e) {
            return "";
        }
    }

    public static String getHost(Context context) {
        migrateLegacyKeysIfNeeded(context);
        return prefsConfig(context).getString(KEY_HOST, "").trim();
    }

    public static void setHost(Context context, String host) {
        prefsConfig(context).edit().putString(KEY_HOST, host == null ? "" : host.trim()).apply();
    }

    public static int getPort(Context context) {
        migrateLegacyKeysIfNeeded(context);
        int p = prefsConfig(context).getInt(KEY_PORT, DEFAULT_PORT);
        return p > 0 && p <= 65535 ? p : DEFAULT_PORT;
    }

    public static void setPort(Context context, int port) {
        prefsConfig(context).edit().putInt(KEY_PORT, port).apply();
    }

    public static String getUser(Context context) {
        migrateLegacyKeysIfNeeded(context);
        String u = prefsConfig(context).getString(KEY_USER, DEFAULT_USER);
        return TextUtils.isEmpty(u) ? DEFAULT_USER : u.trim();
    }

    public static void setUser(Context context, String user) {
        prefsConfig(context).edit().putString(KEY_USER,
                TextUtils.isEmpty(user) ? DEFAULT_USER : user.trim()).apply();
    }

    /** Clé privée — uniquement si session biométrique active. */
    public static String getPrivateKeyPem(Context context) {
        if (!PcShellSession.isUnlocked() && !bypassForTests) {
            return "";
        }
        String cached = PcShellKeyCache.getPrivateKeyPem();
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        return readPrivateKeyFromVault(context);
    }

    public static String getPublicKeyLine(Context context) {
        if (!PcShellSession.isUnlocked() && !bypassForTests) {
            return "";
        }
        String cached = PcShellKeyCache.getPublicKeyLine();
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        return readPublicKeyFromVault(context);
    }

    public static boolean hasKeyPair(Context context) {
        migrateLegacyKeysIfNeeded(context);
        if (prefsConfig(context).getBoolean(KEY_HAS_KEY_PAIR, false)) {
            return true;
        }
        SharedPreferences legacy = prefsLegacy(context);
        return !TextUtils.isEmpty(legacy.getString(KEY_PRIVATE_PEM, ""))
                && !TextUtils.isEmpty(legacy.getString(KEY_PUBLIC_LINE, ""));
    }

    public static void saveKeyPair(Context context, String privatePem, String publicLine) {
        migrateLegacyKeysIfNeeded(context);
        try {
            SharedPreferences vault = prefsKeyVault(context);
            vault.edit()
                    .putString(KEY_PRIVATE_PEM, privatePem != null ? privatePem : "")
                    .putString(KEY_PUBLIC_LINE, publicLine != null ? publicLine : "")
                    .apply();
            prefsConfig(context).edit().putBoolean(KEY_HAS_KEY_PAIR, true).apply();
            if (PcShellSession.isUnlocked() || bypassForTests) {
                PcShellKeyCache.putCachedKeys(
                        privatePem != null ? privatePem : "",
                        publicLine != null ? publicLine : "");
            }
            prefsLegacy(context).edit()
                    .remove(KEY_PRIVATE_PEM)
                    .remove(KEY_PUBLIC_LINE)
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Enregistrement clé SSH impossible", e);
        }
    }

    public static void clearKeyPair(Context context) {
        try {
            prefsKeyVault(context).edit().clear().apply();
        } catch (Exception ignored) {
        }
        prefsConfig(context).edit().putBoolean(KEY_HAS_KEY_PAIR, false).apply();
        prefsLegacy(context).edit()
                .remove(KEY_PRIVATE_PEM)
                .remove(KEY_PUBLIC_LINE)
                .apply();
        PcShellKeyCache.lock();
    }

    public static boolean isConfigured(Context context) {
        return !TextUtils.isEmpty(getHost(context)) && hasKeyPair(context);
    }

    /** Base URL ntfy (sans topic) — défaut {@code https://ntfy.sh}. */
    public static String getNtfyBaseUrl() {
        return "https://ntfy.sh";
    }

    /** Token Bearer optionnel pour topic ntfy privé (config future). */
    public static String getNtfyToken() {
        return "";
    }

    /** Visible tests. */
    public static void resetForTests(Context context) {
        prefsConfig(context).edit().clear().apply();
        try {
            prefsKeyVault(context).edit().clear().apply();
        } catch (Exception ignored) {
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS_KEYS_TEST, Context.MODE_PRIVATE).edit().clear().apply();
        prefsLegacy(context).edit().clear().apply();
        PcShellSession.resetForTests();
        PcShellKeyCache.resetForTests();
        bypassForTests = false;
    }
}
