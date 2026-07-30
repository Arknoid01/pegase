package com.pegasuscorp.orbe.diag;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Dernier lancement des mini-tests — survit à la fermeture de l'écran réglages.
 */
public final class DiagScriptRunStore {

    private static final String PREFS = "diag_script_runs";

    private DiagScriptRunStore() {}

    public static void markStarted(Context ctx) {
        prefs(ctx).edit()
                .putString("state", "running")
                .putLong("started_at", System.currentTimeMillis())
                .putLong("finished_at", 0L)
                .putString("summary", "En cours…")
                .putInt("step_index", 0)
                .putInt("step_total", DiagScripts.miniSuite().size())
                .putString("step_label", "")
                .putString("step_phase", "démarrage")
                .apply();
    }

    public static void markProgress(Context ctx, int index, int total, String label, String phase) {
        SharedPreferences.Editor ed = prefs(ctx).edit()
                .putInt("step_index", index)
                .putInt("step_total", total)
                .putString("step_phase", phase != null ? phase : "");
        if (label != null && !label.isEmpty()) {
            ed.putString("step_label", label);
        }
        ed.apply();
    }

    public static void markFinished(Context ctx, DiagScriptResult result) {
        String summary = result != null ? result.summaryLine() : "Terminé";
        prefs(ctx).edit()
                .putString("state", result != null && result.clean ? "clean" : "issues")
                .putLong("finished_at", System.currentTimeMillis())
                .putString("summary", summary)
                .putString("step_phase", "terminé")
                .apply();
    }

    public static void markCancelled(Context ctx) {
        prefs(ctx).edit()
                .putString("state", "cancelled")
                .putLong("finished_at", System.currentTimeMillis())
                .putString("summary", "Annulé")
                .putString("step_phase", "annulé")
                .apply();
    }

    public static void markBlocked(Context ctx, String reason) {
        prefs(ctx).edit()
                .putString("state", "blocked")
                .putLong("finished_at", System.currentTimeMillis())
                .putString("summary", reason != null ? reason : "Impossible de démarrer")
                .apply();
    }

    public static boolean isRunning(Context ctx) {
        return "running".equals(prefs(ctx).getString("state", ""));
    }

    /** Corrige un état « running » orphelin après fermeture app ou crash. */
    public static void syncRunning(Context ctx, boolean actuallyRunning) {
        if (!actuallyRunning && isRunning(ctx)) {
            markCancelled(ctx);
        }
    }

    /** Ligne affichée sous le bouton mini-tests. */
    public static String statusLine(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String state = p.getString("state", "");
        if (state.isEmpty()) {
            return "Prêt — appuie pour lancer une trace propre à analyser.";
        }
        if ("running".equals(state)) {
            int idx = p.getInt("step_index", 0) + 1;
            int total = p.getInt("step_total", DiagScripts.miniSuite().size());
            String label = p.getString("step_label", "");
            String phase = p.getString("step_phase", "");
            long started = p.getLong("started_at", 0L);
            String ago = started > 0 ? " · démarré il y a " + formatAgo(System.currentTimeMillis() - started)
                    : "";
            if (!label.isEmpty()) {
                return "▶ EN COURS — test " + idx + "/" + total + " · " + label
                        + " · " + phase + ago;
            }
            return "▶ EN COURS — " + phase + ago;
        }
        long finished = p.getLong("finished_at", 0L);
        String when = finished > 0 ? " (" + formatAgo(System.currentTimeMillis() - finished) + ")" : "";
        String summary = p.getString("summary", "");
        if ("clean".equals(state)) {
            return "✓ Dernier run propre" + when + " — " + summary;
        }
        if ("issues".equals(state)) {
            return "⚠ Dernier run avec problèmes" + when + " — " + summary;
        }
        if ("cancelled".equals(state)) {
            return "Run annulé" + when + " — relance si besoin";
        }
        if ("blocked".equals(state)) {
            return summary;
        }
        return summary;
    }

    private static String formatAgo(long deltaMs) {
        if (deltaMs < 60_000L) return (deltaMs / 1000L) + " s";
        if (deltaMs < 3_600_000L) return (deltaMs / 60_000L) + " min";
        return (deltaMs / 3_600_000L) + " h";
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
