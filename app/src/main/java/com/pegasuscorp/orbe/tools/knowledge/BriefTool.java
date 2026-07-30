package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolTag;

import com.pegasuscorp.orbe.tools.ToolResult;

import com.pegasuscorp.orbe.tools.Tool;
import com.pegasuscorp.orbe.tools.ToolCallback;

import android.content.Context;
import android.text.TextUtils;

import com.pegasuscorp.orbe.chat.FallbackChatBackend;
import com.pegasuscorp.orbe.chat.MultiProviderBackend;
import com.pegasuscorp.orbe.prefetch.PrefetchCache;
import com.pegasuscorp.orbe.routines.CustomRoutineStore;
import com.pegasuscorp.orbe.voice.SpeechInputNormalizer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Agrège le brief du matin depuis {@link PrefetchCache} uniquement — 0 appel réseau.
 * Section diag : uniquement si le cache est fiable et hors backend fallback — jamais improvisée.
 */
public final class BriefTool implements Tool {

    /** Dernier brief servi (session) — pour « plus de détail » / brief({}) vide. */
    public static final String KEY_BRIEF_LAST = "brief_last";
    public static final long TTL_BRIEF_LAST_MS = PrefetchCache.TTL_DIAG_SESSION_MS;

    @Override
    public String id() {
        return "brief";
    }

    @Override
    public ToolTag tag() {
        return ToolTag.BRIEF;
    }

    @Override
    public String description() {
        return "brief(action:\"brief\"|\"detail\"|\"add\"|\"list\", query?:str, type?:str, label?:str, ttlDays?:int) — "
                + "Brief du matin depuis le cache local (météo, boucherie, NASA, diag, routines). "
                + "action=brief : uniquement sur demande explicite (« brief du matin », "
                + "« résume ma journée », « qu'est-ce que j'ai aujourd'hui »). "
                + "NE PAS rappeler brief() si l'utilisateur demande « plus de détail » ou "
                + "« développe » après un brief — répondre en prose depuis le cache disponible. "
                + "Rappeler brief() uniquement sur une nouvelle demande explicite de brief. "
                + "params vides après un brief récent → prose enrichie depuis le cache, "
                + "pas un nouveau brief. Jamais de réseau. "
                + "Ne jamais inventer une section diag absente du cache "
                + "(surtout sur backend fallback / 120b). "
                + "action=add : ajoute une routine custom. action=list : liste les routines.";
    }

    @Override
    public void execute(Context ctx, JSONObject params, ToolCallback cb) {
        try {
            // params vides + brief récent → prose cache, pas repeated_action brief({})
            if (isEmptyBriefParams(params) && hasRecentBrief(ctx)) {
                cb.onSuccess(ToolResult.text(composeBriefDetail(ctx)));
                return;
            }

            String action = resolveAction(params);
            switch (action) {
                case "add":
                case "add_routine":
                case "ajoute":
                    cb.onSuccess(ToolResult.text(addRoutine(ctx, params)));
                    break;
                case "list":
                case "routines":
                    cb.onSuccess(ToolResult.text(listRoutines(ctx)));
                    break;
                case "detail":
                case "details":
                case "detaille":
                case "détaillé":
                case "plus":
                case "developpe":
                case "développe":
                    cb.onSuccess(ToolResult.text(composeBriefDetail(ctx)));
                    break;
                case "brief":
                case "morning":
                case "matin":
                case "today":
                case "":
                default:
                    cb.onSuccess(ToolResult.text(composeBrief(ctx)));
                    break;
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "brief impossible" : e.getMessage();
            cb.onError("Brief : " + msg);
        }
    }

    /** params absents / {} / action vide sans autre champ utile. */
    public static boolean isEmptyBriefParams(JSONObject params) {
        if (params == null || params.length() == 0) return true;
        String action = params.optString("action", "").trim();
        if (!action.isEmpty()) return false;
        return TextUtils.isEmpty(params.optString("query", ""))
                && TextUtils.isEmpty(params.optString("utterance", ""))
                && TextUtils.isEmpty(params.optString("type", ""))
                && TextUtils.isEmpty(params.optString("label", ""));
    }

    /**
     * Normalise action / alias / texte libre. Vide → brief (sauf détail détecté dans query).
     */
    static String resolveAction(JSONObject params) {
        if (params == null) return "brief";
        String raw = params.optString("action", "").trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            raw = params.optString("query", "").trim().toLowerCase(Locale.ROOT);
        }
        if (raw.isEmpty()) {
            raw = params.optString("utterance", "").trim().toLowerCase(Locale.ROOT);
        }
        if (raw.isEmpty()) {
            raw = params.optString("type", "").trim().toLowerCase(Locale.ROOT);
        }
        switch (raw) {
            case "add":
            case "add_routine":
            case "ajoute":
                return "add";
            case "list":
            case "routines":
                return "list";
            case "detail":
            case "details":
            case "detaille":
            case "détaillé":
            case "plus":
            case "developpe":
            case "développe":
                return "detail";
            case "brief":
            case "morning":
            case "matin":
            case "today":
                return "brief";
            default:
                break;
        }
        if (raw.isEmpty()) return "brief";
        String fold = SpeechInputNormalizer.fold(raw).replace('\'', ' ')
                .replace('’', ' ');
        if (looksLikeBriefDetailFollowUp(fold)) return "detail";
        if ((fold.contains("ajoute") || fold.contains("ajouter"))
                && fold.contains("routine")) {
            return "add";
        }
        if ((fold.contains("liste") || fold.contains("lister"))
                && fold.contains("routine")) {
            return "list";
        }
        return "brief";
    }

