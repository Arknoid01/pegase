package com.pegasuscorp.orbe.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Outils désactivés par l'utilisateur (onglet Outils → ⋮). Un outil désactivé n'est plus proposé
 * au modèle et son exécution est refusée, quelle que soit l'origine de l'appel (voix, texte, lot).
 */
public final class ToolToggles {

    private static final String PREFS = "pegase_tool_toggles";
    private static final String KEY = "disabled";

    private static volatile Set<String> disabled = Collections.emptySet();

    private ToolToggles() {}

    public static boolean isDisabled(String toolId) {
        return toolId != null && disabled.contains(toolId.trim().toLowerCase(Locale.ROOT));
    }

    /** Recharge l'instantané depuis les préférences (démarrage, puis après chaque changement). */
    public static void reload(Context ctx) {
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        disabled = Collections.unmodifiableSet(new HashSet<>(p.getStringSet(KEY, new HashSet<>())));
    }

    public static void setDisabled(Context ctx, String toolId, boolean off) {
        if (toolId == null) return;
        SharedPreferences p = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> ids = new HashSet<>(p.getStringSet(KEY, new HashSet<>()));
        String key = toolId.trim().toLowerCase(Locale.ROOT);
        if (off) ids.add(key); else ids.remove(key);
        p.edit().putStringSet(KEY, ids).apply();
        disabled = Collections.unmodifiableSet(ids);
    }
}
