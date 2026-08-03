package com.pegasuscorp.orbe.voice;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.pegasuscorp.orbe.diag.PegaseDiagLog;

import org.json.JSONObject;

/**
 * Trace horodatée de la transition wake → STT (cross-process via Intent extras).
 * <p>
 * Séquence attendue dans {@code files/diag/kws_lifecycle.jsonl} :
 * {@code wake_detected} → {@code kws_release_start} → {@code kws_release_done}
 * → {@code stt_open_start} → {@code stt_prepare_done} → {@code stt_open_done}
 * → {@code stt_ready} (optionnel).
 */
public final class WakeToSttTrace {

    private static final String TAG = "WakeToStt";

    public static final String EXTRA_TRANSITION_ID = "wake_transition_id";
    public static final String EXTRA_TRANSITION_T0 = "wake_transition_t0";

    /** Process-local (launcher) — rempli depuis l'Intent wake. */
    private static volatile long sId;
    private static volatile long sT0ElapsedMs;

    private WakeToSttTrace() {}

    /** Démarre une nouvelle transition (:voice). */
    public static long begin() {
        long t0 = SystemClock.elapsedRealtime();
        sId = t0;
        sT0ElapsedMs = t0;
        return t0;
    }

    public static void attachToIntent(Intent intent, long transitionId, long t0ElapsedMs) {
        if (intent == null) return;
        intent.putExtra(EXTRA_TRANSITION_ID, transitionId);
        intent.putExtra(EXTRA_TRANSITION_T0, t0ElapsedMs);
    }

    /** Relaye l'id depuis l'activité wake (processus launcher). */
    public static void adoptFromIntent(Intent intent) {
        if (intent == null) return;
        long id = intent.getLongExtra(EXTRA_TRANSITION_ID, 0L);
        long t0 = intent.getLongExtra(EXTRA_TRANSITION_T0, 0L);
        if (id != 0L) {
            sId = id;
            sT0ElapsedMs = t0 != 0L ? t0 : id;
        }
    }

    public static long currentId() {
        return sId;
    }

    public static long currentT0() {
        return sT0ElapsedMs;
    }

    public static void mark(Context ctx, String event) {
        mark(ctx, event, null);
    }

    public static void mark(Context ctx, String event, JSONObject extra) {
        if (event == null || event.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        long id = sId;
        long t0 = sT0ElapsedMs;
        long elapsed = (id != 0L && t0 != 0L) ? (now - t0) : -1L;
        try {
            JSONObject f = extra != null ? extra : new JSONObject();
            if (id != 0L) f.put("transition_id", id);
            if (t0 != 0L) f.put("t0_elapsed", t0);
            f.put("now_elapsed", now);
            f.put("elapsed_ms", elapsed);
            Log.i(TAG, event + " elapsed_ms=" + elapsed
                    + (extra != null ? (" " + extra) : ""));
            if (ctx != null) {
                PegaseDiagLog.kws(ctx, event, f);
            } else {
                PegaseDiagLog.kwsFromStatic(event, f);
            }
        } catch (Exception e) {
            Log.w(TAG, "mark " + event, e);
        }
    }
}
