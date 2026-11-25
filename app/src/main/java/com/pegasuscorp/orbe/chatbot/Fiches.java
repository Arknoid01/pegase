package com.pegasuscorp.orbe.chatbot;

import com.pegasuscorp.orbe.search.TexteFr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Mémoire rangée : fiches sur Yannick, ses proches, ses projets (données perso, poussées à part
 * dans Android/data/…/files/chatbot/fiches.json, jamais dans le dépôt).
 */
public final class Fiches {

    public static final class Entite {
        public final String id, type, nom, role, etat, suite;
        public final List<String> faits;

        Entite(JSONObject o) {
            id = o.optString("id");
            type = o.optString("type");
            nom = o.optString("nom");
            role = o.optString("role", "");
            etat = o.optString("etat", "");
            suite = o.optString("suite", "");
            faits = strings(o.optJSONArray("faits"));
        }

        /** Phrases de fait, prêtes à servir d'idées de réponse. */
        public List<String> facts() {
            List<String> out = new ArrayList<>();
            if (!role.isEmpty()) out.add(nom + ", " + role);
            if (!etat.isEmpty()) out.add(nom + " : " + etat);
            if (!suite.isEmpty()) out.add("prochaine étape : " + suite);
            out.addAll(faits);
            return out;
        }
    }

    public final JSONObject moi;
    public final List<Entite> entites = new ArrayList<>();
    private final List<Pattern> aliasPats = new ArrayList<>();
    private final List<Entite> aliasEnt = new ArrayList<>();

    public Fiches(JSONObject data) {
        moi = data.optJSONObject("moi") != null ? data.optJSONObject("moi") : new JSONObject();
        JSONArray arr = data.optJSONArray("entites");
        List<String[]> pairs = new ArrayList<>();   // alias, index
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entite e = new Entite(o);
                entites.add(e);
                List<String> al = strings(o.optJSONArray("alias"));
                al.add(e.nom);
                for (String a : al) {
                    if (a == null || a.trim().isEmpty()) continue;
                    pairs.add(new String[]{Normalizer.stripAccents(a.toLowerCase(Locale.FRENCH)),
                            String.valueOf(entites.size() - 1)});
                }
            }
        }
        // Du plus long au plus court (« mon chien » avant « chien »).
        pairs.sort((x, y) -> y[0].length() - x[0].length());
        for (String[] p : pairs) {
            aliasPats.add(Pattern.compile("(?<!\\w)" + Pattern.quote(p[0]) + "(?!\\w)", TexteFr.U));
            aliasEnt.add(entites.get(Integer.parseInt(p[1])));
        }
    }

    /** Entités citées dans le message (texte sans accents). */
    public List<Entite> find(String key) {
        List<Entite> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < aliasPats.size(); i++) {
            Entite e = aliasEnt.get(i);
            if (!seen.contains(e.id) && aliasPats.get(i).matcher(key).find()) {
                found.add(e);
                seen.add(e.id);
            }
        }
        return found;
    }

    public String prenom() {
        return moi.optString("prenom", "");
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s != null) out.add(s);
        }
        return out;
    }
}
