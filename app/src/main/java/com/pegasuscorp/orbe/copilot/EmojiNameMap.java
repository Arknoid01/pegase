package com.pegasuscorp.orbe.copilot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convertit des noms d'emoji FR (souvent produits par le LLM) en caractères Unicode
 * avant {@code ACTION_SET_TEXT}.
 */
public final class EmojiNameMap {

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        put("smiley qui rigole", "😂");
        put("smiley qui rigole jaune", "😂");
        put("mort de rire", "😂");
        put("mdr", "😂");
        put("rire aux larmes", "😂");
        put("smiley qui pleure", "😢");
        put("smiley triste", "😢");
        put("pleure", "😢");
        put("smiley content", "😊");
        put("smiley heureux", "😊");
        put("sourire", "😊");
        put("smiley clin d oeil", "😉");
        put("clin d oeil", "😉");
        put("coeur", "❤️");
        put("coeurs", "❤️");
        put("heart", "❤️");
        put("feu", "🔥");
        put("fire", "🔥");
        put("pouce", "👍");
        put("pouce leve", "👍");
        put("thumbs up", "👍");
        put("ok hand", "👌");
        put("check", "✅");
        put("valide", "✅");
        put("croix", "❌");
        put("attention", "⚠️");
        put("etoile", "⭐");
        put("soleil", "☀️");
        put("lune", "🌙");
        put("cafe", "☕");
        put("musique", "🎵");
        put("fete", "🎉");
        put("party", "🎉");
        put("bisous", "😘");
        put("bisou", "😘");
        put("amour", "😍");
        put("enerve", "😠");
        put("colere", "😠");
        put("choc", "😱");
        put("surpris", "😮");
        put("pense", "🤔");
        put("reflexion", "🤔");
        put("clap", "👏");
        put("applaudissements", "👏");
        put("priere", "🙏");
        put("merci", "🙏");
        put("rocket", "🚀");
        put("fusee", "🚀");
    }

    private EmojiNameMap() {}

    private static void put(String name, String emoji) {
        NAMES.put(fold(name), emoji);
    }

    /** Remplace un libellé entier ou des fragments connus par l'emoji. */
    public static String expand(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String trimmed = raw.trim();
        String key = fold(trimmed);
        String emoji = NAMES.get(key);
        if (emoji != null) return emoji;
        // Préfixe optionnel « emoji … »
        if (key.startsWith("emoji ")) {
            emoji = NAMES.get(key.substring(6).trim());
            if (emoji != null) return emoji;
        }
        // Remplacements inline (ex. « salut smiley qui rigole »)
        String out = trimmed;
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            String phrase = e.getKey();
            if (phrase.length() < 4) continue;
            out = out.replaceAll("(?i)\\b" + Pattern.quote(phrase) + "\\b",
                    Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
