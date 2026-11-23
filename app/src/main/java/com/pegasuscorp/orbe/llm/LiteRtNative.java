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

    /** Retourne la réponse JSON brute de LiteRT, ou null. */
    static native String nativeGenerate(long handle, String systemJson, String historyJson,
                                        String userJson, int maxOutputTokens, String constraintRegex);

    static native String nativeLastError();

    /** Flux : démarre (0 en cas d'échec), lit le texte accumulé, teste la fin, puis libère. */
    static native long nativeStreamStart(long handle, String systemJson, String historyJson,
                                         String userJson, int maxOutputTokens, String constraintRegex);

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
