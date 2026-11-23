package com.pegasuscorp.orbe.search;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import com.pegasuscorp.orbe.memory.UserProfileStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Réglages de la recherche web (étape C du plan, 2026-11-23) :
 * termes privés jamais envoyés aux moteurs (Brave, Bing, Wikipédia, Tavily) + budget data.
 */
public final class ReglagesRecherche {

    private ReglagesRecherche() {}

    private static final String PREFS = "recherche_privee";
    private static final String K_TERMES = "termes_prives";

    /** Budget Wi-Fi / réseau facturé (4G) : pages lues et taille maximale par page. */
    public static final int PAGES_WIFI = 8, PAGES_4G = 4;
    public static final int OCTETS_WIFI = 600_000, OCTETS_4G = 300_000;

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Texte saisi (un terme par ligne ou séparés par des virgules). */
    public static String termesSaisis(Context ctx) {
        return prefs(ctx).getString(K_TERMES, "");
    }

    public static void setTermesSaisis(Context ctx, String texte) {
        prefs(ctx).edit().putString(K_TERMES, texte == null ? "" : texte.trim()).apply();
    }

    /** Termes saisis + prénom/nom du profil (ajoutés d'office). */
    public static List<String> termesPrives(Context ctx) {
        Set<String> out = new LinkedHashSet<>(decouper(termesSaisis(ctx)));
        try {
            String nom = UserProfileStore.getInstance(ctx).getUserName();
            if (nom != null && nom.trim().length() >= 3) {
                out.add(nom.trim());
                for (String part : nom.trim().split("\\s+")) if (part.length() >= 3) out.add(part);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(out);
    }

    static List<String> decouper(String texte) {
        List<String> out = new ArrayList<>();
        if (texte == null) return out;
        for (String t : texte.split("[,;\\n]")) {
            t = t.trim();
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    /** Réseau facturé (4G, partage de connexion…) → budget réduit. */
    public static boolean reseauFacture(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return true;   // dans le doute, économiser
        }
    }
}
