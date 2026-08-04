package com.pegasuscorp.orbe.llm;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Charge {@code pegase-personality.md} — référence unique de ton pour chat, bureau et Orion.
 * Fichier éditable : {@code files/contexts/pegase-personality.md} (seed depuis assets).
 */
public final class PersonalityGuide {

    public static final String FILENAME = "pegase-personality.md";
    private static final String ASSET_PATH = "contexts/" + FILENAME;
    /** Budget prompt — le doc complet tient en dessous ; garde de la marge pour le reste. */
    private static final int MAX_INJECT_CHARS = 6_000;

    private static volatile String cachedBody;
    private static volatile String[] cachedBanned;

    /** Repli si le markdown n'a pas de section liste noire parseable. */
    private static final String[] FALLBACK_BANNED = {
            "n'hésite pas",
            "il est important de noter",
            "en tant qu'assistant",
            "je suis là pour t'aider",
            "cela dépend de plusieurs facteurs",
            "excellente question",
            "super question",
    };

    private PersonalityGuide() {}

    /** Bloc à injecter dans les prompts système (chat, bureau, Orion). */
    public static String promptBlock(Context context) {
        return promptBlock(context, MAX_INJECT_CHARS);
    }

    /** Variante avec plafond chars (ex. budget Groq serré). */
    public static String promptBlock(Context context, int maxChars) {
        String body = load(context);
        if (body == null || body.trim().isEmpty()) return "";
        body = body.trim();
        int cap = maxChars > 0 ? Math.min(maxChars, MAX_INJECT_CHARS) : MAX_INJECT_CHARS;
        if (body.length() > cap) {
            body = body.substring(0, cap - 1).trim() + "\n…";
        }
        return "\n=== Personnalité Pégase (référence) ===\n" + body + "\n";
    }

    /** Détecte une tournure « assistant générique » — utile pour tests et diag. */
    public static boolean containsBannedPhrase(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String fold = foldForMatch(text);
        for (String banned : bannedPhrases()) {
            if (fold.contains(banned)) return true;
        }
        return false;
    }

    /**
     * Retire les tournures bannies avant TTS / affichage oral.
     * Ne remplace pas par une autre phrase — coupe le segment fautif.
     */
    public static String stripBannedPhrases(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        String out = text;
        for (String banned : bannedPhrases()) {
            out = removeIgnoreCase(out, banned);
        }
        out = out.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("^[,.;:!?]+", "").trim();
        out = out.replaceAll("[,.;:!?]+$", "").trim();
        return out;
    }

    static String[] bannedPhrases() {
        if (cachedBanned != null) return cachedBanned;
        String body = cachedBody;
        if (body == null || body.isEmpty()) {
            cachedBanned = FALLBACK_BANNED;
            return cachedBanned;
        }
        cachedBanned = parseBannedFromBody(body);
        return cachedBanned;
    }

    static String[] parseBannedFromBody(String body) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.startsWith("## ") && foldForMatch(t).contains("liste noire")) {
                inSection = true;
                continue;
            }
            if (inSection && t.startsWith("## ")) break;
            if (!inSection || !t.startsWith("- ")) continue;
            String phrase = normalizeBannedBullet(t.substring(2));
            if (!phrase.isEmpty()) out.add(phrase);
        }
        if (out.isEmpty()) return FALLBACK_BANNED;
        return out.toArray(new String[0]);
    }

    private static String normalizeBannedBullet(String raw) {
        if (raw == null) return "";
        String s = raw.replace('«', ' ').replace('»', ' ')
                .replace('…', ' ').replace('!', ' ').trim();
        int paren = s.indexOf('(');
        if (paren > 0) s = s.substring(0, paren).trim();
        return foldForMatch(s).replaceAll("\\s+", " ").trim();
    }

    private static String foldForMatch(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('’', '\'');
    }

    private static String removeIgnoreCase(String text, String needle) {
        if (needle == null || needle.isEmpty()) return text;
        String foldHay = foldForMatch(text);
        String foldNeedle = foldForMatch(needle);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (foldHay.regionMatches(i, foldNeedle, 0, foldNeedle.length())) {
                i += foldNeedle.length();
                continue;
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    public static void clearCacheForTests() {
        cachedBody = null;
        cachedBanned = null;
    }

    static String load(Context context) {
        if (cachedBody != null) return cachedBody;
        String fromDisk = readDisk(context);
        if (fromDisk != null && !fromDisk.trim().isEmpty()) {
            cachedBody = fromDisk;
            bannedPhrases();
            return cachedBody;
        }
        String fromAsset = readAsset(context);
        cachedBody = fromAsset != null ? fromAsset : "";
        bannedPhrases();
        return cachedBody;
    }

    private static String readDisk(Context context) {
        if (context == null) return null;
        try {
            File f = new File(context.getApplicationContext().getFilesDir(),
                    "contexts/" + FILENAME);
            if (!f.isFile()) return null;
            return readStream(new java.io.FileInputStream(f));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readAsset(Context context) {
        if (context == null) return null;
        try (InputStream in = context.getApplicationContext().getAssets().open(ASSET_PATH)) {
            return readStream(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readStream(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
