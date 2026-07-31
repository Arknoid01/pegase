package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;

/**
 * Dernier snapshot écran (a11y/OCR) pour le copilote — filtré localement avant le LLM.
 * Injecté dans {@link com.pegasuscorp.orbe.memory.ContextBuilder} uniquement sur
 * {@link com.pegasuscorp.orbe.session.Channel#COPILOT}.
 */
public final class CopilotScreenContext {

    /** Au-delà, le snapshot est considéré périmé et ignoré. */
    public static final long MAX_AGE_MS = 45_000L;
    public static final int MAX_TEXT_CHARS = 2_000;

    public static final class Snapshot {
        public final String packageName;
        public final String text;
        public final long ageMs;

        Snapshot(String packageName, String text, long ageMs) {
            this.packageName = packageName != null ? packageName : "";
            this.text = text != null ? text : "";
            this.ageMs = Math.max(0L, ageMs);
        }
    }

    private CopilotScreenContext() {}

    /**
     * Lit le dernier contexte écran s'il est frais, autorisé et non vide.
     * @return null si indisponible ou filtré
     */
    public static Snapshot readFresh(Context ctx) {
        if (ctx == null) return null;
        if (!CopilotPrefs.isScreenAnalysisEnabled(ctx)) return null;

        String pkg = ScreenContextStore.getLastPackage(ctx);
        if (!CopilotPrefs.isPackageAllowed(ctx, pkg)) return null;

        String text = ScreenContextStore.getLastText(ctx);
        if (TextUtils.isEmpty(text)) return null;
        text = text.trim();
        if (text.isEmpty()) return null;

        long ts = ScreenContextStore.getLastTimestampMs(ctx);
        if (ts <= 0L) return null;
        long ageMs = System.currentTimeMillis() - ts;
        if (ageMs > CopilotPrefs.getScreenMaxAgeMs(ctx)) return null;

        return new Snapshot(pkg, clip(text, CopilotPrefs.getScreenMaxChars(ctx)), ageMs);
    }

    /** Bloc prompt injecté dans le system message. */
    public static String buildPromptBlock(Snapshot snap) {
        if (snap == null || snap.text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- Écran actif (copilote, local) ---\n");
        if (!snap.packageName.isEmpty()) {
            sb.append("App : ").append(snap.packageName).append(".\n");
        }
        sb.append("Extrait (il y a ").append(Math.max(1L, snap.ageMs / 1000L))
                .append(" s) :\n");
        sb.append(snap.text).append("\n");
        sb.append("Ce texte vient de l'accessibilité/OCR local — pas d'image envoyée.\n");
        sb.append("Pour cliquer / expliquer / chercher : appelle ui_action, ui_explain ou ")
                .append("ui_search avec target = un libellé visible ci-dessus ")
                .append("(ex. Astronomie et espace). Le matching est local sur l'appareil. ")
                .append("Si hésitation, réessaie avec un autre libellé — ")
                .append("n'interroge pas l'utilisateur, agis.\n");
        return sb.toString();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
