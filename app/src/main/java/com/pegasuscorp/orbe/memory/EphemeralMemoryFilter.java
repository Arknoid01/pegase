package com.pegasuscorp.orbe.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Distingue bruit de tâche (UI / intention éphémère) et souvenirs durables.
 *
 * <p>Le filtre phrase/heuristique est une <b>défense secondaire</b> : le robinet
 * principal est de ne pas écrire les résultats {@code ui_*} dans
 * {@code sessionTurns} / {@code recent_turns} (voir {@link com.pegasuscorp.orbe.chat.ConversationManager}).
 *
 * <p>Pour la promotion « pending », on utilise une <b>liste blanche</b>
 * ({@link #isDurablePending}) — radicaux / regex, pas des expressions figées.
 */
public final class EphemeralMemoryFilter {

    /**
     * Intention de rappel / report — radicaux FR (pensez, penser, prenne, oublié…).
     * Word-boundary light : pas collé à une lettre avant/après le radical.
     */
    private static final Pattern INTENT_STEM = Pattern.compile(
            "(^|[^a-z])("
                    + "rappel\\w*"
                    + "|pens\\w*"          // pense, penser, pensez, pensais…
                    + "|prend\\w*|prenn\\w*|pris\\b"  // prendre, prenne, pris
                    + "|oubli\\w*"         // oublie, oublié, oubliez…
                    + "|faudr\\w*"         // faudra (pas « faut » seul → trop large)
                    + "|prevoi\\w*|prevoir\\b"
                    + "|a\\s+faire|a-faire"
                    + "|on\\s+en\\s+reparl\\w*"
                    + "|a\\s+suivre|a-suivre"
                    + "|quand\\s+tu\\s+pourr\\w*"
                    + "|plus\\s+tard"
                    + "|prochaine\\s+fois"
                    + "|semaine\\s+prochaine"
                    + "|apres\\s+le\\s+repas"
                    + "|on\\s+verra"
                    + ")([^a-z]|$)",
            Pattern.CASE_INSENSITIVE);

    /** Ancre temporelle seule trop large (météo « pour demain »). */
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "(^|[^a-z])("
                    + "demain"
                    + "|ce\\s+soir"
                    + "|cette\\s+semaine"
                    + "|dans\\s+une\\s+heure"
                    + "|dans\\s+1\\s+heure"
                    + "|apres[- ]?demain"
                    + ")([^a-z]|$)",
            Pattern.CASE_INSENSITIVE);

    private EphemeralMemoryFilter() {}

    /** Outils copilote UI — leurs textes ne doivent pas alimenter le résumé / permanent. */
    public static boolean isUiToolId(String toolId) {
        if (toolId == null) return false;
        String id = toolId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return false;
        return id.equals("ui_action")
                || id.equals("ui_explain")
                || id.equals("ui_search")
                || id.equals("screen_capture")
                || id.equals("copilot_action")
                || id.startsWith("ui_");
    }

    /**
     * Bruit éphémère : accusés UI fixes + négociation / intention de tâche UI
     * (reformulations LLM comprises autant que possible).
     */
    public static boolean isNoise(String content) {
        if (content == null) return true;
        String t = content.trim();
        if (t.isEmpty()) return true;
        String f = fold(t);

        if (isUiAckNoise(f)) return true;
        if (isUiTaskNoise(f)) return true;
        return false;
    }

    /**
     * Liste blanche : sujet « à reprendre » qui mérite le permanent.
     * <ul>
     *   <li>Intention (radical) seule → OK (« Fait moi penser à … »)</li>
     *   <li>Ancre temporelle seule → NON (météo demain)</li>
     *   <li>Ancre + intention → OK</li>
     * </ul>
     */
    public static boolean isDurablePending(String content) {
        if (content == null) return false;
        String t = content.trim();
        if (t.length() < 8) return false;
        if (isNoise(t)) return false;
        String f = fold(t);
        // Intention (radical) = rappel / report — ancre temporelle seule = non
        // (évite « météo pour demain »). Temps + intention couvert par l'intention.
        return INTENT_STEM.matcher(f).find();
    }

    /** Exposé pour tests / diag : ancre temporelle sans intention. */
    static boolean hasTimeAnchorOnly(String content) {
        if (content == null) return false;
        String f = fold(content);
        return TIME_ANCHOR.matcher(f).find() && !INTENT_STEM.matcher(f).find();
    }

    /** Fait / décision de session promouvable (pas de bruit UI). */
    public static boolean isDurableSessionItem(String content) {
        if (content == null) return false;
        String t = content.trim();
        if (t.length() < 6) return false;
        return !isNoise(t);
    }

    /**
     * Même intention pending sous deux formulations
     * (ex. résumé LLM vs tour user « Fait moi penser à … »).
     */
    public static boolean samePendingIntent(String a, String b) {
        if (a == null || b == null) return false;
        String ca = pendingCore(a);
        String cb = pendingCore(b);
        if (ca.isEmpty() || cb.isEmpty()) return false;
        if (ca.equals(cb)) return true;
        if (ca.contains(cb) || cb.contains(ca)) return true;
        // « lave vaisselle » vs « lavevaisselle »
        String nas = ca.replace(" ", "");
        String nbs = cb.replace(" ", "");
        if (nas.equals(nbs) || nas.contains(nbs) || nbs.contains(nas)) return true;
        return tokenOverlap(ca, cb) >= 0.72f;
    }

    /**
     * Déduplique une liste de pending : garde la formulation la plus longue
     * (souvent le tour user explicite).
     */
    public static void dedupePendingList(java.util.List<String> items) {
        if (items == null || items.size() < 2) return;
        java.util.ArrayList<String> kept = new java.util.ArrayList<>();
        for (String item : items) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;
            int match = -1;
            for (int i = 0; i < kept.size(); i++) {
                if (samePendingIntent(kept.get(i), trimmed)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                kept.add(trimmed);
            } else if (trimmed.length() > kept.get(match).length()) {
                kept.set(match, trimmed);
            }
        }
        items.clear();
        items.addAll(kept);
    }

    /** Noyau sémantique : retire enveloppes « fait moi penser / rappel de … ». */
    static String pendingCore(String text) {
        String f = fold(text);
        f = f.replaceAll(
                "^(fait|fais)\\s+moi\\s+pens\\w*\\s+(a|de)\\s+", "");
        f = f.replaceAll("^pens\\w*\\s+(a|de)\\s+(moi\\s+)?", "");
        f = f.replaceAll("^rappel\\w*(\\s*-?\\s*moi)?\\s+(de\\s+|d\\s+)?", "");
        f = f.replaceAll("^(yannick|pegase)\\s+(veut\\s+etre\\s+)?rappel\\w*\\s+(de\\s+)?", "");
        f = f.replaceAll("\\s+", " ").trim();
        return f;
    }

    private static float tokenOverlap(String a, String b) {
        java.util.HashSet<String> ta = tokens(a);
        java.util.HashSet<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0f;
        int inter = 0;
        for (String t : ta) {
            if (tb.contains(t)) inter++;
        }
        int union = ta.size() + tb.size() - inter;
        return union == 0 ? 0f : (float) inter / (float) union;
    }

    private static java.util.HashSet<String> tokens(String folded) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (String p : folded.split("\\s+")) {
            if (p.length() >= 3) out.add(p);
        }
        return out;
    }

    private static boolean isUiAckNoise(String f) {
        if (f.startsWith("clic envoye")
                || f.contains("clic envoye sur")
                || f.startsWith("tap envoye")
                || f.contains("j ai clique sur")
                || f.contains("jai clique sur")
                || f.contains("je clique sur")) {
            return true;
        }
        if (f.startsWith("defilement effectue")
                || f.equals("texte saisi")
                || f.equals("texte saisi.")
                || f.equals("retour arriere")
                || f.equals("retour arriere.")) {
            return true;
        }
        if (f.startsWith("action ui ")
                || f.startsWith("ui_action")) {
            return true;
        }
        if (f.length() <= 80 && (f.startsWith("ok, ") || f.startsWith("ok "))
                && (f.contains(" clique") || f.contains(" ouvert") || f.contains(" lance"))) {
            return true;
        }
        return false;
    }

    /**
     * Intentions / négociations de tâche UI (une seule interaction) — pas un souvenir.
     * Couvre aussi « Yannick veut cliquer sur … » ([session]).
     */
    private static boolean isUiTaskNoise(String f) {
        if (f.contains("view_id") || f.contains("viewid") || f.contains("resource-id")
                || f.contains("resource_id") || f.contains("identifiant exact")
                || f.contains("identifiant de la vue")
                || f.contains("identifiant de vue")
                || f.contains("besoin de l identifiant")
                || f.contains("necessite l identifiant")
                || f.contains("demande cet identifiant")
                || f.contains("deja demande cet identifiant")
                || f.contains("deja demande l identifiant")
                || f.contains("a deja demande")) {
            return true;
        }
        if (f.contains("veut cliquer")
                || f.contains("veut taper")
                || f.contains("veut appuyer")
                || f.contains("intention de clic")
                || f.contains("en attente du clic")
                || f.contains("en attente de la cible")
                || f.contains("tache ui")
                || f.contains("action copilote")) {
            return true;
        }
        // Intention de clic courte : « … cliquer sur X » sans marqueur durable
        if (f.contains("cliquer sur") || f.contains("clique sur") || f.contains("appuie sur")) {
            if (f.length() <= 140) return true;
        }
        return false;
    }

    static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace('´', '\'');
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'à': case 'á': case 'â': case 'ä': sb.append('a'); break;
                case 'è': case 'é': case 'ê': case 'ë': sb.append('e'); break;
                case 'ì': case 'í': case 'î': case 'ï': sb.append('i'); break;
                case 'ò': case 'ó': case 'ô': case 'ö': sb.append('o'); break;
                case 'ù': case 'ú': case 'û': case 'ü': sb.append('u'); break;
                case 'ç': sb.append('c'); break;
                case '\'': sb.append(' '); break;
                default: sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
