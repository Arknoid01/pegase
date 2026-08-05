package com.pegasuscorp.orbe.copilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Store hints a11y par app : seeds embarqués + overrides utilisateur (SharedPreferences).
 * Thin slice v5.5 — apprentissage manuel / futur « retiens que… ».
 */
public final class CopilotAppHintsStore {

    private static final String PREFS = "copilot_app_hints";
    private static final String KEY_OVERRIDES = "overrides_json";

    private CopilotAppHintsStore() {}

    public static CopilotAppHints get(Context ctx, String packageName) {
        if (TextUtils.isEmpty(packageName)) return CopilotAppHints.empty("");
        CopilotAppHints seed = builtin(packageName);
        CopilotAppHints override = readOverride(ctx, packageName);
        if (override == null || override.isEmpty()) return seed;
        if (seed.isEmpty()) return override;
        // Override gagne sur les flags ; notes/aliases fusionnés.
        return override.mergeOver(seed);
    }

    /** Ajoute une note utilisateur (persistée). Plafond + dédup. */
    public static final int MAX_NOTES = 12;

    public static void addNote(Context ctx, String packageName, String note) {
        if (ctx == null || TextUtils.isEmpty(packageName) || TextUtils.isEmpty(note)) return;
        CopilotAppHints cur = getOverrideOrEmpty(ctx, packageName);
        List<String> notes = new ArrayList<>(cur.notes);
        String n = note.trim();
        if (notes.contains(n)) return;
        notes.add(n);
        while (notes.size() > MAX_NOTES) {
            notes.remove(0);
        }
        saveOverride(ctx, new CopilotAppHints(packageName, notes, cur.aliases,
                cur.preferGesture, cur.preferA11yFirst,
                cur.strictTextMatch, cur.distrustA11yClickSuccess));
    }

    /** Enregistre un alias « dit » → « libellé écran ». */
    public static void setAlias(Context ctx, String packageName, String from, String to) {
        if (ctx == null || TextUtils.isEmpty(packageName)
                || TextUtils.isEmpty(from) || TextUtils.isEmpty(to)) return;
        CopilotAppHints cur = getOverrideOrEmpty(ctx, packageName);
        Map<String, String> aliases = new LinkedHashMap<>(cur.aliases);
        aliases.put(CopilotAppHints.foldKey(from), to.trim());
        saveOverride(ctx, new CopilotAppHints(packageName, cur.notes, aliases,
                cur.preferGesture, cur.preferA11yFirst,
                cur.strictTextMatch, cur.distrustA11yClickSuccess));
    }

    /** Active/coupe le matching strict pour une app. */
    public static void setStrictTextMatch(Context ctx, String packageName, boolean on) {
        if (ctx == null || TextUtils.isEmpty(packageName)) return;
        CopilotAppHints cur = getOverrideOrEmpty(ctx, packageName);
        saveOverride(ctx, new CopilotAppHints(packageName, cur.notes, cur.aliases,
                cur.preferGesture, cur.preferA11yFirst,
                on, cur.distrustA11yClickSuccess));
    }

    /** Gesture only / distrust a11y click success (WebView, Compose). */
    public static void setDistrustA11yClick(Context ctx, String packageName, boolean on) {
        if (ctx == null || TextUtils.isEmpty(packageName)) return;
        CopilotAppHints cur = getOverrideOrEmpty(ctx, packageName);
        saveOverride(ctx, new CopilotAppHints(packageName, cur.notes, cur.aliases,
                cur.preferGesture, cur.preferA11yFirst,
                cur.strictTextMatch, on));
    }

    static CopilotAppHints builtin(String packageName) {
        if (packageName == null) return CopilotAppHints.empty("");
        switch (packageName) {
            case "com.whatsapp":
            case "com.whatsapp.w4b":
                return whatsApp(packageName);
            case "com.reddit.frontpage":
            case "com.reddit.android":
                return reddit(packageName);
            case "com.brave.browser":
            case "com.android.chrome":
            case "org.mozilla.firefox":
                return browser(packageName);
            default:
                return CopilotAppHints.empty(packageName);
        }
    }

    private static CopilotAppHints whatsApp(String pkg) {
        List<String> notes = new ArrayList<>();
        notes.add("Liste de chats : cible le nom du contact exact (pas un extrait du dernier message).");
        notes.add("Dans une conversation, « envoyer » = bouton d'envoi ; tape d'abord le champ message.");
        notes.add("Évite les cibles trop courtes (1–2 lettres) — faux positifs fréquents.");
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(CopilotAppHints.foldKey("envoyer"), "Envoyer");
        aliases.put(CopilotAppHints.foldKey("nouvelle discussion"), "Nouvelle discussion");
        aliases.put(CopilotAppHints.foldKey("nouveau message"), "Nouvelle discussion");
        aliases.put(CopilotAppHints.foldKey("recherche"), "Rechercher");
        return new CopilotAppHints(pkg, notes, aliases,
                true, false, true, false);
    }

    private static CopilotAppHints reddit(String pkg) {
        List<String> notes = new ArrayList<>();
        notes.add("UI Compose : ACTION_CLICK a11y souvent sans effet — gesture prioritaire.");
        notes.add("Préfère les libellés visibles (Upvote, Comment, Share, Join) plutôt qu'un titre de post long.");
        return new CopilotAppHints(pkg, notes, Collections.emptyMap(),
                true, false, false, true);
    }

    private static CopilotAppHints browser(String pkg) {
        List<String> notes = new ArrayList<>();
        notes.add("WebView : succès a11y souvent fantôme — gesture sur le contenu web.");
        notes.add("Pour chercher / coller une URL : cible la barre d'adresse (omnibox), pas le contenu.");
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(CopilotAppHints.foldKey("barre d'adresse"), "barre d'adresse");
        aliases.put(CopilotAppHints.foldKey("omnibox"), "barre d'adresse");
        aliases.put(CopilotAppHints.foldKey("rechercher"), "barre d'adresse");
        return new CopilotAppHints(pkg, notes, aliases,
                true, false, false, true);
    }

    private static CopilotAppHints getOverrideOrEmpty(Context ctx, String packageName) {
        CopilotAppHints o = readOverride(ctx, packageName);
        return o != null ? o : CopilotAppHints.empty(packageName);
    }

    private static CopilotAppHints readOverride(Context ctx, String packageName) {
        if (ctx == null || TextUtils.isEmpty(packageName)) return null;
        String raw = prefs(ctx).getString(KEY_OVERRIDES, "{}");
        try {
            JSONObject root = new JSONObject(raw != null ? raw : "{}");
            JSONObject o = root.optJSONObject(packageName);
            if (o == null) return null;
            return CopilotAppHints.fromJson(packageName, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveOverride(Context ctx, CopilotAppHints hints) {
        if (ctx == null || hints == null || TextUtils.isEmpty(hints.packageName)) return;
        try {
            String raw = prefs(ctx).getString(KEY_OVERRIDES, "{}");
            JSONObject root = new JSONObject(raw != null ? raw : "{}");
            root.put(hints.packageName, hints.toJson());
            prefs(ctx).edit().putString(KEY_OVERRIDES, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
