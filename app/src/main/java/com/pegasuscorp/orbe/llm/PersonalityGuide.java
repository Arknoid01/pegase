package com.pegasuscorp.orbe.llm;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    private static final String[] BANNED_PHRASES = {
            "n'hésite pas",
            "nhesite pas",
            "il est important de noter",
            "en tant qu'assistant",
            "en tant qu assistant",
            "je suis là pour t'aider",
            "je suis la pour t aider",
            "cela dépend de plusieurs facteurs",
            "cela depend de plusieurs facteurs",
            "excellente question",
            "super question",
            "n'hésitez pas",
    };

    private PersonalityGuide() {}

    /** Bloc à injecter dans les prompts système (chat, bureau, Orion). */
    public static String promptBlock(Context context) {
        String body = load(context);
        if (body == null || body.trim().isEmpty()) return "";
        body = body.trim();
        if (body.length() > MAX_INJECT_CHARS) {
            body = body.substring(0, MAX_INJECT_CHARS - 1).trim() + "\n…";
        }
        return "\n=== Personnalité Pégase (référence) ===\n" + body + "\n";
    }

    /** Détecte une tournure « assistant générique » — utile pour tests et diag. */
    public static boolean containsBannedPhrase(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String fold = text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('’', '\'');
        for (String banned : BANNED_PHRASES) {
            if (fold.contains(banned)) return true;
        }
        return false;
    }

    public static void clearCacheForTests() {
        cachedBody = null;
    }

    static String load(Context context) {
        if (cachedBody != null) return cachedBody;
        String fromDisk = readDisk(context);
        if (fromDisk != null && !fromDisk.trim().isEmpty()) {
            cachedBody = fromDisk;
            return cachedBody;
        }
        String fromAsset = readAsset(context);
        cachedBody = fromAsset != null ? fromAsset : "";
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
