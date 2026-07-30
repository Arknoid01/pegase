package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Écuries suivies pour le filtre d'actus F1 (Phase 3).
 */
public final class FavoriteTeamsStore {

    private static final String PREFS = "f1_favorite_teams";
    private static final String KEY_TEAMS = "team_ids";
    private static final String KEY_NEWS_ENABLED = "news_enabled";
    private static final String DEFAULT_CSV = "ferrari,mclaren,mercedes";

    /** Catalogue affiché dans les prefs. */
    public static final TeamDef[] CATALOG = {
            new TeamDef("ferrari", "Ferrari", "ferrari", "scuderia"),
            new TeamDef("mclaren", "McLaren", "mclaren", "mclaren racing"),
            new TeamDef("mercedes", "Mercedes", "mercedes", "mercedes-amg", "amg"),
            new TeamDef("red_bull", "Red Bull", "red bull", "redbull", "rbpt"),
            new TeamDef("williams", "Williams", "williams"),
            new TeamDef("aston_martin", "Aston Martin", "aston martin", "aston"),
            new TeamDef("alpine", "Alpine", "alpine", "bwt alpine"),
            new TeamDef("haas", "Haas", "haas", "moneygram"),
            new TeamDef("racing_bulls", "Racing Bulls", "racing bulls", "visa cash", "vcarb", "alphatauri", "alpha tauri", "toro rosso"),
            new TeamDef("sauber", "Kick Sauber", "sauber", "stake", "audi"),
    };

    private FavoriteTeamsStore() {}

    public static boolean isNewsEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_NEWS_ENABLED, true);
    }

    public static void setNewsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_NEWS_ENABLED, enabled).apply();
    }

    public static Set<String> getSelectedIds(Context ctx) {
        String raw = prefs(ctx).getString(KEY_TEAMS, DEFAULT_CSV);
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String p : raw.split(",")) {
            String id = p.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty() && find(id) != null) out.add(id);
        }
        return out;
    }

    public static void setSelectedIds(Context ctx, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            prefs(ctx).edit().putString(KEY_TEAMS, "").apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.trim().isEmpty()) continue;
            if (find(id.trim()) == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id.trim().toLowerCase(Locale.ROOT));
        }
        prefs(ctx).edit().putString(KEY_TEAMS, sb.toString()).apply();
    }

    public static void toggle(Context ctx, String teamId) {
        if (teamId == null || find(teamId) == null) return;
        Set<String> cur = new LinkedHashSet<>(getSelectedIds(ctx));
        String id = teamId.toLowerCase(Locale.ROOT);
        if (cur.contains(id)) cur.remove(id);
        else cur.add(id);
        setSelectedIds(ctx, cur);
    }

    public static boolean isSelected(Context ctx, String teamId) {
        return teamId != null && getSelectedIds(ctx).contains(teamId.toLowerCase(Locale.ROOT));
    }

    public static List<TeamDef> selectedTeams(Context ctx) {
        Set<String> ids = getSelectedIds(ctx);
        List<TeamDef> out = new ArrayList<>();
        for (TeamDef t : CATALOG) {
            if (ids.contains(t.id)) out.add(t);
        }
        return out;
    }

    public static String summaryLabel(Context ctx) {
        List<TeamDef> teams = selectedTeams(ctx);
        if (teams.isEmpty()) return "(aucune)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(teams.get(i).label);
        }
        return sb.toString();
    }

    public static TeamDef find(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (TeamDef t : CATALOG) {
            if (t.id.equals(key)) return t;
        }
        return null;
    }

    /** Alias + label pour matching titre/description. */
    public static List<String> aliasesFor(TeamDef team) {
        if (team == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        out.add(team.label.toLowerCase(Locale.ROOT));
        out.addAll(Arrays.asList(team.aliases));
        return out;
    }

    public static void resetForTests(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class TeamDef {
        public final String id;
        public final String label;
        public final String[] aliases;

        public TeamDef(String id, String label, String... aliases) {
            this.id = id;
            this.label = label;
            this.aliases = aliases != null ? aliases : new String[0];
        }
    }
}
