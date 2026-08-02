package com.pegasuscorp.orbe.voice;

import android.util.Log;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

/**
 * Logs de diagnostic KWS — route audio + proxy qualité signal (Sherpa n'expose pas de score).
 * Duplique les événements clés dans {@link PegaseDiagLog} (JSONL local).
 */
public final class KwsDiagnostics {

    private static final String TAG = "KwsDiagnostics";
    private static final float KEYWORDS_THRESHOLD = 0.04f;
    private static final float KEYWORDS_SCORE = 5.0f;
    /** Parole probable sans BPE — sous ce RMS (dB) on ignore. */
    private static final float SPEECH_RMS_DB = -40f;

    private static volatile long lastProbeLogMs;
    private static volatile long lastSpeechNoTokensMs;
    private static volatile long lastMediaSkipLogMs;
    private static volatile int decodeProbes;
    private static volatile int mediaSkipStreak;

    private KwsDiagnostics() {}

    /** Appelé au début de chaque session micro / tentative d'écoute. */
    public static void logSessionStart(String routeDescription) {
        decodeProbes = 0;
        mediaSkipStreak = 0;
        Log.i(TAG, "session_start route={" + routeDescription + "} threshold="
                + KEYWORDS_THRESHOLD + " keyword_score=" + KEYWORDS_SCORE);
        fileEvent("kws_session_start", routeDescription, null, -96f, null, null);
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
        try {
            JSONObject f = new JSONObject();
            f.put("probe", decodeProbes);
            f.put("route", routeDescription);
            f.put("rms_db", rmsDb);
            f.put("samples", readSamples);
            f.put("threshold", KEYWORDS_THRESHOLD);
            PegaseDiagLog.kwsFromStatic("kws_probe", f);
        } catch (Exception ignored) {}
    }

    /** Decode prêt mais pas encore de mot — utile pour calibrer le seuil sur micro téléphone. */
    public static void logDecodeReadyNoHit(String routeDescription, float rmsDb,
            String[] tokens, float[] timestamps) {
        if (tokens != null && tokens.length > 0) {
            logNearMiss(routeDescription, rmsDb, tokens, timestamps);
            return;
        }
        maybeLogSpeechNoTokens(routeDescription, rmsDb);
        if (decodeProbes % 5 != 0) return;
        Log.d(TAG, String.format(Locale.US,
                "decode_ready_no_hit route={%s} rms_db=%.1f tokens=[] ts=[] threshold=%.2f",
                routeDescription, rmsDb, KEYWORDS_THRESHOLD));
        fileEvent("kws_decode_no_hit", routeDescription, null, rmsDb, tokens, timestamps);
    }

    /**
     * Tokens BPE présents mais pas de keyword — seuil / boost trop stricts.
     * Toujours en Info, non throttlé.
     */
    public static void logNearMiss(String routeDescription, float rmsDb,
            String[] tokens, float[] timestamps) {
        Log.i(TAG, String.format(Locale.US,
                "near_miss route={%s} rms_db=%.1f tokens=%s ts=%s threshold=%.2f",
                routeDescription, rmsDb,
                Arrays.toString(tokens),
                timestamps != null && timestamps.length > 0
                        ? Arrays.toString(timestamps) : "[]",
                KEYWORDS_THRESHOLD));
        fileEvent("kws_near_miss", routeDescription, null, rmsDb, tokens, timestamps);
    }

