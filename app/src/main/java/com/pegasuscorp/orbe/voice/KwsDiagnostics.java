package com.pegasuscorp.orbe.voice;

import android.util.Log;

import java.util.Arrays;
import java.util.Locale;

/**
 * Logs de diagnostic KWS — route audio + proxy qualité signal (Sherpa n'expose pas de score).
 */
public final class KwsDiagnostics {

    private static final String TAG = "KwsDiagnostics";
    private static final float KEYWORDS_THRESHOLD = 0.10f;
    private static final float KEYWORDS_SCORE = 2.0f;

    private static volatile long lastProbeLogMs;
    private static volatile int decodeProbes;

    private KwsDiagnostics() {}

    /** Appelé au début de chaque session micro / tentative d'écoute. */
    public static void logSessionStart(String routeDescription) {
        decodeProbes = 0;
        Log.i(TAG, "session_start route={" + routeDescription + "} threshold="
                + KEYWORDS_THRESHOLD + " keyword_score=" + KEYWORDS_SCORE);
    }

    /** Pendant la boucle — niveau audio + route (proxy « confiance » signal). */
    public static void maybeLogProbe(String routeDescription, float rmsDb, int readSamples) {
        decodeProbes++;
        long now = System.currentTimeMillis();
        if (now - lastProbeLogMs < 2_000L) return;
        lastProbeLogMs = now;
        Log.i(TAG, String.format(Locale.US,
                "probe #%d route={%s} rms_db=%.1f samples=%d threshold=%.2f",
                decodeProbes, routeDescription, rmsDb, readSamples, KEYWORDS_THRESHOLD));
    }

    /** Decode prêt mais pas encore de mot — utile pour calibrer le seuil sur micro téléphone. */
    public static void logDecodeReadyNoHit(String routeDescription, float rmsDb,
            String[] tokens, float[] timestamps) {
        if (decodeProbes % 5 != 0) return;
        String tok = tokens != null && tokens.length > 0
                ? Arrays.toString(tokens) : "[]";
        String ts = timestamps != null && timestamps.length > 0
                ? Arrays.toString(timestamps) : "[]";
        Log.d(TAG, String.format(Locale.US,
                "decode_ready_no_hit route={%s} rms_db=%.1f tokens=%s ts=%s threshold=%.2f",
                routeDescription, rmsDb, tok, ts, KEYWORDS_THRESHOLD));
    }

    /** Mot-clé détecté — log complet pour corréler route / signal / tokens. */
    public static void logHit(String routeDescription, float rmsDb, String keyword,
            String[] tokens, float[] timestamps) {
        Log.i(TAG, String.format(Locale.US,
                "HIT keyword=\"%s\" route={%s} rms_db=%.1f tokens=%s ts=%s "
                        + "threshold=%.2f keyword_score=%.1f",
                keyword, routeDescription, rmsDb,
                tokens != null ? Arrays.toString(tokens) : "[]",
                timestamps != null ? Arrays.toString(timestamps) : "[]",
                KEYWORDS_THRESHOLD, KEYWORDS_SCORE));
    }

    /** RMS en dB (0 = silence, valeurs négatives typiques pour la parole). */
    public static float computeRmsDb(short[] buffer, int length) {
        if (buffer == null || length <= 0) return -96f;
        double sum = 0;
        for (int i = 0; i < length; i++) {
            double s = buffer[i] / 32768.0;
            sum += s * s;
        }
        double rms = Math.sqrt(sum / length);
        if (rms < 1e-9) return -96f;
        return (float) (20.0 * Math.log10(rms));
    }
}
