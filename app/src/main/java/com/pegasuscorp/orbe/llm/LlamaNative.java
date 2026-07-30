package com.pegasuscorp.orbe.llm;

import android.util.Log;

/**
 * Pont JNI vers llama.cpp.
 */
final class LlamaNative {

    private static final String TAG = "PegaseLLM";
    private static boolean libraryLoaded;
    private static String loadError;

    static {
        try {
            System.loadLibrary("pegase_llm");
            libraryLoaded = true;
            Log.i(TAG, "Lib native pegase_llm chargée avec succès.");
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
            loadError = e.getMessage();
            // Log complet pour déboguer — visible dans Logcat / filtre "PegaseLLM"
            Log.e(TAG, "ÉCHEC chargement pegase_llm : " + loadError, e);
        }
    }

    private LlamaNative() {}

    static boolean isAvailable() { return libraryLoaded; }
    static String getLoadError()  { return loadError; }

    static native boolean loadModel(String path, int contextSize, int threads);
    static native void    unloadModel();
    static native String  generate(String prompt, float temperature, float topP, int maxTokens);
    static native void    cancelGeneration();
}