    /** Parole (RMS fort) sans aucun token BPE — modèle sourd / mauvaise source. */
    public static void maybeLogSpeechNoTokens(String routeDescription, float rmsDb) {
        if (rmsDb <= SPEECH_RMS_DB) return;
        long now = System.currentTimeMillis();
        if (now - lastSpeechNoTokensMs < 2_000L) return;
        lastSpeechNoTokensMs = now;
        Log.i(TAG, String.format(Locale.US,
                "speech_no_tokens route={%s} rms_db=%.1f threshold=%.2f",
                routeDescription, rmsDb, KEYWORDS_THRESHOLD));
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("rms_db", rmsDb);
            f.put("threshold", KEYWORDS_THRESHOLD);
            PegaseDiagLog.kwsFromStatic("kws_speech_no_tokens", f);
        } catch (Exception ignored) {}
    }

    /**
     * Média actif pendant l'écoute (on continue à décoder — compteur pour corrélation).
     */
    public static void maybeLogMediaActive(String routeDescription) {
        mediaSkipStreak++;
        long now = System.currentTimeMillis();
        if (now - lastMediaSkipLogMs < 2_000L) return;
        lastMediaSkipLogMs = now;
        Log.i(TAG, String.format(Locale.US,
                "media_skip route={%s} streak=%d (decode continues)",
                routeDescription, mediaSkipStreak));
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("streak", mediaSkipStreak);
            PegaseDiagLog.kwsFromStatic("kws_media_skip", f);
        } catch (Exception ignored) {}
        mediaSkipStreak = 0;
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
        fileEvent("kws_hit", routeDescription, keyword, rmsDb, tokens, timestamps);
    }

    /** HIT ignoré — signal trop faible (poche / frottement). */
    public static void logHitRejectedRms(String routeDescription, float rmsDb, String keyword,
            String[] tokens, float minRmsDb) {
        Log.i(TAG, String.format(Locale.US,
                "hit_rejected_rms keyword=\"%s\" rms_db=%.1f min=%.1f tokens=%s route={%s}",
                keyword, rmsDb, minRmsDb,
                tokens != null ? Arrays.toString(tokens) : "[]",
                routeDescription));
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("keyword", keyword != null ? keyword : "");
            f.put("rms_db", rmsDb);
            f.put("min_rms_db", minRmsDb);
            if (tokens != null) f.put("tokens", Arrays.toString(tokens));
            PegaseDiagLog.kwsFromStatic("kws_hit_rejected_rms", f);
        } catch (Exception ignored) {}
    }

    /** HIT ignoré — écran off + proximité (téléphone en poche). */
    public static void logHitRejectedPocket(String routeDescription, float rmsDb, String keyword,
            String[] tokens) {
        Log.i(TAG, String.format(Locale.US,
                "hit_rejected_pocket keyword=\"%s\" rms_db=%.1f tokens=%s route={%s}",
                keyword, rmsDb,
                tokens != null ? Arrays.toString(tokens) : "[]",
                routeDescription));
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("keyword", keyword != null ? keyword : "");
            f.put("rms_db", rmsDb);
            if (tokens != null) f.put("tokens", Arrays.toString(tokens));
            PegaseDiagLog.kwsFromStatic("kws_hit_rejected_pocket", f);
        } catch (Exception ignored) {}
    }

    /** Reset volontaire du stream Sherpa (anti-saturation après parole). */
    public static void logStreamReset(String routeDescription, String reason) {
        Log.i(TAG, "stream_reset reason=" + reason + " route={" + routeDescription + "}");
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("reason", reason != null ? reason : "");
            PegaseDiagLog.kwsFromStatic("kws_stream_reset", f);
        } catch (Exception ignored) {}
    }

    public static void logLoopError(String routeDescription, String kind, String message) {
        try {
            JSONObject f = new JSONObject();
            f.put("route", routeDescription != null ? routeDescription : "");
            f.put("kind", kind);
            f.put("message", message != null ? message : "");
            PegaseDiagLog.kwsFromStatic("kws_loop_error", f);
        } catch (Exception ignored) {}
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

    private static void fileEvent(String event, String route, String keyword, float rmsDb,
            String[] tokens, float[] timestamps) {
        try {
            JSONObject f = new JSONObject();
            f.put("route", route != null ? route : "");
            f.put("threshold", KEYWORDS_THRESHOLD);
            f.put("keyword_score", KEYWORDS_SCORE);
            if (keyword != null) f.put("keyword", keyword);
            if (rmsDb > -95f) f.put("rms_db", rmsDb);
            if (tokens != null) f.put("tokens", Arrays.toString(tokens));
            if (timestamps != null) f.put("timestamps", Arrays.toString(timestamps));
            PegaseDiagLog.kwsFromStatic(event, f);
        } catch (Exception ignored) {}
    }
}
