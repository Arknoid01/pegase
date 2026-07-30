package com.pegasuscorp.orbe.contextstore;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEditResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commandes vocales pour les contextes nommés (charge / décharge / liste / recherche).
 */
public final class ContextEditor {

    public interface Callback {
        void onResult(MemoryEditResult result);
    }

    private static final Pattern LOAD = Pattern.compile(
            "(?i)(?:charge|charger|ouvre|ouvrir)\\s+(?:le\\s+|la\\s+|les\\s+)?"
                    + "(?:contexte\\s+)?(.+?)\\s*$");

    private static final Pattern UNLOAD_ALL = Pattern.compile(
            "(?i)d[ée]charge(?:r)?\\s+tout\\b|lib[èe]re(?:r)?\\s+(?:les\\s+)?contextes?");

    private static final Pattern UNLOAD_ONE = Pattern.compile(
            "(?i)d[ée]charge(?:r)?\\s+(?:le\\s+|la\\s+)?(?:contexte\\s+)?(.+)\\s*$");

    private static final Pattern LIST = Pattern.compile(
            "(?i)(?:montre|montre[- ]moi|liste|lister|quels?|affiche)\\s+"
                    + "(?:mes\\s+)?(?:fichiers?\\s+de\\s+)?contextes?");

    private static final Pattern LOADED_STATUS = Pattern.compile(
            "(?i)(?:quel(?:s)?\\s+contexte(?:s)?\\s+(?:est|sont)\\s+charg|"
                    + "contexte(?:s)?\\s+(?:actif|actifs|charg[ée]s?))");

    /** « cherche dans mes fichiers ce qui parle de Tavily » */
    private static final Pattern SEARCH = Pattern.compile(
            "(?i)(?:cherche|rechercher|trouve|trouver)\\s+"
                    + "(?:dans\\s+(?:mes\\s+)?(?:fichiers?|contextes?)\\s+)?"
                    + "(?:ce\\s+qui\\s+(?:parle|traite)\\s+de\\s+|qui\\s+parle\\s+de\\s+)?"
                    + "(.+)\\s*$");

    private static final Pattern SEARCH_WHERE = Pattern.compile(
            "(?i)(?:ou|où)\\s+(?:en\\s+suis[- ]je|j'en\\s+suis)\\s+(?:avec\\s+)?(.+)\\s*$");

    private final ContextualFileStore store;
    private final Context appContext;

    public ContextEditor(Context context) {
        appContext = context.getApplicationContext();
        store = ContextualFileStore.getInstance(appContext);
    }

    public static boolean looksLikeContextCommand(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        String fold = ContextualFileStore.fold(t);
        if (LIST.matcher(t).find() || LOADED_STATUS.matcher(t).find()) return true;
        if (UNLOAD_ALL.matcher(t).find()) return true;
        if (fold.contains("decharge") || fold.contains("decharger")) return true;
        if (looksLikeSearch(fold, t)) return true;
        if (!(fold.contains("charge") || fold.contains("charger")
                || fold.contains("ouvre") || fold.contains("ouvrir"))) {
            return false;
        }
        if (fold.contains("batterie") || fold.contains("telephone")
                || fold.contains("portable") || fold.contains("niveau")) {
            return false;
        }
        if (fold.contains("contexte")) return true;
        Matcher m = LOAD.matcher(t);
        if (!m.find()) return false;
        return targetLooksLikeContext(m.group(1));
    }

    private static boolean looksLikeSearch(String fold, String original) {
        if (SEARCH_WHERE.matcher(original).find()) return true;
        boolean wantsSearch = fold.contains("cherche") || fold.contains("rechercher")
                || fold.contains("trouve") || fold.contains("trouver");
        if (!wantsSearch) return false;
        return fold.contains("fichier") || fold.contains("contexte")
                || fold.contains("parle de") || fold.contains("dans mes");
    }

    private static boolean targetLooksLikeContext(String target) {
        if (target == null) return false;
        String f = ContextualFileStore.fold(target);
        f = f.replaceFirst("^(le|la|les|du|de|des|contexte)\\s+", "").trim();
        return f.equals("orion") || f.equals("boucherie") || f.equals("fableris")
                || f.equals("olympos") || f.equals("irisforge") || f.equals("pegase")
                || f.contains("orion") || f.contains("boucherie") || f.contains("fableris")
                || f.contains("olympos") || f.contains("irisforge") || f.contains("pegase")
                || f.contains("contexte");
    }