    public static boolean hasRecentBrief(Context ctx) {
        return !TextUtils.isEmpty(PrefetchCache.get(ctx, KEY_BRIEF_LAST, TTL_BRIEF_LAST_MS));
    }

    /**
     * Backend de repli runtime (autre provider / modèle que le premier de la chaîne) :
     * la section diag est omise pour que le modèle n'improvise pas.
     */
    public static boolean isOnFallbackBackend(Context ctx) {
        if (FallbackChatBackend.isOnFallbackBackend()) return true;
        return MultiProviderBackend.isOnFallbackBackend();
    }

    /** Agrégation cache-only — exposée pour les tests. */
    public static String composeBrief(Context ctx) {
        String brief = composeFromCache(ctx, true);
        if (ctx != null && !TextUtils.isEmpty(brief)
                && !brief.startsWith("Rien de préchargé")) {
            PrefetchCache.put(ctx, KEY_BRIEF_LAST, brief);
        }
        return brief;
    }

    /**
     * Version « plus de détail » : mêmes sources cache, phrases moins tronquées.
     */
    public static String composeBriefDetail(Context ctx) {
        String detailed = composeFromCache(ctx, false);
        if (TextUtils.isEmpty(detailed)
                || detailed.startsWith("Rien de préchargé")) {
            String last = PrefetchCache.get(ctx, KEY_BRIEF_LAST, TTL_BRIEF_LAST_MS);
            if (!TextUtils.isEmpty(last)) {
                return "Voici plus de détail à partir du brief déjà chargé :\n" + last;
            }
            return detailed;
        }
        return detailed;
    }

    private static String composeFromCache(Context ctx, boolean shortPhrases) {
        List<String> lines = new ArrayList<>();

        addIfPresent(lines, PrefetchCache.get(ctx, PrefetchCache.KEY_WEATHER,
                PrefetchCache.TTL_WEATHER_MS));
        addIfPresent(lines, PrefetchCache.get(ctx, PrefetchCache.KEY_BOUCHERIE,
                PrefetchCache.TTL_BOUCHERIE_MS));
        addIfPresent(lines, PrefetchCache.get(ctx, PrefetchCache.KEY_NASA,
                PrefetchCache.TTL_NASA_MS));

        // Diag : skip silencieux sur fallback backend — le 120b ne doit pas la voir
        String diagSection = "";
        if (!isOnFallbackBackend(ctx)) {
            String diag = PrefetchCache.get(ctx, PrefetchCache.KEY_DIAG,
                    PrefetchCache.TTL_DIAG_SESSION_MS);
            if (isReliableDiag(diag)) {
                diagSection = diag.trim();
            }
        }
        if (!TextUtils.isEmpty(diagSection)) {
            lines.add(diagSection);
        }

        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        for (CustomRoutineStore.CustomRoutine r : store.listActive()) {
            String cached = PrefetchCache.get(ctx, PrefetchCache.customKey(r.id),
                    PrefetchCache.TTL_CUSTOM_MS);
            if (!TextUtils.isEmpty(cached)) {
                lines.add(cached.trim());
            } else if (!TextUtils.isEmpty(r.label) && r.type == CustomRoutineStore.Type.REMINDER) {
                lines.add(r.label);
            }
        }

        if (lines.isEmpty()) {
            return "Rien de préchargé pour le moment — relance Orbe le matin "
                    + "pour remplir le brief.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(shortPhrases ? onePhrase(lines.get(i)) : detailPhrase(lines.get(i)));
        }
        return sb.toString().trim();
    }

