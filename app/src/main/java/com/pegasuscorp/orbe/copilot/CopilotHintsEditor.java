package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.AppListCache;
import com.pegasuscorp.orbe.memory.MemoryEditResult;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voix : « retiens que sur WhatsApp… » → {@link CopilotAppHintsStore}.
 * Intercepté avant {@link com.pegasuscorp.orbe.memory.MemoryEditor}.
 */
public final class CopilotHintsEditor {

    /**
     * retiens/note/souviens-toi [que] sur|pour|dans APP [que] HINT
     */
    private static final Pattern RETIENS_SUR = Pattern.compile(
            "(?i)^(?:retiens|souviens[- ]toi|note)(?:\\s+que)?\\s+"
                    + "(?:sur|pour|dans)\\s+(.+?)\\s+(?:que\\s+)?(.+)$");

    /** sur APP, retiens/note [que] HINT */
    private static final Pattern SUR_RETIENS = Pattern.compile(
            "(?i)^sur\\s+(.+?)\\s*[,:]?\\s+"
                    + "(?:retiens|souviens[- ]toi|note)(?:\\s+que)?\\s+(.+)$");

    /** X veut dire / = / c'est Y */
    private static final Pattern ALIAS = Pattern.compile(
            "(?i)^(?:quand\\s+je\\s+(?:dis|dit)\\s+)?(.+?)\\s+"
                    + "(?:veut\\s+dire|voudrait\\s+dire|=|c['']est|ca\\s+veut\\s+dire)\\s+(.+)$");

    private CopilotHintsEditor() {}

    public static boolean looksLikeHintsEdit(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        if (RETIENS_SUR.matcher(t).matches()) return true;
        if (SUR_RETIENS.matcher(t).matches()) return true;
        String fold = SpeechInputNormalizer.fold(t);
        // « retiens sur whatsapp que… » sans « que » après retiens
        return fold.startsWith("retiens sur ")
                || fold.startsWith("retiens pour ")
                || fold.startsWith("retiens dans ")
                || fold.startsWith("note sur ")
                || fold.startsWith("souviens toi sur ")
                || fold.startsWith("souviens-toi sur ");
    }

    public static MemoryEditResult process(Context ctx, String userText) {
        if (ctx == null || TextUtils.isEmpty(userText)) {
            return MemoryEditResult.failed("Je n'ai pas compris le hint.");
        }
        String t = userText.trim();
        Matcher m = RETIENS_SUR.matcher(t);
        if (!m.matches()) {
            m = SUR_RETIENS.matcher(t);
        }
        if (!m.matches()) {
            return MemoryEditResult.notMemoryEdit();
        }
        String appRaw = m.group(1).trim();
        String hintRaw = m.group(2).trim();
        if (appRaw.isEmpty() || hintRaw.isEmpty()) {
            return MemoryEditResult.failed("Dis l'app et le hint, "
                    + "par exemple : retiens que sur WhatsApp le contact Marie "
                    + "c'est le chat épinglé.");
        }

        ResolvedApp app = resolveApp(ctx, appRaw);
        if (app == null) {
            return MemoryEditResult.failed(
                    "Je ne connais pas l'app « " + appRaw + " ». "
                            + "Dis WhatsApp, Brave, Reddit, Chrome…");
        }

        Matcher alias = ALIAS.matcher(hintRaw);
        if (alias.matches()) {
            String from = cleanAliasToken(alias.group(1));
            String to = cleanAliasToken(alias.group(2));
            String foldHintAlias = SpeechInputNormalizer.fold(hintRaw);
            boolean strongForm = foldHintAlias.contains("veut dire")
                    || foldHintAlias.contains("voudrait dire")
                    || foldHintAlias.contains("quand je dis")
                    || foldHintAlias.contains("quand je dit")
                    || hintRaw.contains("=");
            int fromWords = countWords(from);
            int toWords = countWords(to);
            // « le contact Marie c'est le chat épinglé » = note, pas alias.
            boolean shortPair = fromWords > 0 && fromWords <= 2 && toWords <= 4
                    && from.length() <= 32;
            if (!from.isEmpty() && !to.isEmpty() && (strongForm || shortPair)) {
                CopilotAppHintsStore.setAlias(ctx, app.packageName, from, to);
                return MemoryEditResult.applied(
                        app.label + " : « " + from + " » → « " + to + " »",
                        "OK, sur " + app.label + ", « " + from + " » voudra dire « "
                                + to + " ».");
            }
        }

        String foldHint = SpeechInputNormalizer.fold(hintRaw);
        if (looksLikeStrictFlag(foldHint)) {
            boolean on = !foldHint.contains("pas strict") && !foldHint.contains("moins strict");
            CopilotAppHintsStore.setStrictTextMatch(ctx, app.packageName, on);
            return MemoryEditResult.applied(
                    app.label + " : matching " + (on ? "strict" : "souple"),
                    on ? "OK, matching strict sur " + app.label + "."
                            : "OK, matching plus souple sur " + app.label + ".");
        }
        if (looksLikeGestureFlag(foldHint)) {
            CopilotAppHintsStore.setDistrustA11yClick(ctx, app.packageName, true);
            return MemoryEditResult.applied(
                    app.label + " : gesture prioritaire",
                    "OK, sur " + app.label + " je privilégierai le geste tactile "
                            + "plutôt que le clic accessibilité.");
        }

        CopilotAppHintsStore.addNote(ctx, app.packageName, hintRaw);
        String shortHint = hintRaw.length() > 60
                ? hintRaw.substring(0, 57) + "…"
                : hintRaw;
        return MemoryEditResult.applied(
                app.label + " : " + shortHint,
                "C'est noté pour " + app.label + ".");
    }