    public void process(String userText, Callback callback) {
        MemoryEditResult result = tryEdit(userText);
        if (result == null) {
            callback.onResult(MemoryEditResult.notMemoryEdit());
            return;
        }
        callback.onResult(result);
    }

    private MemoryEditResult tryEdit(String text) {
        String trimmed = text.trim();

        if (LIST.matcher(trimmed).find()) {
            return MemoryEditResult.applied("Contextes", store.formatListForSpeech());
        }
        if (LOADED_STATUS.matcher(trimmed).find()) {
            return MemoryEditResult.applied("Contextes actifs", store.formatLoadedForSpeech());
        }
        if (UNLOAD_ALL.matcher(trimmed).find()) {
            return MemoryEditResult.applied("Déchargé", store.unload("tout"));
        }

        Matcher where = SEARCH_WHERE.matcher(trimmed);
        if (where.find()) {
            String topic = cleanTarget(where.group(1));
            // Charge le contexte projet si connu, sinon recherche
            List<String> loaded = store.loadMultiple(splitTargets(topic));
            if (!loaded.isEmpty()) {
                return MemoryEditResult.applied(
                        "Chargé : " + loaded.get(0),
                        "J'ai chargé " + loaded.get(0) + ". "
                                + "Regarde la section État ou Plan d'action.");
            }
            return runSearch(topic);
        }

        if (looksLikeSearch(ContextualFileStore.fold(trimmed), trimmed)) {
            Matcher sm = SEARCH.matcher(trimmed);
            if (sm.find()) {
                String q = cleanSearchQuery(sm.group(1));
                if (!q.isEmpty()) return runSearch(q);
            }
        }

        Matcher um = UNLOAD_ONE.matcher(trimmed);
        if (um.find()) {
            String target = cleanTarget(um.group(1));
            if ("tout".equals(ContextualFileStore.fold(target))) {
                return MemoryEditResult.applied("Déchargé", store.unload("tout"));
            }
            return MemoryEditResult.applied("Déchargé", store.unload(target));
        }

        Matcher lm = LOAD.matcher(trimmed);
        if (lm.find()) {
            List<String> parts = splitTargets(cleanTarget(lm.group(1)));
            if (parts.isEmpty()) {
                return MemoryEditResult.failed("Précise quel contexte charger.");
            }
            List<String> ok = store.loadMultiple(parts);
            if (ok.isEmpty()) {
                return MemoryEditResult.failed(
                        "Je n'ai pas trouvé ce fichier de contexte. Dis « montre mes contextes ».");
            }
            if (ok.size() == 1) {
                return MemoryEditResult.applied(
                        "Chargé : " + ok.get(0),
                        "Contexte " + ok.get(0) + " chargé. Je l'aurai en tête pour la suite.");
            }
            String joined = String.join(" et ", ok);
            return MemoryEditResult.applied(
                    "Chargés : " + joined,
                    "Contextes " + joined + " chargés.");
        }

        return null;
    }

    private MemoryEditResult runSearch(String query) {
        ContextSearchIndex idx = ContextSearchIndex.getInstance(appContext);
        // Au premier tour, index sync si vide (évite course avec indexAllAsync)
        if (idx.search(query, 1, 0f).isEmpty()) {
            idx.indexAllNow();
        }
        List<ContextSearchIndex.Hit> hits = idx.search(query, 3, ContextSearchIndex.MIN_SCORE);
        String speech = idx.formatSearchForSpeech(hits, query);
        return MemoryEditResult.applied(
                hits.isEmpty() ? "Aucun résultat" : hits.size() + " fichier(s)",
                speech);
    }

    private static String cleanSearchQuery(String raw) {
        String t = cleanTarget(raw);
        t = t.replaceAll("(?i)^(dans\\s+(mes\\s+)?(fichiers?|contextes?)\\s+)", "");
        t = t.replaceAll("(?i)^(ce\\s+qui\\s+(parle|traite)\\s+de\\s+)", "");
        t = t.replaceAll("(?i)^(qui\\s+parle\\s+de\\s+)", "");
        return t.trim();
    }

    private static String cleanTarget(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceAll("(?i)\\s+(s'il te pla[iî]t|stp|please)\\s*$", "");
        t = t.replaceAll("[.!?]+\\s*$", "");
        return t.trim();
    }

    static List<String> splitTargets(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String normalized = raw.replace(',', ' ')
                .replaceAll("(?i)\\s+et\\s+", "|||")
                .replaceAll("\\s+", " ")
                .trim();
        for (String part : normalized.split("\\|\\|\\|")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        if (out.isEmpty() && !raw.trim().isEmpty()) out.add(raw.trim());
        return out;
    }
}
