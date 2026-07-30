package com.pegasuscorp.orbe.f1companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filtre équipes favorites + score d'importance (pénalité, upgrade, abandon…).
 */
public final class F1NewsFilter {

    private static final String[] BOOST = {
            "penalty", "pénalité", "penalised", "penalized",
            "upgrade", "évolution", "evolution", "update",
            "crash", "accident", "collision", "abandon", "retired", "retirement",
            "pole", "win", "victory", "victoire", "champion",
            "disqualif", "banned", "suspend",
            "safety car", "red flag", "drapeau rouge",
            "contract", "contrat", "signing", "signs",
            "fastest lap", "meilleur tour",
    };

    private F1NewsFilter() {}

    public static final class Match {
        public final F1RssItem item;
        public final List<FavoriteTeamsStore.TeamDef> teams;
        public final int score;

        Match(F1RssItem item, List<FavoriteTeamsStore.TeamDef> teams, int score) {
            this.item = item;
            this.teams = teams;
            this.score = score;
        }

        public String primaryTeamLabel() {
            return teams.isEmpty() ? "F1" : teams.get(0).label;
        }
    }

    public static List<Match> filter(List<F1RssItem> items, List<FavoriteTeamsStore.TeamDef> favorites) {
        List<Match> out = new ArrayList<>();
        if (items == null || favorites == null || favorites.isEmpty()) return out;
        for (F1RssItem item : items) {
            if (item == null) continue;
            List<FavoriteTeamsStore.TeamDef> hit = matchingTeams(item, favorites);
            if (hit.isEmpty()) continue;
            out.add(new Match(item, hit, score(item, hit)));
        }
        out.sort((a, b) -> Integer.compare(b.score, a.score));
        return out;
    }

    static List<FavoriteTeamsStore.TeamDef> matchingTeams(
            F1RssItem item, List<FavoriteTeamsStore.TeamDef> favorites) {
        List<FavoriteTeamsStore.TeamDef> hit = new ArrayList<>();
        String hay = item.haystack();
        for (FavoriteTeamsStore.TeamDef team : favorites) {
            for (String alias : FavoriteTeamsStore.aliasesFor(team)) {
                if (alias == null || alias.length() < 3) continue;
                if (hay.contains(alias.toLowerCase(Locale.ROOT))) {
                    hit.add(team);
                    break;
                }
            }
        }
        return hit;
    }

    static int score(F1RssItem item, List<FavoriteTeamsStore.TeamDef> teams) {
        int s = 10 + teams.size() * 5;
        String hay = item.haystack();
        for (String k : BOOST) {
            if (hay.contains(k)) s += 8;
        }
        // Titre court et clair = un peu mieux pour notif
        if (item.title.length() > 20 && item.title.length() < 120) s += 2;
        return s;
    }
}