    private static boolean looksLikeStrictFlag(String fold) {
        return fold.contains("matching strict")
                || fold.contains("match strict")
                || fold.contains("sois strict")
                || fold.contains("etre strict")
                || fold.contains("être strict")
                || fold.contains("libellé exact")
                || fold.contains("libelle exact")
                || fold.equals("strict")
                || fold.contains("pas strict")
                || fold.contains("moins strict");
    }

    private static boolean looksLikeGestureFlag(String fold) {
        return fold.contains("gesture")
                || fold.contains("geste")
                || fold.contains("pas d a11y")
                || fold.contains("pas da11y")
                || fold.contains("pas accessibilite")
                || fold.contains("pas accessibilité")
                || fold.contains("webview")
                || fold.contains("compose")
                || fold.contains("clic fantome")
                || fold.contains("clic fantôme")
                || fold.contains("succes fantome")
                || fold.contains("succès fantôme");
    }

    private static String cleanAliasToken(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if ((t.startsWith("«") && t.endsWith("»"))
                || (t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static int countWords(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        return s.trim().split("\\s+").length;
    }

    static final class ResolvedApp {
        final String label;
        final String packageName;

        ResolvedApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    static ResolvedApp resolveApp(Context ctx, String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String fold = SpeechInputNormalizer.fold(raw)
                .replace(" l application ", " ")
                .replace(" application ", " ")
                .replace(" l app ", " ")
                .replace(" app ", " ")
                .trim();
        if (fold.isEmpty()) return null;

        // Presets copilote (même vocabulaire que les réglages).
        String[][] presets = {
                {"youtube", CopilotPrefs.PKG_YOUTUBE, "YouTube"},
                {"brave", "com.brave.browser", "Brave"},
                {"chrome", "com.android.chrome", "Chrome"},
                {"firefox", "org.mozilla.firefox", "Firefox"},
                {"whatsapp", "com.whatsapp", "WhatsApp"},
                {"whats app", "com.whatsapp", "WhatsApp"},
                {"reddit", "com.reddit.frontpage", "Reddit"},
                {"gmail", "com.google.android.gm", "Gmail"},
                {"telegram", "org.telegram.messenger", "Telegram"},
                {"messages", "com.google.android.apps.messaging", "Messages"},
                {"slack", "com.Slack", "Slack"},
                {"cursor", "com.android.chrome", "Cursor (navigateur)"},
        };
        for (String[] p : presets) {
            if (fold.equals(p[0]) || fold.startsWith(p[0] + " ")) {
                return new ResolvedApp(p[2], p[1]);
            }
        }

        java.util.List<AppListCache.AppEntry> apps = AppListCache.getCached();
        if (apps != null) {
            AppListCache.AppEntry best = null;
            for (AppListCache.AppEntry e : apps) {
                if (e == null || e.label == null) continue;
                String lf = SpeechInputNormalizer.fold(e.label);
                if (lf.equals(fold)) {
                    return new ResolvedApp(e.label, e.pkg);
                }
                if (best == null && (lf.startsWith(fold) || fold.startsWith(lf))) {
                    best = e;
                }
            }
            if (best != null) {
                return new ResolvedApp(best.label, best.pkg);
            }
        }
        return null;
    }
}