    /**
     * Diag utilisable dans le brief : vrai bilan cache, pas erreur / backend-fallback.
     * Ne jamais inventer une section absente ou peu fiable.
     */
    static boolean isReliableDiag(String diag) {
        if (TextUtils.isEmpty(diag)) return false;
        String f = diag.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('’', '\'');
        if (f.contains("pas d'archives") || f.contains("pas encore de traces")
                || f.contains("aucune trace disponible")
                || f.contains("aucune trace detaillee")
                || f.contains("aucune donnee pour")
                || f.contains("rien a diagnostiquer") || f.contains("diagnostic impossible")
                || f.contains("seule la session du jour")
                || f.contains("backend fallback") || f.contains("[fallback]")
                || f.trim().equals("fallback") || f.startsWith("erreur :")
                || f.startsWith("erreur:")) {
            return false;
        }
        return true;
    }

    private static void addIfPresent(List<String> lines, String value) {
        if (!TextUtils.isEmpty(value)) {
            lines.add(value.trim());
        }
    }

    /** Une phrase / source : première ligne utile, sans bloc multiligne. */
    static String onePhrase(String raw) {
        if (raw == null) return "";
        String t = raw.trim().replace('\n', ' ').replaceAll("\\s+", " ");
        if (t.length() > 280) {
            t = t.substring(0, 277).trim() + "…";
        }
        return t;
    }

    static String detailPhrase(String raw) {
        if (raw == null) return "";
        String t = raw.trim().replaceAll("[ \\t]+", " ");
        if (t.length() > 800) {
            t = t.substring(0, 797).trim() + "…";
        }
        return t;
    }

    private static String addRoutine(Context ctx, JSONObject params) {
        String utterance = params.optString("utterance", "").trim();
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        CustomRoutineStore.CustomRoutine created;
        if (!utterance.isEmpty()) {
            created = store.addFromVoice(utterance);
            if (created == null) {
                return "Je n'ai pas compris la routine à ajouter. "
                        + "Dis par exemple : « ajoute à ma routine du matin : "
                        + "cherche les résultats F1 ».";
            }
        } else {
            String query = params.optString("query", "").trim();
            if (query.isEmpty()) {
                return "Précise quoi ajouter à la routine du matin.";
            }
            CustomRoutineStore.Type type = parseType(params.optString("type", "WEB_SEARCH"));
            String label = params.optString("label", "").trim();
            Integer ttl = null;
            if (params.has("ttlDays") && !params.isNull("ttlDays")) {
                int d = params.optInt("ttlDays", 0);
                if (d > 0) ttl = d;
            }
            created = store.add(type, query, label, ttl);
        }
        return "OK — j'ai ajouté « " + created.label + " » à ta routine du matin.";
    }

    private static String listRoutines(Context ctx) {
        List<CustomRoutineStore.CustomRoutine> all =
                CustomRoutineStore.getInstance(ctx).listAll();
        if (all.isEmpty()) {
            return "Aucune routine custom pour le moment.";
        }
        StringBuilder sb = new StringBuilder();
        for (CustomRoutineStore.CustomRoutine r : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.active ? "• " : "○ ")
                    .append(r.label)
                    .append(" (").append(r.type.name().toLowerCase()).append(')');
        }
        return sb.toString();
    }

    private static CustomRoutineStore.Type parseType(String raw) {
        try {
            return CustomRoutineStore.Type.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return CustomRoutineStore.Type.WEB_SEARCH;
        }
    }

    /** true si la phrase demande plus de détail après un brief. */
    public static boolean looksLikeBriefDetailFollowUp(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ');
        // "developpe" seul est trop large (« développer un jeu ») — exiger brief
        return f.contains("plus de detail") || f.contains("plus de details")
                || f.contains("plus en detail")
                || (f.contains("developpe") && f.contains("brief"))
                || f.contains("detaille le brief")
                || f.contains("tu peux detailler") || f.contains("c est tout ?")
                || (f.contains("detail") && (f.contains("brief") || f.contains("hier")
                || f.contains("matin") || f.contains("davantage")));
    }
}
