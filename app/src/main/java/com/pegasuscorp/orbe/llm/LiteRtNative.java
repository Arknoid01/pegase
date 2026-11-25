package com.pegasuscorp.orbe.llm;

/**
 * Accès natif à LiteRT-LM (moteur Gemma sur GPU). Les fonctions sont dans libpegase_llm.so
 * (litert_jni.cpp) ; liblitert-lm.so est chargée à l'exécution par dlopen.
 */
public final class LiteRtNative {

    static {
        System.loadLibrary("pegase_llm");
    }

    private LiteRtNative() {}

    /** Charge un modèle .litertlm. Retourne 0 en cas d'échec ({@link #lastError()} donne la cause). */
    static native long nativeLoad(String modelPath, String cacheDir, String backend);

    static native void nativeUnload(long handle);

    /**
     * Retourne la réponse JSON brute de LiteRT, ou null.
     * temperature &lt; 0 : réglages par défaut du modèle ; 0 : glouton ; topP/topK &lt;= 0 : 0,95 / 64.
     */
    static native String nativeGenerateSampled(long handle, String systemJson, String historyJson,
                                        String userJson, int maxOutputTokens, String constraintRegex,
                                        float temperature, float topP, int topK);

    /** Sans réglage d'échantillonnage (défauts du modèle). */
    static String nativeGenerate(long handle, String systemJson, String historyJson,
                                 String userJson, int maxOutputTokens, String constraintRegex) {
        return nativeGenerateSampled(handle, systemJson, historyJson, userJson, maxOutputTokens,
                constraintRegex, -1f, -1f, -1);
    }

    static native String nativeLastError();

    /** Flux : démarre (0 en cas d'échec), lit le texte accumulé, teste la fin, puis libère. */
    static native long nativeStreamStartSampled(long handle, String systemJson, String historyJson,
                                         String userJson, int maxOutputTokens, String constraintRegex,
                                         float temperature, float topP, int topK);

    static native String nativeStreamText(long streamId);

    static native boolean nativeStreamDone(long streamId);

    static native String nativeStreamError(long streamId);

    static native void nativeStreamFinish(long streamId);

    public static String lastError() {
        try {
            return nativeLastError();
        } catch (Throwable t) {
            return "bibliothèque native absente : " + t.getMessage();
        }
    }
}
