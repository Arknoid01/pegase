package com.pegasuscorp.orbe.sms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Règles du ménage SMS — pur Java, testable sans Android.
 *
 * Candidat = conversation qui ne contient qu'UN seul SMS, reçu, d'un expéditeur
 * absent des contacts (le filtrage « 1 message / reçu / hors contacts » est fait
 * par {@link SmsInbox}). Ici on range chaque candidat dans une catégorie, et on
 * prépare / lit le tri par le LLM.
 */
public final class SmsSpamRules {

    static final int BODY_MAX_CHARS = 280;

    public enum Category {
        /** Numéro de téléphone classique, hors contacts → coché d'office. */
        LIKELY_SPAM,
        /** Expéditeur en lettres (« AMELI ») ou numéro court (36xxx) → souvent un vrai service. */
        SERVICE
    }

    public enum Verdict { NONE, SPAM, LEGIT, UNSURE }

    public static final class Candidate {
        public final long threadId;
        public final String address;
        public final String body;
        public final long date;
        public Category category;
        public Verdict verdict = Verdict.NONE;
        public String reason = "";
        public boolean checked;

        public Candidate(long threadId, String address, String body, long date) {
            this.threadId = threadId;
            this.address = address != null ? address : "";
            this.body = body != null ? body : "";
            this.date = date;
        }
    }

    private SmsSpamRules() {}

    /** Expéditeur en lettres ou numéro court (≤ 6 chiffres) : typique des vrais services. */
    public static boolean isServiceSender(String address) {
        if (address == null) return false;
        String a = address.trim();
        for (int i = 0; i < a.length(); i++) {
            if (Character.isLetter(a.charAt(i))) return true;
        }
        int digits = 0;
        for (int i = 0; i < a.length(); i++) {
            if (Character.isDigit(a.charAt(i))) digits++;
        }
        return digits > 0 && digits <= 6;
    }

    public static Category categorize(Candidate c) {
        // Pas de délai de grâce pour les récents : les codes de validation viennent
        // d'expéditeurs en lettres / numéros courts, déjà rangés dans SERVICE.
        if (isServiceSender(c.address)) return Category.SERVICE;
        return Category.LIKELY_SPAM;
    }

    /** Range le candidat et fixe la case par défaut (seuls les spams probables sont cochés). */
    public static void classify(Candidate c) {
        c.category = categorize(c);
        c.checked = c.category == Category.LIKELY_SPAM;
    }

    /** Applique un verdict LLM : il prime sur les règles, sauf « doute » qui garde le choix des règles. */
    public static void applyVerdict(Candidate c, Verdict v, String reason) {
        c.verdict = v;
        c.reason = reason != null ? reason : "";
        if (v == Verdict.SPAM) c.checked = true;
        else if (v == Verdict.LEGIT) c.checked = false;
    }

    /**
     * Assez sûre pour supprimer toute seule : uniquement un « spam » explicite du LLM.
     * Un spam probable (règle « 1 SMS / hors contacts ») ou un « doute » restent
     * pour le ménage manuel.
     */
    public static boolean isSureToDelete(Verdict v) {
        return v == Verdict.SPAM;
    }

    public static List<Long> sureSpamThreadIds(List<Candidate> candidates) {
        ArrayList<Long> ids = new ArrayList<>();
        if (candidates == null) return ids;
        for (Candidate c : candidates) {
            if (isSureToDelete(c.verdict)) ids.add(c.threadId);
        }
        return ids;
    }

    public static String buildLlmPrompt(List<Candidate> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu tries des SMS reçus en France. Pour chacun, dis s'il s'agit d'un spam ")
          .append("(arnaque, faux colis, faux CPF / Ameli / impôts, phishing, pub non sollicitée, ")
          .append("« maman j'ai changé de numéro »…) ou d'un message légitime ")
          .append("(code de validation, vraie livraison, rendez-vous, banque, personne réelle).\n")
          .append("Réponds UNIQUEMENT en JSON valide, sans markdown, sous la forme : ")
          .append("[{\"id\":<id>,\"verdict\":\"spam|legit|doute\",\"raison\":\"<6 mots max>\"}]\n\n");
        for (Candidate c : batch) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", c.threadId);
                o.put("expediteur", c.address);
                o.put("texte", truncate(c.body, BODY_MAX_CHARS));
            } catch (Exception ignored) {
            }
            sb.append(o).append('\n');
        }
        return sb.toString();
    }

    /** threadId → [verdict, raison]. Ignore tout ce qui n'est pas lisible. */
    public static Map<Long, String[]> parseLlmVerdicts(String text) {
        Map<Long, String[]> out = new HashMap<>();
        if (text == null) return out;
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        try {
            JSONArray arr = new JSONArray(text.substring(start, end + 1));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("id")) continue;
                out.put(o.optLong("id"), new String[]{
                        o.optString("verdict", ""), o.optString("raison", "")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static Verdict verdictOf(String raw) {
        if (raw == null) return Verdict.NONE;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("spam")) return Verdict.SPAM;
        if (v.startsWith("legit") || v.startsWith("légit")) return Verdict.LEGIT;
        if (v.startsWith("doute") || v.startsWith("unsure")) return Verdict.UNSURE;
        return Verdict.NONE;
    }

    static String truncate(String s, int max) {
        String flat = s.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
