package com.pegasuscorp.orbe.tools.device;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;
import com.pegasuscorp.orbe.tools.life.DurationParser;

import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Minuteur Android — durée, libellé, liste/historique local.
 */
public final class TimerTool implements Tool {

    static final int DURATION_TOLERANCE_SEC = 60;
    static final long DUPLICATE_WINDOW_MS = 60_000L;

    private static volatile int lastTimerSeconds = -1;
    private static volatile long lastTimerStartedAtMs = 0L;

    @Override public String id() { return "timer"; }

    @Override public ToolTag tag() { return ToolTag.TIMER; }

    @Override
    public String description() {
        return "timer(action:\"start\"|\"list\"|\"recent\"|\"open\", seconds?:int, minutes?:int, "
                + "duration?:str, when?:str, label?:str, confirm?:bool) — Minuteur. "
                + "duration/when « 5 minutes » / « 1h30 » ; list/open/recent.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        if (params == null) params = new JSONObject();
        String action = params.optString("action", "start").trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty() || "add".equals(action) || "set".equals(action)) action = "start";

        if ("list".equals(action) || "show".equals(action) || "open".equals(action)) {
            openTimers(ctx, cb);
            return;
        }
        if ("recent".equals(action) || "history".equals(action)) {
            cb.onSuccessAndExit(ToolResult.text(
                    UtilityScheduleStore.get(ctx).formatRecentTimers()));
            return;
        }
        if (!"start".equals(action)) {
            cb.onError("Action minuteur inconnue. Utilise start, list ou recent.");
            return;
        }

        int seconds = resolveSeconds(params);
        if (seconds <= 0) {
            cb.onError("Précise la durée (seconds, minutes, ou duration « 5 minutes »).");
            return;
        }
        boolean confirmReplace = params.optBoolean("confirm", false);
        if (!confirmReplace && isRecentSimilarTimer(seconds)) {
            cb.onSuccess(ToolResult.text(
                    "Un minuteur de " + formatMinutesLabel(lastTimerSeconds)
                            + " tourne déjà. Tu veux le remplacer ?"));
            return;
        }

        String label = firstNonEmpty(params, "label", "message", "name", "titre");
        launchTimer(ctx, seconds, label, cb);
    }

    static int resolveSeconds(JSONObject params) {
        int seconds = params.optInt("seconds", 0);
        if (seconds > 0) return seconds;
        int minutes = params.optInt("minutes", 0);
        if (minutes > 0) return minutes * 60;
        String raw = firstNonEmpty(params, "duration", "when", "duree", "durée");
        int parsed = DurationParser.parseToSeconds(raw);
        return parsed;
    }

    private static void launchTimer(Context ctx, int seconds, String label, ToolCallback cb) {
        // Toujours planifier un minuteur Pégase (exact + notif HIGH) —
        // ACTION_SET_TIMER via startActivity échoue souvent écran verrouillé / Doze.
        PegaseTimerScheduler.cancel(ctx);
        long fireAt = PegaseTimerScheduler.schedule(ctx, seconds, label);
        if (fireAt <= 0L) {
            cb.onError("Impossible de planifier le minuteur (alarme exacte refusée).");
            return;
        }

        // Bonus : aussi pousser vers l'app Horloge si déverrouillé (UI système).
        boolean locked = PegaseTimerReceiver.isKeyguardLocked(ctx);
        if (!locked) {
            trySystemClockTimer(ctx, seconds, label);
        }

        long now = System.currentTimeMillis();
        rememberTimer(seconds);
        UtilityScheduleStore.get(ctx).recordTimer(seconds, label, now);
        String spoken = "Minuteur de " + formatLabel(seconds);
        if (!TextUtils.isEmpty(label)) spoken += " — " + label;
        spoken += " lancé.";
        if (locked) {
            spoken += " Je te préviendrai même écran verrouillé.";
        }
        if (PegaseTimerReceiver.isDndBlockingAlarms(ctx)) {
            spoken += " Attention : le mode Ne pas déranger bloque les alarmes.";
        }
        cb.onSuccessAndExit(ToolResult.text(spoken));
    }

    /** Best-effort Horloge système (peut échouer en arrière-plan). */
    private static void trySystemClockTimer(Context ctx, int seconds, String label) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!TextUtils.isEmpty(label)) {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        }
        try {
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    private static void openTimers(Context ctx, ToolCallback cb) {
        Intent show = new Intent(AlarmClock.ACTION_SHOW_TIMERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (show.resolveActivity(ctx.getPackageManager()) != null) {
            ctx.startActivity(show);
            cb.onSuccessAndExit(ToolResult.text(
                    "J'ouvre les minuteurs.\n"
                            + UtilityScheduleStore.get(ctx).formatRecentTimers()));
            return;
        }
        openClockAlarmsFallback(ctx, cb);
    }

    private static void openClockAlarmsFallback(Context ctx, ToolCallback cb) {
        Intent fallback = ctx.getPackageManager()
                .getLaunchIntentForPackage("com.google.android.deskclock");
        if (fallback == null) {
            fallback = ctx.getPackageManager()
                    .getLaunchIntentForPackage("com.android.deskclock");
        }
        if (fallback != null) {
            ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            cb.onSuccessAndExit(ToolResult.text("J'ouvre l'app Horloge (minuteurs)."));
            return;
        }
        cb.onError("Aucune app Horloge trouvée.");
    }

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

    static void resetForTests() {
        lastTimerSeconds = -1;
        lastTimerStartedAtMs = 0L;
    }

    static void seedActiveTimerForTests(int seconds, long startedAtMs) {
        lastTimerSeconds = seconds;
        lastTimerStartedAtMs = startedAtMs;
    }

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

    private static String firstNonEmpty(JSONObject params, String... keys) {
        for (String k : keys) {
            String v = params.optString(k, "").trim();
            if (!v.isEmpty()) return v;
        }
        return "";
    }
}
