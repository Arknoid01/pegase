package com.pegasuscorp.orbe.voice;

import android.content.Context;

import com.pegasuscorp.orbe.llm.PegasePrompt;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Nettoie et corrige les transcriptions SpeechRecognizer avant envoi au LLM.
 */
public final class SpeechInputNormalizer {

    private static final Pattern LEADING_FILLERS = Pattern.compile(
            "^(?:(?:euh+|ben+|bah+|heu+|alors|bon|voilà|voila|enfin|donc|ok|ouais)[,\\s]+)+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Map<String, String> STT_FIXES = new LinkedHashMap<>();

    static {
        STT_FIXES.put("pegasse", "pégase");
        STT_FIXES.put("pegaze", "pégase");
        STT_FIXES.put("pegas", "pégase");
        STT_FIXES.put("mettreo", "météo");
        STT_FIXES.put("meteo", "météo");
        STT_FIXES.put("météaux", "météo");
        STT_FIXES.put("footbal", "foot");
        STT_FIXES.put("football", "foot");
        STT_FIXES.put("championnat", "championnat");
        STT_FIXES.put("actualite", "actualités");
        STT_FIXES.put("actualites", "actualités");
        STT_FIXES.put("formule un", "formule 1");
        STT_FIXES.put("f1", "formule 1");
        STT_FIXES.put("spotifie", "spotify");
        STT_FIXES.put("spotifi", "spotify");
        STT_FIXES.put("whatsapp", "whatsapp");
        STT_FIXES.put("minuteur", "minuteur");
        STT_FIXES.put("discuter", "discuter");
        STT_FIXES.put("qu est ce", "qu'est-ce");
        STT_FIXES.put("quest ce", "qu'est-ce");
        STT_FIXES.put("est ce que", "est-ce que");
        STT_FIXES.put("est ce", "est-ce");
        STT_FIXES.put("peux tu", "peux-tu");
        STT_FIXES.put("veux tu", "veux-tu");
        STT_FIXES.put("s il te plait", "s'il te plaît");
        STT_FIXES.put("sil te plait", "s'il te plaît");
        STT_FIXES.put("stp", "s'il te plaît");
        STT_FIXES.put("apres demain", "après-demain");
        STT_FIXES.put("aujourd hui", "aujourd'hui");
        STT_FIXES.put("aujourdhui", "aujourd'hui");
        STT_FIXES.put("parapluie", "parapluie");
        STT_FIXES.put("dernier match", "dernier match");
        STT_FIXES.put("prochain match", "prochain match");
        STT_FIXES.put("quoi de neuf", "quoi de neuf");
        STT_FIXES.put("choses a faire", "choses à faire");
        STT_FIXES.put("liste a faire", "liste à faire");
        STT_FIXES.put("bloc note", "bloc-notes");
        STT_FIXES.put("saint germain", "Saint-Germain");
        STT_FIXES.put("paris saint germain", "Paris Saint-Germain");
    }

    private SpeechInputNormalizer() {}

    public static String normalize(String raw) {
        return normalize(null, raw);
    }

    public static String normalize(Context context, String raw) {
        if (raw == null) return "";
        String out = raw.trim();
        if (out.isEmpty()) return "";

        if (context != null) {
            out = VoiceCorrectionStore.getInstance(context).apply(out);
        }

        out = out.replace('\u00A0', ' ');
        out = LEADING_FILLERS.matcher(out).replaceAll("").trim();
        out = out.replaceAll("\\s+", " ");

        String lower = out.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> fix : STT_FIXES.entrySet()) {
            if (lower.contains(fix.getKey())) {
                lower = lower.replace(fix.getKey(), fix.getValue().toLowerCase(Locale.ROOT));
            }
        }
        out = capitalizeFirst(lower);

        out = PegasePrompt.fixFrenchOralSpacing(out);
        return out.trim();
    }

    /** Score de qualité pour choisir la meilleure hypothèse STT. */
    public static double qualityScore(String normalized) {
        if (normalized == null || normalized.isEmpty()) return 0;
        double score = normalized.length();
        if (normalized.length() < 3) score -= 5;
        if (normalized.split("\\s+").length >= 2) score += 4;
        if (normalized.matches(".*[éèêëàâäùûüôöîïç].*")) score += 2;
        if (normalized.contains("?")) score += 1;
        return score;
    }

    public static boolean changedMeaningfully(String raw, String normalized) {
        if (raw == null || normalized == null) return false;
        String a = raw.trim().toLowerCase(Locale.ROOT);
        String b = normalized.trim().toLowerCase(Locale.ROOT);
        return !a.equals(b);
    }

    private static String capitalizeFirst(String text) {
        if (text.isEmpty()) return text;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Pour comparaisons sans accents. */
    public static String fold(String text) {
        if (text == null) return "";
        String n = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }
}
