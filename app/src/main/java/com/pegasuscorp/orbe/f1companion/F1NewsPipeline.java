package com.pegasuscorp.orbe.f1companion;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Poll RSS → filtre équipes → seed silencieux → article candidat pour Intention.
 */
public final class F1NewsPipeline {

    private static final String TAG = "F1NewsPipeline";

    private F1NewsPipeline() {}

    /**
     * Cherche le meilleur article nouveau pour les équipes suivies.
     * Premier poll : seed silencieux (pas de notif).
     * Ne marque pas l'article vu — à faire après notif réussie.
     */
    public static F1NewsFilter.Match findBestUnseen(Context ctx) {
        if (ctx == null) return null;
        Context app = ctx.getApplicationContext();
        try {
            if (!FavoriteTeamsStore.isNewsEnabled(app)) return null;
            List<FavoriteTeamsStore.TeamDef> favorites = FavoriteTeamsStore.selectedTeams(app);
            if (favorites.isEmpty()) return null;

            List<F1RssItem> items = F1RssFetcher.fetchAll();
            F1NewsStore.setLastPollMs(app, System.currentTimeMillis());
            if (items.isEmpty()) return null;

            if (!F1NewsStore.isSeeded(app)) {
                List<String> ids = new ArrayList<>();
                for (F1RssItem it : items) ids.add(it.id());
                F1NewsStore.markSeenAll(app, ids);
                Log.i(TAG, "Seed " + ids.size() + " articles (silent)");
                return null;
            }

            if (F1NewsStore.hasPending(app)) return null;

            List<F1NewsFilter.Match> matches = F1NewsFilter.filter(items, favorites);
            for (F1NewsFilter.Match m : matches) {
                if (F1NewsStore.hasSeen(app, m.item.id())) continue;
                return m;
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "findBestUnseen", e);
            return null;
        }
    }

    /** Prépare le pending (summary) juste avant la notif. */
    public static void preparePending(Context ctx, F1NewsFilter.Match match) {
        if (ctx == null || match == null || match.item == null) return;
        Context app = ctx.getApplicationContext();
        String summary = F1NewsSummarizer.summarize(app, match.item, match.primaryTeamLabel());
        F1NewsStore.setPending(app, match.item.id(), match.item.title, summary,
                match.item.link, match.primaryTeamLabel());
    }

    /** Phrase chat après « En parler ». */
    public static String discussPhrase(Context ctx) {
        String team = F1NewsStore.getPendingTeam(ctx);
        String summary = F1NewsStore.getPendingSummary(ctx);
        if (summary == null || summary.isEmpty()) summary = F1NewsStore.getPendingTitle(ctx);
        if (summary == null || summary.isEmpty()) {
            return "Qu’est-ce que tu penses de la dernière actu F1 ?";
        }
        String prefix = (team != null && !team.isEmpty()) ? ("Actu " + team + " : ") : "Actu F1 : ";
        return prefix + summary + " Tu en penses quoi ?";
    }
}
