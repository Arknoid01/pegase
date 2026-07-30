package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import org.json.JSONObject;

/**
 * Lance un minuteur Android.
 * Anti-doublon : durée ±60 s créée il y a moins de 60 s → message sans nouveau timer.
 */
public final class TimerTool implements Tool {

    /** Tolérance durée (secondes) pour considérer deux timers « identiques ». */
    static final int DURATION_TOLERANCE_SEC = 60;
    /** Fenêtre anti-doublon après création. */
    static final long DUPLICATE_WINDOW_MS = 60_000L;

    private static volatile int lastTimerSeconds = -1;
    private static volatile long lastTimerStartedAtMs = 0L;

    @Override public String id() { return "timer"; }

    @Override public ToolTag tag() { return ToolTag.TIMER; }

    @Override
    public String description() {
        return "timer(seconds:int, confirm?:bool) — Lance un minuteur (ex: 300 pour 5 minutes). "
                + "Si un minuteur de durée quasi identique (±60 s) a été créé dans les 60 dernières secondes, "
                + "répond sans en créer un nouveau (sauf confirm:true pour remplacer).";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        int seconds = params != null ? params.optInt("seconds", 0) : 0;
        if (seconds <= 0) {
            int minutes = params != null ? params.optInt("minutes", 5) : 5;
            seconds = minutes * 60;
        }
        boolean confirmReplace = params != null && params.optBoolean("confirm", false);

        if (!confirmReplace && isRecentSimilarTimer(seconds)) {
            cb.onSuccess(ToolResult.text(
                    "Un minuteur de " + formatMinutesLabel(lastTimerSeconds)
                            + " tourne déjà. Tu veux le remplacer ?"));
            return;
        }

        launchTimer(ctx, seconds, cb);
    }

    private static void launchTimer(Context ctx, int seconds, ToolCallback cb) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (intent.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(intent);
            rememberTimer(seconds);
            cb.onSuccessAndExit(ToolResult.text(
                    "Minuteur de " + formatLabel(seconds) + " lancé."));
        } else {
            cb.onError("Aucune application minuteur sur ce téléphone.");
        }
    }

    /**
     * Timer de durée ±60 s créé il y a moins de 60 s.
     * Visible tests.
     */
    static boolean isRecentSimilarTimer(int seconds) {
        if (lastTimerSeconds <= 0 || lastTimerStartedAtMs <= 0L) return false;
        if (Math.abs(seconds - lastTimerSeconds) > DURATION_TOLERANCE_SEC) return false;
        long elapsedMs = System.currentTimeMillis() - lastTimerStartedAtMs;
        return elapsedMs >= 0 && elapsedMs < DUPLICATE_WINDOW_MS;
    }

    static void rememberTimer(int seconds) {
        lastTimerSeconds = seconds;
        lastTimerStartedAtMs = System.currentTimeMillis();
    }

    /** Visible tests. */
    static void resetForTests() {
        lastTimerSeconds = -1;
        lastTimerStartedAtMs = 0L;
    }

    /** Visible tests. */
    static void seedActiveTimerForTests(int seconds, long startedAtMs) {
        lastTimerSeconds = seconds;
        lastTimerStartedAtMs = startedAtMs;
    }

    /** « X min » pour le message anti-doublon. */
    static String formatMinutesLabel(int seconds) {
        int min = Math.max(1, (int) Math.round(seconds / 60.0));
        return min + " min";
    }

    static String formatLabel(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        if (min > 0) {
            return sec > 0
                    ? min + " min " + sec + " s"
                    : min + " minute" + (min > 1 ? "s" : "");
        }
        return seconds + " secondes";
    }
}
